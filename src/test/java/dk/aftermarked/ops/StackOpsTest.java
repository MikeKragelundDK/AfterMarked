package dk.aftermarked.ops;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.io.StringReader;
import java.io.IOException;
import java.io.UncheckedIOException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPERATIONS — the stack (PLAN §2.7: two replicas, N−1 capacity, graceful shutdown).
 *
 * <p>compose.yaml is the only description of how the pieces fit together: which role
 * the application connects as, which port is public, where the database data lives,
 * and how long a replica may spend shutting down. Each of them carries an invariant
 * that an innocent edit can break without any other test noticing.
 */
class StackOpsTest {

	private static final Map<String, Object> COMPOSE = ProjectFile.yaml("compose.yaml");

	private static final Map<String, Object> DB = ProjectFile.map(COMPOSE, "services", "db");

	private static final Map<String, Object> APP = ProjectFile.map(COMPOSE, "services", "app");

	private static final Map<String, Object> LB = ProjectFile.map(COMPOSE, "services", "lb");

	// -----------------------------------------------------------------------
	// Availability
	// -----------------------------------------------------------------------

	/**
	 * PLAN §2.7: "Capacity is calculated at N−1". If the replica count drops to 1,
	 * there is no N−1 to calculate at, and every deploy becomes downtime instead of a
	 * capacity question.
	 */
	@Test
	void givenTheComposeFile_whenReadingTheAppService_thenItRunsWithTwoReplicas() {
		assertThat(ProjectFile.map(APP, "deploy").get("replicas"))
				.as("two replicas are the precondition for zero-downtime deploys and for the N−1 assumption")
				.isEqualTo(2);
	}

	@Test
	void givenTheComposeFile_whenReadingTheServiceImages_thenEveryPulledImageIsPinnedByDigest() {
		List<String> images = List.of(
				(String) DB.get("image"), (String) LB.get("image"), (String) APP.get("image"));

		assertThat(images.subList(0, 2))
				.as("CLAUDE.md: no version drift — not in the stack around the application either")
				.allSatisfy(image -> assertThat(image).contains("@sha256:"));
		assertThat(images.getLast())
				.as("the app image is built locally from the Dockerfile and therefore has no digest")
				.isEqualTo("theticket:local");
	}

	@Test
	void givenTheComposeFile_whenReadingTheStartupOrder_thenEachServiceWaitsForHealthAndNotJustForStart() {
		assertThat(ProjectFile.map(APP, "depends_on", "db").get("condition"))
				.as("service_started would let Flyway hit a Postgres that is not yet accepting connections")
				.isEqualTo("service_healthy");
		assertThat(ProjectFile.map(LB, "depends_on", "app").get("condition"))
				.as("the load balancer must not send traffic to replicas that are not ready")
				.isEqualTo("service_healthy");
	}

	@Test
	void givenTheComposeFile_whenReadingTheAppHealthcheck_thenItProbesReadinessOnTheManagementPort() {
		List<String> command = (List<String>) ProjectFile.map(APP, "healthcheck").get("test");
		String test = String.join(" ", command);

		assertThat(test)
				.as("readiness — not liveness and not the front page. Only readiness knows whether the replica can take traffic")
				.contains("/actuator/health/readiness")
				.contains("8081");
		assertThat(test)
				.as("the healthcheck must not go through the public port; then it measures the load balancer, not the app")
				.doesNotContain(":8080");
	}

	// -----------------------------------------------------------------------
	// Graceful shutdown — a cross-cutting invariant against application.properties
	// -----------------------------------------------------------------------

	/**
	 * The mistake this test exists for: someone raises the Spring shutdown window
	 * because a slow provider call has to be allowed to finish — and forgets that
	 * Docker still sends SIGKILL after {@code stop_grace_period}. Then graceful
	 * shutdown is a setting written in a file, and not a behaviour in production.
	 */
	@Test
	void givenTheShutdownWindows_whenComparingThem_thenTheContainerGracePeriodOutlastsTheSpringOne() {
		long stopGracePeriod = ProjectFile.seconds((String) APP.get("stop_grace_period"));
		long springTimeout = ProjectFile.seconds(springProperty("spring.lifecycle.timeout-per-shutdown-phase"));

		assertThat(springTimeout)
				.as("without an explicit timeout Spring waits 30s, which is more than the Docker default of 10s")
				.isPositive();
		assertThat(stopGracePeriod)
				.as("""
						Docker has to give Spring time to finish in-flight requests. \
						If stop_grace_period (%ds) is not greater than \
						spring.lifecycle.timeout-per-shutdown-phase (%ds), the JVM is \
						SIGKILLed in the middle of a checkout.""".formatted(stopGracePeriod, springTimeout))
				.isGreaterThan(springTimeout);
	}

	@Test
	void givenTheApplicationProperties_whenReadingTheShutdownMode_thenTheApplicationShutsDownGracefullyAtAll() {
		assertThat(springProperty("server.shutdown"))
				.as("without graceful, connections are cut hard on every deploy")
				.isEqualTo("graceful");
	}

	// -----------------------------------------------------------------------
	// Tenant isolation and attack surface
	// -----------------------------------------------------------------------

	/**
	 * PLAN §2.7 rule 6: the migration user owns the schema and bypasses RLS. If the
	 * application connects as that user, the whole tenant isolation is switched off —
	 * and nothing else in the system would notice.
	 */
	@Test
	void givenTheComposeEnvironment_whenReadingTheDatabaseUsers_thenTheAppDoesNotConnectAsTheMigrationUser() {
		Map<String, Object> env = ProjectFile.map(APP, "environment");

		assertThat(env.get("THETICKET_DB_USER"))
				.as("the runtime role — without ownership, without BYPASSRLS")
				.isEqualTo("theticket_runtime")
				.isNotEqualTo(env.get("THETICKET_DB_MIGRATION_USER"));
		assertThat(env.get("THETICKET_DB_MIGRATION_USER"))
				.as("Flyway connects as the owner on its own connection")
				.isEqualTo("theticket");
	}

	/**
	 * The management port carries the Prometheus metrics and is protected by a login
	 * only. It must never be published to the host — and the database not at all.
	 */
	@Test
	void givenTheComposeFile_whenReadingThePublishedPorts_thenOnlyTheLoadBalancerPortIsPublished() {
		assertThat(DB).as("Postgres must not be reachable from the host").doesNotContainKey("ports");
		assertThat(APP).as("the replicas are reached only through the load balancer").doesNotContainKey("ports");
		assertThat((List<String>) LB.get("ports"))
				.as("one public entrance")
				.containsExactly("8080:80");
		assertThat(ProjectFile.text("compose.yaml"))
				.as("8081 is the management port and must not be exposed outside the container network")
				.doesNotContain("8081:");
	}

	/**
	 * Postgres 18 moved PGDATA to {@code /var/lib/postgresql/18/docker} and now
	 * declares the volume at {@code /var/lib/postgresql}. The old path
	 * ({@code /var/lib/postgresql/data}), which every older example uses, would give a
	 * container that starts fine and loses the whole database on restart.
	 */
	@Test
	void givenAPostgres18Image_whenReadingItsVolumeMounts_thenTheDataVolumePointsAtTheCorrectPath() {
		String image = (String) DB.get("image");
		assertThat(image).as("the test only knows the path rule for postgres 18").startsWith("postgres:18");

		assertThat((List<String>) DB.get("volumes"))
				.as("postgres:18 declares VOLUME /var/lib/postgresql — not /var/lib/postgresql/data")
				.contains("dbdata:/var/lib/postgresql")
				.noneSatisfy(m -> assertThat(m).contains("/var/lib/postgresql/data"));
	}

	/**
	 * The init script is run by {@code bash} inside the Linux container. If it is
	 * checked out with CRLF on a Windows machine, it fails with
	 * {@code $'\r': command not found} — and then Postgres starts without the runtime
	 * role, after which the application cannot connect. {@code .gitattributes} guards
	 * against that; this test is the net below, because the symptom is far from the
	 * cause.
	 */
	@Test
	void givenTheRoleInitScript_whenReadingItsBytes_thenItHasUnixLineEndingsAndIsExecutable() {
		String script = ProjectFile.text("deploy/postgres/init/01-roles.sh");

		assertThat(script).as("bash inside the container cannot read CRLF").doesNotContain("\r");
		assertThat(script).startsWith("#!/bin/bash");
		assertThat(ProjectFile.text(".gitattributes"))
				.as("without the rule the script gets CRLF on the next checkout on Windows")
				.contains("*.sh text eol=lf");
	}

	@Test
	void givenTheComposeFile_whenReadingTheDatabaseVolumes_thenTheRoleScriptIsMountedReadOnly() {
		assertThat((List<String>) DB.get("volumes"))
				.as("the init script creates login roles; the container must not be able to change it")
				.contains("./deploy/postgres/init:/docker-entrypoint-initdb.d:ro");
	}

	// -----------------------------------------------------------------------

	private static String springProperty(String key) {
		Properties p = new Properties();
		try {
			p.load(new StringReader(ProjectFile.text("src/main/resources/application.properties")));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		String value = p.getProperty(key);
		assertThat(value).as("%s is missing from application.properties", key).isNotNull();
		return value;
	}

}

package dk.aftermarked.ops;

import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPERATIONS — the actuator surface (PLAN §2.7 "Monitoring", CLAUDE.md "Auth &amp; crypto").
 *
 * <p>Actuator runs on its own port ({@code management.server.port}) and therefore in
 * a <em>child context</em> with its own servlet container. The claim made by
 * {@code SecurityConfig} and by {@code application.properties} is that
 * {@code /actuator/prometheus} "is still behind a login". That claim cannot be
 * verified with MockMvc, because MockMvc only knows the main context — which is why
 * this test goes over a real socket to the real management port.
 *
 * <p>The test pins down four things:
 * <ol>
 *   <li>Metrics (Prometheus) require a login — on the management port too.</li>
 *   <li>Health is open, because the container and load balancer probes use it.</li>
 *   <li>Health leaks no details (DB URL, disk space, component names) to unauthorized callers.</li>
 *   <li>Endpoints we have not exposed are unreachable — not even by accident.</li>
 * </ol>
 *
 * <p>{@code management.server.port=0} yields a random port, so the test can run in
 * parallel with a local stack on 8081.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"management.server.port=0",
				// Spring Boot test support disables metric export by default.
				// Here it must be on: otherwise /actuator/prometheus does not exist
				// at all, and the test would "prove" that it is closed merely
				// because it is missing.
				"management.prometheus.metrics.export.enabled=true",
				"spring.security.user.name=ops-test",
				"spring.security.user.password=ops-test-password"
		})
class ActuatorAccessOpsTest {

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		OpsPostgres.register(registry);
	}

	private static final String USER = "ops-test";

	private static final String PASSWORD = "ops-test-password";

	/**
	 * Endpoints that are NOT listed in {@code management.endpoints.web.exposure.include}.
	 * Every one of them is an information leak of its own: {@code env} and
	 * {@code configprops} show database passwords in clear or masked form,
	 * {@code heapdump} is the entire memory including sessions and tokens,
	 * {@code mappings} draws the attack surface, and {@code shutdown} kills the replica.
	 */
	private static final List<String> NOT_EXPOSED = List.of(
			"/actuator/env", "/actuator/beans", "/actuator/configprops", "/actuator/heapdump",
			"/actuator/threaddump", "/actuator/loggers", "/actuator/mappings", "/actuator/shutdown",
			"/actuator/flyway", "/actuator/scheduledtasks", "/actuator/caches", "/actuator/metrics");

	@Value("${local.server.port}")
	int applicationPort;

	@Value("${local.management.port}")
	int managementPort;

	HttpProbe management;

	HttpProbe application;

	@BeforeEach
	void createProbes() {
		this.management = new HttpProbe(managementPort);
		this.application = new HttpProbe(applicationPort);
	}

	@Test
	void givenTheRunningApplication_whenComparingItsPorts_thenActuatorListensOnItsOwnPort() {
		assertThat(managementPort)
				.as("actuator must sit on its own port, which the load balancer does not expose")
				.isNotEqualTo(applicationPort);
	}

	@Test
	void givenNoCredentials_whenScrapingPrometheusOnTheManagementPort_thenTheRequestIsUnauthorized() {
		HttpResponse<String> response = management.get("/actuator/prometheus");

		assertThat(response.statusCode())
				.as("""
						/actuator/prometheus on the management port must require a login. \
						If the answer is 200, the filter chain from SecurityConfig does not \
						apply inside the actuator child context, and anyone on the internal \
						network can read the metrics.""")
				.isEqualTo(401);
		assertThat(response.body()).doesNotContain("# HELP", "jvm_memory_used_bytes");
	}

	@Test
	void givenValidCredentials_whenScrapingPrometheus_thenTheMetricsAreReturned() {
		HttpResponse<String> response = management.getWithLogin("/actuator/prometheus", USER, PASSWORD);

		assertThat(response.statusCode())
				.as("locked down is only half of it — the scrape must still work with credentials")
				.isEqualTo(200);
		assertThat(response.body()).contains("# HELP").contains("jvm_memory_used_bytes");
	}

	@Test
	void givenNoCredentials_whenProbingHealthOnTheManagementPort_thenItAnswersUp() {
		HttpResponse<String> response = management.get("/actuator/health");

		assertThat(response.statusCode())
				.as("the compose healthcheck and the load balancer probe carry no credentials")
				.isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"");
	}

	/** Readiness is the one the compose.yaml healthcheck asks for — it must exist and be open. */
	@Test
	void givenNoCredentials_whenProbingReadinessAndLiveness_thenBothAreOpenAndReportUp() {
		assertThat(management.get("/actuator/health/readiness").statusCode()).isEqualTo(200);
		assertThat(management.get("/actuator/health/readiness").body()).contains("UP");
		assertThat(management.get("/actuator/health/liveness").statusCode()).isEqualTo(200);
		assertThat(management.get("/actuator/health/liveness").body()).contains("UP");
	}

	@Test
	void givenAnUnauthorizedCaller_whenReadingHealth_thenNoComponentDetailsAreDisclosed() {
		String body = management.get("/actuator/health").body();

		assertThat(body)
				.as("show-details is 'never' — health must not reveal components, database or disk space")
				.doesNotContain("components")
				.doesNotContain("diskSpace")
				.doesNotContain("\"db\"")
				.doesNotContain("PostgreSQL")
				.doesNotContain("jdbc:");
		assertThat(body.length())
				.as("a health response carrying details is long; %s".formatted(body))
				.isLessThan(120);
	}

	@Test
	void givenEndpointsOutsideTheExposureList_whenRequestedWithoutCredentials_thenNoneAnswerWithOk() {
		for (String path : NOT_EXPOSED) {
			assertThat(management.get(path).statusCode())
					.as("%s must be neither exposed nor open", path)
					.isNotEqualTo(200);
		}
	}

	/**
	 * They must not answer even with valid credentials: the exposure list is the real
	 * barrier, the login is only the second layer.
	 */
	@Test
	void givenEndpointsOutsideTheExposureList_whenRequestedWithValidCredentials_thenTheyStillDoNotExist() {
		for (String path : NOT_EXPOSED) {
			assertThat(management.getWithLogin(path, USER, PASSWORD).statusCode())
					.as("%s is not in exposure.include and must therefore not exist", path)
					.isEqualTo(404);
		}
	}

	/**
	 * The access rules are path based. Then they must hold when the path is written
	 * differently too: {@code /actuator/health} is open, and an attacker will try to
	 * hang prometheus off the tail of exactly that path.
	 */
	@Test
	void givenObfuscatedPathVariants_whenRequestingPrometheusWithoutCredentials_thenNoneBypassTheLogin() {
		List<String> variants = List.of(
				"/actuator/prometheus/",
				"/actuator//prometheus",
				"/ACTUATOR/prometheus",
				"/actuator/Prometheus",
				"/actuator/health/../prometheus",
				"/actuator/health/%2e%2e/prometheus",
				"/actuator/health;/../prometheus");

		for (String path : variants) {
			assertThat(management.get(path).statusCode())
					.as("%s must not hand out metrics without a login", path)
					.isNotEqualTo(200);
		}
	}

	/** A health response must not linger in an intermediate cache — a load balancer has to see the current state. */
	@Test
	void givenAHealthProbe_whenReadingItsResponseHeaders_thenTheResponseIsNotCacheable() {
		HttpResponse<String> response = management.get("/actuator/health");

		assertThat(HttpProbe.header(response, "Cache-Control"))
				.as("the readiness answer decides whether the replica gets traffic — it must never be served from a cache")
				.contains("no-store");
	}

	@Test
	void givenNoCredentials_whenRequestingTheActuatorDiscoveryPage_thenItIsNotServed() {
		assertThat(management.get("/actuator").statusCode())
				.as("the discovery page lists the whole actuator surface and is therefore reconnaissance")
				.isNotEqualTo(200);
	}

	/**
	 * It is the management port that carries actuator. The application port — the only
	 * one the load balancer forwards to — must not serve anything at all under
	 * /actuator.
	 */
	@Test
	void givenTheApplicationPort_whenRequestingAnyActuatorPath_thenNothingIsServedWithOrWithoutALogin() {
		for (String path : List.of("/actuator", "/actuator/health", "/actuator/info", "/actuator/prometheus")) {
			assertThat(application.get(path).statusCode())
					.as("%s must not be reachable through the load balancer", path)
					.isNotEqualTo(200);
			assertThat(application.getWithLogin(path, USER, PASSWORD).statusCode())
					.as("%s must not be reachable through the load balancer, not even with a login", path)
					.isNotEqualTo(200);
		}
	}

	/**
	 * /actuator/info is exposed. As long as there is no build-info or git contributor,
	 * the answer is empty — but that is a decision, not a coincidence, and it must be
	 * caught if someone enables build-info without thinking about the fact that the
	 * commit sha and build timestamp then sit out in the open.
	 */
	@Test
	void givenValidCredentials_whenReadingTheInfoEndpoint_thenItLeaksNeitherVersionNorCommit() {
		HttpResponse<String> response = management.getWithLogin("/actuator/info", USER, PASSWORD);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body())
				.as("the info endpoint must not reveal the java version, the OS or the commit")
				.doesNotContain("java")
				.doesNotContain("\"os\"")
				.doesNotContain("commit")
				.doesNotContain("build");
	}

}

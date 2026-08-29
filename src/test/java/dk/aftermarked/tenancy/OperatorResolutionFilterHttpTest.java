package dk.aftermarked.tenancy;

import java.sql.SQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The host name is the ONLY permitted source of operator context (PLAN §2.6,
 * §2.7 rule 2). This test leans on the entrance: can a header, a port, a letter case
 * or a path variant move or bypass the operator?
 *
 * <p>{@code MockHttpServletRequest.getServerName()} reads the Host header and strips
 * the port exactly like a real container, so {@code .header("Host", …)} is the genuine
 * article here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(dk.aftermarked.TestcontainersConfiguration.class)
class OperatorResolutionFilterHttpTest {

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	MockMvcTester mvc;

	@BeforeEach
	void seed() throws SQLException {
		OperatorSeed.insertTwoOperators(postgres);
	}

	// --- the host name, and only the host name ------------------------------

	@Test
	void givenAKnownHostHeader_whenRequestingTheFrontPage_thenItDeterminesTheOperator() {
		assertThat(mvc.get().uri("/").header(HttpHeaders.HOST, "a.test"))
				.hasStatusOk().bodyText().contains("Operator A");
	}

	/** The customer runs on a different port number behind the load balancer. The port is not part of the identity. */
	@Test
	void givenAHostHeaderCarryingAPortNumber_whenRequestingTheFrontPage_thenTheOperatorStillResolves() {
		assertThat(mvc.get().uri("/").header(HttpHeaders.HOST, "a.test:8443"))
				.hasStatusOk().bodyText().contains("Operator A");
	}

	/** Host names are case insensitive; the database stores them in lower case. */
	@Test
	void givenAHostHeaderInUpperCase_whenRequestingTheFrontPage_thenTheOperatorStillResolves() {
		assertThat(mvc.get().uri("/").header(HttpHeaders.HOST, "A.TEST"))
				.hasStatusOk().bodyText().contains("Operator A");
	}

	/**
	 * ATTACK: a user on operator A's domain asks to become operator B by sending the
	 * header a reverse proxy normally sets. The header must have no effect whatsoever —
	 * {@code server.forward-headers-strategy} is off, and if it is ever turned on,
	 * {@code TenancyWiringTest} fails.
	 */
	@Test
	void givenAnXForwardedHostForAnotherOperator_whenRequestingTheFrontPage_thenItCannotMoveTheOperator() {
		assertThat(mvc.get().uri("/")
				.header(HttpHeaders.HOST, "a.test")
				.header("X-Forwarded-Host", "b.test"))
				.hasStatusOk().bodyText().contains("Operator A").doesNotContain("Operator B");
	}

	@Test
	void givenAForwardedHeaderForAnotherOperator_whenRequestingTheFrontPage_thenItCannotMoveTheOperator() {
		assertThat(mvc.get().uri("/")
				.header(HttpHeaders.HOST, "a.test")
				.header("Forwarded", "host=b.test;proto=https"))
				.hasStatusOk().bodyText().contains("Operator A").doesNotContain("Operator B");
	}

	@Test
	void givenOtherHostSpoofingHeaders_whenRequestingTheFrontPage_thenNoneOfThemCanMoveTheOperator() {
		assertThat(mvc.get().uri("/")
				.header(HttpHeaders.HOST, "a.test")
				.header("X-Forwarded-Server", "b.test")
				.header("X-Host", "b.test")
				.header("X-Original-Host", "b.test"))
				.hasStatusOk().bodyText().contains("Operator A").doesNotContain("Operator B");
	}

	/** A query parameter is not a source of operator context either. */
	@Test
	void givenAQueryParameterNamingAnotherOperator_whenRequestingTheFrontPage_thenItCannotMoveTheOperator() {
		assertThat(mvc.get().uri("/?operator=" + OperatorSeed.B.value()).header(HttpHeaders.HOST, "a.test"))
				.hasStatusOk().bodyText().contains("Operator A").doesNotContain("Operator B");
	}

	// --- fails closed -------------------------------------------------------

	@Test
	void givenAnUnknownHostHeader_whenRequestingTheFrontPage_thenTheRequestIsMisdirected() {
		assertThat(mvc.get().uri("/").header(HttpHeaders.HOST, "unknown.test")).hasStatus(421);
	}

	@Test
	void givenAnEmptyHostHeader_whenRequestingTheFrontPage_thenTheRequestIsMisdirected() {
		assertThat(mvc.get().uri("/").header(HttpHeaders.HOST, "")).hasStatus(421);
	}

	/** A trailing dot is a different host name. Fail-closed, not fail-open. */
	@Test
	void givenAHostHeaderWithATrailingDot_whenRequestingTheFrontPage_thenItIsRejected() {
		assertThat(mvc.get().uri("/").header(HttpHeaders.HOST, "a.test.")).hasStatus(421);
	}

	@Test
	void givenASubdomainOfAKnownHostName_whenRequestingTheFrontPage_thenItIsRejected() {
		assertThat(mvc.get().uri("/").header(HttpHeaders.HOST, "evil.a.test")).hasStatus(421);
	}

	// --- the order in the filter chain --------------------------------------

	/**
	 * Operator resolution must sit BEFORE Spring Security. Proven by an unknown host
	 * name on a login-protected path giving 421 and not 401: had Security run first,
	 * the answer would be 401.
	 */
	@Test
	void givenAnUnknownHostOnAProtectedPath_whenRequestingIt_thenTheOperatorFilterRunsBeforeSpringSecurity() {
		assertThat(mvc.get().uri("/operator").header(HttpHeaders.HOST, "unknown.test")).hasStatus(421);
		assertThat(mvc.get().uri("/operator").header(HttpHeaders.HOST, "a.test")).hasStatus(401);
	}

	// --- the actuator exemption ----------------------------------------------

	/**
	 * The exemption applies to actuator: an unknown host name does NOT give 421 here.
	 * The answers come from the layers that sit after the operator filter, and thereby
	 * show that it really was skipped — 404 because actuator is served from the
	 * management port (8081) and not from the application port, and 401 because Spring
	 * Security only opens {@code /actuator/health}, not {@code /actuator}.
	 */
	@Test
	void givenAnUnknownHostName_whenRequestingTheActuatorPath_thenItIsExemptFromOperatorResolution() {
		assertThat(mvc.get().uri("/actuator/health").header(HttpHeaders.HOST, "unknown.test"))
				.hasStatus(404);
		assertThat(mvc.get().uri("/actuator").header(HttpHeaders.HOST, "unknown.test"))
				.hasStatus(401);
	}

	/**
	 * FINDING (fixed): the exemption used to match on a bare prefix, so /actuatorx,
	 * /actuator-admin and any other path merely BEGINNING with "/actuator" slipped past
	 * operator resolution. It now matches on a segment boundary.
	 */
	@Test
	void givenPathsThatMerelyBeginWithActuator_whenRequestingThemWithAnUnknownHost_thenTheyAreNOTExempt() {
		assertThat(mvc.get().uri("/actuatorx").header(HttpHeaders.HOST, "unknown.test")).hasStatus(421);
		assertThat(mvc.get().uri("/actuator-admin/dump").header(HttpHeaders.HOST, "unknown.test"))
				.hasStatus(421);
		assertThat(mvc.get().uri("/actuatorial").header(HttpHeaders.HOST, "unknown.test")).hasStatus(421);
	}

	/**
	 * Path variants that are NOT exactly /actuator/… go through the filter and fail
	 * closed. getRequestURI() is raw — neither normalized nor decoded — and that is
	 * exactly why these end up in the filter instead of slipping past it.
	 */
	@Test
	void givenEncodedOrDoubleSlashVariantsOfTheActuatorPath_whenRequestingThem_thenTheyGoThroughTheFilter() {
		assertThat(mvc.get().uri("//actuator/health").header(HttpHeaders.HOST, "unknown.test"))
				.hasStatus(421);
		assertThat(mvc.get().uri("/%61ctuator/health").header(HttpHeaders.HOST, "unknown.test"))
				.hasStatus(421);
	}

}

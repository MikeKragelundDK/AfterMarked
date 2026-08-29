package dk.aftermarked.ops;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/**
 * OPERATIONS — the public HTTP surface (PLAN §2.7 cache policy, CLAUDE.md
 * "Auth &amp; crypto" and rule 2 of tenant isolation).
 *
 * <p>Four things are pinned down:
 * <ol>
 *   <li><strong>The default of the cache policy.</strong> PLAN §2.7 item 2:
 *       {@code private, no-store} is the default, {@code public} is opt-in with a
 *       justification. The opt-in mechanics themselves are built in E15, but the
 *       default can be held now — and it makes it expensive to accidentally make a
 *       page cacheable.</li>
 *   <li><strong>CSRF is on.</strong> A POST without a token must be rejected, and a
 *       POST with a token must not be rejected for the same reason. Without that
 *       second proof you do not know whether the 403 came from CSRF or from something
 *       else entirely.</li>
 *   <li><strong>httpBasic, not formLogin.</strong> An unauthorized call must give 401
 *       with {@code WWW-Authenticate}, not a 302 to a login page that does not
 *       exist.</li>
 *   <li><strong>The host name — and only the host name — determines the operator.</strong>
 *       {@code X-Forwarded-Host} and {@code Forwarded} are client controlled and must
 *       never be able to move the request to another customer.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(dk.aftermarked.TestcontainersConfiguration.class)
class WebSecurityOpsTest {

	/** Paths that are not explicitly open, and that must therefore require a login. */
	private static final List<String> MUST_REQUIRE_LOGIN = List.of(
			"/operator", "/admin", "/api", "/api/orders", "/sales", "/listings",
			"/actuator", "/actuator/prometheus", "/actuator/info", "/actuator/env");

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	MockMvcTester mvc;

	@BeforeEach
	void seedTwoOperators() throws SQLException {
		try (Connection owner = DriverManager.getConnection(
				postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				Statement sql = owner.createStatement()) {
			sql.execute("DELETE FROM operator");
			sql.execute("""
					INSERT INTO operator (id, slug, name, hostname) VALUES
					  ('00000000-0000-4000-8000-0000000000a1', 'ops-a', 'Operator A', 'a.test'),
					  ('00000000-0000-4000-8000-0000000000b1', 'ops-b', 'Operator B', 'b.test')
					""");
		}
	}

	// -----------------------------------------------------------------------
	// Cache policy (PLAN §2.7 item 2)
	// -----------------------------------------------------------------------

	@Test
	void givenAKnownHostName_whenRequestingTheFrontPage_thenTheResponseIsNotCacheable() {
		MockHttpServletResponse response = get("/", "a.test").getResponse();

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(cacheControl(response))
				.as("PLAN §2.7: no-store is the default. A cacheable page carrying operator or "
						+ "user data is a data breach, not a performance bug.")
				.contains("no-store");
	}

	@Test
	void givenAnyServedPath_whenReadingItsCacheControl_thenNoneArePublicCacheableWithoutAnExplicitDecision() {
		for (String path : List.of("/", "/operator", "/does-not-exist")) {
			MockHttpServletResponse response = get(path, "a.test").getResponse();
			// Foerst at headeren OVERHOVEDET er der: uden den ville de to
			// doesNotContain herunder vaere groenne paa et svar helt uden
			// cache-politik — praecis det, testen skal fange.
			assertThat(cacheControl(response))
					.as("%s has no Cache-Control at all; no policy is not the same as a safe policy", path)
					.isNotEmpty();
			assertThat(cacheControl(response))
					.as("%s must not be public-cacheable; public requires an explicit justification (PLAN §2.7)", path)
					.doesNotContain("public")
					.doesNotContain("s-maxage");
		}
	}

	@Test
	void givenAnUnauthorizedRequest_whenReadingTheRejectedResponse_thenItIsAlsoNoStore() {
		MockHttpServletResponse response = get("/operator", "a.test").getResponse();

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(cacheControl(response))
				.as("a 401 response must not be allowed to sit in an intermediate cache")
				.contains("no-store");
	}

	// -----------------------------------------------------------------------
	// Security headers
	// -----------------------------------------------------------------------

	@Test
	void givenAKnownHostName_whenRequestingTheFrontPage_thenTheStandardSecurityHeadersAreSet() {
		MockHttpServletResponse response = get("/", "a.test").getResponse();

		assertThat(response.getHeader("X-Content-Type-Options"))
				.as("the browser must not guess the content type")
				.isEqualTo("nosniff");
		assertThat(response.getHeader("X-Frame-Options"))
				.as("the marketplace must not be clickjackable into an iframe")
				.isEqualTo("DENY");
	}

	// -----------------------------------------------------------------------
	// CSRF
	// -----------------------------------------------------------------------

	@Test
	void givenNoCsrfToken_whenPostingToTheFrontPage_thenTheRequestIsForbidden() {
		MvcTestResult response = mvc.post().uri("/").with(host("a.test")).exchange();

		assertThat(response.getResponse().getStatus())
				.as("CSRF protection must be on, on the open front page too — "
						+ "this is where the checkout POSTs land later")
				.isEqualTo(403);
	}

	@Test
	void givenAValidCsrfToken_whenPostingToTheFrontPage_thenTheRequestIsNotRejectedByCsrf() {
		MvcTestResult response = mvc.post().uri("/").with(host("a.test")).with(csrf()).exchange();

		assertThat(response.getResponse().getStatus())
				.as("with a valid token the 403 must disappear — otherwise it did not come from CSRF")
				.isNotEqualTo(403);
	}

	// -----------------------------------------------------------------------
	// Authentication
	// -----------------------------------------------------------------------

	@Test
	void givenNoCredentials_whenCallingAProtectedPath_thenItAnswers401WithABasicChallengeAndNoRedirect() {
		MockHttpServletResponse response = get("/operator", "a.test").getResponse();

		assertThat(response.getStatus())
				.as("httpBasic was chosen — there is no login page to redirect to")
				.isEqualTo(401);
		assertThat(response.getHeader("WWW-Authenticate")).startsWith("Basic");
	}

	@Test
	void givenNoCredentials_whenCallingEveryPathThatIsNotOpen_thenAllOfThemRequireALogin() {
		for (String path : MUST_REQUIRE_LOGIN) {
			assertThat(get(path, "a.test").getResponse().getStatus())
					.as("%s is not on the permitAll list and must therefore require a login", path)
					.isEqualTo(401);
		}
	}

	/**
	 * /error is open, because Spring dispatches errors there internally. In return it
	 * must not be usable to pull out a stack trace.
	 */
	@Test
	void givenTheOpenErrorPath_whenRequestingIt_thenTheResponseLeaksNoStackTrace()
			throws java.io.UnsupportedEncodingException {
		MockHttpServletResponse response = get("/error", "a.test").getResponse();

		// Et tomt svar ville goere alle tre doesNotContain sande uden at bevise
		// noget. Statussen er det, der viser, at vi faktisk ramte fejlsiden.
		assertThat(response.getStatus())
				.as("/error must answer — an empty response would make the assertions below vacuous")
				.isNotEqualTo(404);
		assertThat(response.getContentAsString())
				.doesNotContain("java.lang.")
				.doesNotContain("at dk.aftermarked")
				.doesNotContain("org.springframework.web");
	}

	// -----------------------------------------------------------------------
	// The host name is the only source of the operator (PLAN §2.7, rule 2)
	// -----------------------------------------------------------------------

	@Test
	void givenAnXForwardedHostForAnotherCustomer_whenRequestingTheFrontPage_thenTheOperatorIsUnchanged() {
		MvcTestResult response = mvc.get().uri("/")
				.with(host("a.test"))
				.header("X-Forwarded-Host", "b.test")
				.header("X-Forwarded-Server", "b.test")
				.exchange();

		assertThat(response).hasStatusOk().bodyText()
				.as("the operator is resolved from the host name, not from a header the client sets itself")
				.contains("Operator A").doesNotContain("Operator B");
	}

	@Test
	void givenAForwardedHeaderForAnotherCustomer_whenRequestingTheFrontPage_thenTheOperatorIsUnchanged() {
		MvcTestResult response = mvc.get().uri("/")
				.with(host("a.test"))
				.header("Forwarded", "host=b.test;proto=https")
				.exchange();

		assertThat(response).hasStatusOk().bodyText()
				.contains("Operator A").doesNotContain("Operator B");
	}

	@Test
	void givenAHostHeaderCarryingAPortNumber_whenRequestingTheFrontPage_thenItStillResolvesTheRightOperator() {
		MvcTestResult response = mvc.get().uri("/").with(request -> {
			request.setServerName("b.test");
			request.setServerPort(8080);
			return request;
		}).exchange();

		assertThat(response).hasStatusOk().bodyText().contains("Operator B");
	}

	// -----------------------------------------------------------------------

	private MvcTestResult get(String path, String hostName) {
		return mvc.get().uri(path).with(host(hostName)).exchange();
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor host(String hostName) {
		return request -> {
			request.setServerName(hostName);
			return request;
		};
	}

	private static String cacheControl(MockHttpServletResponse response) {
		String header = response.getHeader("Cache-Control");
		return header != null ? header : "";
	}

}

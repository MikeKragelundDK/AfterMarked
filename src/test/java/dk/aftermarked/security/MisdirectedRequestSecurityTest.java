package dk.aftermarked.security;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SIKKERHEDSREVIEW — afvisningsvejen, målt over en rigtig socket.
 *
 * <p>Et ukendt værtsnavn afvises af {@code OperatorResolutionFilter} med 421, og
 * filteret sidder FØR Spring Security i kæden. Det er med vilje — uden operatør
 * er der intet at autentificere imod — men det betyder, at
 * {@code response.sendError(421)} skrives på det RÅ svar, ikke på det, Spring
 * Security har pakket ind. Om afvisningen alligevel ender med sikkerheds-
 * headerne afhænger derfor af, om containerens ERROR-dispatch går gennem
 * security-kæden. Det kan MockMvc ikke svare på; det kan kun en rigtig Tomcat.
 *
 * <p>Afvisningen er den mest eksponerede flade, systemet har: den svarer på
 * enhver forespørgsel mod ethvert domæne, der peger på os, fra hvem som helst —
 * også domæner, ingen kunde har endnu. Derfor måles den her ved siden af et
 * kendt værtsnavn, så en manglende header ikke kan forveksles med "vi sætter
 * den slet ikke".
 *
 * <p>Værtsnavnet styres uden at røre Host-headeren: {@code localhost} er seedet
 * som operatør, {@code 127.0.0.1} er ikke. To URL'er mod den samme port giver
 * dermed den ene det kendte og den anden det ukendte værtsnavn.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MisdirectedRequestSecurityTest {

	private static final int MISDIRECTED_REQUEST = 421;

	private static final String KNOWN_HOST = "localhost";

	private static final String UNKNOWN_HOST = "127.0.0.1";

	private static final String RUNTIME_USER = "theticket_runtime";

	private static final String RUNTIME_PASSWORD = "runtime-dev-only";

	private static final PostgreSQLContainer CONTAINER =
			new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
					.withCopyFileToContainer(
							MountableFile.forHostPath("deploy/postgres/init/01-roles.sh", 0755),
							"/docker-entrypoint-initdb.d/01-roles.sh");

	static {
		CONTAINER.start();
	}

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", CONTAINER::getJdbcUrl);
		registry.add("spring.datasource.username", () -> RUNTIME_USER);
		registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
		registry.add("spring.flyway.url", CONTAINER::getJdbcUrl);
		registry.add("spring.flyway.user", CONTAINER::getUsername);
		registry.add("spring.flyway.password", CONTAINER::getPassword);
	}

	@Value("${local.server.port}")
	int port;

	@BeforeEach
	void seedTheOperatorOnLocalhost() throws SQLException {
		try (Connection owner = DriverManager.getConnection(
				CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
				Statement sql = owner.createStatement()) {
			sql.execute("DELETE FROM operator");
			sql.execute("""
					INSERT INTO operator (id, slug, name, hostname) VALUES
					  ('00000000-0000-4000-8000-0000000000d1', 'sec-known', 'Known Operator', 'localhost')
					""");
		}
	}

	/**
	 * Kontrollen: et kendt værtsnavn får siden OG headerne. Uden den her ville
	 * en manglende header på afvisningen kunne forklares med "vi sætter den
	 * ingen steder".
	 */
	@Test
	void givenAKnownHostName_whenTheFrontPageIsServed_thenItCarriesTheStandardSecurityHeaders() {
		HttpResponse<String> response = get(KNOWN_HOST);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(header(response, "X-Content-Type-Options")).isEqualTo("nosniff");
		assertThat(header(response, "X-Frame-Options")).isEqualTo("DENY");
		assertThat(header(response, "Cache-Control")).contains("no-store");
	}

	/**
	 * Afvisningen sker før Spring Security. Svaret skal alligevel bære de samme
	 * headere som resten af fladen — ellers er den ene vej ud af applikationen,
	 * der er åben for alle, også den ene uden politik.
	 */
	@Test
	void givenAnUnknownHostName_whenTheRequestIsRejected_thenTheRejectionCarriesTheStandardSecurityHeaders() {
		HttpResponse<String> response = get(UNKNOWN_HOST);

		assertThat(response.statusCode()).isEqualTo(MISDIRECTED_REQUEST);
		assertThat(header(response, "X-Content-Type-Options"))
				.as("the rejection is served to anyone; the browser must not sniff its content type either")
				.isEqualTo("nosniff");
		assertThat(header(response, "X-Frame-Options"))
				.as("a framed rejection page is still a page from a domain that points at us")
				.isEqualTo("DENY");
		assertThat(header(response, "Cache-Control"))
				.as("""
						a cached 421 is a customer domain that stays broken after it has been \
						provisioned — the answer must never be reusable""")
				.contains("no-store");
	}

	/** Afvisningen må ikke fortælle, hvad der er indenfor. */
	@Test
	void givenAnUnknownHostName_whenReadingTheRejectionBody_thenItLeaksNoInternalDetail() {
		HttpResponse<String> response = get(UNKNOWN_HOST);

		assertThat(response.body())
				.doesNotContain("java.lang.")
				.doesNotContain("dk.aftermarked")
				.doesNotContain("org.springframework")
				.as("the name of a customer that does exist is not the business of a caller on a foreign domain")
				.doesNotContain("Known Operator")
				.doesNotContain("jdbc:")
				.doesNotContain("operator_id_for_host");
	}

	/**
	 * Afvisningen sker før alt andet og må ikke koste en session. Ellers er en
	 * strøm af ukendte værtsnavne en gratis måde at fylde hukommelsen på — og
	 * det kræver hverken login eller et gyldigt domæne.
	 */
	@Test
	void givenAnUnknownHostName_whenTheRequestIsRejected_thenNoSessionCookieIsHandedOut() {
		assertThat(get(UNKNOWN_HOST).headers().allValues("Set-Cookie"))
				.as("an unauthenticated rejection must not allocate server-side state")
				.noneSatisfy(value -> assertThat(value).containsIgnoringCase("JSESSIONID"));
	}

	/**
	 * ANGREB: læg sin egen streng i STIEN og få den gengivet i afvisningen.
	 * Afvisningen serveres til hvem som helst på ethvert domæne, der peger på
	 * os, så en krop, der gentager kalderens eget input, er den billigst mulige
	 * reflekterings-flade — og den ligger uden for al header-politik, fordi
	 * filteret kører før Spring Security.
	 */
	@Test
	void givenARequestPathCarryingMarkup_whenTheRequestIsRejected_thenNothingOfItComesBackOut() {
		HttpResponse<String> response = get(UNKNOWN_HOST, "/%3Cscript%3E?q=%3Cimg%20src%3Dx%3E");

		assertThat(response.statusCode()).isEqualTo(MISDIRECTED_REQUEST);
		assertThat(response.body())
				.as("a rejection that echoes the caller's own path is a reflection surface for free")
				.doesNotContain("script")
				.doesNotContain("%3C")
				.isEmpty();
	}

	private HttpResponse<String> get(String hostName) {
		return get(hostName, "/");
	}

	private HttpResponse<String> get(String hostName, String path) {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + hostName + ":" + port + path))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();
		try (HttpClient client = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NEVER)
				.connectTimeout(Duration.ofSeconds(10))
				.build()) {
			return client.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	private static String header(HttpResponse<String> response, String name) {
		return response.headers().firstValue(name).orElse("");
	}

}

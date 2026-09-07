package dk.aftermarked.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.util.AntPathMatcher;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/**
 * SIKKERHEDSREVIEW — skrivestien ind i kataloget, FØR den findes.
 *
 * <p>Der er ingen controller bag {@code PUT /api/catalog} i dag, og der skal
 * ikke laves en her. Endpointet er udskudt med vilje: autentificeringen af et
 * katalog-push er en FJERDE stop-zone (Auth &amp; krypto), som sprintplanen ikke
 * navngiver, PLAN.md udtaler sig aldrig om skemaet, og OpenAPI-kontrakten skal
 * navngive det først. Det er tre beslutninger, der er Mikes — ikke noget en
 * commit må afgøre undervejs. Sprintets eget exit-kriterie ("idempotens bevist
 * i test") indfries på ingest-servicen og har aldrig krævet en HTTP-flade.
 *
 * <p>Denne klasse er TRIPWIREN i mellemtiden. Stien er lukket i dag, fordi
 * {@code SecurityConfig} lukker alt, der ikke står på en permitAll-liste — og
 * den dag nogen wirer en controller ind, skal det være et VALG at åbne den,
 * ikke en bivirkning. En {@code @RequestMapping} alene ville ellers give en
 * fungerende, uautentificeret skrivesti ind i kundens katalog, og intet i bygget
 * ville sige fra.
 *
 * <p><strong>Fælden, to af de fire tests findes for.</strong> Et 403 på et PUT
 * uden CSRF-token er CREDENTIAL-UAFHÆNGIGT: {@code CsrfFilter} kører før
 * {@code BasicAuthenticationFilter} og kortslutter kæden, så et request med
 * fuldt gyldige credentials får præcis samme 403. "Den gav 403" er derfor
 * ALDRIG et bevis for, at autentificeringen virkede. De to ting måles derfor
 * hver for sig, ved at fjerne én ting ad gangen:
 * <ul>
 *   <li>token med, credentials væk → 401 med Basic-challenge. Det er det eneste
 *       HTTP-bevis for, at {@code anyRequest().authenticated()} også dækker
 *       denne sti.</li>
 *   <li>credentials med, token væk → 403. CSRF gælder også en maskinvendt sti,
 *       så længe kæden er den samme som markedspladsens.</li>
 *   <li>begge dele væk → 403, altså netop det svar, der intet beviser.</li>
 * </ul>
 *
 * <p>Og fordi HTTP-vejen ikke kan bevise mere end det, læser den fjerde test
 * konfigurationens EGEN kildetekst: står stien på en permitAll-liste, er den
 * åben, uanset hvad en statuskode siger.
 *
 * <p><strong>Klassen ændrer intet i sikkerhedskonfigurationen.</strong> Den
 * tilføjer ingen filterkæde, slår ingenting fra og laver ingen undtagelse for
 * sin egen sti. Særligt CSRF: et globalt {@code csrf().disable()} ville gøre de
 * her tests grønne på den værst tænkelige måde og samtidig åbne hele
 * Thymeleaf-fladen.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(dk.aftermarked.TestcontainersConfiguration.class)
class CatalogWriteAccessSecurityTest {

	/** Stien katalog-pushet FÅR, den dag nogen beslutter, hvordan den autentificerer. */
	private static final String CATALOG_PATH = "/api/catalog";

	private static final String KNOWN_HOST = "a.catalogwrite.test";

	/** Et værtsnavn, ingen operatør ejer. Det er hele pointen i den tredje test. */
	private static final String UNKNOWN_HOST = "nobody.catalogwrite.test";

	/**
	 * Et navn, ikke en konto: testprofilen konfigurerer ingen bruger, så
	 * {@code user(...)} sætter en færdig, autentificeret kontekst direkte. Det er
	 * bevidst den STÆRKEST mulige kalder — pointen er, at selv den bliver afvist.
	 */
	private static final String PUSHER = "catalog-pusher";

	/** Kroppen et rigtigt push ville have. Den må aldrig nå at blive læst. */
	private static final String PAYLOAD = "{\"organizers\":[]}";

	private static final int MISDIRECTED_REQUEST = 421;

	private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

	private static final Path SECURITY_CONFIG =
			SOURCE_ROOT.resolve(Path.of("dk", "aftermarked", "SecurityConfig.java"));

	/**
	 * {@code requestMatchers(...)} umiddelbart efterfulgt af {@code permitAll()},
	 * målt på kildeteksten uden whitespace — så et linjeskift midt i kæden ikke
	 * gør en åben sti usynlig for testen.
	 */
	private static final Pattern PERMIT_ALL_CHAIN =
			Pattern.compile("requestMatchers\\(([^)]*)\\)\\.permitAll\\(\\)");

	private static final Pattern QUOTED_LITERAL = Pattern.compile("\"([^\"]*)\"");

	/**
	 * En DEKLARATION af en filterkæde — metodens returtype efterfulgt af dens navn.
	 * Bevidst ikke ordet alene: en kommentar, der NÆVNER en kommende kæde, er ikke
	 * en kæde, og resten af kodebasen skrives af andre hænder end denne test.
	 */
	private static final Pattern FILTER_CHAIN_DECLARATION =
			Pattern.compile("\\bSecurityFilterChain\\s+\\w+\\s*\\(");

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	MockMvcTester mvc;

	@BeforeEach
	void seedOneOperator() throws SQLException {
		try (Connection owner = DriverManager.getConnection(
				postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				Statement sql = owner.createStatement()) {
			sql.execute("DELETE FROM operator");
			sql.execute("""
					INSERT INTO operator (id, slug, name, hostname) VALUES
					  ('00000000-0000-4000-8000-00000000cfa1', 'catwrite-a', 'Catalog Write A', 'a.catalogwrite.test')
					""");
		}
	}

	/**
	 * Den anonyme kalder — den, der kan nå fladen fra hele internettet.
	 *
	 * <p>Uden token er svaret 403, og det siger INTET: det kommer fra
	 * {@code CsrfFilter}, som kortslutter kæden, før nogen har spurgt efter
	 * credentials. Derfor gentages requestet med et gyldigt token, så CSRF er ude
	 * af billedet, og det, der bliver tilbage, er selve adgangskontrollen: 401 med
	 * en Basic-challenge. Havde stien været åben, var svaret kommet fra
	 * DispatcherServlet i stedet — og så er tripwiren gået.
	 */
	@Test
	void givenNoCredentials_whenPuttingACatalog_thenItIsRefused() {
		MockHttpServletResponse withoutToken = putCatalog(KNOWN_HOST).exchange().getResponse();

		assertThat(withoutToken.getStatus())
				.as("a token-less PUT is refused by CSRF before authentication is even attempted; "
						+ "this 403 proves nothing on its own — see the next assertion")
				.isEqualTo(403);

		MockHttpServletResponse withToken = putCatalog(KNOWN_HOST).with(csrf()).exchange().getResponse();

		assertThat(withToken.getStatus())
				.as("with CSRF out of the way, an anonymous PUT to %s must be refused by authentication "
						+ "itself — a 404 or a 405 here would mean the request reached the dispatcher, "
						+ "and the write path is open", CATALOG_PATH)
				.isEqualTo(401);
		assertThat(withToken.getHeader("WWW-Authenticate"))
				.as("httpBasic was chosen — there is no login page to redirect to")
				.startsWith("Basic");
	}

	/**
	 * Spejlbilledet: credentials med, token væk. Statussen er den samme 403 som i
	 * det anonyme tilfælde ovenfor, og det er præcis dét, testen skal skrive ned —
	 * en afvisning, der ikke skelner mellem en kalder med og uden credentials,
	 * beviser ingenting om autentificering.
	 *
	 * <p>Den anden halvdel er lige så vigtig: med BÅDE credentials og token
	 * forsvinder 403'eren. Uden den halvdel kunne 403'eren komme fra hvad som
	 * helst andet, og testen ville kun måle, at requestet fejlede.
	 */
	@Test
	void givenValidCredentialsButNoCsrfToken_whenPuttingACatalog_thenItIsStillRefused() {
		MockHttpServletResponse refused = putCatalog(KNOWN_HOST).with(user(PUSHER)).exchange().getResponse();

		assertThat(refused.getStatus())
				.as("CSRF must apply to the catalog write path too — and the 403 an authenticated caller "
						+ "gets is the very same 403 an anonymous one gets")
				.isEqualTo(403);

		MockHttpServletResponse allowedPastCsrf =
				putCatalog(KNOWN_HOST).with(user(PUSHER)).with(csrf()).exchange().getResponse();

		assertThat(allowedPastCsrf.getStatus())
				.as("with a valid token the 403 must disappear — otherwise it did not come from CSRF, "
						+ "and the assertion above measured something else entirely")
				.isNotEqualTo(403);
	}

	/**
	 * Værtsnavnet afgør operatøren, og et ukendt værtsnavn fejler lukket.
	 * {@code OperatorResolutionFilter} sidder før Spring Security i kæden, så
	 * afvisningen sker, før der overhovedet findes en operatør at autentificere
	 * imod — og længe før nogen handler kunne røre kroppen.
	 *
	 * <p>Rækkefølgen måles på det NØGNE request. Det er den eneste form, der kan
	 * skelne: uden token ville {@code CsrfFilter} svare 403, hvis den kom først —
	 * hvilket den gør på et kendt værtsnavn, to tests længere oppe. Et 421 her kan
	 * derfor kun komme fra et filter, der ligger foran hele security-kæden.
	 * ({@code OperatorResolutionFilterHttpTest} pinner den samme rækkefølge mod
	 * 401'eren på en beskyttet GET; det her er den tilsvarende måling mod CSRF.)
	 *
	 * <p>Det andet request er så det stærkest mulige: gyldige credentials OG et
	 * gyldigt token. Det kan ikke måle rækkefølge — det ville passere begge filtre
	 * alligevel — men det siger noget andet, som er lige så vigtigt: selv et
	 * fejlfrit request får hverken en challenge eller en krop med tilbage.
	 */
	@Test
	void givenAnUnknownHostName_whenPuttingACatalog_thenItIsRefusedBeforeAnyHandlerRuns() {
		MockHttpServletResponse bare = putCatalog(UNKNOWN_HOST).exchange().getResponse();

		assertThat(bare.getStatus())
				.as("a 403 here would mean CsrfFilter answered before the host name was resolved — the "
						+ "very same request on a known host is a 403; a 404 or a 405 would mean the "
						+ "dispatcher answered first")
				.isEqualTo(MISDIRECTED_REQUEST);

		MockHttpServletResponse flawless =
				putCatalog(UNKNOWN_HOST).with(user(PUSHER)).with(csrf()).exchange().getResponse();

		assertThat(flawless.getStatus())
				.as("credentials and a token change nothing: there is no operator to authenticate against "
						+ "on a host name nobody owns")
				.isEqualTo(MISDIRECTED_REQUEST);
		assertThat(flawless.getHeader("WWW-Authenticate"))
				.as("no authentication entry point may have run — the request never got that far")
				.isNull();
		assertThat(flawless.getContentAsByteArray())
				.as("a caller on a host name no customer owns is told nothing about what is inside")
				.isEmpty();
	}

	/**
	 * Det, HTTP ikke kan bevise, læses i kildeteksten.
	 *
	 * <p>En statuskode kan man komme til at fejllæse — 403'eren ovenfor er
	 * beviset på netop det. Konfigurationen kan man ikke: står katalog-stien på en
	 * permitAll-liste, er den åben, uanset hvad kæden ellers svarer i dag.
	 * Mønstrene sammenlignes derfor med en path-matcher og ikke med
	 * {@code contains}: {@code "/api/**"} og {@code "/**"} åbner stien uden at
	 * nævne den med ét ord.
	 *
	 * <p>Testen fejler LUKKET. Kan den ikke læse hver eneste {@code permitAll} i
	 * filen som en liste af literaler, siger den fra i stedet for at være grøn på
	 * et ufuldstændigt billede — og den kræver, at {@code anyRequest()} stadig
	 * lukker resten, for uden den lukkeregel betyder fraværet fra listen
	 * ingenting.
	 */
	@Test
	void givenTheCatalogPath_whenReadingTheSecurityConfiguration_thenItIsNotOnAnyPermitAllList() {
		assertThat(filesDeclaringAFilterChain())
				.as("the permitAll list below is the whole story only as long as SecurityConfig.java is "
						+ "the only place a SecurityFilterChain is declared")
				.containsExactly(normalised(SECURITY_CONFIG));

		String source = read(SECURITY_CONFIG);
		String code = source.replaceAll("\\s+", "");
		List<String> openPatterns = new ArrayList<>();
		int chains = 0;

		Matcher chain = PERMIT_ALL_CHAIN.matcher(code);
		while (chain.find()) {
			chains++;
			int before = openPatterns.size();
			Matcher literal = QUOTED_LITERAL.matcher(chain.group(1));
			while (literal.find()) {
				openPatterns.add(literal.group(1));
			}
			assertThat(openPatterns.size())
					.as("the permitAll list [%s] names no literal path, so this test cannot read what it "
							+ "opens", chain.group(1))
					.isGreaterThan(before);
		}

		int mentions = source.split("permitAll", -1).length - 1;
		assertThat(chains)
				.as("SecurityConfig.java mentions permitAll %d time(s), but only %d of them sit in a "
						+ "requestMatchers(...).permitAll() chain this test can read; an unreadable "
						+ "permitAll is an unproven one", mentions, chains)
				.isEqualTo(mentions);
		assertThat(openPatterns)
				.as("no permitAll pattern was found at all — the test would then be green because it read "
						+ "nothing, not because the path is closed")
				.isNotEmpty();

		AntPathMatcher matcher = new AntPathMatcher();
		for (String pattern : openPatterns) {
			assertThat(matcher.match(pattern, CATALOG_PATH))
					.as("permitAll(\"%s\") opens %s; the catalog write path must never be open, least of "
							+ "all before anyone has decided how it authenticates", pattern, CATALOG_PATH)
					.isFalse();
		}

		assertThat(code)
				.as("without anyRequest().authenticated() as the closing rule, being absent from the "
						+ "permitAll list says nothing about whether the path requires a login")
				.contains("anyRequest().authenticated()");
	}

	// -----------------------------------------------------------------------

	/**
	 * Requestet et rigtigt katalog-push ville sende: PUT, JSON, en krop. Kun
	 * værtsnavn, credentials og token varierer mellem testene — alt andet holdes
	 * fast, så en forskel i svaret kun kan komme derfra.
	 */
	private MockMvcTester.MockMvcRequestBuilder putCatalog(String hostName) {
		return mvc.put().uri(CATALOG_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content(PAYLOAD)
				.with(host(hostName));
	}

	private static RequestPostProcessor host(String hostName) {
		return request -> {
			request.setServerName(hostName);
			return request;
		};
	}

	private static List<String> filesDeclaringAFilterChain() {
		try (Stream<Path> tree = Files.walk(SOURCE_ROOT)) {
			return tree.filter(Files::isRegularFile)
					.filter(file -> file.toString().endsWith(".java"))
					.filter(file -> FILTER_CHAIN_DECLARATION.matcher(read(file)).find())
					.map(CatalogWriteAccessSecurityTest::normalised)
					.sorted()
					.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** Samme skrivemåde på Windows og Linux, så fejlbeskeden er den samme begge steder. */
	private static String normalised(Path file) {
		return file.toString().replace('\\', '/');
	}

	private static String read(Path file) {
		if (!Files.isRegularFile(file)) {
			throw new AssertionError("The file " + file + " does not exist — the test cannot prove anything about it");
		}
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}

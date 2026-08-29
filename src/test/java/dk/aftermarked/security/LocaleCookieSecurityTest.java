package dk.aftermarked.security;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SIKKERHEDSREVIEW — sprogvalget som det LÆSES tilbage.
 *
 * <p>{@code LocaleResolutionHttpTest} beviser, at {@code ?lang=} fejler lukket.
 * Men {@code ?lang=} er kun den ene halvdel af kanalen: valget lander i cookien
 * {@code THETICKET_LANG}, og cookien er lige så meget bruger-input som
 * parameteren — den ligger i klientens egen browser og kan sættes af hvem som
 * helst, der kan skrive en cookie på domænet (klienten selv, en søskende-vært
 * på et fælles forældredomæne, en gammel cookie fra en tidligere version).
 *
 * <p>Reglen i CLAUDE.md gælder hele kanalen: et ukendt sprog skal ignoreres i
 * stilhed — aldrig 500, aldrig en halvoversat side. Cookien lever 365 dage, så
 * en dårlig værdi er ikke et enkelt dårligt request; den er et dårligt domæne
 * for den browser, indtil nogen rydder cookies.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(dk.aftermarked.TestcontainersConfiguration.class)
class LocaleCookieSecurityTest {

	private static final String COOKIE_NAME = "THETICKET_LANG";

	/**
	 * Accept-Language sættes eksplicit i hvert request. Uden headeren finder
	 * {@code MockHttpServletRequest} selv på en locale, og så ville testen måle
	 * maskinens standardsprog i stedet for vores egen fallback-kæde.
	 */
	private static final String UNSUPPORTED_BROWSER_LANGUAGE = "de-DE,de";

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
					  ('00000000-0000-4000-8000-0000000000c1', 'sec-a', 'Operator A', 'a.test')
					""");
		}
	}

	/**
	 * ANGREB: en cookie, {@code StringUtils.parseLocale} ikke kan læse. Den kan
	 * hverken komme fra vores egen sprogskifter eller fra en tidligere version —
	 * men den kan komme fra klienten selv eller fra en søskende-vært. Svaret må
	 * være siden på standardsproget, ikke en fejlside.
	 */
	@Test
	void givenAMalformedLanguageCookie_whenRequestingTheFrontPage_thenItIsIgnoredInsteadOfFailing() {
		for (String rubbish : List.of("!!", "..", "%%%", "<script>", "en/../../etc/passwd")) {
			MockHttpServletResponse response = get(rubbish, UNSUPPORTED_BROWSER_LANGUAGE);

			assertThat(response.getStatus())
					.as("a language cookie of [%s] must never be able to take the page down", rubbish)
					.isEqualTo(200);
		}
	}

	/**
	 * Ignoreret betyder ignoreret — ikke "tvunget til standardsproget". Ligger
	 * der en ulæselig cookie, skal resten af kæden stå ved magt, præcis som når
	 * {@code ?lang=} afvises.
	 */
	@Test
	void givenAMalformedLanguageCookie_whenTheBrowserAsksForASupportedLanguage_thenTheBrowserLanguageIsStillUsed() {
		assertThat(body(get("!!", "en-GB,en")))
				.as("an unreadable cookie must fall through to Accept-Language, not overrule it")
				.contains("lang=\"en\"");
	}

	/**
	 * ANGREB: en cookie med et sprog, der er velformet, men som vi ikke har
	 * tekster til. Den bliver læst som requestets locale, og siden bliver
	 * halvoversat: {@code html lang} siger ét sprog, teksterne kommer fra en
	 * fallback-bundle på et andet, og {@code Translations.localize} slår op på et
	 * sprog, der kun findes i databasen.
	 *
	 * <p>Der asserteres på {@code lang}-attributten og ikke på selve teksten:
	 * bundle-fallback for et ukendt sprog afhænger af maskinens
	 * system-locale ({@code spring.messages.fallback-to-system-locale}), mens
	 * {@code lang}-attributten er den locale, vi selv valgte.
	 */
	@Test
	void givenALanguageCookieWeHaveNoTextsFor_whenRequestingTheFrontPage_thenTheDefaultLanguageIsUsed() {
		for (String unsupported : List.of("de", "zz", "fr-FR", "nl-BE")) {
			MockHttpServletResponse response = get(unsupported, UNSUPPORTED_BROWSER_LANGUAGE);

			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(body(response))
					.as("a language cookie of [%s] must not put the page in a language we have no texts for",
							unsupported)
					.contains("lang=\"da\"");
		}
	}

	/**
	 * En regionsvariant af et sprog, vi HAR, skal give sproget — men på VORES
	 * form. Ellers går brugerens ordrette streng videre som opslagsnøgle til
	 * {@code Translations}, hvor kun de kanoniske sprogkoder findes.
	 */
	@Test
	void givenARegionalVariantInTheLanguageCookie_whenRequestingTheFrontPage_thenItIsNormalisedToTheCanonicalLanguage() {
		for (String variant : List.of("en-GB", "en-US", "en-US-x-hack")) {
			assertThat(body(get(variant, UNSUPPORTED_BROWSER_LANGUAGE)))
					.as("[%s] is English, and English is spelled 'en' here", variant)
					.contains("lang=\"en\"");
		}
	}

	/** Regressionsværn: et sprog, vi HAR, skal stadig komme igennem fra cookien og slå browserens. */
	@Test
	void givenASupportedLanguageCookie_whenRequestingTheFrontPage_thenTheRememberedLanguageIsStillUsed() {
		assertThat(body(get("en", "da-DK,da"))).contains("lang=\"en\"");
		assertThat(body(get("da", "en-GB,en"))).contains("lang=\"da\"");
	}

	private MockHttpServletResponse get(String cookieValue, String acceptLanguage) {
		return mvc.get().uri("/")
				.header(HttpHeaders.HOST, "a.test")
				.header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage)
				.cookie(new Cookie(COOKIE_NAME, cookieValue))
				.exchange().getResponse();
	}

	private static String body(MockHttpServletResponse response) {
		try {
			return response.getContentAsString();
		}
		catch (java.io.UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

}

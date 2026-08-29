package dk.aftermarked.web;

import java.util.UUID;

import dk.aftermarked.LocalizationConfig;
import dk.aftermarked.localization.Translations;
import dk.aftermarked.tenancy.Operator;
import dk.aftermarked.tenancy.OperatorId;
import dk.aftermarked.tenancy.OperatorRegistry;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Reglerne for HVILKET sprog en besøgende får, og hvordan valget huskes.
 *
 * <p>Rækkefølgen der bevises: {@code ?lang=} slår alt andet, ellers cookien,
 * ellers {@code Accept-Language} hvis vi understøtter sproget, ellers dansk.
 *
 * <p>Den vigtigste test er den sidste: {@code ?lang=} er <em>bruger-input</em>,
 * og bruger-input i dette system fejler lukket. Et ukendt sprog må hverken give
 * en fejlside, en halvoversat side eller en cookie, vi ikke har tekster til.
 */
@WebMvcTest(HelloController.class)
@Import({ dk.aftermarked.SecurityConfig.class, LocalizationConfig.class })
class LocaleResolutionHttpTest {

	private static final String DANISH = "Hallo verden. Skelettet kører.";

	private static final String ENGLISH = "Hello world. The skeleton is running.";

	@Autowired
	MockMvcTester mvc;

	@MockitoBean
	OperatorRegistry operators;

	@MockitoBean
	Translations translations;

	@BeforeEach
	void stubTheOperatorAndTranslations() {
		when(operators.current()).thenReturn(
				new Operator(new OperatorId(UUID.randomUUID()), "test", "Test Operator", "test.local"));
		when(translations.localize(any(), any(), any(), any(), any()))
				.thenAnswer(call -> call.getArgument(4));
	}

	@Test
	void givenAnUnsupportedAcceptLanguage_whenRequestingTheFrontPage_thenTheDefaultLanguageIsUsed() {
		assertThat(mvc.get().uri("/").header(HttpHeaders.ACCEPT_LANGUAGE, "de-DE,de"))
				.hasStatusOk()
				.bodyText()
				.as("we have no German texts — the language must fall back to Danish, not show key names")
				.contains(DANISH);
	}

	@Test
	void givenASupportedAcceptLanguage_whenRequestingTheFrontPage_thenTheBrowserLanguageIsUsed() {
		assertThat(mvc.get().uri("/").header(HttpHeaders.ACCEPT_LANGUAGE, "en-GB,en"))
				.hasStatusOk().bodyText().contains(ENGLISH);
		assertThat(mvc.get().uri("/").header(HttpHeaders.ACCEPT_LANGUAGE, "da-DK,da"))
				.hasStatusOk().bodyText().contains(DANISH);
	}

	@Test
	void givenTheLanguageParameter_whenItNamesASupportedLanguage_thenItOverridesTheBrowserLanguage() {
		assertThat(mvc.get().uri("/?lang=da").header(HttpHeaders.ACCEPT_LANGUAGE, "en-GB,en"))
				.hasStatusOk()
				.as("the user's own choice must outweigh the browser setting")
				.bodyText().contains(DANISH);
	}

	@Test
	void givenTheLanguageParameter_whenItNamesASupportedLanguage_thenTheChoiceIsRememberedInACookie() {
		MockHttpServletResponse response = mvc.get().uri("/?lang=en").exchange().getResponse();

		Cookie cookie = response.getCookie("THETICKET_LANG");
		assertThat(cookie).as("without a cookie the language would have to be chosen again on every click").isNotNull();
		assertThat(cookie.getValue()).isEqualTo("en");
		assertThat(cookie.isHttpOnly()).as("the language is read by the server only").isTrue();
		assertThat(cookie.getPath()).isEqualTo("/");
	}

	@Test
	void givenALanguageCookie_whenRequestingTheFrontPageWithoutTheParameter_thenTheRememberedLanguageIsUsed() {
		assertThat(mvc.get().uri("/")
				.cookie(new Cookie("THETICKET_LANG", "en"))
				.header(HttpHeaders.ACCEPT_LANGUAGE, "da-DK,da"))
				.hasStatusOk()
				.as("the remembered choice must beat the browser language")
				.bodyText().contains(ENGLISH);
	}

	/**
	 * FAIL-CLOSED. {@code ?lang=} er bruger-input, og et sprog vi ikke har
	 * tekster til må hverken give en fejl eller ændre noget. Den besøgende
	 * beholder præcis det sprog, vedkommende havde.
	 */
	@Test
	void givenAnUnsupportedLanguageParameter_whenRequestingTheFrontPage_thenItIsIgnoredAndNothingChanges() {
		for (String rubbish : java.util.List.of("klingon", "zz", "de", "../etc/passwd", "en_US; DROP", "")) {
			MockHttpServletResponse response = mvc.get().uri("/?lang=" + rubbish)
					.cookie(new Cookie("THETICKET_LANG", "da"))
					.exchange().getResponse();

			assertThat(response.getStatus()).as("[%s] must not produce an error page", rubbish).isEqualTo(200);
			assertThat(response.getCookie("THETICKET_LANG"))
					.as("[%s] must not overwrite the language the user already chose", rubbish)
					.isNull();
		}
	}

	/**
	 * En regionsvariant af et sprog, vi HAR — {@code en-US}, {@code en-GB} — er
	 * ikke skrald: brugeren beder om engelsk. Men det er os, der bestemmer
	 * formen. Cookien skal indeholde vores kanoniske {@code en}, aldrig
	 * brugerens egen streng, som ellers ville blive læst tilbage ved hvert
	 * senere request.
	 */
	@Test
	void givenARegionalVariantOfASupportedLanguage_whenRequestingTheFrontPage_thenItIsNormalisedToTheCanonicalLanguage() {
		for (String variant : java.util.List.of("en-US", "en-GB", "en-US-x-hack")) {
			MockHttpServletResponse response = mvc.get().uri("/?lang=" + variant).exchange().getResponse();

			assertThat(response.getCookie("THETICKET_LANG")).as("[%s]", variant).isNotNull();
			assertThat(response.getCookie("THETICKET_LANG").getValue())
					.as("[%s] must be normalised to our own language code", variant)
					.isEqualTo("en");
		}
	}

	/**
	 * Et POST må ikke kunne flytte sproget som sidegevinst — sprogskift er en
	 * GET-handling, og alt andet ville gøre en fremmed formular til en
	 * sprogskifter.
	 */
	@Test
	void givenTheLanguageParameterOnAPost_whenTheRequestIsMade_thenTheLanguageIsNotChanged() {
		MockHttpServletResponse response = mvc.post().uri("/?lang=en")
				.cookie(new Cookie("THETICKET_LANG", "da"))
				.exchange().getResponse();

		assertThat(response.getCookie("THETICKET_LANG"))
				.as("only GET may change the language")
				.isNull();
	}

	/** Sprogvælgeren skal findes på siden — ellers kan brugeren ikke bruge toggle'en. */
	@Test
	void givenAnyLanguage_whenRenderingThePage_thenTheLanguageSwitcherOffersEverySupportedLanguage() {
		assertThat(mvc.get().uri("/?lang=da")).hasStatusOk().bodyText()
				.contains("Dansk").contains("English")
				.as("the language you are already on must not be a link to itself")
				.contains("?lang=en").doesNotContain("?lang=da");
	}

	@Test
	void givenALanguage_whenRenderingThePage_thenTheHtmlLangAttributeMatchesIt() {
		assertThat(mvc.get().uri("/?lang=en")).hasStatusOk().bodyText().contains("lang=\"en\"");
		assertThat(mvc.get().uri("/?lang=da")).hasStatusOk().bodyText().contains("lang=\"da\"");
	}

}

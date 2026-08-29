package dk.aftermarked.web;

import java.util.Locale;
import java.util.UUID;

import dk.aftermarked.LocalizationConfig;
import dk.aftermarked.localization.Translations;
import dk.aftermarked.tenancy.Operator;
import dk.aftermarked.tenancy.OperatorId;
import dk.aftermarked.tenancy.OperatorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The front page must be open without a login, and the Thymeleaf template must render.
 * Needs no Docker — unlike the integration test against a real Postgres.
 * The operator lookup and the translation lookup are both mocked; resolution itself is
 * covered by the invariant test, and the translation store by {@code TranslationsTest}.
 */
@WebMvcTest(HelloController.class)
@Import({ dk.aftermarked.SecurityConfig.class, LocalizationConfig.class })
class HelloControllerTest {

	static final OperatorId OPERATOR = new OperatorId(UUID.randomUUID());

	@Autowired
	MockMvcTester mvc;

	@MockitoBean
	OperatorRegistry operators;

	@MockitoBean
	Translations translations;

	@BeforeEach
	void stubTheCurrentOperator() {
		when(operators.current()).thenReturn(new Operator(OPERATOR, "test", "Test Operator", "test.local"));
		// Uden oversaettelse falder localize tilbage paa grundteksten — det er
		// praecis den kontrakt, forsiden hviler paa.
		when(translations.localize(any(), any(), any(), any(), any()))
				.thenAnswer(call -> call.getArgument(4));
	}

	@Test
	void givenAnAnonymousVisitor_whenRequestingTheFrontPage_thenItRendersWithoutALogin() {
		assertThat(mvc.get().uri("/"))
				.hasStatusOk()
				.hasViewName("hello")
				.bodyText().contains("Test Operator");
	}

	/**
	 * Systemteksten kommer fra bundlen, ikke fra skabelonen. Beviset er, at den
	 * SKIFTER med sproget — en hardkodet streng ville se ens ud i begge kald.
	 * Selve sprogvalgets regler ligger i {@code LocaleResolutionHttpTest}.
	 */
	@Test
	void givenDifferentLanguages_whenRequestingTheFrontPage_thenTheSystemTextComesFromTheBundle() {
		assertThat(mvc.get().uri("/?lang=da")).hasStatusOk()
				.bodyText().contains("Hallo verden. Skelettet kører.").doesNotContain("Hello world");
		assertThat(mvc.get().uri("/?lang=en")).hasStatusOk()
				.bodyText().contains("Hello world. The skeleton is running.").doesNotContain("Hallo verden");
	}

	/**
	 * Kundens eget navn er ikke systemtekst. Findes der en oversættelse for
	 * sproget, er det DEN, der vises — grundteksten fra {@code operator.name}
	 * er kun fallback.
	 */
	@Test
	void givenATranslatedOperatorName_whenRequestingTheFrontPage_thenTheTranslationIsShownInsteadOfTheBaseText() {
		when(translations.localize(eq("operator"), eq(OPERATOR.value()), eq("name"), any(Locale.class), any()))
				.thenReturn("Translated Operator Name");

		assertThat(mvc.get().uri("/"))
				.hasStatusOk()
				.bodyText().contains("Translated Operator Name").doesNotContain("Test Operator");
	}

	@Test
	void givenAnAnonymousVisitor_whenRequestingAPathThatIsNotOpen_thenTheRequestIsUnauthorized() {
		assertThat(mvc.get().uri("/operator")).hasStatus(401);
	}

}

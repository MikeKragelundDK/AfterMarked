package dk.aftermarked.catalog;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.aftermarked.catalog.fields.CustomFieldDeclaration;
import dk.aftermarked.catalog.fields.CustomFieldSchema;
import dk.aftermarked.catalog.fields.CustomFieldScope;
import dk.aftermarked.catalog.fields.CustomFieldType;
import dk.aftermarked.catalog.internal.FieldSchemaProperties;
import dk.aftermarked.catalog.internal.FieldSchemaProperties.FieldDeclarationProperties;
import dk.aftermarked.catalog.internal.FieldSchemaProperties.OperatorFieldSchema;
import dk.aftermarked.catalog.internal.FieldSchemaRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feltskemaet, som det opløses ved OPSTART: {@code base.yml} plus operatørens
 * egen mappe, med de krydsfelt-regler, en enkelt deklaration ikke kan se.
 *
 * <p>Alt herunder handler om, at en config-fejl skal stoppe deployet og ikke et
 * natligt push klokken 03. Den vigtigste regel er, at et key aldrig skifter
 * type: en LAGRET værdis type skal blive ved med at være kendt, og der findes
 * ingen migration af en JSONB-kolonne, som ingen har skemaet til.
 *
 * <p>Testen er bevidst uden Spring på nær ét sted. Registret er ren Java over en
 * bunden record, så det kan bygges direkte — kun kontrakten "en ubunden nøgle er
 * en opstartsfejl" kræver Boots binder for overhovedet at kunne måles.
 */
class FieldSchemaRegistryTest {

	private static final String PILOT = "event-it";

	@Test
	void givenTwoDeclarationsOfTheSameKeyWithDifferentTypes_whenTheRegistryIsBuilt_thenStartupFails() {
		FieldSchemaProperties properties = new FieldSchemaProperties(
				schema(1, field("organizerLogo", "image", "organizer")),
				Map.of("acme", schema(1, field("organizerLogo", "url", "organizer"))));

		assertThatThrownBy(() -> new FieldSchemaRegistry(properties))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("organizerLogo")
				.hasMessageContaining("IMAGE")
				.hasMessageContaining("URL")
				.hasMessageContaining("never changes type");
	}

	@Test
	void givenTwoOverlappingWindowsForTheSameKey_whenTheRegistryIsBuilt_thenStartupFails() {
		FieldDeclarationProperties first = window("badge", "text", "event", 0, 5);
		FieldDeclarationProperties second = window("badge", "text", "event", 3, null);

		FieldSchemaProperties properties = new FieldSchemaProperties(schema(0),
				Map.of("acme", schema(1, first, second)));

		assertThatThrownBy(() -> new FieldSchemaRegistry(properties))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("badge")
				.hasMessageContaining("overlapping version windows");
	}

	@Test
	void givenTwoAdjacentWindowsForTheSameKey_whenTheRegistryIsBuilt_thenItIsLegalHistory() {
		FieldDeclarationProperties retired = window("badge", "text", "event", 0, 3);
		FieldDeclarationProperties reintroduced = window("badge", "text", "event", 3, null);

		FieldSchemaRegistry registry = new FieldSchemaRegistry(new FieldSchemaProperties(schema(0),
				Map.of("acme", schema(4, retired, reintroduced))));

		assertThat(registry.forOperator("acme").find(CustomFieldScope.EVENT, "badge")).isPresent();
		assertThat(registry.schemaAt("acme", 1).find(CustomFieldScope.EVENT, "badge")).isPresent();
	}

	/**
	 * {@code ignoreUnknownFields = false} er hele pointen: uden den bliver en
	 * stavefejl i en kundes config-fil stilfærdigt til en default, kunden tror,
	 * feltet er deklareret, og hvert eneste push afviser det som en ukendt nøgle.
	 */
	@Test
	void givenAFieldSchemaWithAMisspelledProperty_whenTheApplicationStarts_thenItRefusesToStart() {
		new ApplicationContextRunner()
				.withUserConfiguration(FieldSchemaConfiguration.class)
				.withPropertyValues("theticket.field-schema.operators.acme.version=1",
						"theticket.field-schema.operators.acme.fields[0].key=organizerLogo",
						"theticket.field-schema.operators.acme.fields[0].type=image",
						"theticket.field-schema.operators.acme.fields[0].scope=organizer",
						"theticket.field-schema.operators.acme.fields[0].labels.da=Logo")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasStackTraceContaining("labels");
				});
	}

	@Test
	void givenAWellSpelledFieldSchema_whenTheApplicationStarts_thenItBinds() {
		new ApplicationContextRunner()
				.withUserConfiguration(FieldSchemaConfiguration.class)
				.withPropertyValues("theticket.field-schema.operators.acme.version=1",
						"theticket.field-schema.operators.acme.fields[0].key=organizerLogo",
						"theticket.field-schema.operators.acme.fields[0].type=image",
						"theticket.field-schema.operators.acme.fields[0].scope=organizer")
				.run(context -> {
					assertThat(context).hasNotFailed();
					FieldSchemaProperties properties = context.getBean(FieldSchemaProperties.class);
					assertThat(properties.operators()).containsKey("acme");
				});
	}

	@Test
	void givenATranslatableDeclarationOnANonTextType_whenTheSchemaIsBound_thenItIsRejected() {
		FieldSchemaProperties properties = new FieldSchemaProperties(schema(0), Map.of("acme",
				schema(1, new FieldDeclarationProperties("eventImage", "image", "event", null, false, true,
						List.of(), 0, null))));

		assertThatThrownBy(() -> new FieldSchemaRegistry(properties))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("acme")
				.hasMessageContaining("eventImage")
				.hasMessageContaining("only TEXT and RICHTEXT carry translations");
	}

	/**
	 * En arrangør har intet event at validere valutaen imod, så beløbet ville
	 * ligge for evigt uvalideret i en jsonb, som {@code %_minor}-skanningen
	 * aldrig kan se.
	 */
	@Test
	void givenAMoneyFieldDeclaredForTheOrganizerScope_whenTheSchemaIsBound_thenItIsRejected() {
		FieldSchemaProperties properties = new FieldSchemaProperties(schema(0),
				Map.of("acme", schema(1, field("membershipFee", "money", "organizer"))));

		assertThatThrownBy(() -> new FieldSchemaRegistry(properties))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("membershipFee")
				.hasMessageContaining("MONEY");
	}

	@Test
	void givenAnEnumFieldDeclaration_whenReadingItsValues_thenTheyAreIdentifiersAndNotUserFacingText() {
		FieldSchemaRegistry registry = new FieldSchemaRegistry(new FieldSchemaProperties(schema(0),
				Map.of("acme", schema(1, new FieldDeclarationProperties("stageSize", "enum", "event", null,
						false, false, List.of("SMALL", "LARGE"), 0, null)))));

		CustomFieldDeclaration declaration = registry.forOperator("acme")
				.find(CustomFieldScope.EVENT, "stageSize")
				.orElseThrow();

		assertThat(declaration.values()).allSatisfy(value -> assertThat(value).matches("[A-Z][A-Z0-9_]*"));

		// Den brugervendte tekst kommer fra en noegle og aldrig fra en config-fil.
		FieldSchemaProperties userFacing = new FieldSchemaProperties(schema(0),
				Map.of("acme", schema(1, new FieldDeclarationProperties("stageSize", "enum", "event", null,
						false, false, List.of("Stor scene"), 0, null))));
		assertThatThrownBy(() -> new FieldSchemaRegistry(userFacing))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("identifiers, never user-facing text");
	}

	/**
	 * Læsesiden. En række bærer selv den version, den blev skrevet under, og et
	 * felt, der er trukket tilbage i dag, har stadig en kendt type i DEN version.
	 * Uden det ville en gammel værdi være utolkelig — og dermed reelt tabt.
	 */
	@Test
	void givenARetiredFieldWithAnUntilVersion_whenLookingUpItsType_thenTheDeclarationIsStillKnown() {
		FieldSchemaRegistry registry = new FieldSchemaRegistry(new FieldSchemaProperties(schema(0),
				Map.of("acme", schema(2, window("legacyBadge", "text", "event", 0, 2)))));

		assertThat(registry.forOperator("acme").find(CustomFieldScope.EVENT, "legacyBadge")).isEmpty();

		CustomFieldSchema asWritten = registry.schemaAt("acme", 1);
		assertThat(asWritten.find(CustomFieldScope.EVENT, "legacyBadge"))
				.map(CustomFieldDeclaration::type)
				.contains(CustomFieldType.TEXT);
	}

	@Test
	void givenAnOperatorWithoutItsOwnSchema_whenLookingItUp_thenTheBaseSchemaIsUsedAndAWarningNamesTheSlug() {
		FieldSchemaRegistry registry = new FieldSchemaRegistry(
				new FieldSchemaProperties(schema(1, field("sharedNote", "text", "event")), Map.of()));
		ListAppender<ILoggingEvent> recorded = attachAppender();

		CustomFieldSchema first = registry.forOperator("unconfigured-operator");
		CustomFieldSchema second = registry.forOperator("unconfigured-operator");

		assertThat(first.find(CustomFieldScope.EVENT, "sharedNote")).isPresent();
		assertThat(second).isEqualTo(first);
		assertThat(recorded.list)
				.as("the fallback is warned about exactly once per slug, not once per push")
				.singleElement()
				.satisfies(event -> assertThat(event.getFormattedMessage()).contains("unconfigured-operator"));
		detachAppender(recorded);
	}

	/**
	 * Labels står IKKE i YAML'en. Konventionen {@code field.<key>.label}
	 * fastlægges og testes her, mens mekanismen — en per-operatør
	 * {@code MessageSource} — er udskudt til den sprint, der først renderer et
	 * felt. En label i en config-fil ville omgå {@code MessageSource}, og
	 * {@code LocalizationOpsTest} ville aldrig se en manglende oversættelse.
	 */
	@Test
	void givenEveryDeclaredField_whenDerivingItsLabelKey_thenItFollowsTheFieldDotKeyDotLabelConvention() throws IOException {
		FieldSchemaRegistry registry = new FieldSchemaRegistry(shippedConfiguration());

		List<CustomFieldDeclaration> declarations = registry.forOperator(PILOT).declarations();

		assertThat(declarations).as("the shipped pilot profile must actually declare fields").isNotEmpty();
		assertThat(declarations).allSatisfy(declaration -> {
			assertThat(FieldSchemaRegistry.labelKey(declaration))
					.isEqualTo("field." + declaration.key() + ".label");
			assertThat(FieldSchemaRegistry.labelKey(declaration)).matches("field[.][a-zA-Z0-9]+[.]label");
		});
	}

	/**
	 * De filer, vi rent faktisk skibber. Binder de ikke, eller bryder de en
	 * krydsfelt-regel, nægter appen at starte i produktion — og det skal ses her
	 * og ikke der.
	 */
	@Test
	void givenTheShippedOperatorProfiles_whenResolvedThroughTheRegistry_thenThePilotFieldsAreDeclared() throws IOException {
		FieldSchemaRegistry registry = new FieldSchemaRegistry(shippedConfiguration());

		CustomFieldSchema pilot = registry.forOperator(PILOT);

		assertThat(pilot.find(CustomFieldScope.ORGANIZER, "organizerLogo"))
				.map(CustomFieldDeclaration::type)
				.contains(CustomFieldType.IMAGE);
		assertThat(pilot.find(CustomFieldScope.EVENT, "eventImage"))
				.map(CustomFieldDeclaration::role)
				.contains("branding.eventImage");
	}

	@Test
	void givenAnOperatorOverridingASharedFieldWithTheSameType_whenResolved_thenTheOperatorDeclarationWins() {
		FieldDeclarationProperties shared = new FieldDeclarationProperties("organizerLogo", "image", "organizer",
				"branding.logo", false, false, List.of(), 0, null);
		FieldDeclarationProperties own = new FieldDeclarationProperties("organizerLogo", "image", "organizer",
				"branding.mark", true, false, List.of(), 0, null);

		FieldSchemaRegistry registry = new FieldSchemaRegistry(
				new FieldSchemaProperties(schema(1, shared), Map.of("acme", schema(1, own))));

		assertThat(registry.forOperator("acme").forScope(CustomFieldScope.ORGANIZER))
				.singleElement()
				.satisfies(declaration -> {
					assertThat(declaration.role()).isEqualTo("branding.mark");
					assertThat(declaration.required()).isTrue();
				});
	}

	@Test
	void givenAFieldSchemaNamingATypeWeDoNotHave_whenTheRegistryIsBuilt_thenStartupFailsNamingTheLegalTypes() {
		FieldSchemaProperties properties = new FieldSchemaProperties(schema(0),
				Map.of("acme", schema(1, field("teaser", "markdown", "event"))));

		assertThatThrownBy(() -> new FieldSchemaRegistry(properties))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("teaser")
				.hasMessageContaining("markdown")
				.hasMessageContaining("richtext");
	}

	/**
	 * Binder de skibbede YAML-filer på præcis samme måde, som
	 * {@code spring.config.import} gør ved opstart, men uden en Spring-kontekst.
	 */
	private static FieldSchemaProperties shippedConfiguration() throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
		for (PropertySource<?> source : loader.load("base", new ClassPathResource("operators/base.yml"))) {
			environment.getPropertySources().addLast(source);
		}
		for (PropertySource<?> source : loader.load("event-it",
				new ClassPathResource("operators/event-it/field-schema.yml"))) {
			environment.getPropertySources().addLast(source);
		}
		return Binder.get(environment)
				.bind("theticket.field-schema", FieldSchemaProperties.class)
				.orElseThrow(() -> new IllegalStateException("the shipped operator profiles bound nothing"));
	}

	private static OperatorFieldSchema schema(int version, FieldDeclarationProperties... fields) {
		return new OperatorFieldSchema(version, List.of(fields));
	}

	private static FieldDeclarationProperties field(String key, String type, String scope) {
		return new FieldDeclarationProperties(key, type, scope, null, false, false, List.of(), 0, null);
	}

	private static FieldDeclarationProperties window(String key, String type, String scope, int since, Integer until) {
		return new FieldDeclarationProperties(key, type, scope, null, false, false, List.of(), since, until);
	}

	/**
	 * Advarslen aflæses på loggeren og ikke på {@code System.out}: en
	 * ConsoleAppender, en anden testklasse allerede har startet, holder fast i
	 * den oprindelige stream, og en aflæsning af standard-ud ville derfor være
	 * grøn eller rød afhængigt af, hvilke tests der kørte før.
	 */
	private static ListAppender<ILoggingEvent> attachAppender() {
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		((Logger) LoggerFactory.getLogger(FieldSchemaRegistry.class)).addAppender(appender);
		return appender;
	}

	private static void detachAppender(ListAppender<ILoggingEvent> appender) {
		((Logger) LoggerFactory.getLogger(FieldSchemaRegistry.class)).detachAppender(appender);
		appender.stop();
	}

	@EnableConfigurationProperties(FieldSchemaProperties.class)
	static class FieldSchemaConfiguration {

	}

}

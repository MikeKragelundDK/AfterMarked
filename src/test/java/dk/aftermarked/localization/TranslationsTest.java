package dk.aftermarked.localization;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;

import dk.aftermarked.TestcontainersConfiguration;
import dk.aftermarked.tenancy.OperatorContext;
import dk.aftermarked.tenancy.OperatorId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Translations} er indgangen til kundens egne oversatte tekster.
 *
 * <p>To ting skal holde: <b>1)</b> grundteksten er sandheden, så et manglende
 * sprog aldrig giver en tom side, og <b>2)</b> ingen kalder kan skrive ind i en
 * anden operatørs tekster, uanset hvad de sender med.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TranslationsTest {

	static final OperatorId A = new OperatorId(UUID.fromString("00000000-0000-4000-8000-00000000ab01"));

	static final OperatorId B = new OperatorId(UUID.fromString("00000000-0000-4000-8000-00000000ab02"));

	static final UUID ENTITY = UUID.fromString("00000000-0000-4000-8000-00000000ee01");

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	Translations translations;

	@BeforeEach
	void seedTwoOperators() throws SQLException {
		try (Connection owner = ownerConnection(); Statement sql = owner.createStatement()) {
			sql.execute("DELETE FROM translation");
			sql.execute("DELETE FROM operator");
			sql.execute("""
					INSERT INTO operator (id, slug, name, hostname) VALUES
					  ('%s', 'tx-a', 'Operator A', 'a.translations.test'),
					  ('%s', 'tx-b', 'Operator B', 'b.translations.test')
					""".formatted(A, B));
		}
	}

	@Test
	void givenNoTranslationForTheLanguage_whenLocalising_thenTheBaseTextIsReturned() {
		assertThat(OperatorContext.callAs(A,
				() -> translations.localize("operator", ENTITY, "name", Locale.of("en"), "Base text")))
				.as("a missing translation must never give an empty page or a key name")
				.isEqualTo("Base text");
	}

	@Test
	void givenATranslationForTheLanguage_whenLocalising_thenTheTranslationWinsOverTheBaseText() {
		OperatorContext.runAs(A, () -> translations.put("operator", ENTITY, "name", Locale.of("en"), "English name"));

		assertThat(OperatorContext.callAs(A,
				() -> translations.localize("operator", ENTITY, "name", Locale.of("en"), "Base text")))
				.isEqualTo("English name");
	}

	@Test
	void givenATranslationInOneLanguage_whenLocalisingAnother_thenTheBaseTextIsStillReturned() {
		OperatorContext.runAs(A, () -> translations.put("operator", ENTITY, "name", Locale.of("en"), "English name"));

		assertThat(OperatorContext.callAs(A,
				() -> translations.localize("operator", ENTITY, "name", Locale.of("da"), "Base text")))
				.as("English must not leak into Danish")
				.isEqualTo("Base text");
	}

	@Test
	void givenAnExistingTranslation_whenPuttingItAgain_thenItIsUpdatedInsteadOfDuplicated() {
		OperatorContext.runAs(A, () -> {
			translations.put("operator", ENTITY, "name", Locale.of("en"), "First");
			translations.put("operator", ENTITY, "name", Locale.of("en"), "Second");
		});

		assertThat(OperatorContext.callAs(A, () -> translations.find("operator", ENTITY, "name", Locale.of("en"))))
				.contains("Second");
	}

	/**
	 * Kernen i det hele: to kunder oversætter den samme entitet, og ingen af dem
	 * kan se den andens tekst. {@code operator_id} sættes fra konteksten, aldrig
	 * fra et argument — der er derfor ingen parameter at forgifte.
	 */
	@Test
	void givenTwoOperatorsTranslatingTheSameEntity_whenEachReadsItsOwn_thenTheyNeverSeeEachOthersText() {
		OperatorContext.runAs(A, () -> translations.put("operator", ENTITY, "name", Locale.of("en"), "A's name"));
		OperatorContext.runAs(B, () -> translations.put("operator", ENTITY, "name", Locale.of("en"), "B's name"));

		assertThat(OperatorContext.callAs(A, () -> translations.find("operator", ENTITY, "name", Locale.of("en"))))
				.contains("A's name");
		assertThat(OperatorContext.callAs(B, () -> translations.find("operator", ENTITY, "name", Locale.of("en"))))
				.contains("B's name");
	}

	@Test
	void givenNoOperatorContext_whenReadingATranslation_thenNothingIsFound() {
		OperatorContext.runAs(A, () -> translations.put("operator", ENTITY, "name", Locale.of("en"), "A's name"));

		assertThat(translations.find("operator", ENTITY, "name", Locale.of("en")))
				.as("without a context RLS sees nothing — here too")
				.isEmpty();
	}

	@Test
	void givenNoOperatorContext_whenWritingATranslation_thenItFailsLoudly() {
		assertThatThrownBy(() -> translations.put("operator", ENTITY, "name", Locale.of("en"), "no owner"))
				.as("a translation without an owner belongs nowhere")
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("No operator in context");
	}

	/** {@code Locale.of("da")} og {@code Locale.forLanguageTag("da")} skal lande samme sted. */
	@Test
	void givenTheSameLanguageBuiltTwoWays_whenLookingItUp_thenBothFindTheSameRow() {
		OperatorContext.runAs(A, () -> translations.put("operator", ENTITY, "name", Locale.of("da"), "Danish name"));

		assertThat(OperatorContext.callAs(A,
				() -> translations.find("operator", ENTITY, "name", Locale.forLanguageTag("da"))))
				.contains("Danish name");
	}

	private Connection ownerConnection() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

}

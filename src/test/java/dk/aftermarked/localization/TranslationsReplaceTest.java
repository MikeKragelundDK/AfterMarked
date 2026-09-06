package dk.aftermarked.localization;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import dk.aftermarked.TestcontainersConfiguration;
import dk.aftermarked.tenancy.OperatorContext;
import dk.aftermarked.tenancy.OperatorId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mængdeerstatningen af oversættelser — den del af katalog-pushet, ingen
 * eksisterende test kiggede på.
 *
 * <p>To ting skal holde, og de fejler begge <em>tavst</em>, hvis de ikke gør:
 *
 * <p><b>1) Et identisk andet push skriver ingenting om.</b> Uden
 * no-op-guarden på {@code ON CONFLICT DO UPDATE} får hver eneste
 * oversættelsesrække ny {@code updated_at} og ny {@code xmin} hver nat, og
 * sprintets exit-kriterie er falsk for hvert oversat felt — mens
 * katalogtabellerne, som idempotens-testen måler på, ser helt rolige ud.
 * Testene her sammenligner derfor rækkens {@code xmin} og {@code updated_at},
 * ikke bare dens værdi: en omskrivning til den samme tekst er stadig en
 * omskrivning.
 *
 * <p><b>2) Sletningen når aldrig uden for de felter, pushet ejer.</b> Var
 * erstatningen scopet til {@code entity_type} alene, ville det natlige push
 * slette operatørens egne admin-oversættelser af alle andre felter kl. 03 —
 * uden en linje i audit-loggen og uden nogen mulighed for at se hvorfor.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TranslationsReplaceTest {

	static final OperatorId A = new OperatorId(UUID.fromString("00000000-0000-4000-8000-00000000ba01"));

	static final UUID FIRST_EVENT = UUID.fromString("00000000-0000-4000-8000-00000000fe01");

	static final UUID SECOND_EVENT = UUID.fromString("00000000-0000-4000-8000-00000000fe02");

	/** Præcis de felter, et katalog-push ejer på et event (spec §3.3). */
	static final Set<String> OWNED_BY_THE_PUSH = Set.of("title", "venue");

	/**
	 * Kundens tekst kan indeholde hvad som helst. Værdien her rammer hver gren i
	 * den håndskrevne JSON-writer: citationstegn, backslash, linjeskift,
	 * tabulator, et styretegn der skal blive til en {@code \\u}-escape, et tegn
	 * uden for ASCII og et tegn uden for BMP (et surrogatpar).
	 */
	static final String AWKWARD_VALUE = "He said \"stop\", then a backslash \\, a newline\n"
			+ "a tab\tand the control character " + (char) 0x07
			+ ", the euro sign " + (char) 0x20AC
			+ " and an emoji " + Character.toString(0x1F600);

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	Translations translations;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void seedOneOperator() throws SQLException {
		try (Connection owner = ownerConnection(); Statement sql = owner.createStatement()) {
			sql.execute("DELETE FROM translation");
			sql.execute("DELETE FROM operator");
			sql.execute("""
					INSERT INTO operator (id, slug, name, hostname)
					VALUES ('%s', 'tr-replace', 'Operator A', 'a.replace.test')
					""".formatted(A));
		}
	}

	// --- No-op-guarden ------------------------------------------------------

	@Test
	void givenTheSameTranslationTwice_whenWritingIt_thenTheSecondWriteChangesNothing() {
		OperatorContext.runAs(A, () -> {
			assertThat(translations.put("event", FIRST_EVENT, "title", Locale.of("en"), "Spring Festival"))
					.as("the first write really writes")
					.isTrue();
			List<String> afterTheFirstWrite = fingerprints(FIRST_EVENT);

			assertThat(translations.put("event", FIRST_EVENT, "title", Locale.of("en"), "Spring Festival"))
					.as("an identical second write must report that it changed nothing")
					.isFalse();

			assertThat(fingerprints(FIRST_EVENT))
					.as("same value, same row version — updated_at and xmin must not have moved")
					.isEqualTo(afterTheFirstWrite);
		});
	}

	@Test
	void givenAnEntityWithTwoTranslations_whenReplacedWithTheSameTwo_thenNoRowIsRewritten() {
		List<TranslationRow> pushed = List.of(
				new TranslationRow(FIRST_EVENT, "title", "en", "Spring Festival"),
				new TranslationRow(FIRST_EVENT, "venue", "en", "The Old Warehouse"));

		OperatorContext.runAs(A, () -> {
			Translations.TranslationChange first = replaceOwned(FIRST_EVENT, pushed);
			assertThat(first).isEqualTo(new Translations.TranslationChange(2, 0));
			List<String> afterTheFirstPush = fingerprints(FIRST_EVENT);

			Translations.TranslationChange second = replaceOwned(FIRST_EVENT, pushed);

			assertThat(second)
					.as("the exit criterion: push twice, nothing changes")
					.isEqualTo(new Translations.TranslationChange(0, 0));
			assertThat(fingerprints(FIRST_EVENT)).isEqualTo(afterTheFirstPush);
		});
	}

	// --- Mængdesemantikken --------------------------------------------------

	@Test
	void givenAnEntityWithTwoTranslations_whenReplacedWithOne_thenTheOtherIsRemoved() {
		OperatorContext.runAs(A, () -> {
			replaceOwned(FIRST_EVENT, List.of(
					new TranslationRow(FIRST_EVENT, "title", "en", "Spring Festival"),
					new TranslationRow(FIRST_EVENT, "title", "de", "Spring Festival in German")));

			Translations.TranslationChange change = replaceOwned(FIRST_EVENT,
					List.of(new TranslationRow(FIRST_EVENT, "title", "en", "Spring Festival")));

			assertThat(change)
					.as("the German title is gone from the payload, so it must be gone from the table")
					.isEqualTo(new Translations.TranslationChange(0, 1));
			assertThat(texts(FIRST_EVENT)).containsExactly("title/en/Spring Festival");
		});
	}

	/**
	 * Kernen i {@code ownedFields}: operatørens egen admin-oversættelse af et
	 * felt, kunden ikke sender, må ikke forsvinde kl. 03.
	 */
	@Test
	void givenATranslationOfAFieldThePushDoesNotOwn_whenReplacingTheOwnedFields_thenItSurvives() {
		OperatorContext.runAs(A, () -> {
			translations.put("event", FIRST_EVENT, "description", Locale.of("en"), "Written in admin, not pushed");
			translations.put("event", FIRST_EVENT, "title", Locale.of("en"), "Spring Festival");

			Translations.TranslationChange change = replaceOwned(FIRST_EVENT, List.of());

			assertThat(change)
					.as("an empty payload is a pure delete — but only of the owned fields")
					.isEqualTo(new Translations.TranslationChange(0, 1));
			assertThat(texts(FIRST_EVENT)).containsExactly("description/en/Written in admin, not pushed");
		});
	}

	@Test
	void givenAnEntityWithTranslations_whenAnotherEntityIsReplaced_thenTheFirstIsUntouched() {
		OperatorContext.runAs(A, () -> {
			replaceOwned(FIRST_EVENT, List.of(new TranslationRow(FIRST_EVENT, "title", "en", "Spring Festival")));
			List<String> theFirstEventBefore = fingerprints(FIRST_EVENT);

			replaceOwned(SECOND_EVENT, List.of(new TranslationRow(SECOND_EVENT, "title", "en", "Autumn Festival")));

			assertThat(fingerprints(FIRST_EVENT))
					.as("replacing one entity must not touch another")
					.isEqualTo(theFirstEventBefore);
			assertThat(texts(SECOND_EVENT)).containsExactly("title/en/Autumn Festival");
		});
	}

	// --- Den håndskrevne JSON-writer ----------------------------------------

	@Test
	void givenAValueWithQuotesBackslashesAndNonAsciiCharacters_whenReplacedAndReadBack_thenItSurvivesUnchanged() {
		OperatorContext.runAs(A, () -> {
			translations.replaceAll("event", List.of(FIRST_EVENT), OWNED_BY_THE_PUSH,
					List.of(new TranslationRow(FIRST_EVENT, "title", "en", AWKWARD_VALUE)));

			assertThat(translations.find("event", FIRST_EVENT, "title", Locale.of("en")))
					.as("the hand-written JSON writer must escape the text, not mangle it")
					.contains(AWKWARD_VALUE);
		});
	}

	/**
	 * {@code U+0000} kan ikke lagres i en PostgreSQL {@code text}-kolonne
	 * overhovedet. Afvisningen skal derfor ske hos os, med en besked der peger på
	 * rækken — ikke nede i driveren midt i en transaktion.
	 */
	@Test
	void givenAValueContainingANulCharacter_whenBuildingATranslationRow_thenItIsRejectedWithAClearMessage() {
		assertThatThrownBy(() -> new TranslationRow(FIRST_EVENT, "title", "en", "before" + (char) 0 + "after"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("NUL character (U+0000)")
				.hasMessageContaining("title/en");
	}

	// --- Grænsetilfælde og kontrakten ---------------------------------------

	@Test
	void givenNoEntitiesAtAll_whenReplacingTheOwnedFields_thenNothingIsTouched() {
		OperatorContext.runAs(A, () -> {
			translations.put("event", FIRST_EVENT, "title", Locale.of("en"), "Spring Festival");

			Translations.TranslationChange change =
					translations.replaceAll("event", List.of(), OWNED_BY_THE_PUSH, List.of());

			assertThat(change)
					.as("an empty scope must be a safe no-op, not an invalid statement")
					.isEqualTo(new Translations.TranslationChange(0, 0));
			assertThat(texts(FIRST_EVENT)).containsExactly("title/en/Spring Festival");
		});
	}

	@Test
	void givenNoOperatorContext_whenReplacingTranslations_thenItFailsLoudly() {
		assertThatThrownBy(() -> translations.replaceAll("event", List.of(FIRST_EVENT), OWNED_BY_THE_PUSH,
				List.of(new TranslationRow(FIRST_EVENT, "title", "en", "no owner"))))
				.as("without a context the delete would silently reach nothing and report success")
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("No operator in context");
	}

	/**
	 * En række uden for {@code ownedFields} ville blive skrevet, men aldrig kunne
	 * fjernes igen af en senere erstatning — den ville være udødelig.
	 */
	@Test
	void givenARowForAFieldTheCallerDoesNotOwn_whenReplacing_thenItIsRejectedBeforeAnySqlRuns() {
		OperatorContext.runAs(A, () -> {
			assertThatThrownBy(() -> translations.replaceAll("event", List.of(FIRST_EVENT), OWNED_BY_THE_PUSH,
					List.of(new TranslationRow(FIRST_EVENT, "description", "en", "not ours to write"))))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("outside the owned fields");

			assertThat(texts(FIRST_EVENT)).isEmpty();
		});
	}

	/**
	 * PostgreSQL afviser selv to konfliktende rækker i én sætning med
	 * "ON CONFLICT DO UPDATE command cannot affect row a second time" — en besked
	 * der ikke peger på kalderens fejl. Vi fanger den før.
	 */
	@Test
	void givenTwoRowsForTheSameFieldAndLanguage_whenReplacing_thenTheDuplicateIsRejected() {
		OperatorContext.runAs(A, () -> assertThatThrownBy(() -> replaceOwned(FIRST_EVENT, List.of(
				new TranslationRow(FIRST_EVENT, "title", "en", "Spring Festival"),
				new TranslationRow(FIRST_EVENT, "title", "en", "Spring Festival Again"))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Duplicate translation row"));
	}

	// -----------------------------------------------------------------------

	private Translations.TranslationChange replaceOwned(UUID entityId, List<TranslationRow> rows) {
		return translations.replaceAll("event", List.of(entityId), OWNED_BY_THE_PUSH, rows);
	}

	/** Felt, sprog og tekst — det en læser af siden ville se. */
	private List<String> texts(UUID entityId) {
		return jdbc.sql("""
				SELECT field || '/' || locale || '/' || value FROM translation
				 WHERE entity_id = :entityId
				 ORDER BY field, locale
				""")
				.param("entityId", entityId)
				.query(String.class)
				.list();
	}

	/**
	 * Som {@link #texts}, men med rækkens identitet: {@code updated_at} og
	 * systemkolonnen {@code xmin}, som får en ny værdi hver gang rækken skrives.
	 * Det er den eneste måde at se forskel på "uændret" og "skrevet om til det
	 * samme".
	 */
	private List<String> fingerprints(UUID entityId) {
		return jdbc.sql("""
				SELECT field || '/' || locale || '/' || value
				       || '/' || CAST(updated_at AS text) || '/' || CAST(xmin AS text)
				  FROM translation
				 WHERE entity_id = :entityId
				 ORDER BY field, locale
				""")
				.param("entityId", entityId)
				.query(String.class)
				.list();
	}

	private Connection ownerConnection() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

}

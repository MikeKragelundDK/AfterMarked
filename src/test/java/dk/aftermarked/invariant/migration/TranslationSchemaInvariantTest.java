package dk.aftermarked.invariant.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dk.aftermarked.TestcontainersConfiguration;
import dk.aftermarked.tenancy.OperatorContext;
import dk.aftermarked.tenancy.OperatorId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INVARIANT — V3 og oversættelser af kundens egne tekster (PLAN §2.7 regel 1).
 * Denne test er spec.
 *
 * <p>{@code translation} bærer {@code operator_id} og er dermed omfattet af
 * præcis den regel, {@code operator} er: RLS slået til, en policy der dækker
 * begge retninger, og ingen vej for én kunde til at læse eller skrive en andens
 * tekster. Den generelle test i {@code invariant.tenancy} fanger, at policyen
 * <em>findes</em>; denne prøver aktivt at bryde den.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TranslationSchemaInvariantTest {

	static final OperatorId A = new OperatorId(UUID.fromString("00000000-0000-4000-8000-0000000000c1"));

	static final OperatorId B = new OperatorId(UUID.fromString("00000000-0000-4000-8000-0000000000d1"));

	static final UUID ENTITY = UUID.fromString("00000000-0000-4000-8000-00000000e001");

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void seedTwoOperatorsWithATranslationEach() throws SQLException {
		try (Connection owner = ownerConnection(); Statement sql = owner.createStatement()) {
			sql.execute("DELETE FROM translation");
			sql.execute("DELETE FROM operator");
			sql.execute("""
					INSERT INTO operator (id, slug, name, hostname) VALUES
					  ('%s', 'tr-a', 'Operator A', 'a.translation.test'),
					  ('%s', 'tr-b', 'Operator B', 'b.translation.test')
					""".formatted(A, B));
			sql.execute("""
					INSERT INTO translation (operator_id, entity_type, entity_id, field, locale, value) VALUES
					  ('%s', 'operator', '%s', 'name', 'en', 'A in English'),
					  ('%s', 'operator', '%s', 'name', 'en', 'B in English')
					""".formatted(A, ENTITY, B, ENTITY));
		}
	}

	// --- RLS ---------------------------------------------------------------

	@Test
	void givenNoOperatorContext_whenReadingTranslations_thenNothingIsVisible() {
		assertThat(values()).isEmpty();
	}

	@Test
	void givenABoundOperatorContext_whenReadingTranslations_thenOnlyItsOwnAreVisible() {
		assertThat(OperatorContext.callAs(A, this::values)).containsExactly("A in English");
		assertThat(OperatorContext.callAs(B, this::values)).containsExactly("B in English");
	}

	/**
	 * Samme entitet, samme felt, samme sprog — men to kunder. Ville isolationen
	 * svigte her, ville den ene kundes oversættelse kunne vises på den andens
	 * markedsplads.
	 */
	@Test
	void givenTwoOperatorsTranslatingTheSameEntity_whenEachReadsIt_thenTheyNeverSeeEachOthersText() {
		assertThat(OperatorContext.callAs(A, this::values)).doesNotContain("B in English");
		assertThat(OperatorContext.callAs(B, this::values)).doesNotContain("A in English");
	}

	@Test
	void givenTheContextOfOperatorA_whenUpdatingOperatorBsTranslation_thenNothingIsChanged() throws SQLException {
		int updated = OperatorContext.callAs(A, () -> jdbc
				.sql("UPDATE translation SET value = 'hijacked' WHERE operator_id = :id")
				.param("id", B.value()).update());

		assertThat(updated).as("A must not be able to touch B's texts").isZero();
		assertThat(allValuesAsOwner()).contains("B in English").doesNotContain("hijacked");
	}

	@Test
	void givenTheContextOfOperatorA_whenDeletingOperatorBsTranslation_thenNothingIsDeleted() throws SQLException {
		int deleted = OperatorContext.callAs(A, () -> jdbc
				.sql("DELETE FROM translation WHERE operator_id = :id").param("id", B.value()).update());

		assertThat(deleted).isZero();
		assertThat(allValuesAsOwner()).contains("B in English");
	}

	/** WITH CHECK-halvdelen: A må ikke kunne skrive en række, der TILHØRER B. */
	@Test
	void givenTheContextOfOperatorA_whenInsertingARowOwnedByOperatorB_thenThePolicyRejectsIt() {
		assertThatThrownBy(() -> OperatorContext.runAs(A, () -> jdbc.sql("""
				INSERT INTO translation (operator_id, entity_type, entity_id, field, locale, value)
				VALUES (:operatorId, 'operator', :entityId, 'name', 'da', 'smuglet ind')
				""").param("operatorId", B.value()).param("entityId", ENTITY).update()))
				.isInstanceOf(DataAccessException.class)
				.rootCause().hasMessageContaining("row-level security");
	}

	@Test
	void givenNoOperatorContext_whenUpdatingOrDeletingEverything_thenNothingIsChanged() throws SQLException {
		assertThat(jdbc.sql("UPDATE translation SET value = 'wiped'").update()).isZero();
		assertThat(jdbc.sql("DELETE FROM translation").update()).isZero();
		assertThat(allValuesAsOwner()).hasSize(2);
	}

	// --- Constraints --------------------------------------------------------

	@Test
	void givenAnInvalidLocale_whenInsertingATranslation_thenTheCheckConstraintRejectsIt() {
		for (String invalid : List.of("DA", "dansk", "d", "da_DK", "da-dk", "", "en-GBB")) {
			assertThatThrownBy(() -> insertAsOwner("name", invalid, "text"))
					.as("the language code [%s] must be rejected", invalid)
					.isInstanceOf(SQLException.class)
					.extracting(error -> ((SQLException) error).getSQLState())
					.isEqualTo("23514");
		}
	}

	@Test
	void givenAValidLocale_whenInsertingATranslation_thenItPassesTheCheckConstraint() throws SQLException {
		for (String valid : List.of("da", "en", "en-GB", "sv")) {
			insertAsOwner("field_" + valid.toLowerCase().replace("-", "_"), valid, "text");
		}
	}

	/**
	 * En tom oversættelse ville skygge for grundteksten og vise brugeren
	 * ingenting — værre end slet ingen oversættelse.
	 */
	@Test
	void givenABlankValue_whenInsertingATranslation_thenTheCheckConstraintRejectsIt() {
		for (String blank : List.of("", "   ", "\t", "\n")) {
			assertThatThrownBy(() -> insertAsOwner("name", "da", blank))
					.as("a blank value [%s] is not a translation", blank.replace("\n", "\\n"))
					.isInstanceOf(SQLException.class)
					.extracting(error -> ((SQLException) error).getSQLState())
					.isEqualTo("23514");
		}
	}

	/** Én oversættelse pr. felt pr. sprog — ellers er det udefineret, hvilken der vises. */
	@Test
	void givenAnExistingTranslation_whenInsertingTheSameFieldAndLocaleAgain_thenTheUniqueConstraintRejectsIt() {
		assertThatThrownBy(() -> OperatorContext.runAs(A, () -> jdbc.sql("""
				INSERT INTO translation (operator_id, entity_type, entity_id, field, locale, value)
				VALUES (:operatorId, 'operator', :entityId, 'name', 'en', 'a second English name')
				""").param("operatorId", A.value()).param("entityId", ENTITY).update()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	/**
	 * To kunder må derimod GERNE have hver sin oversættelse af samme entitet og
	 * felt — unique-nøglen begynder med {@code operator_id} netop derfor.
	 */
	@Test
	void givenTwoOperators_whenBothTranslateTheSameEntityAndField_thenBothRowsAreAllowed() throws SQLException {
		assertThat(allValuesAsOwner())
				.as("the seed has already inserted exactly that — both rows must exist")
				.containsExactlyInAnyOrder("A in English", "B in English");
	}

	// --- Rettigheder --------------------------------------------------------

	/**
	 * {@code ALTER DEFAULT PRIVILEGES} fra V1 skal have ramt en tabel oprettet i
	 * V3. Uden det ville hele oversættelseslaget være utilgængeligt for appen —
	 * og fejlen ville først vise sig ved første opslag i produktion.
	 */
	@Test
	void givenTheRuntimeRole_whenInspectingItsPrivilegesOnTranslation_thenItHasFullCrudButNoTruncate() {
		assertThat(tablePrivilege("SELECT")).isTrue();
		assertThat(tablePrivilege("INSERT")).isTrue();
		assertThat(tablePrivilege("UPDATE")).isTrue();
		assertThat(tablePrivilege("DELETE")).isTrue();
		assertThat(tablePrivilege("TRUNCATE")).as("TRUNCATE bypasses RLS completely").isFalse();
	}

	// -----------------------------------------------------------------------

	private List<String> values() {
		return jdbc.sql("SELECT value FROM translation ORDER BY value").query(String.class).list();
	}

	private boolean tablePrivilege(String privilege) {
		return jdbc.sql("SELECT has_table_privilege('theticket_app', 'translation', :privilege)")
				.param("privilege", privilege).query(Boolean.class).single();
	}

	/** Indsætter som ejeren, så RLS er ude af billedet og CHECK-constrainten er den eneste dør. */
	private void insertAsOwner(String field, String locale, String value) throws SQLException {
		try (Connection owner = ownerConnection();
				var sql = owner.prepareStatement("""
						INSERT INTO translation (operator_id, entity_type, entity_id, field, locale, value)
						VALUES (?, 'operator', ?, ?, ?, ?)
						""")) {
			sql.setObject(1, A.value());
			sql.setObject(2, ENTITY);
			sql.setString(3, field);
			sql.setString(4, locale);
			sql.setString(5, value);
			sql.executeUpdate();
		}
	}

	private List<String> allValuesAsOwner() throws SQLException {
		List<String> values = new ArrayList<>();
		try (Connection owner = ownerConnection(); Statement sql = owner.createStatement();
				ResultSet rs = sql.executeQuery("SELECT value FROM translation ORDER BY value")) {
			while (rs.next()) {
				values.add(rs.getString(1));
			}
		}
		return values;
	}

	private Connection ownerConnection() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

}

package dk.aftermarked.ops;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RLS skal beskytte skrivning såvel som læsning. Gyldige fremmednøgler og nye
 * refs sikrer, at afviste INSERT og UPDATE måler policyen, ikke en anden constraint.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CatalogRlsWriteOpsTest {

	static final OperatorId A = new OperatorId(UUID.fromString("00000000-0000-4000-8000-00000000d901"));

	static final OperatorId B = new OperatorId(UUID.fromString("00000000-0000-4000-8000-00000000d902"));

	static final UUID A_ORGANIZER = UUID.fromString("00000000-0000-4000-8000-00000000d911");

	static final UUID B_ORGANIZER = UUID.fromString("00000000-0000-4000-8000-00000000d912");

	static final UUID A_EVENT = UUID.fromString("00000000-0000-4000-8000-00000000d921");

	static final UUID B_EVENT = UUID.fromString("00000000-0000-4000-8000-00000000d922");

	static final List<String> READS = List.of(
			"SELECT name FROM organizer ORDER BY id",
			"SELECT title FROM event ORDER BY id",
			"SELECT name FROM ticket_type ORDER BY id",
			"SELECT entity_ref FROM audit_log ORDER BY id");

	static final List<String> FOREIGN_INSERTS = List.of(
			"""
			INSERT INTO organizer (operator_id, ref, name)
			VALUES (:operator, 'foreign', 'Foreign')
			""",
			"""
			INSERT INTO event (operator_id, organizer_id, ref, title, starts_at, sales_status, currency)
			VALUES (:operator, :organizer, 'foreign', 'Foreign',
			        TIMESTAMPTZ '2027-06-30 16:00:00+00', 'UNKNOWN', 'DKK')
			""",
			"""
			INSERT INTO ticket_type (operator_id, event_id, ref, name, face_value_minor, currency)
			VALUES (:operator, :event, 'foreign', 'Foreign', 1000, 'DKK')
			""",
			"""
			INSERT INTO audit_log (operator_id, actor_type, action, entity_type, entity_ref)
			VALUES (:operator, 'system', 'event.created', 'event', 'Foreign')
			""");

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void seedTwoOperatorsWithAllCatalogRows() throws SQLException {
		try (Connection owner = DriverManager.getConnection(
				postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
			owner.setAutoCommit(false);
			execute(owner, "DELETE FROM operator WHERE id IN (?, ?)", A.value(), B.value());
			seedCatalog(owner, A, A_ORGANIZER, A_EVENT, "rls-write-a", "a.rls-write.test", "Catalog A");
			seedCatalog(owner, B, B_ORGANIZER, B_EVENT, "rls-write-b", "b.rls-write.test", "Catalog B");
			owner.commit();
		}
	}

	@Test
	void givenTwoCatalogs_whenReadingEveryCatalogTable_thenOnlyTheBoundOperatorsRowsAreVisible() {
		for (String query : READS) {
			assertThat(OperatorContext.callAs(A, () -> jdbc.sql(query).query(String.class).list()))
					.as(query).containsExactly("Catalog A");
			assertThat(OperatorContext.callAs(B, () -> jdbc.sql(query).query(String.class).list()))
					.as(query).containsExactly("Catalog B");
			assertThat(jdbc.sql(query).query(String.class).list()).as(query).isEmpty();
		}
	}

	@Test
	void givenAnotherOperatorsValidParents_whenInsertingTheirOperatorId_thenEveryPolicyRejectsTheWrite() {
		for (String query : FOREIGN_INSERTS) {
			assertThatThrownBy(() -> OperatorContext.runAs(A, () -> insertForB(query)))
					.as(query).isInstanceOf(DataAccessException.class)
					.rootCause().hasMessageContaining("row-level security");
		}
	}

	@Test
	void givenNoOperatorContext_whenInsertingAnExplicitOperatorId_thenEveryPolicyRejectsTheWrite() {
		for (String query : FOREIGN_INSERTS) {
			assertThatThrownBy(() -> insertForB(query))
					.as(query).isInstanceOf(DataAccessException.class)
					.rootCause().hasMessageContaining("row-level security");
		}
	}

	@Test
	void givenOwnCatalogRows_whenMovingThemToAnotherOperatorsValidParents_thenWithCheckRejectsTheWrite() {
		List<String> moves = List.of(
				"UPDATE organizer SET operator_id = :operator, ref = 'moved'",
				"UPDATE event SET operator_id = :operator, organizer_id = :organizer, ref = 'moved'",
				"UPDATE ticket_type SET operator_id = :operator, event_id = :event, ref = 'moved'");

		for (String query : moves) {
			assertThatThrownBy(() -> OperatorContext.runAs(A, () -> insertForB(query)))
					.as(query).isInstanceOf(DataAccessException.class)
					.rootCause().hasMessageContaining("row-level security");
		}
	}

	@Test
	void givenAnotherOperatorsCatalogRows_whenUpdatingOrDeletingThem_thenNoRowsAreAffected() {
		List<String> mutations = List.of(
				"UPDATE organizer SET name = 'Tampered' WHERE operator_id = :operator",
				"UPDATE event SET title = 'Tampered' WHERE operator_id = :operator",
				"UPDATE ticket_type SET name = 'Tampered' WHERE operator_id = :operator",
				"DELETE FROM ticket_type WHERE operator_id = :operator",
				"DELETE FROM event WHERE operator_id = :operator",
				"DELETE FROM organizer WHERE operator_id = :operator");

		for (String query : mutations) {
			assertThat(OperatorContext.callAs(A,
					() -> jdbc.sql(query).param("operator", B.value()).update())).as(query).isZero();
		}
		for (String query : READS) {
			assertThat(OperatorContext.callAs(B, () -> jdbc.sql(query).query(String.class).list()))
					.as(query).containsExactly("Catalog B");
		}
	}

	private void insertForB(String query) {
		jdbc.sql(query).param("operator", B.value()).param("organizer", B_ORGANIZER)
				.param("event", B_EVENT).update();
	}

	private static void seedCatalog(Connection owner, OperatorId operator, UUID organizer, UUID event,
			String slug, String hostname, String label) throws SQLException {
		execute(owner, "INSERT INTO operator (id, slug, name, hostname) VALUES (?, ?, ?, ?)",
				operator.value(), slug, label, hostname);
		execute(owner, "INSERT INTO organizer (id, operator_id, ref, name) VALUES (?, ?, 'shared', ?)",
				organizer, operator.value(), label);
		execute(owner, """
				INSERT INTO event (id, operator_id, organizer_id, ref, title, starts_at, sales_status, currency)
				VALUES (?, ?, ?, 'shared', ?, TIMESTAMPTZ '2027-06-30 16:00:00+00', 'UNKNOWN', 'DKK')
				""", event, operator.value(), organizer, label);
		execute(owner, """
				INSERT INTO ticket_type (operator_id, event_id, ref, name, face_value_minor, currency)
				VALUES (?, ?, 'shared', ?, 1000, 'DKK')
				""", operator.value(), event, label);
		execute(owner, """
				INSERT INTO audit_log (operator_id, actor_type, action, entity_type, entity_ref)
				VALUES (?, 'system', 'event.created', 'event', ?)
				""", operator.value(), label);
	}

	/** Alle SQL-sætninger er faste; værdier bindes også under seedning som ejeren. */
	private static void execute(Connection connection, String query, Object... values) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(query)) {
			for (int index = 0; index < values.length; index++) {
				statement.setObject(index + 1, values[index]);
			}
			statement.executeUpdate();
		}
	}

}

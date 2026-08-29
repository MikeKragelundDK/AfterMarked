package dk.aftermarked.tenancy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Two operators to try reading across. Seeded as the owner (the Flyway user),
 * because the runtime role is blocked by RLS and therefore cannot insert test data
 * itself — which is a point in its own right.
 *
 * <p>The host names match the ones the invariant test uses, so the two sets of tests
 * can be read together.
 */
final class OperatorSeed {

	static final OperatorId A = new OperatorId(UUID.fromString("00000000-0000-4000-8000-00000000000a"));

	static final OperatorId B = new OperatorId(UUID.fromString("00000000-0000-4000-8000-00000000000b"));

	/** An operator that does not exist in the database — "deleted" as seen from the context. */
	static final OperatorId DELETED = new OperatorId(UUID.fromString("00000000-0000-4000-8000-0000000000ff"));

	private OperatorSeed() {
	}

	static void insertTwoOperators(PostgreSQLContainer postgres) throws SQLException {
		try (Connection owner = ownerConnection(postgres); Statement sql = owner.createStatement()) {
			sql.execute("DELETE FROM operator");
			sql.execute("""
					INSERT INTO operator (id, slug, name, hostname) VALUES
					  ('%s', 'op-a', 'Operator A', 'a.test'),
					  ('%s', 'op-b', 'Operator B', 'b.test')
					""".formatted(A, B));
		}
	}

	static Connection ownerConnection(PostgreSQLContainer postgres) throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

}

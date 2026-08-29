package dk.aftermarked.invariant.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INVARIANT — what V1 actually enforces in the database (PLAN §2.7, CLAUDE.md).
 * This test is spec.
 *
 * <p>{@code TenantIsolationInvariantTest} proves that the application cannot read or
 * write across tenants. This test goes one layer down and examines the mechanisms it
 * rests on — the shape of the policy, the failure paths of the functions, the
 * privileges of the roles and the CHECK constraints — including the routes the
 * application cannot take itself, but that an attacker with an SQL injection would.
 *
 * <p>Every raw connection in this test is its own {@link DriverManager} connection, not
 * borrowed from the pool. Several of the tests deliberately leave a dirty session
 * behind (an invalid operator context, a temp table), and that must never be passed on
 * to another test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BaselineSchemaInvariantTest {

	static final OperatorId A = new OperatorId(UUID.fromString("00000000-0000-4000-8000-0000000000a1"));

	static final OperatorId B = new OperatorId(UUID.fromString("00000000-0000-4000-8000-0000000000b1"));

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void seedTwoOperators() throws SQLException {
		try (Connection owner = ownerConnection(); Statement sql = owner.createStatement()) {
			sql.execute("DELETE FROM operator");
			sql.execute("""
					INSERT INTO operator (id, slug, name, hostname) VALUES
					  ('%s', 'mig-a', 'Operator A', 'a.migration.test'),
					  ('%s', 'mig-b', 'Operator B', 'b.migration.test')
					""".formatted(A, B));
		}
	}

	// ------------------------------------------------------------------
	// current_operator_id()
	// ------------------------------------------------------------------

	/** No context → NULL → no rows. The base assumption behind the whole policy. */
	@Test
	void givenNoOperatorContext_whenReadingTheContextAndTheTable_thenTheIdIsNullAndNoRowsAreVisible() {
		assertThat(jdbc.sql("SELECT current_operator_id()").query(UUID.class).optional()).isEmpty();
		assertThat(jdbc.sql("SELECT count(*) FROM operator").query(Integer.class).single()).isZero();
	}

	/**
	 * A session variable that cannot be cast to uuid must fail LOUDLY. The dangerous
	 * alternative would be a function that swallowed the error and returned NULL — then
	 * a poisoned context would look like "no context" instead of stopping the request.
	 *
	 * <p>The application cannot reach this state itself ({@code TenantAwareDataSource}
	 * sets either a {@link UUID} or the empty string), but an SQL injection can.
	 */
	@Test
	void givenAnUncastableOperatorContext_whenQueryingTheTable_thenItFailsLoudlyAndClosed() throws SQLException {
		try (Connection runtime = runtimeConnection(); Statement sql = runtime.createStatement()) {
			sql.execute("SELECT set_config('app.operator_id', 'not-a-uuid', false)");

			assertThatThrownBy(() -> sql.executeQuery("SELECT current_operator_id()"))
					.as("the function must not swallow an invalid value")
					.isInstanceOf(SQLException.class)
					.extracting(error -> ((SQLException) error).getSQLState())
					.isEqualTo("22P02");

			assertThatThrownBy(() -> sql.executeQuery("SELECT slug FROM operator"))
					.as("the query must fail — not return everything, and not silently return nothing")
					.isInstanceOf(SQLException.class)
					.extracting(error -> ((SQLException) error).getSQLState())
					.isEqualTo("22P02");
		}
	}

	/**
	 * {@code STABLE} is the right volatility level: the value is constant within one
	 * statement (so the policy can be used in an index lookup), but is read afresh on
	 * the next statement. The test runs the SAME prepared statement far past pgjdbc's
	 * {@code prepareThreshold} (5), so the server switches to a generic plan — that is
	 * where an erroneous {@code IMMUTABLE} marking would freeze the first operator and
	 * start showing one customer's data to another.
	 */
	@Test
	void givenAStatementRunningOnAGenericPlan_whenTheContextChanges_thenItIsRereadOnEveryStatement()
			throws SQLException {
		try (Connection runtime = runtimeConnection();
				PreparedStatement setContext = runtime.prepareStatement("SELECT set_config('app.operator_id', ?, false)");
				PreparedStatement readSlug = runtime.prepareStatement("SELECT slug FROM operator")) {

			for (int round = 0; round < 8; round++) {
				OperatorId expected = round % 2 == 0 ? A : B;
				String expectedSlug = round % 2 == 0 ? "mig-a" : "mig-b";

				setContext.setString(1, expected.toString());
				setContext.execute();

				List<String> seen = new ArrayList<>();
				try (ResultSet rs = readSlug.executeQuery()) {
					while (rs.next()) {
						seen.add(rs.getString(1));
					}
				}
				assertThat(seen).as("round %d — the context must take effect every time", round)
						.containsExactly(expectedSlug);
			}
		}
	}

	// ------------------------------------------------------------------
	// operator_id_for_host() — SECURITY DEFINER
	// ------------------------------------------------------------------

	@Test
	void givenAHostNameInMixedCase_whenLookingItUp_thenTheLookupIsCaseInsensitive() {
		assertThat(lookup("A.Migration.TEST")).isEqualTo(A.value());
		assertThat(lookup("a.migration.test")).isEqualTo(A.value());
	}

	/**
	 * The host name is stored without a port, and the lookup must not take it upon
	 * itself to strip one. {@code HttpServletRequest.getServerName()} never delivers a
	 * port, so a lookup carrying one is by definition not a real request.
	 */
	@Test
	void givenAHostNameWithAPortOrAnUnknownHost_whenLookingItUp_thenNoOperatorIsReturned() {
		assertThat(lookup("a.migration.test:8080")).isNull();
		assertThat(lookup("unknown.migration.test")).isNull();
		assertThat(lookup("")).isNull();
		assertThat(lookup(null)).isNull();
	}

	/**
	 * ⛔ The most important test in the file. {@code operator_id_for_host} is
	 * {@code SECURITY DEFINER} and thus runs as the owner, who bypasses RLS.
	 *
	 * <p>If {@code pg_temp} is left out of the function's {@code search_path}, Postgres
	 * searches it anyway — and as the FIRST schema, before {@code pg_catalog}, when
	 * relations are resolved. A calling session can therefore create its own
	 * {@code operator} table and decide for itself which operator id the context lookup
	 * hands back. Temp tables live as long as the connection does, so one injected
	 * statement would poison every single later request on that pooled connection.
	 *
	 * <p>The migration places {@code pg_temp} explicitly LAST. The test performs the
	 * attack and demands the right answer.
	 */
	@Test
	void givenATempTableShadowingTheOperatorTable_whenCallingTheSecurityDefinerLookup_thenItStillReadsThePublicTable()
			throws SQLException {
		try (Connection runtime = runtimeConnection(); Statement sql = runtime.createStatement()) {
			sql.execute("CREATE TEMP TABLE operator (id uuid, slug text, name text, hostname text)");
			sql.execute("INSERT INTO operator (id, slug, name, hostname) VALUES "
					+ "('%s', 'fake', 'Fake', 'a.migration.test')".formatted(UUID.randomUUID()));

			try (ResultSet rs = sql.executeQuery("SELECT operator_id_for_host('a.migration.test')")) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getObject(1, UUID.class))
						.as("the function must read public.operator — not the temp table the caller made itself")
						.isEqualTo(A.value());
			}
		}
	}

	/**
	 * {@code REVOKE ALL ... FROM PUBLIC} must be enough: an arbitrary new role must not
	 * be able to call the cross-tenant lookup, and the function must only be able to
	 * return a uuid — never the name, the slug or the row, which would turn it into an
	 * enumeration tool.
	 */
	@Test
	void givenAnArbitraryNewRole_whenCheckingTheCrossTenantLookup_thenItIsClosedToItAndReturnsOnlyAUuid()
			throws SQLException {
		try (Connection owner = ownerConnection(); Statement sql = owner.createStatement()) {
			sql.execute("DROP ROLE IF EXISTS migration_test_curious");
			sql.execute("CREATE ROLE migration_test_curious NOLOGIN");
			try {
				assertThat(hasFunctionPrivilege(sql, "migration_test_curious", "operator_id_for_host(text)"))
						.as("a new role does not inherit the privilege from PUBLIC").isFalse();
				assertThat(hasFunctionPrivilege(sql, "theticket_app", "operator_id_for_host(text)"))
						.as("the runtime group must be able to call it — otherwise the context lookup does not work")
						.isTrue();

				try (ResultSet rs = sql.executeQuery(
						"SELECT pg_get_function_result(oid), prosecdef FROM pg_proc WHERE proname = 'operator_id_for_host'")) {
					assertThat(rs.next()).isTrue();
					assertThat(rs.getString(1)).as("the return type limits the leak to a single id").isEqualTo("uuid");
					assertThat(rs.getBoolean(2)).isTrue();
				}
			}
			finally {
				sql.execute("DROP ROLE IF EXISTS migration_test_curious");
			}
		}
	}

	// ------------------------------------------------------------------
	// The shape of the policy, and DELETE
	// ------------------------------------------------------------------

	/**
	 * The policy is written without {@code FOR} and without {@code TO} and therefore
	 * covers ALL commands for ALL roles. {@code USING} applies to SELECT, UPDATE and
	 * DELETE; {@code WITH CHECK} applies to the rows INSERT and UPDATE leave behind. If
	 * one half is missing, the hole exists in only one direction — and that is exactly
	 * the kind of thing nobody spots by reading the SQL.
	 */
	@Test
	void givenTheOperatorPolicy_whenInspectingItsShape_thenItCoversAllCommandsForAllRolesWithUsingAndWithCheck()
			throws SQLException {
		try (Connection owner = ownerConnection(); Statement sql = owner.createStatement();
				ResultSet rs = sql.executeQuery("""
						SELECT p.polname, p.polcmd, p.polpermissive, p.polroles::text AS roles,
						       p.polqual IS NOT NULL AS has_using,
						       p.polwithcheck IS NOT NULL AS has_with_check
						FROM pg_policy p JOIN pg_class c ON c.oid = p.polrelid
						WHERE c.relname = 'operator'
						""")) {
			assertThat(rs.next()).as("operator must have exactly one policy").isTrue();
			assertThat(rs.getString("polname")).isEqualTo("operator_tenant_isolation");
			assertThat(rs.getString("polcmd")).as("'*' = FOR ALL — no command slips past").isEqualTo("*");
			assertThat(rs.getBoolean("polpermissive")).isTrue();
			assertThat(rs.getString("roles"))
					.as("the policy applies to PUBLIC (oid 0), that is every role — no role is exempt")
					.isEqualTo("{0}");
			assertThat(rs.getBoolean("has_using")).isTrue();
			assertThat(rs.getBoolean("has_with_check")).isTrue();
			assertThat(rs.next()).as("only one policy — otherwise OR applies between them").isFalse();
		}
	}

	/** DELETE is governed by {@code USING}: A does not see B's row and therefore cannot delete it. */
	@Test
	void givenTheContextOfOperatorA_whenDeletingOperatorBsRow_thenTheDeleteAffectsNothing() throws SQLException {
		int deleted = OperatorContext.callAs(A,
				() -> jdbc.sql("DELETE FROM operator WHERE id = :id").param("id", B.value()).update());
		assertThat(deleted).as("A must not be able to delete B's row").isZero();
		assertThat(slugsAsOwner()).contains("mig-b");
	}

	/**
	 * A job without a context must be able to see, update and delete nothing. Without
	 * {@code USING} on DELETE a context-free {@code DELETE FROM operator} would empty
	 * the table for every customer at once.
	 */
	@Test
	void givenNoOperatorContext_whenUpdatingOrDeletingEverything_thenNothingIsChanged() throws SQLException {
		assertThat(jdbc.sql("UPDATE operator SET name = 'reset'").update()).isZero();
		assertThat(jdbc.sql("DELETE FROM operator").update()).isZero();
		assertThat(slugsAsOwner()).containsExactlyInAnyOrder("mig-a", "mig-b");
	}

	/**
	 * {@code FORCE ROW LEVEL SECURITY} is deliberately NOT enabled: the owner (the
	 * migration user) bypasses RLS, and that is the design itself — it is the one route
	 * by which provisioning and the dev seed can create an operator, and the route
	 * back-office pulls fleet-wide numbers through (PLAN §2.7, rule 7).
	 *
	 * <p>The counterweight is that the application NEVER connects as the owner:
	 * {@code TenantIsolationInvariantTest} proves that the runtime role neither owns
	 * tables nor has {@code BYPASSRLS}. If {@code FORCE} is turned on, the dev seed and
	 * provisioning stop working — which is why the choice stands here with a test
	 * proving that the owner route really is the only way around.
	 */
	@Test
	void givenTheOperatorTable_whenInspectingItsRlsFlags_thenTheOwnerBypassesRlsDeliberatelyAndForceIsOff()
			throws SQLException {
		try (Connection owner = ownerConnection(); Statement sql = owner.createStatement();
				ResultSet rs = sql.executeQuery(
						"SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = 'operator'")) {
			assertThat(rs.next()).isTrue();
			assertThat(rs.getBoolean("relrowsecurity")).as("RLS must be enabled").isTrue();
			assertThat(rs.getBoolean("relforcerowsecurity"))
					.as("FORCE is opted out of — the owner is the documented cross-tenant route").isFalse();
		}
		assertThat(slugsAsOwner()).as("the owner sees all tenants without a context")
				.containsExactlyInAnyOrder("mig-a", "mig-b");
	}

	// ------------------------------------------------------------------
	// Privileges
	// ------------------------------------------------------------------

	@Test
	void givenTheRuntimeRole_whenItTriesToCreateATable_thenItIsDeniedByTheSchemaPrivileges() throws SQLException {
		try (Connection runtime = runtimeConnection(); Statement sql = runtime.createStatement()) {
			assertThatThrownBy(() -> sql.execute("CREATE TABLE smuggled_in (i int)"))
					.as("the runtime role must not be able to create tables — a new table would be without RLS")
					.isInstanceOf(SQLException.class)
					.extracting(error -> ((SQLException) error).getSQLState())
					.isEqualTo("42501");
		}
	}

	/**
	 * The Flyway history is created BEFORE V1 runs, and {@code ALTER DEFAULT PRIVILEGES}
	 * in V1 therefore only reaches objects created afterwards. That is an
	 * order-dependent assumption the comment in V1 rests on — so it has to be proven,
	 * not written down.
	 */
	@Test
	void givenTheRuntimeRole_whenItReadsTheFlywayHistory_thenItIsDenied() throws SQLException {
		try (Connection runtime = runtimeConnection(); Statement sql = runtime.createStatement()) {
			assertThatThrownBy(() -> sql.executeQuery("SELECT * FROM flyway_schema_history"))
					.isInstanceOf(SQLException.class)
					.extracting(error -> ((SQLException) error).getSQLState())
					.isEqualTo("42501");
		}
	}

	/**
	 * Privileges the runtime role must never have. SELECT and UPDATE are there on
	 * purpose (the application shows and edits its own operator); TRUNCATE, REFERENCES
	 * and TRIGGER are not — TRUNCATE would incidentally empty the table COMPLETELY
	 * BYPASSING the policy, because TRUNCATE is not a row operation.
	 */
	@Test
	void givenTheRuntimeRole_whenInspectingItsTablePrivileges_thenItHasNeitherTruncateNorReferencesNorTrigger() {
		assertThat(tablePrivilege("theticket_app", "operator", "SELECT")).isTrue();
		assertThat(tablePrivilege("theticket_app", "operator", "UPDATE")).isTrue();
		assertThat(tablePrivilege("theticket_app", "operator", "TRUNCATE"))
				.as("TRUNCATE bypasses RLS completely").isFalse();
		assertThat(tablePrivilege("theticket_app", "operator", "REFERENCES")).isFalse();
		assertThat(tablePrivilege("theticket_app", "operator", "TRIGGER")).isFalse();
	}

	// ------------------------------------------------------------------
	// CHECK constraints
	// ------------------------------------------------------------------

	/**
	 * The host name is the ONLY source of operator context. Garbage in the column is
	 * garbage in the tenant boundary: a host name with upper-case letters could never
	 * be hit (the lookup {@code lower()}s its input), an empty string or a wildcard
	 * would be an operator no host points at, and a port number would never match a
	 * real request.
	 */
	@Test
	void givenAnInvalidHostName_whenInsertingIt_thenTheCheckConstraintRejectsIt() {
		for (String invalid : List.of("A.Migration.Test", "a.migration.test:8080", "", " a.test", "a test",
				"*", "a_test.dk", "-a.test", "a.test-")) {
			assertThatThrownBy(() -> insertAsOwner("mig-x", invalid))
					.as("the host name [%s] must be rejected", invalid)
					.isInstanceOf(SQLException.class)
					.extracting(error -> ((SQLException) error).getSQLState())
					.isEqualTo("23514");
		}
	}

	@Test
	void givenAValidHostName_whenInsertingIt_thenItPassesTheCheckConstraint() throws SQLException {
		for (String valid : List.of("localhost", "billetter.eventfirma.dk", "a1.test", "x")) {
			insertAsOwner("mig-" + valid.replaceAll("[^a-z0-9]", ""), valid);
		}
	}

	/**
	 * The Postgres regexp is not line sensitive by default, so {@code $} matches only
	 * the end of the string — a slug with a trailing newline therefore cannot sneak
	 * past. That is worth having written down: in many other languages {@code ^...$}
	 * would let exactly that value through.
	 */
	@Test
	void givenAnInvalidSlug_whenInsertingIt_thenItIsRejectedEvenWithATrailingNewline() {
		for (String invalid : List.of("Mig-A", "mig a", "mig_a", "-mig", "", "a", "mig\nA", "mig-a\n")) {
			assertThatThrownBy(() -> insertAsOwner(invalid, "slugtest.migration.test"))
					.as("slug [%s] must be rejected", invalid.replace("\n", "\\n"))
					.isInstanceOf(SQLException.class)
					.extracting(error -> ((SQLException) error).getSQLState())
					.isEqualTo("23514");
		}
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private UUID lookup(String host) {
		return jdbc.sql("SELECT operator_id_for_host(:host)").param("host", host).query(UUID.class).optional()
				.orElse(null);
	}

	private boolean tablePrivilege(String role, String table, String privilege) {
		return jdbc.sql("SELECT has_table_privilege(:role, :table, :privilege)")
				.param("role", role).param("table", table).param("privilege", privilege)
				.query(Boolean.class).single();
	}

	private static boolean hasFunctionPrivilege(Statement sql, String role, String function) throws SQLException {
		try (ResultSet rs = sql.executeQuery(
				"SELECT has_function_privilege('%s', '%s', 'EXECUTE')".formatted(role, function))) {
			rs.next();
			return rs.getBoolean(1);
		}
	}

	/** Inserts as the owner, so RLS is out of the picture and the CHECK constraint is the only door. */
	private void insertAsOwner(String slug, String hostname) throws SQLException {
		try (Connection owner = ownerConnection();
				PreparedStatement sql = owner.prepareStatement(
						"INSERT INTO operator (slug, name, hostname) VALUES (?, 'Test', ?)")) {
			sql.setString(1, slug);
			sql.setString(2, hostname);
			sql.executeUpdate();
		}
	}

	private List<String> slugsAsOwner() throws SQLException {
		List<String> slugs = new ArrayList<>();
		try (Connection owner = ownerConnection(); Statement sql = owner.createStatement();
				ResultSet rs = sql.executeQuery("SELECT slug FROM operator ORDER BY slug")) {
			while (rs.next()) {
				slugs.add(rs.getString(1));
			}
		}
		return slugs;
	}

	private Connection ownerConnection() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

	private Connection runtimeConnection() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), TestcontainersConfiguration.RUNTIME_USER,
				TestcontainersConfiguration.RUNTIME_PASSWORD);
	}

}

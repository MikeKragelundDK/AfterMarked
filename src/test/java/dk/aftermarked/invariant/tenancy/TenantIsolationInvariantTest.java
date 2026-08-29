package dk.aftermarked.invariant.tenancy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import dk.aftermarked.tenancy.OperatorContext;
import dk.aftermarked.tenancy.OperatorId;
import dk.aftermarked.tenancy.internal.TenantAwareDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INVARIANT — tenant isolation (PLAN §2.7, CLAUDE.md). This test is spec.
 *
 * <p>It actively tries to read and write across two operators and has to fail in the
 * attempt. If it cannot be made green, the code is wrong — not the test.
 *
 * <p>The pool is deliberately set to ONE connection: then the same physical connection
 * is reused across contexts, and the test proves that a previous operator's context
 * never follows along.
 */
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=1")
@AutoConfigureMockMvc
@Import(dk.aftermarked.TestcontainersConfiguration.class)
class TenantIsolationInvariantTest {

	static final OperatorId A = new OperatorId(UUID.fromString("00000000-0000-4000-8000-00000000000a"));
	static final OperatorId B = new OperatorId(UUID.fromString("00000000-0000-4000-8000-00000000000b"));

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	DataSource dataSource;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MockMvcTester mvc;

	@Autowired
	org.springframework.context.ApplicationContext applicationContext;

	@BeforeEach
	void seedTwoOperators() throws SQLException {
		try (Connection owner = ownerConnection(); Statement sql = owner.createStatement()) {
			sql.execute("DELETE FROM operator");
			sql.execute("""
					INSERT INTO operator (id, slug, name, hostname) VALUES
					  ('%s', 'op-a', 'Operator A', 'a.test'),
					  ('%s', 'op-b', 'Operator B', 'b.test')
					""".formatted(A, B));
		}
	}

	@Test
	void givenTheRunningApplication_whenInspectingItsDataSource_thenTheOnlyOneIsTenantAware() {
		assertThat(dataSource).isInstanceOf(TenantAwareDataSource.class);
	}

	@Test
	void givenTheRoleTheApplicationConnectsAs_whenInspectingItsPrivileges_thenItCannotBypassRls() {
		var role = jdbc.sql("SELECT rolname, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user")
				.query((rs, i) -> new Role(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3)))
				.single();
		assertThat(role.name()).isEqualTo("theticket_runtime");
		assertThat(role.superuser()).as("the runtime role must never be a superuser").isFalse();
		assertThat(role.bypassRls()).as("the runtime role must never have BYPASSRLS").isFalse();

		Integer ownedTables = jdbc.sql("SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner = current_user")
				.query(Integer.class).single();
		assertThat(ownedTables).as("the runtime role must not own tables — the owner bypasses RLS").isZero();
	}

	/** Rule 1: a table with operator_id and no RLS policy does not exist. Catches any future migration that forgets it. */
	@Test
	void givenEveryTableCarryingOperatorId_whenInspectingTheSchema_thenEachHasRlsEnabledAndAtLeastOnePolicy()
			throws SQLException {
		List<String> violations = new ArrayList<>();
		int inspected = 0;
		try (Connection owner = ownerConnection(); Statement sql = owner.createStatement();
				ResultSet rs = sql.executeQuery("""
						SELECT c.relname,
						       c.relrowsecurity,
						       (SELECT count(*) FROM pg_policy p WHERE p.polrelid = c.oid) AS policies
						FROM pg_class c
						JOIN pg_namespace n ON n.oid = c.relnamespace
						WHERE n.nspname = 'public'
						  AND c.relkind IN ('r', 'p')
						  AND (c.relname = 'operator'
						       OR EXISTS (SELECT 1 FROM pg_attribute a
						                  WHERE a.attrelid = c.oid AND a.attname = 'operator_id' AND NOT a.attisdropped))
						""")) {
			while (rs.next()) {
				inspected++;
				if (!rs.getBoolean("relrowsecurity") || rs.getInt("policies") < 1) {
					violations.add(rs.getString("relname"));
				}
			}
		}
		assertThat(inspected).as("the operator table must be covered as a minimum").isGreaterThanOrEqualTo(1);
		assertThat(violations).as("tables with operator_id without RLS or without a policy").isEmpty();
	}

	@Test
	void givenNoOperatorContext_whenQueryingOperators_thenNothingIsVisible() {
		assertThat(slugs()).isEmpty();
	}

	@Test
	void givenABoundOperatorContext_whenQueryingOperators_thenOnlyTheOwnOperatorIsVisible() {
		assertThat(OperatorContext.callAs(A, this::slugs)).containsExactly("op-a");
		assertThat(OperatorContext.callAs(B, this::slugs)).containsExactly("op-b");
	}

	@Test
	void givenTheContextOfOperatorA_whenWritingToAnotherOperatorsRows_thenEveryWriteIsRejected() {
		int updatedOther = OperatorContext.callAs(A, () -> jdbc.sql("UPDATE operator SET name = 'hacked' WHERE id = :id")
				.param("id", B.value()).update());
		assertThat(updatedOther).as("A must not be able to update B's row").isZero();

		int updatedOwn = OperatorContext.callAs(A, () -> jdbc.sql("UPDATE operator SET name = 'Operator A2' WHERE id = :id")
				.param("id", A.value()).update());
		assertThat(updatedOwn).isOne();

		assertThatThrownBy(() -> OperatorContext.runAs(A, () -> jdbc
				.sql("INSERT INTO operator (id, slug, name, hostname) VALUES (:id, 'op-c', 'Smuggled in', 'c.test')")
				.param("id", UUID.randomUUID()).update()))
				.as("A must not be able to insert a row belonging to another operator")
				.isInstanceOf(DataAccessException.class)
				.rootCause().hasMessageContaining("row-level security");
	}

	@Test
	void givenAPoolOfOneConnection_whenTheContextChangesBetweenQueries_thenNoStaleContextFollowsTheConnection() {
		assertThat(OperatorContext.callAs(A, this::slugs)).containsExactly("op-a");
		assertThat(slugs()).as("the same physical connection, no context").isEmpty();
		assertThat(OperatorContext.callAs(B, this::slugs)).containsExactly("op-b");
		assertThat(OperatorContext.callAs(A, this::slugs)).containsExactly("op-a");
	}

	@Test
	void givenAnUnknownHostName_whenRequestingTheFrontPage_thenTheRequestIsRejectedClosed() {
		assertThat(mvc.get().uri("/").with(request -> {
			request.setServerName("unknown.test");
			return request;
		})).hasStatus(421);
	}

	@Test
	void givenTwoKnownHostNames_whenRequestingTheFrontPage_thenTheHostNameDeterminesTheOperator() {
		assertThat(mvc.get().uri("/").with(request -> {
			request.setServerName("a.test");
			return request;
		})).hasStatusOk().bodyText().contains("Operator A").doesNotContain("Operator B");

		assertThat(mvc.get().uri("/").with(request -> {
			request.setServerName("b.test");
			return request;
		})).hasStatusOk().bodyText().contains("Operator B").doesNotContain("Operator A");
	}

	/**
	 * ATTACK: a user on a.test asks to become b.test by sending the header a reverse
	 * proxy normally sets. The host name is the only permitted source (PLAN §2.6,
	 * §2.7 rule 2) — the header must have no effect.
	 */
	@Test
	void givenSpoofedForwardingHeaders_whenRequestingTheFrontPage_thenTheyCannotTakeOverAnotherOperatorsSurface() {
		assertThat(mvc.get().uri("/")
				.header("X-Forwarded-Host", "b.test")
				.header("Forwarded", "host=b.test")
				.with(request -> {
					request.setServerName("a.test");
					return request;
				}))
				.hasStatusOk().bodyText().contains("Operator A").doesNotContain("Operator B");
	}

	/**
	 * ATTACK: a query parameter must not be able to pick the operator either. There is
	 * no source other than the host name.
	 */
	@Test
	void givenAQueryParameterNamingAnotherOperator_whenRequestingTheFrontPage_thenItCannotTakeOverThatSurface() {
		assertThat(mvc.get().uri("/?operator=" + B.value() + "&operator_id=" + B.value())
				.with(request -> {
					request.setServerName("a.test");
					return request;
				}))
				.hasStatusOk().bodyText().contains("Operator A").doesNotContain("Operator B");
	}

	/**
	 * The data source is not merely tenant aware — it is the ONLY one. A second data
	 * source would not be wrapped (the wrapping happens on the bean name) and would
	 * therefore never set {@code app.operator_id}.
	 */
	@Test
	void givenTheApplicationContext_whenLookingForDataSources_thenNoneExistsBesideTheTenantAwareOne() {
		assertThat(applicationContext.getBeanNamesForType(DataSource.class)).containsExactly("dataSource");
	}

	private List<String> slugs() {
		return jdbc.sql("SELECT slug FROM operator ORDER BY slug").query(String.class).list();
	}

	private Connection ownerConnection() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

	private record Role(String name, boolean superuser, boolean bypassRls) {
	}

}

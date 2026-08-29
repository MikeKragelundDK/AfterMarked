package dk.aftermarked.tenancy;

import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OperatorRegistry} has two methods with a security model each, and they have
 * to keep having them: {@code findByHost} goes through the SECURITY DEFINER function
 * and is deliberately context independent, while {@code current} may only see what
 * RLS lets it see.
 */
@SpringBootTest
@Import(dk.aftermarked.TestcontainersConfiguration.class)
class OperatorRegistryTest {

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	OperatorRegistry registry;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate tx;

	@BeforeEach
	void seed() throws SQLException {
		OperatorSeed.insertTwoOperators(postgres);
	}

	// --- findByHost: the chicken and the egg, solved with SECURITY DEFINER --

	@Test
	void givenNoBoundContext_whenLookingUpAKnownHostName_thenTheOperatorIsFound() {
		assertThat(OperatorContext.current()).isEmpty();
		assertThat(registry.findByHost("a.test")).contains(OperatorSeed.A);
		assertThat(registry.findByHost("b.test")).contains(OperatorSeed.B);
	}

	/** Host names are stored in lower case; the lookup has to tolerate what the browser sends. */
	@Test
	void givenAHostNameInUpperCase_whenLookingItUp_thenTheLookupIsCaseInsensitive() {
		assertThat(registry.findByHost("A.TEST")).contains(OperatorSeed.A);
		assertThat(registry.findByHost("A.Test")).contains(OperatorSeed.A);
	}

	@Test
	void givenAnUnknownHostName_whenLookingItUp_thenTheAnswerIsEmpty() {
		assertThat(registry.findByHost("unknown.test")).isEmpty();
		assertThat(registry.findByHost("a.test.")).as("a trailing dot is not the same host").isEmpty();
		assertThat(registry.findByHost("sub.a.test")).isEmpty();
	}

	@Test
	void givenANullOrBlankHostName_whenLookingItUp_thenItIsRejectedWithAnEmptyAnswer() {
		assertThat(registry.findByHost(null)).isEmpty();
		assertThat(registry.findByHost("")).isEmpty();
		assertThat(registry.findByHost("   ")).isEmpty();
	}

	/**
	 * findByHost is the one place where the application reads across tenants. It has to
	 * give the right answer regardless of which context happens to be bound —
	 * otherwise a lookup inside a job would give a different result than the same
	 * lookup inside a request.
	 */
	@Test
	void givenAContextForAnotherOperator_whenLookingUpAHostName_thenTheAnswerIsTheSameAsWithoutAContext() {
		assertThat(OperatorContext.callAs(OperatorSeed.A, () -> registry.findByHost("b.test")))
				.as("the SECURITY DEFINER function is not affected by the RLS context")
				.contains(OperatorSeed.B);
		assertThat(OperatorContext.callAs(OperatorSeed.B, () -> registry.findByHost("a.test")))
				.contains(OperatorSeed.A);
	}

	/** The function returns a uuid — not the row. No name, no slug, no leak. */
	@Test
	void givenTheHostLookupFunction_whenInspectingItsReturnType_thenItHandsOutOnlyAnIdAndNeverTheRow() {
		assertThat(jdbc.sql("SELECT pg_get_function_result(oid) FROM pg_proc WHERE proname = 'operator_id_for_host'")
				.query(String.class).single()).isEqualTo("uuid");
	}

	// --- current: only what RLS shows --------------------------------------

	@Test
	void givenNoBoundContext_whenCallingCurrent_thenItFailsLoudly() {
		assertThatThrownBy(registry::current)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("No operator in context");
	}

	@Test
	void givenABoundContext_whenCallingCurrent_thenItReturnsOnlyTheOwnOperator() {
		assertThat(OperatorContext.callAs(OperatorSeed.A, registry::current).slug()).isEqualTo("op-a");
		assertThat(OperatorContext.callAs(OperatorSeed.B, registry::current).slug()).isEqualTo("op-b");
	}

	/**
	 * A context pointing at an operator that no longer exists — a deleted customer, an
	 * old job row, a stale session — must not hand back some random other operator's
	 * row. It has to fail.
	 */
	@Test
	void givenAContextForADeletedOperator_whenCallingCurrent_thenItFailsLoudly() {
		assertThatThrownBy(() -> OperatorContext.callAs(OperatorSeed.DELETED, registry::current))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("RLS returned no row");
	}

	/**
	 * THE TRAP, turned into an error instead of a wrong answer: the context lives on
	 * the thread, but {@code app.operator_id} is set at connection checkout. A
	 * {@code runAs} inside an already open transaction moves only one of the two.
	 * Without {@code WHERE id} in {@code current()} this method would return operator
	 * A's row and claim that it was B's.
	 */
	@Test
	void givenAThreadContextAndAConnectionThatDisagree_whenCallingCurrent_thenItFailsInsteadOfAnsweringWrongly() {
		OperatorContext.runAs(OperatorSeed.A, () -> tx.executeWithoutResult(status -> {
			// Forces the connection out of the pool NOW, while A is bound.
			assertThat(slugs()).containsExactly("op-a");

			OperatorContext.runAs(OperatorSeed.B, () -> {
				assertThat(OperatorContext.current()).as("the thread believes B").contains(OperatorSeed.B);
				assertThat(slugs()).as("the connection is still A's").containsExactly("op-a");

				assertThatThrownBy(registry::current)
						.as("the disagreement must become an error, not a wrong operator")
						.isInstanceOf(IllegalStateException.class)
						.hasMessageContaining("RLS returned no row");
			});
		}));
	}

	private List<String> slugs() {
		return jdbc.sql("SELECT slug FROM operator ORDER BY slug").query(String.class).list();
	}

}

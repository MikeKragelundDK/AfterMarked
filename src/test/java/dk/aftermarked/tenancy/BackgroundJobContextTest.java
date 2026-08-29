package dk.aftermarked.tenancy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLAN §2.7, rule 3: background jobs have no host and must bind the operator context
 * themselves from the job row's own {@code operator_id}, BEFORE they touch data. There
 * are no jobs yet; this test is the pattern they have to follow — and, just as
 * importantly, the three ways of getting it wrong.
 *
 * <p>None of the traps are theoretical: the context is applied at connection checkout,
 * and a job that opens its transaction or its thread on the wrong side of
 * {@code runAs} quietly sees zero rows, or the previous operator's.
 */
@SpringBootTest
@Import(dk.aftermarked.TestcontainersConfiguration.class)
class BackgroundJobContextTest {

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	DataSource dataSource;

	@Autowired
	TransactionTemplate tx;

	@BeforeEach
	void seed() throws SQLException {
		OperatorSeed.insertTwoOperators(postgres);
	}

	// --- the right way ------------------------------------------------------

	/** A job without a context sees ZERO rows — not all of them. That is the whole point of the empty string. */
	@Test
	void givenAJobThatBindsNoContext_whenItQueriesOperators_thenItSeesZeroRows() {
		assertThat(slugs()).isEmpty();
	}

	@Test
	void givenAJobThatBindsItsContext_whenItQueriesOperators_thenItSeesOnlyItsOwnOperatorsRows() {
		assertThat(OperatorContext.callAs(OperatorSeed.A, this::slugs)).containsExactly("op-a");
		assertThat(OperatorContext.callAs(OperatorSeed.B, this::slugs)).containsExactly("op-b");
	}

	/** The pattern: runAs on the OUTSIDE, the transaction on the INSIDE. */
	@Test
	void givenATransactionOpenedInsideRunAs_whenItQueriesOperators_thenItSeesTheContext() {
		OperatorContext.runAs(OperatorSeed.A,
				() -> tx.executeWithoutResult(status -> assertThat(slugs()).containsExactly("op-a")));
	}

	/** Switching context between two jobs in the same worker leaves no residue. */
	@Test
	void givenTwoJobsRunBackToBack_whenTheSecondQueriesOperators_thenItDoesNotInheritTheFirstsContext() {
		assertThat(OperatorContext.callAs(OperatorSeed.A, this::slugs)).containsExactly("op-a");
		assertThat(slugs()).as("between two jobs there is no context").isEmpty();
		assertThat(OperatorContext.callAs(OperatorSeed.B, this::slugs)).containsExactly("op-b");
	}

	/**
	 * Two jobs running at the same time for different customers must not be able to see
	 * each other's data.
	 *
	 * <p>Tidsgraensen er ikke pynt: braender den ene opgave sammen FOER den naar
	 * barrieren, bliver barrieren ikke brudt, og den anden venter for evigt. Uden
	 * loftet ville et brud vise sig som en CI-koersel, der haenger, i stedet for som
	 * en roed test.
	 */
	@Test
	@Timeout(30)
	void givenTwoConcurrentJobsForDifferentOperators_whenBothQueryOperators_thenTheirDataNeverMixes() throws Exception {
		CyclicBarrier atTheSameTime = new CyclicBarrier(2);
		try (ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<List<String>> a = worker.submit(() -> OperatorContext.callAs(OperatorSeed.A, () -> {
				atTheSameTime.await();
				return slugs();
			}));
			Future<List<String>> b = worker.submit(() -> OperatorContext.callAs(OperatorSeed.B, () -> {
				atTheSameTime.await();
				return slugs();
			}));
			assertThat(a.get()).containsExactly("op-a");
			assertThat(b.get()).containsExactly("op-b");
		}
	}

	// --- the three traps ----------------------------------------------------

	/**
	 * TRAP 1 (warned about in the javadoc of {@link OperatorContext}, never tested
	 * before): a transaction that began BEFORE {@code runAs} already holds its
	 * connection and therefore never sees the context. The job finds zero rows and
	 * concludes that there is no work.
	 */
	@Test
	void givenATransactionOpenedBEFORERunAs_whenItQueriesOperatorsInsideRunAs_thenItStillSeesNoContext() {
		tx.executeWithoutResult(status -> {
			// The connection is checked out here — without a context.
			assertThat(slugs()).isEmpty();

			OperatorContext.runAs(OperatorSeed.A, () -> {
				assertThat(OperatorContext.current()).as("the thread has the context").contains(OperatorSeed.A);
				assertThat(slugs()).as("but the connection never got it").isEmpty();
			});
		});
	}

	/**
	 * TRAP 2: a {@code runAs} inside an open transaction switches the thread, but not
	 * the connection. Java says B, the database answers A. This is the dangerous one of
	 * the three, because it hands back DATA — just the wrong customer's.
	 */
	@Test
	void givenANestedRunAsInsideAnOpenTransaction_whenItQueriesOperators_thenTheDatabaseContextIsUnchanged() {
		OperatorContext.runAs(OperatorSeed.A, () -> tx.executeWithoutResult(status -> {
			assertThat(slugs()).containsExactly("op-a");

			OperatorContext.runAs(OperatorSeed.B, () -> {
				assertThat(OperatorContext.current()).contains(OperatorSeed.B);
				assertThat(slugs()).as("the connection is still bound to A").containsExactly("op-a");
			});
		}));
	}

	/**
	 * TRAP 3: the job hands the work over to another thread. The ScopedValue does not
	 * follow along, so the new thread runs without a context and sees zero rows.
	 * Fail-closed — but the job believes it is done.
	 */
	@Test
	void givenAJobThatMovesItsWorkToAnotherThread_whenTheNewThreadQueriesOperators_thenTheContextIsLost()
			throws Exception {
		try (ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<List<String>> onAnotherThread = OperatorContext.callAs(OperatorSeed.A,
					() -> worker.submit(this::slugs));
			assertThat(onAnotherThread.get()).as("the new thread has no context").isEmpty();
		}
	}

	/**
	 * A connection checked out outside a transaction and then held freezes the context
	 * from its checkout moment. A later context switch on the thread does not touch it.
	 */
	@Test
	void givenAConnectionHeldOutsideATransaction_whenTheThreadContextChanges_thenTheConnectionKeepsItsFrozenContext()
			throws Exception {
		Connection held = OperatorContext.callAs(OperatorSeed.A, dataSource::getConnection);
		try {
			assertThat(sessionOperator(held)).isEqualTo(OperatorSeed.A.toString());
			assertThat(slugsOn(held)).containsExactly("op-a");

			OperatorContext.runAs(OperatorSeed.B, () -> {
				assertThat(sessionOperator(held)).as("the connection knows nothing of the switch")
						.isEqualTo(OperatorSeed.A.toString());
				assertThat(slugsOn(held)).containsExactly("op-a");
			});
		}
		finally {
			held.close();
		}
	}

	/**
	 * And a fresh checkout after the held connection always starts over —
	 * {@code app.operator_id} is set unconditionally, also when there is no context.
	 * That this holds on the very same physical connection is proven by the invariant
	 * test, which runs with a pool of one.
	 */
	@Test
	void givenAReleasedBoundConnection_whenCheckingOutAFreshOne_thenTheNewCheckoutStartsWithoutAnOperator()
			throws Exception {
		Connection first = OperatorContext.callAs(OperatorSeed.A, dataSource::getConnection);
		assertThat(sessionOperator(first)).isEqualTo(OperatorSeed.A.toString());
		first.close();

		try (Connection again = dataSource.getConnection()) {
			assertThat(sessionOperator(again)).as("fresh checkout, no context").isEmpty();
			assertThat(slugsOn(again)).isEmpty();
		}
	}

	// --- helpers ------------------------------------------------------------

	private List<String> slugs() {
		return jdbc.sql("SELECT slug FROM operator ORDER BY slug").query(String.class).list();
	}

	private static String sessionOperator(Connection connection) {
		try (Statement sql = connection.createStatement();
				ResultSet rs = sql.executeQuery("SELECT current_setting('app.operator_id', true)")) {
			rs.next();
			String value = rs.getString(1);
			return value == null ? "" : value;
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static List<String> slugsOn(Connection connection) {
		List<String> slugs = new ArrayList<>();
		try (Statement sql = connection.createStatement();
				ResultSet rs = sql.executeQuery("SELECT slug FROM operator ORDER BY slug")) {
			while (rs.next()) {
				slugs.add(rs.getString(1));
			}
			return slugs;
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
	}

}

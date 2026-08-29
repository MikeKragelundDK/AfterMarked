package dk.aftermarked.tenancy.internal;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import dk.aftermarked.tenancy.OperatorContext;
import dk.aftermarked.tenancy.OperatorId;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TenantAwareDataSource} is the one place where the operator context reaches
 * the database. This test is about the mechanics around the SQL — that it is always
 * run, that the connection is not leaked when it fails, and that the pool is closed.
 *
 * <p>That {@code set_config} actually gives RLS what it needs is proven against a real
 * Postgres in the invariant test and in {@code BackgroundJobContextTest}.
 */
class TenantAwareDataSourceTest {

	static final OperatorId A = new OperatorId(UUID.fromString("00000000-0000-4000-8000-00000000000a"));

	@Test
	void givenABoundOperator_whenCheckingOutAConnection_thenTheOperatorIdIsSetOnIt() throws SQLException {
		Stub stub = new Stub();
		TenantAwareDataSource ds = new TenantAwareDataSource(stub.dataSource);

		OperatorContext.runAs(A, () -> assertThat(ds.getConnection()).isSameAs(stub.connection));

		verify(stub.connection).prepareStatement(TenantAwareDataSource.SET_OPERATOR_SQL);
		verify(stub.statement).setString(1, A.toString());
		verify(stub.statement).execute();
	}

	/**
	 * Without a context the variable is set to the empty string — NEVER skipped. That
	 * is the entire reason a reused pooled connection cannot carry a previous
	 * operator's context forward.
	 */
	@Test
	void givenNoBoundContext_whenCheckingOutAConnection_thenTheOperatorIdIsSetToTheEmptyString() throws SQLException {
		Stub stub = new Stub();
		TenantAwareDataSource ds = new TenantAwareDataSource(stub.dataSource);

		assertThat(ds.getConnection()).isSameAs(stub.connection);

		verify(stub.statement).setString(1, "");
		verify(stub.statement).execute();
	}

	/** The back door {@code getConnection(user, pass)} binds too — otherwise it would be a way around. */
	@Test
	void givenABoundOperator_whenCheckingOutAConnectionWithCredentials_thenTheOperatorIsBoundAsWell()
			throws SQLException {
		Stub stub = new Stub();
		when(stub.dataSource.getConnection(anyString(), anyString())).thenReturn(stub.connection);
		TenantAwareDataSource ds = new TenantAwareDataSource(stub.dataSource);

		OperatorContext.runAs(A, () -> assertThat(ds.getConnection("someone", "secret")).isSameAs(stub.connection));

		verify(stub.statement).setString(1, A.toString());
		verify(stub.statement).execute();
	}

	/** If set_config fails, the connection must not be left outside the pool. */
	@Test
	void givenAFailingPrepareStatement_whenCheckingOutAConnection_thenTheConnectionIsClosed() throws SQLException {
		Stub stub = new Stub();
		when(stub.connection.prepareStatement(anyString())).thenThrow(new SQLException("down"));
		TenantAwareDataSource ds = new TenantAwareDataSource(stub.dataSource);

		assertThatThrownBy(ds::getConnection).isInstanceOf(SQLException.class).hasMessage("down");

		verify(stub.connection).close();
	}

	@Test
	void givenAFailingExecute_whenCheckingOutAConnection_thenTheStatementAndTheConnectionAreBothClosed()
			throws SQLException {
		Stub stub = new Stub();
		when(stub.statement.execute()).thenThrow(new SQLException("rejected"));
		TenantAwareDataSource ds = new TenantAwareDataSource(stub.dataSource);

		assertThatThrownBy(ds::getConnection).isInstanceOf(SQLException.class).hasMessage("rejected");

		InOrder order = inOrder(stub.statement, stub.connection);
		order.verify(stub.statement).close();
		order.verify(stub.connection).close();
	}

	/** A RuntimeException along the way must not leave the connection open either. */
	@Test
	void givenARuntimeExceptionDuringBinding_whenCheckingOutAConnection_thenTheConnectionIsStillClosed()
			throws SQLException {
		Stub stub = new Stub();
		when(stub.statement.execute()).thenThrow(new IllegalStateException("unexpected"));
		TenantAwareDataSource ds = new TenantAwareDataSource(stub.dataSource);

		assertThatThrownBy(ds::getConnection).isInstanceOf(IllegalStateException.class);

		verify(stub.connection).close();
	}

	@Test
	void givenASuccessfulCheckout_whenBindingCompletes_thenOnlyTheStatementIsClosed() throws SQLException {
		Stub stub = new Stub();
		TenantAwareDataSource ds = new TenantAwareDataSource(stub.dataSource);

		ds.getConnection();

		verify(stub.statement).close();
		verify(stub.connection, never()).close();
	}

	/**
	 * The value has to be a bare uuid. {@link OperatorId} is a record, and without its
	 * {@code toString} override {@code set_config} would receive
	 * {@code OperatorId[value=…]} — which Postgres cannot cast to uuid. Then every
	 * single request would fail. This test locks that coupling down.
	 */
	@Test
	void givenABoundOperatorId_whenItIsSentToSetConfig_thenItIsABareUuidWithoutTheRecordWrapper()
			throws SQLException {
		Stub stub = new Stub();
		TenantAwareDataSource ds = new TenantAwareDataSource(stub.dataSource);

		OperatorContext.runAs(A, ds::getConnection);

		verify(stub.statement).setString(1, "00000000-0000-4000-8000-00000000000a");
	}

	/** Spring closes the bean — that is, the wrapper. Without this delegation the whole pool is leaked. */
	@Test
	void givenAClosablePool_whenTheWrapperIsClosed_thenTheUnderlyingPoolIsClosedToo() throws Exception {
		ClosableDataSource target = mock(ClosableDataSource.class);
		new TenantAwareDataSource(target).close();
		verify(target).close();
	}

	@Test
	void givenAPoolThatCannotBeClosed_whenTheWrapperIsClosed_thenCloseIsANoOp() {
		DataSource target = mock(DataSource.class);
		TenantAwareDataSource ds = new TenantAwareDataSource(target);
		assertThatCode(ds::close).doesNotThrowAnyException();
	}

	@Test
	void givenAPoolWhoseCloseThrowsACheckedException_whenTheWrapperIsClosed_thenItIsWrappedInAnIOException()
			throws Exception {
		ClosableDataSource target = mock(ClosableDataSource.class);
		doThrow(new Exception("cannot close")).when(target).close();

		assertThatThrownBy(new TenantAwareDataSource(target)::close)
				.isInstanceOf(IOException.class)
				.hasRootCauseMessage("cannot close");
	}

	/** A DataSource that is also AutoCloseable — like HikariDataSource. */
	interface ClosableDataSource extends DataSource, AutoCloseable {

		@Override
		void close() throws Exception;

	}

	private static final class Stub {

		final DataSource dataSource = mock(DataSource.class);

		final Connection connection = mock(Connection.class);

		final PreparedStatement statement = mock(PreparedStatement.class);

		Stub() {
			try {
				when(dataSource.getConnection()).thenReturn(connection);
				when(connection.prepareStatement(anyString())).thenReturn(statement);
			}
			catch (SQLException ex) {
				throw new IllegalStateException(ex);
			}
		}

	}

}

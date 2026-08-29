package dk.aftermarked.tenancy.internal;

import java.io.Closeable;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import javax.sql.DataSource;

import dk.aftermarked.tenancy.OperatorContext;
import dk.aftermarked.tenancy.OperatorId;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Det ene sted, operator-konteksten når databasen.
 *
 * <p>Ved hver forbindelses-checkout sættes session-variablen {@code app.operator_id}
 * — <em>ubetinget</em>: til operatørens id hvis der er en kontekst, ellers til
 * tom streng. Der springes aldrig over. Det er dét, der gør, at en forbindelse,
 * der kommer tilbage fra puljen med en tidligere operatørs kontekst, aldrig kan
 * lække den videre: næste checkout overskriver altid. Invariant-testen kører med
 * en pulje på én forbindelse for at bevise præcis det.
 *
 * <p>RLS-policies i Postgres læser variablen via {@code current_operator_id()}.
 * Tom værdi giver NULL og dermed ingen rækker. Et job uden kontekst ser altså intet.
 *
 * <p>Wrapperen skal være den <em>eneste</em> {@link DataSource}, applikationen
 * har. Flyway kører som migrationsbrugeren på sin egen forbindelse og er med
 * vilje udenfor.
 */
public final class TenantAwareDataSource extends DelegatingDataSource implements Closeable {

	static final String SET_OPERATOR_SQL = "SELECT set_config('app.operator_id', ?, false)";

	public TenantAwareDataSource(DataSource target) {
		super(target);
	}

	@Override
	public Connection getConnection() throws SQLException {
		return bindOperator(super.getConnection());
	}

	@Override
	public Connection getConnection(String username, String password) throws SQLException {
		return bindOperator(super.getConnection(username, password));
	}

	private static Connection bindOperator(Connection connection) throws SQLException {
		String value = OperatorContext.current().map(OperatorId::toString).orElse("");
		try (PreparedStatement statement = connection.prepareStatement(SET_OPERATOR_SQL)) {
			statement.setString(1, value);
			statement.execute();
			return connection;
		}
		catch (SQLException | RuntimeException ex) {
			connection.close();
			throw ex;
		}
	}

	/** Lukker den underliggende pulje ved nedlukning — Spring kalder close på bønnen, ikke på målet. */
	@Override
	public void close() throws IOException {
		if (obtainTargetDataSource() instanceof AutoCloseable closeable) {
			try {
				closeable.close();
			}
			catch (IOException | RuntimeException ex) {
				throw ex;
			}
			catch (Exception ex) {
				throw new IOException(ex);
			}
		}
	}

}

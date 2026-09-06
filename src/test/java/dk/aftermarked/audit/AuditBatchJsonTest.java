package dk.aftermarked.audit;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import dk.aftermarked.TestcontainersConfiguration;
import dk.aftermarked.tenancy.OperatorContext;
import dk.aftermarked.tenancy.OperatorId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditBatchJsonTest {

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	AuditLog auditLog;

	private final OperatorId operator = new OperatorId(UUID.randomUUID());

	@BeforeEach
	void seedOperator() throws SQLException {
		try (Connection owner = ownerConnection();
				PreparedStatement insert = owner.prepareStatement(
						"INSERT INTO operator (id, slug, name, hostname) VALUES (?, ?, ?, ?)")) {
			insert.setObject(1, operator.value());
			insert.setString(2, "audit-json-" + operator);
			insert.setString(3, "Audit JSON operator");
			insert.setString(4, operator + ".audit-json.test");
			insert.executeUpdate();
		}
	}

	@AfterEach
	void removeOperator() throws SQLException {
		try (Connection owner = ownerConnection();
				PreparedStatement delete = owner.prepareStatement("DELETE FROM operator WHERE id = ?")) {
			delete.setObject(1, operator.value());
			delete.executeUpdate();
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void givenAStateThatInjectsAnotherRow_whenRecordingABatch_thenTheWholeBatchIsRejected(boolean beforeState) {
		String injected = "{}}, {\"actor_type\":\"system\",\"action\":\"event.deleted\","
				+ "\"entity_type\":\"event\",\"entity_ref\":\"forged\",\"after_state\":{}";
		AuditEntry malicious = entry("malicious", beforeState ? injected : null, beforeState ? "{}" : injected);

		OperatorContext.runAs(operator, () -> {
			assertThatThrownBy(() -> auditLog.recordAll(List.of(entry("valid", null, "{}"), malicious)))
					.isInstanceOf(DataAccessException.class);
			assertThat(jdbc.sql("SELECT count(*) FROM audit_log").query(Long.class).single()).isZero();
		});
	}

	@Test
	void givenAStateThatOverridesTheAction_whenRecording_thenItIsRejectedWithoutWriting() {
		String injected = "{},\"action\":\"event.deleted\",\"padding\":{}";
		OperatorContext.runAs(operator, () -> {
			assertThatThrownBy(() -> auditLog.record(entry("malicious", injected, "{}")))
					.isInstanceOf(DataAccessException.class);
			assertThat(jdbc.sql("SELECT count(*) FROM audit_log").query(Long.class).single()).isZero();
		});
	}

	@Test
	void givenNestedStatesAndEscapedText_whenRecording_thenStatesRemainJsonObjectsAndNullRemainsNull() {
		String state = "{\"nested\":{\"items\":[1,true,null]},\"text\":\"quote \\\" and slash \\\\u0000\"}";
		OperatorContext.runAs(operator, () -> {
			auditLog.record(entry("valid", null, state));
			assertThat(jdbc.sql("""
					SELECT before_state IS NULL AND jsonb_typeof(after_state) = 'object'
					       AND after_state = CAST(:state AS jsonb)
					  FROM audit_log WHERE entity_ref = 'valid'
					""").param("state", state).query(Boolean.class).single()).isTrue();
		});
	}

	private static AuditEntry entry(String ref, String beforeState, String afterState) {
		return AuditEntry.of(AuditActor.system("audit-json-test"), "event.updated", "event", null, ref,
				beforeState, afterState);
	}

	private Connection ownerConnection() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

}

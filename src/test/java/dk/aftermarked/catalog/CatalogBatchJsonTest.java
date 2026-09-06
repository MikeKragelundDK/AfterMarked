package dk.aftermarked.catalog;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import dk.aftermarked.TestcontainersConfiguration;
import dk.aftermarked.catalog.fields.CustomFieldScope;
import dk.aftermarked.catalog.fields.CustomFieldValues;
import dk.aftermarked.catalog.internal.EventJdbcStore;
import dk.aftermarked.catalog.internal.EventJdbcStore.EventRow;
import dk.aftermarked.catalog.internal.OrganizerJdbcStore;
import dk.aftermarked.catalog.internal.OrganizerJdbcStore.OrganizerRow;
import dk.aftermarked.catalog.internal.TicketTypeJdbcStore;
import dk.aftermarked.catalog.internal.TicketTypeJdbcStore.TicketTypeRow;
import dk.aftermarked.money.Money;
import dk.aftermarked.tenancy.OperatorContext;
import dk.aftermarked.tenancy.OperatorId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Rigtige jsonb-batches: feltdokumentet må aldrig kunne ændre de omgivende rækker. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CatalogBatchJsonTest {

	static final OperatorId A = new OperatorId(UUID.fromString("00000000-0000-4000-8000-0000000000e8"));

	static final OperatorId B = new OperatorId(UUID.fromString("00000000-0000-4000-8000-0000000000e9"));

	static final UUID ORGANIZER = UUID.fromString("00000000-0000-4000-8000-00000000e801");

	static final UUID EVENT = UUID.fromString("00000000-0000-4000-8000-00000000e802");

	static final Instant START = Instant.parse("2027-06-30T16:00:00Z");

	static final Currency DKK = Currency.getInstance("DKK");

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	OrganizerJdbcStore organizers;

	@Autowired
	EventJdbcStore events;

	@Autowired
	TicketTypeJdbcStore ticketTypes;

	@BeforeEach
	void seedTwoOperatorsAndOneCatalog() throws SQLException {
		try (Connection owner = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword()); Statement sql = owner.createStatement()) {
			sql.execute("DELETE FROM operator");
			sql.execute("""
					INSERT INTO operator (id, slug, name, hostname) VALUES
					  ('%s', 'batch-a', 'Batch A', 'a.batch.test'),
					  ('%s', 'batch-b', 'Batch B', 'b.batch.test')
					""".formatted(A, B));
			sql.execute("""
					INSERT INTO organizer (id, operator_id, ref, name)
					VALUES ('%s', '%s', 'seed-organizer', 'Seed organizer')
					""".formatted(ORGANIZER, A));
			sql.execute("""
					INSERT INTO event (id, operator_id, organizer_id, ref, title, starts_at, sales_status, currency)
					VALUES ('%s', '%s', '%s', 'seed-event', 'Seed event',
					        TIMESTAMPTZ '2027-06-30 16:00:00+00', 'ON_SALE', 'DKK')
					""".formatted(EVENT, A, ORGANIZER));
		}
	}

	@ParameterizedTest
	@EnumSource(CustomFieldScope.class)
	void givenASiblingRowInjection_whenUpsertingTheBatch_thenTheWholeStatementIsRejected(CustomFieldScope scope) {
		String document = switch (scope) {
			case ORGANIZER -> "{}},{\"ref\":\"injected\",\"name\":\"Injected\",\"custom_fields\":{}";
			case EVENT -> """
					{}},{"organizer_id":"%s","ref":"injected","title":"Injected",
					"starts_at":"2027-06-30T16:00:00Z","sales_status":"UNKNOWN",
					"currency":"DKK","custom_fields":{}
					""".formatted(ORGANIZER).strip();
			case TICKET_TYPE -> """
					{}},{"event_id":"%s","ref":"injected","name":"Injected",
					"face_value_minor":1000,"currency":"DKK","custom_fields":{}
					""".formatted(EVENT).strip();
		};
		long before = count(scope);

		assertThatThrownBy(() -> upsert(scope, "Original", List.of("{}", document), 1))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(count(scope)).isEqualTo(before);
	}

	@ParameterizedTest
	@EnumSource(CustomFieldScope.class)
	void givenAnEscapedNul_whenUpsertingTheBatch_thenTheWholeStatementIsRejected(CustomFieldScope scope) {
		long before = count(scope);

		assertThatThrownBy(() -> upsert(scope, "Original", List.of("{}", "{\"note\":\"\\u0000\"}"), 1))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(count(scope)).isEqualTo(before);
	}

	@ParameterizedTest
	@EnumSource(CustomFieldScope.class)
	void givenEscapedTextAndEquivalentJson_whenUpsertingAgain_thenOnlyChangedDataCreatesAnOutcome(
			CustomFieldScope scope) {
		String document = "{\"note\":\"Quote \\\" line\\n and literal \\\\u0000\",\"decimal\":1.00}";
		List<Change> created = upsert(scope, "Original", List.of(document), 1);
		assertThat(created).singleElement().satisfies(change -> {
			assertThat(change.created()).isTrue();
			assertThat(change.beforeState()).isNull();
			assertThat(change.afterState()).isNotNull();
		});
		UUID id = created.getFirst().id();
		String initialState = state(scope, id);
		assertThat(matchesDocument(scope, id, document)).isTrue();

		assertThat(upsert(scope, "Original", List.of(document), 1)).isEmpty();
		assertThat(upsert(scope, "Original", List.of(document.replace("1.00", "1")), 2)).isEmpty();
		assertThat(state(scope, id)).isEqualTo(initialState);

		assertThat(upsert(scope, "Changed", List.of(document), 2)).singleElement().satisfies(change -> {
			assertThat(change.id()).isEqualTo(id);
			assertThat(change.created()).isFalse();
			assertThat(change.beforeState()).isEqualTo(initialState);
			assertThat(change.afterState()).isNotEqualTo(initialState);
		});
		assertThat(matchesDocument(scope, id, document)).isTrue();
	}

	@Test
	void givenAnotherTenantsEvent_whenUpdatingSalesStatus_thenTheRowAndVersionStayUnchanged() {
		String before = state(CustomFieldScope.EVENT, EVENT);

		assertThat(OperatorContext.callAs(B, () -> events.updateSalesStatus(EVENT, SalesStatus.UNKNOWN, 1)))
				.isFalse();
		assertThat(state(CustomFieldScope.EVENT, EVENT)).isEqualTo(before);
		assertThat(OperatorContext.callAs(A, () -> events.updateSalesStatus(EVENT, SalesStatus.UNKNOWN, 1)))
				.isTrue();
		assertThat(OperatorContext.callAs(A, () -> events.updateSalesStatus(EVENT, SalesStatus.ON_SALE, 1)))
				.isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "XXX", "XTS" })
	void givenACurrencyWithoutMinorUnits_whenInsertingASkeleton_thenItIsRejectedBeforeWriting(String code) {
		assertThatThrownBy(() -> OperatorContext.callAs(A, () -> events.insertSkeleton(ORGANIZER,
				new EventRef("invalid-currency"), START, Currency.getInstance(code))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("no minor unit");

		assertThat(OperatorContext.callAs(A, () -> events.findByRef(new EventRef("invalid-currency")))).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(CustomFieldScope.class)
	void givenFieldsValidatedUnderAnotherVersion_whenUpserting_thenTheBatchIsRejectedBeforeWriting(
			CustomFieldScope scope) {
		long before = count(scope);

		assertThatThrownBy(() -> upsert(scope, "Original", List.of("{}"), 2, 1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Custom field schema version must match the batch schema version");

		assertThat(count(scope)).isEqualTo(before);
	}

	private List<Change> upsert(CustomFieldScope scope, String name, List<String> documents, int schemaVersion) {
		return upsert(scope, name, documents, schemaVersion, schemaVersion);
	}

	private List<Change> upsert(CustomFieldScope scope, String name, List<String> documents, int schemaVersion,
			int fieldSchemaVersion) {
		return OperatorContext.callAs(A, () -> {
			List<CustomFieldValues> fields = documents.stream()
					.map(document -> new CustomFieldValues(scope, fieldSchemaVersion, document)).toList();
			return switch (scope) {
				case ORGANIZER -> organizers.upsertAll(java.util.stream.IntStream.range(0, fields.size())
						.mapToObj(index -> new OrganizerRow(new OrganizerRef("row-" + index), name,
								fields.get(index))).toList(), schemaVersion).stream()
						.map(row -> new Change(row.id(), row.created(), row.beforeState(), row.afterState())).toList();
				case EVENT -> events.upsertAll(java.util.stream.IntStream.range(0, fields.size())
						.mapToObj(index -> new EventRow(ORGANIZER, new EventRef("row-" + index), name, null,
								START, null, SalesStatus.UNKNOWN, null, null, DKK, fields.get(index))).toList(),
						schemaVersion).stream()
						.map(row -> new Change(row.id(), row.created(), row.beforeState(), row.afterState())).toList();
				case TICKET_TYPE -> ticketTypes.upsertAll(java.util.stream.IntStream.range(0, fields.size())
						.mapToObj(index -> new TicketTypeRow(EVENT, new TicketTypeRef("row-" + index), name,
								Money.of(12000, DKK), fields.get(index))).toList(), schemaVersion).stream()
						.map(row -> new Change(row.id(), row.created(), row.beforeState(), row.afterState())).toList();
			};
		});
	}

	private long count(CustomFieldScope scope) {
		String query = switch (scope) {
			case ORGANIZER -> "SELECT count(*) FROM organizer";
			case EVENT -> "SELECT count(*) FROM event";
			case TICKET_TYPE -> "SELECT count(*) FROM ticket_type";
		};
		return OperatorContext.callAs(A, () -> jdbc.sql(query).query(Long.class).single());
	}

	private String state(CustomFieldScope scope, UUID id) {
		String query = switch (scope) {
			case ORGANIZER -> "SELECT to_jsonb(o.*) FROM organizer o WHERE id = :id";
			case EVENT -> "SELECT to_jsonb(e.*) FROM event e WHERE id = :id";
			case TICKET_TYPE -> "SELECT to_jsonb(t.*) FROM ticket_type t WHERE id = :id";
		};
		return OperatorContext.callAs(A, () -> jdbc.sql(query).param("id", id).query(String.class).single());
	}

	private boolean matchesDocument(CustomFieldScope scope, UUID id, String document) {
		String query = switch (scope) {
			case ORGANIZER -> "SELECT custom_fields = CAST(:document AS jsonb) FROM organizer WHERE id = :id";
			case EVENT -> "SELECT custom_fields = CAST(:document AS jsonb) FROM event WHERE id = :id";
			case TICKET_TYPE -> "SELECT custom_fields = CAST(:document AS jsonb) FROM ticket_type WHERE id = :id";
		};
		return OperatorContext.callAs(A, () -> jdbc.sql(query).param("id", id).param("document", document)
				.query(Boolean.class).single());
	}

	private record Change(UUID id, boolean created, String beforeState, String afterState) {
	}

}

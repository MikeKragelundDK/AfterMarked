package dk.aftermarked.catalog;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dk.aftermarked.TestcontainersConfiguration;
import dk.aftermarked.audit.AuditLog;
import dk.aftermarked.catalog.internal.CatalogIngestReport;
import dk.aftermarked.catalog.internal.CatalogIngestService;
import dk.aftermarked.localization.Translations;
import dk.aftermarked.tenancy.OperatorContext;
import dk.aftermarked.tenancy.OperatorId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;

/** Oversættelsernes faktiske ændringer skal kunne spores til importen, også uden ændrede grundfelter. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CatalogTranslationAuditTest {

	static final OperatorId A = new OperatorId(UUID.fromString("00000000-0000-4000-8000-000000003a01"));

	static final OperatorId B = new OperatorId(UUID.fromString("00000000-0000-4000-8000-000000003a02"));

	static final String UPDATED = "After \"revision\"\nBackslash \\ and euro €";

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	CatalogIngestService ingest;

	@Autowired
	Translations translations;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JsonMapper json;

	@MockitoSpyBean
	AuditLog auditLog;

	@BeforeEach
	void seedTwoOperators() throws SQLException {
		try (Connection owner = DriverManager.getConnection(
				postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
			try (PreparedStatement delete = owner.prepareStatement("DELETE FROM operator WHERE id IN (?, ?)")) {
				delete.setObject(1, A.value());
				delete.setObject(2, B.value());
				delete.executeUpdate();
			}
			try (PreparedStatement insert = owner.prepareStatement("""
					INSERT INTO operator (id, slug, name, hostname) VALUES
					  (?, 'translation-audit-a', 'Audit A', 'a.translation-audit.test'),
					  (?, 'translation-audit-b', 'Audit B', 'b.translation-audit.test')
					""")) {
				insert.setObject(1, A.value());
				insert.setObject(2, B.value());
				insert.executeUpdate();
			}
		}
	}

	@Test
	void givenANewMultilingualCatalog_whenPushed_thenEveryTranslationHasItsCreatedStateAndSubject() {
		push(A, catalog(), "create-translations");

		List<JsonNode> stored = translationStates(A);
		List<JsonNode> entries = translationAudits(A, "create-translations");
		assertThat(entries).hasSize(4);
		assertThat(entries).extracting(entry -> entry.get("after_state")).containsExactlyInAnyOrderElementsOf(stored);
		for (JsonNode entry : entries) {
			JsonNode state = entry.get("after_state");
			assertThat(entry.get("before_state").isNull()).isTrue();
			assertSubject(entry, state, "translation_created", "create-translations");
		}
	}

	@ParameterizedTest
	@CsvSource({
			"organizer, name, /organizers/0/name",
			"event, title, /organizers/0/events/0/title",
			"event, venue, /organizers/0/events/0/venue",
			"ticket_type, name, /organizers/0/events/0/ticketTypes/0/name"
	})
	void givenOnlyATranslationChanged_whenPushed_thenItsExactBeforeAndAfterAreAudited(
			String entityType, String field, String pointer) {
		push(A, catalog(), "initial");
		JsonNode before = translationState(A, entityType, field);
		List<String> stamps = catalogStamps();
		JsonNode changed = catalog();
		((ObjectNode) changed.at(pointer)).put("en", UPDATED);

		CatalogIngestReport report = push(A, changed, "update-translation");

		assertThat(report.translationsWritten()).isEqualTo(1);
		assertThat(report.translationsRemoved()).isZero();
		assertThat(catalogStamps()).isEqualTo(stamps);
		JsonNode after = translationState(A, entityType, field);
		assertThat(after.get("value").stringValue()).isEqualTo(UPDATED);
		List<JsonNode> entries = translationAudits(A, "update-translation");
		assertThat(entries).hasSize(1);
		JsonNode entry = entries.getFirst();
		assertThat(entry.get("before_state")).isEqualTo(before);
		assertThat(entry.get("after_state")).isEqualTo(after);
		assertSubject(entry, after, "translation_updated", "update-translation");
	}

	@ParameterizedTest
	@CsvSource({
			"organizer, name, /organizers/0/name",
			"event, title, /organizers/0/events/0/title",
			"event, venue, /organizers/0/events/0/venue",
			"ticket_type, name, /organizers/0/events/0/ticketTypes/0/name"
	})
	void givenATranslationRemovedFromThePayload_whenPushed_thenTheDeletedStateIsAudited(
			String entityType, String field, String pointer) {
		push(A, catalog(), "initial");
		JsonNode before = translationState(A, entityType, field);
		List<String> stamps = catalogStamps();
		JsonNode changed = catalog();
		((ObjectNode) changed.at(pointer)).remove("en");

		CatalogIngestReport report = push(A, changed, "remove-translation");

		assertThat(report.translationsWritten()).isZero();
		assertThat(report.translationsRemoved()).isEqualTo(1);
		assertThat(translationStates(A)).doesNotContain(before).hasSize(3);
		assertThat(catalogStamps()).isEqualTo(stamps);
		List<JsonNode> entries = translationAudits(A, "remove-translation");
		assertThat(entries).hasSize(1);
		JsonNode entry = entries.getFirst();
		assertThat(entry.get("before_state")).isEqualTo(before);
		assertThat(entry.get("after_state").isNull()).isTrue();
		assertSubject(entry, before, "translation_deleted", "remove-translation");
	}

	@Test
	void givenAnIdenticalMultilingualPush_whenReplayed_thenNeitherTranslationsNorAuditRowsChange() {
		push(A, catalog(), "initial");
		List<JsonNode> before = translationStates(A);
		long auditBefore = auditCount(A);

		assertThat(push(A, catalog(), "replay").changedNothing()).isTrue();

		assertThat(translationStates(A)).isEqualTo(before);
		assertThat(auditCount(A)).isEqualTo(auditBefore);
	}

	@Test
	void givenTwoOperatorsWithTheSameRefs_whenOneChangesTranslations_thenTheOtherKeepsItsDataAndAudit() {
		push(A, catalog(), "initial-a");
		push(B, catalog(), "initial-b");
		List<JsonNode> before = translationStates(B);
		long auditBefore = auditCount(B);
		JsonNode changed = catalog();
		((ObjectNode) changed.at("/organizers/0/name")).put("en", UPDATED);
		((ObjectNode) changed.at("/organizers/0/events/0/title")).remove("en");

		push(A, changed, "change-a");

		assertThat(translationAudits(A, "change-a")).hasSize(2);
		assertThat(translationStates(B)).isEqualTo(before);
		assertThat(auditCount(B)).isEqualTo(auditBefore);
	}

	@Test
	void givenOmittedTicketTypesAndAnAdminField_whenPushingADelta_thenNeitherIsDeletedOrAudited() {
		push(A, catalog(), "initial");
		JsonNode ticketBefore = translationState(A, "ticket_type", "name");
		UUID eventId = UUID.fromString(translationState(A, "event", "title").get("entity_id").stringValue());
		OperatorContext.runAs(A, () -> translations.put("event", eventId, "admin_note", Locale.ENGLISH, "Keep me"));
		JsonNode adminBefore = translationState(A, "event", "admin_note");
		JsonNode delta = catalog();
		((ObjectNode) delta.at("/organizers/0/events/0")).putArray("ticketTypes");
		((ObjectNode) delta.at("/organizers/0/events/0/title")).put("en", UPDATED);

		push(A, delta, "delta");

		assertThat(translationState(A, "ticket_type", "name")).isEqualTo(ticketBefore);
		assertThat(translationState(A, "event", "admin_note")).isEqualTo(adminBefore);
		assertThat(translationAudits(A, "delta")).hasSize(1);
	}

	@Test
	void givenAnAuditWriteFailure_whenChangingTheCatalogAndTranslations_thenAllChangesRollBack() {
		push(A, catalog(), "initial");
		List<JsonNode> before = translationStates(A);
		List<String> stamps = catalogStamps();
		long auditBefore = auditCount(A);
		JsonNode changed = catalog();
		((ObjectNode) changed.at("/organizers/0/name")).put("da", "Changed base").put("en", UPDATED);
		((ObjectNode) changed.at("/organizers/0/events/0/title")).remove("en");
		((ObjectNode) changed.at("/organizers/0/events/0/venue")).put("sv", "New translation");
		doThrow(new DataIntegrityViolationException("Simulated audit failure")).when(auditLog).recordAll(anyList());

		assertThatThrownBy(() -> push(A, changed, "failed-audit")).isInstanceOf(CatalogException.class);

		assertThat(translationStates(A)).isEqualTo(before);
		assertThat(catalogStamps()).isEqualTo(stamps);
		assertThat(auditCount(A)).isEqualTo(auditBefore);
	}

	private JsonNode catalog() {
		return json.readTree("""
				{"organizers":[{"ref":"acme","name":{"da":"Acme Live","en":"Acme Live Ltd"},"events":[
				  {"ref":"summer","title":{"da":"Summer","en":"Summer Festival"},
				   "venue":{"da":"Showground","en":"Show Grounds"},"startsAt":"2027-06-30T16:00:00Z",
				   "salesStatus":"ON_SALE","currency":"DKK","ticketTypes":[
				     {"ref":"standard","name":{"da":"Standard","en":"Standard Ticket"},
				      "faceValue":{"amount":33000,"currency":"DKK"}}
				   ]}
				]}]}
				""");
	}

	private CatalogIngestReport push(OperatorId operator, JsonNode body, String pushId) {
		return OperatorContext.callAs(operator, () -> ingest.apply(body, pushId));
	}

	private List<JsonNode> translationStates(OperatorId operator) {
		return OperatorContext.callAs(operator, () -> jdbc.sql("""
				SELECT to_jsonb(t)::text FROM translation t ORDER BY entity_type, field, locale
				""").query(String.class).list().stream().map(json::readTree).toList());
	}

	private JsonNode translationState(OperatorId operator, String entityType, String field) {
		return translationStates(operator).stream()
				.filter(row -> row.get("entity_type").stringValue().equals(entityType)
						&& row.get("field").stringValue().equals(field))
				.findFirst().orElseThrow();
	}

	private List<JsonNode> translationAudits(OperatorId operator, String pushId) {
		return OperatorContext.callAs(operator, () -> jdbc.sql("""
				SELECT to_jsonb(a)::text FROM audit_log a
				 WHERE actor_ref = :pushId AND action LIKE '%.translation_%' ORDER BY id
				""").param("pushId", pushId).query(String.class).list().stream().map(json::readTree).toList());
	}

	private long auditCount(OperatorId operator) {
		return OperatorContext.callAs(operator, () -> jdbc.sql("SELECT count(*) FROM audit_log").query(Long.class).single());
	}

	private List<String> catalogStamps() {
		return OperatorContext.callAs(A, () -> jdbc.sql("""
				SELECT to_jsonb(o)::text AS state FROM organizer o
				UNION ALL SELECT to_jsonb(e)::text FROM event e
				UNION ALL SELECT to_jsonb(t)::text FROM ticket_type t
				ORDER BY state
				""").query(String.class).list());
	}

	private void assertSubject(JsonNode entry, JsonNode state, String verb, String pushId) {
		String entityType = state.get("entity_type").stringValue();
		assertThat(entry.get("entity_type")).isEqualTo(state.get("entity_type"));
		assertThat(entry.get("entity_id")).isEqualTo(state.get("entity_id"));
		assertThat(entry.get("entity_ref").stringValue()).isEqualTo(switch (entityType) {
			case "organizer" -> "acme";
			case "event" -> "summer";
			case "ticket_type" -> "standard";
			default -> throw new AssertionError("Unexpected entity type " + entityType);
		});
		assertThat(entry.get("action").stringValue()).isEqualTo(entityType + "." + verb);
		assertThat(entry.get("actor_type").stringValue()).isEqualTo("catalog_push");
		assertThat(entry.get("actor_ref").stringValue()).isEqualTo(pushId);
	}

}

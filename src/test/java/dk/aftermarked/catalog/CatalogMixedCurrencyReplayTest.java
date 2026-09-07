package dk.aftermarked.catalog;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import dk.aftermarked.TestcontainersConfiguration;
import dk.aftermarked.catalog.internal.CatalogIngestReport;
import dk.aftermarked.catalog.internal.CatalogIngestService;
import dk.aftermarked.money.CurrencyMismatchException;
import dk.aftermarked.tenancy.OperatorContext;
import dk.aftermarked.tenancy.OperatorId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JSON-indgangen skal følge samme valutapolitik som den typede skrivesti. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CatalogMixedCurrencyReplayTest {

	private static final OperatorId OPERATOR = new OperatorId(
			UUID.fromString("00000000-0000-4000-8000-000000002f01"));

	private static final OperatorId NEIGHBOUR = new OperatorId(
			UUID.fromString("00000000-0000-4000-8000-000000002f02"));

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	CatalogIngestService ingest;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JsonMapper json;

	@BeforeEach
	void seedAnInternationalOperator() throws SQLException {
		try (Connection owner = ownerConnection()) {
			try (PreparedStatement delete = owner.prepareStatement("DELETE FROM operator WHERE id IN (?, ?)")) {
				delete.setObject(1, OPERATOR.value());
				delete.setObject(2, NEIGHBOUR.value());
				delete.executeUpdate();
			}
			try (PreparedStatement insert = owner.prepareStatement("""
					INSERT INTO operator (id, slug, name, hostname, single_currency)
					VALUES (?, 'currency-replay', 'Currency Replay', 'replay.currency.test', false)
					""")) {
				insert.setObject(1, OPERATOR.value());
				insert.executeUpdate();
			}
		}
	}

	@Test
	void givenAnExistingMixedCatalogAndSingleCurrencyReenabled_whenReplayingItsJson_thenNoRowOrAuditChanges()
			throws SQLException {
		JsonNode catalog = catalog("DKK", "EUR");
		push(catalog, "first-push");
		setSingleCurrency(true);
		List<Map<String, Object>> before = eventStamps();
		long auditRows = auditCount();

		CatalogIngestReport replay = push(catalog, "replay-push");

		assertThat(replay.organizers()).isEqualTo(new CatalogIngestReport.LevelCounts(0, 0, 1));
		assertThat(replay.events()).isEqualTo(new CatalogIngestReport.LevelCounts(0, 0, 2));
		assertThat(replay.ticketTypes()).isEqualTo(new CatalogIngestReport.LevelCounts(0, 0, 2));
		assertThat(eventStamps()).isEqualTo(before);
		assertThat(auditCount()).isEqualTo(auditRows);
	}

	@Test
	void givenAnExistingMixedCatalogAndSingleCurrencyReenabled_whenJsonAddsAThirdCurrency_thenThePushIsRejected()
			throws SQLException {
		push(catalog("DKK", "EUR"), "first-push");
		setSingleCurrency(true);
		List<Map<String, Object>> before = eventStamps();
		long auditRows = auditCount();

		assertThatThrownBy(() -> push(catalog("DKK", "EUR", "SEK"), "new-currency-push"))
				.isInstanceOf(CurrencyMismatchException.class)
				.hasMessageContaining("SEK");
		assertThat(eventStamps()).isEqualTo(before);
		assertThat(auditCount()).isEqualTo(auditRows);
	}

	@Test
	void givenAnEmptySingleCurrencyCatalog_whenJsonCarriesTwoCurrencies_thenThePayloadIsRejected()
			throws SQLException {
		setSingleCurrency(true);

		assertThatThrownBy(() -> push(catalog("DKK", "EUR"), "mixed-push"))
				.isInstanceOf(CatalogValidationException.class);
		assertThat(eventStamps()).isEmpty();
		assertThat(auditCount()).isZero();
	}

	@Test
	void givenAnExistingMixedCatalogAndSingleCurrencyReenabled_whenJsonChangesATitle_thenTheChangeIsApplied()
			throws SQLException {
		push(catalog("DKK", "EUR"), "initial");
		setSingleCurrency(true);
		JsonNode changed = catalog("DKK", "EUR");
		((ObjectNode) changed.at("/organizers/0/events/1")).put("title", "Revised event");

		CatalogIngestReport result = push(changed, "update-title");

		assertThat(result.events()).isEqualTo(new CatalogIngestReport.LevelCounts(0, 1, 1));
		assertThat(OperatorContext.callAs(OPERATOR, () -> jdbc.sql("""
				SELECT title FROM event WHERE ref = 'event-eur'
				""").query(String.class).single())).isEqualTo("Revised event");
	}

	@Test
	void givenASingleCurrencyCatalog_whenJsonAddsAnotherCurrencyAlongsideTheExistingOne_thenNothingChanges()
			throws SQLException {
		setSingleCurrency(true);
		push(catalog("DKK"), "initial");
		List<Map<String, Object>> before = eventStamps();
		long auditBefore = auditCount();

		assertThatThrownBy(() -> push(catalog("DKK", "EUR"), "mixed"))
				.isInstanceOf(CatalogValidationException.class);

		assertThat(eventStamps()).isEqualTo(before);
		assertThat(auditCount()).isEqualTo(auditBefore);
	}

	@Test
	void givenAnotherOperatorsMixedCatalog_whenPushingTwoCurrenciesToASingleCurrencyOperator_thenItIsRejected()
			throws SQLException {
		try (Connection owner = ownerConnection(); PreparedStatement insert = owner.prepareStatement("""
				INSERT INTO operator (id, slug, name, hostname, single_currency)
				VALUES (?, 'mixed-neighbour', 'Mixed Neighbour', 'neighbour.currency.test', false)
				""")) {
			insert.setObject(1, NEIGHBOUR.value());
			insert.executeUpdate();
		}
		OperatorContext.runAs(NEIGHBOUR, () -> ingest.apply(catalog("DKK", "EUR"), "neighbour"));
		setSingleCurrency(true);

		assertThatThrownBy(() -> push(catalog("DKK", "EUR"), "mixed"))
				.isInstanceOf(CatalogValidationException.class);

		assertThat(eventStamps()).isEmpty();
		assertThat(auditCount()).isZero();
	}

	private JsonNode catalog(String... currencies) {
		List<Map<String, Object>> events = new ArrayList<>();
		for (String currency : currencies) {
			events.add(Map.of("ref", "event-" + currency.toLowerCase(Locale.ROOT),
					"title", "Event " + currency,
					"startsAt", "2027-06-30T16:00:00Z",
					"salesStatus", "ON_SALE",
					"currency", currency,
					"ticketTypes", List.of(Map.of("ref", "standard", "name", "Standard",
							"faceValue", Map.of("amount", 33000L, "currency", currency)))));
		}
		return json.valueToTree(Map.of("organizers", List.of(
				Map.of("ref", "acme", "name", "Acme Live", "events", events))));
	}

	private CatalogIngestReport push(JsonNode catalog, String pushId) {
		return OperatorContext.callAs(OPERATOR, () -> ingest.apply(catalog, pushId));
	}

	private List<Map<String, Object>> eventStamps() {
		return OperatorContext.callAs(OPERATOR, () -> jdbc.sql("""
				SELECT id, currency, version, updated_at FROM event ORDER BY ref
				""").query().listOfRows());
	}

	private long auditCount() {
		return OperatorContext.callAs(OPERATOR,
				() -> jdbc.sql("SELECT count(*) FROM audit_log").query(Long.class).single());
	}

	private void setSingleCurrency(boolean singleCurrency) throws SQLException {
		try (Connection owner = ownerConnection();
				PreparedStatement update = owner.prepareStatement("UPDATE operator SET single_currency = ? WHERE id = ?")) {
			update.setBoolean(1, singleCurrency);
			update.setObject(2, OPERATOR.value());
			update.executeUpdate();
		}
	}

	private Connection ownerConnection() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

}

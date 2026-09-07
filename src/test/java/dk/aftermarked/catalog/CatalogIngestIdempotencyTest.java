package dk.aftermarked.catalog;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import dk.aftermarked.TestcontainersConfiguration;
import dk.aftermarked.catalog.fields.CustomFieldScope;
import dk.aftermarked.catalog.fields.CustomFieldValues;
import dk.aftermarked.catalog.internal.CatalogIngestReport;
import dk.aftermarked.catalog.internal.CatalogIngestService;
import dk.aftermarked.catalog.internal.CatalogPayload;
import dk.aftermarked.catalog.internal.CustomFieldColumns;
import dk.aftermarked.catalog.internal.EventJdbcStore;
import dk.aftermarked.catalog.internal.EventUpsert;
import dk.aftermarked.catalog.internal.OrganizerJdbcStore;
import dk.aftermarked.catalog.internal.OrganizerUpsert;
import dk.aftermarked.catalog.internal.TicketTypeJdbcStore;
import dk.aftermarked.catalog.internal.TicketTypeUpsert;
import dk.aftermarked.catalog.internal.TranslatableText;
import dk.aftermarked.money.Money;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPRINTETS EXIT-KRITERIE: det samme katalog pushet to gange ændrer ingenting.
 *
 * <p>Idempotens måles ad FIRE uafhængige veje, fordi hver af dem har sit eget
 * hul:
 * <ol>
 *   <li><strong>Rapporten.</strong> Upsertens {@code RETURNING} giver kun de
 *       rækker, den faktisk rørte, så et andet push skal give
 *       {@code created = 0, updated = 0, unchanged = pushet}.</li>
 *   <li><strong>Rækkens egen tilstand:</strong> {@code version},
 *       {@code updated_at} og {@code xmin}. Den sidste er den strengeste — den
 *       flytter sig, hvis rækken overhovedet blev skrevet om, også når værdierne
 *       endte identiske.</li>
 *   <li><strong>Audit-loggen.</strong> Et no-op-push skriver NUL linjer. Det er
 *       et bevis, der ikke deler kode med de to første.</li>
 *   <li><strong>Oversættelserne.</strong> Uden {@code Translations}' no-op-guard
 *       ville hvert push omskrive hver eneste oversættelsesrække — og de tre
 *       første veje ville alle være grønne, fordi de måler katalogtabellerne.</li>
 * </ol>
 *
 * <p>⚠ Der sammenlignes <strong>aldrig</strong> på en jsonb-kolonnes TEKST:
 * {@code jsonb} bevarer den numeriske form, der blev skrevet først, så
 * <code>{"a":1.0}</code> og <code>{"a":1}</code> er ens som jsonb og forskellige
 * som tekst.
 *
 * <p>Payloadene bygges som records og ikke gennem parseren. Det er med vilje:
 * testen skal måle SKRIVESTIEN, og en fejl i JSON-parsningen må ikke kunne
 * ligne et brud på idempotensen. Én test går den fulde JSON-vej for at vise, at
 * de to ender hænger sammen.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CatalogIngestIdempotencyTest {

	static final OperatorId A = new OperatorId(UUID.fromString("00000000-0000-4000-8000-000000001d01"));

	static final OperatorId B = new OperatorId(UUID.fromString("00000000-0000-4000-8000-000000001d02"));

	/** Arrangøren, der ejes af operatøren selv i én af testene. */
	static final UUID SEEDED_ORGANIZER = UUID.fromString("00000000-0000-4000-8000-000000001d11");

	static final OrganizerRef ACME = new OrganizerRef("acme");

	static final EventRef SUMMER = new EventRef("summer-2027");

	static final EventRef WINTER = new EventRef("winter-2027");

	static final Instant SUMMER_STARTS_AT = Instant.parse("2027-06-30T16:00:00Z");

	static final Instant SUMMER_ENDS_AT = Instant.parse("2027-06-30T23:30:00Z");

	static final Instant WINTER_STARTS_AT = Instant.parse("2027-12-27T19:00:00Z");

	static final Instant SEEDED_AT = Instant.parse("2026-09-06T10:15:30.123456Z");

	static final Currency DKK = Money.currencyOf("DKK");

	/**
	 * Nøglerne står med vilje i ikke-alfabetisk rækkefølge. {@code jsonb}
	 * normaliserer dem, og at det andet push med den OMVENDTE rækkefølge ikke
	 * rører rækken, er hele pointen i {@code IS DISTINCT FROM} på en
	 * jsonb-kolonne.
	 */
	static final String DOCUMENT = "{\"b\":2,\"a\":1}";

	static final String REORDERED_DOCUMENT = "{\"a\":1,\"b\":2}";

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	CatalogIngestService ingest;

	@Autowired
	OrganizerJdbcStore organizers;

	@Autowired
	EventJdbcStore events;

	@Autowired
	TicketTypeJdbcStore ticketTypes;

	/**
	 * Den WIREDE mapper og ikke en, testen selv bygger: JSON-vejen skal måles
	 * gennem den hærdning, {@code JsonConfig} lægger på, og ikke gennem Jacksons
	 * defaults.
	 */
	@Autowired
	JsonMapper json;

	/**
	 * Seedes som EJEREN: runtime-rollen er blokeret af RLS på {@code operator} og
	 * kan ikke lægge sin egen række ind. {@code DELETE FROM operator} kaskaderer
	 * gennem katalog, oversættelser OG audit-log, så hver test starter tomt —
	 * også selvom applikationsrollen har fået {@code DELETE} på {@code audit_log}
	 * trukket tilbage.
	 */
	@BeforeEach
	void seedTwoOperators() throws SQLException {
		try (Connection owner = ownerConnection(); Statement sql = owner.createStatement()) {
			sql.execute("DELETE FROM operator");
			sql.execute("""
					INSERT INTO operator (id, slug, name, hostname)
					VALUES ('%s', 'ing-a', 'Ingest A', 'a.ingest.test')
					""".formatted(A));
			sql.execute("""
					INSERT INTO operator (id, slug, name, hostname)
					VALUES ('%s', 'ing-b', 'Ingest B', 'b.ingest.test')
					""".formatted(B));
		}
	}

	// --- De fire beviser på et uændret andet push -------------------------

	@Test
	void givenAFullCatalog_whenPushedTwiceInARow_thenTheSecondPushChangesNoRow() {
		CatalogIngestReport first = push(aFullCatalog(), "push-1");
		assertThat(first.organizers().created()).isEqualTo(1);
		assertThat(first.events().created()).isEqualTo(2);
		assertThat(first.ticketTypes().created()).isEqualTo(4);

		CatalogIngestReport second = push(aFullCatalog(), "push-2");

		assertThat(second.organizers())
				.as("zero returned rows is the SUCCESS signal of an upsert, not a failure")
				.isEqualTo(new CatalogIngestReport.LevelCounts(0, 0, 1));
		assertThat(second.events()).isEqualTo(new CatalogIngestReport.LevelCounts(0, 0, 2));
		assertThat(second.ticketTypes()).isEqualTo(new CatalogIngestReport.LevelCounts(0, 0, 4));
	}

	/**
	 * {@code xmin} er den strengeste af de tre: den flytter sig, hvis rækken
	 * overhovedet blev skrevet om — også når de nye værdier var identiske med de
	 * gamle. En omskrevet række koster en ny generation, autovacuum skal rydde
	 * op efter, hver eneste nat.
	 */
	@Test
	void givenAFullCatalog_whenPushedTwiceInARow_thenNoVersionAndNoUpdatedAtMoves() {
		push(aFullCatalog(), "push-1");
		RowStamp summerBefore = eventStamp(SUMMER);
		RowStamp winterBefore = eventStamp(WINTER);

		push(aFullCatalog(), "push-2");

		assertThat(eventStamp(SUMMER)).isEqualTo(summerBefore);
		assertThat(eventStamp(WINTER)).isEqualTo(winterBefore);
		assertThat(summerBefore.version()).as("the row was born on 1 and must stay there").isEqualTo(1);
	}

	@Test
	void givenAFullCatalog_whenPushedTwiceInARow_thenTheSecondPushWritesNoAuditRow() {
		push(aFullCatalog(), "push-1");
		long afterFirst = auditRowCount();
		assertThat(afterFirst).as("one organizer, two events and four ticket types were created").isEqualTo(7);

		push(aFullCatalog(), "push-2");

		assertThat(auditRowCount())
				.as("an unchanged push writes no audit line — the second, independent proof of idempotence")
				.isEqualTo(afterFirst);
	}

	@Test
	void givenAMultilingualCatalog_whenPushedTwice_thenNoTranslationRowIsRewritten() {
		push(aMultilingualCatalog(), "push-1");
		List<String> before = translationStamps();
		assertThat(before).as("organizer name, event title, event venue and ticket type name").hasSize(4);

		CatalogIngestReport second = push(aMultilingualCatalog(), "push-2");

		assertThat(second.translationsWritten())
				.as("without the no-op guard on the upsert every nightly push rewrites every translation")
				.isZero();
		assertThat(second.translationsRemoved()).isZero();
		assertThat(translationStamps()).isEqualTo(before);
	}

	// --- To ting, der ikke må tælle som en ændring ------------------------

	@Test
	void givenACatalogWithReorderedJsonKeys_whenPushedAgain_thenNothingChanges() {
		push(aFullCatalog("Summer 2027", "Acme Live", DOCUMENT, 0), "push-1");
		RowStamp before = eventStamp(SUMMER);
		long auditBefore = auditRowCount();

		CatalogIngestReport second = push(aFullCatalog("Summer 2027", "Acme Live", REORDERED_DOCUMENT, 0), "push-2");

		assertThat(second.events()).isEqualTo(new CatalogIngestReport.LevelCounts(0, 0, 2));
		assertThat(eventStamp(SUMMER)).isEqualTo(before);
		assertThat(auditRowCount()).isEqualTo(auditBefore);
	}

	/**
	 * Skema-stemplet står i {@code SET} og ALDRIG i {@code IS DISTINCT FROM}-tuplen.
	 * Stod det i tuplen, ville en ren konfigurations-bump omskrive hver eneste
	 * uændret række, flytte {@code updated_at} og udsende en audit-linje pr.
	 * række — målt til 5.000 falske {@code event.updated} for en bump uden
	 * dataændring. Og fordi {@code SET} ikke kører, når {@code WHERE} er falsk,
	 * beholder en urørt række et SANDFÆRDIGT stempel: den version, dens værdier
	 * faktisk blev valideret under.
	 */
	@Test
	void givenTheFieldSchemaVersionBumpedWithNoDataChange_whenPushedAgain_thenNothingChanges() {
		push(aFullCatalog("Summer 2027", "Acme Live", DOCUMENT, 0), "push-1");
		RowStamp before = eventStamp(SUMMER);
		long auditBefore = auditRowCount();

		CatalogIngestReport second = push(aFullCatalog("Summer 2027", "Acme Live", DOCUMENT, 1), "push-2");

		assertThat(second.events()).isEqualTo(new CatalogIngestReport.LevelCounts(0, 0, 2));
		assertThat(eventStamp(SUMMER)).isEqualTo(before);
		assertThat(auditRowCount()).isEqualTo(auditBefore);
		assertThat(customFieldsSchemaVersionOf(SUMMER))
				.as("the stamp describes the VALUES, so an untouched row keeps a truthful one")
				.isZero();
	}

	// --- Delta-semantik ------------------------------------------------------

	@Test
	void givenOneChangedEventTitle_whenPushed_thenOnlyThatEventBumpsItsVersionAndIsAudited() {
		push(aFullCatalog(), "push-1");
		RowStamp winterBefore = eventStamp(WINTER);
		long auditBefore = auditRowCount();

		CatalogIngestReport second = push(aFullCatalog("Summer 2027 (sold out)", "Acme Live", "{}", 0), "push-2");

		assertThat(second.events()).isEqualTo(new CatalogIngestReport.LevelCounts(0, 1, 1));
		assertThat(eventStamp(SUMMER).version()).isEqualTo(2);
		assertThat(eventStamp(WINTER)).as("the other event must stand untouched").isEqualTo(winterBefore);
		assertThat(auditRowCount()).isEqualTo(auditBefore + 1);
		assertThat(latestAuditAction()).isEqualTo("event.updated");
	}

	/**
	 * "Et delta bærer færre ENTITETER. Det bærer aldrig færre FELTER." Fraværet af
	 * et event i et push betyder derfor ikke, at eventet er væk — der findes
	 * bevidst hverken {@code archived_at} eller tombstones, fordi et
	 * "fuldt" push, der ankommer tomt (kundens cron-bug, en auth-fejl der
	 * returnerer {}), ellers ville tømme hele kataloget i stilhed kl. 03.
	 */
	@Test
	void givenADeltaCarryingOneEvent_whenApplied_thenTheOtherEventsAreUntouched() {
		push(aFullCatalog(), "push-1");
		RowStamp summerBefore = eventStamp(SUMMER);

		CatalogIngestReport delta = push(aDeltaCarryingWinterOnly(), "push-2");

		assertThat(delta.events()).isEqualTo(new CatalogIngestReport.LevelCounts(0, 0, 1));
		assertThat(eventStamp(SUMMER)).as("an event the delta never mentioned must not move").isEqualTo(summerBefore);
		assertThat(readEvent(SUMMER)).isPresent();
		assertThat(readTicketType(SUMMER, new TicketTypeRef("vip"))).isPresent();
	}

	/**
	 * Beviset for filtreringen af billettype-kortet: read-back'en giver ALLE
	 * billettyper på eventet, og sendes hele kortet videre som {@code entityIds}
	 * til {@code replaceAll}, slettes den urørte billettypes oversættelser i
	 * stilhed — {@code replaceAll} kaster ikke, for rækkerne er en delmængde.
	 * Derfor bærer early bird her den ene oversættelsesrække, et ufiltreret delta
	 * ville slette, og rækkens hele skrivetilstand sammenlignes før og efter.
	 */
	@Test
	void givenAnEventWithThreeTicketTypes_whenADeltaCarriesTwo_thenTheThirdSurvives() {
		push(aFullCatalogWhereEarlyBirdIsTranslated(), "push-1");
		assertThat(readTicketType(SUMMER, new TicketTypeRef("early"))).isPresent();
		List<String> translationsBefore = translationStamps();
		assertThat(translationsBefore).as("the fixture must carry the one row an unfiltered delta would delete")
				.singleElement().asString().contains("/name/en/");

		CatalogIngestReport delta = push(aDeltaCarryingTwoTicketTypesOnSummer(), "push-2");

		assertThat(delta.ticketTypes()).isEqualTo(new CatalogIngestReport.LevelCounts(0, 0, 2));
		assertThat(delta.translationsRemoved()).as("nothing the delta did not carry may be removed").isZero();
		assertThat(readTicketType(SUMMER, new TicketTypeRef("early"))).isPresent();
		assertThat(translationStamps())
				.as("the untouched ticket type's translation row must survive, unwritten")
				.isEqualTo(translationsBefore);
	}

	// --- Audit-sporet --------------------------------------------------------

	@Test
	void givenAPushedCatalog_whenReadingTheAuditTrail_thenEveryCreatedAndUpdatedRowIsRecordedWithItsDiff() {
		push(aFullCatalog(), "push-1");

		assertThat(auditRowCount()).isEqualTo(7);
		assertThat(actionCount("organizer.created")).isEqualTo(1);
		assertThat(actionCount("event.created")).isEqualTo(2);
		assertThat(actionCount("ticket_type.created")).isEqualTo(4);
		assertThat(auditRowsWithAnAfterState()).as("every written row carries its new state").isEqualTo(7);
		assertThat(auditRowsWithABeforeState()).as("a creation has no before state").isZero();

		push(aFullCatalog("Summer 2027 (sold out)", "Acme Live", "{}", 0), "push-2");

		assertThat(actionCount("event.updated")).isEqualTo(1);
		assertThat(auditRowsWithABeforeState())
				.as("an update carries the whole row before and after — the diff is free from the upsert's RETURNING")
				.isEqualTo(1);
		assertThat(auditedTitleAfter("event.updated")).isEqualTo("Summer 2027 (sold out)");
		assertThat(auditedActorRef("event.updated")).isEqualTo("push-2");
	}

	/**
	 * Kolonne-partitionering: hver kolonne i pushets {@code SET}-liste er
	 * KUNDEEJET. {@code is_default} er VORES flag, det står ikke på
	 * {@code OrganizerRow}, og et natligt push må derfor aldrig kunne rydde det —
	 * ellers ville alle skelet-events hænge på en arrangør uden default-mærke fra
	 * kl. 03.
	 */
	@Test
	void givenAnOperatorOwnedFlag_whenTheNightlyPushRuns_thenItIsNotOverwritten() {
		OperatorContext.runAs(A, () -> organizers.insert(
				new Organizer(SEEDED_ORGANIZER, ACME, "Acme Live", true, 1, SEEDED_AT, SEEDED_AT),
				CustomFieldColumns.empty(SEEDED_ORGANIZER)));

		CatalogIngestReport report = push(aFullCatalog("Summer 2027", "Acme Live 2027", "{}", 0), "push-1");

		assertThat(report.organizers().updated()).as("the name did change, so the row WAS rewritten").isEqualTo(1);
		Organizer readBack = OperatorContext.callAs(A, () -> organizers.findByRef(ACME)).orElseThrow();
		assertThat(readBack.name()).isEqualTo("Acme Live 2027");
		assertThat(readBack.isDefault()).as("our own flag survives the customer's push").isTrue();
		assertThat(readBack.id()).as("the row keeps its identity").isEqualTo(SEEDED_ORGANIZER);
	}

	// --- Tenant-isolation ----------------------------------------------------

	@Test
	void givenAPushForOneOperator_whenAnotherOperatorReadsItsCatalog_thenItSeesNothing() {
		push(aFullCatalog(), "push-1");

		assertThat(OperatorContext.callAs(B, () -> organizers.findByRef(ACME))).isEmpty();
		assertThat(OperatorContext.callAs(B, () -> events.idsByRef(List.of(SUMMER, WINTER)))).isEmpty();
		assertThat(OperatorContext.callAs(B,
				() -> jdbc.sql("SELECT count(*) FROM audit_log").query(Long.class).single()))
				.as("the audit trail is tenant scoped like everything else")
				.isZero();
	}

	// --- Den fulde JSON-vej --------------------------------------------------

	/**
	 * Den ene test, der går hele vejen fra kundens rå JSON. De øvrige bygger
	 * records direkte, så en fejl i parsningen ikke kan ligne et brud på
	 * idempotensen — men de to ender skal hænge sammen, og det vises her.
	 *
	 * <p>Payloaden bærer med vilje INGEN custom felter: operatøren {@code ing-a}
	 * har ingen egen feltskema-fil og falder tilbage til {@code base.yml}, som
	 * ikke deklarerer nogen nøgler. Et felt her ville blive afvist med
	 * {@code UNKNOWN_FIELD_KEY} — og det ville være parseren, der havde ret.
	 */
	@Test
	void givenAJsonCatalogBody_whenPushedThroughTheParser_thenItIsWrittenAndASecondPushChangesNothing() {
		JsonNode body = json.readTree("""
				{
				  "organizers": [
				    {
				      "ref": "acme",
				      "name": "Acme Live",
				      "events": [
				        {
				          "ref": "summer-2027",
				          "title": "Summer 2027",
				          "startsAt": "2027-06-30T16:00:00Z",
				          "salesStatus": "ON_SALE",
				          "currency": "DKK",
				          "ticketTypes": [
				            {
				              "ref": "standard",
				              "name": "Standard",
				              "faceValue": { "amount": 33000, "currency": "DKK" }
				            }
				          ]
				        }
				      ]
				    }
				  ]
				}
				""");

		CatalogIngestReport first = OperatorContext.callAs(A, () -> ingest.apply(body, "json-1"));
		assertThat(first.organizers().created()).isEqualTo(1);
		assertThat(first.events().created()).isEqualTo(1);
		assertThat(first.ticketTypes().created()).isEqualTo(1);
		assertThat(readEvent(SUMMER)).isPresent();

		RowStamp before = eventStamp(SUMMER);
		CatalogIngestReport second = OperatorContext.callAs(A, () -> ingest.apply(body, "json-2"));

		assertThat(second.events()).isEqualTo(new CatalogIngestReport.LevelCounts(0, 0, 1));
		assertThat(eventStamp(SUMMER)).isEqualTo(before);
	}

	// --- Payloadene ----------------------------------------------------------

	private CatalogPayload aFullCatalog() {
		return aFullCatalog("Summer 2027", "Acme Live", "{}", 0);
	}

	/**
	 * Standardkataloget: én arrangør, to events, fire billettyper. Sommer-eventet
	 * har både sted, slutdato, salgsslut og salgs-URL; vinter-eventet har ingen
	 * af delene, så begge kolonnesæt kommer gennem hver eneste test.
	 */
	private CatalogPayload aFullCatalog(String summerTitle, String organizerName, String summerDocument,
			int schemaVersion) {
		EventUpsert summer = new EventUpsert(SUMMER, plain(summerTitle), plain("Roskilde"), SUMMER_STARTS_AT,
				SUMMER_ENDS_AT, SalesStatus.ON_SALE, SUMMER_ENDS_AT, "https://acme.test/summer", DKK,
				new CustomFieldValues(CustomFieldScope.EVENT, schemaVersion, summerDocument),
				List.of(ticketType("standard", "Standard", 33_000, schemaVersion),
						ticketType("vip", "VIP", 99_000, schemaVersion),
						ticketType("early", "Early bird", 25_000, schemaVersion)));
		EventUpsert winter = new EventUpsert(WINTER, plain("Winter 2027"), null, WINTER_STARTS_AT, null,
				SalesStatus.SOLD_OUT, null, null, DKK, emptyFields(CustomFieldScope.EVENT, schemaVersion),
				List.of(ticketType("standard", "Standard", 20_000, schemaVersion)));
		return catalogOf(organizerName, schemaVersion, List.of(summer, winter));
	}

	/**
	 * Standardkataloget, men early bird-billetten bærer en engelsk oversættelse:
	 * den ene række, et ufiltreret delta på to af tre billettyper ville slette.
	 */
	private CatalogPayload aFullCatalogWhereEarlyBirdIsTranslated() {
		EventUpsert summer = new EventUpsert(SUMMER, plain("Summer 2027"), plain("Roskilde"), SUMMER_STARTS_AT,
				SUMMER_ENDS_AT, SalesStatus.ON_SALE, SUMMER_ENDS_AT, "https://acme.test/summer", DKK,
				emptyFields(CustomFieldScope.EVENT, 0),
				List.of(ticketType("standard", "Standard", 33_000, 0), ticketType("vip", "VIP", 99_000, 0),
						new TicketTypeUpsert(new TicketTypeRef("early"), translated("Early bird", "Early bird (EN)"),
								Money.of(25_000, DKK), emptyFields(CustomFieldScope.TICKET_TYPE, 0))));
		EventUpsert winter = new EventUpsert(WINTER, plain("Winter 2027"), null, WINTER_STARTS_AT, null,
				SalesStatus.SOLD_OUT, null, null, DKK, emptyFields(CustomFieldScope.EVENT, 0),
				List.of(ticketType("standard", "Standard", 20_000, 0)));
		return catalogOf("Acme Live", 0, List.of(summer, winter));
	}

	/** Et delta bærer færre ENTITETER — her kun det ene event, der er ændret på. */
	private CatalogPayload aDeltaCarryingWinterOnly() {
		EventUpsert winter = new EventUpsert(WINTER, plain("Winter 2027"), null, WINTER_STARTS_AT, null,
				SalesStatus.SOLD_OUT, null, null, DKK, emptyFields(CustomFieldScope.EVENT, 0),
				List.of(ticketType("standard", "Standard", 20_000, 0)));
		return catalogOf("Acme Live", 0, List.of(winter));
	}

	private CatalogPayload aDeltaCarryingTwoTicketTypesOnSummer() {
		EventUpsert summer = new EventUpsert(SUMMER, plain("Summer 2027"), plain("Roskilde"), SUMMER_STARTS_AT,
				SUMMER_ENDS_AT, SalesStatus.ON_SALE, SUMMER_ENDS_AT, "https://acme.test/summer", DKK,
				emptyFields(CustomFieldScope.EVENT, 0),
				List.of(ticketType("standard", "Standard", 33_000, 0), ticketType("vip", "VIP", 99_000, 0)));
		return catalogOf("Acme Live", 0, List.of(summer));
	}

	/** Hvert oversætteligt felt bærer en engelsk version ved siden af grundteksten. */
	private CatalogPayload aMultilingualCatalog() {
		TicketTypeUpsert standard = new TicketTypeUpsert(new TicketTypeRef("standard"),
				translated("Standard", "Standard ticket"), Money.of(33_000, DKK),
				emptyFields(CustomFieldScope.TICKET_TYPE, 0));
		EventUpsert summer = new EventUpsert(SUMMER, translated("Summer 2027", "Summer Festival 2027"),
				translated("Roskilde Showground", "Roskilde Show Grounds"), SUMMER_STARTS_AT, SUMMER_ENDS_AT,
				SalesStatus.ON_SALE, null, null, DKK, emptyFields(CustomFieldScope.EVENT, 0), List.of(standard));
		OrganizerUpsert acme = new OrganizerUpsert(ACME, translated("Acme Live", "Acme Live Ltd"),
				emptyFields(CustomFieldScope.ORGANIZER, 0), List.of(summer));
		return new CatalogPayload(List.of(acme), List.of(), 0);
	}

	private static CatalogPayload catalogOf(String organizerName, int schemaVersion, List<EventUpsert> pushedEvents) {
		OrganizerUpsert acme = new OrganizerUpsert(ACME, plain(organizerName),
				emptyFields(CustomFieldScope.ORGANIZER, schemaVersion), pushedEvents);
		return new CatalogPayload(List.of(acme), List.of(), schemaVersion);
	}

	private static TicketTypeUpsert ticketType(String ref, String name, long minorUnits, int schemaVersion) {
		return new TicketTypeUpsert(new TicketTypeRef(ref), plain(name), Money.of(minorUnits, DKK),
				emptyFields(CustomFieldScope.TICKET_TYPE, schemaVersion));
	}

	private static CustomFieldValues emptyFields(CustomFieldScope scope, int schemaVersion) {
		return new CustomFieldValues(scope, schemaVersion, CustomFieldValues.EMPTY_DOCUMENT);
	}

	/** En almindelig streng fra kundens payload: grundtekst uden oversættelser. */
	private static TranslatableText plain(String base) {
		return new TranslatableText(base, Map.of());
	}

	/** Grundteksten på operatørens basissprog plus én engelsk oversættelse. */
	private static TranslatableText translated(String base, String english) {
		return new TranslatableText(base, Map.of("en", english));
	}

	// --- Læsninger ----------------------------------------------------------

	private CatalogIngestReport push(CatalogPayload payload, String pushId) {
		return OperatorContext.callAs(A, () -> ingest.apply(payload, pushId));
	}

	private Optional<Event> readEvent(EventRef ref) {
		return OperatorContext.callAs(A, () -> events.findByRef(ref));
	}

	private Optional<TicketType> readTicketType(EventRef eventRef, TicketTypeRef ref) {
		return OperatorContext.callAs(A, () -> {
			UUID eventId = events.idsByRef(List.of(eventRef)).get(eventRef);
			return (eventId == null) ? Optional.<TicketType>empty() : ticketTypes.findByRef(eventId, ref);
		});
	}

	/**
	 * Rækkens hele skrivetilstand. {@code xmin} læses som tekst — den er en
	 * {@code xid}, og en JDBC-mapping af den typen findes ikke.
	 */
	private RowStamp eventStamp(EventRef ref) {
		return OperatorContext.callAs(A, () -> jdbc.sql("""
				SELECT version, CAST(updated_at AS text) AS updated_at, CAST(xmin AS text) AS xmin
				  FROM event WHERE ref = :ref
				""")
				.param("ref", ref.value())
				.query((rs, row) -> new RowStamp(rs.getInt("version"), rs.getString("updated_at"),
						rs.getString("xmin")))
				.single());
	}

	private int customFieldsSchemaVersionOf(EventRef ref) {
		return OperatorContext.callAs(A,
				() -> jdbc.sql("SELECT custom_fields_schema_version FROM event WHERE ref = :ref")
						.param("ref", ref.value())
						.query(Integer.class)
						.single());
	}

	private List<String> translationStamps() {
		return OperatorContext.callAs(A, () -> jdbc.sql("""
				SELECT entity_type, entity_id, field, locale, value,
				       CAST(updated_at AS text) AS updated_at, CAST(xmin AS text) AS xmin
				  FROM translation
				 ORDER BY entity_type, entity_id, field, locale
				""")
				.query((rs, row) -> rs.getString("entity_type") + '/' + rs.getString("entity_id") + '/'
						+ rs.getString("field") + '/' + rs.getString("locale") + '/' + rs.getString("value") + '/'
						+ rs.getString("updated_at") + '/' + rs.getString("xmin"))
				.list());
	}

	private long auditRowCount() {
		return OperatorContext.callAs(A,
				() -> jdbc.sql("SELECT count(*) FROM audit_log").query(Long.class).single());
	}

	private long actionCount(String action) {
		return OperatorContext.callAs(A,
				() -> jdbc.sql("SELECT count(*) FROM audit_log WHERE action = :action")
						.param("action", action)
						.query(Long.class)
						.single());
	}

	/**
	 * Antal linjer med en FØR-tilstand — altså opdateringer. En oprettelse har
	 * ingen: {@code (old.id IS NULL)} gør {@code before_state} til SQL NULL.
	 */
	private long auditRowsWithABeforeState() {
		return OperatorContext.callAs(A, () -> jdbc
				.sql("SELECT count(*) FROM audit_log WHERE before_state IS NOT NULL")
				.query(Long.class)
				.single());
	}

	/** Antal linjer med en EFTER-tilstand. Hver skrevet række har én. */
	private long auditRowsWithAnAfterState() {
		return OperatorContext.callAs(A, () -> jdbc
				.sql("SELECT count(*) FROM audit_log WHERE after_state IS NOT NULL")
				.query(Long.class)
				.single());
	}

	private String latestAuditAction() {
		return OperatorContext.callAs(A,
				() -> jdbc.sql("SELECT action FROM audit_log ORDER BY id DESC LIMIT 1").query(String.class).single());
	}

	/** Titlen som den står i audit-linjens {@code after_state}. Læses som jsonb, aldrig som tekst. */
	private String auditedTitleAfter(String action) {
		return OperatorContext.callAs(A,
				() -> jdbc.sql("SELECT after_state ->> 'title' FROM audit_log WHERE action = :action")
						.param("action", action)
						.query(String.class)
						.single());
	}

	private String auditedActorRef(String action) {
		return OperatorContext.callAs(A,
				() -> jdbc.sql("SELECT actor_ref FROM audit_log WHERE action = :action")
						.param("action", action)
						.query(String.class)
						.single());
	}

	private Connection ownerConnection() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

	/** {@code version}, {@code updated_at} og {@code xmin} — hele rækkens skrivetilstand i én værdi. */
	record RowStamp(int version, String updatedAt, String xmin) {
	}

}

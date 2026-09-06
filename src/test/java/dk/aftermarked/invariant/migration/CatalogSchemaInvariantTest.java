package dk.aftermarked.invariant.migration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import dk.aftermarked.TestcontainersConfiguration;
import dk.aftermarked.catalog.Event;
import dk.aftermarked.catalog.EventRef;
import dk.aftermarked.catalog.Organizer;
import dk.aftermarked.catalog.OrganizerRef;
import dk.aftermarked.catalog.SalesStatus;
import dk.aftermarked.catalog.TicketType;
import dk.aftermarked.catalog.TicketTypeRef;
import dk.aftermarked.catalog.internal.CustomFieldColumns;
import dk.aftermarked.catalog.internal.EventJdbcStore;
import dk.aftermarked.catalog.internal.OrganizerJdbcStore;
import dk.aftermarked.catalog.internal.TicketTypeJdbcStore;
import dk.aftermarked.money.Money;
import dk.aftermarked.tenancy.OperatorContext;
import dk.aftermarked.tenancy.OperatorId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INVARIANT — kataloget mod V5 (PLAN §3). Denne test er spec.
 *
 * <p>Den erstatter det, {@code ddl-auto=validate} gjorde, før persistensen blev
 * records og håndskrevet SQL: hvert aggregat holdes op mod sin tabel
 * <em>begge veje</em>, og hvert aggregat skrives, læses tilbage og
 * sammenlignes. Det er de to tests, PLAN §3 kræver af et nyt aggregat, og de
 * fanger hver sin fejl — skema-testen en kolonne uden et felt, round-trip-testen
 * en glemt kolonne i et {@code INSERT} eller en glemt linje i en RowMapper.
 *
 * <p><strong>Sammenligningen SUBTRAHERER {@code operator_id}, den lægger det
 * aldrig til.</strong> Kolonnen findes på alle tre tabeller og på ingen af
 * recordsene: hver indsættelse skriver {@code current_operator_id()} ordret i
 * SQL'en, så der ikke findes en kodesti, der KAN bære et forkert tenant-id ind i
 * en skrivning. En fejlende skema-test må derfor <strong>aldrig</strong>
 * "rettes" ved at føje et {@code operatorId}-felt til et aggregat — og lige så
 * lidt ved at føje {@code customFields} til det offentlige {@code Event}, som
 * ville give orders, payments og marketplace adgang til kundens feltværdier
 * gennem den unavngivne grænseflade (PLAN §2.6, DoD punkt 4). Kolonnerne uden
 * felt dækkes af {@link CustomFieldColumns}, som ligger i {@code internal}
 * netop derfor.
 *
 * <p>Skrivning OG læsning ligger inde i {@code OperatorContext.runAs}, og der
 * åbnes ingen transaktion udenom: kontekstens værdi lægges på databasen ved
 * forbindelses-checkout, så en forbindelse taget FØR {@code runAs} ser nul
 * rækker — hvilket ligner en mapping-fejl uden at være det.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CatalogSchemaInvariantTest {

	static final OperatorId A = new OperatorId(UUID.fromString("00000000-0000-4000-8000-0000000000c7"));

	static final UUID ORGANIZER = UUID.fromString("00000000-0000-4000-8000-00000000c101");

	static final UUID EVENT = UUID.fromString("00000000-0000-4000-8000-00000000c102");

	static final UUID TICKET_TYPE = UUID.fromString("00000000-0000-4000-8000-00000000c103");

	/** Allerede trunkeret til mikrosekunder — Postgres kan ikke gemme mere. */
	static final Instant WRITTEN_AT = Instant.parse("2026-09-06T10:15:30.123456Z");

	static final Instant STARTS_AT = Instant.parse("2027-06-30T16:00:00Z");

	static final Instant ENDS_AT = Instant.parse("2027-06-30T23:30:00Z");

	/**
	 * Nøglerne står med vilje i ikke-alfabetisk rækkefølge: {@code jsonb}
	 * normaliserer dem, og det er dét, upsertens {@code IS DISTINCT FROM} hviler
	 * på.
	 */
	static final String DOCUMENT = "{\"b\":2,\"a\":1}";

	/** De tre klasser, der ejer al SQL mod katalogtabellerne. Læses som kilde nedenfor. */
	static final List<Path> STORES = List.of(
			storePath("OrganizerJdbcStore.java"), storePath("EventJdbcStore.java"),
			storePath("TicketTypeJdbcStore.java"));

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

	/**
	 * Seedes som EJEREN: runtime-rollen er blokeret af RLS på {@code operator} og
	 * kan ikke lægge sin egen række ind. {@code DELETE FROM operator} kaskaderer
	 * gennem hele kataloget, så hver test starter på et tomt katalog.
	 */
	@BeforeEach
	void seedAnOperator() throws SQLException {
		try (Connection owner = ownerConnection(); Statement sql = owner.createStatement()) {
			sql.execute("DELETE FROM operator");
			sql.execute("""
					INSERT INTO operator (id, slug, name, hostname)
					VALUES ('%s', 'cat-a', 'Catalog A', 'a.catalog.test')
					""".formatted(A));
		}
	}

	// --- Skema, begge veje --------------------------------------------------

	@Test
	void givenTheOrganizerRecords_whenComparingThemToTheOrganizerTable_thenEveryFieldHasAColumnAndEveryColumnAField() {
		Set<String> columns = AggregateSchema.columnsOf(jdbc, "organizer");
		columns.removeAll(AggregateSchema.DATABASE_OWNED);

		assertThat(columns)
				.as("a column without a field is data nobody reads; a field without a column is a write that fails")
				.containsExactlyInAnyOrderElementsOf(
						AggregateSchema.columnsCoveredBy(Organizer.class, CustomFieldColumns.class));
	}

	@Test
	void givenTheEventRecords_whenComparingThemToTheEventTable_thenEveryFieldHasAColumnAndEveryColumnAField() {
		Set<String> columns = AggregateSchema.columnsOf(jdbc, "event");
		columns.removeAll(AggregateSchema.DATABASE_OWNED);

		assertThat(columns)
				.as("the two custom field columns belong to CustomFieldColumns — never to the public Event")
				.containsExactlyInAnyOrderElementsOf(
						AggregateSchema.columnsCoveredBy(Event.class, CustomFieldColumns.class));
	}

	@Test
	void givenTheTicketTypeRecords_whenComparingThemToTheTicketTypeTable_thenEveryFieldHasAColumnAndEveryColumnAField() {
		Set<String> columns = AggregateSchema.columnsOf(jdbc, "ticket_type");
		columns.removeAll(AggregateSchema.DATABASE_OWNED);

		assertThat(columns)
				.as("one Money spans face_value_minor plus the row's shared currency")
				.containsExactlyInAnyOrderElementsOf(
						AggregateSchema.columnsCoveredBy(TicketType.class, CustomFieldColumns.class));
	}

	// --- Round-trip ---------------------------------------------------------

	@Test
	void givenAnOrganizer_whenWrittenAndReadBack_thenItIsEqualToTheOriginal() {
		Organizer written = anOrganizer("acme");

		OperatorContext.runAs(A, () -> organizers.insert(written, CustomFieldColumns.empty(ORGANIZER)));
		Organizer readBack = OperatorContext.callAs(A, () -> organizers.findByRef(new OrganizerRef("acme")))
				.orElseThrow();

		assertThat(readBack).isEqualTo(written);
	}

	/**
	 * Hver kerne­kolonne er sat, inklusive {@code primary_purchase_url}. Den er
	 * bevidst en kernekolonne og ikke et custom felt — ventelisten afhænger af
	 * den (PLAN §2.2/§2.6), og et adfærdsbærende felt må ikke ligge i
	 * feltværdi-lageret. Derfor skal round-trip-testen også dække den.
	 */
	@Test
	void givenAnEvent_whenWrittenAndReadBack_thenItIsEqualToTheOriginal() {
		Event written = new Event(EVENT, ORGANIZER, new EventRef("summer-2027"), "Summer 2027", "Roskilde",
				STARTS_AT, ENDS_AT, SalesStatus.ON_SALE, ENDS_AT, "https://acme.test/summer",
				Money.currencyOf("DKK"), 1, WRITTEN_AT, WRITTEN_AT);

		OperatorContext.runAs(A, () -> {
			organizers.insert(anOrganizer("acme"), CustomFieldColumns.empty(ORGANIZER));
			events.insert(written, CustomFieldColumns.empty(EVENT));
		});
		Event readBack = OperatorContext.callAs(A, () -> events.findByRef(new EventRef("summer-2027"))).orElseThrow();

		assertThat(readBack).isEqualTo(written);
	}

	@Test
	void givenATicketType_whenWrittenAndReadBack_thenItIsEqualToTheOriginal() {
		TicketType written = new TicketType(TICKET_TYPE, EVENT, new TicketTypeRef("standard"), "Standard",
				Money.of(33_000, "DKK"), 1, WRITTEN_AT, WRITTEN_AT);

		OperatorContext.runAs(A, () -> {
			organizers.insert(anOrganizer("acme"), CustomFieldColumns.empty(ORGANIZER));
			events.insert(anEvent("summer-2027", null), CustomFieldColumns.empty(EVENT));
			ticketTypes.insert(written, CustomFieldColumns.empty(TICKET_TYPE));
		});
		TicketType readBack = OperatorContext
				.callAs(A, () -> ticketTypes.findByRef(EVENT, new TicketTypeRef("standard"))).orElseThrow();

		assertThat(readBack)
				.as("the amount and the row's currency must come back as the one Money they went in as")
				.isEqualTo(written);
	}

	/**
	 * De fire nullable kolonner er ægte valgfrie — et skelet-event fra lazy
	 * enrichment har hverken sted, slutdato, salgsslut eller salgs-URL. Et NULL,
	 * der kommer tilbage som en tom streng eller som epoch, ville være en
	 * mapping-fejl, ingen skema-test kan se.
	 */
	@Test
	void givenAnEventWithNoVenueAndNoEndDate_whenWrittenAndReadBack_thenTheNullsSurvive() {
		Event written = new Event(EVENT, ORGANIZER, new EventRef("skeleton-2027"), "skeleton-2027", null,
				STARTS_AT, null, SalesStatus.UNKNOWN, null, null, Money.currencyOf("DKK"), 1, WRITTEN_AT, WRITTEN_AT);

		OperatorContext.runAs(A, () -> {
			organizers.insert(anOrganizer("acme"), CustomFieldColumns.empty(ORGANIZER));
			events.insert(written, CustomFieldColumns.empty(EVENT));
		});
		Event readBack = OperatorContext.callAs(A, () -> events.findByRef(new EventRef("skeleton-2027"))).orElseThrow();

		assertThat(readBack).isEqualTo(written);
		assertThat(readBack.venue()).isNull();
		assertThat(readBack.endsAt()).isNull();
		assertThat(readBack.salesEndsAt()).isNull();
		assertThat(readBack.primaryPurchaseUrl()).isNull();
	}

	/**
	 * ⚠ Der sammenlignes <strong>aldrig</strong> på jsonb'ens tekst.
	 * {@code jsonb} bevarer den numeriske form, der blev skrevet først, så
	 * <code>{"a":1.0}</code> og <code>{"a":1}</code> er ens som jsonb og
	 * forskellige som tekst: en tekst-assertion består ved held og fejler den
	 * dag en kunde sender {@code 1.0}. Sammenligningen sker derfor med
	 * {@code jsonb = jsonb} i databasen.
	 */
	@Test
	void givenACustomFieldDocument_whenWrittenAndReadBack_thenItIsTheSameJsonDocument() {
		CustomFieldColumns written = new CustomFieldColumns(EVENT, DOCUMENT, 3);

		OperatorContext.runAs(A, () -> {
			organizers.insert(anOrganizer("acme"), CustomFieldColumns.empty(ORGANIZER));
			events.insert(anEvent("summer-2027", null), written);
		});
		CustomFieldColumns readBack = OperatorContext.callAs(A, () -> events.findCustomFields(EVENT)).orElseThrow();

		assertThat(readBack.id()).isEqualTo(EVENT);
		assertThat(readBack.customFieldsSchemaVersion())
				.as("the stamp describes the values, so it must survive untouched")
				.isEqualTo(3);
		assertThat(OperatorContext.callAs(A, () -> documentMatchesInTheDatabase(readBack.customFields())))
				.as("what came back must be the same document as the one in the column")
				.isTrue();
		assertThat(OperatorContext.callAs(A, () -> documentMatchesInTheDatabase(DOCUMENT)))
				.as("and the same document as the one that was written")
				.isTrue();
	}

	// --- Tenant-kolonnen og de sætninger, der skriver den -------------------

	@Test
	void givenTheCatalogTables_whenLookingForTheTenantColumn_thenEachCarriesOperatorIdAndNoRecordDoes() {
		for (String table : List.of("organizer", "event", "ticket_type")) {
			assertThat(AggregateSchema.columnsOf(jdbc, table))
					.as("[%s] must carry the tenant column RLS filters on", table)
					.contains("operator_id");
		}

		assertThat(AggregateSchema.columnsCoveredBy(Organizer.class, Event.class, TicketType.class,
				CustomFieldColumns.class))
				.as("no aggregate may be able to carry a tenant id at all")
				.doesNotContain("operator_id");
	}

	/**
	 * Den strukturelle halvdel af beslutning §8, målt på kilden: findes der ingen
	 * parameter ved det navn, findes der heller ingen kodesti, der kan binde et
	 * forkert tenant-id. RLS' {@code WITH CHECK} fanger fejlen bagefter — dette
	 * gør, at den ikke kan opstå.
	 *
	 * <p>Der scannes efter selve BINDINGEN og ikke efter ordet: javadoc'en i de
	 * tre filer nævner {@code operatorId} med vilje, netop for at forklare
	 * hvorfor det ikke er et felt.
	 */
	@Test
	void givenTheCatalogInsertStatements_whenReadingTheirSql_thenNoneBindsAnOperatorIdParameter() throws Exception {
		for (Path store : STORES) {
			String source = Files.readString(store, StandardCharsets.UTF_8);

			assertThat(source)
					.as("[%s] must write the tenant id in SQL, not bind it", store.getFileName())
					.contains("current_operator_id()");
			for (String binding : List.of(".param(\"operatorId\"", ".param(\"operator_id\"", ":operatorId",
					":operator_id")) {
				assertThat(source)
						.as("[%s] must never bind the tenant id as [%s]", store.getFileName(), binding)
						.doesNotContain(binding);
			}
		}
	}

	// --- Constraints --------------------------------------------------------

	/**
	 * Formatreglerne står i databasen og ikke i den kompakte konstruktør.
	 * Gentog aggregatet dem, kunne denne test ikke længere føre en dårlig værdi
	 * ned til constrainten — og så ville den måle Java frem for skemaet.
	 */
	@Test
	void givenABlankTitle_whenWritingAnEvent_thenTheCheckConstraintRejectsIt() {
		assertThatThrownBy(() -> OperatorContext.runAs(A, () -> {
			organizers.insert(anOrganizer("acme"), CustomFieldColumns.empty(ORGANIZER));
			events.insert(new Event(EVENT, ORGANIZER, new EventRef("blank-2027"), "   ", null, STARTS_AT, null,
					SalesStatus.UNKNOWN, null, null, Money.currencyOf("DKK"), 1, WRITTEN_AT, WRITTEN_AT),
					CustomFieldColumns.empty(EVENT));
		}))
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause().hasMessageContaining("event_title_not_blank");
	}

	@Test
	void givenAnEventEndingBeforeItStarts_whenWritten_thenTheCheckConstraintRejectsIt() {
		assertThatThrownBy(() -> OperatorContext.runAs(A, () -> {
			organizers.insert(anOrganizer("acme"), CustomFieldColumns.empty(ORGANIZER));
			events.insert(new Event(EVENT, ORGANIZER, new EventRef("backwards-2027"), "Backwards", null,
					ENDS_AT, STARTS_AT, SalesStatus.UNKNOWN, null, null, Money.currencyOf("DKK"), 1,
					WRITTEN_AT, WRITTEN_AT), CustomFieldColumns.empty(EVENT));
		}))
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause().hasMessageContaining("event_ends_after_start");
	}

	// --- Optimistisk laasning ----------------------------------------------

	@Test
	void givenAnEventAtVersionOne_whenUpdatedWithTheExpectedVersion_thenItSucceedsAndBumpsTheVersion() {
		seedOneEvent();

		boolean updated = OperatorContext.callAs(A, () -> events.updateSalesStatus(EVENT, SalesStatus.SOLD_OUT, 1));

		assertThat(updated).isTrue();
		Event readBack = OperatorContext.callAs(A, () -> events.findByRef(new EventRef("summer-2027"))).orElseThrow();
		assertThat(readBack.salesStatus()).isEqualTo(SalesStatus.SOLD_OUT);
		assertThat(readBack.version()).as("the version column IS the lock (PLAN 3)").isEqualTo(2);
	}

	/**
	 * Nul ændrede rækker betyder her — modsat upserten, hvor nul er succes — at
	 * nogen kom først. Havde sætningen ikke haft {@code AND version = ?}, ville
	 * den anden skriver stilfærdigt have overskrevet den førstes ændring.
	 */
	@Test
	void givenAnEventChangedByAnotherWriter_whenUpdatedWithAStaleVersion_thenNoRowIsChangedAndItReportsAConflict() {
		seedOneEvent();
		assertThat(OperatorContext.callAs(A, () -> events.updateSalesStatus(EVENT, SalesStatus.SOLD_OUT, 1))).isTrue();

		boolean updated = OperatorContext.callAs(A, () -> events.updateSalesStatus(EVENT, SalesStatus.ON_SALE, 1));

		assertThat(updated).as("the expected version is stale — the write must not land").isFalse();
		Event readBack = OperatorContext.callAs(A, () -> events.findByRef(new EventRef("summer-2027"))).orElseThrow();
		assertThat(readBack.salesStatus()).isEqualTo(SalesStatus.SOLD_OUT);
		assertThat(readBack.version()).isEqualTo(2);
	}

	// --- Default-arrangoeren ------------------------------------------------

	/**
	 * Højst én default-arrangør pr. operatør, håndhævet af et PARTIELT unikt
	 * indeks. Konsekvensen for koden står i migrationen: indekset kan ikke være
	 * DEFERRABLE, så et skift af default skal være to sætninger i én transaktion
	 * — etlinjers-varianten {@code SET is_default = (id = :id)} lykkes eller
	 * fejler afhængigt af rækkernes fysiske rækkefølge.
	 */
	@Test
	void givenAnOperatorWithADefaultOrganizer_whenAddingASecondDefault_thenThePartialUniqueIndexRejectsIt() {
		assertThat(OperatorContext.callAs(A, () -> organizers.insertDefault("Acme Live"))).isTrue();

		assertThatThrownBy(() -> OperatorContext.runAs(A, () -> organizers.insert(
				new Organizer(ORGANIZER, new OrganizerRef("acme"), "Acme Live", true, 1, WRITTEN_AT, WRITTEN_AT),
				CustomFieldColumns.empty(ORGANIZER))))
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause().hasMessageContaining("organizer_one_default_per_operator_idx");
	}

	/**
	 * CHECK'en TILLADER {@value OrganizerJdbcStore#DEFAULT_REF}, fordi vi selv
	 * skriver rækken; validatoren afviser den fra et katalog-push. Afvistes den
	 * begge steder, ville hvert eneste push og hver eneste lazy enrichment fejle
	 * med en 23514 — og {@code DROP CONSTRAINT} er forbudt for altid.
	 */
	@Test
	void givenTheReservedDefaultRef_whenWrittenByUs_thenTheCheckConstraintAcceptsIt() {
		assertThat(OperatorContext.callAs(A, () -> organizers.insertDefault("Acme Live")))
				.as("the row is ours to write")
				.isTrue();

		Organizer readBack = OperatorContext
				.callAs(A, () -> organizers.findByRef(new OrganizerRef(OrganizerJdbcStore.DEFAULT_REF))).orElseThrow();
		assertThat(readBack.isDefault()).isTrue();
		assertThat(readBack.ref().isReservedDefault()).isTrue();

		assertThat(OperatorContext.callAs(A, () -> organizers.insertDefault("Acme Live")))
				.as("a second call is the normal state, not an error")
				.isFalse();
	}

	// -----------------------------------------------------------------------

	private Organizer anOrganizer(String ref) {
		return new Organizer(ORGANIZER, new OrganizerRef(ref), "Acme Live", false, 1, WRITTEN_AT, WRITTEN_AT);
	}

	private Event anEvent(String ref, String purchaseUrl) {
		return new Event(EVENT, ORGANIZER, new EventRef(ref), "Summer 2027", "Roskilde", STARTS_AT, ENDS_AT,
				SalesStatus.ON_SALE, ENDS_AT, purchaseUrl, Money.currencyOf("DKK"), 1, WRITTEN_AT, WRITTEN_AT);
	}

	private void seedOneEvent() {
		OperatorContext.runAs(A, () -> {
			organizers.insert(anOrganizer("acme"), CustomFieldColumns.empty(ORGANIZER));
			events.insert(anEvent("summer-2027", null), CustomFieldColumns.empty(EVENT));
		});
	}

	/** {@code jsonb = jsonb} og aldrig {@code text = text} — se testens javadoc. */
	private boolean documentMatchesInTheDatabase(String document) {
		return jdbc.sql("SELECT custom_fields = CAST(:document AS jsonb) FROM event WHERE id = :id")
				.param("document", document)
				.param("id", EVENT)
				.query(Boolean.class).single();
	}

	private static Path storePath(String fileName) {
		return Path.of("src", "main", "java", "dk", "aftermarked", "catalog", "internal", fileName);
	}

	private Connection ownerConnection() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

}

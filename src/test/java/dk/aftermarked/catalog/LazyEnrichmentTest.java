package dk.aftermarked.catalog;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import dk.aftermarked.TestcontainersConfiguration;
import dk.aftermarked.catalog.fields.CustomFieldScope;
import dk.aftermarked.catalog.fields.CustomFieldValues;
import dk.aftermarked.catalog.internal.CatalogIngestReport;
import dk.aftermarked.catalog.internal.CatalogIngestService;
import dk.aftermarked.catalog.internal.CatalogPayload;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lazy enrichment er GULVET under kataloget, ikke en strategi (PLAN §2.2): en
 * kunde, der aldrig pusher et katalog, kan stadig få en listing, fordi den
 * første verificerede billet skaber præcis de rækker, en listing kræver.
 *
 * <p><strong>Retningen er asymmetrisk, og det er hele designet.</strong>
 * Skelettet skrives med {@code ON CONFLICT DO NOTHING} og overskriver ALDRIG
 * katalogdata; katalog-pushet er den fulde upsert og udfylder gerne et skelet.
 * De to tests, der beviser hver sin retning, er de vigtigste her.
 *
 * <p>Skelettet fødes med {@code title} = eventets egen ref,
 * {@code sales_status} = {@link SalesStatus#UNKNOWN} og
 * {@code primary_purchase_url} = NULL. Det er dét, der gør enrichment til et
 * gulv frem for en erstatning: ventelisten afhænger af netop de to felter, og
 * UNKNOWN er aldrig "til salg".
 *
 * <p>Samtidighedstesten er med vilje IKKE {@code @Transactional}: Spring ville
 * pakke begge tråde ind i én transaktion, og den advisory-lås, hele designet
 * hviler på, ville aldrig komme i konflikt med sig selv. Hver tråd åbner sit
 * eget {@code OperatorContext.callAs} — en {@code ScopedValue}-binding lever kun
 * på den tråd, der åbnede scopet.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LazyEnrichmentTest {

	static final OperatorId A = new OperatorId(UUID.fromString("00000000-0000-4000-8000-000000001e01"));

	static final String OPERATOR_NAME = "Enrichment A";

	static final EventRef SUMMER = new EventRef("summer-2027");

	static final TicketTypeRef STANDARD = new TicketTypeRef("standard");

	static final OrganizerRef ACME = new OrganizerRef("acme");

	static final Instant VERIFIED_STARTS_AT = Instant.parse("2027-06-30T16:00:00Z");

	static final Instant CATALOG_STARTS_AT = Instant.parse("2027-06-30T17:00:00Z");

	static final Instant CATALOG_ENDS_AT = Instant.parse("2027-06-30T23:30:00Z");

	static final Currency DKK = Money.currencyOf("DKK");

	static final Money FACE_VALUE = Money.of(33_000, DKK);

	/** Gennemløbet må ikke kunne hænge: en tabt lås skal fejle, ikke blokere bygget. */
	static final int TIMEOUT_SECONDS = 30;

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
	 * Seedes som EJEREN: runtime-rollen er blokeret af RLS på {@code operator}.
	 * {@code DELETE FROM operator} kaskaderer gennem hele kataloget og
	 * audit-loggen, så hver test starter på et tomt katalog — netop den
	 * tilstand, lazy enrichment findes for.
	 */
	@BeforeEach
	void seedAnOperatorWithAnEmptyCatalog() throws SQLException {
		try (Connection owner = ownerConnection(); Statement sql = owner.createStatement()) {
			sql.execute("DELETE FROM operator");
			sql.execute("""
					INSERT INTO operator (id, slug, name, hostname)
					VALUES ('%s', 'enr-a', '%s', 'a.enrich.test')
					""".formatted(A, OPERATOR_NAME));
		}
	}

	// --- Gulvet --------------------------------------------------------------

	@Test
	void givenAnEmptyCatalog_whenAVerifyResponseArrives_thenASkeletonEventAndItsTicketTypeAreCreated() {
		Event created = enrich(aVerifiedTicket());

		assertThat(created.ref()).isEqualTo(SUMMER);
		assertThat(created.startsAt()).isEqualTo(VERIFIED_STARTS_AT);
		assertThat(created.currency()).isEqualTo(DKK);
		TicketType ticketType = OperatorContext
				.callAs(A, () -> ticketTypes.findByRef(created.id(), STANDARD)).orElseThrow();
		assertThat(ticketType.name())
				.as("a skeleton without a ticket type is useless — an S4 listing references one")
				.isEqualTo("Standard");
		assertThat(ticketType.faceValue()).isEqualTo(FACE_VALUE);
		assertThat(auditRowCount())
				.as("all three created rows are recorded — the synthesized organizer too — and nothing else")
				.isEqualTo(3);
		assertThat(auditActorRefs()).containsOnly("lazy_enrichment");
		assertThat(auditRowsWithAnAfterStateAndNoBeforeState())
				.as("a skeleton nobody asked for must be answerable: the whole row is the after-state")
				.isEqualTo(3);
	}

	@Test
	void givenNoOrganizerInAVerifyResponse_whenEnriching_thenTheEventLandsUnderTheSynthesizedDefaultOrganizer() {
		Event created = enrich(aVerifiedTicket());

		Organizer synthesized = OperatorContext
				.callAs(A, () -> organizers.findByRef(new OrganizerRef(OrganizerRef.RESERVED_DEFAULT)))
				.orElseThrow();
		assertThat(synthesized.isDefault()).isTrue();
		assertThat(synthesized.name())
				.as("the customer has no organizers, so ours is named after the operator itself")
				.isEqualTo(OPERATOR_NAME);
		assertThat(created.organizerId()).isEqualTo(synthesized.id());
	}

	@Test
	void givenASkeletonEvent_whenReadingItsSalesStatus_thenItIsUnknownAndNeverOnSale() {
		Event created = enrich(aVerifiedTicket());

		assertThat(created.salesStatus()).isEqualTo(SalesStatus.UNKNOWN);
		assertThat(created.salesStatus().isOnSale())
				.as("UNKNOWN is never on sale and never resumes the waiting list")
				.isFalse();
	}

	@Test
	void givenASkeletonEvent_whenReadingItsPrimaryPurchaseUrl_thenItIsAbsent() {
		Event created = enrich(aVerifiedTicket());

		assertThat(created.primaryPurchaseUrl())
				.as("the waiting list depends on it, and a verify response cannot supply it")
				.isNull();
		assertThat(created.venue()).isNull();
		assertThat(created.endsAt()).isNull();
		assertThat(created.salesEndsAt()).isNull();
	}

	@Test
	void givenASkeletonEvent_whenReadingItsTitle_thenItIsTheEventRef() {
		Event created = enrich(aVerifiedTicket());

		assertThat(created.title())
				.as("the column is NOT NULL because the fallback chain ends on the base field (PLAN 2.9)")
				.isEqualTo(SUMMER.value());
	}

	// --- De to retninger -----------------------------------------------------

	@Test
	void givenAnExistingCatalogEvent_whenEnrichingLazily_thenTheCatalogDataIsNotOverwritten() {
		push(aCatalog("Summer 2027"), "push-1");
		long auditBefore = auditRowCount();
		RowStamp before = eventStamp();

		// Samme refs, men et andet navn og et andet beløb. Verify-svaret er
		// gulvet -- det må aldrig vinde over kundens egne værdier.
		Event event = enrich(new VerifiedTicket(SUMMER, STANDARD, "Standard from verify", Money.of(1_000, DKK),
				VERIFIED_STARTS_AT));

		assertThat(event.title()).isEqualTo("Summer 2027");
		assertThat(event.salesStatus()).isEqualTo(SalesStatus.ON_SALE);
		assertThat(event.primaryPurchaseUrl()).isEqualTo("https://acme.test/summer");
		assertThat(event.startsAt()).isEqualTo(CATALOG_STARTS_AT);
		assertThat(eventStamp()).as("nothing was rewritten at all").isEqualTo(before);
		TicketType ticketType = OperatorContext.callAs(A, () -> ticketTypes.findByRef(event.id(), STANDARD))
				.orElseThrow();
		assertThat(ticketType.name()).isEqualTo("Standard");
		assertThat(ticketType.faceValue()).isEqualTo(FACE_VALUE);
		assertThat(auditRowCount()).as("nothing was created, so nothing is logged").isEqualTo(auditBefore);
	}

	@Test
	void givenASkeletonEvent_whenTheCatalogIsPushedForTheSameRef_thenTheCatalogValuesWinWithoutChangingItsIdentity() {
		Event skeleton = enrich(aVerifiedTicket());
		assertThat(skeleton.version()).isEqualTo(1);

		CatalogIngestReport report = push(aCatalog("Summer 2027"), "push-1");

		assertThat(report.events().updated()).isEqualTo(1);
		Event filled = OperatorContext.callAs(A, () -> events.findByRef(SUMMER)).orElseThrow();
		assertThat(filled.id())
				.as("there is no reserved event ref, precisely so a later push recognises THIS row")
				.isEqualTo(skeleton.id());
		assertThat(filled.title()).isEqualTo("Summer 2027");
		assertThat(filled.salesStatus()).isEqualTo(SalesStatus.ON_SALE);
		assertThat(filled.primaryPurchaseUrl()).isEqualTo("https://acme.test/summer");
		assertThat(filled.version()).isEqualTo(2);
		assertThat(filled.organizerId())
				.as("the push moves the event from the synthesized organizer to the customer's own")
				.isNotEqualTo(skeleton.organizerId());
	}

	// --- Samtidighed ---------------------------------------------------------

	/**
	 * To rigtige tråde, hver med sin egen {@code OperatorContext}-binding og sin
	 * egen transaktion. Testen måler resultatet: én række, og begge kaldere får
	 * det SAMME event. Den beviser IKKE låsen — {@code ON CONFLICT DO NOTHING}
	 * plus genlæsning ville give det samme, fordi taberen blokerer på det unikke
	 * indeks, til vinderen har committet. Låsen bevises af valuta-kapløbene i
	 * {@code OperatorCurrencyInvariantTest}, hvor to forskellige events ellers
	 * ville slippe igennem hver sit tomme katalog.
	 */
	@Test
	void givenTwoConcurrentVerifyResponsesForTheSameRef_whenBothEnrich_thenOnlyOneRowExists() throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch go = new CountDownLatch(1);
		Callable<UUID> enrichWhenReleased = () -> {
			ready.countDown();
			go.await();
			return OperatorContext.callAs(A, () -> ingest.enrich(aVerifiedTicket()).id());
		};

		ExecutorService threads = Executors.newFixedThreadPool(2);
		UUID first;
		UUID second;
		try {
			Future<UUID> one = threads.submit(enrichWhenReleased);
			Future<UUID> other = threads.submit(enrichWhenReleased);
			assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			go.countDown();
			first = one.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			second = other.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		finally {
			threads.shutdownNow();
		}

		assertThat(first).as("the loser must read back the row the winner wrote").isEqualTo(second);
		assertThat(rowCount("event")).isEqualTo(1);
		assertThat(rowCount("ticket_type")).isEqualTo(1);
		assertThat(rowCount("organizer")).as("the synthesized default organizer is written once").isEqualTo(1);
		assertThat(auditRowCount()).as("only the rows that were actually created are logged").isEqualTo(3);
	}

	// --- Fixtures ------------------------------------------------------------

	private static VerifiedTicket aVerifiedTicket() {
		return new VerifiedTicket(SUMMER, STANDARD, "Standard", FACE_VALUE, VERIFIED_STARTS_AT);
	}

	/** Kundens eget katalog for den samme ref — med alt det, et verify-svar ikke kender. */
	private static CatalogPayload aCatalog(String title) {
		TicketTypeUpsert standard = new TicketTypeUpsert(STANDARD, plain("Standard"), FACE_VALUE,
				emptyFields(CustomFieldScope.TICKET_TYPE));
		EventUpsert summer = new EventUpsert(SUMMER, plain(title), plain("Roskilde"), CATALOG_STARTS_AT,
				CATALOG_ENDS_AT, SalesStatus.ON_SALE, CATALOG_ENDS_AT, "https://acme.test/summer", DKK,
				emptyFields(CustomFieldScope.EVENT), List.of(standard));
		OrganizerUpsert acme = new OrganizerUpsert(ACME, plain("Acme Live"),
				emptyFields(CustomFieldScope.ORGANIZER), List.of(summer));
		return new CatalogPayload(List.of(acme), List.of(), 0);
	}

	private static CustomFieldValues emptyFields(CustomFieldScope scope) {
		return new CustomFieldValues(scope, 0, CustomFieldValues.EMPTY_DOCUMENT);
	}

	private static TranslatableText plain(String base) {
		return new TranslatableText(base, Map.of());
	}

	// --- Læsninger ----------------------------------------------------------

	private Event enrich(VerifiedTicket verified) {
		return OperatorContext.callAs(A, () -> ingest.enrich(verified));
	}

	private CatalogIngestReport push(CatalogPayload payload, String pushId) {
		return OperatorContext.callAs(A, () -> ingest.apply(payload, pushId));
	}

	private RowStamp eventStamp() {
		return OperatorContext.callAs(A, () -> jdbc.sql("""
				SELECT version, CAST(updated_at AS text) AS updated_at, CAST(xmin AS text) AS xmin
				  FROM event WHERE ref = :ref
				""")
				.param("ref", SUMMER.value())
				.query((rs, row) -> new RowStamp(rs.getInt("version"), rs.getString("updated_at"),
						rs.getString("xmin")))
				.single());
	}

	/**
	 * Tabelnavnet kommer fra en fast liste i testen og aldrig fra en værdi:
	 * et tabelnavn kan ikke parametriseres, og det er præcis dér, risikoen
	 * samler sig (CLAUDE.md).
	 */
	private long rowCount(String table) {
		String allowed = switch (table) {
			case "event" -> "event";
			case "ticket_type" -> "ticket_type";
			case "organizer" -> "organizer";
			default -> throw new IllegalArgumentException("Unknown table [" + table + "]");
		};
		return OperatorContext.callAs(A,
				() -> jdbc.sql("SELECT count(*) FROM " + allowed).query(Long.class).single());
	}

	private long auditRowCount() {
		return OperatorContext.callAs(A,
				() -> jdbc.sql("SELECT count(*) FROM audit_log").query(Long.class).single());
	}

	/** Skeletternes audit-linjer: ingen før-tilstand, men hele rækken som efter-tilstand. */
	private long auditRowsWithAnAfterStateAndNoBeforeState() {
		return OperatorContext.callAs(A, () -> jdbc.sql("""
				SELECT count(*) FROM audit_log
				 WHERE after_state IS NOT NULL AND before_state IS NULL
				   AND after_state ->> 'ref' IS NOT NULL
				""").query(Long.class).single());
	}

	private List<String> auditActorRefs() {
		return OperatorContext.callAs(A,
				() -> jdbc.sql("SELECT actor_ref FROM audit_log").query(String.class).list());
	}

	private Connection ownerConnection() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

	/** {@code version}, {@code updated_at} og {@code xmin} — hele rækkens skrivetilstand i én værdi. */
	record RowStamp(int version, String updatedAt, String xmin) {
	}

}

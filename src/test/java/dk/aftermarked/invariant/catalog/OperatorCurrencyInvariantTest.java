package dk.aftermarked.invariant.catalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
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
import java.util.regex.Pattern;
import java.util.stream.Stream;

import dk.aftermarked.TestcontainersConfiguration;
import dk.aftermarked.catalog.EventRef;
import dk.aftermarked.catalog.OrganizerRef;
import dk.aftermarked.catalog.SalesStatus;
import dk.aftermarked.catalog.TicketTypeRef;
import dk.aftermarked.catalog.VerifiedTicket;
import dk.aftermarked.catalog.fields.CustomFieldScope;
import dk.aftermarked.catalog.fields.CustomFieldValues;
import dk.aftermarked.catalog.internal.CatalogIngestService;
import dk.aftermarked.catalog.internal.CatalogPayload;
import dk.aftermarked.catalog.internal.EventUpsert;
import dk.aftermarked.catalog.internal.OrganizerUpsert;
import dk.aftermarked.catalog.internal.TicketTypeUpsert;
import dk.aftermarked.catalog.internal.TranslatableText;
import dk.aftermarked.money.CurrencyMismatchException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INVARIANT — "alle events hos ÉN operatør deler valuta" (SPRINTS:175,
 * PLAN §2.5). Denne test er spec.
 *
 * <p><strong>Den bliver ALDRIG slettet, og den skal aldrig røres.</strong> Det
 * er hele grunden til, at reglen ligger som DATA
 * ({@code operator.single_currency}, V4) og ikke som en CHECK-constraint: en
 * constraint ville være permanent, fordi {@code MigrationDisciplineInvariantTest}
 * forbyder {@code DROP CONSTRAINT} for altid, mens PLAN samtidig lover, at den
 * internationale kunde koster "én linje", og CLAUDE.md forbyder at slette eller
 * svække en invariant-test. Med et flag beskriver testen BEGGE tilstande, og den
 * internationale kunde er en {@code UPDATE} uden deploy.
 *
 * <p>Reglen er PER OPERATØR. PLAN §2.5's "alle events i instansen" er skrevet
 * før beslutningen om én delt instans for alle kunder; læst ordret i dag ville
 * den forbyde en dansk og en norsk kunde at ligge i samme database — altså hele
 * arkitekturen.
 *
 * <p><strong>Fire af de seks tests måler adfærd, den femte måler race'en, og den
 * sjette måler KILDEN.</strong> Den sidste er der, fordi de fem andre kun kan
 * se den skrivesti, de selv kalder: en ny klasse med sin egen {@code UPDATE} mod
 * {@code event} ville have sin egen mening om valutaen og aldrig komme forbi
 * hverken låsen eller politikken — og den ville ligne en uskyldig sætning i et
 * review.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OperatorCurrencyInvariantTest {

	/** Enkelt-valuta slået TIL (kolonnens default). */
	static final OperatorId SINGLE = new OperatorId(UUID.fromString("00000000-0000-4000-8000-000000001f01"));

	/** Enkelt-valuta slået FRA — den internationale kunde. */
	static final OperatorId MULTI = new OperatorId(UUID.fromString("00000000-0000-4000-8000-000000001f02"));

	/** Enkelt-valuta slået TIL, men i en anden valuta end {@link #SINGLE}. */
	static final OperatorId NEIGHBOUR = new OperatorId(UUID.fromString("00000000-0000-4000-8000-000000001f03"));

	static final OrganizerRef ACME = new OrganizerRef("acme");

	static final TicketTypeRef STANDARD = new TicketTypeRef("standard");

	static final EventRef SUMMER = new EventRef("summer-2027");

	static final EventRef WINTER = new EventRef("winter-2027");

	static final Instant STARTS_AT = Instant.parse("2027-06-30T16:00:00Z");

	static final Currency DKK = Money.currencyOf("DKK");

	static final Currency EUR = Money.currencyOf("EUR");

	static final Currency SEK = Money.currencyOf("SEK");

	static final int TIMEOUT_SECONDS = 30;

	/** Produktionskildens rod. Maven kører fra projektroden. */
	static final Path PRODUCTION_ROOT = Path.of("src", "main", "java", "dk", "aftermarked");

	/**
	 * Den ENE klasse, der må skrive {@code event}. En STI og ikke et filnavn: et
	 * filnavn ville fritage enhver fremtidig fil, der tilfældigvis hed det samme,
	 * et andet sted i træet.
	 */
	static final Path THE_ONE_WRITER = PRODUCTION_ROOT.resolve(Path.of("catalog", "internal",
			"EventJdbcStore.java"));

	/**
	 * En sætning, der SKRIVER tabellen. Ordgrænsen efter tabelnavnet er
	 * nødvendig: uden den ville Modulith's egen {@code event_publication}-tabel
	 * gøre bygget rødt. Læsninger står med vilje uden for reglen — de kan ikke
	 * have en mening om valutaen.
	 */
	static final Pattern A_WRITE_TO_THE_EVENT_TABLE = Pattern
			.compile("(?i)\\b(?:insert\\s+into|update|delete\\s+from)\\s+event\\b");

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	CatalogIngestService ingest;

	/**
	 * Tre operatører, så begge flagtilstande OG naboskabet mellem to kunder kan
	 * måles. {@code single_currency} nævnes kun i den ene indsættelse — kolonnens
	 * default er {@code true}, og de otte eksisterende seedere, der ikke kender
	 * kolonnen, skal blive ved med at virke.
	 */
	@BeforeEach
	void seedThreeOperators() throws SQLException {
		try (Connection owner = ownerConnection(); Statement sql = owner.createStatement()) {
			sql.execute("DELETE FROM operator");
			sql.execute("""
					INSERT INTO operator (id, slug, name, hostname)
					VALUES ('%s', 'cur-a', 'Currency A', 'a.currency.test')
					""".formatted(SINGLE));
			sql.execute("""
					INSERT INTO operator (id, slug, name, hostname, single_currency)
					VALUES ('%s', 'cur-b', 'Currency B', 'b.currency.test', false)
					""".formatted(MULTI));
			sql.execute("""
					INSERT INTO operator (id, slug, name, hostname)
					VALUES ('%s', 'cur-c', 'Currency C', 'c.currency.test')
					""".formatted(NEIGHBOUR));
		}
	}

	// --- Begge flagtilstande -------------------------------------------------

	/**
	 * Politikken gælder BEGGE skrivestier. Ligger kontrollen kun i den ene,
	 * findes reglen kun dér — og et natligt DKK-push plus et verify-drevet
	 * EUR-skelet ville hver især se et konsistent katalog.
	 */
	@Test
	void givenAnOperatorWithSingleCurrency_whenPushingASecondCurrency_thenTheSecondEventIsRejected() {
		push(SINGLE, aCatalogWith(anEvent(SUMMER, DKK)), "push-1");

		assertThatThrownBy(() -> push(SINGLE, aCatalogWith(anEvent(WINTER, EUR)), "push-2"))
				.as("the catalog push must be refused")
				.isInstanceOf(CurrencyMismatchException.class)
				.hasMessageContaining("DKK")
				.hasMessageContaining("EUR");

		assertThatThrownBy(() -> enrich(SINGLE,
				new VerifiedTicket(WINTER, STANDARD, "Standard", Money.of(9_900, EUR), STARTS_AT)))
				.as("lazy enrichment must be refused by the very same guard")
				.isInstanceOf(CurrencyMismatchException.class)
				.hasMessageContaining("DKK")
				.hasMessageContaining("EUR");

		assertThat(currenciesOf(SINGLE)).containsExactly("DKK");
		assertThat(eventCountOf(SINGLE)).isEqualTo(1);
	}

	/**
	 * Den internationale kunde. Reglen er data, så den koster en {@code UPDATE} og
	 * ingen migration — og denne test er beviset for, at flaget faktisk gør noget
	 * i den retning også.
	 */
	@Test
	void givenAnOperatorWithSingleCurrencyTurnedOff_whenPushingASecondCurrency_thenBothEventsAreStored() {
		push(MULTI, aCatalogWith(anEvent(SUMMER, DKK)), "push-1");

		push(MULTI, aCatalogWith(anEvent(WINTER, EUR)), "push-2");

		assertThat(currenciesOf(MULTI)).containsExactly("DKK", "EUR");
		assertThat(eventCountOf(MULTI)).isEqualTo(2);
	}

	// --- Naboskabet ----------------------------------------------------------

	/**
	 * To kunder i samme delte instans, hver med sin valuta og med den SAMME ref.
	 * Politikken må kun kunne se den kaldende operatørs egne rækker — den læser
	 * gennem RLS, ikke gennem et filter, nogen skal huske at skrive.
	 */
	@Test
	void givenTwoOperatorsWithDifferentCurrencies_whenBothPush_thenNeitherBlocksTheOther() {
		push(SINGLE, aCatalogWith(anEvent(SUMMER, DKK)), "push-a");

		push(NEIGHBOUR, aCatalogWith(anEvent(SUMMER, EUR)), "push-c");

		assertThat(currenciesOf(SINGLE)).containsExactly("DKK");
		assertThat(currenciesOf(NEIGHBOUR)).containsExactly("EUR");
	}

	// --- To valutaer i SAMME batch -------------------------------------------

	/**
	 * Hullet, en naiv implementation efterlader: tjekkes valutaerne én ad gangen
	 * FØR skrivningen, ser hvert tjek det samme tomme katalog, og begge events
	 * lander. Kontrollen er derfor en MÆNGDE — unionen af det, kataloget har, og
	 * det, batchen bærer — målt før den første række røres.
	 */
	@Test
	void givenAPushCarryingTwoCurrenciesInOneBatch_whenValidated_thenItIsRejectedNamingBothCodes() {
		CatalogPayload mixed = aCatalogWith(anEvent(SUMMER, DKK), anEvent(WINTER, EUR));

		assertThatThrownBy(() -> push(SINGLE, mixed, "push-1"))
				.isInstanceOf(CurrencyMismatchException.class)
				.hasMessageContaining("DKK")
				.hasMessageContaining("EUR");

		assertThat(eventCountOf(SINGLE))
				.as("the check runs before the first row is written, and the whole push is all-or-nothing")
				.isZero();
	}

	// --- Race'en -------------------------------------------------------------

	/**
	 * Præcis den race, {@code pg_advisory_xact_lock} findes for. To
	 * verify-svar i hver sin valuta rammer et TOMT katalog samtidig; uden låsen
	 * ser begge et konsistent én-valuta-katalog og committer begge.
	 *
	 * <p>Testen er med vilje ikke {@code @Transactional}: Spring ville pakke
	 * begge tråde ind i én transaktion, og låsen ville aldrig komme i konflikt
	 * med sig selv. Hver tråd åbner sit eget {@code OperatorContext.callAs} —
	 * bindingen er en {@code ScopedValue} og lever kun på den tråd, der åbnede
	 * scopet.
	 *
	 * <p>Hvem der vinder, er med vilje ikke fastlagt. Det, reglen lover, er at
	 * præcis én gør.
	 */
	@Test
	void givenTwoConcurrentVerifyResponsesInDifferentCurrenciesForAnEmptyOperator_whenBothEnrich_thenOneIsRejected()
			throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch go = new CountDownLatch(1);

		ExecutorService threads = Executors.newFixedThreadPool(2);
		String danish;
		String european;
		try {
			Future<String> one = threads.submit(enrichWhenReleased(ready, go, SUMMER, Money.of(33_000, DKK)));
			Future<String> other = threads.submit(enrichWhenReleased(ready, go, WINTER, Money.of(9_900, EUR)));
			assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			go.countDown();
			danish = one.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			european = other.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		finally {
			threads.shutdownNow();
		}

		assertThat(List.of(danish, european))
				.as("exactly one of the two verify responses may create the operator's first currency")
				.containsExactlyInAnyOrder("accepted", "rejected");
		assertThat(eventCountOf(SINGLE)).isEqualTo(1);
		assertThat(currenciesOf(SINGLE)).hasSize(1);
	}

	// --- Flaget slået til igen -----------------------------------------------

	/**
	 * Flaget kan slås til IGEN, efter at en kunde har været international, og så
	 * er kataloget allerede blandet. Måltes unionen dér, ville hvert eneste push
	 * og hver eneste enrichment blive afvist for evigt uden en vej ud. Reglen er
	 * derfor: sættet må ikke vokse, men det, der allerede findes, må skrives.
	 * Selve flag-skiftet gates i operatør-admin (S9), ikke her.
	 */
	@Test
	void givenAnAlreadyMixedCatalogAndTheFlagTurnedBackOn_whenPushing_thenExistingCurrenciesPassAndANewOneIsRejected()
			throws SQLException {
		push(MULTI, aCatalogWith(anEvent(SUMMER, DKK), anEvent(WINTER, EUR)), "push-1");
		turnSingleCurrencyOn(MULTI);

		push(MULTI, aCatalogWith(anEvent(new EventRef("autumn-2027"), EUR)), "push-2");
		enrich(MULTI, new VerifiedTicket(new EventRef("spring-2028"), STANDARD, "Standard", Money.of(33_000, DKK),
				STARTS_AT));

		assertThatThrownBy(() -> push(MULTI, aCatalogWith(anEvent(new EventRef("tour-2028"), SEK)), "push-3"))
				.as("the set must not grow")
				.isInstanceOf(CurrencyMismatchException.class)
				.hasMessageContaining("SEK");
		assertThat(currenciesOf(MULTI)).containsExactly("DKK", "EUR");
		assertThat(eventCountOf(MULTI)).isEqualTo(4);
	}

	/**
	 * Push-siden af den samtidige måling: to natlige push i hver sin valuta mod
	 * et tomt katalog. Uden låsen ser begge et tomt katalog, begge består
	 * politikken, og ingen fremmednøgle stopper dem — det er to forskellige
	 * events. Med låsen står den anden i kø og ser den førstes valuta.
	 */
	@Test
	void givenTwoConcurrentPushesInDifferentCurrenciesForAnEmptySingleCurrencyOperator_whenBothRun_thenOneIsRejected()
			throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch go = new CountDownLatch(1);

		ExecutorService threads = Executors.newFixedThreadPool(2);
		String danish;
		String european;
		try {
			Future<String> one = threads.submit(
					pushWhenReleased(ready, go, aCatalogWith(anEvent(SUMMER, DKK)), "push-dkk"));
			Future<String> other = threads.submit(
					pushWhenReleased(ready, go, aCatalogWith(anEvent(WINTER, EUR)), "push-eur"));
			assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			go.countDown();
			danish = one.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			european = other.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		finally {
			threads.shutdownNow();
		}

		assertThat(List.of(danish, european))
				.as("exactly one of the two pushes may create the operator's first currency")
				.containsExactlyInAnyOrder("accepted", "rejected");
		assertThat(eventCountOf(SINGLE)).isEqualTo(1);
		assertThat(currenciesOf(SINGLE)).hasSize(1);
	}

	// --- Kilden --------------------------------------------------------------

	/**
	 * Den strukturelle halvdel: samler al SQL mod {@code event} i én klasse, er
	 * politikken og låsen ét sted at revidere. En anden skrivesti ville være en
	 * anden mening om valutaen, {@code sales_status} og
	 * {@code primary_purchase_url} — og den ville ikke kunne ses i et review,
	 * fordi den ville ligne en uskyldig sætning.
	 *
	 * <p>Scanningen dækker {@code src/main/java} og ikke migrationerne.
	 * Migrationer læses af {@code MigrationDisciplineInvariantTest}, og en
	 * fremtidig data-rettelse i en Flyway-fil skal blive ved med at være mulig —
	 * en invariant-test, der forbød den for altid, ville være en regel, ingen
	 * kunne komme uden om igen.
	 */
	@Test
	void givenTheProductionSources_whenLookingForStatementsWritingTheEventTable_thenOnlyEventJdbcStoreDoesIt()
			throws IOException {
		List<Path> sources = productionSources();
		assertThat(sources)
				.as("the scan must actually read the production sources — a rule over an empty set is green "
						+ "for the wrong reason")
				.hasSizeGreaterThan(30)
				.contains(THE_ONE_WRITER);

		List<String> offences = new ArrayList<>();
		for (Path file : sources) {
			if (file.equals(THE_ONE_WRITER)) {
				continue;
			}
			if (A_WRITE_TO_THE_EVENT_TABLE.matcher(withoutComments(Files.readString(file, StandardCharsets.UTF_8))).find()) {
				offences.add(file.toString());
			}
		}

		assertThat(offences)
				.as("EventJdbcStore is the ONE class that writes the event table; a second one would be a "
						+ "second opinion about the currency, the sales status and the purchase URL")
				.isEmpty();
		assertThat(A_WRITE_TO_THE_EVENT_TABLE
				.matcher(withoutComments(Files.readString(THE_ONE_WRITER, StandardCharsets.UTF_8))).find())
				.as("the positive control: the pattern must actually match the file that IS allowed to write")
				.isTrue();
		assertThat(A_WRITE_TO_THE_EVENT_TABLE
				.matcher(withoutComments("/** we never update event rows here */\n// nor delete from event\nclass X { }"))
				.find())
				.as("prose in a comment is not a statement: the scan must not turn a future Javadoc into a red build")
				.isFalse();
		assertThat(A_WRITE_TO_THE_EVENT_TABLE.matcher("INSERT INTO event_publication (id) VALUES (1)").find())
				.as("the word boundary must keep Modulith's own outbox table out of the rule")
				.isFalse();
	}

	// --- Fixtures ------------------------------------------------------------

	private Callable<String> enrichWhenReleased(CountDownLatch ready, CountDownLatch go, EventRef ref,
			Money faceValue) {
		return () -> {
			ready.countDown();
			go.await();
			try {
				OperatorContext.callAs(SINGLE,
						() -> ingest.enrich(new VerifiedTicket(ref, STANDARD, "Standard", faceValue, STARTS_AT)));
				return "accepted";
			}
			catch (CurrencyMismatchException ex) {
				return "rejected";
			}
		};
	}

	private Callable<String> pushWhenReleased(CountDownLatch ready, CountDownLatch go, CatalogPayload payload,
			String pushId) {
		return () -> {
			ready.countDown();
			go.await();
			try {
				OperatorContext.runAs(SINGLE, () -> ingest.apply(payload, pushId));
				return "accepted";
			}
			catch (CurrencyMismatchException ex) {
				return "rejected";
			}
		};
	}

	private static CatalogPayload aCatalogWith(EventUpsert... pushedEvents) {
		OrganizerUpsert acme = new OrganizerUpsert(ACME, plain("Acme Live"),
				emptyFields(CustomFieldScope.ORGANIZER), List.of(pushedEvents));
		return new CatalogPayload(List.of(acme), List.of(), 0);
	}

	private static EventUpsert anEvent(EventRef ref, Currency currency) {
		TicketTypeUpsert standard = new TicketTypeUpsert(STANDARD, plain("Standard"), Money.of(33_000, currency),
				emptyFields(CustomFieldScope.TICKET_TYPE));
		return new EventUpsert(ref, plain("Event " + ref.value()), null, STARTS_AT, null, SalesStatus.UNKNOWN,
				null, null, currency, emptyFields(CustomFieldScope.EVENT), List.of(standard));
	}

	private static CustomFieldValues emptyFields(CustomFieldScope scope) {
		return new CustomFieldValues(scope, 0, CustomFieldValues.EMPTY_DOCUMENT);
	}

	private static TranslatableText plain(String base) {
		return new TranslatableText(base, Map.of());
	}

	// --- Læsninger ----------------------------------------------------------

	private void push(OperatorId operator, CatalogPayload payload, String pushId) {
		OperatorContext.runAs(operator, () -> ingest.apply(payload, pushId));
	}

	private void enrich(OperatorId operator, VerifiedTicket verified) {
		OperatorContext.runAs(operator, () -> ingest.enrich(verified));
	}

	/** Valutaerne som RLS lader den kaldende operatør se dem. */
	private List<String> currenciesOf(OperatorId operator) {
		return OperatorContext.callAs(operator,
				() -> jdbc.sql("SELECT DISTINCT currency FROM event ORDER BY currency").query(String.class).list());
	}

	private long eventCountOf(OperatorId operator) {
		return OperatorContext.callAs(operator,
				() -> jdbc.sql("SELECT count(*) FROM event").query(Long.class).single());
	}

	/**
	 * Kommentarer og Javadoc fjernes før scanningen: SQL'en står i tekstblokke,
	 * aldrig i kommentarer, og en fremtidig Javadoc med ordene "update event"
	 * må ikke kunne gøre bygget rødt for altid.
	 */
	static String withoutComments(String source) {
		return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
	}

	private static List<Path> productionSources() throws IOException {
		try (Stream<Path> files = Files.walk(PRODUCTION_ROOT)) {
			return files.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".java"))
					.sorted()
					.toList();
		}
	}

	/** Flag-skiftet, som operatør-admin (S9) en dag laver — her direkte som ejer. */
	private void turnSingleCurrencyOn(OperatorId operator) throws SQLException {
		try (Connection owner = ownerConnection(); Statement sql = owner.createStatement()) {
			sql.execute("UPDATE operator SET single_currency = true WHERE id = '%s'".formatted(operator));
		}
	}

	private Connection ownerConnection() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

}

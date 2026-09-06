package dk.aftermarked.ops;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import dk.aftermarked.TestcontainersConfiguration;
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
 * OPERATIONS — applikationens ur og databasens ur skal være enige om, hvor
 * mange decimaler et tidsstempel har.
 *
 * <p>{@code timestamptz} gemmer MIKROsekunder. Et {@link Instant} har
 * NANOsekunder. Krydser et utrunkeret {@code Instant} JDBC-grænsen, runder
 * pgjdbc det HALVT OP på vej ned — og et senere
 * {@code WHERE created_at = :ts} med den oprindelige værdi rammer så nul rækker.
 * Nul rækker er ikke en fejl, så ingenting logger, ingenting kaster, og
 * outbox-jobbet, der ledte efter sin egen række, finder den bare aldrig.
 *
 * <p>{@code ClockConfig} løser det ét sted i stedet for ved hver skrivning, der
 * ikke går gennem en kompakt konstruktør. Denne test måler begge halvdele af
 * den beslutning: at bønnen faktisk har den opløsning, og at et stempel fra den
 * kommer uændret tilbage fra en RIGTIG Postgres. Den anden halvdel kan kun
 * måles mod databasen — en mocket persistens ville være grøn uanset hvad.
 *
 * <p>Der skrives i {@code translation}, en tabel appen i forvejen skriver i
 * under en operatør-kontekst. Alternativet — en tabel oprettet til lejligheden —
 * ville bevise noget om en tabel, der ikke findes i produktionen.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ClockOpsTest {

	static final OperatorId CLOCK_OPERATOR = new OperatorId(UUID.fromString("00000000-0000-4000-8000-0000000000ac"));

	static final int NANOS_PER_MICROSECOND = 1000;

	/**
	 * Nok aflæsninger til, at et ur, der tikkede finere end mikrosekunder,
	 * ville blive fanget. Trunkeringen gør assertionen deterministisk — dette er
	 * ikke en sandsynlighed, men en gentagelse.
	 */
	static final int SAMPLES = 1000;

	@Autowired
	Clock clock;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PostgreSQLContainer postgres;

	/**
	 * Kun DENNE operatør ryddes og genskabes — ikke hele tabellen. Otte andre
	 * testklasser deler den samme Spring-kontekst og rydder op med et
	 * {@code DELETE FROM operator}; at gøre det samme her ville betyde, at
	 * rækkefølgen af tests afgjorde, hvem der ryddede hvad. Kaskaden fra V3
	 * fjerner oversættelserne med operatøren.
	 */
	@BeforeEach
	void seedOneOperator() throws SQLException {
		try (Connection owner = ownerConnection(); Statement sql = owner.createStatement()) {
			sql.execute("DELETE FROM operator WHERE id = '%s'".formatted(CLOCK_OPERATOR));
			sql.execute("""
					INSERT INTO operator (id, slug, name, hostname)
					VALUES ('%s', 'op-clock', 'Clock Operator', 'clock.operator.test')
					""".formatted(CLOCK_OPERATOR));
		}
	}

	/**
	 * Første halvdel: uret må aldrig give noget FINERE, end databasen kan gemme.
	 *
	 * <p>Den sidste assertion er den, aflæsningerne ikke kan give. At hvert
	 * stempel er trunkeret til mikrosekunder ville også være sandt for et ur med
	 * millisekunders opløsning — og et sådant ur ville smide præcis den
	 * præcision væk, kolonnen kan holde, uden at nogen test blev rød. Den binder
	 * derfor tikket til ét mikrosekund. {@code Clock.tickMicros()} FINDES IKKE;
	 * mikrosekunder skrives med {@link Clock#tick(Clock, Duration)}.
	 */
	@Test
	void givenTheApplicationClock_whenReadingAnInstant_thenItsPrecisionMatchesWhatPostgresCanStore() {
		for (int sample = 0; sample < SAMPLES; sample++) {
			Instant reading = clock.instant();

			assertThat(reading.getNano() % NANOS_PER_MICROSECOND)
					.as("%s carries a fraction of a microsecond that timestamptz cannot store", reading)
					.isZero();
			assertThat(reading)
					.as("%s must already be what truncating to microseconds would produce", reading)
					.isEqualTo(reading.truncatedTo(ChronoUnit.MICROS));
		}

		assertThat(clock)
				.as("the bean must tick at exactly one microsecond — a millisecond clock would pass every "
						+ "reading above and still throw away precision the column can hold")
				.isEqualTo(Clock.tick(Clock.systemUTC(), Duration.ofNanos(NANOS_PER_MICROSECOND)));
	}

	/**
	 * Anden halvdel: et stempel fra uret kommer uændret tilbage.
	 *
	 * <p>Modprøven er ikke pynt. Uden den ville testen også være grøn, hvis
	 * databasen kunne gemme nanosekunder — og så ville den ikke bevise, at
	 * trunkeringen gør en forskel, kun at ingenting gik i stykker. Det andet
	 * stempel er derfor uretsstemplet plus 500 nanosekunder, altså præcis den
	 * værdi, der IKKE kan overleve turen. Der asserteres kun, at den kommer
	 * anderledes tilbage — ikke hvilken vej den rundes — for rundingsretningen
	 * er driverens og ikke vores.
	 *
	 * <p>De to rækker adskilles på {@code locale}, fordi
	 * {@code translation_unique} dækker (operatør, entitetstype, entitet, felt,
	 * sprog): to rækker med samme sprog ville være den samme række.
	 */
	@Test
	void givenAnInstantFromTheApplicationClock_whenWrittenAndReadBack_thenItIsBitIdentical() {
		Instant fromTheClock = clock.instant();

		OperatorContext.runAs(CLOCK_OPERATOR, () -> writeTranslation("da", fromTheClock));

		assertThat(readStamp("da"))
				.as("an instant from the application clock must survive the round trip unchanged")
				.isEqualTo(fromTheClock);

		Instant nanosecondPrecise = fromTheClock.plusNanos(500L);
		OperatorContext.runAs(CLOCK_OPERATOR, () -> writeTranslation("en", nanosecondPrecise));

		assertThat(readStamp("en"))
				.as("a nanosecond-precise instant does NOT survive — that is exactly why the clock truncates")
				.isNotEqualTo(nanosecondPrecise);
	}

	// -----------------------------------------------------------------------

	/**
	 * {@code atOffset(ZoneOffset.UTC)} og ikke et bart {@link Instant}: pgjdbc
	 * kan ikke udlede en SQL-type for et {@code Instant}. Og ALDRIG en
	 * {@code LocalDateTime} — den kaster ikke, den flytter værdien med den lokale
	 * offset: to timer på Mikes maskine, nul i CI.
	 */
	private void writeTranslation(String locale, Instant stamp) {
		jdbc.sql("""
				INSERT INTO translation (operator_id, entity_type, entity_id, field, locale, value,
				                         created_at, updated_at)
				VALUES (current_operator_id(), 'operator', :entityId, 'name', :locale, :value, :stamp, :stamp)
				""")
				.param("entityId", CLOCK_OPERATOR.value())
				.param("locale", locale)
				.param("value", "The Clock Operator")
				.param("stamp", stamp.atOffset(ZoneOffset.UTC))
				.update();
	}

	/** Læses som {@link OffsetDateTime} og konverteres — den vej rundt taber ingen offset. */
	private Instant readStamp(String locale) {
		return OperatorContext.callAs(CLOCK_OPERATOR, () -> jdbc.sql("""
				SELECT updated_at FROM translation
				 WHERE entity_type = 'operator' AND entity_id = :entityId AND field = 'name' AND locale = :locale
				""")
				.param("entityId", CLOCK_OPERATOR.value())
				.param("locale", locale)
				.query((rs, rowNumber) -> rs.getObject("updated_at", OffsetDateTime.class).toInstant())
				.single());
	}

	/** Ejeren (Flyway-brugeren) — RLS gælder ikke for tabelejeren, så seedet kan skrives. */
	private Connection ownerConnection() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

}

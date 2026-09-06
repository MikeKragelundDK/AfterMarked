package dk.aftermarked.invariant.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import dk.aftermarked.TestcontainersConfiguration;
import dk.aftermarked.tenancy.Operator;
import dk.aftermarked.tenancy.OperatorContext;
import dk.aftermarked.tenancy.OperatorId;
import dk.aftermarked.tenancy.OperatorRegistry;
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
 * INVARIANT — {@code Operator} som aggregat (PLAN §3, SPRINTS §S2). Denne test
 * er spec.
 *
 * <p>PLAN §3 kræver to tests af hvert aggregat, og de fanger hver sin fejl:
 * <ul>
 *   <li>en <strong>skema-test</strong>, der matcher recordens felter mod
 *       tabellens kolonner <em>begge veje</em>. Den er erstatningen for
 *       {@code ddl-auto=validate}, som forsvandt med ORM'en — uden den opdages
 *       en kolonne, ingen læser, først den dag nogen leder efter dens værdi.</li>
 *   <li>en <strong>round-trip-test</strong>, der skriver aggregatet, læser det
 *       tilbage og asserter lighed. Den fanger den glemte kolonne i et
 *       {@code INSERT} og den glemte linje i en RowMapper — begge dele er grønne
 *       i en skema-test.</li>
 * </ul>
 *
 * <p>Seederen navngiver med vilje <em>ikke</em> V4's to nye kolonner. Det er
 * præcis det, otte andre seedere i projektet gør (tre af dem i invariant-mapper,
 * hvor de ikke må redigeres), og de to tests nedenfor gør den vane til en
 * kontrakt: mister kolonnerne deres DEFAULT, bliver det rødt her frem for i en
 * test, der intet har med sprog eller valuta at gøre.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OperatorSchemaInvariantTest {

	/** Seedet uden V4's kolonner — som de otte eksisterende seedere gør det. */
	static final OperatorId SEEDED = new OperatorId(UUID.fromString("00000000-0000-4000-8000-0000000000e1"));

	/** Skrives af testen selv, med hver eneste kolonne sat eksplicit. */
	static final OperatorId ROUND_TRIP = new OperatorId(UUID.fromString("00000000-0000-4000-8000-0000000000e2"));

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	OperatorRegistry registry;

	@BeforeEach
	void seedOneOperatorWithoutTheColumnsAddedByV4() throws SQLException {
		try (Connection owner = ownerConnection(); Statement sql = owner.createStatement()) {
			sql.execute("DELETE FROM operator");
			sql.execute("""
					INSERT INTO operator (id, slug, name, hostname) VALUES
					  ('%s', 'op-seeded', 'Seeded Operator', 'seeded.operator.test')
					""".formatted(SEEDED));
		}
	}

	// --- Skema og round-trip -----------------------------------------------

	/**
	 * Begge veje: hvert felt på recorden har en kolonne, og hver kolonne har et
	 * felt. {@code operator_id} trækkes FRA tabellens kolonner og lægges aldrig
	 * TIL recordens — {@code operator} har ingen sådan kolonne, fordi dens
	 * tenant-nøgle er dens {@code id}.
	 */
	@Test
	void givenTheOperatorRecord_whenComparingItToItsTable_thenEveryFieldHasAColumnAndEveryColumnAField() {
		Set<String> columns = AggregateSchema.columnsOf(jdbc, "operator");
		columns.removeAll(AggregateSchema.DATABASE_OWNED);

		assertThat(columns)
				.as("a column without a field is data nobody reads; a field without a column is a write that fails")
				.containsExactlyInAnyOrderElementsOf(AggregateSchema.columnsCoveredBy(Operator.class));
	}

	/**
	 * Skrivning og læsning ligger <em>begge</em> inde i en operator-kontekst, og
	 * ingen af dem åbner en transaktion udenom: kontekstens værdi lægges på
	 * databasen ved forbindelses-checkout, så en forbindelse, der er taget FØR
	 * {@code runAs}, ser nul rækker — hvilket ligner en mapping-fejl og er det
	 * ikke.
	 *
	 * <p>Sproget er {@code en-GB} og {@code singleCurrency} er slået fra med
	 * vilje: en operatør med dansk og enkeltvaluta ville komme rigtigt tilbage,
	 * også hvis RowMapperen ignorerede begge kolonner og lod defaults stå.
	 */
	@Test
	void givenAnOperator_whenWrittenAndReadBack_thenItIsEqualToTheOriginal() {
		Operator written = new Operator(ROUND_TRIP, "op-round-trip", "Round Trip A/S", "roundtrip.operator.test",
				Locale.forLanguageTag("en-GB"), false, Instant.now());

		OperatorContext.runAs(written.id(), () -> insertAsRuntime(written));
		Operator readBack = OperatorContext.callAs(written.id(), registry::current);

		assertThat(readBack)
				.as("a forgotten column in the INSERT or a forgotten line in the RowMapper shows up here")
				.isEqualTo(written);
	}

	// --- Defaults, som de otte seedere hviler på ---------------------------

	@Test
	void givenAnOperatorSeededWithoutALocale_whenReadingItsBaseLanguage_thenItIsDanish() {
		assertThat(OperatorContext.callAs(SEEDED, registry::current).defaultLocale())
				.as("eight seeders insert without naming the column — the DEFAULT is what keeps them green")
				.isEqualTo(Locale.of("da"));
	}

	/**
	 * Politikken "alle events hos denne operatør deler valuta" er slået TIL,
	 * indtil nogen siger andet. Havde kolonnen ingen default, ville en ny kunde
	 * ligge med en ubestemt valutatilstand fra sin allerførste række.
	 */
	@Test
	void givenAnOperatorSeededWithoutTheCurrencyFlag_whenReadingIt_thenSingleCurrencyIsOn() {
		assertThat(OperatorContext.callAs(SEEDED, registry::current).singleCurrency())
				.as("the single-currency policy is the default; the international customer is an UPDATE")
				.isTrue();
	}

	/**
	 * Kolonnen bærer præcis den samme regex som {@code translation.locale}. En
	 * operatør, hvis basissprog ikke kan stå i {@code translation}, ville have en
	 * uskrivelig fallback-kæde — så de to constraints skal blive ved med at være
	 * enige om, hvad en sprogkode er.
	 */
	@Test
	void givenAnUnparseableLocaleCode_whenWritingItAsTheBaseLanguage_thenTheCheckConstraintRejectsIt() {
		List<String> invalid = List.of("DA", "dansk", "d", "da_DK", "da-dk", "", "en-GBB", "da-DK-x-hack");

		for (int index = 0; index < invalid.size(); index++) {
			String locale = invalid.get(index);
			String slug = "op-bad-locale-" + index;

			assertThatThrownBy(() -> insertAsOwner(slug, slug + ".operator.test", locale))
					.as("the language code [%s] must be rejected", locale)
					.isInstanceOf(SQLException.class)
					.extracting(error -> ((SQLException) error).getSQLState())
					.isEqualTo("23514");
		}
	}

	// -----------------------------------------------------------------------

	/** Skriver som appen selv gør det: gennem RLS, med hver kolonne bundet. */
	private void insertAsRuntime(Operator operator) {
		jdbc.sql("""
				INSERT INTO operator (id, slug, name, hostname, default_locale, single_currency, created_at)
				VALUES (:id, :slug, :name, :hostname, :defaultLocale, :singleCurrency, :createdAt)
				""")
				.param("id", operator.id().value())
				.param("slug", operator.slug())
				.param("name", operator.name())
				.param("hostname", operator.hostname())
				.param("defaultLocale", operator.defaultLocale().toLanguageTag())
				.param("singleCurrency", operator.singleCurrency())
				// OffsetDateTime og ikke Instant: driveren kan ikke selv udlede
				// SQL-typen for en Instant, og kaldet fejler på selve bindingen.
				.param("createdAt", OffsetDateTime.ofInstant(operator.createdAt(), ZoneOffset.UTC))
				.update();
	}

	/** Indsætter som ejeren, så RLS er ude af billedet og CHECK-constrainten er den eneste dør. */
	private void insertAsOwner(String slug, String hostname, String locale) throws SQLException {
		try (Connection owner = ownerConnection();
				var sql = owner.prepareStatement("""
						INSERT INTO operator (slug, name, hostname, default_locale)
						VALUES (?, 'Bad Locale Operator', ?, ?)
						""")) {
			sql.setString(1, slug);
			sql.setString(2, hostname);
			sql.setString(3, locale);
			sql.executeUpdate();
		}
	}

	private Connection ownerConnection() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

}

package dk.aftermarked.invariant.money;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static dk.aftermarked.invariant.money.MoneyArithmeticInvariantTest.SOURCE_ROOT;
import static dk.aftermarked.invariant.money.MoneyArithmeticInvariantTest.productionSources;
import static dk.aftermarked.invariant.money.MoneyArithmeticInvariantTest.readSource;
import static dk.aftermarked.invariant.money.MoneyArithmeticInvariantTest.scan;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * INVARIANT — der summeres aldrig hen over valutaer (SPRINTS:176, CLAUDE.md).
 * Denne test er spec.
 *
 * <p>{@link dk.aftermarked.money.Money} lukker den ene halvdel: {@code plus} og
 * {@code minus} kaster på tværs af valutaer, og der findes ingen
 * {@code sum(Collection)} uden en valuta at seede med. Men der findes to veje
 * udenom, som hverken typen, Modulith eller
 * {@code MoneyArithmeticInvariantTest} kan se — og de dækkes hver af sin test
 * her.
 *
 * <p><strong>Vej 1: SQL.</strong> {@code SELECT sum(face_value_minor) FROM
 * ticket_type} er en tekstblok. Den refererer ingen Java-type, nævner ingen
 * {@code double}, og lægger glad DKK og EUR sammen i én {@code bigint}.
 * Scanningen kræver derfor, at en sætning, der SUMMERER en beløbskolonne, også
 * nævner valutaen — i en {@code GROUP BY}, i et {@code WHERE} eller i
 * projektionen. Reglen er kun mulig, fordi beløbskolonner hedder
 * {@code %_minor} (samme konvention, som {@code MoneyColumnInvariantTest}
 * håndhæver mod det faktiske skema).
 *
 * <p><strong>Vej 2: den rå accessor.</strong> {@code Money} er en record, så
 * {@code minorUnits()} er en uundgåeligt offentlig accessor. Linjen
 *
 * <pre>{@code long total = listings.stream().mapToLong(Money::minorUnits).sum();}</pre>
 *
 * kompilerer, nævner intet flydende tal, rører ingen SQL og kalder aldrig
 * {@code plus} — og summerer DKK og EUR i én {@code long} med hvert eneste
 * andet net i projektet grønt. Derfor må accessoren kun bruges inde i
 * {@code dk.aftermarked.money} selv.
 *
 * <p><strong>Den ene undtagelse — og hvorfor den ikke er den fejl, reglen
 * findes for.</strong> {@code TicketTypeJdbcStore} bygger batchens
 * jsonb-dokument i hånden og skriver {@code minorUnits()} ind i det, fordi
 * {@code MoneyColumns.bind} binder <em>parametre</em> og ikke kan skrive et tal
 * ind i et JSON-dokument. Beløbet skrives i samme objekt som sin
 * {@code currencyCode()}, så et beløb uden valuta stadig ikke kan skrives ned,
 * og der summeres ingenting. Undtagelsen er en ENKELT navngiven fil og aldrig
 * en pakke, og den <strong>skal fjernes igen</strong>, den dag
 * {@code MoneyColumns} får en batch-form. Klassens egen javadoc siger det
 * samme fra den anden side.
 *
 * <p>Læseren deles med {@code MoneyArithmeticInvariantTest}: to håndskrevne
 * Java-lexere, der skulle holdes enige, ville før eller siden være uenige.
 */
class CurrencyAggregationInvariantTest {

	/** Modulet, der ejer beløbstypen — og dermed den eneste, der må regne på dens tal. */
	static final Path MONEY_MODULE = Path.of("money");

	/**
	 * Den ene navngivne undtagelse. En STI og ikke en pakke: en
	 * pakke-undtagelse ville dække hver eneste fremtidig klasse i
	 * {@code catalog.internal}, og den, der tilføjer nummer to, ville aldrig se
	 * denne test.
	 */
	static final Path THE_PERSISTENCE_DOOR = Path.of("catalog", "internal", "TicketTypeJdbcStore.java");

	/**
	 * Accessoren BRUGT, ikke bare nævnt: et punktum eller en metodereference
	 * foran. Uden det ville recordens egen komponent-erklæring
	 * ({@code long minorUnits}) og en lokal variabel af samme navn tælle med, og
	 * reglen ville handle om et ord i stedet for om et kald.
	 */
	static final Pattern A_RAW_MINOR_UNITS_USE = Pattern.compile("(?:\\.|::)\\s*minorUnits\\b");

	/** Et strenglitteral, der er en SQL-sætning og ikke en besked eller et kolonnenavn. */
	static final Pattern A_STATEMENT = Pattern.compile("\\b(select|insert\\s+into|update|delete\\s+from)\\b");

	/**
	 * En aggregering, der REGNER et nyt beløb ud af flere rækker.
	 * {@code min} og {@code max} står bevidst udenfor: de returnerer en værdi,
	 * der allerede står i en række, og opfinder ikke et beløb, der aldrig har
	 * eksisteret. {@code sum} og {@code avg} gør præcis det.
	 */
	static final Pattern AN_AGGREGATE_OVER_AN_AMOUNT =
			Pattern.compile("\\b(sum|avg)\\s*\\(\\s*[a-z0-9_.\"]*(?:_minor|amount)");

	static final Pattern A_CURRENCY_COLUMN = Pattern.compile("\\bcurrency\\b");

	@Test
	void givenEveryProductionSqlStatement_whenItSumsAnAmountColumn_thenTheStatementAlsoNamesTheCurrency() {
		List<String> offences = new ArrayList<>();

		for (Path file : productionSources()) {
			for (String statement : sqlStatementsIn(file)) {
				if (AN_AGGREGATE_OVER_AN_AMOUNT.matcher(statement).find()
						&& !A_CURRENCY_COLUMN.matcher(statement).find()) {
					offences.add(file + ": " + statement);
				}
			}
		}

		assertThat(offences)
				.as("a sum over an amount column that never names a currency has added DKK and EUR into one "
						+ "bigint — group by it, filter on it, or project it (SPRINTS:176)")
				.isEmpty();
	}

	@Test
	void givenOurProductionCode_whenScanningUsesOfTheRawMinorUnitsAccessor_thenOnlyMoneyAndItsPersistenceDoorTouchIt() {
		List<String> offences = new ArrayList<>();

		for (Path file : productionSources()) {
			if (mayTouchTheRawAccessor(file)) {
				continue;
			}
			if (A_RAW_MINOR_UNITS_USE.matcher(scan(readSource(file)).code()).find()) {
				offences.add(file.toString());
			}
		}

		assertThat(offences)
				.as("Money is a record, so minorUnits() is an unavoidably public accessor — "
						+ "mapToLong(Money::minorUnits).sum() adds DKK and EUR with every other net green")
				.isEmpty();
	}

	/**
	 * Vagthunden mod en grøn tautologi, i tre led. En regel over nul sætninger
	 * eller nul filer er grøn af den forkerte grund; og et regex, der aldrig
	 * matcher noget som helst, ville lade den anden test bestå for evigt.
	 *
	 * <p>Sidste led er den positive kontrol: {@code dk.aftermarked.money} BRUGER
	 * selv accessoren ({@code MoneyColumns} binder den, {@code plus} læser den),
	 * så matcher mønstret ikke dér, matcher det ingenting.
	 *
	 * <p>Gulvene ligger med vilje langt under de faktiske tal, så de ikke skal
	 * vedligeholdes, hver gang der kommer en sætning til.
	 */
	@Test
	void givenTheScan_whenCountingTheStatementsItSees_thenItIsNotCheckingAnEmptySet() {
		long statements = productionSources().stream().mapToLong(file -> sqlStatementsIn(file).size()).sum();

		assertThat(productionSources())
				.as("the scan must actually read the production sources")
				.hasSizeGreaterThan(30);
		assertThat(statements)
				.as("the SQL rule must actually see hand-written SQL — otherwise it is green for the wrong reason")
				.isGreaterThan(10);

		boolean moneyItselfUsesTheAccessor = productionSources().stream()
				.filter(file -> SOURCE_ROOT.relativize(file).startsWith(MONEY_MODULE))
				.anyMatch(file -> A_RAW_MINOR_UNITS_USE.matcher(scan(readSource(file)).code()).find());

		assertThat(moneyItselfUsesTheAccessor)
				.as("the money module genuinely uses minorUnits() — if the pattern misses it, it matches nothing")
				.isTrue();
	}

	// -----------------------------------------------------------------------

	/**
	 * Hvert strenglitteral og hver tekstblok i filen, der ligner en SQL-sætning,
	 * med små bogstaver. Postgres' identifiers er case-insensitive, så
	 * {@code SUM(FACE_VALUE_MINOR)} er præcis den samme sætning — og en regel,
	 * der kun så den ene stavemåde, ville være grøn på den anden.
	 */
	static List<String> sqlStatementsIn(Path file) {
		return scan(readSource(file)).literals().stream()
				.map(literal -> literal.toLowerCase(Locale.ROOT))
				.filter(literal -> A_STATEMENT.matcher(literal).find())
				.toList();
	}

	/** {@code dk.aftermarked.money} selv, plus den ene navngivne fil — se klassens javadoc. */
	static boolean mayTouchTheRawAccessor(Path file) {
		Path relative = SOURCE_ROOT.relativize(file);
		return relative.startsWith(MONEY_MODULE) || relative.equals(THE_PERSISTENCE_DOOR);
	}

}

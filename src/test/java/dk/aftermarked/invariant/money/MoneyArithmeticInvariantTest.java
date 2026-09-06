package dk.aftermarked.invariant.money;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * INVARIANT — der regnes ikke i decimaler i produktionskoden (CLAUDE.md,
 * PLAN §2.5). Denne test er spec.
 *
 * <p>Beløb er heltal i mindste enhed plus en ISO-4217-kode. Et flydende tal kan
 * ikke repræsentere 0,10 eksakt, så en split, der lægger tre afrundede andele
 * sammen, rammer ikke det, køberen betalte — og forskellen dukker op i en
 * afstemning uger senere, ikke i en test.
 *
 * <p><strong>Hvorfor to net og ikke ét.</strong> ArchUnit læser BYTEKODE. Den ser
 * felter, signaturer og typeafhængigheder — men den ser ikke en lokal variabel.
 * Linjen
 *
 * <pre>{@code long fee = Math.round(minorUnits * 0.10);}</pre>
 *
 * efterlader <em>intet</em> spor, ArchUnit kan finde: den lokale er en primitiv
 * uden navn i konstantpuljen, og {@code 0.10} er en konstant. Kildescanningen er
 * derfor ikke bælte-og-seler, den er det eneste net, der fanger netop den linje
 * — og netop den linje er den, en gebyrberegning bliver skrevet som.
 *
 * <p><strong>Hvorfor kommentarer og strenge fjernes først.</strong> Uden det
 * ville en dansk javadoc, der forklarer hvorfor vi IKKE bruger float, gøre
 * bygget rødt — og fjernelsen ville ske ved at slette forklaringen. Læseren
 * skiller derfor kode fra kommentarer og strenglitteraler, før regexerne kører.
 *
 * <p><strong>Den ene undtagelse.</strong> {@code dk.aftermarked.money.MoneyFormatter}
 * må røre {@code BigDecimal} — og kun til at TRYKKE et beløb, aldrig til at
 * regne på et ({@code BigDecimal.valueOf(long, scale)} flytter kommaet i et
 * heltal uden en division). Undtagelsen er navngivet én gang og
 * <strong>må aldrig udvides</strong>: så snart en anden klasse må regne i
 * decimaler, er hele pointen med heltal i mindste enhed væk. Skal en ny klasse
 * vise et beløb, kalder den formatteren.
 *
 * <p><strong>Rækkevidde.</strong> Kildescanningen læser {@code src/main/java} og
 * ikke andet. En {@code numeric}-kolonne i en migration fanges IKKE her —
 * {@code MoneyColumnInvariantTest} scanner skemaet, og migrationerne læses af
 * {@code MigrationDisciplineInvariantTest}.
 *
 * <p>Forvent i øvrigt at reglen fyrer i S7: Stripes
 * {@code BalanceTransaction.getExchangeRate()} returnerer et
 * {@code BigDecimal}. Det er featuren, ikke fejlen — og den beslutning hører i
 * den sprint sammen med sin egen begrundelse.
 */
class MoneyArithmeticInvariantTest {

	/**
	 * Kun produktionskode. Testkode må gerne regne i decimaler — fx for at vise,
	 * hvad et flydende tal ville have gjort ved beløbet.
	 */
	static final JavaClasses PRODUCTION_CODE = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("dk.aftermarked");

	/** Produktionskildens rod. Maven kører fra projektroden. */
	static final Path SOURCE_ROOT = Path.of("src", "main", "java", "dk", "aftermarked");

	static final String FORMATTER = "dk.aftermarked.money.MoneyFormatter";

	/**
	 * Undtagelsen som en STI relativ til {@link #SOURCE_ROOT}, sammenlignet som
	 * {@link Path} og ikke som en streng: separatoren er en backslash på Mikes
	 * maskine og en skråstreg i CI, og en strengsammenligning ville derfor holde
	 * det ene sted og fejle det andet — med en fejl, der ligner en udvidet
	 * undtagelse.
	 */
	static final Path FORMATTER_SOURCE = Path.of("money", "MoneyFormatter.java");

	/**
	 * Tal, der ikke er eksakte. Både primitiverne og deres indpakninger, og
	 * begge {@code java.math}-typerne: {@code BigDecimal} er eksakt, men
	 * inviterer til division og skalering, og {@code BigInteger} er et heltal,
	 * der ikke er en {@code long} — begge dele ville betyde, at et beløb ikke
	 * længere er dét, kolonnen kan gemme.
	 */
	static final Set<String> INEXACT_NUMBERS = Set.of("double", "float", "java.lang.Double", "java.lang.Float",
			"java.math.BigDecimal", "java.math.BigInteger");

	static final DescribedPredicate<JavaClass> AN_INEXACT_NUMBER =
			new DescribedPredicate<>("an inexact number") {
				@Override
				public boolean test(JavaClass type) {
					JavaClass element = type.isArray() ? type.getBaseComponentType() : type;
					return INEXACT_NUMBERS.contains(element.getName());
				}
			};

	static final DescribedPredicate<List<JavaClass>> A_PARAMETER_LIST_CONTAINING_AN_INEXACT_NUMBER =
			new DescribedPredicate<>("a parameter list containing an inexact number") {
				@Override
				public boolean test(List<JavaClass> parameters) {
					return parameters.stream().anyMatch(AN_INEXACT_NUMBER);
				}
			};

	/** {@code double}, {@code float}, {@code BigDecimal}, {@code BigInteger} som ord. */
	static final Pattern AN_INEXACT_TYPE_NAME = Pattern.compile("\\b(double|float|BigDecimal|BigInteger)\\b");

	/**
	 * Afrunding er en pengebeslutning og hører i en politik med en eksplicit
	 * {@code RoundingMode}, ikke i et {@code Math}-kald midt i en metode.
	 * {@code Math.abs} står bevidst IKKE på listen — den er legitim på en
	 * {@code long}.
	 */
	static final Pattern A_ROUNDING_CALL = Pattern.compile("Math\\.(round|floor|ceil)\\s*\\(");

	/**
	 * Decimal-litteraler i deres tre former: med komma ({@code 0.10}), med
	 * suffiks ({@code 2d}) og med eksponent ({@code 1e5}). Kig-bagud-udtrykket
	 * sikrer, at tallet står alene og ikke er et led i en identifier eller et
	 * metodekald ({@code config.setCoercion} og {@code 0x1F} rammes ikke).
	 */
	static final List<Pattern> DECIMAL_LITERAL_FORMS = List.of(
			Pattern.compile("(?<![\\w.$])\\d[\\d_]*\\.\\d"),
			Pattern.compile("(?<![\\w.$])\\d[\\d_]*(?:\\.[\\d_]*)?[fFdD](?![\\w$])"),
			Pattern.compile("(?<![\\w.$])\\d[\\d_]*(?:\\.[\\d_]*)?[eE][+-]?\\d"));

	/** Tekstblokkens afgrænser. Skrevet som en konstant, så den ikke skal læses to gange. */
	static final String TEXT_BLOCK = "\"\"\"";

	// --- Bytekode-nettet -----------------------------------------------------

	@Test
	void givenOurProductionCode_whenReadingEveryFieldType_thenNoneHoldsAnInexactNumber() {
		ArchRule rule = noFields()
				.should().haveRawType(AN_INEXACT_NUMBER)
				.because("money is integer minor units plus an ISO 4217 code — a field cannot hold a fraction "
						+ "of a smallest unit (CLAUDE.md)");

		rule.check(PRODUCTION_CODE);
	}

	@Test
	void givenOurProductionCode_whenReadingEverySignature_thenNoneReturnsAnInexactNumber() {
		ArchRule rule = noMethods()
				.should().haveRawReturnType(AN_INEXACT_NUMBER)
				.because("a method that hands back an inexact number has already lost the amount (CLAUDE.md)");

		rule.check(PRODUCTION_CODE);
	}

	/**
	 * Konstruktører tælles med, ikke kun metoder: en record-komponent af typen
	 * {@code double} ville ellers kun blive fanget som felt, og en almindelig
	 * konstruktør slet ikke.
	 */
	@Test
	void givenOurProductionCode_whenReadingEverySignature_thenNoneTakesAnInexactNumber() {
		ArchRule rule = noCodeUnits()
				.should().haveRawParameterTypes(A_PARAMETER_LIST_CONTAINING_AN_INEXACT_NUMBER)
				.because("an amount that can be handed in as a fraction is an amount someone rounded first "
						+ "(CLAUDE.md)");

		rule.check(PRODUCTION_CODE);
	}

	/**
	 * Afhængighedsvinklen fanger det, signaturerne ikke gør: en klasse, der
	 * bruger {@code BigDecimal} internt uden at nævne den i sit API.
	 */
	@Test
	void givenOurProductionCode_whenScanningDependencies_thenDecimalTypesAreOnlyUsedByTheFormatter() {
		ArchRule rule = noClasses()
				.that().doNotHaveFullyQualifiedName(FORMATTER)
				.should().dependOnClassesThat(AN_INEXACT_NUMBER)
				.because("MoneyFormatter is the one class allowed to touch BigDecimal, and only to PRINT an "
						+ "amount — the exception must never be widened (CLAUDE.md)");

		rule.check(PRODUCTION_CODE);
	}

	// --- Kilde-nettet --------------------------------------------------------

	@Test
	void givenEveryProductionSourceFile_whenStrippingCommentsAndStrings_thenNoneMentionsAFloatingPointType() {
		List<String> offences = new ArrayList<>();

		for (Path file : productionSources()) {
			if (isTheFormatter(file)) {
				continue;
			}
			String code = scan(readSource(file)).code();
			collectMatches(file, code, AN_INEXACT_TYPE_NAME, offences);
			collectMatches(file, code, A_ROUNDING_CALL, offences);
			for (Pattern form : DECIMAL_LITERAL_FORMS) {
				collectMatches(file, code, form, offences);
			}
		}

		assertThat(offences)
				.as("ArchUnit cannot see a local variable, so this scan is the only net under "
						+ "'long fee = Math.round(minorUnits * 0.10)' (CLAUDE.md)")
				.isEmpty();
	}

	/**
	 * Vagthunden mod en grøn tautologi, i fire led. Peger importeren eller
	 * stien det forkerte sted hen, ville reglerne bestå ved at kontrollere
	 * ingenting; og en læser, der ved en fejl fjernede ALT, ville bestå ved at
	 * kontrollere en tom streng.
	 *
	 * <p>Sidste led er en syntetisk prøve og ikke en anden fils javadoc: den
	 * beviser præcis de to halvdele — at kommentarer og strenge FORSVINDER, og
	 * at koden imellem dem BLIVER — uden at kunne knække, fordi nogen omskriver
	 * en kommentar et andet sted i projektet.
	 *
	 * <p>Gulvene ligger med vilje langt under de faktiske tal, så de ikke skal
	 * vedligeholdes, hver gang der kommer en fil til.
	 */
	@Test
	void givenTheRule_whenCountingTheClassesItSees_thenItIsNotCheckingAnEmptySet() {
		assertThat(PRODUCTION_CODE.stream().count())
				.as("the bytecode rules must actually see the production code")
				.isGreaterThan(30);
		assertThat(productionSources())
				.as("the source scan must actually read the production sources")
				.hasSizeGreaterThan(30);

		String formatter = scan(readSource(SOURCE_ROOT.resolve(FORMATTER_SOURCE))).code();
		assertThat(formatter)
				.as("the reader must keep real code from a real file — the formatter genuinely names BigDecimal")
				.contains("BigDecimal");

		String probe = """
				class Probe {
					// double
					/* BigDecimal */
					String note = "float";
					long amount = 1L;
				}
				""";
		String strippedProbe = scan(probe).code();
		assertThat(strippedProbe)
				.as("comments and string literals must be gone before the regexes run")
				.doesNotContain("double")
				.doesNotContain("BigDecimal")
				.doesNotContain("float");
		assertThat(strippedProbe)
				.as("the code between them must survive, or the scan proves nothing")
				.contains("long amount = 1L");
	}

	// --- Laeseren ------------------------------------------------------------

	/**
	 * Kildefilen delt i to: {@code code} er alt uden for kommentarer og
	 * litteraler, {@code literals} er indholdet af hver streng, hver tekstblok
	 * og hvert tegn-litteral.
	 *
	 * <p>{@code literals} bruges ikke her, men af
	 * {@link CurrencyAggregationInvariantTest}, som skal læse SQL'en i
	 * tekstblokkene. De to regler deler læser med vilje: to håndskrevne
	 * Java-lexere, der skulle holdes enige, ville før eller siden være uenige.
	 */
	record Scan(String code, List<String> literals) {
	}

	/**
	 * En lille Java-lexer. Den forstår præcis fire ting — linjekommentaren,
	 * blokkommentaren, strenglitteralet og tekstblokken — og det er nok: den
	 * skal kun kunne afgøre, om et tegn er kode eller ikke-kode.
	 *
	 * <p>Hver kommentar og hvert litteral erstattes af ét mellemrum og ikke af
	 * ingenting, så to identifiers på hver sin side af en kommentar ikke smelter
	 * sammen til ét ord, regexen så kunne læse forkert.
	 */
	static Scan scan(String source) {
		StringBuilder code = new StringBuilder(source.length());
		List<String> literals = new ArrayList<>();
		int index = 0;
		int length = source.length();
		while (index < length) {
			char character = source.charAt(index);
			char next = index + 1 < length ? source.charAt(index + 1) : '\0';
			if (character == '/' && next == '/') {
				index += 2;
				while (index < length && source.charAt(index) != '\n') {
					index++;
				}
				code.append(' ');
			}
			else if (character == '/' && next == '*') {
				index += 2;
				while (index < length
						&& !(source.charAt(index) == '*' && index + 1 < length && source.charAt(index + 1) == '/')) {
					index++;
				}
				index = Math.min(index + 2, length);
				code.append(' ');
			}
			else if (source.startsWith(TEXT_BLOCK, index)) {
				index += TEXT_BLOCK.length();
				int start = index;
				while (index < length) {
					if (source.charAt(index) == '\\') {
						index += 2;
						continue;
					}
					if (source.startsWith(TEXT_BLOCK, index)) {
						break;
					}
					index++;
				}
				literals.add(source.substring(start, Math.min(index, length)));
				index = Math.min(index + TEXT_BLOCK.length(), length);
				code.append(' ');
			}
			else if (character == '"' || character == '\'') {
				index++;
				int start = index;
				while (index < length) {
					char inside = source.charAt(index);
					if (inside == '\\') {
						index += 2;
						continue;
					}
					if (inside == character || inside == '\n') {
						break;
					}
					index++;
				}
				literals.add(source.substring(start, Math.min(index, length)));
				index = Math.min(index + 1, length);
				code.append(' ');
			}
			else {
				code.append(character);
				index++;
			}
		}
		return new Scan(code.toString(), List.copyOf(literals));
	}

	/** Hver produktionskildefil. Fejler højlydt, hvis roden ikke findes. */
	static List<Path> productionSources() {
		if (!Files.isDirectory(SOURCE_ROOT)) {
			throw new AssertionError(
					"The source root " + SOURCE_ROOT + " does not exist — the scan cannot prove anything about it");
		}
		try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
			return files.filter(Files::isRegularFile)
					.filter(file -> file.getFileName().toString().endsWith(".java"))
					.sorted()
					.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	static String readSource(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	static boolean isTheFormatter(Path file) {
		return SOURCE_ROOT.relativize(file).equals(FORMATTER_SOURCE);
	}

	/** Hvert fund som "sti: tekst", så en fejlrapport peger på linjen uden en debugger. */
	static void collectMatches(Path file, String code, Pattern pattern, List<String> offences) {
		Matcher matcher = pattern.matcher(code);
		while (matcher.find()) {
			offences.add(file + ": " + matcher.group());
		}
	}

}

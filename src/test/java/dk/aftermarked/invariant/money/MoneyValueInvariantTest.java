package dk.aftermarked.invariant.money;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.stream.Collectors;

import dk.aftermarked.money.CurrencyMismatchException;
import dk.aftermarked.money.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INVARIANT — beløbstypen selv (CLAUDE.md, PLAN §2.5). Denne test er spec.
 *
 * <p>Reglen er én sætning: <strong>et beløb er et heltal i mindste enhed OG en
 * ISO-4217-kode, og de to kan ikke skilles ad.</strong> Alt herunder er den
 * sætning målt fra hver sin side — værdisiden i de seks første tests, og
 * API-siden i de to sidste.
 *
 * <p><strong>Hvorfor to refleksionstests.</strong> De seks første tests beviser,
 * at typen opfører sig rigtigt <em>i dag</em>. De beviser ikke, at den bliver
 * ved. Et {@code Money.ZERO} eller et {@code implements Comparable} tilføjet af
 * en, der synes typen mangler noget, ville lade hver eneste af de andre tests
 * stå grøn — og samtidig genåbne præcis de to huller, typen findes for at
 * lukke: et nul uden valuta, og en sortering af en blandet liste, der fejler
 * halvvejs igennem og efterlader listen delvist sorteret. Refleksionen læser
 * derfor det offentlige API som data og stiller krav til FORMEN, ikke til en
 * enkelt værdi.
 *
 * <p>Ingen Spring og ingen database: typen afhænger af ingenting
 * ({@code allowedDependencies = {}}), og en test af den skal kunne køre lige så
 * nøgent.
 */
class MoneyValueInvariantTest {

	static final Currency DKK = Currency.getInstance("DKK");

	static final Currency EUR = Currency.getInstance("EUR");

	/**
	 * Koder, som JDK'et ACCEPTERER, men som ikke kan bære et beløb: XXX er
	 * ISO-4217's egen "ingen valuta", XTS er reserveret til test. Begge har -1
	 * decimaler — der findes ingen mindste enhed at tælle i — og begge slipper
	 * gennem {@link Currency#getInstance(String)} uden en lyd. Det er dét, der
	 * gør dem farlige: en tom eller default-udfyldt valutakode ender her og ser
	 * gyldig ud hele vejen ned.
	 */
	static final List<String> CODES_WITHOUT_A_MINOR_UNIT = List.of("XXX", "XTS");

	// --- Vaerdisiden --------------------------------------------------------

	@Test
	void givenACurrencyWithoutAMinorUnit_whenBuildingAnAmount_thenItIsRejected() {
		for (String code : CODES_WITHOUT_A_MINOR_UNIT) {
			Currency currency = Currency.getInstance(code);

			// Forudsaetningen, resten af blokken hviler paa: koden ER gyldig for
			// JDK'et. Holder den ikke, tester blokken noget andet, end den tror.
			assertThat(currency.getDefaultFractionDigits())
					.as("[%s] must be a currency the JDK accepts but that has no minor unit", code)
					.isNegative();

			assertThatThrownBy(() -> Money.of(1L, currency))
					.as("an amount in [%s] has no smallest unit to count in", code)
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining(code);
			assertThatThrownBy(() -> Money.of(1L, code))
					.as("the string factory must close the same door as the typed one")
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining(code);
			assertThatThrownBy(() -> Money.zero(currency))
					.as("a zero in [%s] is a zero without a currency by another name", code)
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	/**
	 * Koden skal stå i beskeden. JDK'ets egen fejl for en ukendt kode nævner
	 * enten en ISO-3166-LANDEKODE eller slet ingen kode, og en fejl, der ikke
	 * navngiver den værdi, den handler om, koster en debugger at læse.
	 */
	@Test
	void givenAnUnknownCurrencyCode_whenBuildingAnAmount_thenItIsRejectedNamingTheCode() {
		assertThatThrownBy(() -> Money.of(33000L, "ZZZ"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("ZZZ");
		assertThatThrownBy(() -> Money.currencyOf("BITCOIN"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("BITCOIN");
	}

	/**
	 * Stripe sender valutakoder med små bogstaver. Gik den rå streng til
	 * {@link Currency#getInstance(String)}, ville JDK'et kaste med en besked om
	 * ISO-3166-landekoder — en fejl, der sender den næste læser et helt forkert
	 * sted hen.
	 *
	 * <p>Den sidste linje er den, der betyder noget: at koden STÅR med store
	 * bogstaver kunne være kosmetik, men at beløbet kan lægges sammen med et
	 * beløb bygget fra selve {@link Currency}-objektet beviser, at det er den
	 * SAMME valuta og ikke bare den samme tekst.
	 */
	@Test
	void givenALowercaseCurrencyCodeAsStripeSendsIt_whenBuildingAnAmount_thenItIsNormalisedToUpperCase() {
		Money fromStripe = Money.of(33000L, "dkk");

		assertThat(fromStripe.currencyCode()).isEqualTo("DKK");
		assertThat(fromStripe.currency()).isEqualTo(DKK);
		assertThat(fromStripe).isEqualTo(Money.of(33000L, DKK));
		assertThat(fromStripe.plus(Money.of(1L, DKK))).isEqualTo(Money.of(33001L, DKK));
	}

	@Test
	void givenTwoAmountsInDifferentCurrencies_whenAddingThem_thenItThrowsCurrencyMismatch() {
		Money kroner = Money.of(33000L, DKK);
		Money euro = Money.of(4400L, EUR);

		assertThatThrownBy(() -> kroner.plus(euro))
				.isInstanceOf(CurrencyMismatchException.class)
				.hasMessageContaining("DKK")
				.hasMessageContaining("EUR");
		assertThatThrownBy(() -> kroner.minus(euro)).isInstanceOf(CurrencyMismatchException.class);

		// Egen exception-type og ikke bare en IllegalArgumentException: sagaen og
		// den daglige afstemning skal kunne SKELNE to valutaer, der moedes inde i
		// vores egen aritmetik, fra daarligt input udefra. Derfor skal begge koder
		// ogsaa kunne laeses STRUKTURERET og ikke kun ud af beskeden.
		assertThatThrownBy(() -> kroner.plus(euro))
				.isInstanceOfSatisfying(CurrencyMismatchException.class, mismatch -> {
					assertThat(mismatch.expected()).isEqualTo(DKK);
					assertThat(mismatch.actual()).isEqualTo(EUR);
				});
	}

	/**
	 * Sammenligning er lige så farlig som addition. Et {@code isGreaterThan},
	 * der svarede "ja" på tværs af valutaer, ville gøre en priscap-kontrol til
	 * en tilfældighed afhængig af kursen den dag.
	 */
	@Test
	void givenTwoAmountsInDifferentCurrencies_whenComparingThem_thenItThrowsCurrencyMismatch() {
		Money kroner = Money.of(33000L, DKK);
		Money euro = Money.of(4400L, EUR);

		assertThatThrownBy(() -> kroner.isGreaterThan(euro)).isInstanceOf(CurrencyMismatchException.class);
		assertThatThrownBy(() -> kroner.isLessThan(euro)).isInstanceOf(CurrencyMismatchException.class);
		assertThatThrownBy(() -> kroner.isAtLeast(euro)).isInstanceOf(CurrencyMismatchException.class);
	}

	/**
	 * Et overløb på en {@code long} ombrydes i stilhed til et NEGATIVT tal, og
	 * et negativt beløb i en udbetalingsplan er penge, der bevæger sig den
	 * forkerte vej. Den sidste assertion er ikke pynt: den viser præcis det
	 * udfald, {@code Math.addExact} bytter væk, så ingen senere "forenkler"
	 * tilbage til et almindeligt plus.
	 */
	@Test
	void givenAnAmountAtTheLongBoundary_whenAddingToIt_thenItOverflowsLoudlyRatherThanWrappingAround() {
		Money ceiling = Money.of(Long.MAX_VALUE, DKK);
		Money floor = Money.of(Long.MIN_VALUE, DKK);
		Money smallestUnit = Money.of(1L, DKK);

		assertThatThrownBy(() -> ceiling.plus(smallestUnit)).isInstanceOf(ArithmeticException.class);
		assertThatThrownBy(() -> floor.minus(smallestUnit)).isInstanceOf(ArithmeticException.class);
		assertThatThrownBy(floor::negated).isInstanceOf(ArithmeticException.class);

		assertThat(Long.MAX_VALUE + 1L)
				.as("this is what a plain plus would have returned instead of throwing")
				.isNegative();
	}

	// --- API-siden: formen, ikke vaerdien ------------------------------------

	/**
	 * Hver offentlig vej til et {@link Money} skal bære en valuta med sig. Et
	 * {@code Money.of(long)} ville gøre "beløb uden valuta" kompilérbart igen,
	 * og så er resten af nettet i dette projekt uden betydning: typen kan ikke
	 * beskytte en tilstand, den selv kan konstrueres i.
	 *
	 * <p>Reglen er skrevet som en EGENSKAB og ikke som en fast liste af
	 * signaturer. En fast liste ville skulle redigeres, hver gang en lovlig
	 * fabrik kom til (S4/S6) — og en invariant-test, der skal redigeres, er en
	 * invariant-test, der bliver redigeret forkert en dag.
	 */
	@Test
	void givenTheMoneyType_whenListingItsPublicFactories_thenNoneProducesAnAmountWithoutACurrency() {
		List<Executable> producers = publicProducersOfAnAmount();

		assertThat(producers)
				.as("the rule must actually see the factories — otherwise it is green for the wrong reason")
				.hasSizeGreaterThanOrEqualTo(3);

		for (Executable producer : producers) {
			assertThat(carriesACurrency(producer))
					.as("%s produces an amount and must therefore take its currency with it", signatureOf(producer))
					.isTrue();
		}
	}

	/**
	 * De to ting, der ER besluttet fravalgt, og som skal blive ved med at være
	 * det (se {@link Money}'s javadoc):
	 *
	 * <ol>
	 *   <li>{@code Comparable} — {@code compareTo} ville skulle kaste på tværs
	 *       af valutaer og dermed bryde sin egen kontrakt. {@code list.sort()}
	 *       og {@code TreeMap} ville fejle MIDT i arbejdet og efterlade en
	 *       delvist sorteret liste. At sortere en blandet liste ER fejlen.</li>
	 *   <li>Et nul uden valuta — {@code Money.ZERO} er præcis det hul, typen
	 *       findes for at lukke, og et FELT er ikke dækket af fabriksreglen
	 *       ovenfor.</li>
	 * </ol>
	 *
	 * <p>Både grænsefladen og metoden tjekkes: en {@code compareTo} skrevet
	 * uden at implementere {@code Comparable} er den samme mine med etiketten
	 * pillet af — den bliver fundet af den første komparator-refaktorering, der
	 * kommer forbi.
	 */
	@Test
	void givenTheMoneyType_whenReadingItsPublicApi_thenItIsNotComparableAndHasNoCurrencylessZero() {
		assertThat(Comparable.class.isAssignableFrom(Money.class))
				.as("sorting a mixed list is the failure, not a detail — use isGreaterThan/isLessThan/isAtLeast")
				.isFalse();
		assertThat(Arrays.stream(Money.class.getInterfaces()).map(Class::getName).toList())
				.doesNotContain(Comparable.class.getName());
		assertThat(Arrays.stream(Money.class.getDeclaredMethods()).map(Method::getName).toList())
				.as("a compareTo without the interface is the same mine with the label filed off")
				.doesNotContain("compareTo");

		List<String> currencylessConstants = Arrays.stream(Money.class.getDeclaredFields())
				.filter(field -> Modifier.isPublic(field.getModifiers()))
				.filter(field -> Modifier.isStatic(field.getModifiers()))
				.filter(field -> field.getType().equals(Money.class))
				.map(Field::getName)
				.toList();

		assertThat(currencylessConstants)
				.as("a constant amount is an amount whose currency was decided once, for everyone")
				.isEmpty();

		List<String> parameterlessFactories = Arrays.stream(Money.class.getDeclaredMethods())
				.filter(method -> Modifier.isPublic(method.getModifiers()))
				.filter(method -> Modifier.isStatic(method.getModifiers()))
				.filter(method -> method.getReturnType().equals(Money.class))
				.filter(method -> method.getParameterCount() == 0)
				.map(Method::getName)
				.toList();

		assertThat(parameterlessFactories)
				.as("a factory that takes nothing cannot have been told a currency")
				.isEmpty();
	}

	// -----------------------------------------------------------------------

	/** Hver offentlig konstruktoer og hver offentlig statisk fabrik, der giver et {@link Money}. */
	static List<Executable> publicProducersOfAnAmount() {
		List<Executable> producers = new ArrayList<>();
		Arrays.stream(Money.class.getDeclaredConstructors())
				.filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
				.forEach(producers::add);
		Arrays.stream(Money.class.getDeclaredMethods())
				.filter(method -> Modifier.isPublic(method.getModifiers()))
				.filter(method -> Modifier.isStatic(method.getModifiers()))
				.filter(method -> method.getReturnType().equals(Money.class))
				.forEach(producers::add);
		return producers;
	}

	/**
	 * En {@link Currency} er den typede vej; en {@link String} er wire-formen,
	 * som {@code Money.currencyOf} normaliserer og validerer. Alt andet er et
	 * beløb, hvis valuta nogen bestemmer et andet sted.
	 */
	static boolean carriesACurrency(Executable producer) {
		return Arrays.stream(producer.getParameterTypes())
				.anyMatch(type -> type.equals(Currency.class) || type.equals(String.class));
	}

	static String signatureOf(Executable producer) {
		return Arrays.stream(producer.getParameterTypes())
				.map(Class::getSimpleName)
				.collect(Collectors.joining(", ", producer.getName() + "(", ")"));
	}

}

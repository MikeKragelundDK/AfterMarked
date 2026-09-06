package dk.aftermarked.money;

import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

/**
 * Et beløb: et heltal i valutaens mindste enhed plus valutaen selv (CLAUDE.md,
 * PLAN §2.5). 330,00 kr. er {@code Money.of(33000, "DKK")} — aldrig 330.00.
 *
 * <p><strong>Hvorfor heltal.</strong> Et flydende tal kan ikke repræsentere 0,10
 * eksakt, og en split, der lægger tre afrundede andele sammen, rammer derfor
 * ikke det, køberen betalte. Heltal i mindste enhed har ingen afrundingsfejl at
 * akkumulere. {@code long} og ikke {@code int}: JPY har nul decimaler, så 2,1
 * mia. minimale enheder er kun 21 mio. kr.
 *
 * <p><strong>Hvorfor valutaen altid er med.</strong> Et beløb uden valuta er
 * ikke et beløb — det er et tal, nogen senere gætter en valuta til. Typen kan
 * derfor ikke konstrueres uden en, og hver binær operation kaster
 * {@link CurrencyMismatchException}, hvis de to ikke er enige. Der findes ingen
 * omregning i systemet: ingen transaktion krydser events, og ét event har én
 * valuta.
 *
 * <p><strong>Bevidst fraværende i V1.</strong> Skrevet ned, fordi hver enkelt
 * ellers bliver tilføjet igen af en, der synes typen mangler noget:
 * <ul>
 *   <li>{@code implements Comparable} — {@code compareTo} ville skulle kaste på
 *       tværs af valutaer og dermed bryde sin egen kontrakt: {@code list.sort()}
 *       og {@code TreeMap} ville fejle halvvejs igennem med en delvist sorteret
 *       liste. At sortere en blandet liste ER fejlen, ikke en detalje. Brug
 *       {@link #isGreaterThan}, {@link #isLessThan} og {@link #isAtLeast}, som
 *       må kaste.</li>
 *   <li>{@code Money.ZERO} — et nul uden valuta er præcis det hul, typen findes
 *       for at lukke. {@link #zero(Currency)} findes i stedet.</li>
 *   <li>{@code Money.of(long)} — ville gøre "beløb uden valuta" kompilérbart
 *       igen.</li>
 *   <li>Multiplikation med et brøktal og procentregning — en procent uden en
 *       eksplicit afrundingsregel er afrundingskronen bestilt på forhånd. Det
 *       kommer som {@code percent(int basisPoints, RoundingMode)} i S4/S6
 *       sammen med sin invariant-test.</li>
 *   <li>{@code allocate(int...)} — splittet kræver largest-remainder, så summen
 *       af andelene er præcis dét, køberen betalte. Hører i S6 ved siden af den
 *       test, der beviser det.</li>
 *   <li>{@code sum(Collection)} uden en valuta — den ville skulle seedes med et
 *       valutaløst nul og ville dermed adoptere den første valuta, der dukkede
 *       op i listen: DKK og EUR lagt sammen, med en grøn test.</li>
 *   <li>Konvertering, {@code parse("330.00")} og konstruktion fra et flydende
 *       tal — omregning findes ikke, og et brøktal i penge findes ikke.</li>
 * </ul>
 */
public record Money(long minorUnits, Currency currency) {

	public Money {
		// Jackson binder en MANGLENDE "currency" i JSON til null uden at fejle
		// (verificeret på 3.1.5). Uden denne linje når et beløb uden valuta hele
		// vejen til databasen.
		Objects.requireNonNull(currency, "currency must not be null");
		// XXX ("ingen valuta") og XTS ("reserveret til test") ER gyldige
		// ISO-4217-koder og slipper gennem Currency.getInstance. De har -1
		// decimaler og kan derfor ikke bære et beløb: der findes ingen mindste
		// enhed at tælle i. Reglen selv bor i hasMinorUnit, så Event og
		// katalog-parseren afviser præcis de samme koder som Money.
		if (!hasMinorUnit(currency)) {
			throw new IllegalArgumentException(
					"Currency " + currency.getCurrencyCode() + " has no minor unit and cannot carry an amount");
		}
	}

	/**
	 * Om valutaen har en mindste enhed at tælle i.
	 *
	 * <p>Den ENE definition af "kan bære et beløb". {@code Money} bruger den i sin
	 * konstruktør, {@link #currencyOf} bruger den på vejen fra en streng, og
	 * {@code Event} bruger den på sin egen valuta: et event i XXX kunne ellers
	 * skrives, men dets billettyper kunne aldrig læses tilbage, og en
	 * {@code VerifiedFaceValue} kunne aldrig laves til det. Databasen kan ikke
	 * håndhæve reglen — SQL kender ikke ISO-4217's decimaler — så den bor her.
	 */
	public static boolean hasMinorUnit(Currency currency) {
		Objects.requireNonNull(currency, "currency must not be null");
		return currency.getDefaultFractionDigits() >= 0;
	}

	public static Money of(long minorUnits, Currency currency) {
		return new Money(minorUnits, currency);
	}

	/** Som {@link #of(long, Currency)}, men fra en kode på wire-form — se {@link #currencyOf}. */
	public static Money of(long minorUnits, String isoCurrencyCode) {
		return new Money(minorUnits, currencyOf(isoCurrencyCode));
	}

	/**
	 * Nul i ÉN valuta — sælgergebyret ved en hand-off, en tom sum, der stadig
	 * ved hvilken valuta den er tom i. Der findes ikke et nul uden valuta.
	 */
	public static Money zero(Currency currency) {
		return new Money(0L, currency);
	}

	/**
	 * Den ENE vej fra en streng til en valuta.
	 *
	 * <p>Koden store-bogstaveres <em>før</em> {@link Currency#getInstance}, fordi
	 * Stripe sender små bogstaver ({@code "dkk"}), og JDK'et læser en kode med
	 * små bogstaver som en ISO-3166-landekode: {@code getInstance("dkk")} kaster
	 * med beskeden <em>"The country code: \"dk\" is not a valid ISO 3166
	 * code"</em>, som sender den næste læser et helt forkert sted hen.
	 *
	 * <p>{@link Locale#ROOT} og ikke standard-localen: i tyrkisk lokalitet giver
	 * {@code "i".toUpperCase()} et prikløst I, og {@code "TRY"} og {@code "ILS"}
	 * ville blive ukendte koder på en maskine med tyrkisk sprogindstilling.
	 *
	 * <p>Fejlen ombrydes i vores egen besked, så den altid navngiver koden — vi
	 * ejer ikke JDK'ets ordlyd, og den har skiftet før.
	 *
	 * <p>En kode uden mindste enhed (XXX, XTS) afvises HER og ikke først i
	 * {@code Money}s konstruktør: det er denne vej, katalog-parseren bruger til et
	 * EVENTS valuta, og et event i XXX bærer aldrig et beløb, men det kan heller
	 * aldrig få en billettype læst tilbage. Se {@link #hasMinorUnit}.
	 */
	public static Currency currencyOf(String isoCurrencyCode) {
		Objects.requireNonNull(isoCurrencyCode, "isoCurrencyCode must not be null");
		String normalised = isoCurrencyCode.strip().toUpperCase(Locale.ROOT);
		Currency currency;
		try {
			currency = Currency.getInstance(normalised);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("Unknown ISO 4217 currency code [" + normalised + "]", ex);
		}
		if (!hasMinorUnit(currency)) {
			throw new IllegalArgumentException(
					"Currency " + normalised + " has no minor unit and cannot carry an amount");
		}
		return currency;
	}

	/** Valutakoden som den lagres og sendes: altid tre STORE bogstaver. */
	public String currencyCode() {
		return currency.getCurrencyCode();
	}

	/**
	 * Summen af to beløb i samme valuta.
	 *
	 * <p>{@link Math#addExact} og ikke {@code +}: et overløb på en {@code long}
	 * ombrydes i stilhed til et negativt tal, og et negativt beløb i en
	 * udbetalingsplan er penge, der bevæger sig den forkerte vej. Højlydt fejl er
	 * det eneste acceptable udfald.
	 */
	public Money plus(Money other) {
		requireSameCurrency(other);
		return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
	}

	public Money minus(Money other) {
		requireSameCurrency(other);
		return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
	}

	/** Beløbet med modsat fortegn — en refusion set fra udbetalingens side. */
	public Money negated() {
		return new Money(Math.negateExact(minorUnits), currency);
	}

	public boolean isZero() {
		return minorUnits == 0L;
	}

	public boolean isNegative() {
		return minorUnits < 0L;
	}

	public boolean isPositive() {
		return minorUnits > 0L;
	}

	public boolean isGreaterThan(Money other) {
		requireSameCurrency(other);
		return minorUnits > other.minorUnits;
	}

	public boolean isLessThan(Money other) {
		requireSameCurrency(other);
		return minorUnits < other.minorUnits;
	}

	public boolean isAtLeast(Money other) {
		requireSameCurrency(other);
		return minorUnits >= other.minorUnits;
	}

	/**
	 * Udviklervendt: logs og exception-beskeder. {@code "33000 DKK"} — den rå
	 * mindste enhed, så en log kan holdes op mod en kolonne uden mellemregning.
	 *
	 * <p>Aldrig lokaliseret og aldrig vist til en bruger. Det, brugeren ser,
	 * kommer fra {@link MoneyFormatter}.
	 */
	@Override
	public String toString() {
		return minorUnits + " " + currency.getCurrencyCode();
	}

	private void requireSameCurrency(Money other) {
		Objects.requireNonNull(other, "other must not be null");
		if (!currency.equals(other.currency)) {
			throw new CurrencyMismatchException(currency, other.currency);
		}
	}

}

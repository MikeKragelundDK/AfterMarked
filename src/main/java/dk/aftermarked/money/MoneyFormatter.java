package dk.aftermarked.money;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * Et beløb, som en bruger ser det. Tallet er data, ikke tekst: symbolet,
 * tusindtalsseparatoren og rækkefølgen kommer fra JDK'ets CLDR-data for
 * brugerens sprog — aldrig fra en streng i vores egen kode, og aldrig fra en
 * nøgle i {@code messages*.properties}. Der er intet at oversætte her.
 *
 * <p><strong>Den ENESTE klasse i produktionskoden, der må røre
 * {@link BigDecimal}</strong> — og kun til at TRYKKE et beløb, aldrig til at
 * regne på et. {@code MoneyArithmeticInvariantTest} navngiver klassen
 * eksplicit som sin ene undtagelse, og den undtagelse må aldrig udvides: så
 * snart en anden klasse må regne i decimaler, er hele pointen med heltal i
 * mindste enhed væk.
 *
 * <p>En {@link NumberFormat} er ikke trådsikker, så den bygges pr. kald og
 * deles aldrig i et felt. Det koster ingenting ved den trafik, PLAN §5 måler,
 * og alternativet er en formatter, to requests kan stå og pille i samtidig.
 */
@Component
public class MoneyFormatter {

	/**
	 * Beløbet formateret til {@code locale}, fx {@code "330,00 kr."} på dansk.
	 *
	 * <p>Bemærk til den, der skriver en assertion på resultatet: dansk skiller
	 * tal og symbol med et hårdt mellemrum (U+00A0), ikke et almindeligt. Skrives
	 * det som et almindeligt mellemrum, fejler testen af en grund, ingen kan se i
	 * en diff.
	 */
	public String format(Money money, Locale locale) {
		Objects.requireNonNull(money, "money");
		Objects.requireNonNull(locale, "locale");
		NumberFormat format = NumberFormat.getCurrencyInstance(locale);
		// Uden denne linje bruger formatteren LOCALENS egen valuta.
		// SUPPORTED_LOCALES er sprog uden land (Locale.of("da")), og for dem er
		// localens valuta XXX -- verificeret. Så "330,00 kr." ville blive
		// "330,00 XXX", og en dansk kunde ville se en pris uden en valuta.
		format.setCurrency(money.currency());
		int digits = money.currency().getDefaultFractionDigits();
		// Både minimum og maksimum, og begge fra valutaen selv: JPY har nul
		// decimaler, og en formatter, der arver localens antal, ville vise
		// "¥1.234,00" for et beløb, der ikke har en øre-del at vise.
		format.setMinimumFractionDigits(digits);
		format.setMaximumFractionDigits(digits);
		// BigDecimal.valueOf(long, scale) er EKSAKT: den flytter kommaet i et
		// heltal og laver hverken en division eller et flydende mellemled. 33000
		// med skala 2 ER 330.00 -- ikke noget der ligner.
		return format.format(BigDecimal.valueOf(money.minorUnits(), digits));
	}

}

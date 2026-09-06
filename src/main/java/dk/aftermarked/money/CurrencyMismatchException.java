package dk.aftermarked.money;

import java.io.Serial;
import java.util.Currency;
import java.util.Objects;

/**
 * To beløb i forskellige valutaer blev forsøgt lagt sammen, trukket fra hinanden
 * eller sammenlignet.
 *
 * <p>Egen type og ikke bare en {@link IllegalArgumentException}, fordi
 * købs-sagaen og den daglige Stripe-afstemning skal kunne SKELNE den fra dårligt
 * input. Et forkert formateret beløb fra en kunde er en 4xx, som kalderen selv
 * kan rette. To valutaer, der mødes inde i vores egen aritmetik, er derimod en
 * programfejl eller korrupte data: det skal eskalere til et menneske, ikke
 * retry'es og ikke logges og fortsættes (CLAUDE.md — slugte fejl i pengekode er
 * tabte penge).
 *
 * <p>Arven fra {@link IllegalArgumentException} er bevidst: en kalder, der ikke
 * kender typen, behandler den stadig som det programmeringsfejl, den er, i
 * stedet for at lade den passere som en almindelig {@code RuntimeException}.
 *
 * <p>Beskeden er engelsk og udviklervendt (CLAUDE.md). Den navngiver begge
 * koder, fordi den ene af dem er den forkerte, og ingen kan se hvilken uden dem
 * begge. Beløbene selv står IKKE i beskeden — den havner i loggen.
 */
public class CurrencyMismatchException extends IllegalArgumentException {

	@Serial
	private static final long serialVersionUID = 1L;

	private final Currency expected;

	private final Currency actual;

	public CurrencyMismatchException(Currency expected, Currency actual) {
		super("Currencies do not match: expected " + Objects.requireNonNull(expected, "expected").getCurrencyCode()
				+ " but was " + Objects.requireNonNull(actual, "actual").getCurrencyCode());
		this.expected = expected;
		this.actual = actual;
	}

	/** Valutaen på det beløb, operationen blev kaldt PÅ — den, der satte tonen. */
	public Currency expected() {
		return expected;
	}

	/** Valutaen på beløbet, der blev givet MED — den, der ikke passede. */
	public Currency actual() {
		return actual;
	}

}

package dk.aftermarked.catalog;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Kundens egen stabile event-reference (PLAN §2.2). Den er nøglen i kundens
 * system, står ordret i URL-stien {@code /e/{eventRef}}, og er sammen med
 * operatøren det, upserten støder på: {@code UNIQUE (operator_id, ref)}.
 *
 * <p>Egen type af samme grund som {@link OrganizerRef}: to strenge af samme form
 * kan byttes om uden at compileren siger noget.
 *
 * <p>Formatet er ordret V5's {@code event_ref_format}. Bemærk forskellen til
 * {@link OrganizerRef}: der findes INGEN reserveret event-ref. Et skelet-event
 * fra lazy enrichment bruger kundens egen ref fra verify-svaret — det er hele
 * pointen med, at et senere katalog-push kan genkende og udfylde netop den
 * række.
 *
 * <p>Ref'en er case-sensitiv, ordret som kunden skrev den. {@code /e/ACME} og
 * {@code /e/acme} er dermed to events. Det er kundens nøglerum, ikke vores, og
 * en normalisering her ville betyde, at vi kunne afvise et katalog, kundens eget
 * system finder fuldt gyldigt.
 */
public record EventRef(String value) {

	/** Ordret den samme regex som V5's {@code event_ref_format}. */
	private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._~-]{0,63}$");

	public EventRef {
		Objects.requireNonNull(value, "value");
		if (!isValid(value)) {
			throw new IllegalArgumentException(
					"Event ref does not match the required format ^[A-Za-z0-9][A-Za-z0-9._~-]{0,63}$");
		}
	}

	/**
	 * Om en rå streng kan blive til en {@code EventRef} — uden at kaste, så
	 * parseren kan samle ALLE overtrædelser i payloaden og svare kunden på én
	 * gang i stedet for at stoppe ved den første.
	 *
	 * <p>Værdien står med vilje ikke i exception-beskeden, denne metode findes
	 * for: en ref fra wire kan være vilkårligt lang og vilkårligt indhold, og den
	 * hører hjemme bag en JSON-Pointer i 422-svaret — ikke i en log.
	 */
	public static boolean isValid(String candidate) {
		return candidate != null && FORMAT.matcher(candidate).matches();
	}

}

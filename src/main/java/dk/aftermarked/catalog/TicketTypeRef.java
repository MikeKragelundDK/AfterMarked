package dk.aftermarked.catalog;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Kundens egen stabile billettype-reference (PLAN §2.2).
 *
 * <p>Egen type af samme grund som {@link OrganizerRef} og {@link EventRef}: en
 * {@code upsert(eventRef, ticketTypeRef)} kaldt med byttede argumenter er en
 * fejl, compileren skal fange — ikke en, produktionen skal opdage.
 *
 * <p>Formatet er ordret V5's {@code ticket_type_ref_format}. Til forskel fra de
 * to andre er billettype-ref'en kun unik INDEN I sit event
 * ({@code UNIQUE (operator_id, event_id, ref)}): to events hos samme kunde må
 * begge have en billettype, der hedder {@code standard}. Typen bærer derfor
 * ingen viden om, hvilket event den hører til — den binding sker i
 * {@link TicketType#eventId()}.
 */
public record TicketTypeRef(String value) {

	/** Ordret den samme regex som V5's {@code ticket_type_ref_format}. */
	private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._~-]{0,63}$");

	public TicketTypeRef {
		Objects.requireNonNull(value, "value");
		if (!isValid(value)) {
			throw new IllegalArgumentException(
					"Ticket type ref does not match the required format ^[A-Za-z0-9][A-Za-z0-9._~-]{0,63}$");
		}
	}

	/**
	 * Om en rå streng kan blive til en {@code TicketTypeRef} — uden at kaste, så
	 * parseren kan samle alle overtrædelser og svare kunden på én gang.
	 */
	public static boolean isValid(String candidate) {
		return candidate != null && FORMAT.matcher(candidate).matches();
	}

}

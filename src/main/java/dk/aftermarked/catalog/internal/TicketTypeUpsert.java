package dk.aftermarked.catalog.internal;

import java.util.Objects;

import dk.aftermarked.catalog.TicketTypeRef;
import dk.aftermarked.catalog.fields.CustomFieldScope;
import dk.aftermarked.catalog.fields.CustomFieldValues;
import dk.aftermarked.money.Money;

/**
 * Én billettype, som kunden sendte den — parset, valideret og typet.
 *
 * <p>Formen er den KOMPLETTE entitet (spec-beslutning #20): <em>"Et delta bærer
 * færre ENTITETER. Det bærer aldrig færre FELTER."</em> Der findes derfor ingen
 * "sæt kun det her"-tilstand og intet full/delta-flag; hvert felt, entiteten
 * har, står i payloaden, og upserten skriver dem alle.
 *
 * <p>{@code faceValue} er PÅKRÆVET og et objekt på wire
 * ({@code {"amount": 30000, "currency": "DKK"}}) — den eneste form, hvor et
 * beløb uden valuta ikke kan skrives ned (spec-beslutning #7). Valutaen skal
 * være eventets: ét event = én valuta, og V5's tre-kolonners fremmednøgle
 * håndhæver det i databasen.
 *
 * <p>Typen ligger i {@code internal}, fordi den bærer feltværdier
 * (spec-beslutning #11): lå den i den offentlige {@code catalog}-pakke, kunne
 * ethvert modul med {@code allowedDependencies = {"catalog"}} nå feltlageret
 * gennem den unavngivne grænseflade, uden at ArchUnit-reglen fyrede.
 *
 * @param ref kundens egen ref, unik INDEN I eventet
 * @param name navnet, med sine eventuelle oversættelser
 * @param faceValue pålydende. Heltal i mindste enhed plus ISO-4217 (CLAUDE.md)
 * @param customFields de validerede custom felter. Aldrig {@code null} —
 *     {@code CustomFieldValues.empty(TICKET_TYPE)}, når kunden ingen sendte
 */
public record TicketTypeUpsert(TicketTypeRef ref, TranslatableText name, Money faceValue,
		CustomFieldValues customFields) {

	public TicketTypeUpsert {
		Objects.requireNonNull(ref, "ref");
		Objects.requireNonNull(name, "name");
		// Et beløb, der er væk, må ikke blive til nul: face_value_minor er NOT NULL,
		// og en manglende pris er en fejl i payloaden, ikke en gratis billet.
		Objects.requireNonNull(faceValue, "faceValue");
		Objects.requireNonNull(customFields, "customFields");
		// Samme regel som V5's ticket_type_face_value_sane. Uden den bliver et
		// negativt beløb til en 23514 midt i et natligt push i stedet for en
		// læsbar 422 — og beløbet navngives ALDRIG, det er kundens data.
		if (faceValue.isNegative()) {
			throw new IllegalArgumentException(
					"The face value of ticket type [" + ref.value() + "] must not be negative");
		}
		// Et dokument valideret mod EVENT-felterne må ikke kunne bæres af en
		// billettype. Kontrollen står her og i TicketTypeRow, fordi de to er
		// hinandens eneste døre ind til rækken.
		if (customFields.scope() != CustomFieldScope.TICKET_TYPE) {
			throw new IllegalArgumentException("The custom fields of ticket type [" + ref.value()
					+ "] were validated for scope " + customFields.scope() + ", not TICKET_TYPE");
		}
	}

}

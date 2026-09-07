package dk.aftermarked.catalog.internal;

import java.util.List;
import java.util.Objects;

import dk.aftermarked.catalog.OrganizerRef;
import dk.aftermarked.catalog.fields.CustomFieldScope;
import dk.aftermarked.catalog.fields.CustomFieldValues;

/**
 * Én arrangør, som kunden sendte den — parset, valideret og typet, med sine
 * events under sig.
 *
 * <p>{@code organizers[]} er den ENESTE liste på topniveau (spec-beslutning
 * #16). En kunde, der slet ikke skelner mellem arrangører, sender ÉN arrangør —
 * deres eget firma med deres egen ref. Det giver én kodesti og nul tvetydighed;
 * alternativet, {@code organizerRef: null}, ville give "null betyder default"
 * to stavemåder. {@code OrganizerRef.RESERVED_DEFAULT} er reserveret til den
 * arrangør, VI syntetiserer i lazy enrichment, og afvises fra wire med
 * {@link CatalogErrorCode#RESERVED_REF}.
 *
 * <p>Nesting er ADRESSERING, ikke indeholdelse (spec-beslutning #20): at
 * eventene står inde i arrangøren siger, hvem de hører til — ikke at en
 * arrangør uden events sletter noget. Der findes ingen sletning i V1.
 *
 * <p>Typen ligger i {@code internal}, fordi den bærer feltværdier
 * (spec-beslutning #11): lå den i den offentlige {@code catalog}-pakke, kunne
 * ethvert modul med {@code allowedDependencies = {"catalog"}} nå feltlageret
 * gennem den unavngivne grænseflade, uden at ArchUnit-reglen fyrede.
 *
 * @param ref kundens egen arrangør-ref. Aldrig {@code _default}
 * @param name navnet, med sine eventuelle oversættelser
 * @param customFields de validerede custom felter. Aldrig {@code null} —
 *     {@code CustomFieldValues.empty(ORGANIZER)}, når kunden ingen sendte
 * @param events arrangørens events. Må være tom
 */
public record OrganizerUpsert(OrganizerRef ref, TranslatableText name, CustomFieldValues customFields,
		List<EventUpsert> events) {

	public OrganizerUpsert {
		Objects.requireNonNull(ref, "ref");
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(customFields, "customFields");
		Objects.requireNonNull(events, "events");
		events = List.copyOf(events);
		// Reservationen af OrganizerRef.RESERVED_DEFAULT håndhæves bevidst IKKE
		// her. Den hører to andre steder: parseren afviser den fra wire med
		// RESERVED_REF og en JSON-Pointer, som er dét kunden kan handle på, og
		// CatalogIngestService har sit eget net før skrivningen. En tredje
		// kontrol i typen ville kaste en anden exception-type end de to — og en
		// test af nettet ville så fejle på den forkerte fejl.
		// Et dokument valideret mod EVENT-felterne må ikke kunne bæres af en
		// arrangør. Kontrollen står her og i OrganizerRow, fordi de to er
		// hinandens eneste døre ind til rækken.
		if (customFields.scope() != CustomFieldScope.ORGANIZER) {
			throw new IllegalArgumentException("The custom fields of organizer [" + ref.value()
					+ "] were validated for scope " + customFields.scope() + ", not ORGANIZER");
		}
	}

}

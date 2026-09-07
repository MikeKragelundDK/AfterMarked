package dk.aftermarked.catalog.internal;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

import dk.aftermarked.catalog.EventRef;
import dk.aftermarked.catalog.SalesStatus;
import dk.aftermarked.catalog.fields.CustomFieldScope;
import dk.aftermarked.catalog.fields.CustomFieldValues;
import dk.aftermarked.money.Money;

/**
 * Ét event, som kunden sendte det — parset, valideret og typet, med sine
 * billettyper under sig.
 *
 * <p>Nesting er ADRESSERING, ikke indeholdelse (spec-beslutning #20). At
 * billettyperne står inde i eventet betyder, at de hører til netop dét event —
 * ikke at et push uden dem sletter noget. Der findes ingen sletning i V1
 * (spec-beslutning #19), så et event, der kommer med færre billettyper, lader
 * resten stå.
 *
 * <p>{@code salesStatus} er NOT NULL hele vejen ned og aldrig en
 * {@code Optional} (spec-beslutning #17): kolonnen og aggregatet skal kunne
 * udtrykke præcis det samme, ellers sletter en admin-redigering GENNEM
 * aggregatet kundens egen værdi i stilhed. En token, vi ikke kender, bliver
 * {@link SalesStatus#UNKNOWN} plus en {@link CatalogWarning}.
 *
 * <p>{@code primaryPurchaseUrl} er en KERNEKOLONNE og ikke et custom felt
 * (spec-beslutning #18): ventelisten afhænger af den, og PLAN §2.6 forbyder
 * custom felter at påvirke adfærd. Politikken — kun https — ligger i
 * {@link CatalogPayloadParser}, mens V5's CHECK bevidst er løsere: en CHECK kan
 * strammes senere, aldrig løsnes.
 *
 * <p>Typen ligger i {@code internal}, fordi den bærer feltværdier
 * (spec-beslutning #11).
 *
 * @param ref kundens egen event-ref. Unik pr. operatør
 * @param title titlen. NOT NULL i V5 — fallback-kædens trin 4 er grundfeltet
 * @param venue spillestedet, eller {@code null}
 * @param startsAt starttidspunkt. Påkrævet
 * @param endsAt sluttidspunkt, eller {@code null}. Aldrig før {@code startsAt}
 * @param salesStatus kundens primærsalg. Aldrig {@code null}
 * @param salesEndsAt hvornår kundens eget salg lukker, eller {@code null}
 * @param primaryPurchaseUrl kundens egen salgsside, eller {@code null}. Kun https
 * @param currency eventets valuta. Ét event = én valuta (PLAN §2.5)
 * @param customFields de validerede custom felter. Aldrig {@code null}
 * @param ticketTypes billettyperne på eventet. Må være tom
 */
public record EventUpsert(EventRef ref, TranslatableText title, TranslatableText venue, Instant startsAt,
		Instant endsAt, SalesStatus salesStatus, Instant salesEndsAt, String primaryPurchaseUrl,
		Currency currency, CustomFieldValues customFields, List<TicketTypeUpsert> ticketTypes) {

	public EventUpsert {
		Objects.requireNonNull(ref, "ref");
		Objects.requireNonNull(title, "title");
		Objects.requireNonNull(startsAt, "startsAt");
		Objects.requireNonNull(salesStatus, "salesStatus");
		Objects.requireNonNull(currency, "currency");
		Objects.requireNonNull(customFields, "customFields");
		Objects.requireNonNull(ticketTypes, "ticketTypes");
		ticketTypes = List.copyOf(ticketTypes);
		// Samme regel som Money og EventRow: en valuta uden mindste enhed (XXX,
		// XTS) kan aldrig bære et beløb, og et event i den kunne aldrig få en
		// billettype læst tilbage.
		if (!Money.hasMinorUnit(currency)) {
			throw new IllegalArgumentException("Event [" + ref.value() + "] is in currency "
					+ currency.getCurrencyCode() + ", which has no minor unit and can never carry a face value");
		}
		if (endsAt != null && endsAt.isBefore(startsAt)) {
			throw new IllegalArgumentException("Event [" + ref.value() + "] ends before it starts");
		}
		// Ét event = én valuta. V5 håndhæver det med den tre-kolonners
		// fremmednøgle (operator_id, id, currency), men dér ville uenigheden være
		// en 23503 midt i et natligt push. Her er den vores egen programfejl:
		// parseren har allerede afvist kundens uenighed med CURRENCY_MISMATCH.
		for (TicketTypeUpsert ticketType : ticketTypes) {
			if (!ticketType.faceValue().currency().equals(currency)) {
				throw new IllegalArgumentException("Ticket type [" + ticketType.ref().value() + "] on event ["
						+ ref.value() + "] is in another currency than the event itself");
			}
		}
		// Et dokument valideret mod ARRANGØR-felterne må ikke kunne bæres af et
		// event. Kontrollen står her og i EventRow, fordi de to er hinandens
		// eneste døre ind til rækken.
		if (customFields.scope() != CustomFieldScope.EVENT) {
			throw new IllegalArgumentException("The custom fields of event [" + ref.value()
					+ "] were validated for scope " + customFields.scope() + ", not EVENT");
		}
	}

}

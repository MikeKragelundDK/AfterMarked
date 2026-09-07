package dk.aftermarked.catalog.internal;

import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import dk.aftermarked.audit.AuditActor;
import dk.aftermarked.audit.AuditEntry;
import dk.aftermarked.audit.AuditLog;
import dk.aftermarked.catalog.CatalogException;
import dk.aftermarked.catalog.CatalogIngest;
import dk.aftermarked.catalog.CatalogValidationException;
import dk.aftermarked.catalog.CatalogValidationException.Violation;
import dk.aftermarked.catalog.Event;
import dk.aftermarked.catalog.EventRef;
import dk.aftermarked.catalog.OrganizerRef;
import dk.aftermarked.catalog.VerifiedTicket;
import dk.aftermarked.catalog.fields.CustomFieldValues;
import dk.aftermarked.localization.TranslationRow;
import dk.aftermarked.localization.Translations;
import dk.aftermarked.money.Money;
import dk.aftermarked.tenancy.Operator;
import dk.aftermarked.tenancy.OperatorContext;
import dk.aftermarked.tenancy.OperatorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Hele ingest-pipelinen: kundens natlige katalog-dump og lazy enrichment fra et
 * verify-svar. <strong>Rækkefølgen er bærende</strong> — hvert trin nedenfor
 * findes, fordi rækkefølgen uden det giver en stille fejl:
 *
 * <ol>
 *   <li>{@link OperatorContext#required()}. Tenanten kommer fra værtsnavnet ved
 *       request-indgangen eller fra jobrækkens egen {@code operator_id} — aldrig
 *       fra payloaden, som med vilje ikke har et felt, der kunne bære en.</li>
 *   <li>{@link CatalogWriteGuard#serialiseWritesForCurrentOperator()}, som den
 *       første sætning i transaktionen. Låsen er {@code xact}-scopet, så den
 *       holder præcis så længe transaktionen gør.</li>
 *   <li>PARSE og VALIDÉR hele payloaden i ren Java, før én række røres. Alle
 *       overtrædelser samles og rapporteres på én gang, så kunden retter i én
 *       runde — et delvist skrevet katalog er værre end et afvist, og det gør
 *       idempotens-garantien uudsigelig.</li>
 *   <li>Arrangører → <strong>læs id'erne tilbage</strong> → events (med
 *       {@link CatalogWriteGuard#requireSingleCurrency(Operator, java.util.Collection)}
 *       FØR skrivningen) → læs tilbage → billettyper.</li>
 *   <li>{@link Translations#replaceAll} pr. niveau, scopet til de felter pushet
 *       ejer.</li>
 *   <li>{@link AuditLog#recordAll} — kun for de rækker, der faktisk blev
 *       oprettet eller ændret. Et no-op-push skriver NUL audit-linjer, og det er
 *       det andet, uafhængige bevis for idempotens.</li>
 * </ol>
 *
 * <p><strong>Læs-tilbage er ikke en optimering, den er nødvendig.</strong>
 * Upsertens {@code RETURNING} giver kun de ÆNDREDE rækker, så et andet identisk
 * push returnerer ingenting. Uden læsningen ville børneniveauet og
 * oversættelserne stå uden forældre-id'er og i stilhed tabe hele deres niveau —
 * netop i det tilfælde, hvor alt skulle være uændret.
 *
 * <p><strong>Refs opløses i JAVA, aldrig i et JOIN.</strong> En INNER JOIN
 * dropper hvert event med en ukendt {@code organizerRef} i stilhed (rækketallet
 * kommer bare mindre tilbage); en LEFT JOIN til default-arrangøren flytter det i
 * stilhed. Begge er præcis den fejlklasse, designet findes for. Her bliver det
 * en {@link CatalogException}, der navngiver ref'en.
 *
 * <p><strong>⚠ Metoderne må ALDRIG gøres asynkrone eller returnere en
 * {@code Callable}.</strong> {@code OncePerRequestFilter} springer
 * ASYNC-dispatch over, og {@link OperatorContext}s {@code ScopedValue}-binding
 * lever kun på den tråd, der åbnede scopet. Et bulk-import er præcis den
 * handler, nogen ville flytte af request-tråden for at frigøre den — og
 * tenant-konteksten ville gå tabt undervejs, uden en fejl: forbindelsen ville
 * blive checket ud uden operatør, RLS ville give nul rækker, og pushet ville
 * rapportere succes.
 *
 * @see CatalogWriteGuard
 */
@Component
public class CatalogIngestService implements CatalogIngest {

	/**
	 * Aktør-referencen på de linjer, lazy enrichment skriver. Konstant og ikke en
	 * parameter: der er kun én kodesti, og "systemet gjorde det" skal kunne spores
	 * til præcis det sted.
	 */
	static final String LAZY_ENRICHMENT = "lazy_enrichment";

	/**
	 * Så mange audit-linjer pr. sætning. Én sætning pr. push ville for et fuldt
	 * førstedump (12.200 rækker plus oversættelser, hver med hele rækken som
	 * JSON) holde 10-30 MB tekst i hukommelsen ad gangen — inde i den
	 * transaktion, der holder operatørens lås. Klumperne deler transaktion, så
	 * "alt eller intet" er uændret.
	 */
	static final int AUDIT_CHUNK = 1_000;

	private static final Logger log = LoggerFactory.getLogger(CatalogIngestService.class);

	private final CatalogWriteGuard guard;

	private final OrganizerJdbcStore organizers;

	private final EventJdbcStore events;

	private final TicketTypeJdbcStore ticketTypes;

	private final Translations translations;

	private final AuditLog auditLog;

	private final OperatorRegistry operators;

	private final FieldSchemaRegistry fieldSchemas;

	CatalogIngestService(CatalogWriteGuard guard, OrganizerJdbcStore organizers, EventJdbcStore events,
			TicketTypeJdbcStore ticketTypes, Translations translations, AuditLog auditLog,
			OperatorRegistry operators, FieldSchemaRegistry fieldSchemas) {
		this.guard = guard;
		this.organizers = organizers;
		this.events = events;
		this.ticketTypes = ticketTypes;
		this.translations = translations;
		this.auditLog = auditLog;
		this.operators = operators;
		this.fieldSchemas = fieldSchemas;
	}

	// --- Lazy enrichment ----------------------------------------------------

	/**
	 * {@inheritDoc}
	 *
	 * <p>Gulvet under kataloget (PLAN §2.2). To rækker skrives — eventet OG dets
	 * billettype — begge med {@code ON CONFLICT DO NOTHING}, så et verify-svar
	 * ALDRIG overskriver katalogdata. Den modsatte vej er tilladt: katalog-pushet
	 * er den fulde upsert og udfylder gerne et skelet, uden at ændre rækkens
	 * identitet.
	 *
	 * <p>To ting sker med vilje i denne rækkefølge:
	 * <ul>
	 *   <li>Låsen og enkelt-valuta-kontrollen tages FØR arrangøren oprettes, så
	 *       to samtidige verify-svar i hver sin valuta ikke begge kan se et tomt
	 *       katalog.</li>
	 *   <li>{@link Event#acceptFaceValue(Money)} kaldes FØR billettypen skrives.
	 *       Findes eventet i forvejen i en anden valuta, bliver afvisningen en
	 *       {@link dk.aftermarked.money.CurrencyMismatchException}, der navngiver
	 *       begge koder — og ikke en rå 23503 fra den sammensatte fremmednøgle
	 *       {@code (operator_id, event_id, currency)}, som ingen kan læse.</li>
	 * </ul>
	 *
	 * <p>{@code required: true}-custom-felter gælder ikke her: et verify-svar
	 * kender ingen feltværdier, og én påkrævet feltdeklaration ville ellers gøre
	 * en kundes første listing umulig. Skelettet fødes med databasens eget tomme
	 * feltdokument.
	 */
	@Override
	@Transactional
	public Event enrich(VerifiedTicket verified) {
		Objects.requireNonNull(verified, "verified");
		OperatorContext.required();
		this.guard.serialiseWritesForCurrentOperator();
		Operator operator = this.operators.current();
		Currency currency = verified.faceValue().currency();
		this.guard.requireSingleCurrency(operator, currency);

		// Kun de rækker, der faktisk blev OPRETTET, får en linje. Et andet
		// verify-svar for samme billet skriver nul linjer. Efter-tilstanden er
		// hele rækken, som ved et push: "hvad skrev systemet kl. 03:12?" skal
		// kunne besvares for netop den sti, ingen har bedt om.
		AuditActor actor = AuditActor.system(LAZY_ENRICHMENT);
		List<AuditEntry> entries = new ArrayList<>(3);
		EventRef eventRef = verified.eventRef();
		Optional<Event> existingEvent = attempt("the event [" + eventRef.value() + "] during lazy enrichment",
				() -> this.events.findByRef(eventRef));
		Optional<EventJdbcStore.UpsertOutcome> createdEvent = Optional.empty();
		if (existingEvent.isEmpty()) {
			// Et kendt event har allerede sin arrangør. Kun et NYT skelet har
			// brug for en default; ellers ville et verify-kald oprette en ekstra
			// arrangør eller kollidere med operatørens eksisterende default.
			UUID organizerId = defaultOrganizerId(operator, actor, entries);
			createdEvent = attempt("the event [" + eventRef.value() + "] during lazy enrichment",
					() -> this.events.insertSkeleton(organizerId, eventRef, verified.eventStartsAt(), currency));
		}
		// Tom Optional betyder "fandtes allerede" og er SUCCES. Eventet læses
		// tilbage efter et indsættelsesforsøg -- kalderen skal have aggregatet,
		// og billettypen skal have id'et.
		Event event = existingEvent.orElseGet(() -> attempt("the event [" + eventRef.value() + "] during lazy enrichment",
				() -> this.events.findByRef(eventRef))
				.orElseThrow(() -> new CatalogException(
						"The event [" + eventRef.value() + "] was written but could not be read back")));
		// Returværdien kasseres: kaldet er her for KONTROLLEN. Prisloftets vej
		// (S4) går gennem den samme metode, men her er den den ene dør, der
		// gør en valuta-uenighed læsbar, før billettypen rammer fremmednøglen.
		event.acceptFaceValue(verified.faceValue());
		Optional<TicketTypeJdbcStore.UpsertOutcome> createdTicketType = attempt(
				"the ticket type [" + verified.ticketTypeRef().value() + "] during lazy enrichment",
				() -> this.ticketTypes.insertSkeleton(event.id(), verified.ticketTypeRef(),
						verified.ticketTypeName(), verified.faceValue()));

		createdEvent.ifPresent(outcome -> entries.add(AuditEntry.of(actor, EventJdbcStore.ENTITY_TYPE + ".created",
				EventJdbcStore.ENTITY_TYPE, outcome.id(), outcome.ref(), null, outcome.afterState())));
		createdTicketType.ifPresent(outcome -> entries.add(AuditEntry.of(actor,
				TicketTypeJdbcStore.ENTITY_TYPE + ".created", TicketTypeJdbcStore.ENTITY_TYPE, outcome.id(),
				outcome.ref(), null, outcome.afterState())));
		recordAudit("the audit trail of lazy enrichment", entries);
		return event;
	}

	// --- Katalog-push -------------------------------------------------------

	/**
	 * Skriver et helt katalog-push og rapporterer, hvad der faktisk skete.
	 *
	 * <p>Payloaden er allerede parset og valideret, når den når hertil: hele
	 * kontrollen er ren Java uden en eneste database-rundtur, og alle
	 * overtrædelser er rapporteret på én gang.
	 *
	 * @param payload de typede upsert-records, parseren har produceret
	 * @param pushId ét id pr. request. Det bliver {@code actor_ref} på hver
	 *     audit-linje, så en natlig ændring kan spores til den kørsel, der lavede
	 *     den
	 * @throws CatalogException hvis en række ikke kunne skrives, eller hvis en ref
	 *     ikke kunne læses tilbage
	 * @throws dk.aftermarked.money.CurrencyMismatchException hvis operatøren kører
	 *     enkelt-valuta, og pushet ville give kataloget sin anden valuta
	 */
	@Transactional
	public CatalogIngestReport apply(CatalogPayload payload, String pushId) {
		Objects.requireNonNull(payload, "payload");
		Objects.requireNonNull(pushId, "pushId");
		OperatorContext.required();
		this.guard.serialiseWritesForCurrentOperator();
		return applyLocked(this.operators.current(), payload, pushId);
	}

	/**
	 * Samme push, men fra kundens rå JSON — formen {@code PUT /catalog} vil bruge,
	 * den dag endpointet skibes.
	 *
	 * <p>Parseren bygges pr. kald, fordi den er bundet til operatørens EGET
	 * basissprog og operatørens EGET feltskema: en delt instans ville validere
	 * den svenske kundes payload mod den danske kundes regler.
	 *
	 * <p><strong>{@code @Transactional} står også her, og det er ikke en
	 * gentagelse.</strong> Et selvkald går uden om Springs proxy, så annotationen
	 * på {@link #apply(CatalogPayload, String)} ville være død kode fra denne vej:
	 * hver eneste sætning ville køre i sin egen auto-commit, den advisory-lås,
	 * hele designet hviler på, ville blive frigivet med det samme, og et push ville
	 * kunne committe halvdelen af sig selv.
	 */
	@Transactional
	public CatalogIngestReport apply(JsonNode body, String pushId) {
		Objects.requireNonNull(body, "body");
		Objects.requireNonNull(pushId, "pushId");
		OperatorContext.required();
		this.guard.serialiseWritesForCurrentOperator();
		Operator operator = this.operators.current();
		CatalogPayloadParser parser = new CatalogPayloadParser(this.fieldSchemas.forOperator(operator.slug()),
				operator.defaultLocale(), operator.slug(), this.guard.requiresSingleCurrencyPayload(operator));
		// Parsning og validering rører ingen rækker: en afvist payload må aldrig
		// efterlade et delvist skrevet katalog.
		return applyLocked(operator, parser.parse(body), pushId);
	}

	/**
	 * Selve pipelinen, når konteksten er bundet og låsen er taget. Privat, fordi
	 * ingen kalder må kunne springe de to trin over.
	 */
	private CatalogIngestReport applyLocked(Operator operator, CatalogPayload payload, String pushId) {
		// Bygges først, så et blankt push-id fejler før den første række og
		// ikke først når audit-linjerne skrives -- et no-op-push skriver ingen.
		AuditActor actor = AuditActor.catalogPush(pushId);
		List<OrganizerUpsert> pushedOrganizers = payload.organizers();
		int schemaVersion = payload.schemaVersion();
		rejectTheReservedOrganizerRef(pushedOrganizers);
		// FØR den første række røres, og som en MÆNGDE. Tjekkes valutaerne én ad
		// gangen, ser hvert tjek det samme katalog, og et push, der selv bærer
		// både DKK og EUR, slipper igennem med to grønne tjek. Og tjekkes de
		// EFTER skrivningen, er kontrollen ikke længere en forudsætning.
		this.guard.requireSingleCurrency(operator, currenciesIn(pushedOrganizers));
		logWarnings(pushId, payload.warnings());

		// --- Arrangører ---
		List<OrganizerJdbcStore.OrganizerRow> organizerRows = new ArrayList<>(pushedOrganizers.size());
		List<OrganizerRef> organizerRefs = new ArrayList<>(pushedOrganizers.size());
		for (OrganizerUpsert organizer : pushedOrganizers) {
			organizerRows.add(new OrganizerJdbcStore.OrganizerRow(organizer.ref(), organizer.name().base(),
					organizer.customFields()));
			organizerRefs.add(organizer.ref());
		}
		List<OrganizerJdbcStore.UpsertOutcome> organizerOutcomes = attempt(
				"the organizer level of catalog push [" + pushId + "]",
				() -> this.organizers.upsertAll(organizerRows, schemaVersion));
		Map<OrganizerRef, UUID> organizerIds = attempt("the organizer level of catalog push [" + pushId + "]",
				() -> this.organizers.idsByRef(organizerRefs));
		for (OrganizerRef ref : organizerRefs) {
			if (!organizerIds.containsKey(ref)) {
				throw new CatalogException("The organizer [" + ref.value()
						+ "] was upserted but could not be read back; its events would lose their parent");
			}
		}

		// --- Events ---
		List<EventJdbcStore.EventRow> eventRows = new ArrayList<>();
		List<EventRef> eventRefs = new ArrayList<>();
		List<EventUpsert> pushedEvents = new ArrayList<>();
		for (OrganizerUpsert organizer : pushedOrganizers) {
			// Kan ikke ske -- arrangøren blev netop upsertet -- men et JOIN ville
			// have tabt eventet i stilhed, og det er præcis dét, denne kodesti
			// findes for at undgå.
			UUID organizerId = organizerIds.get(organizer.ref());
			if (organizerId == null) {
				throw new CatalogException(
						"The event level references the unknown organizer ref [" + organizer.ref().value() + "]");
			}
			for (EventUpsert event : organizer.events()) {
				eventRows.add(new EventJdbcStore.EventRow(organizerId, event.ref(), event.title().base(),
						baseOrNull(event.venue()), event.startsAt(), event.endsAt(), event.salesStatus(),
						event.salesEndsAt(), event.primaryPurchaseUrl(), event.currency(),
						event.customFields()));
				eventRefs.add(event.ref());
				pushedEvents.add(event);
			}
		}
		List<EventJdbcStore.UpsertOutcome> eventOutcomes = attempt(
				"the event level of catalog push [" + pushId + "]",
				() -> this.events.upsertAll(eventRows, schemaVersion));
		Map<EventRef, UUID> eventIds = attempt("the event level of catalog push [" + pushId + "]",
				() -> this.events.idsByRef(eventRefs));
		for (EventRef ref : eventRefs) {
			if (!eventIds.containsKey(ref)) {
				throw new CatalogException("The event [" + ref.value()
						+ "] was upserted but could not be read back; its ticket types would lose their parent");
			}
		}

		// --- Billettyper ---
		List<TicketTypeJdbcStore.TicketTypeRow> ticketTypeRows = new ArrayList<>();
		List<TicketTypeJdbcStore.TicketTypeKey> ticketTypeKeys = new ArrayList<>();
		List<TicketTypeUpsert> pushedTicketTypes = new ArrayList<>();
		for (int index = 0; index < pushedEvents.size(); index++) {
			EventUpsert event = pushedEvents.get(index);
			UUID eventId = eventIds.get(eventRefs.get(index));
			for (TicketTypeUpsert ticketType : event.ticketTypes()) {
				ticketTypeRows.add(new TicketTypeJdbcStore.TicketTypeRow(eventId, ticketType.ref(),
						ticketType.name().base(), ticketType.faceValue(),
						ticketType.customFields()));
				ticketTypeKeys.add(new TicketTypeJdbcStore.TicketTypeKey(eventId, ticketType.ref()));
				pushedTicketTypes.add(ticketType);
			}
		}
		List<TicketTypeJdbcStore.UpsertOutcome> ticketTypeOutcomes = attempt(
				"the ticket type level of catalog push [" + pushId + "]",
				() -> this.ticketTypes.upsertAll(ticketTypeRows, schemaVersion));
		// ⚠ Svaret rummer ALLE billettyper på de opgivne events, ogsaa dem dette
		// delta ikke bar. Sendes hele kortet videre som entityIds til replaceAll,
		// slettes de upushede billettypers oversættelser -- derfor filtreres der
		// ned til præcis de nøgler, pushet bærer.
		Map<TicketTypeJdbcStore.TicketTypeKey, UUID> everyTicketTypeOnThePushedEvents = attempt(
				"the ticket type level of catalog push [" + pushId + "]",
				() -> this.ticketTypes.idsByRef(eventIds.values()));
		Map<TicketTypeJdbcStore.TicketTypeKey, UUID> pushedTicketTypeIds = new LinkedHashMap<>();
		for (TicketTypeJdbcStore.TicketTypeKey key : ticketTypeKeys) {
			UUID id = everyTicketTypeOnThePushedEvents.get(key);
			if (id == null) {
				throw new CatalogException("The ticket type [" + key.ref().value()
						+ "] was upserted but could not be read back on its event");
			}
			pushedTicketTypeIds.put(key, id);
		}

		// --- Oversættelser ---
		// entityIds er PRÆCIS de entiteter, pushet bærer. DELETE'en er scopet
		// til dem og til de felter, pushet ejer, så operatørens egne
		// admin-oversættelser af andre felter overlever.
		List<TranslationRow> organizerTranslations = new ArrayList<>();
		for (int index = 0; index < pushedOrganizers.size(); index++) {
			UUID id = organizerIds.get(organizerRefs.get(index));
			addTranslations(organizerTranslations, id, "name", pushedOrganizers.get(index).name());
		}
		Translations.TranslationChanges organizerChange = attempt(
				"the organizer translations of catalog push [" + pushId + "]",
				() -> this.translations.replaceAllWithChanges(OrganizerJdbcStore.ENTITY_TYPE, organizerIds.values(),
						OrganizerJdbcStore.TRANSLATED_FIELDS, organizerTranslations));

		List<TranslationRow> eventTranslations = new ArrayList<>();
		for (int index = 0; index < pushedEvents.size(); index++) {
			UUID id = eventIds.get(eventRefs.get(index));
			EventUpsert event = pushedEvents.get(index);
			addTranslations(eventTranslations, id, "title", event.title());
			// Et event uden sted har ingen rækker at skrive -- og fordi DELETE'en
			// stadig dækker feltet, forsvinder en tidligere oversættelse af et
			// sted, kunden har fjernet. Det er meningen.
			addTranslations(eventTranslations, id, "venue", event.venue());
		}
		Translations.TranslationChanges eventChange = attempt(
				"the event translations of catalog push [" + pushId + "]",
				() -> this.translations.replaceAllWithChanges(EventJdbcStore.ENTITY_TYPE, eventIds.values(),
						EventJdbcStore.TRANSLATED_FIELDS, eventTranslations));

		List<TranslationRow> ticketTypeTranslations = new ArrayList<>();
		for (int index = 0; index < pushedTicketTypes.size(); index++) {
			UUID id = pushedTicketTypeIds.get(ticketTypeKeys.get(index));
			addTranslations(ticketTypeTranslations, id, "name", pushedTicketTypes.get(index).name());
		}
		Translations.TranslationChanges ticketTypeChange = attempt(
				"the ticket type translations of catalog push [" + pushId + "]",
				() -> this.translations.replaceAllWithChanges(TicketTypeJdbcStore.ENTITY_TYPE, pushedTicketTypeIds.values(),
						TicketTypeJdbcStore.TRANSLATED_FIELDS, ticketTypeTranslations));

		// --- Audit ---
		// KUN ændrede rækker. Et no-op-push skriver nul linjer, og dét er det
		// andet, uafhængige bevis for idempotens: tæller man audit-rækkerne
		// før og efter et gentaget push, skal tallet stå stille.
		List<AuditEntry> entries = new ArrayList<>(
				organizerOutcomes.size() + eventOutcomes.size() + ticketTypeOutcomes.size());
		for (OrganizerJdbcStore.UpsertOutcome outcome : organizerOutcomes) {
			entries.add(AuditEntry.of(actor, action(OrganizerJdbcStore.ENTITY_TYPE, outcome.created()),
					OrganizerJdbcStore.ENTITY_TYPE, outcome.id(), outcome.ref(), outcome.beforeState(),
					outcome.afterState()));
		}
		for (EventJdbcStore.UpsertOutcome outcome : eventOutcomes) {
			entries.add(AuditEntry.of(actor, action(EventJdbcStore.ENTITY_TYPE, outcome.created()),
					EventJdbcStore.ENTITY_TYPE, outcome.id(), outcome.ref(), outcome.beforeState(),
					outcome.afterState()));
		}
		for (TicketTypeJdbcStore.UpsertOutcome outcome : ticketTypeOutcomes) {
			entries.add(AuditEntry.of(actor, action(TicketTypeJdbcStore.ENTITY_TYPE, outcome.created()),
					TicketTypeJdbcStore.ENTITY_TYPE, outcome.id(), outcome.ref(), outcome.beforeState(),
					outcome.afterState()));
		}
		// Oversættelser ændrer ikke grundrækken. De har derfor deres egne
		// auditlinjer, knyttet til samme entitet og push, med SQL'ens faktiske
		// før/efter-tilstand. Et no-op giver ingen returnerede ændringer.
		addTranslationAudits(entries, actor, OrganizerJdbcStore.ENTITY_TYPE, organizerIds,
				OrganizerRef::value, organizerChange);
		addTranslationAudits(entries, actor, EventJdbcStore.ENTITY_TYPE, eventIds,
				EventRef::value, eventChange);
		addTranslationAudits(entries, actor, TicketTypeJdbcStore.ENTITY_TYPE, pushedTicketTypeIds,
				key -> key.ref().value(), ticketTypeChange);
		recordAudit("the audit trail of catalog push [" + pushId + "]", entries);

		return new CatalogIngestReport(
				counts(organizerRows.size(), organizerOutcomes.size(), createdIn(organizerOutcomes)),
				counts(eventRows.size(), eventOutcomes.size(), createdInEvents(eventOutcomes)),
				counts(ticketTypeRows.size(), ticketTypeOutcomes.size(), createdInTicketTypes(ticketTypeOutcomes)),
				organizerChange.written() + eventChange.written() + ticketTypeChange.written(),
				organizerChange.removed() + eventChange.removed() + ticketTypeChange.removed(),
				payload.warnings());
	}

	// --- Hjælpere ----------------------------------------------------------

	private static <R> void addTranslationAudits(List<AuditEntry> entries, AuditActor actor, String entityType,
			Map<R, UUID> idsByRef, Function<R, String> reference, Translations.TranslationChanges changes) {
		Map<UUID, String> refsById = new LinkedHashMap<>();
		idsByRef.forEach((ref, id) -> refsById.put(id, reference.apply(ref)));
		for (Translations.TranslationRowChange change : changes.rows()) {
			String verb = change.afterState() == null ? "translation_deleted"
					: change.beforeState() == null ? "translation_created" : "translation_updated";
			entries.add(AuditEntry.of(actor, entityType + "." + verb, entityType, change.entityId(),
					refsById.get(change.entityId()), change.beforeState(), change.afterState()));
		}
	}

	/**
	 * Default-arrangøren, nye skelet-events hænger på (PLAN §2.3). En eksisterende
	 * default tilhører operatøren og genbruges; kun uden en sådan syntetiserer vi
	 * den reserverede arrangør. Det partielle indeks tillader højst én default.
	 */
	private UUID defaultOrganizerId(Operator operator, AuditActor actor, List<AuditEntry> entries) {
		Optional<UUID> existing = attempt("the default organizer during lazy enrichment",
				this.organizers::findDefaultId);
		if (existing.isPresent()) {
			return existing.get();
		}
		// Den syntetiske arrangør er en række, systemet selv opretter, og den
		// skal derfor i audit-sporet som alt andet, enrichment skriver: ellers
		// peger event.created på en arrangør, sporet aldrig har set.
		Optional<OrganizerJdbcStore.UpsertOutcome> created = attempt(
				"the default organizer during lazy enrichment",
				() -> this.organizers.createDefault(operator.name()));
		if (created.isPresent()) {
			OrganizerJdbcStore.UpsertOutcome outcome = created.get();
			entries.add(AuditEntry.of(actor, OrganizerJdbcStore.ENTITY_TYPE + ".created",
					OrganizerJdbcStore.ENTITY_TYPE, outcome.id(), outcome.ref(), null, outcome.afterState()));
			return outcome.id();
		}
		// Fandtes allerede under en anden skrivning: læs id'et tilbage.
		OrganizerRef ref = new OrganizerRef(OrganizerRef.RESERVED_DEFAULT);
		UUID id = attempt("the default organizer during lazy enrichment",
				() -> this.organizers.idsByRef(List.of(ref))).get(ref);
		if (id == null) {
			throw new CatalogException(
					"The synthesized default organizer exists but could not be read back");
		}
		return id;
	}

	/**
	 * Nettet under parserens {@code RESERVED_REF}. Databasens CHECK TILLADER
	 * {@value dk.aftermarked.catalog.OrganizerRef#RESERVED_DEFAULT}, fordi vi selv
	 * skriver rækken — så uden denne kontrol ville et push kunne omdøbe præcis den
	 * arrangør, alle skelet-events hænger på, gennem upsertens {@code SET name}.
	 */
	private static void rejectTheReservedOrganizerRef(List<OrganizerUpsert> pushedOrganizers) {
		// Samme kontrakt som parseren: kode og JSON-sti, så den typede indgang
		// (S3s pull-poller) får præcis den 422, JSON-indgangen får.
		for (int index = 0; index < pushedOrganizers.size(); index++) {
			if (pushedOrganizers.get(index).ref().isReservedDefault()) {
				throw new CatalogValidationException(List.of(new Violation(CatalogErrorCode.RESERVED_REF.name(),
						"/organizers/" + index + "/ref", "The organizer ref [" + OrganizerRef.RESERVED_DEFAULT
								+ "] is reserved for lazy enrichment and must not be pushed")));
			}
		}
	}

	/**
	 * Én linje pr. push, aldrig én pr. række, og aldrig kundens rå token: et push
	 * med 2.000 events i en ukendt salgsstatus giver én linje med tallet 2.000.
	 * Uden den ville en ukendt status være usynlig for os, indtil endpointet
	 * viser {@code warnings[]} til kunden.
	 */
	private static void logWarnings(String pushId, List<CatalogWarning> warnings) {
		if (warnings.isEmpty()) {
			return;
		}
		Map<CatalogErrorCode, Integer> byCode = new TreeMap<>();
		for (CatalogWarning warning : warnings) {
			byCode.merge(warning.code(), 1, Integer::sum);
		}
		log.warn("Catalog push [{}] carried {} warnings: {}", pushId, warnings.size(), byCode);
	}

	/**
	 * Audit i klumper af {@link #AUDIT_CHUNK}. Samme transaktion, så et no-op
	 * push skriver stadig nul linjer, og en fejl i én klump ruller det hele
	 * tilbage.
	 */
	private void recordAudit(String what, List<AuditEntry> entries) {
		for (int from = 0; from < entries.size(); from += AUDIT_CHUNK) {
			List<AuditEntry> chunk = entries.subList(from, Math.min(from + AUDIT_CHUNK, entries.size()));
			attempt(what, () -> {
				this.auditLog.recordAll(chunk);
				return null;
			});
		}
	}

	/**
	 * Oversættelsesrækkerne for ét felt. Grundsproget står ALDRIG i
	 * {@code byLocale} — grundteksten er sandheden, og oversættelsen er et lag
	 * ovenpå med grundteksten som fallback.
	 */
	private static void addTranslations(List<TranslationRow> rows, UUID entityId, String field,
			TranslatableText text) {
		if (text == null) {
			return;
		}
		for (Map.Entry<String, String> translation : text.byLocale().entrySet()) {
			rows.add(new TranslationRow(entityId, field, translation.getKey(), translation.getValue()));
		}
	}

	private static String baseOrNull(TranslatableText text) {
		return (text == null) ? null : text.base();
	}

	/**
	 * Alle valutaer, pushet ville tilføje — læst af payloaden alene, uden en
	 * eneste database-rundtur, så enkelt-valuta-politikken kan afgøres FØR den
	 * første række skrives. Rækkefølgen bevares, så fejlbeskeden er den samme fra
	 * kørsel til kørsel.
	 */
	private static Set<Currency> currenciesIn(List<OrganizerUpsert> pushedOrganizers) {
		Set<Currency> currencies = new LinkedHashSet<>();
		for (OrganizerUpsert organizer : pushedOrganizers) {
			for (EventUpsert event : organizer.events()) {
				currencies.add(event.currency());
			}
		}
		return currencies;
	}

	private static String action(String entityType, boolean created) {
		return entityType + (created ? ".created" : ".updated");
	}

	/**
	 * {@code unchanged} regnes som "pushet minus dem, sætningen rørte". Det ER
	 * definitionen: upserten returnerer kun de rækker, den faktisk oprettede eller
	 * ændrede, så et andet identisk push giver
	 * {@code created = 0, updated = 0, unchanged = pushet}.
	 */
	private static CatalogIngestReport.LevelCounts counts(int pushed, int touched, int created) {
		return new CatalogIngestReport.LevelCounts(created, touched - created, pushed - touched);
	}

	private static int createdIn(List<OrganizerJdbcStore.UpsertOutcome> outcomes) {
		int created = 0;
		for (OrganizerJdbcStore.UpsertOutcome outcome : outcomes) {
			if (outcome.created()) {
				created++;
			}
		}
		return created;
	}

	private static int createdInEvents(List<EventJdbcStore.UpsertOutcome> outcomes) {
		int created = 0;
		for (EventJdbcStore.UpsertOutcome outcome : outcomes) {
			if (outcome.created()) {
				created++;
			}
		}
		return created;
	}

	private static int createdInTicketTypes(List<TicketTypeJdbcStore.UpsertOutcome> outcomes) {
		int created = 0;
		for (TicketTypeJdbcStore.UpsertOutcome outcome : outcomes) {
			if (outcome.created()) {
				created++;
			}
		}
		return created;
	}

	/**
	 * Ombryder en {@link DataAccessException} i katalogets egen fejltype, så en
	 * kalder kan fange præcis katalogets fejl uden et bredt
	 * {@code catch (Exception)} — det, CLAUDE.md forbyder i den kode, der senere
	 * ligger ved siden af købs-sagaen.
	 *
	 * <p>Kun {@code DataAccessException} fanges. En
	 * {@link dk.aftermarked.money.CurrencyMismatchException} og storenes egne
	 * {@link IllegalArgumentException} går uændret igennem: den første er en
	 * pengefejl, der skal kunne skelnes hele vejen op, og den anden er en
	 * programfejl hos kalderen.
	 *
	 * <p>Beskeden navngiver niveauet og — hvor der er én — ref'en. Den bærer
	 * ALDRIG en værdi fra payloaden: den kan være persondata eller 20 KB
	 * richtext, og en exception-besked ender i loggen.
	 */
	private static <T> T attempt(String what, Supplier<T> statement) {
		try {
			return statement.get();
		}
		catch (DataAccessException ex) {
			throw new CatalogException("Failed to write " + what, ex);
		}
	}

}

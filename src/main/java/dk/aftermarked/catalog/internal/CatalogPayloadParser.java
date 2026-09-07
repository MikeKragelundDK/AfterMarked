package dk.aftermarked.catalog.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import dk.aftermarked.catalog.CatalogValidationException;
import dk.aftermarked.catalog.CatalogValidationException.Violation;
import dk.aftermarked.catalog.EventRef;
import dk.aftermarked.catalog.OrganizerRef;
import dk.aftermarked.catalog.SalesStatus;
import dk.aftermarked.catalog.TicketTypeRef;
import dk.aftermarked.catalog.fields.CustomFieldSchema;
import dk.aftermarked.catalog.fields.CustomFieldScope;
import dk.aftermarked.catalog.fields.CustomFieldValues;
import dk.aftermarked.localization.SupportedLocales;
import dk.aftermarked.money.Money;
import tools.jackson.databind.JsonNode;

/**
 * Kundens katalog-payload som TYPEDE records, gået igennem i hånden fra en
 * {@link JsonNode}.
 *
 * <p><strong>Der bindes med vilje INGEN DTO'er med Jackson.</strong> Fire ting
 * kan et bindingsmål ikke:
 * <ol>
 * <li><strong>Streng-eller-objekt</strong> kan ikke udtrykkes som en
 *     record-komponent uden en deserializer — og deserializeren har brug for
 *     operatørens basissprog, altså request-state. Se
 *     {@link #parseTranslatable}.</li>
 * <li><strong>JSON-Pointer-stier i fejl</strong>
 *     ({@code /organizers/0/events/1/faceValue/amount}) kræver, at man går
 *     træet igennem. En bindingsfejl kender kun feltnavnet.</li>
 * <li><strong>Afvisning af ukendte nøgler skal være eksplicit.</strong>
 *     {@code @JsonIgnoreProperties(ignoreUnknown = false)} gør INTET — det er
 *     annotationens default, og den nægter blot at føje til ignoral-sættet.
 *     Adfærden styres af {@code FAIL_ON_UNKNOWN_PROPERTIES}, som Jackson 3
 *     leverer slået FRA, og som ikke må slås til globalt: den ville også ramme
 *     {@code event_publication}-rækker skrevet af en N-1-replika i en forskudt
 *     rollout. Nøglerne holdes derfor op mod et eksplicit {@link Set} pr.
 *     niveau.</li>
 * <li><strong>Heltalskontrollen på et beløb</strong> er
 *     {@code isIntegralNumber() && canConvertToLong()}. Begge konjunkter er
 *     nødvendige: {@code 300.0} kan konverteres til en {@code long}, og
 *     {@code 92233720368547758070} er et helt tal. {@code asLong()} COERCER og
 *     må aldrig bruges til validering.</li>
 * </ol>
 *
 * <p><strong>ALLE overtrædelser samles og kastes på én gang.</strong> Ét
 * katalog-push er ét alt-eller-intet-kald med op til 2.000 events, og en kunde,
 * der får én fejl ad gangen, skal bruge lige så mange natlige kørsler, som der
 * er fejl. Hele payloaden valideres derfor i ren Java, før én række røres.
 *
 * <p><strong>Overtrædelsen bærer ALDRIG værdien.</strong> Kun en stabil kode, en
 * JSON-Pointer og en udviklervendt engelsk grund: værdien kan være persondata
 * eller 20 KB richtext, og alle tre ender i logs. Kunden ved fra stien præcis,
 * hvor i sin egen payload værdien står. Et NAVN — en nøgle eller en ref, vi
 * selv har reserveret — er ikke en værdi og må gerne stå med.
 *
 * <p><strong>Payloaden bærer ingen tenant.</strong> Et {@code tenant}- eller
 * {@code operator}-felt hvor som helst i dokumentet er
 * {@link CatalogErrorCode#UNKNOWN_PROPERTY}, fordi operatøren udelukkende
 * resolves fra værtsnavnet (PLAN §2.7 regel 2). Det følger gratis af de
 * eksplicitte nøglesæt: der findes ikke et niveau, hvor navnet er kendt.
 *
 * <p><strong>Wire-formen</strong> er {@code organizers[]} som den ENESTE liste
 * på topniveau (spec-beslutning #16); {@code faceValue} er et objekt
 * (spec-beslutning #7); og et delta bærer færre ENTITETER, aldrig færre FELTER
 * (spec-beslutning #20) — hver entitet valideres derfor som komplet, og der
 * findes hverken sletning eller et full/delta-flag.
 *
 * <p><strong>Klassen ligger i {@code internal}</strong> (spec-beslutning #11):
 * den producerer typer, der bærer feltværdier, og lå de i den offentlige
 * {@code catalog}-pakke, kunne ethvert modul med
 * {@code allowedDependencies = {"catalog"}} nå feltlageret gennem den
 * unavngivne grænseflade, uden at ArchUnit-reglen (som kun matcher
 * {@code catalog.fields..}) nogensinde fyrede.
 *
 * <p>Parseren er per OPERATØR — skemaet, basissproget og slug'en hører til den
 * operatør, requesten blev opløst til — og derfor ikke en bønne. Den er
 * tilstandsløs mellem kald: alt, ét push samler op, ligger i {@link Push}.
 */
public class CatalogPayloadParser {

	/**
	 * Batch-grænserne (spec-beslutning #30). Ikke høflighed: ét push er én
	 * transaktion, og et no-op-upsert rækkelåser stadig hver konfliktende række
	 * til commit, så en afgrænset batch afgrænser låsevinduet. Selve
	 * body-størrelsen er HTTP-lagets ansvar; endpointet er udskudt (spec §8).
	 */
	static final int MAX_ORGANIZERS = 200;

	static final int MAX_EVENTS = 2000;

	static final int MAX_TICKET_TYPES = 10_000;

	/** Den ENESTE liste på topniveau. */
	static final Set<String> TOP_LEVEL_PROPERTIES = Set.of("organizers");

	static final Set<String> ORGANIZER_PROPERTIES = Set.of("ref", "name", "customFields", "events");

	static final Set<String> EVENT_PROPERTIES = Set.of("ref", "title", "venue", "startsAt", "endsAt",
			"salesStatus", "salesEndsAt", "primaryPurchaseUrl", "currency", "customFields", "ticketTypes");

	static final Set<String> TICKET_TYPE_PROPERTIES = Set.of("ref", "name", "faceValue", "customFields");

	static final Set<String> FACE_VALUE_PROPERTIES = Set.of("amount", "currency");

	/**
	 * Præcis samme form som V3's {@code translation_locale_format}, V4's
	 * {@code operator_default_locale_format} og {@code CustomFieldValidator}. Fire
	 * steder, der skal være enige om, hvad en sprogkode er, skrives ens med vilje.
	 */
	static final Pattern LOCALE_FORMAT = Pattern.compile("^[a-z]{2}(-[A-Z]{2})?$");

	/**
	 * Sprogene, fladen faktisk renderer. Et velformet tag uden for listen er
	 * ikke en fejl — teksten gemmes, og kunden får en {@code UNSUPPORTED_LOCALE}-
	 * advarsel. Sammenligningen er på SPROG og ikke på hele tagget, ordret som
	 * {@code SupportedLocales.isSupported}: {@code en-GB} er engelsk, og
	 * engelsk renderer vi. Listen læses fra {@code localization}-modulet og
	 * ikke fra rodpakkens web-konfiguration: rodpakken er usynlig for Modulith,
	 * og en import derfra ville være en afhængighed, ingen test kunne se.
	 */
	static final Set<String> RENDERED_LANGUAGES = Set.copyOf(SupportedLocales.ALL.stream()
			.map(Locale::getLanguage)
			.toList());

	/** Formatet, alle tre ref-typer deler. Skrevet ud, så en 422 kan læses uden kildekoden. */
	static final String REF_FORMAT = "^[A-Za-z0-9][A-Za-z0-9._~-]{0,63}$";

	/**
	 * https-POLITIKKEN for {@code primaryPurchaseUrl} (spec-beslutning #18).
	 * V5's CHECK er bevidst løsere ({@code https?}), fordi en CHECK kan strammes
	 * senere, men aldrig løsnes; politikken ligger her, hvor den kan ændres uden
	 * en migration.
	 *
	 * <p><strong>Koden er {@link CatalogErrorCode#FIELD_TYPE_MISMATCH}</strong>,
	 * og valget er bevidst: der findes ingen kode for "forkert skema i en URL",
	 * og en ny kode ville udvide den kontrakt, kunden implementerer imod, for et
	 * enkelt felt. {@code INVALID_REF_FORMAT} handler om refs,
	 * {@code UNKNOWN_PROPERTY} om nøgler, og {@code BLANK_VALUE} er reserveret
	 * til den tomme streng — som stadig rapporteres som netop dét. En URL med
	 * det forkerte skema HAR den forkerte form, og det er dét,
	 * {@code FIELD_TYPE_MISMATCH} betyder.
	 */
	static final String HTTPS_URL_REQUIRED = "primaryPurchaseUrl must be an absolute https URL";

	static final String HTTPS = "https";

	/** V5's {@code event_purchase_url_format} tillader højst 2.000 tegn i hele URL'en. */
	static final int MAX_PURCHASE_URL_CHARACTERS = 2000;

	/**
	 * Tegnet PostgreSQL ikke kan lagre. Skrevet som den <em>oktale</em> escape
	 * {@code '\0'} med vilje — en unicode-escape erstattes af oversætteren
	 * <em>før</em> tokeniseringen og ville lægge et råt NUL-tegn i kildefilen.
	 */
	private static final char NUL = '\0';

	/** Hvor meget af en nøgle der skrives i en fejlbesked. */
	private static final int KEY_CHARACTERS_IN_MESSAGE = 64;

	/** Det år-interval, ISO 8601s fire-cifrede tekstform kan bære uden fortegn. */
	static final int MIN_YEAR = 1;

	static final int MAX_YEAR = 9999;

	private final CustomFieldSchema schema;

	private final String baseLocaleTag;

	/**
	 * Det effektive krydstjek mellem events. Ingest-guarden opløser det under
	 * operatørlåsen: også et allerede blandet katalog skal kunne genafspilles,
	 * hvis {@code single_currency} efterfølgende er slået til igen.
	 */
	private final boolean singleCurrency;

	private final CustomFieldValidator fieldValidator;

	/**
	 * @param schema operatørens opløste feltskema —
	 *     {@code FieldSchemaRegistry.forOperator(slug)}
	 * @param baseLocale operatørens EGET basissprog
	 *     ({@code Operator.defaultLocale()}, kolonnen fra V4). Det er dét sprog,
	 *     objektformen SKAL indeholde. Målt mod den globale
	 *     {@code LocalizationConfig.DEFAULT_LOCALE} ville en svensk kundes fuldt
	 *     gyldige {@code {"sv": …, "en": …}} blive afvist for ikke at indeholde
	 *     dansk
	 * @param operatorSlug navngives i afvisningen af et ukendt custom felt, så en
	 *     nyonboardet kunde uden egen feltskema-fil får en diagnoserbar fejl
	 * @param singleCurrency om payloaden skal dele én valuta, opløst af
	 *     {@code CatalogWriteGuard.requiresSingleCurrencyPayload(operator)} under låsen.
	 *     Falsk for internationale og allerede blandede kataloger; guarden
	 *     afviser stadig nye valutaer efter parsning, når politikken kræver det
	 */
	public CatalogPayloadParser(CustomFieldSchema schema, Locale baseLocale, String operatorSlug,
			boolean singleCurrency) {
		this.schema = Objects.requireNonNull(schema, "schema");
		Objects.requireNonNull(baseLocale, "baseLocale");
		Objects.requireNonNull(operatorSlug, "operatorSlug");
		this.baseLocaleTag = baseLocale.toLanguageTag();
		this.singleCurrency = singleCurrency;
		// Validatoren konstrueres her og gives ikke ind: den er bundet til PRÆCIS
		// det skema, det basissprog og den slug, parseren selv arbejder under, og
		// to kilder til den binding ville før eller siden komme fra hinanden.
		// Slug'en holdes derfor kun ét sted — det er validatoren, der navngiver
		// den i afvisningen af et ukendt custom felt.
		this.fieldValidator = new CustomFieldValidator(schema, baseLocale, operatorSlug);
	}

	/**
	 * Hele payloaden, valideret og typet.
	 *
	 * @param body kundens dokument, som det kom ind
	 * @return de typede upserts, advarslerne i payloadens rækkefølge og den
	 *     feltskema-version, alt blev valideret under
	 * @throws CatalogValidationException med ALLE overtrædelser på én gang
	 */
	public CatalogPayload parse(JsonNode body) {
		Objects.requireNonNull(body, "body");
		Push push = new Push();
		if (!body.isObject()) {
			// Roden er dokumentet selv, så stien er den tomme streng (RFC 6901).
			push.reject(CatalogErrorCode.FIELD_TYPE_MISMATCH, "",
					"A catalog payload must be a JSON object carrying a single organizers array");
			throw new CatalogValidationException(push.violations);
		}
		rejectUnknownProperties(body, "", TOP_LEVEL_PROPERTIES, "the catalog payload", push);
		JsonNode organizers = body.get("organizers");
		if (!isPresent(organizers)) {
			push.reject(CatalogErrorCode.MISSING_REQUIRED_FIELD, "/organizers",
					"The field [organizers] is required; a customer without organizers sends exactly one "
							+ "organizer of their own");
			throw new CatalogValidationException(push.violations);
		}
		if (!organizers.isArray()) {
			push.reject(CatalogErrorCode.FIELD_TYPE_MISMATCH, "/organizers",
					"The field [organizers] must be an array of organizers");
			throw new CatalogValidationException(push.violations);
		}
		// Grænserne tælles FØRST og afviser alene. At gå 100.000 events igennem for
		// at opremse feltfejl i et push, vi afviser på strukturen, er både en
		// DoS-form og støj, kunden ikke kan handle på: de skal chunke, ikke rette.
		// Det er en bevidst indsnævring af "alle overtrædelser på én gang".
		//
		// Mærket er bærende: uden det ville en ukendt nøgle på TOPNIVEAU — som
		// allerede står i listen — afbryde gennemgangen, og kunden ville få én
		// linje om en stavefejl i stedet for hele sin fejlliste.
		int mark = push.violations.size();
		rejectOversizedBatch(organizers, push);
		if (push.violations.size() > mark) {
			throw new CatalogValidationException(push.violations);
		}
		List<OrganizerUpsert> parsed = new ArrayList<>();
		for (int index = 0; index < organizers.size(); index++) {
			OrganizerUpsert organizer = parseOrganizer(organizers.get(index), "/organizers/" + index, push);
			if (organizer != null) {
				parsed.add(organizer);
			}
		}
		if (!push.violations.isEmpty()) {
			throw new CatalogValidationException(push.violations);
		}
		// En tom liste er et lovligt no-op-push. Fravær betyder ikke sletning
		// (spec-beslutning #19), så et tomt push skriver ingenting — det tømmer
		// ikke kataloget klokken 03, når kundens cron-job fejler og returnerer {}.
		return new CatalogPayload(parsed, push.warnings, this.schema.version());
	}

	// --- Niveauerne ----------------------------------------------------------

	/**
	 * Én arrangør. Giver {@code null}, hvis noget som helst i den — eller i dens
	 * events — blev afvist: så er hele pushet på vej til at blive kastet, og en
	 * halvbygget record må ikke kunne slippe ud.
	 *
	 * <p>Mærket ({@code mark}) er hele mekanikken: børnene lægger deres
	 * overtrædelser i den SAMME liste, så en dårlig billettype gør både sit event
	 * og sin arrangør ubrugelig — men først EFTER at alt er gået igennem, så
	 * kunden får hele listen.
	 */
	private OrganizerUpsert parseOrganizer(JsonNode node, String pointer, Push push) {
		int mark = push.violations.size();
		if (!node.isObject()) {
			push.reject(CatalogErrorCode.FIELD_TYPE_MISMATCH, pointer, "An organizer must be a JSON object");
			return null;
		}
		rejectUnknownProperties(node, pointer, ORGANIZER_PROPERTIES, "an organizer", push);
		OrganizerRef ref = parseOrganizerRef(node.get("ref"), pointerFor(pointer, "ref"), push);
		TranslatableText name = parseTranslatable(node.get("name"), pointerFor(pointer, "name"), "name", true, push);
		CustomFieldValues customFields = parseCustomFields(node.get("customFields"),
				pointerFor(pointer, "customFields"), CustomFieldScope.ORGANIZER, null, true, push);
		List<EventUpsert> events = parseEvents(node.get("events"), pointerFor(pointer, "events"), push);
		if (push.violations.size() > mark) {
			return null;
		}
		return new OrganizerUpsert(ref, name, customFields, events);
	}

	/**
	 * Arrangørens events. Listen er VALGFRI: nesting er adressering og ikke
	 * indeholdelse (spec-beslutning #20), så en arrangør, der kun får rettet sit
	 * navn, behøver ikke bære sine events — og fravær sletter aldrig noget.
	 */
	private List<EventUpsert> parseEvents(JsonNode node, String pointer, Push push) {
		if (!isPresent(node)) {
			return List.of();
		}
		if (!node.isArray()) {
			push.reject(CatalogErrorCode.FIELD_TYPE_MISMATCH, pointer, "The field [events] must be an array of events");
			return List.of();
		}
		List<EventUpsert> events = new ArrayList<>();
		for (int index = 0; index < node.size(); index++) {
			EventUpsert event = parseEvent(node.get(index), pointer + "/" + index, push);
			if (event != null) {
				events.add(event);
			}
		}
		return events;
	}

	private EventUpsert parseEvent(JsonNode node, String pointer, Push push) {
		int mark = push.violations.size();
		if (!node.isObject()) {
			push.reject(CatalogErrorCode.FIELD_TYPE_MISMATCH, pointer, "An event must be a JSON object");
			return null;
		}
		rejectUnknownProperties(node, pointer, EVENT_PROPERTIES, "an event", push);
		EventRef ref = parseEventRef(node.get("ref"), pointerFor(pointer, "ref"), push);
		TranslatableText title = parseTranslatable(node.get("title"), pointerFor(pointer, "title"), "title", true,
				push);
		TranslatableText venue = parseTranslatable(node.get("venue"), pointerFor(pointer, "venue"), "venue", false,
				push);
		Instant startsAt = parseTimestamp(node.get("startsAt"), pointerFor(pointer, "startsAt"), "startsAt", true,
				push);
		Instant endsAt = parseTimestamp(node.get("endsAt"), pointerFor(pointer, "endsAt"), "endsAt", false, push);
		if (startsAt != null && endsAt != null && endsAt.isBefore(startsAt)) {
			// Samme regel som V5's event_ends_after_start. Fanget her bliver den en
			// læsbar 422; fanget af databasen ville den være en 23514 midt i et
			// natligt push, hvor ingen kan se hvilket event det var.
			push.reject(CatalogErrorCode.ENDS_BEFORE_START, pointerFor(pointer, "endsAt"),
					"The field [endsAt] must not be before [startsAt]");
		}
		SalesStatus salesStatus = parseSalesStatus(node.get("salesStatus"), pointerFor(pointer, "salesStatus"), push);
		Instant salesEndsAt = parseTimestamp(node.get("salesEndsAt"), pointerFor(pointer, "salesEndsAt"),
				"salesEndsAt", false, push);
		String primaryPurchaseUrl = parsePurchaseUrl(node.get("primaryPurchaseUrl"),
				pointerFor(pointer, "primaryPurchaseUrl"), push);
		Currency currency = parseEventCurrency(node.get("currency"), pointerFor(pointer, "currency"), push);
		CustomFieldValues customFields = parseCustomFields(node.get("customFields"),
				pointerFor(pointer, "customFields"), CustomFieldScope.EVENT, currency, currency != null, push);
		List<TicketTypeUpsert> ticketTypes = parseTicketTypes(node.get("ticketTypes"),
				pointerFor(pointer, "ticketTypes"), currency, push);
		if (push.violations.size() > mark) {
			return null;
		}
		return new EventUpsert(ref, title, venue, startsAt, endsAt, salesStatus, salesEndsAt, primaryPurchaseUrl,
				currency, customFields, ticketTypes);
	}

	/**
	 * Eventets billettyper. Listen er PÅKRÆVET og må være tom: den er et
	 * kernefelt på eventet, og et event uden nøglen er en payload, kunden skal
	 * rette — modsat {@code events} på arrangøren, som er ren adressering.
	 */
	private List<TicketTypeUpsert> parseTicketTypes(JsonNode node, String pointer, Currency eventCurrency,
			Push push) {
		if (!isPresent(node)) {
			push.reject(CatalogErrorCode.MISSING_REQUIRED_FIELD, pointer,
					"The field [ticketTypes] is required and may be an empty array");
			return List.of();
		}
		if (!node.isArray()) {
			push.reject(CatalogErrorCode.FIELD_TYPE_MISMATCH, pointer,
					"The field [ticketTypes] must be an array of ticket types");
			return List.of();
		}
		List<TicketTypeUpsert> ticketTypes = new ArrayList<>();
		// Billettype-ref'en er kun unik INDEN I sit event: UNIQUE (operator_id,
		// event_id, ref). To events hos samme kunde må begge have en 'standard',
		// så sættet er nyt for hvert event og ikke delt for hele pushet.
		Set<String> seen = new HashSet<>();
		for (int index = 0; index < node.size(); index++) {
			TicketTypeUpsert ticketType = parseTicketType(node.get(index), pointer + "/" + index, eventCurrency,
					seen, push);
			if (ticketType != null) {
				ticketTypes.add(ticketType);
			}
		}
		return ticketTypes;
	}

	private TicketTypeUpsert parseTicketType(JsonNode node, String pointer, Currency eventCurrency,
			Set<String> seenRefs, Push push) {
		int mark = push.violations.size();
		if (!node.isObject()) {
			push.reject(CatalogErrorCode.FIELD_TYPE_MISMATCH, pointer, "A ticket type must be a JSON object");
			return null;
		}
		rejectUnknownProperties(node, pointer, TICKET_TYPE_PROPERTIES, "a ticket type", push);
		TicketTypeRef ref = parseTicketTypeRef(node.get("ref"), pointerFor(pointer, "ref"), seenRefs, push);
		TranslatableText name = parseTranslatable(node.get("name"), pointerFor(pointer, "name"), "name", true, push);
		Money faceValue = parseFaceValue(node.get("faceValue"), pointerFor(pointer, "faceValue"), eventCurrency,
				push);
		CustomFieldValues customFields = parseCustomFields(node.get("customFields"),
				pointerFor(pointer, "customFields"), CustomFieldScope.TICKET_TYPE, eventCurrency,
				eventCurrency != null, push);
		if (push.violations.size() > mark) {
			return null;
		}
		return new TicketTypeUpsert(ref, name, faceValue, customFields);
	}

	// --- Refs ----------------------------------------------------------------

	/**
	 * Arrangørens ref. Reservationen tjekkes FØR formatet, fordi
	 * {@code OrganizerRef.isValid} med vilje siger {@code true} for
	 * {@value OrganizerRef#RESERVED_DEFAULT}: databasens CHECK tillader værdien,
	 * fordi vi selv skriver den række i lazy enrichment. Kunne en kunde kræve
	 * den, ville alle skelet-events hænge på en arrangør, kunden pludselig ejede.
	 */
	private OrganizerRef parseOrganizerRef(JsonNode node, String pointer, Push push) {
		String raw = requiredString(node, pointer, "ref", push);
		if (raw == null) {
			return null;
		}
		if (OrganizerRef.RESERVED_DEFAULT.equals(raw)) {
			push.reject(CatalogErrorCode.RESERVED_REF, pointer, "The organizer ref ["
					+ OrganizerRef.RESERVED_DEFAULT + "] is reserved for the organizer we synthesize ourselves");
			return null;
		}
		if (!OrganizerRef.isValid(raw)) {
			push.reject(CatalogErrorCode.INVALID_REF_FORMAT, pointer, "An organizer ref must match " + REF_FORMAT);
			return null;
		}
		if (!push.organizerRefs.add(raw)) {
			push.reject(CatalogErrorCode.DUPLICATE_REF, pointer,
					"The same organizer ref appears twice in this push; which of the two won would be arbitrary");
			return null;
		}
		return new OrganizerRef(raw);
	}

	/**
	 * Eventets ref. Dubletsættet er HELE pushets og ikke arrangørens:
	 * {@code UNIQUE (operator_id, ref)} betyder, at to arrangører ikke kan dele
	 * en event-ref — og to events med samme ref under hver sin arrangør ville
	 * ellers ende som én række, hvis organizer_id skiftede undervejs.
	 */
	private EventRef parseEventRef(JsonNode node, String pointer, Push push) {
		String raw = requiredString(node, pointer, "ref", push);
		if (raw == null) {
			return null;
		}
		if (!EventRef.isValid(raw)) {
			push.reject(CatalogErrorCode.INVALID_REF_FORMAT, pointer, "An event ref must match " + REF_FORMAT);
			return null;
		}
		if (!push.eventRefs.add(raw)) {
			push.reject(CatalogErrorCode.DUPLICATE_REF, pointer,
					"The same event ref appears twice in this push; an event ref is unique per operator");
			return null;
		}
		return new EventRef(raw);
	}

	private TicketTypeRef parseTicketTypeRef(JsonNode node, String pointer, Set<String> seenRefs, Push push) {
		String raw = requiredString(node, pointer, "ref", push);
		if (raw == null) {
			return null;
		}
		if (!TicketTypeRef.isValid(raw)) {
			push.reject(CatalogErrorCode.INVALID_REF_FORMAT, pointer, "A ticket type ref must match " + REF_FORMAT);
			return null;
		}
		if (!seenRefs.add(raw)) {
			push.reject(CatalogErrorCode.DUPLICATE_REF, pointer,
					"The same ticket type ref appears twice on this event");
			return null;
		}
		return new TicketTypeRef(raw);
	}

	// --- Tekst i ét eller flere sprog ----------------------------------------

	/**
	 * Et oversætteligt kernefelt: en JSON-streng ELLER et objekt af sprogtags
	 * (PLAN §2.9).
	 *
	 * <p>Det er dét, en bunden DTO ikke kan udtrykke uden en deserializer — og
	 * deserializeren ville have brug for operatørens basissprog, altså
	 * request-state. Her er sproget bare et felt på parseren.
	 *
	 * <p>Objektformen SKAL indeholde operatørens eget basissprog. Uden det står
	 * fallback-kædens trin 4 — grundfeltet på entiteten — tomt, og teksten er
	 * væk for netop de brugere, kunden selv skriver for.
	 */
	private TranslatableText parseTranslatable(JsonNode node, String pointer, String field, boolean required,
			Push push) {
		if (!isPresent(node)) {
			if (required) {
				push.reject(CatalogErrorCode.MISSING_REQUIRED_FIELD, pointer,
						"The field [" + field + "] is required");
			}
			return null;
		}
		if (node.isObject()) {
			return parseTranslatableObject(node, pointer, field, push);
		}
		if (!node.isString()) {
			push.reject(CatalogErrorCode.FIELD_TYPE_MISMATCH, pointer,
					"The field [" + field + "] must be a string or an object of language tags");
			return null;
		}
		String value = node.stringValue();
		if (rejectNul(value, pointer, field, push)) {
			return null;
		}
		if (value.isBlank()) {
			push.reject(CatalogErrorCode.BLANK_VALUE, pointer, "The field [" + field + "] is blank");
			return null;
		}
		return TranslatableText.of(value);
	}

	private TranslatableText parseTranslatableObject(JsonNode node, String pointer, String field, Push push) {
		String base = null;
		boolean baseLanguageSeen = false;
		boolean sound = true;
		Map<String, String> byLocale = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> translation : node.properties()) {
			String tag = translation.getKey();
			String tagPointer = pointerFor(pointer, tag);
			if (rejectNul(tag, tagPointer, field, push)) {
				sound = false;
				continue;
			}
			if (!LOCALE_FORMAT.matcher(tag).matches()) {
				push.reject(CatalogErrorCode.INVALID_LOCALE_KEY, tagPointer, "The field [" + field
						+ "] is keyed by [" + describe(tag) + "], which is not a language tag on the form aa or aa-BB");
				sound = false;
				continue;
			}
			// Nøglen tæller som set, også når værdien bagefter afvises. Ellers
			// ville én blank dansk tekst give BÅDE BLANK_VALUE og
			// MISSING_BASE_LOCALE for den samme ene fejl.
			boolean isBaseLanguage = tag.equals(this.baseLocaleTag);
			baseLanguageSeen = baseLanguageSeen || isBaseLanguage;
			String text = readTranslation(translation.getValue(), tagPointer, field, push);
			if (text == null) {
				sound = false;
				continue;
			}
			if (isBaseLanguage) {
				base = text;
				continue;
			}
			byLocale.put(tag, text);
			if (!RENDERED_LANGUAGES.contains(Locale.forLanguageTag(tag).getLanguage())) {
				// GEMMES, ikke afvises: kunden må gerne ligge foran os. Advarslen
				// siger blot, at fladen ikke viser sproget, før bundlen findes.
				// Basissproget advares der aldrig om — det lander i grundfeltet,
				// som fallback-kæden altid ender på, uanset hvad vi renderer.
				push.warn(CatalogErrorCode.UNSUPPORTED_LOCALE, tagPointer, "The language tag on the field [" + field
						+ "] is stored, but the flow does not render it yet");
			}
		}
		if (!baseLanguageSeen) {
			push.reject(CatalogErrorCode.MISSING_BASE_LOCALE, pointer, "The field [" + field
					+ "] must carry the operator base language [" + this.baseLocaleTag
					+ "]; it is the base column the fallback chain ends on");
			return null;
		}
		if (!sound || base == null) {
			return null;
		}
		return new TranslatableText(base, byLocale);
	}

	private String readTranslation(JsonNode node, String pointer, String field, Push push) {
		if (!isPresent(node) || !node.isString()) {
			push.reject(CatalogErrorCode.FIELD_TYPE_MISMATCH, pointer,
					"Every language of the field [" + field + "] must be a string");
			return null;
		}
		String text = node.stringValue();
		if (rejectNul(text, pointer, field, push)) {
			return null;
		}
		if (text.isBlank()) {
			push.reject(CatalogErrorCode.BLANK_VALUE, pointer,
					"The field [" + field + "] is blank in one of its languages");
			return null;
		}
		return text;
	}

	// --- Tid, status, URL, valuta og beløb -----------------------------------

	private Instant parseTimestamp(JsonNode node, String pointer, String field, boolean required, Push push) {
		if (!isPresent(node)) {
			if (required) {
				push.reject(CatalogErrorCode.MISSING_REQUIRED_FIELD, pointer,
						"The field [" + field + "] is required");
			}
			return null;
		}
		String raw = requiredString(node, pointer, field, push);
		if (raw == null) {
			return null;
		}
		try {
			// Med OFFSET og ikke en lokal tid: en tid uden zone er tvetydig præcis
			// den nat, sommertiden skifter, og et event, der starter kl. 02.30, er
			// ikke et hypotetisk problem for et billetsystem.
			OffsetDateTime parsed = OffsetDateTime.parse(raw);
			// Java accepterer år 0000, negative år og år over 9999, men de
			// serialiseres som "+10000-…" og "-0001-…", som Postgres afviser i
			// CAST(... AS timestamptz) midt i batchen -- en 500 i stedet for
			// denne 422 (målt på 18.6). Grænsen er den, tekstformen kan bære.
			if (parsed.getYear() < MIN_YEAR || parsed.getYear() > MAX_YEAR) {
				push.reject(CatalogErrorCode.INVALID_TIMESTAMP, pointer, "The field [" + field
						+ "] must have a year between " + MIN_YEAR + " and " + MAX_YEAR);
				return null;
			}
			return parsed.toInstant();
		}
		catch (DateTimeParseException ex) {
			push.reject(CatalogErrorCode.INVALID_TIMESTAMP, pointer, "The field [" + field
					+ "] must be an ISO 8601 timestamp with an offset, for example 2027-06-30T18:00:00+02:00");
			return null;
		}
	}

	/**
	 * Kundens salgsstatus. En token, vi ikke kender, bliver
	 * {@link SalesStatus#UNKNOWN} plus en advarsel — ikke en afvisning: en kunde,
	 * hvis system har en {@code PRESALE}, skal ikke have hele sit natlige push
	 * afvist for en status, der ikke ændrer noget hos os. UNKNOWN er aldrig "til
	 * salg" og genoptager aldrig ventelisten, så retningen er den sikre.
	 *
	 * <p>Advarslen NAVNGIVER IKKE den rå token. Den er kundens data og ville ende
	 * i vores logs; stien siger allerede præcis, hvor den står, og
	 * {@code SalesStatus.fromWire} logger heller ikke værdien. Ingest-servicen
	 * skriver én samlet WARN pr. push med antallet.
	 */
	private SalesStatus parseSalesStatus(JsonNode node, String pointer, Push push) {
		String raw = requiredString(node, pointer, "salesStatus", push);
		if (raw == null) {
			return null;
		}
		if (!SalesStatus.isRecognised(raw)) {
			push.warn(CatalogErrorCode.UNKNOWN_ENUM_VALUE, pointer,
					"unrecognised sales status at " + pointer + "; stored as UNKNOWN");
		}
		return SalesStatus.fromWire(raw);
	}

	/** Se {@link #HTTPS_URL_REQUIRED} for hvorfor koden er {@code FIELD_TYPE_MISMATCH}. */
	private String parsePurchaseUrl(JsonNode node, String pointer, Push push) {
		if (!isPresent(node)) {
			return null;
		}
		String raw = requiredString(node, pointer, "primaryPurchaseUrl", push);
		if (raw == null) {
			return null;
		}
		if (raw.isBlank()) {
			push.reject(CatalogErrorCode.BLANK_VALUE, pointer, "The field [primaryPurchaseUrl] is blank");
			return null;
		}
		URI parsed;
		try {
			parsed = new URI(raw);
		}
		catch (URISyntaxException ex) {
			push.reject(CatalogErrorCode.FIELD_TYPE_MISMATCH, pointer, HTTPS_URL_REQUIRED);
			return null;
		}
		// ALLOW-liste på ét skema og ikke en spærreliste mod http: en spærreliste
		// er listen over de skemaer, nogen huskede, og javascript: og data: ville
		// slippe igennem.
		//
		// Sammenligningen er ORDRET og med små bogstaver — ikke fordi RFC 3986
		// siger det (den kalder skemaer case-insensitive), men fordi V5's
		// event_purchase_url_format bruger '^https?://\S+$', og Postgres' ~ er
		// case-sensitiv. Et HTTPS:// ville altså slippe gennem politikken her og
		// blive til en 23514 klokken 03. Kolonnen bestemmer, ikke RFC'en.
		if (!raw.startsWith(HTTPS + "://") || parsed.getHost() == null || parsed.getHost().isBlank()) {
			push.reject(CatalogErrorCode.FIELD_TYPE_MISMATCH, pointer, HTTPS_URL_REQUIRED);
			return null;
		}
		// V5's length(primary_purchase_url) tæller hele URL'en i Unicode-tegn,
		// ikke UTF-16-enheder. Både længden og mellemrummet fanges her, hvor de
		// bliver en læsbar 422 — ikke en 23514 klokken 03.
		if (containsWhitespace(raw) || raw.codePointCount(0, raw.length())
				> MAX_PURCHASE_URL_CHARACTERS) {
			push.reject(CatalogErrorCode.FIELD_TYPE_MISMATCH, pointer, HTTPS_URL_REQUIRED
					+ " of at most " + MAX_PURCHASE_URL_CHARACTERS + " characters in total, without whitespace");
			return null;
		}
		return raw;
	}

	/**
	 * Eventets valuta, og samtidig kontrollen af, at pushet ikke modsiger sig
	 * selv.
	 *
	 * <p>To forskellige valutaer i ÉT push afvises her. POLITIKKEN "alle events
	 * hos denne operatør deler valuta" ({@code operator.single_currency}, V4)
	 * håndhæves derimod af {@code CatalogWriteGuard} mod databasen bag
	 * advisory-låsen — parseren ser kun payloaden og kan umuligt vide, hvad der
	 * allerede står i kataloget.
	 */
	private Currency parseEventCurrency(JsonNode node, String pointer, Push push) {
		String raw = requiredString(node, pointer, "currency", push);
		if (raw == null) {
			return null;
		}
		Currency currency;
		try {
			// Money.currencyOf afviser også XXX og XTS: koder uden mindste enhed
			// kan aldrig bære et beløb, og et event i dem kunne aldrig få en
			// billettype læst tilbage.
			currency = Money.currencyOf(raw);
		}
		catch (IllegalArgumentException ex) {
			// Money.currencyOf navngiver koden i sin egen besked, og en overtrædelse
			// må aldrig bære værdien. Derfor en ny besked og ikke en videresendt.
			push.reject(CatalogErrorCode.UNKNOWN_CURRENCY, pointer,
					"The field [currency] must be a known ISO 4217 code that has a minor unit");
			return null;
		}
		if (push.currency == null) {
			push.currency = currency;
		}
		// Kun under enkelt-valuta-politikken: for den internationale kunde er to
		// valutaer i ét push det normale, og guarden mod databasen afgør resten.
		else if (this.singleCurrency && !push.currency.equals(currency)) {
			push.reject(CatalogErrorCode.MULTIPLE_CURRENCIES, pointer,
					"Every event in one push must share a currency; this event is in another currency than the "
							+ "first event of the push");
		}
		return currency;
	}

	/**
	 * Billettypens pålydende: {@code {"amount": <heltal>, "currency": "<ISO>"}}.
	 *
	 * <p>Objektet er den ENESTE form, hvor et beløb uden valuta ikke kan skrives
	 * ned (spec-beslutning #7). Og heltalskontrollen er begge konjunkter:
	 * {@code 300.0} kan konverteres til en {@code long}, og
	 * {@code 92233720368547758070} er et helt tal — kun sammen betyder de "et
	 * heltal, vi kan gemme". Et tal i anførselstegn er en streng og altså heller
	 * ikke et tal. {@code asLong()} coercer og bruges aldrig.
	 */
	private Money parseFaceValue(JsonNode node, String pointer, Currency eventCurrency, Push push) {
		if (!isPresent(node)) {
			push.reject(CatalogErrorCode.MISSING_REQUIRED_FIELD, pointer,
					"The field [faceValue] is required; a ticket type without a price is an error, not a free ticket");
			return null;
		}
		if (!node.isObject()) {
			push.reject(CatalogErrorCode.MONEY_NOT_INTEGRAL, pointer, "The field [faceValue] must be an object "
					+ "carrying a whole number of minor units and its own ISO 4217 currency");
			return null;
		}
		rejectUnknownProperties(node, pointer, FACE_VALUE_PROPERTIES, "a face value", push);
		String amountPointer = pointerFor(pointer, "amount");
		JsonNode amount = node.get("amount");
		Long minorUnits = null;
		if (!isPresent(amount)) {
			push.reject(CatalogErrorCode.MISSING_REQUIRED_FIELD, amountPointer, "The field [amount] is required");
		}
		else if (!amount.isIntegralNumber() || !amount.canConvertToLong()) {
			push.reject(CatalogErrorCode.MONEY_NOT_INTEGRAL, amountPointer, "The field [amount] must be a whole "
					+ "number of minor units that fits in a 64-bit signed integer");
		}
		else {
			minorUnits = amount.longValue();
		}
		Currency currency = parseFaceValueCurrency(node.get("currency"), pointerFor(pointer, "currency"), push);
		if (currency != null && eventCurrency != null && !currency.equals(eventCurrency)) {
			// V5 håndhæver det samme med ticket_type's tre-kolonners fremmednøgle
			// mod (operator_id, id, currency) på event. Her bliver det en 422, der
			// peger på den ene billettype.
			push.reject(CatalogErrorCode.CURRENCY_MISMATCH, pointerFor(pointer, "currency"),
					"A ticket type must be in the same currency as the event it hangs on");
			return null;
		}
		if (minorUnits == null || currency == null) {
			return null;
		}
		if (minorUnits < 0L) {
			// Samme regel som V5's ticket_type_face_value_sane. Koden er
			// FIELD_TYPE_MISMATCH af samme grund som for primaryPurchaseUrl: der
			// findes ingen kode for "et lovligt tal i et ulovligt interval", og en
			// ny kode ville udvide den kontrakt, kunden implementerer imod. Uden
			// kontrollen ville et negativt beløb blive en 23514 klokken 03.
			push.reject(CatalogErrorCode.FIELD_TYPE_MISMATCH, amountPointer,
					"The field [amount] must be zero or positive");
			return null;
		}
		return Money.of(minorUnits, currency);
	}

	private Currency parseFaceValueCurrency(JsonNode node, String pointer, Push push) {
		if (!isPresent(node) || !node.isString()) {
			// Et beløb uden valuta er ikke et beløb — det er et tal, nogen senere
			// gætter en valuta til (CLAUDE.md).
			push.reject(CatalogErrorCode.MONEY_MISSING_CURRENCY, pointer,
					"The field [faceValue] must carry its own ISO 4217 currency code as a string");
			return null;
		}
		String raw = node.stringValue();
		if (rejectNul(raw, pointer, "currency", push)) {
			return null;
		}
		try {
			return Money.currencyOf(raw);
		}
		catch (IllegalArgumentException ex) {
			push.reject(CatalogErrorCode.UNKNOWN_CURRENCY, pointer,
					"The field [faceValue] must carry a known ISO 4217 code that has a minor unit");
			return null;
		}
	}

	// --- Custom felter -------------------------------------------------------

	/**
	 * Entitetens custom felter, holdt op mod operatørens feltskema af den
	 * EKSISTERENDE {@link CustomFieldValidator} — parseren gentager ikke én
	 * eneste af dens regler.
	 *
	 * <p>Objektet er valgfrit, men validatoren kaldes ALLIGEVEL, når det mangler:
	 * {@code required}-deklarationer skal rapporteres, og et fraværende
	 * {@code customFields} er præcis den payload, hvor et pligtigt felt er glemt.
	 *
	 * <p>{@code currencyResolved} er ikke pynt. {@code validateForPush} KASTER en
	 * {@code IllegalStateException}, hvis et {@code money}-felt skal valideres
	 * uden en valuta at måle imod — og det er vores egen programfejl, ikke
	 * kundens. Er eventets valuta allerede afvist, ville et kald hertil forvandle
	 * en 422 til en 500, så feltvalideringen springes over: pushet er afvist i
	 * forvejen, og kunden har allerede fået at vide hvorfor.
	 *
	 * <p>Det serialiserede dokument er {@code JsonNode.toString()} — jsonb
	 * normaliserer nøgle-rækkefølge, whitespace og dubletter på databasesiden
	 * (verificeret), så der sorteres ikke her. Det tomme dokument er ÉT og samme,
	 * uanset om kunden udelod nøglen eller sendte {@code {}}: ellers ville to
	 * stavemåder af "ingen felter" give to forskellige records at sammenligne.
	 *
	 * <p>Også det tomme dokument stemples med skemaets version — som alt andet i
	 * pushet. Stemplet siger, hvilket skema værdierne blev valideret under, og
	 * "ingen værdier" ER valideret (det er dér {@code required} håndhæves).
	 * Storene afviser en batch, hvor et dokuments stempel er uenigt med batchens,
	 * så en version-0 tom record fra {@link CustomFieldValues#empty} ville få
	 * hvert eneste push fra en kunde uden deklarerede felter afvist.
	 */
	private CustomFieldValues parseCustomFields(JsonNode node, String pointer, CustomFieldScope scope,
			Currency entityCurrency, boolean currencyResolved, Push push) {
		if (!currencyResolved) {
			return emptyDocument(scope);
		}
		if (!isPresent(node)) {
			push.rejectAll(this.fieldValidator.validateForPush(scope, pointer, Map.of(), entityCurrency));
			return emptyDocument(scope);
		}
		if (!node.isObject()) {
			push.reject(CatalogErrorCode.FIELD_TYPE_MISMATCH, pointer,
					"The field [customFields] must be a JSON object of declared field keys");
			return emptyDocument(scope);
		}
		Map<String, JsonNode> values = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> field : node.properties()) {
			values.put(field.getKey(), field.getValue());
		}
		List<Violation> violations = this.fieldValidator.validateForPush(scope, pointer, values, entityCurrency);
		push.rejectAll(violations);
		if (!violations.isEmpty() || values.isEmpty()) {
			// Dokumentet bygges kun af et VALIDERET objekt: CustomFieldValues'
			// kompakte konstruktør kaster på et NUL-tegn, og et kast her ville
			// erstatte kundens fulde fejlliste med en 500.
			return emptyDocument(scope);
		}
		return new CustomFieldValues(scope, this.schema.version(), node.toString());
	}

	/**
	 * Det tomme dokument under DETTE skema — ikke {@link CustomFieldValues#empty},
	 * som er version 0 og forbeholdt skeletter fra lazy enrichment, der aldrig er
	 * valideret mod noget skema.
	 */
	private CustomFieldValues emptyDocument(CustomFieldScope scope) {
		return new CustomFieldValues(scope, this.schema.version(), CustomFieldValues.EMPTY_DOCUMENT);
	}

	// --- Fælles kontroller ---------------------------------------------------

	/**
	 * Ukendte nøgler, fundet ved at holde objektets feltnavne op mod et
	 * eksplicit sæt. Det er dét, Jacksons {@code FAIL_ON_UNKNOWN_PROPERTIES}
	 * ville have gjort, hvis den kunne slås til for netop denne flade — se
	 * klassens javadoc.
	 *
	 * <p>NØGLEN navngives (forkortet): den er et navn, ikke en værdi, og uden den
	 * leder kunden efter en stavefejl i 2.000 events.
	 */
	private void rejectUnknownProperties(JsonNode node, String pointer, Set<String> known, String level, Push push) {
		for (String property : node.propertyNames()) {
			if (!known.contains(property)) {
				push.reject(CatalogErrorCode.UNKNOWN_PROPERTY, pointerFor(pointer, property),
						"Unknown property [" + describe(property) + "] on " + level
								+ "; a catalog payload carries no tenant of any kind, and the operator is "
								+ "resolved from the host name alone");
			}
		}
	}

	/**
	 * Batch-grænserne, talt uden at gå payloaden igennem.
	 *
	 * <p>Stien er dokumentets rod for alle tre: grænsen gælder HELE pushet, og en
	 * sti ind i den 2.001. entitet ville antyde, at netop den var forkert. Det er
	 * den ikke — pushet er for stort, og kunden skal chunke det.
	 */
	private void rejectOversizedBatch(JsonNode organizers, Push push) {
		if (organizers.size() > MAX_ORGANIZERS) {
			push.reject(CatalogErrorCode.BATCH_TOO_LARGE, "",
					"One catalog push carries at most " + MAX_ORGANIZERS + " organizers");
		}
		int events = 0;
		int ticketTypes = 0;
		for (JsonNode organizer : organizers) {
			JsonNode eventList = organizer.get("events");
			if (eventList == null || !eventList.isArray()) {
				continue;
			}
			events += eventList.size();
			for (JsonNode event : eventList) {
				JsonNode ticketTypeList = event.get("ticketTypes");
				if (ticketTypeList != null && ticketTypeList.isArray()) {
					ticketTypes += ticketTypeList.size();
				}
			}
		}
		if (events > MAX_EVENTS) {
			push.reject(CatalogErrorCode.BATCH_TOO_LARGE, "",
					"One catalog push carries at most " + MAX_EVENTS + " events");
		}
		if (ticketTypes > MAX_TICKET_TYPES) {
			push.reject(CatalogErrorCode.BATCH_TOO_LARGE, "",
					"One catalog push carries at most " + MAX_TICKET_TYPES + " ticket types");
		}
	}

	/**
	 * En påkrævet streng. En eksplicit JSON-{@code null} tæller som fraværende —
	 * samme regel som {@code CustomFieldValidator}, så de to ikke kan komme fra
	 * hinanden.
	 *
	 * <p>En ref, der slet ikke er en streng, er en TYPEfejl og ikke et
	 * formatproblem: {@code INVALID_REF_FORMAT} siger, at teksten har den
	 * forkerte form, og et tal har ingen form at have forkert.
	 */
	private String requiredString(JsonNode node, String pointer, String field, Push push) {
		if (!isPresent(node)) {
			push.reject(CatalogErrorCode.MISSING_REQUIRED_FIELD, pointer, "The field [" + field + "] is required");
			return null;
		}
		if (!node.isString()) {
			push.reject(CatalogErrorCode.FIELD_TYPE_MISMATCH, pointer, "The field [" + field + "] must be a string");
			return null;
		}
		String value = node.stringValue();
		if (rejectNul(value, pointer, field, push)) {
			return null;
		}
		return value;
	}

	/**
	 * U+0000 hvor som helst i en streng. PostgreSQL kan ikke lagre tegnet, og
	 * driveren afviser det dybt nede uden at navngive rækken — så det fanges her,
	 * FØR det når databasen. Værdien navngives aldrig.
	 *
	 * @return {@code true}, hvis strengen blev afvist
	 */
	private boolean rejectNul(String value, String pointer, String field, Push push) {
		if (value.indexOf(NUL) < 0) {
			return false;
		}
		push.reject(CatalogErrorCode.ILLEGAL_CHARACTER, pointer, "The field [" + field
				+ "] contains a NUL character (U+0000), which PostgreSQL text cannot store");
		return true;
	}

	private static boolean containsWhitespace(String value) {
		for (int index = 0; index < value.length(); index++) {
			if (Character.isWhitespace(value.charAt(index))) {
				return true;
			}
		}
		return false;
	}

	/** Fraværende og eksplicit {@code null} er det samme: kunden sendte ingen værdi. */
	private static boolean isPresent(JsonNode node) {
		return node != null && !node.isNull();
	}

	/**
	 * JSON-Pointer (RFC 6901). {@code ~} og {@code /} escapes, fordi et ukendt
	 * key kommer fra kundens payload og kan indeholde begge — og en sti, der er
	 * forkert escaped, peger et andet sted hen end der, hvor fejlen står.
	 * Rækkefølgen er bærende: {@code ~} FØRST, ellers escapes den {@code ~1}, der
	 * lige er indsat.
	 */
	private static String pointerFor(String base, String segment) {
		// JSON Pointer-escaping af tilde og skraastreg -- og derefter styretegn
		// erstattet: stien ender i en log, og en noegle med et linjeskift maa
		// ikke kunne forfalske den naeste loglinje.
		return base + "/" + withoutControlCharacters(segment.replace("~", "~0").replace("/", "~1"));
	}

	/** Alt under U+0020 samt U+007F bliver til et spoergsmaalstegn, saa en besked aldrig baerer et styretegn. */
	static String withoutControlCharacters(String text) {
		StringBuilder cleaned = new StringBuilder(text.length());
		for (int index = 0; index < text.length(); index++) {
			char character = text.charAt(index);
			cleaned.append(Character.isISOControl(character) ? '?' : character);
		}
		return cleaned.toString();
	}

	/** Hvor meget af en nøgle der må stå i en besked, der ender i en log -- og aldrig et styretegn. */
	private static String describe(String key) {
		String shown = (key.length() <= KEY_CHARACTERS_IN_MESSAGE) ? key
				: key.substring(0, KEY_CHARACTERS_IN_MESSAGE) + "...";
		return withoutControlCharacters(shown);
	}

	/**
	 * Alt, ÉT push samler op undervejs.
	 *
	 * <p>Tilstanden ligger her og ikke på parseren, så den samme parser kan
	 * bruges til to payloads uden at den ene arver den andens dubletter. Sættene
	 * er payload-brede med vilje: {@code UNIQUE (operator_id, ref)} gælder på
	 * tværs af arrangører for events.
	 */
	private static final class Push {

		private final List<Violation> violations = new ArrayList<>();

		private final List<CatalogWarning> warnings = new ArrayList<>();

		private final Set<String> organizerRefs = new HashSet<>();

		private final Set<String> eventRefs = new HashSet<>();

		/** Den første GYLDIGE valuta i pushet. Alle senere events måles mod den. */
		private Currency currency;

		private void reject(CatalogErrorCode code, String pointer, String message) {
			this.violations.add(new Violation(code.name(), pointer, message));
		}

		/**
		 * Overtrædelser fra {@link CustomFieldValidator} — ordret, uden at røre
		 * hverken kode eller sti. Validatoren er den ene kilde til feltreglerne, og
		 * en oversættelse her ville være en anden stavemåde af dem.
		 */
		private void rejectAll(List<Violation> violations) {
			this.violations.addAll(violations);
		}

		private void warn(CatalogErrorCode code, String pointer, String message) {
			this.warnings.add(new CatalogWarning(code, pointer, message));
		}

	}

}

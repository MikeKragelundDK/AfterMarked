package dk.aftermarked.catalog.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import dk.aftermarked.catalog.CatalogValidationException.Violation;
import dk.aftermarked.catalog.fields.CustomFieldDeclaration;
import dk.aftermarked.catalog.fields.CustomFieldSchema;
import dk.aftermarked.catalog.fields.CustomFieldScope;
import dk.aftermarked.money.Money;
import tools.jackson.databind.JsonNode;

/**
 * Typevalidering af custom felter ved ingest (PLAN §2.6).
 *
 * <p><strong>ALLE overtrædelser samles, aldrig den første.</strong> Ét
 * katalog-push er ét alt-eller-intet-kald med op til 2.000 events, og en kunde,
 * der får én fejl ad gangen, skal bruge lige så mange natlige kørsler, som der
 * er fejl. Metoderne returnerer derfor en liste og kaster ikke.
 *
 * <p><strong>TYPEN afgør, aldrig formen på JSON'en.</strong> Både
 * <code>{"da": "Hovedscenen"}</code> og
 * <code>{"amount": 12000, "currency": "DKK"}</code> er JSON-objekter, og formen
 * alene kan ikke sige, hvad de betyder. Kun et felt, der er DEKLARERET
 * translatable — og det kan kun {@code TEXT} og {@code RICHTEXT} være — må stå
 * som et objekt af sprogkoder; alt andet objektformet afvises. Fordi typen er
 * låst i skemaet og aldrig skifter for samme key, er betydningen af en LAGRET
 * værdi kendt også år senere.
 *
 * <p><strong>To indgange, ikke et flag.</strong>
 * {@link #validateForPush(CustomFieldScope, String, Map, Currency)} håndhæver
 * {@code required}; {@link #validateForEnrichment(CustomFieldScope, String, Map,
 * Currency)} gør det ikke. Et lazy enrichment-skelet fødes af en verify-respons
 * fra kundens billetsystem og bærer ikke kundens katalogdata — én
 * {@code required}-deklaration må aldrig gøre en kundes FØRSTE listing umulig.
 * Forskellen er en indgang og ikke en boolean, fordi et vendt flag på et
 * kaldsted er tavst, mens et forkert metodenavn kan læses.
 *
 * <p><strong>Overtrædelsen bærer aldrig værdien.</strong> Kun key'en, stien og
 * en udviklervendt engelsk grund. Værdien kan være persondata eller 20 KB
 * richtext, og den ender i logs; kunden ved fra JSON-Pointeren præcis, hvor i
 * sin egen payload den står. Key'en NAVNGIVES, fordi den er et navn og ikke en
 * værdi — men den forkortes i beskeden, så en misdannet nøgle på 20 KB ikke
 * bliver til en log-oversvømmelse.
 *
 * <p><strong>Validatoren er per operatør og per skema-version</strong> og
 * derfor ikke en bønne: skemaet, basissproget og slug'en hører til den
 * operatør, requesten blev opløst til. Læsesiden
 * ({@link #readStored(CustomFieldScope, JsonNode)}) bygges med
 * {@code FieldSchemaRegistry.schemaAt(slug, row.customFieldsSchemaVersion())},
 * så en værdi skrevet under en ældre version tolkes med DEN versions
 * deklarationer — og beholdes uanset, aldrig kastes på.
 */
public final class CustomFieldValidator {

	// Koderne er kontraktens: taget fra CatalogErrorCode, så en stavefejl her
	// ikke kan give kunden en kode, der ikke findes i kontrakten.
	private static final String UNKNOWN_PROPERTY = CatalogErrorCode.UNKNOWN_PROPERTY.name();

	private static final String BLANK_VALUE = CatalogErrorCode.BLANK_VALUE.name();

	private static final String MONEY_NOT_INTEGRAL = CatalogErrorCode.MONEY_NOT_INTEGRAL.name();

	private static final String MONEY_MISSING_CURRENCY = CatalogErrorCode.MONEY_MISSING_CURRENCY.name();

	private static final String UNKNOWN_CURRENCY = CatalogErrorCode.UNKNOWN_CURRENCY.name();

	private static final String CURRENCY_MISMATCH = CatalogErrorCode.CURRENCY_MISMATCH.name();

	private static final String MISSING_BASE_LOCALE = CatalogErrorCode.MISSING_BASE_LOCALE.name();

	private static final String INVALID_LOCALE_KEY = CatalogErrorCode.INVALID_LOCALE_KEY.name();

	private static final String NOT_TRANSLATABLE = CatalogErrorCode.NOT_TRANSLATABLE.name();

	private static final String UNKNOWN_FIELD_KEY = CatalogErrorCode.UNKNOWN_FIELD_KEY.name();

	private static final String FIELD_WRONG_SCOPE = CatalogErrorCode.FIELD_WRONG_SCOPE.name();

	private static final String FIELD_TYPE_MISMATCH = CatalogErrorCode.FIELD_TYPE_MISMATCH.name();

	private static final String FIELD_REQUIRED = CatalogErrorCode.FIELD_REQUIRED.name();

	private static final String ILLEGAL_CHARACTER = CatalogErrorCode.ILLEGAL_CHARACTER.name();

	/** Præcis samme form som V3's {@code translation_locale_format} og V4's
	 *  {@code operator_default_locale_format}. To steder, der skal være enige om,
	 *  hvad en sprogkode er, skrives ens med vilje. */
	private static final Pattern LOCALE_FORMAT = Pattern.compile("^[a-z]{2}(-[A-Z]{2})?$");

	/** Kun datoen. Et tidspunkt hører i et {@code text}-felt eller i en
	 *  kernekolonne — et {@code date}-felt, der stiltiende tog imod et timestamp,
	 *  ville gemme to forskellige betydninger i samme key. */
	private static final Pattern DATE_FORMAT = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");

	/** Små bogstaver, så to skrivemåder af samme farve ikke bliver to forskellige
	 *  jsonb-værdier og dermed et falsk "ændret" i upsertens IS DISTINCT FROM. */
	private static final Pattern COLOR_FORMAT = Pattern.compile("^#[0-9a-f]{6}$");

	/** Et logo hentet over ren http gør kundens egen https-side til blandet
	 *  indhold, og browseren blokerer billedet uden at fortælle nogen det. */
	private static final Set<String> IMAGE_SCHEMES = Set.of("https");

	private static final Set<String> URL_SCHEMES = Set.of("http", "https");

	private static final Set<String> MONEY_PROPERTIES = Set.of("amount", "currency");

	private static final char NUL = '\0';

	/** Hvor meget af en key der skrives i en fejlbesked. */
	private static final int KEY_CHARACTERS_IN_MESSAGE = 64;

	private final CustomFieldSchema schema;

	private final String baseLocaleTag;

	private final String operatorSlug;

	/**
	 * @param schema operatørens opløste feltskema i den version, der valideres
	 *     under — eller ved læsning: i den version, rækken blev skrevet under
	 * @param baseLocale operatørens eget basissprog ({@code operator.default_locale},
	 *     V4). Det er dét sprog, en oversat værdi SKAL indeholde
	 * @param operatorSlug navngives i afvisningen, så en nyonboardet kunde uden
	 *     egen feltskema-fil får en diagnoserbar fejl frem for en tavs
	 */
	public CustomFieldValidator(CustomFieldSchema schema, Locale baseLocale, String operatorSlug) {
		this.schema = Objects.requireNonNull(schema, "schema");
		Objects.requireNonNull(baseLocale, "baseLocale");
		this.baseLocaleTag = baseLocale.toLanguageTag();
		this.operatorSlug = Objects.requireNonNull(operatorSlug, "operatorSlug");
	}

	/**
	 * Validering af felterne på ÉN entitet i et katalog-push.
	 *
	 * <p>{@code required} håndhæves: pushet er kundens fulde billede af
	 * entiteten, så et manglende pligtigt felt er en fejl, kunden skal rette.
	 *
	 * @param scope hvilken entitet felterne hænger på
	 * @param fieldsPointer JSON-Pointer til selve {@code customFields}-objektet i
	 *     kundens payload, fx {@code /organizers/0/events/1/customFields}. Hver
	 *     overtrædelse peger på {@code <fieldsPointer>/<key>}, så kunden kan gå
	 *     direkte til værdien
	 * @param values feltværdierne, som de står i payloaden
	 * @param entityCurrency entitetens egen valuta, som et {@code money}-felt
	 *     skal stemme med. Må være {@code null} i scope {@code ORGANIZER}, hvor
	 *     {@code money} er ulovligt i forvejen
	 * @return alle overtrædelser, i payloadens egen rækkefølge. Tom hvis intet er galt
	 */
	public List<Violation> validateForPush(CustomFieldScope scope, String fieldsPointer,
			Map<String, JsonNode> values, Currency entityCurrency) {
		return validate(scope, fieldsPointer, values, entityCurrency, true);
	}

	/**
	 * Validering af felterne på et skelet-event fra lazy enrichment.
	 *
	 * <p>Forskellen fra pushet er ÉN regel: {@code required} håndhæves ikke. En
	 * verify-respons fra kundens billetsystem indeholder ikke kundens
	 * katalogdata, og et pligtigt felt, ingen har pushet endnu, ville ellers gøre
	 * den allerførste listing umulig — altså lukke produktet for en kunde, der
	 * endnu ikke har sat sin natlige import op. De felter, der FAKTISK er med,
	 * valideres derimod nøjagtigt som i et push.
	 */
	public List<Violation> validateForEnrichment(CustomFieldScope scope, String fieldsPointer,
			Map<String, JsonNode> values, Currency entityCurrency) {
		return validate(scope, fieldsPointer, values, entityCurrency, false);
	}

	/**
	 * Læsesiden: feltværdierne på en gemt række, parret med deres deklaration.
	 *
	 * <p>Metoden kaster ALDRIG. En værdi skrevet under en ældre skema-version
	 * beholdes og gives tilbage, også når feltet i mellemtiden er trukket
	 * tilbage med {@code until} eller helt fjernet fra config: en manglende
	 * deklaration er et rendering-problem, ikke et datatab, og at kaste på
	 * læsestien ville gøre en gammel række til en side, der ikke kan vises.
	 * Deklarationen kommer med som en {@link Optional}, så kalderen selv kan
	 * vælge at springe et ukendt felt over i visningen.
	 *
	 * <p>Slå op med rækkens EGET {@code custom_fields_schema_version} — se
	 * {@code FieldSchemaRegistry.schemaAt} — ellers læses en gammel værdi med
	 * dagens deklarationer.
	 */
	public List<StoredCustomField> readStored(CustomFieldScope scope, JsonNode storedDocument) {
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(storedDocument, "storedDocument");
		if (!storedDocument.isObject()) {
			// V5's CHECK jsonb_typeof(custom_fields) = 'object' gør det umuligt.
			// Læsestien kaster alligevel ikke: en uventet form er en tom visning,
			// ikke en fejlside.
			return List.of();
		}
		List<StoredCustomField> stored = new ArrayList<>();
		for (Map.Entry<String, JsonNode> entry : storedDocument.properties()) {
			stored.add(new StoredCustomField(entry.getKey(), entry.getValue(),
					this.schema.find(scope, entry.getKey())));
		}
		return List.copyOf(stored);
	}

	private List<Violation> validate(CustomFieldScope scope, String fieldsPointer, Map<String, JsonNode> values,
			Currency entityCurrency, boolean enforceRequired) {
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(fieldsPointer, "fieldsPointer");
		Objects.requireNonNull(values, "values");
		List<Violation> violations = new ArrayList<>();
		for (Map.Entry<String, JsonNode> entry : values.entrySet()) {
			validateOne(scope, fieldsPointer, entry.getKey(), entry.getValue(), entityCurrency, violations);
		}
		if (enforceRequired) {
			for (CustomFieldDeclaration declaration : this.schema.forScope(scope)) {
				if (declaration.required() && !isPresent(values.get(declaration.key()))) {
					violations.add(new Violation(FIELD_REQUIRED, pointerFor(fieldsPointer, declaration.key()),
							"Custom field [" + declaration.key() + "] is required on " + scope.configName()
									+ " and is missing"));
				}
			}
		}
		return List.copyOf(violations);
	}

	private void validateOne(CustomFieldScope scope, String fieldsPointer, String key, JsonNode value,
			Currency entityCurrency, List<Violation> violations) {
		String pointer = pointerFor(fieldsPointer, key);
		// NUL FØRST og alene. Tegnet kan ikke stå i en PostgreSQL text-værdi, og
		// en værdi, der bærer det, skal afvises med ÉN besked der siger hvorfor —
		// ikke drukne i en typefejl, kunden så retter forgæves.
		if (containsNul(key) || containsNul(value)) {
			violations.add(new Violation(ILLEGAL_CHARACTER, pointer, "Custom field [" + describe(key)
					+ "] contains a NUL character (U+0000), which PostgreSQL text cannot store"));
			return;
		}
		Optional<CustomFieldDeclaration> declared = this.schema.find(scope, key);
		if (declared.isEmpty()) {
			violations.add(undeclared(scope, pointer, key));
			return;
		}
		if (!isPresent(value)) {
			// En eksplicit JSON-null betyder "intet at gemme her" og er ikke en
			// fejl i sig selv — et event uden billede sender netop den form. Den
			// tæller derimod ikke som en værdi, så required-kontrollen nedenfor
			// fanger den, hvis feltet er pligtigt.
			return;
		}
		validateValue(declared.get(), value, pointer, entityCurrency, violations);
	}

	/**
	 * Et key, skemaet ikke kender i dette scope. Er det deklareret et ANDET sted,
	 * siges det — ellers ville kunden lede efter en stavefejl, der ikke findes.
	 */
	private Violation undeclared(CustomFieldScope scope, String pointer, String key) {
		for (CustomFieldDeclaration declaration : this.schema.declarations()) {
			if (declaration.key().equals(key)) {
				return new Violation(FIELD_WRONG_SCOPE, pointer,
						"Custom field [" + describe(key) + "] is declared for scope "
								+ declaration.scope().configName() + ", not for " + scope.configName());
			}
		}
		if (this.schema.declarations().isEmpty()) {
			// Den diagnoserbare fejl for en nyonboardet kunde, hvis mappe under
			// operators/ mangler. Opstarts-krydstjekket mod operator-tabellen er
			// udskudt, så beskeden her ER advarslen.
			return new Violation(UNKNOWN_FIELD_KEY, pointer, "Custom field [" + describe(key)
					+ "] is not declared: no field schema declared for operator [" + this.operatorSlug + "]");
		}
		return new Violation(UNKNOWN_FIELD_KEY, pointer, "Custom field [" + describe(key)
				+ "] is not declared for scope " + scope.configName() + " of operator [" + this.operatorSlug + "]");
	}

	private void validateValue(CustomFieldDeclaration declaration, JsonNode value, String pointer,
			Currency entityCurrency, List<Violation> violations) {
		switch (declaration.type()) {
			case TEXT, RICHTEXT -> validateText(declaration, value, pointer, violations);
			case IMAGE -> validateUrl(declaration, value, pointer, IMAGE_SCHEMES, violations);
			case URL -> validateUrl(declaration, value, pointer, URL_SCHEMES, violations);
			case DATE -> validateDate(declaration, value, pointer, violations);
			case BOOL -> validateBool(declaration, value, pointer, violations);
			case ENUM -> validateEnum(declaration, value, pointer, violations);
			case MONEY -> validateMoney(declaration, value, pointer, entityCurrency, violations);
			case COLOR -> validateColor(declaration, value, pointer, violations);
		}
	}

	private void validateText(CustomFieldDeclaration declaration, JsonNode value, String pointer,
			List<Violation> violations) {
		if (value.isObject()) {
			if (!declaration.translatable()) {
				violations.add(new Violation(NOT_TRANSLATABLE, pointer, "Custom field [" + declaration.key()
						+ "] is not declared translatable and must be a single string, not an object of languages"));
				return;
			}
			validateTranslations(declaration, value, pointer, violations);
			return;
		}
		requireNonBlankString(declaration, value, pointer, "a string", violations);
	}

	private void validateTranslations(CustomFieldDeclaration declaration, JsonNode value, String pointer,
			List<Violation> violations) {
		if (value.isEmpty()) {
			violations.add(new Violation(BLANK_VALUE, pointer,
					"Custom field [" + declaration.key() + "] carries no language at all"));
			return;
		}
		boolean baseLanguageSeen = false;
		for (Map.Entry<String, JsonNode> translation : value.properties()) {
			String language = translation.getKey();
			String languagePointer = pointerFor(pointer, language);
			if (!LOCALE_FORMAT.matcher(language).matches()) {
				violations.add(new Violation(INVALID_LOCALE_KEY, languagePointer, "Custom field ["
						+ declaration.key() + "] is keyed by [" + describe(language)
						+ "], which is not a language tag on the form aa or aa-BB"));
				continue;
			}
			if (language.equals(this.baseLocaleTag)) {
				baseLanguageSeen = true;
			}
			requireNonBlankString(declaration, translation.getValue(), languagePointer, "a string", violations);
		}
		if (!baseLanguageSeen) {
			// PLAN §2.9's fallback-kæde ender på grundfeltet på entiteten — og et
			// custom felt HAR ingen grundkolonne. Mangler operatørens basissprog,
			// findes der derfor ingen tekst at falde tilbage på, og feltet står
			// tomt for netop de brugere, kunden selv er dansk for.
			violations.add(new Violation(MISSING_BASE_LOCALE, pointer, "Custom field [" + declaration.key()
					+ "] must carry the operator base language [" + this.baseLocaleTag
					+ "]; a custom field has no base column to fall back to"));
		}
	}

	private void validateUrl(CustomFieldDeclaration declaration, JsonNode value, String pointer,
			Set<String> allowedSchemes, List<Violation> violations) {
		String expected = "an absolute URL whose scheme is one of " + schemeList(allowedSchemes);
		if (!value.isString()) {
			violations.add(typeMismatch(declaration, pointer, expected));
			return;
		}
		URI parsed;
		try {
			parsed = new URI(value.stringValue());
		}
		catch (URISyntaxException ex) {
			violations.add(typeMismatch(declaration, pointer, expected));
			return;
		}
		// ALLOW-liste og ikke en spærreliste mod javascript:. En spærreliste er en
		// liste over de angreb, nogen huskede: data:, vbscript: og enhver
		// stavemåde med blandede store og små bogstaver ville slippe igennem.
		String scheme = (parsed.getScheme() == null) ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
		if (!allowedSchemes.contains(scheme)) {
			violations.add(typeMismatch(declaration, pointer, expected));
			return;
		}
		if (parsed.getHost() == null || parsed.getHost().isBlank()) {
			violations.add(typeMismatch(declaration, pointer, expected + " and a host"));
		}
	}

	private void validateDate(CustomFieldDeclaration declaration, JsonNode value, String pointer,
			List<Violation> violations) {
		String expected = "a calendar date on the form YYYY-MM-DD, never a timestamp";
		if (!value.isString()) {
			violations.add(typeMismatch(declaration, pointer, expected));
			return;
		}
		String raw = value.stringValue();
		if (!DATE_FORMAT.matcher(raw).matches()) {
			violations.add(typeMismatch(declaration, pointer, expected));
			return;
		}
		try {
			LocalDate.parse(raw);
		}
		catch (DateTimeParseException ex) {
			// Formen kan være rigtig og datoen alligevel findes ikke: 2026-02-31.
			violations.add(typeMismatch(declaration, pointer, "a date that exists in the calendar"));
		}
	}

	private void validateBool(CustomFieldDeclaration declaration, JsonNode value, String pointer,
			List<Violation> violations) {
		if (!value.isBoolean()) {
			// Strengen "true" er den klassiske: den er sand i hvert eneste
			// skabelonsprog og siger derfor intet om, hvad kunden mente.
			violations.add(typeMismatch(declaration, pointer, "a JSON boolean, never the string true or false"));
		}
	}

	private void validateEnum(CustomFieldDeclaration declaration, JsonNode value, String pointer,
			List<Violation> violations) {
		if (!value.isString()) {
			violations.add(typeMismatch(declaration, pointer, "one of the declared values, as a string"));
			return;
		}
		if (!declaration.values().contains(value.stringValue())) {
			violations.add(typeMismatch(declaration, pointer, "one of the declared values " + declaration.values()));
		}
	}

	private void validateColor(CustomFieldDeclaration declaration, JsonNode value, String pointer,
			List<Violation> violations) {
		String expected = "a lower-case hex colour on the form #rrggbb, never a CSS function";
		if (!value.isString()) {
			violations.add(typeMismatch(declaration, pointer, expected));
			return;
		}
		if (!COLOR_FORMAT.matcher(value.stringValue()).matches()) {
			violations.add(typeMismatch(declaration, pointer, expected));
		}
	}

	private void validateMoney(CustomFieldDeclaration declaration, JsonNode value, String pointer,
			Currency entityCurrency, List<Violation> violations) {
		if (entityCurrency == null) {
			// Vores egen programfejl og ikke kundens: CustomFieldDeclaration
			// forbyder MONEY uden for EVENT og TICKET_TYPE, og begge kender deres
			// valuta. Et stiltiende spring over ville lægge et uvalideret beløb i
			// en jsonb, som ingen %_minor-skanning kan se.
			throw new IllegalStateException("Custom field [" + declaration.key()
					+ "] of type MONEY cannot be validated without the currency of the entity it hangs on");
		}
		if (!value.isObject()) {
			violations.add(typeMismatch(declaration, pointer, "an object with an amount and a currency"));
			return;
		}
		for (String property : value.propertyNames()) {
			if (!MONEY_PROPERTIES.contains(property)) {
				violations.add(new Violation(UNKNOWN_PROPERTY, pointerFor(pointer, property),
						"Custom field [" + declaration.key() + "] of type MONEY carries the unknown property ["
								+ describe(property) + "]"));
			}
		}
		JsonNode amount = value.get("amount");
		if (!isPresent(amount)) {
			violations.add(typeMismatch(declaration, pointer, "an object with an amount and a currency"));
		}
		else if (!amount.isIntegralNumber() || !amount.canConvertToLong()) {
			// Begge led er nødvendige: 300.0 kan konverteres til long, og
			// 92233720368547758070 er et helt tal. Kun sammen er de "et heltal, vi
			// kan gemme".
			violations.add(new Violation(MONEY_NOT_INTEGRAL, pointerFor(pointer, "amount"),
					"Custom field [" + declaration.key()
							+ "] of type MONEY must be a whole number of minor units that fits in a 64-bit "
							+ "signed integer"));
		}
		JsonNode currency = value.get("currency");
		String currencyPointer = pointerFor(pointer, "currency");
		if (!isPresent(currency) || !currency.isString()) {
			violations.add(new Violation(MONEY_MISSING_CURRENCY, currencyPointer, "Custom field ["
					+ declaration.key() + "] of type MONEY must carry its own ISO 4217 currency code as a string"));
			return;
		}
		Currency declared;
		try {
			declared = Money.currencyOf(currency.stringValue());
		}
		catch (IllegalArgumentException ex) {
			// Money.currencyOf navngiver koden i sin egen besked, og en
			// overtrædelse må aldrig bære værdien. Derfor en ny besked og ikke en
			// videresendt.
			violations.add(new Violation(UNKNOWN_CURRENCY, currencyPointer, "Custom field [" + declaration.key()
					+ "] of type MONEY carries a currency that is not a known ISO 4217 code"));
			return;
		}
		if (!declared.equals(entityCurrency)) {
			violations.add(new Violation(CURRENCY_MISMATCH, currencyPointer, "Custom field [" + declaration.key()
					+ "] of type MONEY must be in the same currency as the event it hangs on"));
		}
	}

	private void requireNonBlankString(CustomFieldDeclaration declaration, JsonNode value, String pointer,
			String expected, List<Violation> violations) {
		if (!value.isString()) {
			violations.add(typeMismatch(declaration, pointer, expected));
			return;
		}
		if (value.stringValue().isBlank()) {
			// En streng af mellemrum er ikke en tekst. V5's CHECK ~ '\S' afviser
			// den på kernekolonnerne; her ville den blive gemt og vist som et
			// tomt felt, kunden tror er udfyldt.
			violations.add(new Violation(BLANK_VALUE, pointer,
					"Custom field [" + declaration.key() + "] is blank"));
		}
	}

	private Violation typeMismatch(CustomFieldDeclaration declaration, String pointer, String expected) {
		return new Violation(FIELD_TYPE_MISMATCH, pointer,
				"Custom field [" + declaration.key() + "] of type " + declaration.type() + " must be " + expected);
	}

	private static boolean isPresent(JsonNode value) {
		return value != null && !value.isNull();
	}

	private static boolean containsNul(JsonNode node) {
		if (node == null) {
			return false;
		}
		if (node.isString()) {
			return containsNul(node.stringValue());
		}
		if (node.isObject()) {
			for (Map.Entry<String, JsonNode> property : node.properties()) {
				if (containsNul(property.getKey()) || containsNul(property.getValue())) {
					return true;
				}
			}
			return false;
		}
		for (JsonNode child : node) {
			if (containsNul(child)) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsNul(String value) {
		return value.indexOf(NUL) >= 0;
	}

	private static String schemeList(Set<String> schemes) {
		return schemes.stream().sorted().toList().toString();
	}

	/**
	 * JSON-Pointer (RFC 6901). {@code ~} og {@code /} escapes, fordi et ukendt
	 * key kommer fra kundens payload og kan indeholde begge — og en sti, der er
	 * forkert escaped, peger et andet sted hen end der, hvor fejlen står.
	 * Rækkefølgen er bærende: {@code ~} FØRST, ellers escapes den {@code ~1}, der
	 * lige er indsat.
	 */
	private static String pointerFor(String base, String segment) {
		return base + "/" + segment.replace("~", "~0").replace("/", "~1");
	}

	private static String describe(String key) {
		// Afkortet OG renset for styretegn: beskeden ender i en log, og den
		// besked, der afviser et ulovligt tegn, må ikke selv bære tegnet.
		return CatalogPayloadParser.withoutControlCharacters((key.length() <= KEY_CHARACTERS_IN_MESSAGE) ? key
				: key.substring(0, KEY_CHARACTERS_IN_MESSAGE) + "...");
	}

	/**
	 * Én gemt feltværdi, som den står i {@code custom_fields}, parret med den
	 * deklaration, der beskriver den — eller {@link Optional#empty()}, hvis
	 * feltet ikke er deklareret i den version, rækken blev skrevet under.
	 *
	 * <p>Værdien gives ubeskåret tilbage i begge tilfælde. Det er hele pointen:
	 * kundens data forsvinder ikke, fordi vores config har flyttet sig.
	 */
	public record StoredCustomField(String key, JsonNode value, Optional<CustomFieldDeclaration> declaration) {

		public StoredCustomField {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(value, "value");
			Objects.requireNonNull(declaration, "declaration");
		}

	}

}

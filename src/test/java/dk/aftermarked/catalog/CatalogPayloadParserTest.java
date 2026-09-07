package dk.aftermarked.catalog;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dk.aftermarked.catalog.CatalogValidationException.Violation;
import dk.aftermarked.catalog.fields.CustomFieldDeclaration;
import dk.aftermarked.catalog.fields.CustomFieldSchema;
import dk.aftermarked.catalog.fields.CustomFieldScope;
import dk.aftermarked.catalog.fields.CustomFieldType;
import dk.aftermarked.catalog.fields.CustomFieldValues;
import dk.aftermarked.catalog.internal.CatalogErrorCode;
import dk.aftermarked.catalog.internal.CatalogPayload;
import dk.aftermarked.catalog.internal.CatalogPayloadParser;
import dk.aftermarked.catalog.internal.CatalogWarning;
import dk.aftermarked.catalog.internal.EventUpsert;
import dk.aftermarked.catalog.internal.OrganizerUpsert;
import dk.aftermarked.catalog.internal.TicketTypeUpsert;
import dk.aftermarked.catalog.internal.TranslatableText;
import dk.aftermarked.money.Money;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kundens katalog-payload, gået igennem i hånden fra en {@code JsonNode}.
 *
 * <p>Ingen database og ingen Spring: parseren er per operatør — skema,
 * basissprog og slug — og validerer HELE payloaden i ren Java, før én række
 * røres. Det er dét, der gør et delvist skrevet katalog umuligt, og det er
 * derfor testen kan være en almindelig enhedstest.
 *
 * <p>Fire ting bevises her, og de er grunden til, at der ikke bindes DTO'er:
 * <ol>
 * <li>streng-eller-objekt på et oversætteligt felt, målt mod OPERATØRENS eget
 *     basissprog og ikke mod husets;</li>
 * <li>JSON-Pointer-stier i hver eneste fejl;</li>
 * <li>ukendte nøgler afvist eksplicit — {@code FAIL_ON_UNKNOWN_PROPERTIES} er
 *     slået fra i Jackson 3 og må ikke slås til globalt;</li>
 * <li>heltalskontrollen på et beløb, som er to konjunkter og ikke ét.</li>
 * </ol>
 *
 * <p>Og gennem det hele: en overtrædelse bærer ALDRIG kundens værdi. Den kan
 * være persondata eller 20 KB richtext, og den ender i logs.
 */
class CatalogPayloadParserTest {

	private static final String PILOT = "event-it";

	/** Operatørens eget basissprog ({@code operator.default_locale}, V4). */
	private static final Locale DANISH = Locale.of("da");

	private static final Currency DKK = Currency.getInstance("DKK");

	private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

	private static final String STARTS_AT = "2027-06-30T18:00:00+02:00";

	private static final String ENDS_AT = "2027-06-30T23:00:00+02:00";

	private static final String ORGANIZER_POINTER = "/organizers/0";

	private static final String EVENT_POINTER = "/organizers/0/events/0";

	private static final String TICKET_TYPE_POINTER = "/organizers/0/events/0/ticketTypes/0";

	/**
	 * U+0000, bygget og ikke skrevet. Et råt NUL-byte i en kildefil får git, grep
	 * og ethvert diff-værktøj til at holde op med at læse filen som tekst — og så
	 * er testen usynlig i netop den gennemgang, den er skrevet til.
	 */
	private static final String NUL_CHARACTER = Character.toString(0);

	// --- Streng eller objekt: den flersprogede importform --------------------

	@Test
	void givenATranslatableFieldAsAString_whenParsed_thenItBecomesTheBaseValueWithNoTranslations() {
		ObjectNode body = payload(organizer("acme", event("summer")));

		CatalogPayload parsed = parser().parse(body);

		assertThat(firstEvent(parsed).title()).isEqualTo(new TranslatableText("Summer Festival", Map.of()));
		assertThat(parsed.warnings()).isEmpty();
	}

	@Test
	void givenATranslatableFieldAsAnObject_whenParsed_thenTheBaseLocaleBecomesTheBaseValueAndTheRestBecomeTranslations() {
		ObjectNode event = event("summer");
		event.set("title", languages("da", "Summer Festival", "en", "Summer Festival in English"));
		ObjectNode body = payload(organizer("acme", event));

		CatalogPayload parsed = parser().parse(body);

		TranslatableText title = firstEvent(parsed).title();
		assertThat(title.base())
				.as("the operator base language becomes the base column the fallback chain ends on")
				.isEqualTo("Summer Festival");
		assertThat(title.byLocale())
				.as("the base language is never repeated as a translation row")
				.doesNotContainKey("da")
				.containsExactly(Map.entry("en", "Summer Festival in English"));
	}

	/**
	 * Uden operatørens basissprog findes der ingen grundtekst at falde tilbage
	 * på: PLAN §2.9's fallback-kæde ender på grundfeltet på entiteten, og
	 * {@code event.title} er NOT NULL i V5.
	 */
	@Test
	void givenATranslatableObjectWithoutTheOperatorBaseLanguage_whenParsed_thenTheImportIsRejected() {
		ObjectNode event = event("summer");
		event.set("title", languages("en", "Summer Festival in English"));

		List<Violation> violations = rejectionOf(parser(), payload(organizer("acme", event)));

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo(CatalogErrorCode.MISSING_BASE_LOCALE.name());
			assertThat(violation.path()).isEqualTo(EVENT_POINTER + "/title");
			assertThat(violation.message()).contains("[da]");
		});
	}

	/**
	 * Præcis grunden til, at {@code operator.default_locale} findes (V4): målt
	 * mod den GLOBALE {@code LocalizationConfig.DEFAULT_LOCALE = da} ville en
	 * svensk kundes fuldt gyldige payload blive afvist for ikke at indeholde
	 * dansk. Sproget er kundens, ikke husets.
	 */
	@Test
	void givenAnOperatorWhoseBaseLanguageIsSwedish_whenParsingASwedishObject_thenItIsAccepted() {
		ObjectNode event = event("summer");
		event.set("title", languages("sv", "Summer Festival in Swedish", "en", "Summer Festival in English"));
		CatalogPayloadParser swedish = new CatalogPayloadParser(CustomFieldSchema.empty(1), Locale.of("sv"), PILOT,
				true);

		CatalogPayload parsed = swedish.parse(payload(organizer("acme", event)));

		assertThat(firstEvent(parsed).title())
				.isEqualTo(new TranslatableText("Summer Festival in Swedish",
						Map.of("en", "Summer Festival in English")));
	}

	@Test
	void givenATranslatableObjectWithAMalformedLocaleKey_whenParsed_thenTheImportIsRejected() {
		ObjectNode event = event("summer");
		event.set("title", languages("da", "Summer Festival", "english", "Summer Festival in English"));

		List<Violation> violations = rejectionOf(parser(), payload(organizer("acme", event)));

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo(CatalogErrorCode.INVALID_LOCALE_KEY.name());
			assertThat(violation.path()).isEqualTo(EVENT_POINTER + "/title/english");
			// Nøglen navngives: den er et NAVN og ikke en værdi, og uden den leder
			// kunden efter en stavefejl i 2.000 events.
			assertThat(violation.message()).contains("english");
		});
	}

	/**
	 * Kunden må gerne ligge foran os. Teksten GEMMES — advarslen siger blot, at
	 * fladen ikke renderer sproget, før bundlen findes
	 * ({@code SupportedLocales.ALL}).
	 */
	@Test
	void givenATranslatableObjectWithAWellFormedButUnsupportedLocale_whenParsed_thenItIsStoredWithAWarning() {
		ObjectNode event = event("summer");
		event.set("title", languages("da", "Summer Festival", "de", "Summer Festival in German"));

		CatalogPayload parsed = parser().parse(payload(organizer("acme", event)));

		assertThat(firstEvent(parsed).title().byLocale())
				.as("a language we do not render yet is still stored")
				.containsEntry("de", "Summer Festival in German");
		assertThat(parsed.warnings()).singleElement().satisfies(warning -> {
			assertThat(warning.code()).isEqualTo(CatalogErrorCode.UNSUPPORTED_LOCALE);
			assertThat(warning.path()).isEqualTo(EVENT_POINTER + "/title/de");
		});
	}

	/**
	 * V5's {@code CHECK (title ~ '\S')} ville afvise den som en 23514 midt i et
	 * natligt push, hvor ingen kan se hvilket event det var. Her bliver den en
	 * læsbar 422 med en sti.
	 */
	@Test
	void givenATranslatableObjectWithAWhitespaceOnlyValue_whenParsed_thenItIsRejectedBeforeTheDatabase() {
		ObjectNode event = event("summer");
		event.set("title", languages("da", "   "));

		List<Violation> violations = rejectionOf(parser(), payload(organizer("acme", event)));

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo(CatalogErrorCode.BLANK_VALUE.name());
			assertThat(violation.path()).isEqualTo(EVENT_POINTER + "/title/da");
		});
		// Nøglen ER set, så den manglende BASE-locale rapporteres ikke oveni: én
		// fejl, én linje. Ellers ville kunden lede efter to problemer.
		assertThat(violations).extracting(Violation::code)
				.doesNotContain(CatalogErrorCode.MISSING_BASE_LOCALE.name());
	}

	/**
	 * PostgreSQL kan ikke lagre U+0000 i en {@code text}- eller
	 * {@code jsonb}-værdi, og driveren afviser tegnet dybt nede uden at navngive
	 * rækken. Derfor fanges det HER, før det når databasen.
	 */
	@Test
	void givenAStringContainingANulCharacter_whenParsed_thenTheImportIsRejected() {
		ObjectNode event = event("summer");
		event.put("title", "Summer" + NUL_CHARACTER + "Festival");

		List<Violation> violations = rejectionOf(parser(), payload(organizer("acme", event)));

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo(CatalogErrorCode.ILLEGAL_CHARACTER.name());
			assertThat(violation.path()).isEqualTo(EVENT_POINTER + "/title");
			assertThat(violation.message()).contains("U+0000");
		});
	}

	/**
	 * Oversættelsen af en billettype ender i {@code translation.entity_type}, og
	 * V3's {@code translation_entity_type_format} afviser camelCase. Parseren
	 * stempler entiteten med sit {@link CustomFieldScope}, og det er DEN, den
	 * skrivende side udleder entitetstypen af — så snake_case er ikke en
	 * konvention, den er en constraint.
	 */
	@Test
	void givenATicketTypeScopedTranslation_whenParsed_thenItsEntityTypeIsSnakeCase() {
		ObjectNode ticketType = ticketType("standard");
		ticketType.set("name", languages("da", "Standard", "en", "Standard in English"));
		ObjectNode event = event("summer");
		event.set("ticketTypes", JSON.arrayNode().add(ticketType));

		CatalogPayload parsed = parser().parse(payload(organizer("acme", event)));

		TicketTypeUpsert first = firstEvent(parsed).ticketTypes().getFirst();
		assertThat(first.name().byLocale()).containsExactly(Map.entry("en", "Standard in English"));
		assertThat(first.customFields().scope()).isEqualTo(CustomFieldScope.TICKET_TYPE);
		assertThat(first.customFields().scope().entityType())
				.as("V3's translation_entity_type_format is ^[a-z][a-z0-9_]{0,61}$ and rejects camelCase")
				.isEqualTo("ticket_type")
				.matches("^[a-z][a-z0-9_]*$");
	}

	// --- Refs ----------------------------------------------------------------

	/**
	 * {@code UNIQUE (operator_id, ref)} gælder på tværs af arrangører, så
	 * dubletsættet er hele pushets. Hvilken af de to der vandt, ville være
	 * tilfældigt.
	 *
	 * <p>⚠ Testnavnet er spec'ens, men ref'en NAVNGIVES ikke i beskeden: en ref
	 * fra wire er kundens værdi og kan være vilkårligt indhold — se
	 * {@code EventRef.isValid}s javadoc. Stien peger på præcis den ene af de to,
	 * kunden skal rette, og det er dét, "naming the ref" kan være uden at lægge
	 * kundens data i vores logs.
	 */
	@Test
	void givenTwoEventsWithTheSameRefInOnePush_whenParsed_thenTheImportIsRejectedPointingAtTheSecondOne() {
		ObjectNode body = payload(organizer("acme", event("summer"), event("summer")));

		List<Violation> violations = rejectionOf(parser(), body);

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo(CatalogErrorCode.DUPLICATE_REF.name());
			assertThat(violation.path()).isEqualTo("/organizers/0/events/1/ref");
			assertThat(violation.message()).doesNotContain("summer");
		});
	}

	/**
	 * V5's CHECK TILLADER {@code _default}, fordi vi selv skriver den række i
	 * lazy enrichment (spec-beslutning #14). Kunne en kunde kræve den, ville alle
	 * skelet-events hænge på en arrangør, kunden pludselig ejede.
	 */
	@Test
	void givenAPushClaimingTheReservedDefaultRef_whenParsed_thenItIsRejected() {
		ObjectNode body = payload(organizer(OrganizerRef.RESERVED_DEFAULT, event("summer")));

		List<Violation> violations = rejectionOf(parser(), body);

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo(CatalogErrorCode.RESERVED_REF.name());
			assertThat(violation.path()).isEqualTo(ORGANIZER_POINTER + "/ref");
		});
	}

	// --- Penge: beløbet er heltal i mindste enhed plus en valuta -------------

	/**
	 * Konsekvensen af at parse et træ frem for at binde en DTO
	 * (spec-beslutning #6): {@code readTree} har ingen måltype, så
	 * {@code ACCEPT_FLOAT_AS_INT} og {@code CoercionConfig} fyrer aldrig på
	 * katalog-stien. Kontrollen skal derfor stå i parseren — og den giver 422 med
	 * en sti, ikke 400.
	 */
	@Test
	void givenAFaceValueWithADecimalAmount_whenParsed_thenItIsRejectedWithMoneyNotIntegralAndItsJsonPointer() {
		ObjectNode ticketType = ticketType("standard");
		ticketType.set("faceValue", faceValue(JSON.numberNode(300.50), "DKK"));
		ObjectNode event = event("summer");
		event.set("ticketTypes", JSON.arrayNode().add(ticketType));

		List<Violation> violations = rejectionOf(parser(), payload(organizer("acme", event)));

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo(CatalogErrorCode.MONEY_NOT_INTEGRAL.name());
			assertThat(violation.path()).isEqualTo(TICKET_TYPE_POINTER + "/faceValue/amount");
			// Beløbet står ALDRIG i beskeden. Stien siger allerede, hvor det er.
			assertThat(violation.message()).doesNotContain("300");
		});
	}

	/**
	 * Det andet konjunkt i heltalskontrollen. {@code 92233720368547758070} ER et
	 * helt tal, så {@code isIntegralNumber()} alene ville lukke det ind — og
	 * {@code asLong()} ville coerce det til {@code Long.MAX_VALUE} uden en lyd.
	 */
	@Test
	void givenAFaceValueWithAnAmountBeyondLong_whenParsed_thenItIsRejected() {
		ObjectNode ticketType = ticketType("standard");
		ticketType.set("faceValue",
				faceValue(JSON.numberNode(new BigInteger("92233720368547758070")), "DKK"));
		ObjectNode event = event("summer");
		event.set("ticketTypes", JSON.arrayNode().add(ticketType));

		List<Violation> violations = rejectionOf(parser(), payload(organizer("acme", event)));

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo(CatalogErrorCode.MONEY_NOT_INTEGRAL.name());
			assertThat(violation.path()).isEqualTo(TICKET_TYPE_POINTER + "/faceValue/amount");
		});
	}

	/**
	 * Et tal i anførselstegn er en streng og altså ikke et tal — og hele
	 * {@code faceValue} som en streng er heller ikke et beløb: objektformen er
	 * den ENESTE, hvor et beløb uden valuta ikke kan skrives ned
	 * (spec-beslutning #7).
	 */
	@Test
	void givenAFaceValueAsAJsonString_whenParsed_thenTheImportIsRejected() {
		ObjectNode quotedAmount = ticketType("standard");
		quotedAmount.set("faceValue", faceValue(JSON.stringNode("30000"), "DKK"));
		ObjectNode wholeValueAsText = ticketType("standard");
		wholeValueAsText.put("faceValue", "30000 DKK");

		List<Violation> quoted = rejectionOf(parser(), payload(organizer("acme", eventWith(quotedAmount))));
		List<Violation> text = rejectionOf(parser(), payload(organizer("acme", eventWith(wholeValueAsText))));

		assertThat(quoted).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo(CatalogErrorCode.MONEY_NOT_INTEGRAL.name());
			assertThat(violation.path()).isEqualTo(TICKET_TYPE_POINTER + "/faceValue/amount");
		});
		assertThat(text).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo(CatalogErrorCode.MONEY_NOT_INTEGRAL.name());
			assertThat(violation.path()).isEqualTo(TICKET_TYPE_POINTER + "/faceValue");
		});
	}

	/** Et beløb uden valuta er ikke et beløb — det er et tal, nogen senere gætter en valuta til. */
	@Test
	void givenAFaceValueWithoutACurrency_whenParsed_thenTheImportIsRejected() {
		ObjectNode ticketType = ticketType("standard");
		ticketType.set("faceValue", JSON.objectNode().put("amount", 30000L));

		List<Violation> violations = rejectionOf(parser(), payload(organizer("acme", eventWith(ticketType))));

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo(CatalogErrorCode.MONEY_MISSING_CURRENCY.name());
			assertThat(violation.path()).isEqualTo(TICKET_TYPE_POINTER + "/faceValue/currency");
		});
	}

	/**
	 * Ét event = én valuta (PLAN §2.5). V5 håndhæver det med billettypens
	 * tre-kolonners fremmednøgle mod {@code (operator_id, id, currency)} — men
	 * dér ville uenigheden være en 23503 klokken 03.
	 */
	@Test
	void givenATicketTypeInAnotherCurrencyThanItsEvent_whenParsed_thenTheImportIsRejected() {
		ObjectNode ticketType = ticketType("standard");
		ticketType.set("faceValue", faceValue(JSON.numberNode(30000L), "EUR"));

		List<Violation> violations = rejectionOf(parser(), payload(organizer("acme", eventWith(ticketType))));

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo(CatalogErrorCode.CURRENCY_MISMATCH.name());
			assertThat(violation.path()).isEqualTo(TICKET_TYPE_POINTER + "/faceValue/currency");
		});
	}

	// --- Ukendte nøgler ------------------------------------------------------

	/**
	 * {@code @JsonIgnoreProperties(ignoreUnknown = false)} gør INTET — det er
	 * annotationens default, og adfærden styres af
	 * {@code FAIL_ON_UNKNOWN_PROPERTIES}, som Jackson 3 leverer slået fra, og som
	 * ikke må slås til globalt. Nøglerne holdes derfor op mod et eksplicit sæt
	 * pr. niveau, og DET er hvad denne test måler.
	 */
	@Test
	void givenAnUnknownProperty_whenParsed_thenTheImportIsRejectedNamingItsPath() {
		ObjectNode event = event("summer");
		event.put("colour", "blue");

		List<Violation> violations = rejectionOf(parser(), payload(organizer("acme", event)));

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo(CatalogErrorCode.UNKNOWN_PROPERTY.name());
			assertThat(violation.path()).isEqualTo(EVENT_POINTER + "/colour");
			assertThat(violation.message()).contains("colour");
		});
	}

	/**
	 * Operatøren resolves UDELUKKENDE fra værtsnavnet (PLAN §2.7 regel 2). Der
	 * findes derfor ikke et niveau i payloaden, hvor et tenant-navn er kendt — og
	 * det følger gratis af de eksplicitte nøglesæt.
	 */
	@Test
	void givenATenantFieldInThePayload_whenParsed_thenItIsRejectedAsAnUnknownProperty() {
		ObjectNode organizer = organizer("acme", event("summer"));
		organizer.put("operatorId", "00000000-0000-4000-8000-000000000001");
		ObjectNode body = payload(organizer);
		body.put("tenant", "someone-else");

		List<Violation> violations = rejectionOf(parser(), body);

		assertThat(violations).extracting(Violation::code)
				.containsExactly(CatalogErrorCode.UNKNOWN_PROPERTY.name(), CatalogErrorCode.UNKNOWN_PROPERTY.name());
		assertThat(violations).extracting(Violation::path)
				.containsExactly("/tenant", ORGANIZER_POINTER + "/operatorId");
	}

	// --- Salgsstatus, tid og grænser -----------------------------------------

	/**
	 * En status, vi ikke kender, må ikke afvise hele kundens natlige push:
	 * {@code UNKNOWN} er aldrig "til salg" og genoptager aldrig ventelisten, så
	 * retningen er den sikre.
	 *
	 * <p>Advarslen NAVNGIVER IKKE den rå token. Den er kundens data og ville ende
	 * i vores logs; stien siger allerede præcis, hvor den står, og
	 * {@code SalesStatus.fromWire} logger heller ikke værdien. Den persisteres
	 * bevidst ikke (spec §7 punkt 5). Spec'ens testnavn sagde det modsatte af
	 * assertionen og er derfor rettet.
	 */
	@Test
	void givenAnUnknownSalesStatus_whenParsed_thenItBecomesUnknownAndAWarningPointsAtItsPath() {
		ObjectNode event = event("summer");
		event.put("salesStatus", "PRESALE");

		CatalogPayload parsed = parser().parse(payload(organizer("acme", event)));

		assertThat(firstEvent(parsed).salesStatus()).isEqualTo(SalesStatus.UNKNOWN);
		assertThat(parsed.warnings()).singleElement().satisfies(warning -> {
			assertThat(warning.code()).isEqualTo(CatalogErrorCode.UNKNOWN_ENUM_VALUE);
			assertThat(warning.path()).isEqualTo(EVENT_POINTER + "/salesStatus");
			assertThat(warning.message()).contains(EVENT_POINTER + "/salesStatus").contains("stored as UNKNOWN");
			assertThat(warning.message()).doesNotContain("PRESALE");
		});
	}

	/** Kunder sender {@code sold_out}. Normaliseringen bor i {@code SalesStatus.fromWire}. */
	@Test
	void givenALowercaseSalesStatus_whenParsed_thenItIsAccepted() {
		ObjectNode event = event("summer");
		event.put("salesStatus", "sold_out");

		CatalogPayload parsed = parser().parse(payload(organizer("acme", event)));

		assertThat(firstEvent(parsed).salesStatus()).isEqualTo(SalesStatus.SOLD_OUT);
		assertThat(parsed.warnings()).isEmpty();
	}

	/** Samme regel som V5's {@code event_ends_after_start}, bare som en læsbar 422. */
	@Test
	void givenAnEventEndingBeforeItStarts_whenParsed_thenTheImportIsRejected() {
		ObjectNode event = event("summer");
		event.put("startsAt", ENDS_AT);
		event.put("endsAt", STARTS_AT);

		List<Violation> violations = rejectionOf(parser(), payload(organizer("acme", event)));

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo(CatalogErrorCode.ENDS_BEFORE_START.name());
			assertThat(violation.path()).isEqualTo(EVENT_POINTER + "/endsAt");
		});
	}

	/**
	 * https-politikken (spec-beslutning #18) ligger i Java, fordi V5's CHECK
	 * bevidst er løsere — en CHECK kan strammes senere, aldrig løsnes.
	 *
	 * <p>{@code HTTPS://} er med, og det er ikke en detalje: RFC 3986 kalder
	 * skemaer case-insensitive, men V5's {@code event_purchase_url_format} er
	 * {@code '^https?://\S{1,2000}$'}, og Postgres' {@code ~} er case-sensitiv.
	 * En politik, der lowercasede før sammenligningen, ville lukke værdien ind og
	 * lade databasen afvise den som en 23514 klokken 03. Kolonnen bestemmer.
	 *
	 * <p>Og {@code javascript:} viser, hvorfor kontrollen er en ALLOW-liste: en
	 * spærreliste er listen over de skemaer, nogen huskede.
	 */
	@Test
	void givenAPurchaseUrlThatIsNotLowercaseHttps_whenParsed_thenItIsRejected() {
		List<String> refused = List.of("http://tickets.example.test/summer",
				"HTTPS://tickets.example.test/summer", "javascript:alert(1)");

		for (String url : refused) {
			ObjectNode event = event("summer");
			event.put("primaryPurchaseUrl", url);

			List<Violation> violations = rejectionOf(parser(), payload(organizer("acme", event)));

			assertThat(violations).as("the URL %s must be refused", url).singleElement().satisfies(violation -> {
				assertThat(violation.code()).isEqualTo(CatalogErrorCode.FIELD_TYPE_MISMATCH.name());
				assertThat(violation.path()).isEqualTo(EVENT_POINTER + "/primaryPurchaseUrl");
				assertThat(violation.message()).isEqualTo("primaryPurchaseUrl must be an absolute https URL");
			});
		}
	}

	/**
	 * Grænsen er ikke høflighed: ét push er én transaktion, og et no-op-upsert
	 * rækkelåser stadig hver konfliktende række til commit, så en afgrænset batch
	 * afgrænser låsevinduet.
	 *
	 * <p>Stien er dokumentets rod, og pushet afvises UDEN at blive gået igennem:
	 * feltfejl i 2.001 events er støj, kunden ikke kan handle på — de skal
	 * chunke, ikke rette.
	 */
	@Test
	void givenAPushOverTheEventLimit_whenParsed_thenTheImportIsRejected() {
		ArrayNode events = JSON.arrayNode();
		for (int index = 0; index <= 2000; index++) {
			events.add(event("event-" + index));
		}
		ObjectNode organizer = organizer("acme");
		organizer.set("events", events);

		List<Violation> violations = rejectionOf(parser(), payload(organizer));

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo(CatalogErrorCode.BATCH_TOO_LARGE.name());
			assertThat(violation.path()).as("the whole push is too large, not one entity in it").isEmpty();
		});
	}

	/**
	 * Hele pointen med at validere i ren Java, før én række røres: ét
	 * katalog-push er ét alt-eller-intet-kald med op til 2.000 events, og en
	 * kunde, der får én fejl ad gangen, skal bruge lige så mange natlige
	 * kørsler, som der er fejl.
	 */
	@Test
	void givenAPayloadWithSeveralErrors_whenParsed_thenAllOfThemAreReportedAtOnce() {
		ObjectNode ticketType = ticketType("standard");
		ticketType.set("faceValue", faceValue(JSON.numberNode(300.50), "DKK"));
		ObjectNode event = eventWith(ticketType);
		event.put("ref", "not a legal ref");
		event.remove("title");

		List<Violation> violations = rejectionOf(parser(), payload(organizer("acme", event)));

		assertThat(violations).extracting(Violation::code).containsExactly(
				CatalogErrorCode.INVALID_REF_FORMAT.name(),
				CatalogErrorCode.MISSING_REQUIRED_FIELD.name(),
				CatalogErrorCode.MONEY_NOT_INTEGRAL.name());
		assertThat(violations).extracting(Violation::path).containsExactly(
				EVENT_POINTER + "/ref",
				EVENT_POINTER + "/title",
				TICKET_TYPE_POINTER + "/faceValue/amount");
	}

	// --- Hele vejen igennem --------------------------------------------------

	/**
	 * Én arrangør, to events, tre billettyper — og hvert eneste felt, som
	 * ingest-servicen skal bruge, sammenlignet som records hele vejen ned.
	 *
	 * <p>Custom felter er med, fordi de er dét, der gør typerne interne
	 * (spec-beslutning #11): dokumentet transporteres som TEKST med sit scope og
	 * sit skema-stempel, og stemplet beskriver VÆRDIERNE — ikke rækken.
	 */
	@Test
	void givenACompleteCatalog_whenParsed_thenEveryFieldBecomesTheExpectedRecord() {
		ObjectNode image = JSON.objectNode().put("eventImage", "https://cdn.example.test/summer.png");
		ObjectNode summer = eventWith(ticketType("standard"), ticketType("vip"));
		summer.set("title", languages("da", "Summer Festival", "en", "Summer Festival in English"));
		summer.put("venue", "Main Stage");
		summer.put("endsAt", ENDS_AT);
		summer.put("salesEndsAt", ENDS_AT);
		summer.put("primaryPurchaseUrl", "https://tickets.example.test/summer");
		summer.set("customFields", image);
		ObjectNode winter = eventWith(ticketType("standard"));
		winter.put("ref", "winter");
		winter.put("title", "Winter Festival");
		winter.put("salesStatus", "sold_out");

		CatalogPayload parsed = parserWith(DANISH, imageDeclaration())
				.parse(payload(organizer("acme", summer, winter)));

		Instant starts = Instant.parse("2027-06-30T16:00:00Z");
		Instant ends = Instant.parse("2027-06-30T21:00:00Z");
		EventUpsert expectedSummer = new EventUpsert(new EventRef("summer"),
				new TranslatableText("Summer Festival", Map.of("en", "Summer Festival in English")),
				TranslatableText.of("Main Stage"), starts, ends, SalesStatus.ON_SALE, ends,
				"https://tickets.example.test/summer", DKK,
				new CustomFieldValues(CustomFieldScope.EVENT, 1,
						"{\"eventImage\":\"https://cdn.example.test/summer.png\"}"),
				List.of(ticketTypeUpsert("standard"), ticketTypeUpsert("vip")));
		EventUpsert expectedWinter = new EventUpsert(new EventRef("winter"),
				TranslatableText.of("Winter Festival"), null, starts, null, SalesStatus.SOLD_OUT, null, null, DKK,
				new CustomFieldValues(CustomFieldScope.EVENT, 1, CustomFieldValues.EMPTY_DOCUMENT), List.of(ticketTypeUpsert("standard")));

		assertThat(parsed).isEqualTo(new CatalogPayload(
				List.of(new OrganizerUpsert(new OrganizerRef("acme"), TranslatableText.of("Acme Events"),
						new CustomFieldValues(CustomFieldScope.ORGANIZER, 1, CustomFieldValues.EMPTY_DOCUMENT),
						List.of(expectedSummer, expectedWinter))),
				List.<CatalogWarning>of(), 1));
		assertThat(parsed.organizers().getFirst().events().getFirst().title().byLocale())
				.as("the base language lives in the base column, never also as a translation row")
				.doesNotContainKey("da");
	}

	// --- Grænser, der ellers ville blive en 500 ------------------------------

	/**
	 * Java parser år 10000 og år 0 uden at blinke, men tekstformen bliver
	 * "+10000-…" og "0000-…", som Postgres afviser midt i batchen. Parseren
	 * skal fange det som en 422 med sti, ikke lade det blive en 500.
	 */
	@Test
	void givenATimestampOutsideTheFourDigitYears_whenParsed_thenItIsRejectedAsAnInvalidTimestamp() {
		ObjectNode tenThousand = event("summer");
		tenThousand.put("startsAt", "+10000-06-30T16:00:00Z");
		ObjectNode yearZero = event("winter");
		yearZero.put("startsAt", "0000-06-30T16:00:00Z");

		List<Violation> violations = rejectionOf(parser(), payload(organizer("acme", tenThousand, yearZero)));

		assertThat(violations).extracting(Violation::code)
				.containsExactly(CatalogErrorCode.INVALID_TIMESTAMP.name(), CatalogErrorCode.INVALID_TIMESTAMP.name());
		assertThat(violations).extracting(Violation::path)
				.containsExactly("/organizers/0/events/0/startsAt", "/organizers/0/events/1/startsAt");
	}

	/**
	 * En nøgle med et linjeskift må hverken nå beskeden eller stien: begge
	 * ender i en log, og et linjeskift dér er en forfalsket næste linje.
	 */
	@Test
	void givenAnUnknownKeyWithALineBreak_whenRejected_thenNeitherMessageNorPathCarriesTheControlCharacter() {
		ObjectNode event = event("summer");
		event.put("foo\nbar", "x");

		List<Violation> violations = rejectionOf(parser(), payload(organizer("acme", event)));

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo(CatalogErrorCode.UNKNOWN_PROPERTY.name());
			assertThat(violation.path()).doesNotContain("\n").contains("foo?bar");
			assertThat(violation.message()).doesNotContain("\n");
		});
	}

	// --- Valuta på tværs af events ------------------------------------------

	/**
	 * Under enkelt-valuta-politikken er to valutaer i ét push en selvmodsigelse,
	 * og den afvises her — før databasen spørges. Stien peger på det ANDET event:
	 * det første satte pushets valuta.
	 */
	@Test
	void givenASingleCurrencyOperator_whenEventsAreInTwoCurrencies_thenTheSecondEventIsRejected() {
		ObjectNode summer = event("summer");
		ObjectNode winter = event("winter");
		winter.put("currency", "EUR");
		winter.set("ticketTypes", JSON.arrayNode());

		List<Violation> violations = rejectionOf(parser(), payload(organizer("acme", summer, winter)));

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo(CatalogErrorCode.MULTIPLE_CURRENCIES.name());
			assertThat(violation.path()).isEqualTo("/organizers/0/events/1/currency");
		});
	}

	/**
	 * Den internationale kunde skal kunne sende ét natligt fuldt dump. Politikken
	 * er data på operatøren, og parseren spejler den: er flaget slået fra, er et
	 * blandet push ikke en selvmodsigelse. Guarden mod databasen afgør resten.
	 */
	@Test
	void givenAMultiCurrencyOperator_whenEventsAreInTwoCurrencies_thenThePushIsAccepted() {
		ObjectNode summer = event("summer");
		ObjectNode winter = event("winter");
		winter.put("currency", "EUR");
		winter.set("ticketTypes", JSON.arrayNode());

		CatalogPayload parsed = multiCurrencyParser().parse(payload(organizer("acme", summer, winter)));

		assertThat(parsed.organizers().getFirst().events()).extracting(EventUpsert::currency)
				.containsExactly(DKK, Money.currencyOf("EUR"));
		assertThat(parsed.warnings()).isEmpty();
	}

	// --- Payload-byggere ------------------------------------------------------

	private static CatalogPayloadParser parser() {
		return new CatalogPayloadParser(CustomFieldSchema.empty(1), DANISH, PILOT, true);
	}

	/** Den internationale kunde: {@code operator.single_currency} slået fra. */
	private static CatalogPayloadParser multiCurrencyParser() {
		return new CatalogPayloadParser(CustomFieldSchema.empty(1), DANISH, PILOT, false);
	}

	private static CatalogPayloadParser parserWith(Locale baseLocale, CustomFieldDeclaration... declarations) {
		return new CatalogPayloadParser(new CustomFieldSchema(1, List.of(declarations)), baseLocale, PILOT, true);
	}

	/** PLAN §2.6's eget eksempel, som pilotkundens egen feltskema-fil erklærer det. */
	private static CustomFieldDeclaration imageDeclaration() {
		return new CustomFieldDeclaration("eventImage", CustomFieldType.IMAGE, CustomFieldScope.EVENT,
				"branding.eventImage", false, false, List.of(), 0, null);
	}

	private static ObjectNode payload(ObjectNode... organizers) {
		ArrayNode list = JSON.arrayNode();
		for (ObjectNode organizer : organizers) {
			list.add(organizer);
		}
		return JSON.objectNode().set("organizers", list);
	}

	private static ObjectNode organizer(String ref, ObjectNode... events) {
		ArrayNode list = JSON.arrayNode();
		for (ObjectNode event : events) {
			list.add(event);
		}
		ObjectNode organizer = JSON.objectNode();
		organizer.put("ref", ref);
		organizer.put("name", "Acme Events");
		organizer.set("events", list);
		return organizer;
	}

	/** Det mindst mulige lovlige event, med én billettype under sig. */
	private static ObjectNode event(String ref) {
		return eventWith(ticketType("standard")).put("ref", ref);
	}

	private static ObjectNode eventWith(ObjectNode... ticketTypes) {
		ArrayNode list = JSON.arrayNode();
		for (ObjectNode ticketType : ticketTypes) {
			list.add(ticketType);
		}
		ObjectNode event = JSON.objectNode();
		event.put("ref", "summer");
		event.put("title", "Summer Festival");
		event.put("startsAt", STARTS_AT);
		event.put("salesStatus", "ON_SALE");
		event.put("currency", "DKK");
		event.set("ticketTypes", list);
		return event;
	}

	private static ObjectNode ticketType(String ref) {
		ObjectNode ticketType = JSON.objectNode();
		ticketType.put("ref", ref);
		ticketType.put("name", "Standard");
		ticketType.set("faceValue", faceValue(JSON.numberNode(30000L), "DKK"));
		return ticketType;
	}

	private static TicketTypeUpsert ticketTypeUpsert(String ref) {
		return new TicketTypeUpsert(new TicketTypeRef(ref), TranslatableText.of("Standard"),
				Money.of(30000L, DKK), new CustomFieldValues(CustomFieldScope.TICKET_TYPE, 1, CustomFieldValues.EMPTY_DOCUMENT));
	}

	private static ObjectNode faceValue(JsonNode amount, String currency) {
		return JSON.objectNode().set("amount", amount).put("currency", currency);
	}

	/** Objektformen af et oversætteligt felt: sprogtag → tekst, i par. */
	private static ObjectNode languages(String... tagsAndTexts) {
		ObjectNode languages = JSON.objectNode();
		for (int index = 0; index < tagsAndTexts.length; index += 2) {
			languages.put(tagsAndTexts[index], tagsAndTexts[index + 1]);
		}
		return languages;
	}

	private static EventUpsert firstEvent(CatalogPayload payload) {
		return payload.organizers().getFirst().events().getFirst();
	}

	/**
	 * Overtrædelserne fra et push, der SKAL afvises.
	 *
	 * <p>Skrevet som en try/catch og ikke som AssertJ's
	 * {@code catchThrowableOfType}, fordi dens to argumenter har byttet plads
	 * mellem AssertJ-versioner — og en test, der ikke kan compile på den næste
	 * opgradering, er en test, nogen sletter.
	 */
	private static List<Violation> rejectionOf(CatalogPayloadParser parser, JsonNode body) {
		try {
			parser.parse(body);
		}
		catch (CatalogValidationException rejection) {
			return rejection.violations();
		}
		throw new AssertionError("The payload was accepted, but this test requires it to be rejected");
	}

}

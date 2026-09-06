package dk.aftermarked.catalog;

import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dk.aftermarked.catalog.CatalogValidationException.Violation;
import dk.aftermarked.catalog.fields.CustomFieldDeclaration;
import dk.aftermarked.catalog.fields.CustomFieldSchema;
import dk.aftermarked.catalog.fields.CustomFieldScope;
import dk.aftermarked.catalog.fields.CustomFieldType;
import dk.aftermarked.catalog.internal.CustomFieldValidator;
import dk.aftermarked.catalog.internal.CustomFieldValidator.StoredCustomField;
import dk.aftermarked.catalog.internal.FieldSchemaProperties;
import dk.aftermarked.catalog.internal.FieldSchemaProperties.FieldDeclarationProperties;
import dk.aftermarked.catalog.internal.FieldSchemaProperties.OperatorFieldSchema;
import dk.aftermarked.catalog.internal.FieldSchemaRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Typevalidering af custom felter ved ingest (PLAN §2.6).
 *
 * <p>To ting bevises her, og de er hinandens forudsætning.
 *
 * <p><strong>1) TYPEN afgør, aldrig formen på JSON'en.</strong> Både
 * <code>{"da": "Hovedscenen"}</code> og
 * <code>{"amount": 12000, "currency": "DKK"}</code> er JSON-objekter. Ville vi
 * læse betydningen ud af formen, ville de to være det samme — og en lagret værdi
 * ville skifte betydning den dag, kunden sendte det andet objekt. Kun feltets
 * deklarerede type siger, hvad der står, og fordi et key aldrig skifter type
 * (se {@code FieldSchemaRegistryTest}), er betydningen af en gemt værdi kendt
 * også år senere.
 *
 * <p><strong>2) ALLE overtrædelser rapporteres på én gang.</strong> Ét
 * katalog-push er ét alt-eller-intet-kald med op til 2.000 events, og en kunde,
 * der får én fejl ad gangen, skal bruge lige så mange natlige kørsler, som der
 * er fejl.
 *
 * <p>Dertil den forskel, ingesten SKAL kalde rigtigt:
 * {@code validateForPush} håndhæver {@code required},
 * {@code validateForEnrichment} gør ikke. Ét pligtigt felt må aldrig gøre en
 * kundes allerførste listing umulig.
 */
class CustomFieldValidationTest {

	private static final String PILOT = "event-it";

	/** Operatørens eget basissprog ({@code operator.default_locale}, V4). */
	private static final Locale BASE_LOCALE = Locale.of("da");

	private static final Currency DKK = Currency.getInstance("DKK");

	private static final String FIELDS_POINTER = "/organizers/0/events/0/customFields";

	private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

	/**
	 * U+0000, bygget og ikke skrevet. Et råt NUL-byte i en kildefil får git, grep
	 * og ethvert diff-værktøj til at holde op med at læse filen som tekst — og så
	 * er testen usynlig i netop den gennemgang, den er skrevet til.
	 */
	private static final String NUL_CHARACTER = Character.toString(0);

	/**
	 * Ét JSON-tal er en forkert form for hver eneste af de ni typer — også for
	 * {@code money}, hvor beløbet skal stå som et objekt med sin egen valuta ved
	 * siden af. Derfor kan reglen "en forkert form afvises og navngiver både
	 * key'en og typen" måles over hele enum'et med samme værdi, i stedet for at
	 * ni håndskrevne tests dækker otte typer og glemmer den niende, nogen
	 * tilføjer senere.
	 */
	@ParameterizedTest
	@EnumSource(CustomFieldType.class)
	void givenAValueOfTheWrongJsonType_whenValidating_thenItIsRejectedNamingTheKeyAndTheType(CustomFieldType type) {
		String key = "someField";
		CustomFieldValidator validator = validatorFor(declaration(key, type, CustomFieldScope.EVENT));

		List<Violation> violations = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values(key, JSON.numberNode(42)), DKK);

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo("FIELD_TYPE_MISMATCH");
			assertThat(violation.path()).isEqualTo(FIELDS_POINTER + "/" + key);
			assertThat(violation.message()).contains(key).contains(type.name());
		});
	}

	@Test
	void givenAnUndeclaredCustomFieldKey_whenValidating_thenItIsRejectedNamingTheKey() {
		CustomFieldValidator validator = validatorFor(
				declaration("eventImage", CustomFieldType.IMAGE, CustomFieldScope.EVENT));

		List<Violation> violations = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("sponsorLogo", JSON.stringNode("https://cdn.example.test/sponsor.png")), DKK);

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo("UNKNOWN_FIELD_KEY");
			assertThat(violation.path()).isEqualTo(FIELDS_POINTER + "/sponsorLogo");
			assertThat(violation.message()).contains("sponsorLogo").contains(PILOT);
		});
	}

	/**
	 * Krydstjekket mod {@code operator}-tabellen ved opstart findes ikke (spec §7
	 * punkt 4): det ville kræve et opslag uden operator-kontekst, og
	 * runtime-rollen har hverken {@code BYPASSRLS} eller en {@code SECURITY
	 * DEFINER}-lister. Afvisningen her ER derfor advarslen — og den navngiver
	 * slug'en, så en nyonboardet kunde uden mappe under {@code operators/} får en
	 * diagnoserbar fejl frem for en tavs.
	 */
	@Test
	void givenAnOperatorWithoutItsOwnFieldSchema_whenValidatingItsFirstPush_thenTheRejectionNamesTheSlug() {
		CustomFieldValidator validator = new CustomFieldValidator(CustomFieldSchema.empty(1), BASE_LOCALE,
				"newly-onboarded");

		List<Violation> violations = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("eventImage", JSON.stringNode("https://cdn.example.test/event.png")), DKK);

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo("UNKNOWN_FIELD_KEY");
			assertThat(violation.message())
					.contains("no field schema declared for operator [newly-onboarded]");
		});
	}

	/**
	 * Nøglerummet ligger på ENTITETEN, så {@code logo} lovligt kan findes både på
	 * arrangøren og på eventet. Et key på den forkerte entitet er derfor ikke en
	 * ukendt nøgle, og beskeden siger, hvor feltet faktisk hører hjemme — ellers
	 * leder kunden efter en stavefejl, der ikke findes.
	 */
	@Test
	void givenAFieldDeclaredForAnotherScope_whenValidating_thenItIsRejected() {
		CustomFieldValidator validator = validatorFor(
				declaration("organizerLogo", CustomFieldType.IMAGE, CustomFieldScope.ORGANIZER));

		List<Violation> violations = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("organizerLogo", JSON.stringNode("https://cdn.example.test/logo.png")), DKK);

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo("FIELD_WRONG_SCOPE");
			assertThat(violation.message()).contains("organizerLogo").contains("organizer").contains("event");
		});
	}

	@Test
	void givenARequiredFieldThatIsMissing_whenValidatingACatalogPush_thenItIsRejected() {
		CustomFieldValidator validator = validatorFor(required("eventImage", CustomFieldType.IMAGE));

		List<Violation> violations = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER, Map.of(), DKK);

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo("FIELD_REQUIRED");
			assertThat(violation.path()).isEqualTo(FIELDS_POINTER + "/eventImage");
			assertThat(violation.message()).contains("eventImage");
		});
	}

	/**
	 * Skelet-eventet fødes af en verify-respons fra kundens billetsystem og bærer
	 * ingen katalogdata. Håndhævede vi {@code required} her, ville én pligtig
	 * deklaration lukke produktet for enhver kunde, der endnu ikke har sat sin
	 * natlige import op — altså gøre den allerførste listing umulig. De felter,
	 * der FAKTISK er med, valideres derimod nøjagtigt som i et push.
	 */
	@Test
	void givenARequiredFieldThatIsMissing_whenCreatingASkeletonEvent_thenItIsAccepted() {
		CustomFieldValidator validator = validatorFor(required("eventImage", CustomFieldType.IMAGE));

		List<Violation> onSkeleton = validator.validateForEnrichment(CustomFieldScope.EVENT, FIELDS_POINTER,
				Map.of(), DKK);
		List<Violation> onABadValue = validator.validateForEnrichment(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("eventImage", JSON.stringNode("http://cdn.example.test/event.png")), DKK);

		assertThat(onSkeleton).isEmpty();
		assertThat(onABadValue).extracting(Violation::code).containsExactly("FIELD_TYPE_MISMATCH");
	}

	/**
	 * Strengen {@code "true"} er den klassiske: den er sand i hvert eneste
	 * skabelonsprog og siger derfor intet om, hvad kunden mente.
	 */
	@Test
	void givenABooleanFieldCarryingTheStringTrue_whenValidating_thenItIsRejected() {
		CustomFieldValidator validator = validatorFor(declaration("familyFriendly", CustomFieldType.BOOL,
				CustomFieldScope.EVENT));

		List<Violation> rejected = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("familyFriendly", JSON.stringNode("true")), DKK);
		List<Violation> accepted = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("familyFriendly", JSON.booleanNode(true)), DKK);

		assertThat(rejected).extracting(Violation::code).containsExactly("FIELD_TYPE_MISMATCH");
		assertThat(accepted).isEmpty();
	}

	/**
	 * {@code 300.0} er fælden: den KAN konverteres til en long, så et tjek på
	 * {@code canConvertToLong()} alene ville lukke den ind — og et beløb, der
	 * ankom som et decimaltal, er et beløb, afsenderen regner i kroner og ikke i
	 * øre. Begge konjunkter er nødvendige.
	 */
	@Test
	void givenAMoneyFieldWithADecimalAmount_whenValidating_thenItIsRejected() {
		CustomFieldValidator validator = validatorFor(declaration("depositAmount", CustomFieldType.MONEY,
				CustomFieldScope.EVENT));

		List<Violation> fractional = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("depositAmount", money(JSON.numberNode(300.5), "DKK")), DKK);
		List<Violation> wholeButDecimal = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("depositAmount", money(JSON.numberNode(300.0), "DKK")), DKK);
		List<Violation> minorUnits = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("depositAmount", money(JSON.numberNode(30000L), "DKK")), DKK);

		assertThat(fractional).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo("MONEY_NOT_INTEGRAL");
			assertThat(violation.path()).isEqualTo(FIELDS_POINTER + "/depositAmount/amount");
		});
		assertThat(wholeButDecimal).extracting(Violation::code).containsExactly("MONEY_NOT_INTEGRAL");
		assertThat(minorUnits).isEmpty();
	}

	/**
	 * Ét event = én valuta (PLAN §2.5). Et beløb i et custom felt er stadig et
	 * beløb, og et beløb uden sin rigtige valuta ved siden af er et tal, ingen
	 * kan regne på — og det ligger i en jsonb, som {@code %_minor}-skanningen
	 * aldrig ser.
	 */
	@Test
	void givenAMoneyFieldInAnotherCurrencyThanTheEvent_whenValidating_thenItIsRejected() {
		CustomFieldValidator validator = validatorFor(declaration("depositAmount", CustomFieldType.MONEY,
				CustomFieldScope.EVENT));

		List<Violation> violations = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("depositAmount", money(JSON.numberNode(30000L), "EUR")), DKK);

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo("CURRENCY_MISMATCH");
			assertThat(violation.path()).isEqualTo(FIELDS_POINTER + "/depositAmount/currency");
		});
	}

	/**
	 * Store bogstaver afvises, fordi to skrivemåder af samme farve ellers bliver
	 * to forskellige jsonb-værdier — og dermed et falsk "ændret" i upsertens
	 * {@code IS DISTINCT FROM}, som ville bumpe {@code version} og skrive en
	 * audit-linje for et push, der intet ændrede.
	 */
	@Test
	void givenAColourFieldInUppercaseHex_whenValidating_thenItIsRejected() {
		CustomFieldValidator validator = validatorFor(declaration("brandColor", CustomFieldType.COLOR,
				CustomFieldScope.EVENT));

		List<Violation> rejected = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("brandColor", JSON.stringNode("#FF00AA")), DKK);
		List<Violation> accepted = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("brandColor", JSON.stringNode("#ff00aa")), DKK);

		assertThat(rejected).extracting(Violation::code).containsExactly("FIELD_TYPE_MISMATCH");
		assertThat(accepted).isEmpty();
	}

	/**
	 * En CSS-funktion er ikke en farve, det er et stykke stylesheet. Kom den ind,
	 * ville kundens egen tekst kunne stå i en {@code style}-attribut på vores
	 * side.
	 */
	@Test
	void givenAColourFieldCarryingACssFunction_whenValidating_thenItIsRejected() {
		CustomFieldValidator validator = validatorFor(declaration("brandColor", CustomFieldType.COLOR,
				CustomFieldScope.EVENT));

		List<Violation> violations = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("brandColor", JSON.stringNode("rgb(255, 0, 170)")), DKK);

		assertThat(violations).extracting(Violation::code).containsExactly("FIELD_TYPE_MISMATCH");
	}

	/**
	 * Allow-liste og ikke en spærreliste mod {@code javascript:}. En spærreliste
	 * er en liste over de angreb, nogen huskede: {@code data:}, {@code vbscript:}
	 * og enhver stavemåde med blandede store og små bogstaver ville slippe
	 * igennem. Derfor prøves BEGGE skrivemåder her.
	 */
	@Test
	void givenAUrlFieldWithAJavascriptScheme_whenValidating_thenItIsRejected() {
		CustomFieldValidator validator = validatorFor(declaration("ticketShopUrl", CustomFieldType.URL,
				CustomFieldScope.EVENT));

		List<Violation> lowercase = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("ticketShopUrl", JSON.stringNode("javascript:alert(1)")), DKK);
		List<Violation> mixedCase = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("ticketShopUrl", JSON.stringNode("JavaScript:alert(1)")), DKK);
		List<Violation> relative = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("ticketShopUrl", JSON.stringNode("/tickets")), DKK);

		assertThat(lowercase).extracting(Violation::code).containsExactly("FIELD_TYPE_MISMATCH");
		assertThat(mixedCase).extracting(Violation::code).containsExactly("FIELD_TYPE_MISMATCH");
		assertThat(relative).extracting(Violation::code).containsExactly("FIELD_TYPE_MISMATCH");
	}

	/**
	 * {@code url} tillader både http og https, {@code image} kun https: et
	 * billede hentet over ren http gør kundens egen https-side til blandet
	 * indhold, og browseren blokerer det uden at fortælle nogen det. Netop dét er
	 * grunden til, at billeder har deres egen type og ikke bare er en url.
	 */
	@Test
	void givenAnImageFieldOverPlainHttp_whenValidating_thenItIsRejected() {
		CustomFieldValidator validator = validatorFor(
				declaration("eventImage", CustomFieldType.IMAGE, CustomFieldScope.EVENT),
				declaration("ticketShopUrl", CustomFieldType.URL, CustomFieldScope.EVENT));

		List<Violation> plainImage = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("eventImage", JSON.stringNode("http://cdn.example.test/event.png")), DKK);
		List<Violation> secureImage = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("eventImage", JSON.stringNode("https://cdn.example.test/event.png")), DKK);
		List<Violation> plainUrl = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("ticketShopUrl", JSON.stringNode("http://shop.example.test/tickets")), DKK);

		assertThat(plainImage).extracting(Violation::code).containsExactly("FIELD_TYPE_MISMATCH");
		assertThat(secureImage).isEmpty();
		assertThat(plainUrl).isEmpty();
	}

	/**
	 * Et {@code date}-felt, der stiltiende tog imod et timestamp, ville gemme to
	 * forskellige betydninger under samme key — og den, der læser værdien om et
	 * år, kan ikke se hvilken. Et tidspunkt hører i en kernekolonne.
	 */
	@Test
	void givenADateFieldCarryingATimestamp_whenValidating_thenItIsRejected() {
		CustomFieldValidator validator = validatorFor(declaration("doorsOpenOn", CustomFieldType.DATE,
				CustomFieldScope.EVENT));

		List<Violation> timestamp = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("doorsOpenOn", JSON.stringNode("2027-07-04T18:00:00Z")), DKK);
		List<Violation> impossible = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("doorsOpenOn", JSON.stringNode("2027-02-31")), DKK);
		List<Violation> accepted = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("doorsOpenOn", JSON.stringNode("2027-07-04")), DKK);

		assertThat(timestamp).extracting(Violation::code).containsExactly("FIELD_TYPE_MISMATCH");
		assertThat(impossible).extracting(Violation::code).containsExactly("FIELD_TYPE_MISMATCH");
		assertThat(accepted).isEmpty();
	}

	@Test
	void givenAnEnumFieldWithAnUndeclaredValue_whenValidating_thenItIsRejected() {
		CustomFieldValidator validator = validatorFor(declaration("stageSize", CustomFieldType.ENUM,
				CustomFieldScope.EVENT));

		List<Violation> undeclared = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("stageSize", JSON.stringNode("MEDIUM")), DKK);
		List<Violation> declared = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("stageSize", JSON.stringNode("SMALL")), DKK);

		assertThat(undeclared).extracting(Violation::code).containsExactly("FIELD_TYPE_MISMATCH");
		assertThat(declared).isEmpty();
	}

	/**
	 * U+0000 kan ikke stå i en PostgreSQL {@code text}-værdi. Fanges det ikke
	 * her, fejler hele transaktionen nede i driveren med en besked, der ikke
	 * navngiver feltet — og et push med 2.000 events afvises uden at kunden kan
	 * se hvorfor. Overtrædelsen står ALENE: tegnet kortslutter resten af
	 * valideringen af netop det felt, så kunden ikke retter en typefejl, der kun
	 * er en følge.
	 */
	@Test
	void givenAValueContainingANulCharacter_whenValidating_thenItIsRejectedBeforeItReachesPostgres() {
		CustomFieldValidator validator = validatorFor(declaration("brandColor", CustomFieldType.COLOR,
				CustomFieldScope.EVENT));

		List<Violation> violations = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("brandColor", JSON.stringNode("#ff00aa" + NUL_CHARACTER)), DKK);

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo("ILLEGAL_CHARACTER");
			assertThat(violation.path()).isEqualTo(FIELDS_POINTER + "/brandColor");
			assertThat(violation.message()).contains("U+0000");
		});
	}

	/**
	 * Begge veje af den samme regel. Et objekt er ikke i sig selv en
	 * oversættelse, og et objekt er ikke i sig selv et beløb: kun deklarationens
	 * type siger, hvad der står. Kunne formen afgøre det, ville
	 * <code>{"amount": …}</code> være en oversættelse til sproget "amount" — og
	 * en gemt værdi ville skifte betydning, når skemaet gjorde.
	 */
	@Test
	void givenAMoneyShapedObjectOnATextFieldAndTheOpposite_whenValidating_thenTheTypeDecidesAndNotTheJsonShape() {
		CustomFieldValidator validator = validatorFor(
				translatable("subtitle", CustomFieldType.TEXT),
				declaration("depositAmount", CustomFieldType.MONEY, CustomFieldScope.EVENT));

		List<Violation> moneyShapeOnText = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("subtitle", money(JSON.numberNode(30000L), "DKK")), DKK);
		List<Violation> textShapeOnMoney = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("depositAmount", languages("da", "Hovedscenen")), DKK);

		assertThat(moneyShapeOnText).extracting(Violation::code)
				.containsExactlyInAnyOrder("INVALID_LOCALE_KEY", "INVALID_LOCALE_KEY", "MISSING_BASE_LOCALE");
		assertThat(textShapeOnMoney).extracting(Violation::code)
				.containsExactlyInAnyOrder("UNKNOWN_PROPERTY", "FIELD_TYPE_MISMATCH", "MONEY_MISSING_CURRENCY");
	}

	/**
	 * PLAN §2.9's fallback-kæde ender på grundfeltet på entiteten — og et custom
	 * felt HAR ingen grundkolonne. Mangler operatørens eget basissprog, findes
	 * der derfor ingen tekst at falde tilbage på, og feltet står tomt for netop
	 * de brugere, kunden selv er dansk for.
	 */
	@Test
	void givenATranslatableFieldWithoutTheOperatorBaseLanguage_whenValidating_thenItIsRejected() {
		CustomFieldValidator validator = validatorFor(translatable("subtitle", CustomFieldType.TEXT));

		List<Violation> withoutBase = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("subtitle", languages("en", "The main stage")), DKK);
		List<Violation> withBase = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("subtitle", languages("da", "Hovedscenen").put("en", "The main stage")), DKK);

		assertThat(withoutBase).extracting(Violation::code).containsExactly("MISSING_BASE_LOCALE");
		assertThat(withBase).isEmpty();
	}

	/**
	 * Kun {@code TEXT} og {@code RICHTEXT} kan overhovedet være oversatte, og kun
	 * hvis de er DEKLARERET til det. Et sprogobjekt på et felt, der ikke er, er
	 * ikke en halvt gyldig værdi — det er en værdi, ingen læseside kan finde
	 * grundteksten i.
	 */
	@Test
	void givenANonTranslatableTextFieldCarryingAnObjectOfLanguages_whenValidating_thenItIsRejected() {
		CustomFieldValidator validator = validatorFor(declaration("subtitle", CustomFieldType.TEXT,
				CustomFieldScope.EVENT));

		List<Violation> violations = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER,
				values("subtitle", languages("da", "Hovedscenen")), DKK);

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.code()).isEqualTo("NOT_TRANSLATABLE");
			assertThat(violation.message()).contains("subtitle");
		});
	}

	/**
	 * Exit-kriteriet for fejlformen. En kunde med fem fejl skal rette dem i én
	 * runde og ikke i fem natlige kørsler — og hver overtrædelse peger på sin
	 * egen JSON-Pointer, så rettelsen kan findes uden at gætte.
	 */
	@Test
	void givenSeveralInvalidFieldsOnOneEntity_whenValidating_thenEveryViolationIsReportedAtOnce() {
		CustomFieldValidator validator = validatorFor(
				declaration("brandColor", CustomFieldType.COLOR, CustomFieldScope.EVENT),
				declaration("doorsOpenOn", CustomFieldType.DATE, CustomFieldScope.EVENT),
				declaration("familyFriendly", CustomFieldType.BOOL, CustomFieldScope.EVENT),
				required("eventImage", CustomFieldType.IMAGE));

		// LinkedHashMap og ikke Map.of: rapporteringen skal foelge payloadens egen
		// raekkefoelge, og en uordnet map ville goere den maaling tilfaeldig.
		Map<String, JsonNode> values = new LinkedHashMap<>();
		values.put("brandColor", JSON.stringNode("rgb(255, 0, 170)"));
		values.put("doorsOpenOn", JSON.stringNode("2027-07-04T18:00:00Z"));
		values.put("familyFriendly", JSON.stringNode("true"));
		values.put("sponsorLogo", JSON.stringNode("https://cdn.example.test/sponsor.png"));

		List<Violation> violations = validator.validateForPush(CustomFieldScope.EVENT, FIELDS_POINTER, values, DKK);

		assertThat(violations).extracting(Violation::path).containsExactly(
				FIELDS_POINTER + "/brandColor",
				FIELDS_POINTER + "/doorsOpenOn",
				FIELDS_POINTER + "/familyFriendly",
				FIELDS_POINTER + "/sponsorLogo",
				FIELDS_POINTER + "/eventImage");
		assertThat(violations).extracting(Violation::code).containsExactly(
				"FIELD_TYPE_MISMATCH", "FIELD_TYPE_MISMATCH", "FIELD_TYPE_MISMATCH",
				"UNKNOWN_FIELD_KEY", "FIELD_REQUIRED");
	}

	/**
	 * Læsesiden kaster ALDRIG. En række bærer selv den skema-version, dens
	 * værdier blev valideret under, og et felt, der er trukket tilbage i dag, har
	 * stadig en kendt type i DEN version. Kastede vi på en ukendt deklaration,
	 * ville en gammel række blive til en side, der ikke kan vises — kundens data
	 * ville reelt være tabt, fordi VORES config flyttede sig.
	 */
	@Test
	void givenAValueWrittenUnderAnOlderSchemaVersion_whenRead_thenItIsKeptAndRendersInsteadOfThrowing() {
		FieldSchemaRegistry registry = new FieldSchemaRegistry(new FieldSchemaProperties(
				new OperatorFieldSchema(0, List.of()),
				Map.of(PILOT, new OperatorFieldSchema(2, List.of(
						new FieldDeclarationProperties("legacyBadge", "text", "event", null, false, false,
								List.of(), 0, 2))))));
		ObjectNode stored = JSON.objectNode().put("legacyBadge", "Early bird");

		List<StoredCustomField> today = new CustomFieldValidator(registry.forOperator(PILOT), BASE_LOCALE, PILOT)
				.readStored(CustomFieldScope.EVENT, stored);
		List<StoredCustomField> asWritten = new CustomFieldValidator(registry.schemaAt(PILOT, 1), BASE_LOCALE, PILOT)
				.readStored(CustomFieldScope.EVENT, stored);

		assertThat(today).singleElement().satisfies(field -> {
			assertThat(field.key()).isEqualTo("legacyBadge");
			assertThat(field.value().stringValue()).isEqualTo("Early bird");
			assertThat(field.declaration()).as("the field is retired today, so it has no declaration").isEmpty();
		});
		assertThat(asWritten).singleElement().satisfies(field -> {
			assertThat(field.value().stringValue()).isEqualTo("Early bird");
			assertThat(field.declaration()).map(CustomFieldDeclaration::type).contains(CustomFieldType.TEXT);
		});
	}

	private static CustomFieldValidator validatorFor(CustomFieldDeclaration... declarations) {
		return new CustomFieldValidator(new CustomFieldSchema(1, List.of(declarations)), BASE_LOCALE, PILOT);
	}

	private static CustomFieldDeclaration declaration(String key, CustomFieldType type, CustomFieldScope scope) {
		return new CustomFieldDeclaration(key, type, scope, null, false, false, enumValuesFor(type), 0, null);
	}

	private static CustomFieldDeclaration required(String key, CustomFieldType type) {
		return new CustomFieldDeclaration(key, type, CustomFieldScope.EVENT, null, true, false,
				enumValuesFor(type), 0, null);
	}

	private static CustomFieldDeclaration translatable(String key, CustomFieldType type) {
		return new CustomFieldDeclaration(key, type, CustomFieldScope.EVENT, null, false, true, List.of(), 0, null);
	}

	/** ENUM skal deklarere mindst én vaerdi; alle andre typer skal lade listen staa tom. */
	private static List<String> enumValuesFor(CustomFieldType type) {
		return (type == CustomFieldType.ENUM) ? List.of("SMALL", "LARGE") : List.of();
	}

	private static Map<String, JsonNode> values(String key, JsonNode value) {
		Map<String, JsonNode> values = new LinkedHashMap<>();
		values.put(key, value);
		return values;
	}

	private static ObjectNode money(JsonNode amount, String currencyCode) {
		return JSON.objectNode().set("amount", amount).put("currency", currencyCode);
	}

	private static ObjectNode languages(String language, String text) {
		return JSON.objectNode().put(language, text);
	}

}

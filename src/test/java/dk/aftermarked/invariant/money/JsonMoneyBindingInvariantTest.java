package dk.aftermarked.invariant.money;

import dk.aftermarked.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INVARIANT — JSON kan ikke ændre et beløb på vej ind (CLAUDE.md, PLAN §2.5).
 * Denne test er spec.
 *
 * <p>Boot 4.1 anvender NUL egne Jackson-defaults, så appen kører på rå
 * Jackson 3-defaults. To af dem forvandler et beløb til et ANDET beløb uden at
 * logge noget:
 *
 * <ul>
 *   <li>{@code ACCEPT_FLOAT_AS_INT = true} — {@code {"amount": 300.50}} bliver
 *       til {@code 300}. Halvtreds øre forsvinder.</li>
 *   <li>{@code ALLOW_COERCION_OF_SCALARS = true} — {@code {"amount": "300"}}
 *       bliver til {@code 300}, så et strengfelt i en integration i stilhed
 *       kan blive til et beløb.</li>
 * </ul>
 *
 * <p><strong>Hvorfor Spring startes.</strong> Det er hele pointen, at testen
 * måler den <em>wirede</em> {@link JsonMapper}-bønne og ikke en mapper, testen
 * selv har bygget. En håndbygget mapper ville bevise, at
 * {@code JsonMapperBuilderCustomizer}-lambdaen virker — ikke at Boot faktisk
 * kalder den. Og netop dét er fælden: en
 * {@code Jackson2ObjectMapperBuilderCustomizer} (Boot 3-vanen) kompilerer,
 * starter og bliver i STILHED ignoreret. Med bønnen er den fejl rød.
 *
 * <p><strong>Den fjerde test er ikke en pyntetest.</strong>
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} er bevidst IKKE slået til globalt: den
 * ville ramme {@code event_publication}-rækker skrevet af en N-1-replika, der
 * kender ét felt mere end den replika, der læser dem — altså præcis den
 * forskudte rollout, hele skemadisciplinen findes for at overleve. Fravalget
 * skal derfor kunne SES, ellers bliver det slået til en dag af en, der synes
 * det lyder strengt og rigtigt.
 *
 * <p>Kontrakt-drift på katalog-stien lukkes et andet sted: dens parser læser en
 * {@code JsonNode} og sammenholder feltnavne med et eksplicit sæt pr. niveau.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JsonMoneyBindingInvariantTest {

	/**
	 * Formen, ethvert fremtidigt beløbs-endpoint har: et heltal i mindste enhed
	 * og en ISO-4217-kode. {@code long} og ikke {@code Long}: et manglende beløb
	 * skal være en fejl og ikke et nul, og {@code FAIL_ON_NULL_FOR_PRIMITIVES}
	 * er true i Jackson 3 (modsat 2.x) — gratis og rigtigt.
	 */
	public record AmountPayload(long amountMinor, String currency) {
	}

	/** En læser, der kender FÆRRE felter end afsenderen sendte — N-1-replikaen. */
	public record CurrencyOnlyView(String currency) {
	}

	static final String AN_INTEGRAL_AMOUNT = """
			{"amountMinor": 300, "currency": "DKK"}
			""";

	static final String A_DECIMAL_AMOUNT = """
			{"amountMinor": 300.50, "currency": "DKK"}
			""";

	static final String A_QUOTED_AMOUNT = """
			{"amountMinor": "300", "currency": "DKK"}
			""";

	static final String A_MISSING_AMOUNT = """
			{"currency": "DKK"}
			""";

	static final String AN_AMOUNT_WITH_AN_EXTRA_FIELD = """
			{"amountMinor": 30000, "currency": "DKK", "note": "written by a newer replica"}
			""";

	@Autowired
	JsonMapper json;

	/**
	 * Den første assertion er ikke overflødig — den er kontrollen. {@code 300}
	 * er PRÆCIS det tal, en trunkering ville have givet, så uden den kunne den
	 * anden assertion være grøn, fordi typen slet ikke kan bindes.
	 */
	@Test
	void givenAJsonBodyWithADecimalAmount_whenBindingIt_thenItIsRejectedRatherThanTruncated() {
		assertThat(json.readValue(AN_INTEGRAL_AMOUNT, AmountPayload.class))
				.as("the reader must be able to bind this shape at all")
				.isEqualTo(new AmountPayload(300L, "DKK"));

		assertThatThrownBy(() -> json.readValue(A_DECIMAL_AMOUNT, AmountPayload.class))
				.as("300.50 must never silently become 300 — that is fifty oere with no log line")
				.isInstanceOf(MismatchedInputException.class);
	}

	@Test
	void givenAJsonBodyWithAQuotedAmount_whenBindingIt_thenItIsRejectedRatherThanCoerced() {
		assertThatThrownBy(() -> json.readValue(A_QUOTED_AMOUNT, AmountPayload.class))
				.as("a string field in an integration must not quietly become an amount")
				.isInstanceOf(MismatchedInputException.class);
	}

	/**
	 * Et manglende beløb er ikke nul. Nul er et gyldigt beløb — sælgergebyret
	 * ved en hand-off er nul — så et felt, der binder til nul, fordi det ikke
	 * blev sendt, er ikke til at skelne fra et, der blev sendt som nul.
	 */
	@Test
	void givenAJsonBodyWithAMissingAmount_whenBindingIt_thenItIsRejected() {
		assertThatThrownBy(() -> json.readValue(A_MISSING_AMOUNT, AmountPayload.class))
				.as("an absent amount must fail, not default to zero")
				.isInstanceOf(MismatchedInputException.class);
	}

	@Test
	void givenARecordWithMoreFieldsThanTheReader_whenDeserialisedWithTheHardenedMapper_thenItStillReadsBack() {
		assertThat(json.readValue(AN_AMOUNT_WITH_AN_EXTRA_FIELD, CurrencyOnlyView.class))
				.as("a replica that knows one field fewer must still be able to read the row")
				.isEqualTo(new CurrencyOnlyView("DKK"));

		assertThat(json.readValue(AN_AMOUNT_WITH_AN_EXTRA_FIELD, AmountPayload.class))
				.as("and the hardening must not have broken the amount itself")
				.isEqualTo(new AmountPayload(30000L, "DKK"));
	}

}

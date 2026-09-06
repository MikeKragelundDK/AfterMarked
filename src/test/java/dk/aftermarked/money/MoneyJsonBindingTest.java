package dk.aftermarked.money;

import dk.aftermarked.JsonConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tester den rigtige beløbstype gennem Boots mapper og appens konfiguration. */
class MoneyJsonBindingTest {

	private final ApplicationContextRunner context = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
			.withUserConfiguration(JsonConfig.class);

	@ParameterizedTest
	@ValueSource(longs = { Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE })
	void givenAnAmountAcrossTheLongRange_whenRoundTrippingJson_thenItsMinorUnitsAndCurrencyArePreserved(long minorUnits) {
		context.run(application -> {
			assertThat(application).hasNotFailed();
			JsonMapper json = application.getBean(JsonMapper.class);
			Money amount = Money.of(minorUnits, "DKK");

			String encoded = json.writeValueAsString(amount);
			JsonNode document = json.readTree(encoded);

			assertThat(document.path("minorUnits").isIntegralNumber()).isTrue();
			assertThat(document.path("minorUnits").longValue()).isEqualTo(minorUnits);
			assertThat(document.path("currency").asString()).isEqualTo("DKK");
			assertThat(json.readValue(encoded, Money.class)).isEqualTo(amount);
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"minorUnits\": 300.50, \"currency\": \"DKK\"}",
			"{\"minorUnits\": \"300\", \"currency\": \"DKK\"}",
			"{\"minorUnits\": true, \"currency\": \"DKK\"}",
			"{\"minorUnits\": null, \"currency\": \"DKK\"}",
			"{\"currency\": \"DKK\"}",
			"{\"minorUnits\": 9223372036854775808, \"currency\": \"DKK\"}",
			"{\"minorUnits\": -9223372036854775809, \"currency\": \"DKK\"}",
			"{\"minorUnits\": 300}",
			"{\"minorUnits\": 300, \"currency\": null}",
			"{\"minorUnits\": 300, \"currency\": \"XXX\"}",
			"{\"minorUnits\": 300, \"currency\": \"XTS\"}",
			"{\"minorUnits\": 300, \"currency\": \"ZZZ\"}"
	})
	void givenAnInvalidAmountOrCurrency_whenBindingMoneyJson_thenItIsRejected(String encoded) {
		context.run(application -> {
			assertThat(application).hasNotFailed();
			JsonMapper json = application.getBean(JsonMapper.class);

			assertThat(json.readValue("{\"minorUnits\": 300, \"currency\": \"DKK\"}", Money.class))
					.isEqualTo(Money.of(300L, "DKK"));
			assertThatThrownBy(() -> json.readValue(encoded, Money.class)).isInstanceOf(JacksonException.class);
		});
	}

}

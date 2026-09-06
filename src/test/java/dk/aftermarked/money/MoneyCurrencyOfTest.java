package dk.aftermarked.money;

import java.util.Currency;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Money#currencyOf} er den ene vej fra en streng til en valuta — og
 * dermed den vej, katalog-parseren giver et EVENT sin valuta ad. Den skal
 * afvise præcis de koder, {@code Money} selv afviser, ellers kan et event ende i
 * en valuta, ingen billettype kan bære.
 */
class MoneyCurrencyOfTest {

	/** Gyldige ISO-4217-koder uden mindste enhed: -1 decimaler i JDK'et. */
	static final List<String> CODES_WITHOUT_A_MINOR_UNIT = List.of("XXX", "XTS", "xxx");

	@Test
	void givenACodeWithoutAMinorUnit_whenResolvingTheCurrency_thenItIsRejectedBeforeItCanBecomeAnEventsCurrency() {
		for (String code : CODES_WITHOUT_A_MINOR_UNIT) {
			assertThatThrownBy(() -> Money.currencyOf(code))
					.as("currencyOf(%s)", code)
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("no minor unit");
		}
	}

	@Test
	void givenACodeWithAMinorUnit_whenResolvingTheCurrency_thenItIsReturnedNormalised() {
		assertThat(Money.currencyOf(" dkk ")).isEqualTo(Currency.getInstance("DKK"));
		assertThat(Money.currencyOf("JPY")).isEqualTo(Currency.getInstance("JPY"));
	}

	@Test
	void givenACurrency_whenAskingWhetherItHasAMinorUnit_thenTheAnswerMatchesTheIsoDecimals() {
		assertThat(Money.hasMinorUnit(Currency.getInstance("DKK"))).isTrue();
		assertThat(Money.hasMinorUnit(Currency.getInstance("JPY"))).as("zero decimals is still a minor unit").isTrue();
		assertThat(Money.hasMinorUnit(Currency.getInstance("XXX"))).isFalse();
		assertThat(Money.hasMinorUnit(Currency.getInstance("XTS"))).isFalse();
	}

}

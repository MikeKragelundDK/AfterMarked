package dk.aftermarked.catalog;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import dk.aftermarked.money.CurrencyMismatchException;
import dk.aftermarked.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventFaceValueTest {

	private static final Currency DKK = Currency.getInstance("DKK");

	@ParameterizedTest
	@ValueSource(longs = { -1L, Long.MIN_VALUE })
	void givenANegativeFaceValue_whenAcceptingIt_thenItIsRejected(long minorUnits) {
		Event event = anEvent();
		Money reported = Money.of(minorUnits, DKK);

		assertThatThrownBy(() -> event.acceptFaceValue(reported))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Verified face value must not be negative");
	}

	@ParameterizedTest
	@ValueSource(longs = { 0L, 1L, Long.MAX_VALUE })
	void givenANonnegativeFaceValue_whenAcceptingIt_thenItsAmountAndEventArePreserved(long minorUnits) {
		Event event = anEvent();
		Money reported = Money.of(minorUnits, DKK);

		VerifiedFaceValue accepted = event.acceptFaceValue(reported);

		assertThat(accepted.amount()).isEqualTo(reported);
		assertThat(accepted.eventId()).isEqualTo(event.id());
	}

	@Test
	void givenANegativeFaceValueInAnotherCurrency_whenAcceptingIt_thenTheCurrencyMismatchRemainsExplicit() {
		Event event = anEvent();
		Currency eur = Currency.getInstance("EUR");

		assertThatThrownBy(() -> event.acceptFaceValue(Money.of(-1L, eur)))
				.isInstanceOfSatisfying(CurrencyMismatchException.class, mismatch -> {
					assertThat(mismatch.expected()).isEqualTo(DKK);
					assertThat(mismatch.actual()).isEqualTo(eur);
				});
	}

	private static Event anEvent() {
		Instant startsAt = Instant.parse("2027-07-01T18:00:00Z");
		return new Event(UUID.randomUUID(), UUID.randomUUID(), new EventRef("summer-2027"), "Summer 2027", null,
				startsAt, null, SalesStatus.UNKNOWN, null, null, DKK, 1, startsAt, startsAt);
	}

}

package dk.aftermarked.catalog;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import dk.aftermarked.catalog.fields.CustomFieldScope;
import dk.aftermarked.catalog.fields.CustomFieldValues;
import dk.aftermarked.catalog.internal.EventJdbcStore.EventRow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Et event kan ikke eksistere i en valuta uden mindste enhed. XXX og XTS er
 * gyldige ISO-4217-koder, som {@code Money} afviser — men eventets valuta går
 * ikke gennem {@code Money}, så reglen skal stå ved eventets egne to døre:
 * aggregatet og push-rækken. Uden den kunne eventet skrives, mens dets
 * billettyper aldrig kunne læses tilbage.
 */
class EventCurrencyTest {

	static final List<String> CODES_WITHOUT_A_MINOR_UNIT = List.of("XXX", "XTS");

	static final Instant STARTS_AT = Instant.parse("2027-07-01T18:00:00Z");

	@Test
	void givenACurrencyWithoutAMinorUnit_whenConstructingAnEvent_thenItIsRejected() {
		for (String code : CODES_WITHOUT_A_MINOR_UNIT) {
			assertThatThrownBy(() -> anEvent(Currency.getInstance(code)))
					.as("Event in %s", code)
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("summer-2027")
					.hasMessageContaining("no minor unit");
		}
	}

	@Test
	void givenACurrencyWithoutAMinorUnit_whenConstructingAnEventRowForPush_thenItIsRejected() {
		for (String code : CODES_WITHOUT_A_MINOR_UNIT) {
			assertThatThrownBy(() -> anEventRow(Currency.getInstance(code)))
					.as("EventRow in %s", code)
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("summer-2027")
					.hasMessageContaining("no minor unit");
		}
	}

	@Test
	void givenACurrencyWithAMinorUnit_whenConstructingAnEventAndItsPushRow_thenBothAreAccepted() {
		Currency jpy = Currency.getInstance("JPY");

		assertThat(anEvent(jpy).currency()).as("zero decimals is still a minor unit").isEqualTo(jpy);
		assertThat(anEventRow(jpy).currency()).isEqualTo(jpy);
	}

	private static Event anEvent(Currency currency) {
		return new Event(UUID.randomUUID(), UUID.randomUUID(), new EventRef("summer-2027"), "Summer 2027", null,
				STARTS_AT, null, SalesStatus.UNKNOWN, null, null, currency, 1, STARTS_AT, STARTS_AT);
	}

	private static EventRow anEventRow(Currency currency) {
		return new EventRow(UUID.randomUUID(), new EventRef("summer-2027"), "Summer 2027", null,
				STARTS_AT, null, SalesStatus.UNKNOWN, null, null, currency,
				CustomFieldValues.empty(CustomFieldScope.EVENT));
	}

}

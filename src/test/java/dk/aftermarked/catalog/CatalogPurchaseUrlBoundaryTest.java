package dk.aftermarked.catalog;

import java.util.Locale;

import dk.aftermarked.catalog.fields.CustomFieldSchema;
import dk.aftermarked.catalog.internal.CatalogErrorCode;
import dk.aftermarked.catalog.internal.CatalogPayload;
import dk.aftermarked.catalog.internal.CatalogPayloadParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Parserens grænse skal svare til V5's length(primary_purchase_url) <= 2000. */
class CatalogPurchaseUrlBoundaryTest {

	private static final String PREFIX = "https://tickets.example.test/";

	private static final String URL_POINTER = "/organizers/0/events/0/primaryPurchaseUrl";

	@ParameterizedTest
	@ValueSource(strings = { "a", "🎟" })
	void givenAPurchaseUrlOfTwoThousandCharacters_whenParsed_thenItIsAccepted(String character) {
		String url = urlOfLength(2000, character);

		CatalogPayload payload = parser().parse(payload(url));

		assertThat(payload.organizers().getFirst().events().getFirst().primaryPurchaseUrl()).isEqualTo(url);
	}

	@ParameterizedTest
	@ValueSource(strings = { "a", "🎟" })
	void givenAPurchaseUrlOfTwoThousandAndOneCharacters_whenParsed_thenItsPointerIsRejected(String character) {
		String url = urlOfLength(2001, character);

		assertThatThrownBy(() -> parser().parse(payload(url)))
				.isInstanceOfSatisfying(CatalogValidationException.class, exception ->
						assertThat(exception.violations()).singleElement().satisfies(violation -> {
							assertThat(violation.code()).isEqualTo(CatalogErrorCode.FIELD_TYPE_MISMATCH.name());
							assertThat(violation.path()).isEqualTo(URL_POINTER);
							assertThat(violation.message()).contains("2000 characters in total").doesNotContain(url);
						}));
	}

	private static CatalogPayloadParser parser() {
		return new CatalogPayloadParser(CustomFieldSchema.empty(1), Locale.of("da"), "event-it", true);
	}

	private static String urlOfLength(int length, String character) {
		return PREFIX + character.repeat(length - PREFIX.length());
	}

	private static ObjectNode payload(String url) {
		ObjectNode body = JsonNodeFactory.instance.objectNode();
		ObjectNode organizer = body.putArray("organizers").addObject();
		organizer.put("ref", "acme");
		organizer.put("name", "Acme");
		ObjectNode event = organizer.putArray("events").addObject();
		event.put("ref", "summer");
		event.put("title", "Summer festival");
		event.put("startsAt", "2027-06-30T16:00:00Z");
		event.put("salesStatus", "ON_SALE");
		event.put("currency", "DKK");
		event.put("primaryPurchaseUrl", url);
		event.putArray("ticketTypes");
		return body;
	}

}

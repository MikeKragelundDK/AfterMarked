package dk.aftermarked.catalog;

import dk.aftermarked.catalog.fields.CustomFieldScope;
import dk.aftermarked.catalog.fields.CustomFieldValues;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CustomFieldValues} er kvitteringen for et valideret feltdokument og
 * går råt ind i batch-upserten. De få formkontroller, den har, skal derfor
 * holde — især NUL-tegnet, som Postgres afviser dybt nede i driveren uden at
 * navngive rækken.
 */
class CustomFieldValuesTest {

	@Test
	void givenADocumentWithANulCharacter_whenConstructingTheValues_thenItIsRejectedWithoutQuotingTheDocument() {
		String document = "{\"note\":\"abc\0def\"}";

		assertThatThrownBy(() -> new CustomFieldValues(CustomFieldScope.EVENT, 1, document))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("NUL character")
				.hasMessageContaining("EVENT")
				.satisfies(ex -> assertThat(ex.getMessage()).as("customer data never ends up in the log")
						.doesNotContain("abc"));
	}

	@Test
	void givenAScalarOrAnArray_whenConstructingTheValues_thenItIsRejected() {
		for (String notAnObject : new String[] { "", "null", "42", "[]", "\"{}\"" }) {
			assertThatThrownBy(() -> new CustomFieldValues(CustomFieldScope.ORGANIZER, 0, notAnObject))
					.as("document %s", notAnObject)
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("must be a JSON object");
		}
	}

	@Test
	void givenTheEmptyDocumentWithWhitespaceAround_whenConstructingTheValues_thenItIsStrippedAndEmpty() {
		CustomFieldValues values = new CustomFieldValues(CustomFieldScope.TICKET_TYPE, 3, "  {}  ");

		assertThat(values.json()).isEqualTo(CustomFieldValues.EMPTY_DOCUMENT);
		assertThat(values.isEmpty()).isTrue();
		assertThat(values.schemaVersion()).isEqualTo(3);
	}

}

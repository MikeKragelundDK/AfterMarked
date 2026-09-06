package dk.aftermarked.invariant.catalog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import dk.aftermarked.catalog.Event;
import dk.aftermarked.catalog.EventRef;
import dk.aftermarked.catalog.SalesStatus;
import dk.aftermarked.catalog.VerifiedFaceValue;
import dk.aftermarked.money.CurrencyMismatchException;
import dk.aftermarked.money.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INVARIANT — face value fra verify er tjekket mod et event, før det kan bruges
 * (SPRINTS S2, invariant nr. 1). Denne test er spec.
 *
 * <p>Reglen er, at prisloftet regnes af det beløb, provideren svarede med
 * (PLAN §2.2) — ikke af katalogets tal, og aldrig af et beløb, hvis valuta ingen
 * har set efter. Det er gjort til en TYPE frem for en kontrol, netop fordi en
 * kontrol kan glemmes: listing-koden i S4 tager en {@link VerifiedFaceValue}, og
 * den kan kun opstå i {@link Event#acceptFaceValue(Money)}.
 *
 * <p>Testen har to halvdele. De tre første beviser adfærden — at valutaen rent
 * faktisk sammenlignes, og at resultatet husker hvilket event det blev
 * sammenlignet med. Den sidste beviser FORMEN: at den forseglede grænseflade
 * stadig har præcis én implementation, at den ikke er public, og at den ligger i
 * {@code catalog}. Uden den ville en ny public implementation i et andet modul
 * kunne stemple et vilkårligt beløb som verificeret, mens de tre første tests
 * blev ved med at være grønne.
 *
 * <p>Ingen Spring og ingen database: alt her er typer og sprogsemantik.
 */
class VerifiedFaceValueInvariantTest {

	static final Currency DKK = Currency.getInstance("DKK");

	static final Currency EUR = Currency.getInstance("EUR");

	static final UUID FIRST_EVENT = UUID.fromString("00000000-0000-4000-8000-00000000f001");

	static final UUID SECOND_EVENT = UUID.fromString("00000000-0000-4000-8000-00000000f002");

	@Test
	void givenAnEventInDkk_whenAVerifyResponseReportsEur_thenTheFaceValueIsRejected() {
		Event event = eventIn(FIRST_EVENT, DKK);
		Money reportedInEuro = Money.of(33_000L, EUR);

		assertThatThrownBy(() -> event.acceptFaceValue(reportedInEuro))
				.isInstanceOfSatisfying(CurrencyMismatchException.class, mismatch -> {
					// Retningen er en del af invarianten: EVENTETS valuta er den
					// forventede, verify-svarets er den, der ikke passede. Byttes
					// de om, peger eskaleringen på den forkerte part.
					assertThat(mismatch.expected()).isEqualTo(DKK);
					assertThat(mismatch.actual()).isEqualTo(EUR);
				});
	}

	@Test
	void givenAnEventInDkk_whenAVerifyResponseReportsDkk_thenItYieldsAVerifiedFaceValue() {
		Event event = eventIn(FIRST_EVENT, DKK);

		VerifiedFaceValue verified = event.acceptFaceValue(Money.of(33_000L, DKK));

		assertThat(verified.amount()).isEqualTo(Money.of(33_000L, DKK));
	}

	@Test
	void givenAVerifiedFaceValue_whenReadingIt_thenItCarriesTheEventItWasCheckedAgainst() {
		Event event = eventIn(FIRST_EVENT, DKK);
		Event otherEvent = eventIn(SECOND_EVENT, DKK);

		VerifiedFaceValue verified = event.acceptFaceValue(Money.of(33_000L, DKK));

		// Typen beviser, at beløbet er tjekket mod ET event — ikke mod DETTE.
		// Med operator.single_currency slået fra kan en operatør have både et
		// DKK- og et EUR-event, og et beløb møntet fra det ene ville ellers
		// tilfredsstille compileren i den andens prisloft. Derfor SKAL
		// forbrugeren i S4 sammenligne, og derfor står id'et på typen.
		assertThat(verified.eventId()).isEqualTo(event.id()).isNotEqualTo(otherEvent.id());
	}

	@Test
	void givenTheVerifiedFaceValueType_whenInspectingItsPermittedSubclasses_thenTheOnlyImplementationIsNonPublicAndLivesInCatalog() {
		assertThat(VerifiedFaceValue.class.isSealed())
				.as("anyone may HOLD a verified face value, only catalog may MINT one")
				.isTrue();

		Class<?>[] permitted = VerifiedFaceValue.class.getPermittedSubclasses();
		assertThat(permitted).hasSize(1);

		Class<?> onlyImplementation = permitted[0];
		assertThat(onlyImplementation.getPackageName()).isEqualTo("dk.aftermarked.catalog");
		assertThat(Modifier.isPublic(onlyImplementation.getModifiers()))
				.as("a public implementation could be built from any module, and the invariant would be gone")
				.isFalse();
		assertThat(onlyImplementation.isRecord()).isTrue();

		// En record kan ikke gøre sin kanoniske konstruktør bredere end klassen
		// selv (JLS §8.10.4) — det er præcis derfor formen er en forseglet
		// grænseflade og ikke en public record med en pakke-privat konstruktør.
		// Her måles det i stedet for at antages.
		for (Constructor<?> constructor : onlyImplementation.getDeclaredConstructors()) {
			assertThat(Modifier.isPublic(constructor.getModifiers()))
					.as("constructor %s must not be reachable from outside catalog", constructor)
					.isFalse();
		}
	}

	/**
	 * Et event, der kun adskiller sig fra det næste på id og valuta. Alt andet er
	 * fyld — det er valutaen og id'et, invarianten handler om.
	 */
	private static Event eventIn(UUID id, Currency currency) {
		Instant startsAt = Instant.parse("2027-07-01T18:00:00Z");
		return new Event(id, UUID.fromString("00000000-0000-4000-8000-00000000a001"),
				new EventRef("main-stage-2027"), "Main Stage 2027", "Main Stage",
				startsAt, startsAt.plusSeconds(14_400L), SalesStatus.SOLD_OUT, null,
				"https://tickets.example.test/main-stage-2027", currency,
				1, Instant.parse("2026-09-01T09:00:00Z"), Instant.parse("2026-09-01T09:00:00Z"));
	}

}

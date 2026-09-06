package dk.aftermarked.catalog;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

import dk.aftermarked.money.CurrencyMismatchException;
import dk.aftermarked.money.Money;

/**
 * Et event i kundens katalog (PLAN §2.2): dét, billetterne hører til, og dét,
 * ventelisten og aftermarkedet hænger på. Rækken er et spejl af kundens egen
 * data.
 *
 * <p><strong>Recorden bærer med vilje INGEN {@code operatorId}.</strong> Tabellen
 * har kolonnen, men ingen INSERT binder den som parameter — værdien skrives
 * ordret som {@code current_operator_id()} i SQL'en, så der ikke findes en
 * kodesti, der KAN bære et forkert tenant-id ind i en indsættelse. Skema-testen
 * SUBTRAHERER {@code operator_id} fra tabellens kolonner og lægger det aldrig
 * til det forventede sæt: en fejlende skema-test må ALDRIG "rettes" ved at
 * tilføje feltet her.
 *
 * <p><strong>Recorden bærer med vilje heller INGEN custom felter.</strong>
 * {@code custom_fields} og {@code custom_fields_schema_version} læses kun af
 * {@code catalog.internal}. Lå de her, kunne orders, payments og marketplace
 * læse feltværdierne gennem den unavngivne grænseflade, uden at ArchUnit-reglen
 * på {@code catalog.fields} nogensinde fyrede (PLAN §2.6, DoD punkt 4). Custom
 * felter er præsentation og aldrig adfærd — derfor er {@code primaryPurchaseUrl}
 * en KERNEkolonne og ikke et felt: ventelisten afhænger af den (PLAN §2.6/§2.2),
 * og et adfærdsbærende felt kan ikke ligge i feltværdi-lageret.
 *
 * <p>{@code title} er NOT NULL, også på et skelet-event fra lazy enrichment: så
 * er titlen eventets egen ref, indtil første katalog-push retter den.
 * Fallback-kædens sidste trin er grundfeltet på entiteten (PLAN §2.9), og et
 * tomt grundfelt gør kæden uskrivelig. {@code venue}, {@code endsAt},
 * {@code salesEndsAt} og {@code primaryPurchaseUrl} er derimod ægte valgfrie —
 * de er nullable i skemaet og nullable her.
 *
 * <p>{@code currency} er EVENTETS: ét event = én valuta (PLAN §2.5). Alle beløb
 * på eventets billettyper deler den, håndhævet af en sammensat fremmednøgle mod
 * {@code (operator_id, id, currency)}. At alle events hos ÉN operatør deler
 * valuta er derimod politik og ligger i {@code operator.single_currency}.
 *
 * <p>Format- og rækkefølgeregler — blank titel, {@code ends_at >= starts_at},
 * URL'ens form — håndhæves af V5's CHECK-constraints og af parserens 422, og er
 * med vilje IKKE gentaget i den kompakte konstruktør. Gentog vi dem, kunne de
 * tests, der beviser at constraints'ne virker, ikke længere føre en dårlig værdi
 * ned til databasen gennem aggregatet.
 */
public record Event(UUID id, UUID organizerId, EventRef ref, String title, String venue,
		Instant startsAt, Instant endsAt, SalesStatus salesStatus, Instant salesEndsAt,
		String primaryPurchaseUrl, Currency currency,
		int version, Instant createdAt, Instant updatedAt) {

	public Event {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(organizerId, "organizerId");
		Objects.requireNonNull(ref, "ref");
		Objects.requireNonNull(title, "title");
		Objects.requireNonNull(startsAt, "startsAt");
		// salesStatus er aldrig null: kolonnen er NOT NULL, og UNKNOWN er den
		// neutrale værdi, et event uden kendt status har.
		Objects.requireNonNull(salesStatus, "salesStatus");
		Objects.requireNonNull(currency, "currency");
		Objects.requireNonNull(createdAt, "createdAt");
		Objects.requireNonNull(updatedAt, "updatedAt");
		// Samme regel som Money: XXX og XTS er gyldige ISO-koder uden mindste
		// enhed. Et event i en sådan valuta kunne skrives, men dets billettyper
		// kunne aldrig læses tilbage (MoneyColumns.read kaster), og
		// acceptFaceValue ville altid fejle. Reglen er IKKE en af V5's
		// CHECK-constraints — SQL kender ikke ISO-4217's decimaler — så den hører
		// her og er ikke en gentagelse af skemaet.
		if (!Money.hasMinorUnit(currency)) {
			throw new IllegalArgumentException("Event [" + ref.value() + "] is in currency "
					+ currency.getCurrencyCode() + ", which has no minor unit and can never carry a face value");
		}
		// venue, endsAt, salesEndsAt og primaryPurchaseUrl er bevidst UDEN
		// null-guard: de er nullable i skemaet, og et skelet-event fra lazy
		// enrichment har hverken slutdato eller salgs-URL.
		//
		// Postgres' timestamptz gemmer MIKROsekunder, og pgjdbc runder HALV-OP.
		// Trunkeringen hører her og ikke i testen: sker den i testen, er
		// round-trip-testen ca. 50 % flaky — målt til 9.980 uenigheder ud af
		// 20.000 round-trips på både Windows og CI.
		startsAt = startsAt.truncatedTo(ChronoUnit.MICROS);
		endsAt = truncatedToMicros(endsAt);
		salesEndsAt = truncatedToMicros(salesEndsAt);
		createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
		updatedAt = updatedAt.truncatedTo(ChronoUnit.MICROS);
		// Optimistisk låsning er en version-kolonne og WHERE version = ? (PLAN §3).
		if (version < 1) {
			throw new IllegalArgumentException("Event version must be at least 1 but was " + version);
		}
	}

	/**
	 * Face value fra et verify-svar, bundet til DETTE event.
	 *
	 * <p>Dette er invariant nr. 1 gjort til en type. Listing-koden (S4) tager en
	 * {@link VerifiedFaceValue} og ALDRIG et {@link Money} — og en
	 * {@code VerifiedFaceValue} kan kun laves her. Compileren kræver altså, at
	 * beløbet er gået gennem et event, og kontrollen kan ikke glemmes.
	 *
	 * <p>Face value fra verify er autoriteten for prisloftet på den konkrete
	 * billet; katalogets eget tal er visning og default og kan aldrig overskrive
	 * det (PLAN §2.2).
	 *
	 * <p><strong>Forbrugeren skal stadig sammenligne {@link VerifiedFaceValue#eventId()}
	 * med sit eget event-id.</strong> Typen beviser, at beløbet er tjekket mod ET
	 * event — ikke mod DETTE. Med {@code single_currency} slået fra kan en
	 * operatør have både et DKK- og et EUR-event, og en VerifiedFaceValue møntet
	 * fra det ene ville tilfredsstille compileren i den andens prisloft.
	 *
	 * @param reported beløbet, verify svarede med
	 * @return beløbet, mærket med det event det er tjekket imod
	 * @throws CurrencyMismatchException hvis verify svarer i en anden valuta end
	 *     eventets. Det er en pengefejl og ikke dårligt input: den skal eskalere,
	 *     ikke retry'es og ikke logges og fortsættes
	 * @throws IllegalArgumentException hvis verify svarer med et negativt pålydende
	 */
	public VerifiedFaceValue acceptFaceValue(Money reported) {
		Objects.requireNonNull(reported, "reported");
		if (!this.currency.equals(reported.currency())) {
			throw new CurrencyMismatchException(this.currency, reported.currency());
		}
		// Money tillader negative beløb til refusioner, men en billets pålydende
		// kan ikke være negativt. Verify-beløbet går ikke gennem katalogets
		// face_value_minor >= 0-constraint, før det bruges som prisloft.
		if (reported.isNegative()) {
			throw new IllegalArgumentException("Verified face value must not be negative");
		}
		return new CatalogVerifiedFaceValue(this.id, reported);
	}

	/** {@code null} betyder "ikke sat" og skal blive ved med at gøre det. */
	private static Instant truncatedToMicros(Instant value) {
		return (value == null) ? null : value.truncatedTo(ChronoUnit.MICROS);
	}

}

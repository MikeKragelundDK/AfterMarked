package dk.aftermarked.catalog;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

import dk.aftermarked.money.Money;

/**
 * En billettype på et event (PLAN §2.2): navnet og prisen, kunden selv sælger
 * til. Rækken er et spejl af kundens egen data.
 *
 * <p><strong>Recorden bærer med vilje INGEN {@code operatorId}.</strong> Tabellen
 * har kolonnen, men ingen INSERT binder den som parameter — værdien skrives
 * ordret som {@code current_operator_id()} i SQL'en. Skema-testen SUBTRAHERER
 * {@code operator_id} fra tabellens kolonner og lægger det aldrig til det
 * forventede sæt: en fejlende skema-test må ALDRIG "rettes" ved at tilføje
 * feltet her.
 *
 * <p><strong>Recorden bærer med vilje heller INGEN custom felter.</strong>
 * {@code custom_fields} og {@code custom_fields_schema_version} læses kun af
 * {@code catalog.internal}; lå de her, kunne orders, payments og marketplace nå
 * feltværdierne gennem den unavngivne grænseflade, uden at ArchUnit-reglen på
 * {@code catalog.fields} nogensinde fyrede (PLAN §2.6, DoD punkt 4).
 *
 * <p>{@code faceValue} er ét {@link Money} og fylder TO kolonner:
 * {@code face_value_minor} og {@code currency}. Valutakolonnen er RÆKKENS og
 * ikke beløbets — ét event = én valuta (PLAN §2.5), håndhævet af en sammensat
 * fremmednøgle mod {@code (operator_id, id, currency)} i {@code event}. En
 * billettype i EUR på et DKK-event kan derfor ikke gemmes, og eventets valuta
 * kan ikke ændres, mens der hænger billettyper på det.
 *
 * <p><strong>Katalogets face value er visning og default — ikke autoriteten.</strong>
 * Prisloftet på en konkret billet regnes af det beløb, verify svarede med
 * (PLAN §2.2), og den vej går gennem {@link Event#acceptFaceValue(Money)}, som er
 * det eneste sted en {@link VerifiedFaceValue} kan opstå.
 *
 * <p>Formatreglerne — blankt navn, {@code face_value_minor >= 0} — håndhæves af
 * V5's CHECK-constraints og af parserens 422, og er med vilje IKKE gentaget i
 * den kompakte konstruktør. Gentog vi dem, kunne de tests, der beviser at
 * constraints'ne virker, ikke længere føre en dårlig værdi ned til databasen
 * gennem aggregatet.
 */
public record TicketType(UUID id, UUID eventId, TicketTypeRef ref, String name,
		Money faceValue, int version, Instant createdAt, Instant updatedAt) {

	public TicketType {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(ref, "ref");
		Objects.requireNonNull(name, "name");
		// Money kan ikke findes uden sin valuta (typen sørger for det), men den
		// kan mangle helt — og et beløb, der er væk, må ikke blive til nul.
		Objects.requireNonNull(faceValue, "faceValue");
		Objects.requireNonNull(createdAt, "createdAt");
		Objects.requireNonNull(updatedAt, "updatedAt");
		// Postgres' timestamptz gemmer MIKROsekunder, og pgjdbc runder HALV-OP.
		// Trunkeringen hører her og ikke i testen: sker den i testen, er
		// round-trip-testen ca. 50 % flaky — målt til 9.980 uenigheder ud af
		// 20.000 round-trips på både Windows og CI.
		createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
		updatedAt = updatedAt.truncatedTo(ChronoUnit.MICROS);
		// Optimistisk låsning er en version-kolonne og WHERE version = ? (PLAN §3).
		if (version < 1) {
			throw new IllegalArgumentException("Ticket type version must be at least 1 but was " + version);
		}
	}

}

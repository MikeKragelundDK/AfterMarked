package dk.aftermarked.catalog;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * En arrangør i kundens katalog (PLAN §2.3): den, der står bag events. Rækken er
 * et spejl af kundens egen data — ejerskabet ligger hos kunden, vi holder en
 * kopi til visning og regler.
 *
 * <p><strong>Recorden bærer med vilje INGEN {@code operatorId}.</strong> Tabellen
 * har kolonnen, men den findes ikke som felt på noget aggregat, og ingen INSERT
 * binder den som parameter: værdien skrives ordret som {@code current_operator_id()}
 * i SQL'en. Der findes dermed ingen kodesti, der KAN bære et forkert tenant-id
 * ind i en indsættelse — en strukturel garanti oven på RLS, ikke i stedet for
 * den. Skema-testen SUBTRAHERER {@code operator_id} fra tabellens kolonner; den
 * lægger det aldrig til det forventede sæt, og en fejlende skema-test må derfor
 * ALDRIG "rettes" ved at tilføje feltet her.
 *
 * <p><strong>Recorden bærer med vilje heller INGEN custom felter.</strong>
 * {@code custom_fields} og {@code custom_fields_schema_version} læses kun af
 * {@code catalog.internal}. Lå de på denne offentlige type, ville ethvert modul
 * med {@code allowedDependencies = {"catalog"}} — orders, payments, marketplace
 * — kunne læse feltværdierne gennem den unavngivne grænseflade, uden at
 * ArchUnit-reglen på {@code catalog.fields} nogensinde fyrede (PLAN §2.6,
 * DoD punkt 4). Det er samme regel som ovenfor: den nærliggende "rettelse" af en
 * fejlende skema-test er præcis det, der ville omgå invarianten.
 *
 * <p>{@code isDefault} er VORES flag, ikke kundens: det markerer den arrangør, vi
 * selv syntetiserer for en kunde, der ikke skelner (se
 * {@link OrganizerRef#RESERVED_DEFAULT}). Et katalog-push skriver det aldrig, og
 * et partielt unikt indeks tillader højst én pr. operatør.
 *
 * <p>Formatreglerne — navnet må ikke være blankt, ref'ens form — håndhæves af
 * V5's CHECK-constraints og af parserens 422, og er med vilje IKKE gentaget her.
 * Gentog vi dem, kunne de tests, der beviser at constraints'ne virker, ikke
 * længere føre en dårlig værdi ned til databasen gennem aggregatet.
 */
public record Organizer(UUID id, OrganizerRef ref, String name, boolean isDefault,
		int version, Instant createdAt, Instant updatedAt) {

	public Organizer {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(ref, "ref");
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(createdAt, "createdAt");
		Objects.requireNonNull(updatedAt, "updatedAt");
		// Postgres' timestamptz gemmer MIKROsekunder, og pgjdbc runder HALV-OP.
		// Trunkeringen hører her og ikke i testen: sker den i testen, er
		// round-trip-testen ca. 50 % flaky — målt til 9.980 uenigheder ud af
		// 20.000 round-trips på både Windows og CI.
		createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
		updatedAt = updatedAt.truncatedTo(ChronoUnit.MICROS);
		// Optimistisk låsning er en version-kolonne og WHERE version = ? (PLAN §3).
		// Version 0 findes ikke: rækken fødes på 1, og databasens CHECK siger
		// det samme.
		if (version < 1) {
			throw new IllegalArgumentException("Organizer version must be at least 1 but was " + version);
		}
	}

}

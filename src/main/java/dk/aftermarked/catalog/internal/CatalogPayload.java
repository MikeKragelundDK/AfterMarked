package dk.aftermarked.catalog.internal;

import java.util.List;
import java.util.Objects;

/**
 * Kundens katalog-payload som TYPEDE records — resultatet af
 * {@link CatalogPayloadParser#parse}.
 *
 * <p>Når denne type findes, er HELE payloaden valideret: hver ref har det
 * rigtige format, hver tekst har operatørens basissprog, hvert beløb er et
 * heltal i mindste enhed med sin valuta, og hvert custom felt er holdt op mod
 * sin deklaration. Parseren kaster ellers, og den kaster med ALLE
 * overtrædelser på én gang — et delvist skrevet katalog er værre end et afvist,
 * og en kunde, der får én fejl ad gangen, skal bruge lige så mange natlige
 * kørsler, som der er fejl.
 *
 * <p>{@code organizers} er den ENESTE liste (spec-beslutning #16). En tom liste
 * er et lovligt no-op-push: der findes ingen sletning i V1, så et tomt push
 * skriver ingenting — det tømmer ikke kataloget.
 *
 * <p>Typen ligger i {@code internal}, fordi den bærer feltværdier hele vejen
 * ned (spec-beslutning #11).
 *
 * @param organizers arrangørerne med deres events og billettyper, i payloadens
 *     egen rækkefølge
 * @param warnings det, vi gemte anderledes, end kunden sendte — en ukendt
 *     {@code salesStatus}, et sprog vi ikke renderer endnu. Aldrig en
 *     afvisning, og aldrig med værdien i
 * @param schemaVersion den version af operatørens feltskema, HELE payloaden
 *     blev valideret under ({@code CustomFieldSchema.version()}). Det er dét
 *     tal, {@code custom_fields_schema_version} stemples med — stemplet
 *     beskriver VÆRDIERNE, så en replika på v1 kan se, at rækken er skrevet af
 *     en replika på v2, og lade felterne stå
 */
public record CatalogPayload(List<OrganizerUpsert> organizers, List<CatalogWarning> warnings, int schemaVersion) {

	public CatalogPayload {
		Objects.requireNonNull(organizers, "organizers");
		Objects.requireNonNull(warnings, "warnings");
		organizers = List.copyOf(organizers);
		warnings = List.copyOf(warnings);
		// Samme nedre grænse som CustomFieldSchema og V5's DEFAULT 0. En negativ
		// version ville stemple rækker med et tal, ingen deklaration kan være
		// aktiv i.
		if (schemaVersion < 0) {
			throw new IllegalArgumentException("Field schema version " + schemaVersion + " must not be negative");
		}
	}

}

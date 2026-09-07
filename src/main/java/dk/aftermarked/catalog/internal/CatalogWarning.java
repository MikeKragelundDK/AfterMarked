package dk.aftermarked.catalog.internal;

import java.util.Objects;

/**
 * En advarsel, der IKKE afviser pushet.
 *
 * <p>Forskellen fra en overtrædelse er ikke alvorlighed, men udfald: kataloget
 * blev skrevet, og advarslen fortæller kunden, at vi gemte noget andet, end de
 * sendte — en ukendt {@code salesStatus} blev til {@code UNKNOWN}, en tekst
 * blev gemt på et sprog, fladen ikke renderer endnu. Begge dele er ting, kunden
 * skal kunne se uden at deres natlige import fejler.
 *
 * <p><strong>Advarslen bærer ALDRIG værdien selv</strong> — præcis som en
 * overtrædelse. Den rå token fra kundens {@code salesStatus} kan være hvad som
 * helst, den ender i vores logs, og stien siger allerede, hvor den står.
 * Ingest-servicen logger én samlet WARN pr. push med antallet; hverken den
 * eller svaret til kunden bærer tokenen.
 *
 * <p>Typen ligger i {@code internal} af samme grund som resten af
 * payload-typerne (spec-beslutning #11): den rejser sammen med dem, og en
 * offentlig advarselstype ville trække {@link CatalogErrorCode} med sig ud i
 * den unavngivne grænseflade.
 *
 * @param code hvorfor. En af de to advarselskoder — se
 *     {@link CatalogErrorCode#isWarning()}. Typen håndhæver det bevidst ikke:
 *     ingest-servicen skal kunne advare om noget, parseren ikke kender
 * @param path JSON-Pointer (RFC 6901) ind i kundens payload
 * @param message udviklervendt engelsk grund, uden værdien
 */
public record CatalogWarning(CatalogErrorCode code, String path, String message) {

	public CatalogWarning {
		Objects.requireNonNull(code, "code");
		Objects.requireNonNull(path, "path");
		Objects.requireNonNull(message, "message");
	}

}

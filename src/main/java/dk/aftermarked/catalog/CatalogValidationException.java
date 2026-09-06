package dk.aftermarked.catalog;

import java.io.Serial;
import java.util.List;
import java.util.Objects;

/**
 * Kundens katalog-payload blev afvist. Bærer ALLE overtrædelser, ikke den
 * første.
 *
 * <p>Det er hele pointen: et katalog-push er ét alt-eller-intet-kald med op til
 * 2.000 events, og en kunde, der får én fejl ad gangen, skal bruge lige så mange
 * natlige kørsler som der er fejl. Med den fulde liste retter de i én runde.
 * Derfor validerer parseren HELE payloaden i ren Java, før én række røres — et
 * delvist skrevet katalog er værre end et afvist.
 *
 * <p><strong>Overtrædelsen er en offentlig, flad form.</strong> Den detaljerede
 * type — {@code CatalogViolation} med sin {@code CatalogErrorCode} — bliver
 * liggende i {@code catalog.internal}, hvor resten af payload-typerne ligger:
 * kom den ud på en offentlig signatur, ville den trække den interne fejl-model
 * med sig ud i den unavngivne grænseflade, som ethvert modul med
 * {@code allowedDependencies = {"catalog"}} kan se. Parseren mapper derfor sin
 * egen type over i {@link Violation} på vej ud:
 * {@code new Violation(violation.code().name(), violation.path(), violation.message())}.
 *
 * <p>Koden er stabil og maskinlæsbar, stien er en JSON-Pointer, og beskeden er
 * udviklervendt engelsk. Ingen af de tre bærer NOGENSINDE den afviste værdi
 * selv: den kan være persondata eller 20 KB richtext, og den ender i logs.
 * Kunden ved fra stien præcis hvor i sin egen payload værdien står.
 */
public final class CatalogValidationException extends CatalogException {

	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * Hvor mange overtrædelser der skrives ind i selve exception-beskeden. Hele
	 * listen findes på {@link #violations()} — beskeden skal kunne læses i en
	 * log, og 2.000 linjer i én besked er ingen hjælp.
	 */
	private static final int VIOLATIONS_IN_MESSAGE = 5;

	private final List<Violation> violations;

	public CatalogValidationException(List<Violation> violations) {
		super(summarise(violations));
		this.violations = List.copyOf(violations);
	}

	/**
	 * Alle overtrædelser, i den rækkefølge parseren fandt dem — altså i
	 * payloadens egen rækkefølge, så kunden kan læse dem oppefra og ned.
	 *
	 * <p>Listen er uforanderlig og aldrig tom.
	 */
	public List<Violation> violations() {
		return this.violations;
	}

	/**
	 * Én afvist værdi: en stabil kode, en JSON-Pointer ind i kundens egen payload
	 * og en udviklervendt engelsk grund.
	 *
	 * <p>Bevidst tre strenge og ikke tre typer. Formen er den, der skal kunne
	 * krydse modulgrænsen og senere blive til et 422-svar uden en oversættelse
	 * undervejs — og en enum her ville gøre den interne fejl-model til offentlig
	 * API, som en ny fejlkode ikke længere kunne tilføjes til uden at ændre den.
	 *
	 * @param code stabil, maskinlæsbar kode, fx {@code INVALID_REF_FORMAT}
	 * @param path JSON-Pointer (RFC 6901) ind i payloaden, fx
	 *     {@code /organizers/0/events/1/faceValue/amount}. Den tomme streng er
	 *     dokumentets rod, fx når hele pushet er for stort
	 * @param message engelsk grund. Bærer ALDRIG værdien selv
	 */
	public record Violation(String code, String path, String message) {

		public Violation {
			Objects.requireNonNull(code, "code");
			Objects.requireNonNull(path, "path");
			Objects.requireNonNull(message, "message");
		}

	}

	/**
	 * Beskeden til loggen.
	 *
	 * <p>Formen er med vilje kun null- og tomhedstjek. En konstruktør, der
	 * validerede stiens form, ville kunne kaste MENS vi er ved at rapportere
	 * kundens fejl — og så bliver et 422 til et 500, hvor den egentlige liste er
	 * væk. En tom liste er derimod altid vores egen programfejl: så er der intet
	 * at afvise, og undtagelsen skulle slet ikke være kastet.
	 */
	private static String summarise(List<Violation> violations) {
		Objects.requireNonNull(violations, "violations");
		if (violations.isEmpty()) {
			throw new IllegalArgumentException("A catalog validation failure must carry at least one violation");
		}
		StringBuilder summary = new StringBuilder("Catalog payload rejected with ")
				.append(violations.size())
				.append(violations.size() == 1 ? " violation: " : " violations: ");
		int shown = Math.min(violations.size(), VIOLATIONS_IN_MESSAGE);
		for (int index = 0; index < shown; index++) {
			Violation violation = violations.get(index);
			if (index > 0) {
				summary.append("; ");
			}
			summary.append(violation.code())
					.append(" at [")
					.append(violation.path())
					.append("] ")
					.append(violation.message());
		}
		if (violations.size() > shown) {
			summary.append("; and ").append(violations.size() - shown).append(" more");
		}
		return summary.toString();
	}

}

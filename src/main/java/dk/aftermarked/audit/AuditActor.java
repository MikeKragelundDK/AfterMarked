package dk.aftermarked.audit;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Hvem der gjorde det: en aktørtype og en reference, der peger på den konkrete
 * aktør inde i den type.
 *
 * <p>Typen er en <em>streng</em> og ikke et enum, og det er samme valg som i
 * databasen: {@code audit_log.actor_type} har et FORMAT og ingen lukket liste,
 * fordi en {@code CHECK}-liste kun kan strammes senere — aldrig løsnes — og S4
 * (sælger og køber) og S6 (flere admin-handlinger) skal begge kunne tilføje
 * aktører uden en migration. De typer, vi kender i dag, står som konstanter
 * herunder, og hver af dem har sin fabrik, så en stavefejl ikke bliver til en
 * ny aktørtype i loggen.
 *
 * <p>Referencen er bevidst <strong>uigennemsigtig</strong>. Modulet er et blad
 * (se pakkens javadoc) og må aldrig kende et {@code Listing}, et {@code Event}
 * eller en {@code Order}: den dag loggen holdt et aggregat, ville en ændring af
 * aggregatet ændre betydningen af en linje, der allerede var skrevet.
 *
 * <p>{@code ref} må være {@code null} — kolonnen er nullable, og en læst række
 * kan have været skrevet uden. Fabrikkerne kræver den derimod, fordi deres
 * argument ER identiteten: en {@code null} dér ville lave en anonym linje ud af
 * et kald, der troede, det navngav nogen.
 */
public record AuditActor(String type, String ref) {

	/** Det natlige katalog-push. {@code ref} er push-id'et — ét uuid pr. request. */
	public static final String CATALOG_PUSH = "catalog_push";

	/** Et menneske i operatør-admin. {@code ref} er brugernavnet. */
	public static final String OPERATOR_ADMIN = "operator_admin";

	/** Systemet selv: baggrundsjobs, outbox-workers, lazy enrichment. */
	public static final String SYSTEM = "system";

	/** Præcis samme regex som V5's {@code audit_log_actor_type_format}. */
	static final Pattern TYPE_FORMAT = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");

	/** U+0000, skrevet som escape — tegnet må aldrig stå råt i kilden. */
	private static final char NUL = '\0';

	public AuditActor {
		Objects.requireNonNull(type, "type must not be null");
		if (!TYPE_FORMAT.matcher(type).matches()) {
			throw new IllegalArgumentException("Actor type [" + type
					+ "] must be snake_case, start with a letter and be at most 32 characters"
					+ " (audit_log_actor_type_format)");
		}
		if (ref != null) {
			// En blank ref identificerer ingen. Udelad den i stedet -- ellers ser
			// linjen ud til at pege paa nogen uden at goere det.
			if (ref.isBlank()) {
				throw new IllegalArgumentException(
						"Actor reference for type [" + type + "] is blank; pass null when there is no reference");
			}
			// Postgres' text kan ikke lagre U+0000, og referencen ryger desuden
			// gennem et jsonb-dokument paa vej ned. Uden denne linje fejler
			// skrivningen dybt nede i driveren med en besked, der ikke navngiver
			// den raekke, der er noget galt med.
			if (ref.indexOf(NUL) >= 0) {
				throw new IllegalArgumentException("Actor reference for type [" + type
						+ "] contains a NUL character (U+0000), which PostgreSQL text cannot store");
			}
		}
	}

	/** Det natlige katalog-push, identificeret ved sit push-id. */
	public static AuditActor catalogPush(String pushId) {
		return new AuditActor(CATALOG_PUSH, Objects.requireNonNull(pushId, "pushId must not be null"));
	}

	/** Et menneske i operatør-admin, identificeret ved sit brugernavn. */
	public static AuditActor operatorAdmin(String username) {
		return new AuditActor(OPERATOR_ADMIN, Objects.requireNonNull(username, "username must not be null"));
	}

	/**
	 * Systemet selv. {@code ref} navngiver jobbet eller stien, så "systemet
	 * gjorde det" stadig kan spores til ét sted i koden.
	 */
	public static AuditActor system(String ref) {
		return new AuditActor(SYSTEM, Objects.requireNonNull(ref, "ref must not be null"));
	}

	/** Udviklervendt: {@code "catalog_push/push-1"}. Aldrig vist til en bruger. */
	@Override
	public String toString() {
		return ref == null ? type : type + "/" + ref;
	}

}

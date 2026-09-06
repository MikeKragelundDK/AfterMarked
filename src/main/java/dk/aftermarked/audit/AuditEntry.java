package dk.aftermarked.audit;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Én linje i audit-loggen: hvem gjorde hvad ved hvilken entitet hvornår, og
 * hvordan så den ud før og efter.
 *
 * <p><strong>{@code operator_id} er ikke et felt her.</strong> Kolonnen findes i
 * {@code audit_log}, men ingen kodesti binder den som parameter: skriveren
 * skriver {@code current_operator_id()} ordret i SQL'en. Der findes dermed ingen
 * måde at bære et forkert tenant-id ind i en linje — en strukturel garanti frem
 * for en, der hviler på at RLS'ens {@code WITH CHECK} griber fejlen bagefter.
 *
 * <p><strong>{@code id} og {@code occurredAt} ejes af databasen.</strong> Til
 * skrivning bruges {@link #of}, som lader begge stå tomme: id'et er
 * {@code GENERATED ALWAYS AS IDENTITY}, og tidspunktet er kolonnens
 * {@code DEFAULT now()}, så alle linjer fra samme transaktion deler tidspunkt og
 * sorteres på id. En læst linje har dem begge sat.
 *
 * <p><strong>{@code beforeState} og {@code afterState} er RÅ jsonb-dokumenter
 * båret som tekst.</strong> De kommer fra upsertens
 * {@code to_jsonb(old.*)}/{@code to_jsonb(new.*)}, læses med
 * {@code rs.getString(...)} og skrives igen med {@code CAST(... AS jsonb)}.
 * Formen er valgt for at holde Jackson HELT ude af modulet: en tree-model i
 * signaturen ville gøre en JSON-parser til en del af audit-modulets
 * grænseflade, og modulet er et blad, der ikke skal have en.
 *
 * <p><strong>Sammenlign aldrig de to felter som tekst.</strong> {@code jsonb}
 * normaliserer nøglerækkefølge og whitespace, så {@code {"a":1}} kommer tilbage
 * som <code>{"a": 1}</code> — verificeret på 18.6. En test skal sammenligne
 * {@code jsonb = jsonb} i SQL eller parset JSON, aldrig strengene.
 */
public record AuditEntry(Long id, Instant occurredAt, AuditActor actor, String action, String entityType,
		UUID entityId, String entityRef, String beforeState, String afterState) {

	/** Præcis samme regex som V5's {@code audit_log_action_format}: {@code <entity>.<verb>}. */
	static final Pattern ACTION_FORMAT = Pattern.compile("^[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*$");

	/** Præcis samme regex som V5's {@code audit_log_entity_type_format} (og V3's for oversættelser). */
	static final Pattern ENTITY_TYPE_FORMAT = Pattern.compile("^[a-z][a-z0-9_]{0,61}$");

	/** U+0000, skrevet som escape — tegnet må aldrig stå råt i kilden. */
	private static final char NUL = '\0';

	public AuditEntry {
		Objects.requireNonNull(actor, "actor must not be null");
		Objects.requireNonNull(action, "action must not be null");
		Objects.requireNonNull(entityType, "entityType must not be null");
		if (!ACTION_FORMAT.matcher(action).matches()) {
			throw new IllegalArgumentException("Action [" + action
					+ "] must be snake_case on the form <entity>.<verb> (audit_log_action_format)");
		}
		if (!ENTITY_TYPE_FORMAT.matcher(entityType).matches()) {
			throw new IllegalArgumentException("Entity type [" + entityType
					+ "] must be snake_case, start with a letter and be at most 62 characters"
					+ " (audit_log_entity_type_format)");
		}
		// Samme regel som audit_log_has_a_subject. En linje uden subjekt kan
		// hverken laeses af et menneske eller findes af admin-opslaget, og den
		// ville vaere skrevet af kode, der troede den loggede noget.
		if (entityId == null && entityRef == null) {
			throw new IllegalArgumentException("Audit entry for action [" + action
					+ "] has neither an entity id nor an entity ref; the log line would have no subject");
		}
		if (entityRef != null) {
			if (entityRef.isBlank()) {
				throw new IllegalArgumentException(
						"Entity ref for action [" + action + "] is blank; pass null when there is no ref");
			}
			if (entityRef.indexOf(NUL) >= 0) {
				throw new IllegalArgumentException("Entity ref for action [" + action
						+ "] contains a NUL character (U+0000), which PostgreSQL text cannot store");
			}
		}
		beforeState = requireJsonObjectOrNull(beforeState, "beforeState", action);
		afterState = requireJsonObjectOrNull(afterState, "afterState", action);
		// Postgres gemmer mikrosekunder, og pgjdbc runder HALV-OP paa vej ned.
		// Uden trunkeringen her ville et Instant med nanosekunder komme tilbage
		// som en ANDEN vaerdi, og en round-trip-test ville fejle paa noget, der
		// lignede en mapping-fejl.
		if (occurredAt != null) {
			occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
		}
	}

	/**
	 * Linjen som den skrives: {@code id} og {@code occurredAt} fyldes af
	 * databasen.
	 *
	 * @param entityId  entitetens uuid, eller {@code null} hvis den ikke har et
	 *                  hos os. Der er med vilje INGEN fremmednøgle — subjektet
	 *                  må gerne forsvinde, uden at linjen gør det
	 * @param entityRef kundens egen reference, så linjen kan læses af et
	 *                  menneske, også når id'et ikke findes længere
	 */
	public static AuditEntry of(AuditActor actor, String action, String entityType, UUID entityId, String entityRef,
			String beforeState, String afterState) {
		return new AuditEntry(null, null, actor, action, entityType, entityId, entityRef, beforeState, afterState);
	}

	/**
	 * Sand for en linje, der allerede er skrevet — altså læst tilbage fra
	 * databasen. Skrivestien afviser sådan en: den ville blive til en ny linje
	 * med et nyt id og se ud som om handlingen skete to gange.
	 */
	public boolean isPersisted() {
		return id != null || occurredAt != null;
	}

	/**
	 * Overfladisk formkontrol af et jsonb-dokument. Den er BEVIDST lav — den
	 * rigtige validering er Postgres' egen, når {@code CAST(... AS jsonb)}
	 * kører.
	 *
	 * <p>Kontrollen giver en tidlig fejl ved en åbenlyst forkert form.
	 * Skriveren sender dokumenterne som escaped strenge og konverterer hver
	 * værdi fra {@code text} til {@code jsonb} i SQL, så den fulde syntaks
	 * valideres uden risiko for at påvirke batchens øvrige felter eller rækker.
	 */
	private static String requireJsonObjectOrNull(String state, String name, String action) {
		if (state == null) {
			return null;
		}
		String stripped = state.strip();
		if (!stripped.startsWith("{") || !stripped.endsWith("}")) {
			throw new IllegalArgumentException("The " + name + " of action [" + action
					+ "] is not a JSON object; audit states are whole rows (to_jsonb(old.*)/to_jsonb(new.*))");
		}
		return stripped;
	}

}

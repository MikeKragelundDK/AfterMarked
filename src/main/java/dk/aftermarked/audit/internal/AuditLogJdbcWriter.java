package dk.aftermarked.audit.internal;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import dk.aftermarked.audit.AuditEntry;
import dk.aftermarked.audit.AuditLog;
import dk.aftermarked.tenancy.OperatorContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Den ENESTE klasse i systemet, der skriver i {@code audit_log}.
 *
 * <p>At den er ene om det, er ikke ryddelighed: kunne katalogets egen
 * upsert-CTE skrive linjerne direkte, ville formen på en audit-linje være
 * spredt ud over hver eneste skrivesti, og ingen kunne svare på, hvad der
 * faktisk står i loggen. Med én skriver er reglerne ét sted, og hver ny kalder
 * arver dem.
 *
 * <p><strong>{@code operator_id} bindes aldrig.</strong> Sætningen skriver
 * {@code current_operator_id()} ordret, så der ikke findes en kodesti, der KAN
 * bære et forkert tenant-id ind i loggen. {@link OperatorContext#required()}
 * kaldes alligevel først: uden kontekst ville sætningen ellers fejle på en
 * {@code NOT NULL}-overtrædelse i stedet for at sige, hvad der manglede.
 *
 * <p><strong>Én sætning uanset antal.</strong> Linjerne sendes som ét
 * jsonb-dokument gennem {@code jsonb_to_recordset}. Et førstedump af 5.000
 * events skriver derfor ét statement — ikke 5.000 — inde i den transaktion, der
 * også holder katalogets advisory-lås.
 *
 * <p><strong>Hvorfor dokumentet skrives i hånden.</strong> Modulet er et blad
 * med {@code tenancy} som eneste tilladte afhængighed, og en JSON-mapper i
 * skriveren ville trække en parser ind i et modul uden en JSON-flade. Vigtigere:
 * {@code beforeState} og {@code afterState} sendes som escaped JSON-strenge
 * og læses som {@code text}, før hver værdi konverteres til {@code jsonb}.
 * Dermed kan et ugyldigt dokument aldrig bryde ud af sit felt og ændre andre
 * felter eller indsætte ekstra rækker i batchen. Postgres validerer hvert
 * dokument særskilt, og hele skrivningen fejler atomisk ved ugyldig JSON.
 */
@Component
public class AuditLogJdbcWriter implements AuditLog {

	/**
	 * Batch-indsættelsen. {@code jsonb_to_recordset}s kolonnenavne er
	 * case-sensitive og matcher tabellens ordret, så dokumentets nøgler er
	 * snake_case — en camelCase-nøgle ville give en tavs NULL i stedet for en
	 * fejl.
	 *
	 * <p>{@code CAST(:entries AS jsonb)} med en almindelig {@code String}-param —
	 * aldrig {@code :entries::jsonb} og aldrig {@code stringtype=unspecified} i
	 * JDBC-URL'en.
	 */
	private static final String INSERT_BATCH = """
			INSERT INTO audit_log (operator_id, actor_type, actor_ref, action, entity_type,
			                       entity_id, entity_ref, before_state, after_state)
			SELECT current_operator_id(), a.actor_type, a.actor_ref, a.action, a.entity_type,
			       a.entity_id, a.entity_ref, CAST(a.before_state AS jsonb), CAST(a.after_state AS jsonb)
			  FROM jsonb_to_recordset(CAST(:entries AS jsonb))
			         AS a(actor_type text, actor_ref text, action text, entity_type text,
			              entity_id uuid, entity_ref text, before_state text, after_state text)
			""";

	private final JdbcClient jdbc;

	AuditLogJdbcWriter(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional
	public void record(AuditEntry entry) {
		Objects.requireNonNull(entry, "entry must not be null");
		// Selvkald, saa proxyen springes over -- men transaktionen er allerede
		// aabnet af DENNE metode, og recordAll har ingen anden semantik at
		// bidrage med.
		recordAll(List.of(entry));
	}

	@Override
	@Transactional
	public void recordAll(List<AuditEntry> entries) {
		Objects.requireNonNull(entries, "entries must not be null");
		OperatorContext.required();
		if (entries.isEmpty()) {
			// En tom batch ville indsaette nul raekker, men saetningen ville
			// stadig koste en rundtur og tage sine laase.
			return;
		}
		rejectAlreadyWrittenEntries(entries);
		jdbc.sql(INSERT_BATCH).param("entries", entriesAsJson(entries)).update();
	}

	/**
	 * En linje, der bærer databasens {@code id} eller {@code occurred_at}, er
	 * læst tilbage og ikke ny. Sætningen navngiver ingen af de to kolonner, så
	 * en sådan linje ville blive skrevet igen med et NYT id og et NYT tidspunkt:
	 * handlingen ville stå to gange i loggen, og kalderens værdier ville
	 * forsvinde i stilhed. Det er præcis den slags tavse tab, loggen findes for
	 * at gøre umulige — derfor en højlydt fejl frem for et forsøg.
	 */
	private static void rejectAlreadyWrittenEntries(List<AuditEntry> entries) {
		for (AuditEntry entry : entries) {
			if (entry.isPersisted()) {
				throw new IllegalArgumentException("Audit entry for action [" + entry.action()
						+ "] already carries a database id or timestamp; the log is append-only,"
						+ " so build a new entry with AuditEntry.of(...) instead of rewriting a read one");
			}
		}
	}

	/**
	 * Batchen som ét jsonb-dokument:
	 * {@code [{"actor_type": …, …, "after_state": "{…}"}, …]}.
	 *
	 * <p>Dokumenterne escapes ligesom alle andre strenge. SQL'en læser dem
	 * tilbage som tekst og parser dem derpå som selvstændige JSON-dokumenter.
	 * En {@code null} forbliver SQL NULL gennem begge trin.
	 */
	private static String entriesAsJson(List<AuditEntry> entries) {
		StringBuilder json = new StringBuilder(entries.size() * 192).append('[');
		for (int index = 0; index < entries.size(); index++) {
			AuditEntry entry = entries.get(index);
			if (index > 0) {
				json.append(',');
			}
			json.append("{\"actor_type\":");
			appendJsonString(json, entry.actor().type());
			json.append(",\"actor_ref\":");
			appendJsonStringOrNull(json, entry.actor().ref());
			json.append(",\"action\":");
			appendJsonString(json, entry.action());
			json.append(",\"entity_type\":");
			appendJsonString(json, entry.entityType());
			json.append(",\"entity_id\":");
			appendJsonStringOrNull(json, entry.entityId() == null ? null : entry.entityId().toString());
			json.append(",\"entity_ref\":");
			appendJsonStringOrNull(json, entry.entityRef());
			json.append(",\"before_state\":");
			appendJsonStringOrNull(json, entry.beforeState());
			json.append(",\"after_state\":");
			appendJsonStringOrNull(json, entry.afterState());
			json.append('}');
		}
		return json.append(']').toString();
	}

	private static void appendJsonStringOrNull(StringBuilder json, String text) {
		if (text == null) {
			json.append("null");
		}
		else {
			appendJsonString(json, text);
		}
	}

	/**
	 * Én JSON-streng, escaped efter RFC 8259 §7. Non-ASCII sendes råt —
	 * forbindelsen er UTF-8, og jsonb tager imod det — mens styretegn under
	 * {@code U+0020} escapes, fordi JSON ikke tillader dem rå. {@code U+0000} er
	 * allerede afvist i {@code AuditActor} og {@code AuditEntry}, fordi
	 * hverken {@code text} eller {@code jsonb} kan lagre det.
	 */
	private static void appendJsonString(StringBuilder json, String text) {
		json.append('"');
		for (int index = 0; index < text.length(); index++) {
			char character = text.charAt(index);
			switch (character) {
				case '"' -> json.append("\\\"");
				case '\\' -> json.append("\\\\");
				case '\b' -> json.append("\\b");
				case '\f' -> json.append("\\f");
				case '\n' -> json.append("\\n");
				case '\r' -> json.append("\\r");
				case '\t' -> json.append("\\t");
				default -> {
					if (character < ' ') {
						json.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
					}
					else {
						json.append(character);
					}
				}
			}
		}
		json.append('"');
	}

}

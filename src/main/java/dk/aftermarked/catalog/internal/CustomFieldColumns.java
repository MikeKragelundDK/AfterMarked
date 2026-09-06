package dk.aftermarked.catalog.internal;

import java.util.Objects;
import java.util.UUID;

import dk.aftermarked.catalog.fields.CustomFieldValues;

/**
 * De tre kolonner, kundens egne felter bor i — {@code id}, {@code custom_fields}
 * og {@code custom_fields_schema_version} — som ét record. Én type for alle tre
 * tabeller, fordi de tre kolonner er ens på arrangør, event og billettype
 * (PLAN §2.6).
 *
 * <p><strong>Hvorfor den ligger i {@code internal}.</strong> Den bærer kundens
 * feltværdier. Lå den i den offentlige {@code catalog}-pakke, kunne ethvert
 * modul med {@code allowedDependencies = {"catalog"}} — altså {@code orders},
 * {@code payments} og {@code marketplace} — læse feltlageret gennem en type, det
 * lovligt må se, uden at ArchUnit-reglen på {@code catalog.fields} nogensinde
 * fyrede (PLAN §2.6, DoD punkt 4). Grænsen er pakken, ikke modifikatoren:
 * Modulith afviser en afhængighed på {@code catalog.internal} fra et andet
 * modul, uanset om typen er {@code public}.
 *
 * <p><strong>Hvorfor den findes i stedet for en komponent på aggregatet.</strong>
 * {@code event}-tabellen har to kolonner, det offentlige {@code Event} ikke
 * dækker. Skema-testen sammenligner derfor tabellen med <em>unionen</em> af
 * {@code Event} og denne type. Den nærliggende "rettelse" — at føje en
 * {@code customFields}-komponent til {@code Event} — ville gøre testen grøn og
 * invariant nr. 4 til en ruin i samme ombæring. <strong>Gør det aldrig.</strong>
 *
 * <p><strong>Hvorfor dokumentet er en {@code String}.</strong> Det er præcis
 * dét, der går ind i feltets egen {@code CAST(... AS jsonb)} og kommer ud af
 * {@code rs.getString(...)}. En serialisering undervejs er en ekstra chance for
 * at ændre indholdet — og et JSON-træ i signaturen ville trække Jacksons
 * tree-model ind i et modul, der ikke har nogen JSON-flade.
 *
 * <p><strong>Sammenlign aldrig to dokumenter som tekst.</strong> {@code jsonb}
 * bevarer den numeriske form, der blev skrevet <em>først</em>:
 * {@code {"a":1.0}} og {@code {"a":1}} er ens som jsonb og forskellige som
 * tekst. En assertion på teksten består ved held og fejler den dag en kunde
 * sender {@code 1.0}. Sammenlign med {@code jsonb = jsonb} i SQL eller på
 * parset JSON.
 *
 * @param id                        entiteten, felterne hænger på — samme uuid
 *                                  som aggregatets
 * @param customFields              det validerede JSON-objekt, ordret som det
 *                                  står i kolonnen
 * @param customFieldsSchemaVersion den version af operatørens feltskema,
 *                                  værdierne blev VALIDERET under — ikke den,
 *                                  der gælder i dag
 */
public record CustomFieldColumns(UUID id, String customFields, int customFieldsSchemaVersion) {

	/**
	 * Tegnet PostgreSQL ikke kan lagre. Skrevet som den <em>oktale</em> escape
	 * {@code '\0'} med vilje — en unicode-escape erstattes af oversætteren
	 * <em>før</em> tokeniseringen og ville lægge et råt NUL-tegn i kildefilen.
	 */
	private static final char NUL = '\0';

	public CustomFieldColumns {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(customFields, "customFields");
		String document = customFields.strip();
		// Lav formkontrol med vilje: den aegte validering mod operatoerens
		// feltskema er sket, foer vaerdien naaede hertil. Det her fanger den
		// tomme streng, en null-literal og en skalar, der er sluppet igennem --
		// alle tre ville ellers ramme CHECK jsonb_typeof(custom_fields) =
		// 'object' med en besked, der ikke navngiver entiteten.
		if (!document.startsWith("{") || !document.endsWith("}")) {
			throw new IllegalArgumentException("The custom field document of entity " + id
					+ " is not a JSON object — the column requires jsonb_typeof(custom_fields) = 'object'");
		}
		// Samme grund som i TranslationRow. Dokumentet selv navngives ALDRIG i
		// beskeden: det er kundens data, og en exception-besked ender i loggen.
		if (document.indexOf(NUL) >= 0) {
			throw new IllegalArgumentException("The custom field document of entity " + id
					+ " contains a NUL character (U+0000), which PostgreSQL jsonb cannot store");
		}
		if (customFieldsSchemaVersion < 0) {
			throw new IllegalArgumentException("The custom field schema version of entity " + id
					+ " is negative — the column requires custom_fields_schema_version >= 0");
		}
		customFields = document;
	}

	/**
	 * Entiteten uden custom felter: det tomme dokument under version 0 — præcis
	 * hvad kolonnernes DEFAULT ville have skrevet.
	 */
	public static CustomFieldColumns empty(UUID id) {
		return new CustomFieldColumns(id, CustomFieldValues.EMPTY_DOCUMENT, 0);
	}

}

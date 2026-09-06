package dk.aftermarked.catalog.fields;

/**
 * Hvilken entitet feltet hænger på — arrangøren, eventet eller billettypen
 * (PLAN §2.6).
 *
 * <p>To navne, med vilje: config skriver camelCase ({@code ticketType}),
 * oversættelseslaget snake_case ({@code ticket_type}), fordi V3's CHECK
 * {@code translation_entity_type_format} afviser camelCase. Lå mappingen spredt
 * hos kalderne, ville importen fejle på constrainten hos den første, der glemte
 * den — og den fejl ville komme klokken 03 i et natligt push, ikke i en test.
 *
 * <p>{@link #entityType()} er samtidig tabelnavnet, feltets entitet ligger i.
 * Det er ikke et tilfælde, men det er heller ikke en garanti, koden må hvile
 * på: værdien er en {@code translation.entity_type}-nøgle, ikke en SQL-identifier,
 * og den må aldrig interpoleres ind i en sætning (CLAUDE.md).
 */
public enum CustomFieldScope {

	ORGANIZER("organizer", "organizer"),
	EVENT("event", "event"),
	TICKET_TYPE("ticketType", "ticket_type");

	private final String configName;

	private final String entityType;

	CustomFieldScope(String configName, String entityType) {
		this.configName = configName;
		this.entityType = entityType;
	}

	/** Navnet, som det står i operatørens feltskema-YAML. camelCase. */
	public String configName() {
		return this.configName;
	}

	/** Navnet, som det står i {@code translation.entity_type}. snake_case. */
	public String entityType() {
		return this.entityType;
	}

}

package dk.aftermarked.catalog.fields;

/**
 * De ni typer fra PLAN §2.6. Typen styrer validering og rendering — betydningen
 * ligger i {@code role}, aldrig her. Lå betydningen i typen, mistede vi
 * valideringen.
 *
 * <p>Listen er lukket med vilje. En ny type er en ændring af PLAN §2.6 og af
 * valideringen i {@code catalog.internal} — ikke en tilføjelse nogen kan lave i
 * en config-fil.
 */
public enum CustomFieldType {

	TEXT(true),
	RICHTEXT(true),
	IMAGE(false),
	URL(false),
	DATE(false),
	BOOL(false),
	ENUM(false),
	MONEY(false),
	COLOR(false);

	private final boolean translatable;

	CustomFieldType(boolean translatable) {
		this.translatable = translatable;
	}

	/**
	 * Om felttypen overhovedet KAN oversættes — altså om dens værdi må stå som
	 * et objekt i JSONB'en (PLAN §2.9: oversættelsen bor, hvor værdien bor).
	 *
	 * <p>Det er samtidig dét, der fjerner tvetydigheden i lageret. En værdi som
	 * <code>{"da": "Hovedscenen"}</code> og en værdi som
	 * <code>{"amount": 12000, "currency": "DKK"}</code> er begge JSON-objekter,
	 * og formen alene kan ikke afgøre, hvad de betyder. <strong>TYPEN afgør,
	 * aldrig formen på JSON'en</strong> — og fordi typen er låst i skemaet og
	 * aldrig må skifte for samme key, er betydningen af en LAGRET værdi altid
	 * kendt, også for en aflæsning år senere.
	 */
	public boolean isTranslatable() {
		return this.translatable;
	}

}

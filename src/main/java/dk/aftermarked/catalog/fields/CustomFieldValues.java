package dk.aftermarked.catalog.fields;

import java.util.Objects;

/**
 * De VALIDEREDE feltværdier for én entitet — dokumentet, der lander i
 * {@code custom_fields}-kolonnen (PLAN §2.6).
 *
 * <p>Dokumentet er allerede kontrolleret, når det når hertil:
 * {@code catalog.internal} har holdt hver værdi op mod sin deklaration i
 * {@link CustomFieldSchema}, afvist ukendte nøgler og forkerte typer, og pillet
 * oversættelserne ud. Typen her er dermed kvitteringen for, at det er sket — den
 * er hverken en parser eller en validator.
 *
 * <p><strong>Hvorfor {@code json} er en {@code String} og ikke et JSON-træ.</strong>
 * Denne pakke er den navngivne grænseflade {@code catalog::fields}, altså den
 * flade {@code web} og {@code operatoradmin} kommer til at afhænge af. En
 * Jackson-type i signaturen ville trække tree-modellen med over i hvert af de
 * moduler for ingenting — og {@code @NamedInterface} propagerer sit medlemskab
 * til typer i offentlige signaturer, så koblingen ville ikke blive ved kanten.
 * Dokumentet transporteres escaped som én tekstværdi i batchen og parses
 * derefter med sin egen {@code CAST(... AS jsonb)} i SQL. Dermed ændrer et
 * fejlformet dokument aldrig batchens struktur.
 *
 * <p>{@code schemaVersion} er den version af operatørens feltskema, værdierne
 * blev valideret under — ikke den version, der gælder i dag. Stemplet beskriver
 * VÆRDIERNE, så en replika på v1 kan se, at rækken er skrevet af en replika på
 * v2, og lade felterne stå.
 *
 * <p>{@code scope} står med, fordi et dokument valideret mod arrangør-felterne
 * ikke må kunne skrives på et event. Den kontrol kan ellers kun laves ved at
 * huske det på kaldstedet, og et {@code custom_fields}-dokument på den forkerte
 * tabel ville hverken bryde en constraint eller en test.
 *
 * <p><strong>Formkontrollen er lav med vilje.</strong> Uden en parser kan denne
 * type kun se, at strengen ligner et JSON-objekt. Det er nok til at fange en
 * tom streng, en {@code null}-literal eller en skalar, der er sluppet igennem —
 * den ægte validering er sket før.
 */
public record CustomFieldValues(CustomFieldScope scope, int schemaVersion, String json) {

	/** Det tomme dokument, som V5's {@code DEFAULT '{}'::jsonb} skriver. */
	public static final String EMPTY_DOCUMENT = "{}";

	/**
	 * Tegnet PostgreSQL ikke kan lagre. Skrevet som den <em>oktale</em> escape
	 * {@code '\0'} med vilje — en unicode-escape erstattes af oversætteren
	 * <em>før</em> tokeniseringen og ville lægge et råt NUL-tegn i kildefilen.
	 */
	private static final char NUL = '\0';

	public CustomFieldValues {
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(json, "json");
		if (schemaVersion < 0) {
			throw new IllegalArgumentException(
					"Field schema version " + schemaVersion + " must not be negative");
		}
		// Trimmes FØR formkontrollen, så isEmpty() ikke har et hul for " {} ".
		json = json.strip();
		if (!json.startsWith("{") || !json.endsWith("}")) {
			throw new IllegalArgumentException(
					"Custom field values for scope " + scope + " must be a JSON object");
		}
		// Samme regel som CustomFieldColumns og TranslationRow. Dokumentet går
		// ind i sin egen CAST(... AS jsonb) på batch-stien, og Postgres afviser
		// U+0000 dybt nede i driveren uden at navngive rækken. Dokumentet selv
		// navngives ALDRIG i beskeden: det er kundens data og ender i loggen.
		if (json.indexOf(NUL) >= 0) {
			throw new IllegalArgumentException("Custom field values for scope " + scope
					+ " contain a NUL character (U+0000), which PostgreSQL jsonb cannot store");
		}
	}

	/**
	 * Det tomme dokument for én entitetstype. Dét et skelet-event fra lazy
	 * enrichment skrives med: {@code required}-felter gælder kun ved
	 * katalog-push, ellers ville én påkrævet feltdeklaration gøre en kundes
	 * første listing umulig.
	 */
	public static CustomFieldValues empty(CustomFieldScope scope) {
		return new CustomFieldValues(scope, 0, EMPTY_DOCUMENT);
	}

	/** Om dokumentet er tomt — ingen felter sat på entiteten. */
	public boolean isEmpty() {
		return EMPTY_DOCUMENT.equals(this.json);
	}

}

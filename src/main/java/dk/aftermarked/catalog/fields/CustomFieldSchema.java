package dk.aftermarked.catalog.fields;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Det opløste feltskema for ÉN operatør: et versionsnummer og de deklarationer,
 * der gælder i netop den version (PLAN §2.6).
 *
 * <p>Opløst betyder færdigflettet — {@code base} plus operatørens egne, hvor
 * operatørens vinder på samme key. Flettningen og de fejl, der kun kan ses på
 * hele listen, hører i {@code catalog.internal}; her ligger kun resultatet og de
 * to opslag, valideringen har brug for.
 *
 * <p>Versionen er den, {@code custom_fields_schema_version} stemples med, når en
 * række skrives. Stemplet beskriver VÆRDIERNE og ikke rækken, så det opdateres
 * kun, når værdierne selv ændrer sig — ellers ville en ren config-bump omskrive
 * hver eneste uændrede række og oversvømme audit-loggen.
 *
 * <p><strong>Uforanderlig.</strong> Listen kopieres i den kompakte konstruktør,
 * og opslagene giver uforanderlige samlinger tilbage. Skemaet holdes i en
 * {@code @Component} og læses af hvert eneste push; en delt, foranderlig liste
 * ville være et datakapløb, ingen test ville fange.
 *
 * <p><strong>Konstruktøren frasorterer deklarationer uden for versionens vindue</strong>
 * ({@link CustomFieldDeclaration#isActiveAt(int)}). {@link #declarations()} giver
 * derfor kun de aktive, og et felt, der er trukket tilbage med {@code until}, kan
 * ikke længere findes af {@link #find}. Krydsfelt-regler, der har brug for HELE
 * historikken — først og fremmest den vigtigste, at et key aldrig skifter type —
 * skal derfor køres på listen, FØR den gives herind.
 *
 * <p>Nøgler er unikke PR. SCOPE og ikke globalt: JSONB-nøglerummet ligger på
 * entiteten, så det samme {@code logo} kan lovligt findes på både arrangøren og
 * eventet. To deklarationer med samme key i samme scope er derimod tvetydige, og
 * tvetydigheden ville først vise sig som en vilkårlig vinder ved validering.
 */
public record CustomFieldSchema(int version, List<CustomFieldDeclaration> declarations) {

	public CustomFieldSchema {
		Objects.requireNonNull(declarations, "declarations");
		if (version < 0) {
			throw new IllegalArgumentException(
					"Field schema version " + version + " must not be negative");
		}
		// Kun de deklarationer, der er aktive i NETOP denne version. Frasorteringen
		// er den erklærede hensigt og ikke en slugt tastefejl: since er inklusiv og
		// har 0 som default, så en udeladt version filtrerer ingenting bort — kun
		// et felt, nogen har sat et vindue på, kan falde ud.
		//
		// Den sker FØR dubletkontrollen nedenfor, fordi to deklarationer med samme
		// key og adskilte vinduer er lovlig historik: feltet blev trukket tilbage i
		// én version og genindført i en senere. Deduplikeredes der først, ville
		// historikken være en opstartsfejl.
		declarations = List.copyOf(declarations).stream()
				.filter(declaration -> declaration.isActiveAt(version))
				.toList();
		Map<CustomFieldScope, Map<String, CustomFieldDeclaration>> byScope = new LinkedHashMap<>();
		for (CustomFieldDeclaration declaration : declarations) {
			CustomFieldDeclaration clash = byScope
					.computeIfAbsent(declaration.scope(), scope -> new LinkedHashMap<>())
					.putIfAbsent(declaration.key(), declaration);
			if (clash != null) {
				throw new IllegalArgumentException("Field schema declares key [" + declaration.key()
						+ "] twice in scope " + declaration.scope());
			}
		}
	}

	/** Det tomme skema. Dét en operatør uden egne felter arbejder under. */
	public static CustomFieldSchema empty(int version) {
		return new CustomFieldSchema(version, List.of());
	}

	/**
	 * Deklarationen for én key på én entitetstype, eller tom hvis kunden sender
	 * et felt, vi ikke kender.
	 *
	 * <p>Tom er IKKE det samme som "spring den over": kalderen ved ingest
	 * afviser den med {@code UNKNOWN_FIELD_KEY}. En stiltiende ignoreret nøgle er
	 * præcis dét, PLAN §2.6 kræver afvist — kunden ville ellers tro, feltet var
	 * gemt.
	 */
	public Optional<CustomFieldDeclaration> find(CustomFieldScope scope, String key) {
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(key, "key");
		return this.declarations.stream()
				.filter(declaration -> declaration.scope() == scope && declaration.key().equals(key))
				.findFirst();
	}

	/**
	 * Alle deklarationer på én entitetstype, i deklarationsrækkefølge. Det er
	 * dén liste, {@code required}-kontrollen ved katalog-push går igennem.
	 */
	public List<CustomFieldDeclaration> forScope(CustomFieldScope scope) {
		Objects.requireNonNull(scope, "scope");
		return this.declarations.stream()
				.filter(declaration -> declaration.scope() == scope)
				.toList();
	}

}

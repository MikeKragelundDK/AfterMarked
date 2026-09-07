package dk.aftermarked.catalog.internal;

import java.util.Map;
import java.util.Objects;

/**
 * Én tekst i én eller flere sprogversioner, som den stod i kundens
 * katalog-payload (PLAN §2.9).
 *
 * <p>{@code base} er værdien under operatørens EGET basissprog
 * ({@code operator.default_locale}, V4) og bliver grundfeltet på entiteten —
 * {@code organizer.name}, {@code event.title}, {@code event.venue},
 * {@code ticket_type.name}. {@code byLocale} er de ØVRIGE sprog, keyet på
 * locale-tag, og de bliver rækker i {@code translation}.
 *
 * <p><strong>Basissproget står ALDRIG i {@code byLocale}.</strong> Delingen er
 * hele pointen: grundteksten er sandheden, og oversættelsen er et lag ovenpå
 * med grundteksten som fallback. Lå basissproget begge steder, ville den samme
 * tekst findes i to lagre, der kan komme fra hinanden — og
 * {@code Translations.localize} ville læse en kopi frem for grundfeltet, som
 * fallback-kæden ender på.
 *
 * <p>Typen kan ikke selv håndhæve den regel: den kender ikke operatørens
 * basissprog. Delingen sker i {@link CatalogPayloadParser}, som er det eneste
 * sted, en {@code TranslatableText} bliver til — og det er også dér, den er
 * testet.
 *
 * <p><strong>Hvorfor typen ligger i {@code internal}</strong> (spec-beslutning
 * #11): payload-typerne bærer feltværdier, og lå de i den offentlige
 * {@code catalog}-pakke, kunne ethvert modul med
 * {@code allowedDependencies = {"catalog"}} nå feltlageret gennem den
 * unavngivne grænseflade, uden at ArchUnit-reglen (som kun matcher
 * {@code catalog.fields..}) nogensinde fyrede.
 *
 * @param base teksten på operatørens basissprog. Aldrig tom, aldrig blank —
 *     parseren afviser en blank tekst med {@code BLANK_VALUE}
 * @param byLocale de øvrige sprog, locale-tag ({@code en}, {@code en-GB}) →
 *     tekst. Tom, når kunden sendte en almindelig streng. Uforanderlig kopi
 */
public record TranslatableText(String base, Map<String, String> byLocale) {

	public TranslatableText {
		Objects.requireNonNull(base, "base");
		Objects.requireNonNull(byLocale, "byLocale");
		// Map.copyOf afviser null-nøgler og null-værdier og giver en uforanderlig
		// kopi. Uden kopien ville kalderens map kunne ændre sig, efter parseren
		// har valideret det — og payloaden er præcis det, en kunde ejer.
		byLocale = Map.copyOf(byLocale);
	}

	/** Teksten uden oversættelser — formen, en almindelig JSON-streng bliver til. */
	public static TranslatableText of(String base) {
		return new TranslatableText(base, Map.of());
	}

	/** Om teksten bærer oversættelser ud over grundteksten. */
	public boolean isTranslated() {
		return !this.byLocale.isEmpty();
	}

}

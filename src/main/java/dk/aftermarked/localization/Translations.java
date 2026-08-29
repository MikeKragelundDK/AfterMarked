package dk.aftermarked.localization;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import dk.aftermarked.tenancy.OperatorContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Oversættelser af <em>kundens egne</em> tekster: operatørnavne, og senere
 * event-titler og beskrivelser. Systemets egne tekster ligger i
 * {@code messages*.properties} og kommer aldrig herigennem.
 *
 * <p><strong>Grundteksten er sandheden, oversættelsen er et lag ovenpå.</strong>
 * {@link #localize} falder altid tilbage til den tekst, kaldet selv medbringer
 * (fx {@code operator.name()}), så en manglende oversættelse giver den originale
 * tekst — aldrig en tom side og aldrig et nøglenavn. Det er også dét, der gør
 * tabellen additiv: en replika, der ikke kender oversættelser, viser bare
 * grundteksten.
 *
 * <p><strong>Tenant-isolation.</strong> Læsning filtreres af RLS-policyen på
 * {@code translation}; der er ingen {@code operator_id} i en eneste WHERE her,
 * fordi databasen er den, der håndhæver det. Skrivning sætter
 * {@code operator_id} fra {@link OperatorContext#required()} — aldrig fra et
 * argument, så en kalder ikke kan skrive ind i en fremmed operatørs data.
 */
@Component
public class Translations {

	private final JdbcClient jdbc;

	Translations(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Oversættelsen af ét felt, hvis den findes for præcis dette sprog.
	 *
	 * <p>Der er ingen fallback fra {@code en-GB} til {@code en} her — den slags
	 * hører til den dag, vi understøtter regionsvarianter i fladen.
	 */
	public Optional<String> find(String entityType, UUID entityId, String field, Locale locale) {
		Objects.requireNonNull(entityType, "entityType");
		Objects.requireNonNull(entityId, "entityId");
		Objects.requireNonNull(field, "field");
		Objects.requireNonNull(locale, "locale");
		return jdbc.sql("""
				SELECT value FROM translation
				 WHERE entity_type = :entityType AND entity_id = :entityId
				   AND field = :field AND locale = :locale
				""")
				.param("entityType", entityType)
				.param("entityId", entityId)
				.param("field", field)
				.param("locale", tag(locale))
				.query(String.class)
				.optional();
	}

	/**
	 * Oversættelsen, eller {@code fallback} hvis der ikke findes en. Det er den
	 * metode, skabeloner og controllere skal bruge — den kan ikke give
	 * ingenting.
	 */
	public String localize(String entityType, UUID entityId, String field, Locale locale, String fallback) {
		// Et null-fallback ville give praecis den tomme side, kontrakten lover aldrig sker.
		Objects.requireNonNull(fallback, "fallback");
		return find(entityType, entityId, field, locale).orElse(fallback);
	}

	/**
	 * Skriver eller opdaterer én oversættelse for den operatør, konteksten peger
	 * på. Fejler højlydt uden kontekst — en oversættelse uden ejer hører ingen
	 * steder hjemme.
	 */
	@Transactional
	public void put(String entityType, UUID entityId, String field, Locale locale, String value) {
		Objects.requireNonNull(value, "value");
		UUID operatorId = OperatorContext.required().value();
		jdbc.sql("""
				INSERT INTO translation (operator_id, entity_type, entity_id, field, locale, value)
				VALUES (:operatorId, :entityType, :entityId, :field, :locale, :value)
				ON CONFLICT (operator_id, entity_type, entity_id, field, locale)
				DO UPDATE SET value = EXCLUDED.value, updated_at = now()
				""")
				.param("operatorId", operatorId)
				.param("entityType", entityType)
				.param("entityId", entityId)
				.param("field", field)
				.param("locale", tag(locale))
				.param("value", value)
				.update();
	}

	/**
	 * Sprogkoden som den lagres: {@code da}, {@code en}, {@code en-GB}. Javas
	 * {@code toLanguageTag()} giver præcis den form, CHECK-constrainten kræver,
	 * og {@code Locale.of("da")} og {@code Locale.forLanguageTag("da")} lander
	 * dermed samme sted.
	 */
	private static String tag(Locale locale) {
		return locale.toLanguageTag();
	}

}

package dk.aftermarked;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Global hærdning af JSON-læsningen. Dette er PENGE-kode, selvom der ikke står
 * et beløb i den: to af Jacksons egne defaults forvandler et beløb til et andet
 * beløb uden at logge noget.
 *
 * <p>Boot 4.1 anvender NUL egne Jackson-defaults (verificeret i bytekoden: hele
 * blokken ligger bag {@code isUseJackson2Defaults()}, som er false). Appen kører
 * altså på rå Jackson 3-defaults, og de to farlige er:
 * <ul>
 *   <li>{@code ACCEPT_FLOAT_AS_INT = true} — {@code {"amount": 300.50}} bliver
 *       til {@code 300}. Halvtreds øre forsvinder, og intet siger noget.</li>
 *   <li>{@code ALLOW_COERCION_OF_SCALARS = true} — {@code {"amount": "300"}}
 *       bliver til {@code 300}, og dermed kan en streng-typet felt i en
 *       integration i stilhed blive til et beløb.</li>
 * </ul>
 *
 * <p>Katalog-stien rammes ikke af dem (dens parser læser en {@code JsonNode} og
 * har ingen måltype at koble en bindings-regel til), men de skal lukkes FØR det
 * første JSON-endpoint med et beløb findes — Stripe-webhooks, hand-off-payloads
 * og alt, der kommer efter. Forsvar i dybden er billigt her og dyrt bagefter.
 *
 * <p><strong>Om formen.</strong> Jackson 3-mappere er immutable, så der findes
 * ingen {@code mapper.configure()}: konfigurationen skal ind i byggeren, før
 * mapperen findes. Og bønnetypen er
 * {@link JsonMapperBuilderCustomizer} fra {@code spring-boot-jackson} — en
 * {@code Jackson2ObjectMapperBuilderCustomizer} (Boot 3-vanen) kompilerer,
 * starter og bliver i STILHED ignoreret.
 *
 * <p><strong>Tre ting gøres bevidst IKKE.</strong>
 * <ol>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES} sættes ikke globalt. Den ville ramme
 *       {@code event_publication}-rækker skrevet af en N-1-replika, der kender
 *       ét felt mere end den replika, der læser dem — altså præcis den forskudte
 *       rollout, hele skemadisciplinen er bygget til at overleve. Kontrakt-drift
 *       på katalog-stien lukkes i stedet af parseren, som sammenholder feltnavne
 *       mod et eksplicit sæt pr. niveau.</li>
 *   <li>{@code FAIL_ON_NULL_FOR_PRIMITIVES} røres ikke. Den er true i Jackson 3
 *       (modsat 2.x) — gratis og rigtigt: et manglende beløb bliver en fejl og
 *       ikke et nul.</li>
 *   <li>{@code spring.jackson.use-jackson2-defaults} sættes ALDRIG. Den ville
 *       slå {@code FAIL_ON_UNKNOWN_PROPERTIES} fra og lade float-trunkeringen
 *       stå — begge dele på én gang, og ingen af dem synlige i en diff.</li>
 * </ol>
 */
@Configuration
public class JsonConfig {

	/**
	 * Bønnen har med vilje ingen {@code @Order}: uden en er den sidste i
	 * rækken, og en customizer, der kører sidst, kan ikke overskrives af Boots
	 * egen standard-customizer.
	 *
	 * <p>Coercion-reglerne sættes på {@code LogicalType.Integer} og ikke på
	 * {@code long.class}: den logiske type dækker hvert eneste heltalsfelt,
	 * uanset om det er en {@code long}, en {@code int} eller en indpakket
	 * variant — så et nyt felt er dækket, uden at nogen skal huske at tilføje
	 * det.
	 */
	@Bean
	JsonMapperBuilderCustomizer strictMoneyBinding() {
		return builder -> builder
				.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
				.withCoercionConfig(LogicalType.Integer, config -> {
					config.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
					config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
					config.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
					config.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
				});
	}

}

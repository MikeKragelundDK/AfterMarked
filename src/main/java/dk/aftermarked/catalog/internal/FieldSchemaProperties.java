package dk.aftermarked.catalog.internal;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feltskemaet som det står i config: {@code base.yml} plus én mappe pr.
 * operatør under {@code src/main/resources/operators/} (PLAN §2.6).
 *
 * <p><strong>Nøglen i {@link #operators()} ER operatørens slug</strong> — aldrig
 * et {@code operator_id}. Slug'en er stabil, læselig og står allerede i URL'er;
 * et id ville gøre config-filen ulæselig og binde den til én database. Mappenavnet
 * under {@code operators/} skal være den samme streng.
 *
 * <p><strong>{@code ignoreUnknownFields = false} er ikke pynt.</strong>
 * Defaulten er {@code true}, og med den ville en stavefejl i en kundes config-fil
 * — {@code fieldz} i stedet for {@code fields}, {@code scop} i stedet for
 * {@code scope} — stilfærdigt blive til en default. Kunden ville tro, feltet var
 * deklareret, og hvert push ville afvise det som en ukendt nøgle. Det er præcis
 * den fejlklasse, PLAN §2.6 kræver afvist, så en ubundet nøgle under præfikset er
 * en OPSTARTSFEJL.
 *
 * <p><strong>Valideringen er håndskrevet.</strong> Der tilføjes bevidst ikke
 * {@code spring-boot-starter-validation}: {@code jakarta.validation} er ikke på
 * classpathen, og starteren trækker {@code org.hibernate.validator} med sig — én
 * import derfra gør {@code PersistenceStyleInvariantTest} rød, fordi den scanner
 * efter netop Hibernate. Reglerne for én deklaration ligger i stedet i
 * {@code CustomFieldDeclaration}s kompakte konstruktør, og reglerne, der kræver
 * hele listen, i {@link FieldSchemaRegistry}.
 *
 * <p><strong>{@code type} og {@code scope} bindes som strenge</strong> og ikke
 * direkte til enum'erne. Boots lempelige enum-konvertering ville acceptere både
 * {@code TICKET_TYPE}, {@code ticket-type} og {@code ticketType} og fejle med sin
 * egen ordlyd; her er {@link dk.aftermarked.catalog.fields.CustomFieldScope#configName()}
 * den ene autoritative stavemåde i YAML'en, og fejlbeskeden navngiver både
 * kilden og key'en.
 */
@ConfigurationProperties(prefix = "theticket.field-schema", ignoreUnknownFields = false)
public record FieldSchemaProperties(OperatorFieldSchema base, Map<String, OperatorFieldSchema> operators) {

	public FieldSchemaProperties {
		// En manglende base er lovlig og betyder "ingen faelles felter". Et
		// manglende operators-afsnit er lovligt paa samme maade: en kunde uden
		// egne felter er den normale tilstand ved onboarding.
		base = (base == null) ? OperatorFieldSchema.EMPTY : base;
		operators = (operators == null) ? Map.of() : Map.copyOf(operators);
	}

	/**
	 * Ét skema — enten det fælles {@code base} eller én operatørs egne felter.
	 *
	 * <p>{@code version} er den feltskema-version, en række stemples med, når
	 * dens værdier skrives. Den tælles OP og aldrig om: en værdi skrevet under
	 * version 1 skal stadig kunne læses, den dag skemaet står på 5.
	 */
	public record OperatorFieldSchema(int version, List<FieldDeclarationProperties> fields) {

		/** Skemaet for en kunde uden egne felter. */
		static final OperatorFieldSchema EMPTY = new OperatorFieldSchema(0, List.of());

		public OperatorFieldSchema {
			// List.copyOf afviser null-elementer, saa et tomt punkt i YAML-listen
			// stopper her og ikke som en NullPointerException i registret.
			fields = (fields == null) ? List.of() : List.copyOf(fields);
			if (version < 0) {
				throw new IllegalArgumentException(
						"Field schema version " + version + " must not be negative");
			}
		}

	}

	/**
	 * Én deklaration, som den står i YAML'en. Rå strenge: konverteringen til
	 * {@link dk.aftermarked.catalog.fields.CustomFieldDeclaration} sker i
	 * {@link FieldSchemaRegistry}, som er det eneste sted, en config-fejl kan
	 * blive til en opstartsfejl med kilde og key i beskeden.
	 *
	 * <p>{@code since} er en primitiv {@code int}, så en udeladt {@code since}
	 * binder til 0 og ikke til en opstartsfejl — 0 er også V5's default for
	 * {@code custom_fields_schema_version}. {@code until} er en {@code Integer},
	 * fordi "ingen slutversion" er en reel tilstand og ikke er nul.
	 */
	public record FieldDeclarationProperties(String key, String type, String scope, String role,
			boolean required, boolean translatable, List<String> values, int since, Integer until) {

		public FieldDeclarationProperties {
			values = (values == null) ? List.of() : List.copyOf(values);
		}

	}

}

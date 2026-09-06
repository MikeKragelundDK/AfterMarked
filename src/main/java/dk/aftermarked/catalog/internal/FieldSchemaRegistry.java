package dk.aftermarked.catalog.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dk.aftermarked.catalog.fields.CustomFieldDeclaration;
import dk.aftermarked.catalog.fields.CustomFieldSchema;
import dk.aftermarked.catalog.fields.CustomFieldScope;
import dk.aftermarked.catalog.fields.CustomFieldType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Det opløste feltskema pr. operatør: {@code base.yml} plus operatørens egen
 * mappe under {@code operators/}, hvor operatørens deklaration vinder på samme
 * key (PLAN §2.6).
 *
 * <p><strong>Arven er en eksplicit merge i Java.</strong> Boots binder kan ikke
 * flette to forskellige property-nøgler — {@code theticket.field-schema.base} og
 * {@code theticket.field-schema.operators.<slug>} er to uafhængige træer, og en
 * binder, der "arvede", ville skulle gætte, hvad der overskriver hvad. Her står
 * reglen ét sted, og den kan læses.
 *
 * <p><strong>Krydsfelt-reglerne bor her, fordi de kræver HELE listen.</strong>
 * {@link CustomFieldSchema}s konstruktør frasorterer deklarationer uden for
 * versionens vindue, så den historik, reglerne skal se, er væk, i samme øjeblik
 * skemaet er bygget. To regler køres derfor på registrets egen sammenflettede
 * liste, FØR et {@code CustomFieldSchema} findes:
 * <ol>
 * <li><strong>Et key skifter ALDRIG type.</strong> Kontrollen kører på
 *     {@code base} ∪ operatørens egne, altså FØR overskrivningen: en operatør må
 *     gerne overtage et fælles felts {@code role}, {@code required},
 *     {@code values} eller versionsvindue, men aldrig dets type. Det er
 *     expand-migrate-contract anvendt på config, og det er dét, der gør en
 *     LAGRET værdis type kendt for altid — et {@code image}, der blev til et
 *     {@code money}, ville gøre hver eneste allerede skrevne værdi utolkelig,
 *     og der findes ingen migration af en JSONB-kolonne, ingen har skemaet
 *     til.</li>
 * <li><strong>To vinduer for samme key må ikke overlappe.</strong> Efter
 *     overskrivningen: et key trukket tilbage i version 3 og genindført i
 *     version 5 er lovlig historik, men to samtidige deklarationer er
 *     tvetydige, og {@link CustomFieldSchema#find} ville vælge en vilkårlig
 *     vinder. Kontrollen dækker ALLE versioner, ikke kun den aktuelle, så
 *     {@link #schemaAt} er sikker at slå op i for en gammel række.</li>
 * </ol>
 *
 * <p><strong>Alt bygges i konstruktøren.</strong> Det ER opstartsfejlen: en
 * ugyldig config-fil skal stoppe deployet, ikke et natligt push klokken 03,
 * hvor ingen læser loggen. Hver besked navngiver både kilden ({@code base} eller
 * slug'en) og key'en.
 *
 * <p><strong>En operatør uden egen fil falder tilbage til {@code base} og logger
 * på WARN én gang pr. slug.</strong> Det holder de seedede testoperatører
 * kørende — men det betyder også, at en nyonboardet kunde uden fil får sine
 * custom felter afvist i hvert eneste push. Derfor navngiver
 * {@link CustomFieldValidator} slug'en i afvisningen. Opstarts-krydstjekket mod
 * {@code operator}-tabellen, som PLAN §2.6 beder om, findes ikke: det ville
 * kræve et {@code SELECT slug FROM operator} UDEN operator-kontekst, og
 * runtime-rollen har hverken {@code BYPASSRLS} eller en {@code SECURITY
 * DEFINER}-lister at gå igennem. Halvdelen fås gratis af
 * {@code spring.config.import}, som fejler ved opstart på en manglende fil.
 */
@Component
public class FieldSchemaRegistry {

	/** Kilden i en fejlbesked, når deklarationen kommer fra det fælles skema. */
	static final String BASE_SOURCE = "base";

	private static final Logger log = LoggerFactory.getLogger(FieldSchemaRegistry.class);

	private final OperatorFieldHistory base;

	private final Map<String, OperatorFieldHistory> byOperator;

	/**
	 * Slugs, vi allerede har advaret om. Mængden vokser kun med operatører, der
	 * FINDES i databasen — slug'en kommer fra den operator-række, værtsnavnet
	 * blev opløst til, aldrig fra bruger-input — så den er afgrænset af antallet
	 * af kunder og ikke af trafikken.
	 */
	private final Set<String> slugsWarnedAbout = ConcurrentHashMap.newKeySet();

	public FieldSchemaRegistry(FieldSchemaProperties properties) {
		Objects.requireNonNull(properties, "properties");
		List<CustomFieldDeclaration> shared = declarationsOf(BASE_SOURCE, properties.base());
		requireOneTypePerKey(BASE_SOURCE, shared);
		requireDisjointVersionWindows(BASE_SOURCE, shared);
		this.base = historyOf(BASE_SOURCE, properties.base().version(), shared);
		Map<String, OperatorFieldHistory> resolved = new LinkedHashMap<>();
		for (Map.Entry<String, FieldSchemaProperties.OperatorFieldSchema> entry : properties.operators().entrySet()) {
			String slug = entry.getKey();
			List<CustomFieldDeclaration> own = declarationsOf(slug, entry.getValue());
			// Typereglen ser base OG operatørens egne, FØR overskrivningen. Læste
			// den kun det flettede resultat, ville en operatør kunne omdefinere et
			// fælles felts type ved simpelthen at vinde over det.
			List<CustomFieldDeclaration> everything = new ArrayList<>(shared);
			everything.addAll(own);
			requireOneTypePerKey(slug, everything);
			List<CustomFieldDeclaration> merged = merge(shared, own);
			requireDisjointVersionWindows(slug, merged);
			resolved.put(slug, historyOf(slug, entry.getValue().version(), merged));
		}
		this.byOperator = Map.copyOf(resolved);
	}

	/**
	 * Message-nøglen for feltets label: {@code field.<key>.label}.
	 *
	 * <p>Konventionen fastlægges og testes NU, selvom mekanismen — en
	 * per-operatør {@code MessageSource} — er udskudt til den sprint, der først
	 * renderer et felt. Grunden til, at labels IKKE står i YAML'en, er den samme
	 * som for al anden systemtekst: en label i en config-fil omgår
	 * {@code MessageSource}, {@code LocalizationOpsTest}s regel om identiske
	 * nøglesæt ville aldrig se en manglende oversættelse, og et nyt sprog ville
	 * betyde en redigering af N config-filer i stedet for én bundle.
	 *
	 * <p>Den dag cachen bygges: nøglen SKAL være
	 * {@code (operator_id, locale, code)}. Uden operatøren er det en anden kundes
	 * tekst, uden sproget er det forkert sprog for den næste bruger.
	 */
	public static String labelKey(CustomFieldDeclaration declaration) {
		Objects.requireNonNull(declaration, "declaration");
		return "field." + declaration.key() + ".label";
	}

	/**
	 * Operatørens feltskema i den version, dens rækker stemples med i dag.
	 *
	 * <p>Det er dét skema, et katalog-push valideres imod, og dets
	 * {@link CustomFieldSchema#version()} er den værdi, der skrives i
	 * {@code custom_fields_schema_version}.
	 */
	public CustomFieldSchema forOperator(String slug) {
		return historyFor(slug).active();
	}

	/**
	 * Operatørens feltskema, som det så ud i en GIVEN version.
	 *
	 * <p>Læsesiden. En række bærer selv den version, dens værdier blev valideret
	 * under, og en værdi skrevet under version 1 skal stadig kunne læses den dag,
	 * skemaet står på 5 — også hvis feltet i mellemtiden er trukket tilbage med
	 * {@code until}. Slå derfor op med rækkens eget
	 * {@code custom_fields_schema_version} og ikke med det aktuelle: et felt, der
	 * er væk i dag, har stadig en kendt type i den version, værdien blev skrevet
	 * i.
	 */
	public CustomFieldSchema schemaAt(String slug, int schemaVersion) {
		OperatorFieldHistory history = historyFor(slug);
		if (schemaVersion == history.version()) {
			return history.active();
		}
		return new CustomFieldSchema(schemaVersion, history.declarations());
	}

	private OperatorFieldHistory historyFor(String slug) {
		Objects.requireNonNull(slug, "slug");
		OperatorFieldHistory history = this.byOperator.get(slug);
		if (history != null) {
			return history;
		}
		if (this.slugsWarnedAbout.add(slug)) {
			log.warn("Operator [{}] has no field schema of its own and falls back to the shared base schema; "
					+ "add src/main/resources/operators/{}/field-schema.yml before its first catalog push", slug,
					slug);
		}
		return this.base;
	}

	private static OperatorFieldHistory historyOf(String source, int version,
			List<CustomFieldDeclaration> declarations) {
		try {
			return new OperatorFieldHistory(version, declarations, new CustomFieldSchema(version, declarations));
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException(
					"Field schema for [" + source + "] cannot be resolved: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Operatørens egne deklarationer erstatter {@code base}s for samme
	 * (scope, key) — ALLE af dem, ikke bare den, der tilfældigvis er aktiv i dag.
	 * Blev en enkelt af {@code base}s historiske vinduer stående, ville
	 * operatørens eget vindue overlappe det, og opslaget ville have to svar.
	 */
	private static List<CustomFieldDeclaration> merge(List<CustomFieldDeclaration> shared,
			List<CustomFieldDeclaration> own) {
		Set<String> overridden = new HashSet<>();
		for (CustomFieldDeclaration declaration : own) {
			overridden.add(identityOf(declaration));
		}
		List<CustomFieldDeclaration> merged = new ArrayList<>();
		for (CustomFieldDeclaration declaration : shared) {
			if (!overridden.contains(identityOf(declaration))) {
				merged.add(declaration);
			}
		}
		merged.addAll(own);
		return List.copyOf(merged);
	}

	private static void requireOneTypePerKey(String source, List<CustomFieldDeclaration> declarations) {
		Map<String, CustomFieldDeclaration> firstByIdentity = new LinkedHashMap<>();
		for (CustomFieldDeclaration declaration : declarations) {
			CustomFieldDeclaration first = firstByIdentity.putIfAbsent(identityOf(declaration), declaration);
			if (first != null && first.type() != declaration.type()) {
				throw new IllegalStateException("Field schema for [" + source + "] declares key ["
						+ declaration.key() + "] in scope " + declaration.scope() + " as both " + first.type()
						+ " and " + declaration.type()
						+ "; a custom field key never changes type, or the type of an already stored value stops "
						+ "being knowable");
			}
		}
	}

	private static void requireDisjointVersionWindows(String source, List<CustomFieldDeclaration> declarations) {
		Map<String, List<CustomFieldDeclaration>> byIdentity = new LinkedHashMap<>();
		for (CustomFieldDeclaration declaration : declarations) {
			byIdentity.computeIfAbsent(identityOf(declaration), identity -> new ArrayList<>()).add(declaration);
		}
		for (List<CustomFieldDeclaration> sameKey : byIdentity.values()) {
			for (int first = 0; first < sameKey.size(); first++) {
				for (int second = first + 1; second < sameKey.size(); second++) {
					if (overlaps(sameKey.get(first), sameKey.get(second))) {
						CustomFieldDeclaration declaration = sameKey.get(first);
						throw new IllegalStateException("Field schema for [" + source + "] declares key ["
								+ declaration.key() + "] in scope " + declaration.scope()
								+ " twice with overlapping version windows; separate the two declarations with "
								+ "since/until, or the lookup picks an arbitrary winner");
					}
				}
			}
		}
	}

	/**
	 * Vinduerne er halvåbne, {@code [since, until)}, så to på hinanden følgende
	 * vinduer er sammenhængende uden at overlappe: {@code until: 3} og
	 * {@code since: 3} er nabo-vinduer og ikke en dublet. {@code until == null}
	 * er "uden ende".
	 */
	private static boolean overlaps(CustomFieldDeclaration one, CustomFieldDeclaration other) {
		return startsBefore(one.since(), other.until()) && startsBefore(other.since(), one.until());
	}

	private static boolean startsBefore(int since, Integer until) {
		return until == null || since < until;
	}

	/**
	 * Nøglerummet ligger på ENTITETEN og ikke globalt, så {@code logo} må lovligt
	 * findes både på arrangøren og på eventet. Identiteten er derfor parret og
	 * ikke key'en alene.
	 */
	private static String identityOf(CustomFieldDeclaration declaration) {
		return declaration.scope().name() + '.' + declaration.key();
	}

	private static List<CustomFieldDeclaration> declarationsOf(String source,
			FieldSchemaProperties.OperatorFieldSchema schema) {
		List<CustomFieldDeclaration> declarations = new ArrayList<>();
		for (FieldSchemaProperties.FieldDeclarationProperties field : schema.fields()) {
			declarations.add(declarationOf(source, field));
		}
		return List.copyOf(declarations);
	}

	private static CustomFieldDeclaration declarationOf(String source,
			FieldSchemaProperties.FieldDeclarationProperties field) {
		if (field.key() == null) {
			throw new IllegalStateException("Field schema for [" + source + "] declares a field without a key");
		}
		String key = field.key();
		CustomFieldType type = typeOf(source, key, field.type());
		CustomFieldScope scope = scopeOf(source, key, field.scope());
		try {
			return new CustomFieldDeclaration(key, type, scope, field.role(), field.required(), field.translatable(),
					field.values(), field.since(), field.until());
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			// Reglerne for ÉN deklaration bor i CustomFieldDeclarations kompakte
			// konstruktør; her ombrydes de kun, så beskeden også navngiver den fil,
			// fejlen står i.
			throw new IllegalStateException(
					"Field schema for [" + source + "] declares an illegal field: " + ex.getMessage(), ex);
		}
	}

	/**
	 * {@code type} bindes som en streng og ikke direkte til enum'et: Boots
	 * lempelige enum-konvertering ville acceptere {@code TICKET_TYPE},
	 * {@code ticket-type} og {@code ticketType} som samme værdi og fejle med sin
	 * egen ordlyd. Her er der én stavemåde, og fejlbeskeden navngiver kilden,
	 * key'en og de lovlige værdier.
	 */
	private static CustomFieldType typeOf(String source, String key, String raw) {
		if (raw == null) {
			throw new IllegalStateException(
					"Field schema for [" + source + "] declares field [" + key + "] without a type");
		}
		for (CustomFieldType type : CustomFieldType.values()) {
			if (type.name().toLowerCase(Locale.ROOT).equals(raw)) {
				return type;
			}
		}
		throw new IllegalStateException("Field schema for [" + source + "] declares field [" + key
				+ "] with unknown type [" + raw + "]; the legal types are "
				+ Stream.of(CustomFieldType.values())
						.map(type -> type.name().toLowerCase(Locale.ROOT))
						.collect(Collectors.joining(", ")));
	}

	private static CustomFieldScope scopeOf(String source, String key, String raw) {
		if (raw == null) {
			throw new IllegalStateException(
					"Field schema for [" + source + "] declares field [" + key + "] without a scope");
		}
		for (CustomFieldScope scope : CustomFieldScope.values()) {
			if (scope.configName().equals(raw)) {
				return scope;
			}
		}
		throw new IllegalStateException("Field schema for [" + source + "] declares field [" + key
				+ "] with unknown scope [" + raw + "]; the legal scopes are "
				+ Stream.of(CustomFieldScope.values())
						.map(CustomFieldScope::configName)
						.collect(Collectors.joining(", ")));
	}

	/**
	 * Én operatørs FULDE historik plus det skema, der gælder i dag.
	 *
	 * <p>Historikken beholdes, fordi læsesiden har brug for den: en række
	 * skrevet under version 1 skal kunne tolkes med version 1's deklarationer,
	 * også når feltet er trukket tilbage i dag.
	 */
	private record OperatorFieldHistory(int version, List<CustomFieldDeclaration> declarations,
			CustomFieldSchema active) {
	}

}

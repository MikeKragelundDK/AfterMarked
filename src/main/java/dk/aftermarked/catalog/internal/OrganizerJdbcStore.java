package dk.aftermarked.catalog.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

import dk.aftermarked.catalog.Organizer;
import dk.aftermarked.catalog.OrganizerRef;
import dk.aftermarked.catalog.fields.CustomFieldScope;
import dk.aftermarked.catalog.fields.CustomFieldValues;
import dk.aftermarked.tenancy.OperatorContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Al SQL mod {@code organizer}. Ingen anden klasse skriver eller læser den
 * tabel — samlingen er ikke pænhed, men dét, der gør en revision af
 * tenant-adfærden til at læse ét sted.
 *
 * <p><strong>{@code operator_id} bindes aldrig.</strong> Hver eneste
 * indsættelse skriver {@code current_operator_id()} ordret i SQL'en, og intet
 * record i {@code catalog} har et {@code operatorId}-felt (beslutning §8). Der
 * findes dermed ingen kodesti, der KAN bære et forkert tenant-id ind i en
 * skrivning — en strukturel garanti frem for at stole på, at RLS' WITH CHECK
 * fanger fejlen bagefter. En invariant-test læser denne fils kilde og fejler,
 * hvis nogen alligevel binder en parameter ved det navn.
 *
 * <p><strong>Kalderen ejer transaktionen.</strong> Ingen metode her er
 * {@code @Transactional}, og det er med vilje: skrivestien tager
 * {@code pg_advisory_xact_lock} som sin første sætning, og en lås, der er
 * bundet til transaktionen, holder kun så længe kalderens transaktion gør. Åbnede
 * hver metode sin egen, ville låsen være væk, inden batchen var skrevet.
 *
 * @see EventJdbcStore
 */
@Component
public class OrganizerJdbcStore {

	/**
	 * Ref'en på den arrangør, VI syntetiserer for kunder, der ikke skelner
	 * (PLAN §2.3). Tabellens CHECK TILLADER den, fordi vi selv skriver rækken;
	 * validatoren afviser den fra et katalog-push, så en kunde aldrig kan
	 * omdøbe den arrangør, alle skelet-events hænger på.
	 */
	public static final String DEFAULT_REF = OrganizerRef.RESERVED_DEFAULT;

	/**
	 * Entitetsnavnet i {@code translation} og i {@code audit_log}. Hentet fra
	 * feltskemaets eget scope-enum, så de to stavemåder ikke kan glide fra
	 * hinanden: config skriver camelCase, oversættelseslaget snake_case, og
	 * V3's CHECK afviser camelCase.
	 */
	public static final String ENTITY_TYPE = CustomFieldScope.ORGANIZER.entityType();

	/**
	 * De felter, et katalog-push EJER på en arrangør — argumentet til
	 * {@code Translations.replaceAll}.
	 *
	 * <p>Mængden er fast og lille med vilje. Var den hele {@code entity_type},
	 * ville det natlige push slette operatørens egne admin-oversættelser af
	 * andre felter kl. 03 uden en linje i audit-loggen.
	 */
	public static final Set<String> TRANSLATED_FIELDS = Set.of("name");

	private final JdbcClient jdbc;

	OrganizerJdbcStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Skriver hele batchen med ÉN sætning og rører kun de rækker, der faktisk
	 * ændrer sig.
	 *
	 * <p><strong>Nul returnerede rækker er SUCCES-signalet, ikke en fejl.</strong>
	 * Et andet, identisk push skal ikke ændre noget: {@code WHERE ... IS
	 * DISTINCT FROM ...} gør {@code DO UPDATE} til en no-op, sætningen svarer
	 * {@code INSERT 0 0}, og hverken {@code version}, {@code updated_at} eller
	 * rækkens {@code xmin} flytter sig. Kode, der behandler 0 som en fejl,
	 * forvandler hvert eneste no-op-push til en falsk alarm kl. 03.
	 *
	 * <p>Fire ting i sætningen er mekanik og ikke stil — hver af dem er en
	 * stille fejlkilde:
	 * <ol>
	 *   <li>{@code version} og {@code updated_at} står i {@code SET} og
	 *       <strong>aldrig</strong> i {@code IS DISTINCT FROM}-tuplen.
	 *       {@code EXCLUDED.version} er kolonnens default (1) og
	 *       {@code EXCLUDED.updated_at} er {@code now()}; enten af dem i tuplen
	 *       gør hver eneste række forskellig og dræber no-op'en helt.</li>
	 *   <li>{@code custom_fields_schema_version} står samme sted og af en anden
	 *       grund: stemplet beskriver VÆRDIERNE. Stod det i tuplen, ville en ren
	 *       konfigurations-bump omskrive hver eneste uændrede række, flytte
	 *       {@code updated_at} og udsende en audit-linje pr. række.</li>
	 *   <li>{@code IS DISTINCT FROM} og aldrig {@code <>}: en nullable kolonne
	 *       får {@code <>} til at give NULL, og rækken ville blive sprunget over
	 *       for evigt.</li>
	 *   <li>Målet er alias-kvalificeret i {@code WHERE} ({@code o.name}) og
	 *       aldrig i {@code SET}. Omvendt giver
	 *       {@code [42702] column reference is ambiguous}.</li>
	 * </ol>
	 *
	 * <p>Konfliktmålet indeholder {@code operator_id}. Uden det ville en
	 * krydstenant-kollision enten fejle på en måde, der afslører en anden kunde,
	 * eller — med {@code DO NOTHING} — rapportere succes uden at skrive noget.
	 *
	 * @param rows          den normaliserede projektion, ikke kundens payload:
	 *                      grundværdier, oversættelser allerede pillet ud,
	 *                      fremmednøgler allerede opløst
	 * @param schemaVersion den feltskema-version, hele batchen blev valideret
	 *                      under
	 * @return kun de rækker, der blev oprettet eller ændret — i uspecificeret
	 *         rækkefølge
	 */
	public List<UpsertOutcome> upsertAll(List<OrganizerRow> rows, int schemaVersion) {
		Objects.requireNonNull(rows, "rows");
		OperatorContext.required();
		for (OrganizerRow row : rows) {
			if (row.customFields().schemaVersion() != schemaVersion) {
				throw new IllegalArgumentException("Custom field schema version must match the batch schema version");
			}
		}
		requireDistinctRefs(rows);
		if (rows.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				INSERT INTO organizer AS o (operator_id, ref, name, custom_fields, custom_fields_schema_version)
				SELECT current_operator_id(), b.ref, b.name, CAST(b.custom_fields AS jsonb), :schemaVersion
				  FROM jsonb_to_recordset(CAST(:batch AS jsonb)) AS b(
				           ref           text,
				           name          text,
				           custom_fields text)
				 ORDER BY b.ref
				ON CONFLICT (operator_id, ref) DO UPDATE
				   SET name                         = EXCLUDED.name,
				       custom_fields                = EXCLUDED.custom_fields,
				       custom_fields_schema_version = EXCLUDED.custom_fields_schema_version,
				       version                      = o.version + 1,
				       updated_at                   = now()
				 WHERE (o.name, o.custom_fields)
				       IS DISTINCT FROM
				       (EXCLUDED.name, EXCLUDED.custom_fields)
				RETURNING new.id,
				          new.ref,
				          (old.id IS NULL)                                            AS created,
				          CASE WHEN old.id IS NULL THEN NULL ELSE to_jsonb(old.*) END AS before_state,
				          to_jsonb(new.*)                                             AS after_state
				""")
				.param("batch", batchAsJson(rows))
				.param("schemaVersion", schemaVersion)
				.query((rs, rowNumber) -> new UpsertOutcome(rs.getObject("id", UUID.class), rs.getString("ref"),
						rs.getBoolean("created"), rs.getString("before_state"), rs.getString("after_state")))
				.list();
	}

	/**
	 * Vores uuid for hver af de opgivne refs.
	 *
	 * <p>Læsningen findes, fordi {@code RETURNING} kun giver de ÆNDREDE rækker:
	 * efter et no-op-push står der ingen uuid'er tilbage til børneniveauet eller
	 * til oversættelserne. Uden denne ville et gentaget push tabe hele sit
	 * event-niveau.
	 *
	 * <p>En ref, der ikke findes, står bare ikke i svaret. Kalderen afgør, om
	 * det er en fejl — for et event med en ukendt {@code organizerRef} er det
	 * en 422, der navngiver ref'en, og aldrig et JOIN, der taber rækken i
	 * stilhed.
	 */
	public Map<OrganizerRef, UUID> idsByRef(Collection<OrganizerRef> refs) {
		Objects.requireNonNull(refs, "refs");
		OperatorContext.required();
		if (refs.isEmpty()) {
			return Map.of();
		}
		List<Map.Entry<OrganizerRef, UUID>> found = jdbc
				.sql("SELECT id, ref FROM organizer WHERE ref = ANY(CAST(:refs AS text[]))")
				.param("refs", textArrayLiteral(refs.stream().map(OrganizerRef::value).toList()))
				.query((rs, rowNumber) -> Map.entry(new OrganizerRef(rs.getString("ref")),
						rs.getObject("id", UUID.class)))
				.list();
		Map<OrganizerRef, UUID> byRef = new LinkedHashMap<>();
		for (Map.Entry<OrganizerRef, UUID> row : found) {
			byRef.put(row.getKey(), row.getValue());
		}
		return Map.copyOf(byRef);
	}

	/**
	 * Opretter den syntetiske default-arrangør, hvis den ikke allerede findes.
	 * Kaldes udelukkende af lazy enrichment — et katalog-push når aldrig hertil.
	 *
	 * <p>{@code ON CONFLICT DO NOTHING} og ikke en upsert: rækken er VORES, og
	 * en anden skrivning må ikke kunne omdøbe den. Andet kald svarer derfor
	 * {@code false}, og dét er den normale tilstand — ikke en fejl.
	 *
	 * <p>Den boolske form er bevaret til de kaldere, der kun skal vide OM rækken
	 * blev til; {@link #createDefault} er den samme skrivning med hele rækken
	 * retur, til audit-sporet.
	 */
	public boolean insertDefault(String name) {
		return createDefault(name).isPresent();
	}

	/**
	 * Samme skrivning som {@link #insertDefault}, men den nyoprettede række
	 * kommer tilbage som {@link UpsertOutcome} — {@code created}, ingen
	 * før-tilstand, hele rækken som efter-tilstand — så lazy enrichment kan
	 * auditere arrangøren på lige fod med det event og den billettype, den
	 * opretter i samme kald. Tom betyder "fandtes allerede" og er SUCCES.
	 */
	public Optional<UpsertOutcome> createDefault(String name) {
		Objects.requireNonNull(name, "name");
		OperatorContext.required();
		return jdbc.sql("""
				INSERT INTO organizer (operator_id, ref, name, is_default)
				VALUES (current_operator_id(), :ref, :name, true)
				ON CONFLICT (operator_id, ref) DO NOTHING
				RETURNING id, ref, to_jsonb(new.*) AS after_state
				""")
				.param("ref", DEFAULT_REF)
				.param("name", name)
				.query((rs, row) -> new UpsertOutcome(rs.getObject("id", UUID.class), rs.getString("ref"), true, null,
						rs.getString("after_state")))
				.optional();
	}

	/**
	 * Skriver ét helt aggregat med hver kolonne sat eksplicit — inklusive
	 * {@code id}, {@code version} og de to tidsstempler, som upserten overlader
	 * til databasen.
	 *
	 * <p>Det er den skrivning, round-trip-testen bruger: en glemt kolonne her
	 * eller en glemt linje i {@link #findByRef} bliver til en ulighed, en
	 * skema-test aldrig ville have set.
	 *
	 * @throws IllegalArgumentException hvis feltdokumentet hører til en anden
	 *                                  entitet — to id'er, der ikke er enige, er
	 *                                  felter skrevet på den forkerte række
	 */
	public void insert(Organizer organizer, CustomFieldColumns fields) {
		Objects.requireNonNull(organizer, "organizer");
		Objects.requireNonNull(fields, "fields");
		requireSameEntity(organizer.id(), fields);
		OperatorContext.required();
		jdbc.sql("""
				INSERT INTO organizer (id, operator_id, ref, name, is_default,
				                       custom_fields, custom_fields_schema_version,
				                       version, created_at, updated_at)
				VALUES (:id, current_operator_id(), :ref, :name, :isDefault,
				        CAST(:customFields AS jsonb), :customFieldsSchemaVersion,
				        :version, :createdAt, :updatedAt)
				""")
				.param("id", organizer.id())
				.param("ref", organizer.ref().value())
				.param("name", organizer.name())
				.param("isDefault", organizer.isDefault())
				.param("customFields", fields.customFields())
				.param("customFieldsSchemaVersion", fields.customFieldsSchemaVersion())
				.param("version", organizer.version())
				.param("createdAt", utc(organizer.createdAt()))
				.param("updatedAt", utc(organizer.updatedAt()))
				.update();
	}

	/**
	 * Operatørens default-arrangør til nye skelet-events. RLS afgrænser opslaget
	 * til den bundne operatør, og det partielle unikke indeks sikrer højst én.
	 */
	public Optional<UUID> findDefaultId() {
		OperatorContext.required();
		return jdbc.sql("SELECT id FROM organizer WHERE is_default")
				.query(UUID.class).optional();
	}

	/** Arrangøren bag kundens egen reference, som RLS ser den — tom for en anden kundes ref. */
	public Optional<Organizer> findByRef(OrganizerRef ref) {
		Objects.requireNonNull(ref, "ref");
		OperatorContext.required();
		return jdbc.sql("""
				SELECT id, ref, name, is_default, version, created_at, updated_at
				  FROM organizer WHERE ref = :ref
				""")
				.param("ref", ref.value())
				.query((rs, rowNumber) -> new Organizer(
						rs.getObject("id", UUID.class),
						new OrganizerRef(rs.getString("ref")),
						rs.getString("name"),
						rs.getBoolean("is_default"),
						rs.getInt("version"),
						instant(rs, "created_at"),
						instant(rs, "updated_at")))
				.optional();
	}

	/**
	 * Feltværdierne på én arrangør. Egen metode og ikke en komponent på
	 * {@link Organizer}: aggregatet er offentligt, og feltværdier må aldrig
	 * kunne læses af et modul, der kun har fået {@code catalog}.
	 */
	public Optional<CustomFieldColumns> findCustomFields(UUID id) {
		Objects.requireNonNull(id, "id");
		OperatorContext.required();
		return jdbc.sql("""
				SELECT id, custom_fields, custom_fields_schema_version
				  FROM organizer WHERE id = :id
				""")
				.param("id", id)
				.query((rs, rowNumber) -> new CustomFieldColumns(rs.getObject("id", UUID.class),
						rs.getString("custom_fields"), rs.getInt("custom_fields_schema_version")))
				.optional();
	}

	// --- Batchens wire-form -------------------------------------------------

	/**
	 * Batchen som ét jsonb-dokument. Nøglerne er snake_case og matcher
	 * kolonnelisten i {@code AS b(ref text, …)} ordret:
	 * {@code jsonb_to_recordset} slår op på feltnavn og er
	 * <strong>case-sensitiv</strong>, så en camelCase-nøgle ville binde NULL i
	 * stilhed i stedet for at fejle.
	 *
	 * <p>Skriveren er håndholdt frem for Jacksons — samme valg som
	 * {@code Translations}, og af samme grund: modulet har ingen JSON-flade, og
	 * en tree-model hører ikke hjemme i en skrivesti. Til gengæld SKAL
	 * escapingen være rigtig, for navnet er kundens tekst.
	 *
	 * <p>{@code custom_fields} transporteres som en escaped JSON-streng og
	 * læses som {@code text} i {@code AS b(...)}. Først derefter parser
	 * {@code CAST(b.custom_fields AS jsonb)} dokumentet for den enkelte række.
	 * Dermed kan et fejlformet dokument aldrig lukke rækken og tilføje en
	 * forfalsket søsterrække til batchen. Ugyldig JSON afviser hele sætningen.
	 */
	private static String batchAsJson(List<OrganizerRow> rows) {
		StringBuilder json = new StringBuilder(rows.size() * 96).append('[');
		for (int index = 0; index < rows.size(); index++) {
			OrganizerRow row = rows.get(index);
			if (index > 0) {
				json.append(',');
			}
			json.append("{\"ref\":");
			appendJsonString(json, row.ref().value());
			json.append(",\"name\":");
			appendJsonString(json, row.name());
			json.append(",\"custom_fields\":");
			appendJsonString(json, row.customFields().json());
			json.append('}');
		}
		return json.append(']').toString();
	}

	/** Én JSON-streng, escaped efter RFC 8259 §7. Identisk med {@code Translations}'. */
	private static void appendJsonString(StringBuilder json, String text) {
		json.append('"');
		for (int index = 0; index < text.length(); index++) {
			char character = text.charAt(index);
			switch (character) {
				case '"' -> json.append("\\\"");
				case '\\' -> json.append("\\\\");
				case '\b' -> json.append("\\b");
				case '\f' -> json.append("\\f");
				case '\n' -> json.append("\\n");
				case '\r' -> json.append("\\r");
				case '\t' -> json.append("\\t");
				default -> {
					// Non-ASCII sendes raat: forbindelsen er UTF-8, og jsonb tager
					// imod det. Styretegn under U+0020 er derimod ulovlige raa i JSON.
					if (character < ' ') {
						json.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
					}
					else {
						json.append(character);
					}
				}
			}
		}
		json.append('"');
	}

	/**
	 * Postgres' tekstform for et {@code text[]}: <code>{"a","b"}</code>. Hvert
	 * element citeres — et komma eller en tuborgklamme i en værdi ville ellers
	 * dele elementet i to uden at fejle.
	 */
	private static String textArrayLiteral(Collection<String> values) {
		StringJoiner literal = new StringJoiner(",", "{", "}");
		for (String value : values) {
			literal.add('"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"');
		}
		return literal.toString();
	}

	/**
	 * Et {@link Instant} som driveren kan udlede en SQL-type for. En rå
	 * {@code Instant} kan pgjdbc ikke binde, og en {@code LocalDateTime} ville
	 * forskyde værdien med maskinens egen offset uden at sige det.
	 */
	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private static void requireSameEntity(UUID id, CustomFieldColumns fields) {
		if (!id.equals(fields.id())) {
			throw new IllegalArgumentException("The custom field document belongs to entity " + fields.id()
					+ ", not to organizer " + id);
		}
	}

	/**
	 * To rækker med samme ref i én batch ville få Postgres til at svare
	 * {@code ON CONFLICT DO UPDATE command cannot affect row a second time} — en
	 * besked, der ikke peger på den ref, kunden sendte to gange. Parseren
	 * afviser det som {@code DUPLICATE_REF} med en JSON-Pointer; dette er nettet
	 * under den.
	 */
	private static void requireDistinctRefs(List<OrganizerRow> rows) {
		Set<String> seen = new HashSet<>();
		for (OrganizerRow row : rows) {
			if (!seen.add(row.ref().value())) {
				throw new IllegalArgumentException(
						"The batch carries the organizer ref [" + row.ref().value() + "] more than once");
			}
		}
	}

	// --- Wire-formerne ------------------------------------------------------

	/**
	 * Én arrangør i den normaliserede projektion, upserten skriver.
	 *
	 * <p>{@code is_default} står med vilje <strong>ikke</strong> på listen: det
	 * flag er vores og ikke kundens, og et katalog-push må aldrig kunne sætte
	 * eller rydde det.
	 */
	public record OrganizerRow(OrganizerRef ref, String name, CustomFieldValues customFields) {

		private static final char NUL = '\0';

		public OrganizerRow {
			Objects.requireNonNull(ref, "ref");
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(customFields, "customFields");
			if (name.indexOf(NUL) >= 0) {
				throw new IllegalArgumentException("The name of organizer [" + ref.value()
						+ "] contains a NUL character (U+0000), which PostgreSQL text cannot store");
			}
			// Et dokument valideret mod EVENT-felterne maa ikke kunne skrives paa
			// en arrangoer. Kontrollen ligger her, fordi det er den ene doer.
			if (customFields.scope() != CustomFieldScope.ORGANIZER) {
				throw new IllegalArgumentException("The custom fields of organizer [" + ref.value()
						+ "] were validated for scope " + customFields.scope() + ", not ORGANIZER");
			}
		}
	}

	/**
	 * Hvad upserten faktisk gjorde ved én række.
	 *
	 * <p>{@code created} kommer fra kolonne-formen {@code (old.id IS NULL)} og
	 * ikke fra {@code to_jsonb(old.*) IS NULL}. At en hel-række-reference til en
	 * ikke-eksisterende OLD-række giver SQL NULL frem for et objekt med lutter
	 * null-felter er <em>ikke</em> efterprøvet — og gætter man forkert, bliver
	 * hver eneste nyoprettelse til en {@code organizer.updated} i audit-loggen
	 * fra dag ét.
	 *
	 * <p>{@code beforeState} er {@code null} for en nyoprettelse. Begge
	 * tilstande er jsonb-tekst, ikke et træ: de går ubearbejdet videre til
	 * {@code audit_log}.
	 */
	public record UpsertOutcome(UUID id, String ref, boolean created, String beforeState, String afterState) {
	}

}

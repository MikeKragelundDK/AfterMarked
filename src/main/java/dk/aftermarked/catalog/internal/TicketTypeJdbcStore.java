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

import dk.aftermarked.catalog.TicketType;
import dk.aftermarked.catalog.TicketTypeRef;
import dk.aftermarked.catalog.fields.CustomFieldScope;
import dk.aftermarked.catalog.fields.CustomFieldValues;
import dk.aftermarked.money.Money;
import dk.aftermarked.money.MoneyColumns;
import dk.aftermarked.tenancy.OperatorContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Component;

/**
 * Al SQL mod {@code ticket_type}. Ingen anden klasse skriver eller læser den
 * tabel — samlingen er ikke pænhed, men dét, der gør en revision af
 * tenant-adfærden og af pengekolonnerne til at læse ét sted.
 *
 * <p><strong>Beløbet fylder to kolonner, og valutaen er RÆKKENS.</strong>
 * {@code face_value_minor} er heltal i mindste enhed, og {@code currency} står
 * ved siden af (CLAUDE.md). Kolonnen er ikke beløbets egen: eventets valuta er
 * bundet ind i den sammensatte fremmednøgle
 * {@code (operator_id, event_id, currency)}, så en billettype i EUR på et
 * DKK-event ikke kan gemmes, og eventets valuta ikke kan ændres, mens der
 * hænger billettyper på det. <strong>Alle tre kolonner skal forblive NOT
 * NULL</strong> — {@code MATCH SIMPLE} er defaulten, og den springer HELE
 * tjekket over, hvis bare én FK-kolonne er NULL.
 *
 * <p><strong>{@code operator_id} bindes aldrig.</strong> Hver eneste
 * indsættelse skriver {@code current_operator_id()} ordret i SQL'en, og intet
 * record i {@code catalog} har et {@code operatorId}-felt (beslutning §8). Der
 * findes dermed ingen kodesti, der KAN bære et forkert tenant-id ind i en
 * skrivning. En invariant-test læser denne fils kilde og fejler, hvis nogen
 * alligevel binder en parameter ved det navn.
 *
 * <p><strong>Kalderen ejer transaktionen.</strong> Ingen metode her er
 * {@code @Transactional}: skrivestien tager {@code pg_advisory_xact_lock} som
 * sin første sætning, og en lås bundet til transaktionen holder kun så længe
 * kalderens transaktion gør.
 *
 * @see EventJdbcStore
 */
@Component
public class TicketTypeJdbcStore {

	/**
	 * Entitetsnavnet i {@code translation} og i {@code audit_log}. Hentet fra
	 * feltskemaets eget scope-enum: config skriver camelCase
	 * ({@code ticketType}), oversættelseslaget snake_case, og V3's CHECK
	 * {@code translation_entity_type_format} afviser camelCase. Lå mappingen hos
	 * kalderne, ville importen fejle på constrainten hos den første, der glemte
	 * den.
	 */
	public static final String ENTITY_TYPE = CustomFieldScope.TICKET_TYPE.entityType();

	/**
	 * De felter, et katalog-push EJER på en billettype — argumentet til
	 * {@code Translations.replaceAll}.
	 *
	 * <p>Prisen står med vilje ikke her: den er et beløb i to kolonner, ikke en
	 * tekst, og et beløb oversættes ikke. Det formateres af
	 * {@code MoneyFormatter} for brugerens sprog.
	 */
	public static final Set<String> TRANSLATED_FIELDS = Set.of("name");

	private final JdbcClient jdbc;

	TicketTypeJdbcStore(JdbcClient jdbc) {
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
	 * <p>Seks ting i sætningen er mekanik og ikke stil — hver af dem en stille
	 * fejlkilde:
	 * <ol>
	 *   <li>{@code version} og {@code updated_at} står i {@code SET} og
	 *       <strong>aldrig</strong> i {@code IS DISTINCT FROM}-tuplen.
	 *       {@code EXCLUDED.version} er kolonnens default (1) og
	 *       {@code EXCLUDED.updated_at} er {@code now()}; enten af dem i tuplen
	 *       gør hver eneste række forskellig og dræber no-op'en helt.</li>
	 *   <li>{@code custom_fields_schema_version} står samme sted og af en anden
	 *       grund: stemplet beskriver VÆRDIERNE, så en ren konfigurations-bump
	 *       må ikke omskrive en uændret række.</li>
	 *   <li>{@code IS DISTINCT FROM} og aldrig {@code <>}. Ingen af de fire
	 *       sammenlignede kolonner er nullable i dag, men formen er den samme i
	 *       alle tre stores med vilje: den dag én af dem bliver nullable, må
	 *       reglen ikke skulle huskes igen.</li>
	 *   <li>Målet er alias-kvalificeret i {@code WHERE} ({@code t.name}) og
	 *       aldrig i {@code SET}. Omvendt giver
	 *       {@code [42702] column reference is ambiguous}.</li>
	 *   <li>{@code RETURNING} kan ikke referere {@code excluded}. Den bruger
	 *       PG18's {@code new.}/{@code old.} og KOLONNE-formen
	 *       {@code (old.id IS NULL)}.</li>
	 *   <li>Konfliktmålet indeholder {@code operator_id}. Uden det ville en
	 *       krydstenant-kollision enten fejle på en måde, der afslører en anden
	 *       kunde, eller rapportere succes uden at skrive noget.</li>
	 * </ol>
	 *
	 * <p>Konfliktnøglen er {@code (operator_id, event_id, ref)}: kundens
	 * billettype-ref er kun unik INDEN I sit event, så to events hos samme kunde
	 * må begge have en {@code standard}. {@code event_id} står derfor
	 * <strong>ikke</strong> i {@code SET} — en billettype kan ikke flytte event,
	 * den kan kun oprettes på et andet.
	 *
	 * @param rows          den normaliserede projektion, ikke kundens payload:
	 *                      grundværdier, oversættelser allerede pillet ud,
	 *                      {@code eventRef} allerede opløst til en UUID i Java
	 * @param schemaVersion den feltskema-version, hele batchen blev valideret
	 *                      under
	 * @return kun de rækker, der blev oprettet eller ændret — i uspecificeret
	 *         rækkefølge
	 */
	public List<UpsertOutcome> upsertAll(List<TicketTypeRow> rows, int schemaVersion) {
		Objects.requireNonNull(rows, "rows");
		OperatorContext.required();
		for (TicketTypeRow row : rows) {
			if (row.customFields().schemaVersion() != schemaVersion) {
				throw new IllegalArgumentException("Custom field schema version must match the batch schema version");
			}
		}
		requireDistinctRefs(rows);
		if (rows.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				INSERT INTO ticket_type AS t (operator_id, event_id, ref, name, face_value_minor, currency,
				                              custom_fields, custom_fields_schema_version)
				SELECT current_operator_id(), b.event_id, b.ref, b.name, b.face_value_minor, b.currency,
				       CAST(b.custom_fields AS jsonb), :schemaVersion
				  FROM jsonb_to_recordset(CAST(:batch AS jsonb)) AS b(
				           event_id         uuid,
				           ref              text,
				           name             text,
				           face_value_minor bigint,
				           currency         text,
				           custom_fields    text)
				 ORDER BY b.event_id, b.ref
				ON CONFLICT (operator_id, event_id, ref) DO UPDATE
				   SET name                         = EXCLUDED.name,
				       face_value_minor             = EXCLUDED.face_value_minor,
				       currency                     = EXCLUDED.currency,
				       custom_fields                = EXCLUDED.custom_fields,
				       custom_fields_schema_version = EXCLUDED.custom_fields_schema_version,
				       version                      = t.version + 1,
				       updated_at                   = now()
				 WHERE (t.name, t.face_value_minor, t.currency, t.custom_fields)
				       IS DISTINCT FROM
				       (EXCLUDED.name, EXCLUDED.face_value_minor, EXCLUDED.currency, EXCLUDED.custom_fields)
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
	 * Vores uuid for hver billettype under de opgivne events.
	 *
	 * <p>Læsningen findes, fordi {@code RETURNING} kun giver de ÆNDREDE rækker:
	 * efter et no-op-push står der ingen uuid'er tilbage til oversættelserne.
	 *
	 * <p><strong>Opslaget er scopet til eventet, ikke til ref'en alene.</strong>
	 * En billettype-ref er kun unik INDEN I sit event, så et
	 * {@code WHERE ref = ANY(...)} ville hente {@code standard} fra HVERT event
	 * hos operatøren og lade den sidste vinde i kortet — oversættelser skrevet på
	 * den forkerte billettype, uden en eneste fejl undervejs. Derfor spørges der
	 * på {@code event_id}, og nøglen er parret.
	 *
	 * <p>Svaret rummer ALLE billettyper på de opgivne events, også dem et delta
	 * ikke bar. Det er med vilje — kalderen slår op på de refs, den faktisk
	 * pushede, og en billettype, der ikke var med, skal netop stå urørt tilbage.
	 * ⚠ Send derfor aldrig hele dette kort videre som {@code entityIds} til
	 * {@code Translations.replaceAll}: så ville de upushede billettypers
	 * oversættelser blive slettet.
	 */
	public Map<TicketTypeKey, UUID> idsByRef(Collection<UUID> eventIds) {
		Objects.requireNonNull(eventIds, "eventIds");
		OperatorContext.required();
		if (eventIds.isEmpty()) {
			return Map.of();
		}
		List<Map.Entry<TicketTypeKey, UUID>> found = jdbc.sql("""
				SELECT id, event_id, ref FROM ticket_type WHERE event_id = ANY(CAST(:eventIds AS uuid[]))
				""")
				.param("eventIds", uuidArrayLiteral(eventIds))
				.query((rs, rowNumber) -> Map.entry(
						new TicketTypeKey(rs.getObject("event_id", UUID.class),
								new TicketTypeRef(rs.getString("ref"))),
						rs.getObject("id", UUID.class)))
				.list();
		Map<TicketTypeKey, UUID> byKey = new LinkedHashMap<>();
		for (Map.Entry<TicketTypeKey, UUID> row : found) {
			byKey.put(row.getKey(), row.getValue());
		}
		return Map.copyOf(byKey);
	}

	/**
	 * Billettypen fra lazy enrichment. Kun {@code enrich} kalder den.
	 *
	 * <p>{@code ON CONFLICT DO NOTHING} og ikke en upsert: verify-svaret er
	 * gulvet, ikke kataloget, og det må aldrig overskrive kundens egne værdier.
	 * <strong>Tom returværdi betyder "fandtes allerede" og er SUCCES</strong> —
	 * {@code DO NOTHING} returnerer ingen rækker ved en konflikt, så kalderen
	 * skal slå id'et op med {@link #idsByRef} bagefter, hvis den skal bruge det.
	 *
	 * <p>Beløbets valuta SKAL være eventets. Er den ikke det, afvises rækken af
	 * den sammensatte fremmednøgle med en 23503 — det er dét, der gør "ét event =
	 * én valuta" til databasens ansvar frem for disciplin.
	 */
	public Optional<UUID> insertSkeleton(UUID eventId, TicketTypeRef ref, String name, Money faceValue) {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(ref, "ref");
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(faceValue, "faceValue");
		OperatorContext.required();
		StatementSpec statement = jdbc.sql("""
				INSERT INTO ticket_type (operator_id, event_id, ref, name, face_value_minor, currency)
				VALUES (current_operator_id(), :eventId, :ref, :name, :faceValueMinor, :currency)
				ON CONFLICT (operator_id, event_id, ref) DO NOTHING
				RETURNING id
				""")
				.param("eventId", eventId)
				.param("ref", ref.value())
				.param("name", name);
		return MoneyColumns.bind(statement, "faceValue", faceValue).query(UUID.class).optional();
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
	 * <p>Beløbet bindes gennem {@link MoneyColumns}, som er den eneste vej mellem
	 * et {@code Money} og de to kolonner. Der findes med vilje ingen overload,
	 * der tager et beløb uden sin valuta.
	 *
	 * @throws IllegalArgumentException hvis feltdokumentet hører til en anden
	 *                                  entitet — to id'er, der ikke er enige, er
	 *                                  felter skrevet på den forkerte række
	 */
	public void insert(TicketType ticketType, CustomFieldColumns fields) {
		Objects.requireNonNull(ticketType, "ticketType");
		Objects.requireNonNull(fields, "fields");
		requireSameEntity(ticketType.id(), fields);
		OperatorContext.required();
		StatementSpec statement = jdbc.sql("""
				INSERT INTO ticket_type (id, operator_id, event_id, ref, name, face_value_minor, currency,
				                         custom_fields, custom_fields_schema_version,
				                         version, created_at, updated_at)
				VALUES (:id, current_operator_id(), :eventId, :ref, :name, :faceValueMinor, :currency,
				        CAST(:customFields AS jsonb), :customFieldsSchemaVersion,
				        :version, :createdAt, :updatedAt)
				""")
				.param("id", ticketType.id())
				.param("eventId", ticketType.eventId())
				.param("ref", ticketType.ref().value())
				.param("name", ticketType.name())
				.param("customFields", fields.customFields())
				.param("customFieldsSchemaVersion", fields.customFieldsSchemaVersion())
				.param("version", ticketType.version())
				.param("createdAt", utc(ticketType.createdAt()))
				.param("updatedAt", utc(ticketType.updatedAt()));
		MoneyColumns.bind(statement, "faceValue", ticketType.faceValue()).update();
	}

	/**
	 * Billettypen bag kundens egen reference INDEN I ét event, som RLS ser den.
	 * Eventet er en del af opslaget, fordi ref'en kun er unik dér.
	 */
	public Optional<TicketType> findByRef(UUID eventId, TicketTypeRef ref) {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(ref, "ref");
		OperatorContext.required();
		return jdbc.sql("""
				SELECT id, event_id, ref, name, face_value_minor, currency, version, created_at, updated_at
				  FROM ticket_type WHERE event_id = :eventId AND ref = :ref
				""")
				.param("eventId", eventId)
				.param("ref", ref.value())
				.query((rs, rowNumber) -> new TicketType(
						rs.getObject("id", UUID.class),
						rs.getObject("event_id", UUID.class),
						new TicketTypeRef(rs.getString("ref")),
						rs.getString("name"),
						// Den ene doer den anden vej: begge kolonner laeses
						// sammen, og en NULL i én af dem fejler hoejlydt frem for
						// at blive til et nul-beloeb uden mening.
						MoneyColumns.read(rs, "face_value_minor", "currency"),
						rs.getInt("version"),
						instant(rs, "created_at"),
						instant(rs, "updated_at")))
				.optional();
	}

	/**
	 * Feltværdierne på én billettype. Egen metode og ikke en komponent på
	 * {@link TicketType}: aggregatet er offentligt, og feltværdier må aldrig
	 * kunne læses af et modul, der kun har fået {@code catalog}.
	 */
	public Optional<CustomFieldColumns> findCustomFields(UUID id) {
		Objects.requireNonNull(id, "id");
		OperatorContext.required();
		return jdbc.sql("""
				SELECT id, custom_fields, custom_fields_schema_version
				  FROM ticket_type WHERE id = :id
				""")
				.param("id", id)
				.query((rs, rowNumber) -> new CustomFieldColumns(rs.getObject("id", UUID.class),
						rs.getString("custom_fields"), rs.getInt("custom_fields_schema_version")))
				.optional();
	}

	// --- Batchens wire-form -------------------------------------------------

	/**
	 * Batchen som ét jsonb-dokument. Nøglerne er snake_case og matcher
	 * kolonnelisten i {@code AS b(event_id uuid, …)} ordret:
	 * {@code jsonb_to_recordset} slår op på feltnavn og er
	 * <strong>case-sensitiv</strong>, så en camelCase-nøgle ville binde NULL i
	 * stilhed i stedet for at fejle.
	 *
	 * <p>{@code custom_fields} transporteres som en escaped JSON-streng og
	 * læses som {@code text} i {@code AS b(...)}. Først derefter parser
	 * {@code CAST(b.custom_fields AS jsonb)} dokumentet for den enkelte række.
	 * Dermed kan et fejlformet dokument aldrig lukke rækken og tilføje en
	 * forfalsket søsterrække til batchen. Ugyldig JSON afviser hele sætningen.
	 *
	 * <p>⚠ <strong>Beløbet skrives med {@code minorUnits()}.</strong> Det er den
	 * ene form, en batch kan bære — {@code MoneyColumns.bind} binder
	 * <em>parametre</em> og kan ikke skrive et tal ind i et JSON-dokument, og
	 * {@code toString()} at parse igen ville være værre. Kolonnen ved siden af
	 * bærer valutaen i samme objekt, så et beløb uden sin valuta stadig ikke kan
	 * skrives ned. Skal scanningen af rå {@code minorUnits()}-brug
	 * ({@code CurrencyAggregationInvariantTest}) forblive stram, skal den enten
	 * navngive denne klasse ved siden af {@code money}, eller
	 * {@code MoneyColumns} skal have en batch-form.
	 */
	private static String batchAsJson(List<TicketTypeRow> rows) {
		StringBuilder json = new StringBuilder(rows.size() * 128).append('[');
		for (int index = 0; index < rows.size(); index++) {
			TicketTypeRow row = rows.get(index);
			if (index > 0) {
				json.append(',');
			}
			json.append("{\"event_id\":");
			appendJsonString(json, row.eventId().toString());
			json.append(",\"ref\":");
			appendJsonString(json, row.ref().value());
			json.append(",\"name\":");
			appendJsonString(json, row.name());
			// Et heltal, ikke en streng: kolonnen i AS b(...) er bigint, og et
			// citeret tal ville blive castet af Postgres i stedet for at fejle.
			json.append(",\"face_value_minor\":").append(row.faceValue().minorUnits());
			json.append(",\"currency\":");
			appendJsonString(json, row.faceValue().currencyCode());
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
	 * Postgres' tekstform for et {@code uuid[]}: <code>{a,b,c}</code>. En uuid
	 * har hverken komma, klamme eller mellemrum i sin tekstform, så der er intet
	 * at citere — men {@code CAST(:x AS uuid[])} kræver stadig, at hele literalen
	 * kommer som én almindelig streng.
	 */
	private static String uuidArrayLiteral(Collection<UUID> ids) {
		StringJoiner literal = new StringJoiner(",", "{", "}");
		for (UUID id : ids) {
			literal.add(id.toString());
		}
		return literal.toString();
	}

	/**
	 * Et {@link Instant} som driveren kan udlede en SQL-type for. En rå
	 * {@code Instant} kan pgjdbc ikke binde, og en {@code LocalDateTime} ville
	 * forskyde værdien med maskinens egen offset uden at sige det.
	 */
	private static OffsetDateTime utc(Instant instant) {
		return (instant == null) ? null : instant.atOffset(ZoneOffset.UTC);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return (value == null) ? null : value.toInstant();
	}

	private static void requireSameEntity(UUID id, CustomFieldColumns fields) {
		if (!id.equals(fields.id())) {
			throw new IllegalArgumentException(
					"The custom field document belongs to entity " + fields.id() + ", not to ticket type " + id);
		}
	}

	/**
	 * To rækker med samme {@code (event_id, ref)} i én batch ville få Postgres
	 * til at svare {@code ON CONFLICT DO UPDATE command cannot affect row a
	 * second time} — en besked, der ikke peger på den ref, kunden sendte to
	 * gange. Parseren afviser det som {@code DUPLICATE_REF} med en
	 * JSON-Pointer; dette er nettet under den.
	 */
	private static void requireDistinctRefs(List<TicketTypeRow> rows) {
		Set<TicketTypeKey> seen = new HashSet<>();
		for (TicketTypeRow row : rows) {
			if (!seen.add(new TicketTypeKey(row.eventId(), row.ref()))) {
				throw new IllegalArgumentException("The batch carries the ticket type ref [" + row.ref().value()
						+ "] more than once on event " + row.eventId());
			}
		}
	}

	// --- Wire-formerne ------------------------------------------------------

	/**
	 * Én billettype i den normaliserede projektion, upserten skriver.
	 *
	 * <p>{@code eventId} er allerede opløst: refs oversættes til uuid'er i Java
	 * og aldrig i et JOIN, som ville tabe en billettype med en ukendt event-ref i
	 * stilhed.
	 */
	public record TicketTypeRow(UUID eventId, TicketTypeRef ref, String name, Money faceValue,
			CustomFieldValues customFields) {

		private static final char NUL = '\0';

		public TicketTypeRow {
			Objects.requireNonNull(eventId, "eventId");
			Objects.requireNonNull(ref, "ref");
			Objects.requireNonNull(name, "name");
			// Et beloeb, der er vaek, maa ikke blive til nul: face_value_minor er
			// NOT NULL, og en manglende pris er en fejl i payloaden, ikke en
			// gratis billet.
			Objects.requireNonNull(faceValue, "faceValue");
			Objects.requireNonNull(customFields, "customFields");
			if (name.indexOf(NUL) >= 0) {
				throw new IllegalArgumentException("The name of ticket type [" + ref.value()
						+ "] contains a NUL character (U+0000), which PostgreSQL text cannot store");
			}
			// Et dokument valideret mod EVENT-felterne maa ikke kunne skrives paa
			// en billettype. Kontrollen ligger her, fordi det er den ene doer.
			if (customFields.scope() != CustomFieldScope.TICKET_TYPE) {
				throw new IllegalArgumentException("The custom fields of ticket type [" + ref.value()
						+ "] were validated for scope " + customFields.scope() + ", not TICKET_TYPE");
			}
		}
	}

	/**
	 * Nøglen på en billettype, som kunden kender den: ref'en <em>og</em> det
	 * event, den hører til.
	 *
	 * <p>Egen type, fordi ref'en alene ikke identificerer en række.
	 * {@code UNIQUE (operator_id, event_id, ref)} betyder, at to events hos samme
	 * kunde begge må have en {@code standard} — et kort keyet på ref'en alene
	 * ville lade den ene overskrive den anden, og oversættelserne ville lande på
	 * den forkerte billettype uden en eneste fejl undervejs.
	 */
	public record TicketTypeKey(UUID eventId, TicketTypeRef ref) {

		public TicketTypeKey {
			Objects.requireNonNull(eventId, "eventId");
			Objects.requireNonNull(ref, "ref");
		}
	}

	/**
	 * Hvad upserten faktisk gjorde ved én række.
	 *
	 * <p>{@code created} kommer fra kolonne-formen {@code (old.id IS NULL)} og
	 * ikke fra {@code to_jsonb(old.*) IS NULL}. At en hel-række-reference til en
	 * ikke-eksisterende OLD-række giver SQL NULL frem for et objekt med lutter
	 * null-felter er <em>ikke</em> efterprøvet — og gætter man forkert, bliver
	 * hver eneste nyoprettelse til en {@code ticket_type.updated} i audit-loggen
	 * fra dag ét.
	 *
	 * <p>{@code ref} alene identificerer ikke rækken; {@code id} gør.
	 * {@code afterState} bærer {@code event_id}, hvis en læser skal se, hvor den
	 * hører til.
	 *
	 * <p>Recorden er med vilje gentaget i hver af de tre stores frem for at være
	 * én delt type. De tre niveauer forbruges hver for sig — hvert med sit eget
	 * {@code entity_type} og sine egne handlingsnavne.
	 */
	public record UpsertOutcome(UUID id, String ref, boolean created, String beforeState, String afterState) {
	}

}

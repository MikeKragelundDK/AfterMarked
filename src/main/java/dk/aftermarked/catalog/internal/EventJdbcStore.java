package dk.aftermarked.catalog.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Currency;
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

import dk.aftermarked.catalog.Event;
import dk.aftermarked.catalog.EventRef;
import dk.aftermarked.catalog.SalesStatus;
import dk.aftermarked.catalog.fields.CustomFieldScope;
import dk.aftermarked.catalog.fields.CustomFieldValues;
import dk.aftermarked.money.Money;
import dk.aftermarked.tenancy.OperatorContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Al SQL mod {@code event} — <strong>og det er meningen, at det er ALT</strong>.
 * Denne klasse er det ENESTE sted i produktionskoden, der skriver den tabel, og
 * en invariant-test scanner kilderne og gør bygget rødt, hvis en anden klasse
 * gør det.
 *
 * <p>Grunden er ikke pænhed. Eventet er dét, ventelisten, prisloftet og
 * aftermarkedet hænger på: valutaen ligger her, {@code sales_status} pauser og
 * genoptager ventelisten, og {@code primary_purchase_url} står i "godt
 * nyt"-mailen. En anden skrivesti til den tabel er en anden mening om de
 * regler — og den ville ikke kunne ses i et review, fordi den ville ligne en
 * uskyldig {@code UPDATE}.
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
 * bundet til transaktionen, holder kun så længe kalderens transaktion gør.
 * Åbnede hver metode sin egen, ville låsen være væk, inden batchen var skrevet.
 *
 * @see OrganizerJdbcStore
 * @see TicketTypeJdbcStore
 */
@Component
public class EventJdbcStore {

	/**
	 * Entitetsnavnet i {@code translation} og i {@code audit_log}. Hentet fra
	 * feltskemaets eget scope-enum, så de to stavemåder ikke kan glide fra
	 * hinanden: config skriver camelCase, oversættelseslaget snake_case, og V3's
	 * CHECK afviser camelCase.
	 */
	public static final String ENTITY_TYPE = CustomFieldScope.EVENT.entityType();

	/**
	 * De felter, et katalog-push EJER på et event — argumentet til
	 * {@code Translations.replaceAll}.
	 *
	 * <p>Mængden er fast og lille med vilje. Var den hele {@code entity_type},
	 * ville det natlige push slette operatørens egne admin-oversættelser af
	 * andre felter kl. 03 uden en linje i audit-loggen.
	 */
	public static final Set<String> TRANSLATED_FIELDS = Set.of("title", "venue");

	private final JdbcClient jdbc;

	EventJdbcStore(JdbcClient jdbc) {
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
	 *       grund: stemplet beskriver VÆRDIERNE. Stod det i tuplen, ville en ren
	 *       konfigurations-bump omskrive hver eneste uændrede række, flytte
	 *       {@code updated_at} og udsende en audit-linje pr. række — målt til
	 *       5.000 falske {@code event.updated} for en bump uden dataændring.</li>
	 *   <li>{@code IS DISTINCT FROM} og aldrig {@code <>}: {@code venue},
	 *       {@code ends_at}, {@code sales_ends_at} og
	 *       {@code primary_purchase_url} er nullable, og {@code <>} propagerer
	 *       NULL — rækken ville blive sprunget over for evigt.</li>
	 *   <li>Målet er alias-kvalificeret i {@code WHERE} ({@code e.title}) og
	 *       aldrig i {@code SET}. Omvendt giver
	 *       {@code [42702] column reference is ambiguous}.</li>
	 *   <li>{@code RETURNING} kan ikke referere {@code excluded}. Den bruger
	 *       PG18's {@code new.}/{@code old.} — og den KOLONNE-niveau-form, der
	 *       står nedenfor ({@code old.id IS NULL}). At en hel-række-reference
	 *       til en ikke-eksisterende OLD-række giver SQL NULL frem for et objekt
	 *       med lutter null-felter er ikke efterprøvet, og gætter man forkert,
	 *       bliver hver eneste nyoprettelse til en {@code event.updated} i
	 *       audit-loggen fra dag ét.</li>
	 *   <li>Konfliktmålet indeholder {@code operator_id}. Uden det ville en
	 *       krydstenant-kollision enten fejle på en måde, der afslører en anden
	 *       kunde, eller — med {@code DO NOTHING} — rapportere succes uden at
	 *       skrive noget.</li>
	 * </ol>
	 *
	 * <p>Hver kolonne i {@code SET}-listen er KUNDEEJET. En kolonne, operatøren
	 * kan redigere fra admin, må ikke stå der: så ruller det natlige push
	 * admin-redigeringen tilbage kl. 03, og audit-loggen tilskriver ændringen
	 * {@code catalog_push}. Og der står med vilje <strong>ingen</strong>
	 * {@code AND e.version = :expected} i {@code WHERE}: kunden har intet
	 * forventet versionsnummer at sende, og en godartet samtidig admin-redigering
	 * ville gøre rækken permanent oversprunget.
	 *
	 * @param rows          den normaliserede projektion, ikke kundens payload:
	 *                      grundværdier, oversættelser allerede pillet ud,
	 *                      {@code organizerRef} allerede opløst til en UUID i
	 *                      Java (aldrig i et JOIN, som ville tabe et event med
	 *                      en ukendt ref i stilhed)
	 * @param schemaVersion den feltskema-version, hele batchen blev valideret
	 *                      under
	 * @return kun de rækker, der blev oprettet eller ændret — i uspecificeret
	 *         rækkefølge
	 */
	public List<UpsertOutcome> upsertAll(List<EventRow> rows, int schemaVersion) {
		Objects.requireNonNull(rows, "rows");
		OperatorContext.required();
		for (EventRow row : rows) {
			if (row.customFields().schemaVersion() != schemaVersion) {
				throw new IllegalArgumentException("Custom field schema version must match the batch schema version");
			}
		}
		requireDistinctRefs(rows);
		if (rows.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				INSERT INTO event AS e (operator_id, organizer_id, ref, title, venue, starts_at, ends_at,
				                        sales_status, sales_ends_at, primary_purchase_url, currency,
				                        custom_fields, custom_fields_schema_version)
				SELECT current_operator_id(), b.organizer_id, b.ref, b.title, b.venue, b.starts_at,
				       b.ends_at, b.sales_status, b.sales_ends_at, b.primary_purchase_url, b.currency,
				       CAST(b.custom_fields AS jsonb), :schemaVersion
				  FROM jsonb_to_recordset(CAST(:batch AS jsonb)) AS b(
				           organizer_id         uuid,
				           ref                  text,
				           title                text,
				           venue                text,
				           starts_at            timestamptz,
				           ends_at              timestamptz,
				           sales_status         text,
				           sales_ends_at        timestamptz,
				           primary_purchase_url text,
				           currency             text,
				           custom_fields        text)
				 ORDER BY b.ref
				ON CONFLICT (operator_id, ref) DO UPDATE
				   SET organizer_id                 = EXCLUDED.organizer_id,
				       title                        = EXCLUDED.title,
				       venue                        = EXCLUDED.venue,
				       starts_at                    = EXCLUDED.starts_at,
				       ends_at                      = EXCLUDED.ends_at,
				       sales_status                 = EXCLUDED.sales_status,
				       sales_ends_at                = EXCLUDED.sales_ends_at,
				       primary_purchase_url         = EXCLUDED.primary_purchase_url,
				       currency                     = EXCLUDED.currency,
				       custom_fields                = EXCLUDED.custom_fields,
				       custom_fields_schema_version = EXCLUDED.custom_fields_schema_version,
				       version                      = e.version + 1,
				       updated_at                   = now()
				 WHERE (e.organizer_id, e.title, e.venue, e.starts_at, e.ends_at, e.sales_status,
				        e.sales_ends_at, e.primary_purchase_url, e.currency, e.custom_fields)
				       IS DISTINCT FROM
				       (EXCLUDED.organizer_id, EXCLUDED.title, EXCLUDED.venue, EXCLUDED.starts_at,
				        EXCLUDED.ends_at, EXCLUDED.sales_status, EXCLUDED.sales_ends_at,
				        EXCLUDED.primary_purchase_url, EXCLUDED.currency, EXCLUDED.custom_fields)
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
	 * efter et no-op-push står der ingen uuid'er tilbage til billettyperne eller
	 * til oversættelserne. Uden denne ville et gentaget push tabe hele sit
	 * billettype-niveau.
	 *
	 * <p>En ref, der ikke findes, står bare ikke i svaret.
	 */
	public Map<EventRef, UUID> idsByRef(Collection<EventRef> refs) {
		Objects.requireNonNull(refs, "refs");
		OperatorContext.required();
		if (refs.isEmpty()) {
			return Map.of();
		}
		List<Map.Entry<EventRef, UUID>> found = jdbc
				.sql("SELECT id, ref FROM event WHERE ref = ANY(CAST(:refs AS text[]))")
				.param("refs", textArrayLiteral(refs.stream().map(EventRef::value).toList()))
				.query((rs, rowNumber) -> Map.entry(new EventRef(rs.getString("ref")), rs.getObject("id", UUID.class)))
				.list();
		Map<EventRef, UUID> byRef = new LinkedHashMap<>();
		for (Map.Entry<EventRef, UUID> row : found) {
			byRef.put(row.getKey(), row.getValue());
		}
		return Map.copyOf(byRef);
	}

	/**
	 * Skelet-eventet fra lazy enrichment (PLAN §2.2): titel = ref, status
	 * UNKNOWN, ingen salgs-URL. Kun {@code enrich} kalder den.
	 *
	 * <p>{@code ON CONFLICT DO NOTHING} og ikke en upsert — det er hele
	 * forskellen på gulvet og kataloget. Skelettet må <strong>aldrig</strong>
	 * overskrive katalogdata: findes rækken, er kundens egne værdier bedre end
	 * vores gæt. Den modsatte vej er derimod tilladt, for katalog-pushet er den
	 * fulde upsert ovenfor og overskriver godt nok et skelet — uden at ændre
	 * rækkens identitet.
	 *
	 * <p><strong>Tom returværdi betyder "fandtes allerede" og er SUCCES.</strong>
	 * {@code DO NOTHING} returnerer ingen rækker ved en konflikt, så kalderen
	 * skal slå id'et op med {@link #idsByRef} eller {@link #findByRef} bagefter —
	 * enrich skal bruge det til billettypen uanset hvilken af de to veje rækken
	 * kom ad.
	 *
	 * @param organizerId den arrangør, eventet hænger på — for et skelet er det
	 *                    den syntetiske {@code _default}
	 * @param ref         kundens egen ref fra verify-svaret. Der findes ingen
	 *                    reserveret event-ref: netop derfor kan et senere
	 *                    katalog-push genkende og udfylde præcis denne række
	 * @param startsAt    starttidspunktet fra verify-svaret
	 * @param currency    eventets valuta, taget fra verify-svarets face value
	 * @return id'et på den nyoprettede række, eller tomt hvis eventet allerede
	 *         fandtes
	 */
	public Optional<UUID> insertSkeleton(UUID organizerId, EventRef ref, Instant startsAt, Currency currency) {
		Objects.requireNonNull(organizerId, "organizerId");
		Objects.requireNonNull(ref, "ref");
		Objects.requireNonNull(startsAt, "startsAt");
		Objects.requireNonNull(currency, "currency");
		if (!Money.hasMinorUnit(currency)) {
			throw new IllegalArgumentException("Event [" + ref.value() + "] is in currency "
					+ currency.getCurrencyCode() + ", which has no minor unit and can never carry a face value");
		}
		OperatorContext.required();
		return jdbc.sql("""
				INSERT INTO event (operator_id, organizer_id, ref, title, starts_at, sales_status, currency)
				VALUES (current_operator_id(), :organizerId, :ref, :title, :startsAt, :salesStatus, :currency)
				ON CONFLICT (operator_id, ref) DO NOTHING
				RETURNING id
				""")
				.param("organizerId", organizerId)
				.param("ref", ref.value())
				// Titlen er eventets EGEN ref. Kolonnen er NOT NULL, fordi
				// fallback-kaedens sidste trin er grundfeltet paa entiteten
				// (PLAN 2.9) -- et tomt grundfelt goer kaeden uskrivelig.
				// Foerste katalog-push retter titlen.
				.param("title", ref.value())
				.param("startsAt", utc(startsAt.truncatedTo(ChronoUnit.MICROS)))
				// UNKNOWN og ikke ON_SALE: et skelet-event er aldrig "til salg"
				// og genoptager aldrig ventelisten.
				.param("salesStatus", SalesStatus.UNKNOWN.name())
				.param("currency", currency.getCurrencyCode())
				.query(UUID.class)
				.optional();
	}

	/**
	 * Optimistisk låsning på den ene kolonne, operatør-admin ejer (S9's kalder
	 * findes ikke endnu; formen fastlægges her).
	 *
	 * <p>Låsningen er en {@code version}-kolonne og et
	 * {@code WHERE version = :expectedVersion} (PLAN §3) — ikke en
	 * {@code SELECT … FOR UPDATE}. Nul ændrede rækker betyder her, modsat
	 * upserten ovenfor, at nogen kom først: enten har en anden skrivning bumpet
	 * versionen, eller rækken er væk.
	 *
	 * <p>⚠ {@code sales_status} står OGSÅ i pushets {@code SET}-liste. Det er den
	 * ene kolonne, hvor kolonne-partitioneringen ikke holder: det natlige push
	 * ruller en admin-ændring tilbage kl. 03, og audit-loggen tilskriver
	 * ændringen {@code catalog_push}. Det er kundens egen salgsstatus, så
	 * kunden vinder med vilje — men det skal afklares, før admin-fladen bygges.
	 *
	 * @return {@code true} hvis rækken blev opdateret; {@code false} hvis en
	 *         anden skriver kom først, eller eventet ikke findes for denne
	 *         operatør
	 */
	public boolean updateSalesStatus(UUID id, SalesStatus salesStatus, int expectedVersion) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(salesStatus, "salesStatus");
		OperatorContext.required();
		return jdbc.sql("""
				UPDATE event
				   SET sales_status = :salesStatus, version = version + 1, updated_at = now()
				 WHERE id = :id AND version = :expectedVersion
				""")
				.param("salesStatus", salesStatus.name())
				.param("id", id)
				.param("expectedVersion", expectedVersion)
				.update() == 1;
	}

	/**
	 * Valutaerne i operatørens katalog, som RLS ser dem — grundlaget for
	 * enkelt-valuta-politikken ({@code operator.single_currency}, V4).
	 *
	 * <p>Sætningen står her og ikke i {@code CatalogWriteGuard}, fordi al SQL mod
	 * {@code event} hører i denne klasse. Guarden er til gengæld det eneste sted,
	 * den må kaldes fra, og den skal have taget {@code pg_advisory_xact_lock}
	 * først: uden låsen ser et natligt DKK-push og et samtidigt verify-drevet
	 * EUR-skelet hver et konsistent én-valuta-katalog og committer begge.
	 */
	public List<Currency> distinctCurrencies() {
		OperatorContext.required();
		return jdbc.sql("SELECT DISTINCT currency FROM event")
				.query((rs, rowNumber) -> Money.currencyOf(rs.getString("currency")))
				.list();
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
	public void insert(Event event, CustomFieldColumns fields) {
		Objects.requireNonNull(event, "event");
		Objects.requireNonNull(fields, "fields");
		requireSameEntity(event.id(), fields);
		OperatorContext.required();
		jdbc.sql("""
				INSERT INTO event (id, operator_id, organizer_id, ref, title, venue, starts_at, ends_at,
				                   sales_status, sales_ends_at, primary_purchase_url, currency,
				                   custom_fields, custom_fields_schema_version,
				                   version, created_at, updated_at)
				VALUES (:id, current_operator_id(), :organizerId, :ref, :title, :venue, :startsAt, :endsAt,
				        :salesStatus, :salesEndsAt, :primaryPurchaseUrl, :currency,
				        CAST(:customFields AS jsonb), :customFieldsSchemaVersion,
				        :version, :createdAt, :updatedAt)
				""")
				.param("id", event.id())
				.param("organizerId", event.organizerId())
				.param("ref", event.ref().value())
				.param("title", event.title())
				.param("venue", event.venue())
				.param("startsAt", utc(event.startsAt()))
				.param("endsAt", utc(event.endsAt()))
				.param("salesStatus", event.salesStatus().name())
				.param("salesEndsAt", utc(event.salesEndsAt()))
				.param("primaryPurchaseUrl", event.primaryPurchaseUrl())
				.param("currency", event.currency().getCurrencyCode())
				.param("customFields", fields.customFields())
				.param("customFieldsSchemaVersion", fields.customFieldsSchemaVersion())
				.param("version", event.version())
				.param("createdAt", utc(event.createdAt()))
				.param("updatedAt", utc(event.updatedAt()))
				.update();
	}

	/** Eventet bag kundens egen reference, som RLS ser det — tomt for en anden kundes ref. */
	public Optional<Event> findByRef(EventRef ref) {
		Objects.requireNonNull(ref, "ref");
		OperatorContext.required();
		return jdbc.sql("""
				SELECT id, organizer_id, ref, title, venue, starts_at, ends_at, sales_status, sales_ends_at,
				       primary_purchase_url, currency, version, created_at, updated_at
				  FROM event WHERE ref = :ref
				""")
				.param("ref", ref.value())
				.query((rs, rowNumber) -> new Event(
						rs.getObject("id", UUID.class),
						rs.getObject("organizer_id", UUID.class),
						new EventRef(rs.getString("ref")),
						rs.getString("title"),
						rs.getString("venue"),
						instant(rs, "starts_at"),
						instant(rs, "ends_at"),
						// fromWire og ALDRIG valueOf: en raekke skrevet af en
						// nyere replika kan baere en token, denne version ikke
						// kender, og en laesning maa ikke sprænge i den
						// forskudte rollout, skemaet er bygget til at overleve.
						SalesStatus.fromWire(rs.getString("sales_status")),
						instant(rs, "sales_ends_at"),
						rs.getString("primary_purchase_url"),
						// Money.currencyOf er den ene vej fra streng til valuta.
						Money.currencyOf(rs.getString("currency")),
						rs.getInt("version"),
						instant(rs, "created_at"),
						instant(rs, "updated_at")))
				.optional();
	}

	/**
	 * Feltværdierne på ét event. Egen metode og ikke en komponent på
	 * {@link Event}: aggregatet er offentligt, og feltværdier må aldrig kunne
	 * læses af et modul, der kun har fået {@code catalog}.
	 */
	public Optional<CustomFieldColumns> findCustomFields(UUID id) {
		Objects.requireNonNull(id, "id");
		OperatorContext.required();
		return jdbc.sql("""
				SELECT id, custom_fields, custom_fields_schema_version
				  FROM event WHERE id = :id
				""")
				.param("id", id)
				.query((rs, rowNumber) -> new CustomFieldColumns(rs.getObject("id", UUID.class),
						rs.getString("custom_fields"), rs.getInt("custom_fields_schema_version")))
				.optional();
	}

	// --- Batchens wire-form -------------------------------------------------

	/**
	 * Batchen som ét jsonb-dokument. Nøglerne er snake_case og matcher
	 * kolonnelisten i {@code AS b(organizer_id uuid, …)} ordret:
	 * {@code jsonb_to_recordset} slår op på feltnavn og er
	 * <strong>case-sensitiv</strong>, så en camelCase-nøgle ville binde NULL i
	 * stilhed i stedet for at fejle.
	 *
	 * <p>Skriveren er håndholdt frem for Jacksons — samme valg som
	 * {@code Translations}, og af samme grund: modulet har ingen JSON-flade, og
	 * en tree-model hører ikke hjemme i en skrivesti. Til gengæld SKAL
	 * escapingen være rigtig, for titlen og stedet er kundens tekst.
	 *
	 * <p>Tidsstemplerne skrives som {@link Instant#toString()}, altså ISO-8601
	 * med {@code Z}. Postgres' {@code timestamptz}-input læser den form
	 * utvetydigt; en lokal form uden zone ville blive tolket i serverens
	 * tidszone og flytte værdien uden at fejle.
	 *
	 * <p>{@code custom_fields} transporteres som en escaped JSON-streng og
	 * læses som {@code text} i {@code AS b(...)}. Først derefter parser
	 * {@code CAST(b.custom_fields AS jsonb)} dokumentet for den enkelte række.
	 * Dermed kan et fejlformet dokument aldrig lukke rækken og tilføje en
	 * forfalsket søsterrække til batchen. Ugyldig JSON afviser hele sætningen.
	 */
	private static String batchAsJson(List<EventRow> rows) {
		StringBuilder json = new StringBuilder(rows.size() * 192).append('[');
		for (int index = 0; index < rows.size(); index++) {
			EventRow row = rows.get(index);
			if (index > 0) {
				json.append(',');
			}
			json.append("{\"organizer_id\":");
			appendJsonString(json, row.organizerId().toString());
			json.append(",\"ref\":");
			appendJsonString(json, row.ref().value());
			json.append(",\"title\":");
			appendJsonString(json, row.title());
			json.append(",\"venue\":");
			appendNullableJsonString(json, row.venue());
			json.append(",\"starts_at\":");
			appendJsonString(json, row.startsAt().toString());
			json.append(",\"ends_at\":");
			appendNullableJsonString(json, (row.endsAt() == null) ? null : row.endsAt().toString());
			json.append(",\"sales_status\":");
			appendJsonString(json, row.salesStatus().name());
			json.append(",\"sales_ends_at\":");
			appendNullableJsonString(json, (row.salesEndsAt() == null) ? null : row.salesEndsAt().toString());
			json.append(",\"primary_purchase_url\":");
			appendNullableJsonString(json, row.primaryPurchaseUrl());
			json.append(",\"currency\":");
			appendJsonString(json, row.currency().getCurrencyCode());
			json.append(",\"custom_fields\":");
			appendJsonString(json, row.customFields().json());
			json.append('}');
		}
		return json.append(']').toString();
	}

	/**
	 * En JSON-null for en fraværende værdi — {@code jsonb_to_recordset} giver
	 * SQL NULL for den. En tom streng ville derimod blive gemt som en tom
	 * {@code venue}, og {@code event_venue_not_blank} ville afvise hele batchen.
	 */
	private static void appendNullableJsonString(StringBuilder json, String text) {
		if (text == null) {
			json.append("null");
		}
		else {
			appendJsonString(json, text);
		}
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
		return (instant == null) ? null : instant.atOffset(ZoneOffset.UTC);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return (value == null) ? null : value.toInstant();
	}

	private static void requireSameEntity(UUID id, CustomFieldColumns fields) {
		if (!id.equals(fields.id())) {
			throw new IllegalArgumentException(
					"The custom field document belongs to entity " + fields.id() + ", not to event " + id);
		}
	}

	/**
	 * To rækker med samme ref i én batch ville få Postgres til at svare
	 * {@code ON CONFLICT DO UPDATE command cannot affect row a second time} —
	 * en besked, der ikke peger på den ref, kunden sendte to gange. Parseren
	 * afviser det som {@code DUPLICATE_REF} med en JSON-Pointer; dette er nettet
	 * under den.
	 */
	private static void requireDistinctRefs(List<EventRow> rows) {
		Set<String> seen = new HashSet<>();
		for (EventRow row : rows) {
			if (!seen.add(row.ref().value())) {
				throw new IllegalArgumentException(
						"The batch carries the event ref [" + row.ref().value() + "] more than once");
			}
		}
	}

	// --- Wire-formerne ------------------------------------------------------

	/**
	 * Ét event i den normaliserede projektion, upserten skriver.
	 *
	 * <p>{@code organizerId} er allerede opløst: refs oversættes til uuid'er i
	 * Java og aldrig i et JOIN. En INNER JOIN ville droppe et event med en ukendt
	 * {@code organizerRef} i stilhed (rækketallet kommer bare mindre tilbage), og
	 * en LEFT JOIN til default-arrangøren ville flytte det i stilhed — begge er
	 * præcis den fejlklasse, designet findes for.
	 *
	 * <p>{@code version}, {@code created_at} og {@code updated_at} står med vilje
	 * <strong>ikke</strong> på listen: de er databasens og upsertens, ikke
	 * kundens.
	 */
	public record EventRow(UUID organizerId, EventRef ref, String title, String venue,
			Instant startsAt, Instant endsAt, SalesStatus salesStatus, Instant salesEndsAt,
			String primaryPurchaseUrl, Currency currency, CustomFieldValues customFields) {

		private static final char NUL = '\0';

		public EventRow {
			Objects.requireNonNull(organizerId, "organizerId");
			Objects.requireNonNull(ref, "ref");
			Objects.requireNonNull(title, "title");
			Objects.requireNonNull(startsAt, "startsAt");
			Objects.requireNonNull(salesStatus, "salesStatus");
			Objects.requireNonNull(currency, "currency");
			Objects.requireNonNull(customFields, "customFields");
			// Samme regel som Money og Event: en valuta uden mindste enhed (XXX,
			// XTS) kan aldrig baere et beloeb, og et event i den kunne aldrig faa
			// en billettype laest tilbage. Pushet er den ene doer, kundens streng
			// kommer ind ad, saa reglen staar ogsaa her.
			if (!Money.hasMinorUnit(currency)) {
				throw new IllegalArgumentException("Event [" + ref.value() + "] is in currency "
						+ currency.getCurrencyCode() + ", which has no minor unit and can never carry a face value");
			}
			requireNoNul(ref.value(), "title", title);
			requireNoNul(ref.value(), "venue", venue);
			requireNoNul(ref.value(), "primaryPurchaseUrl", primaryPurchaseUrl);
			// Postgres gemmer MIKROsekunder og runder HALV-OP. Trunkeres der
			// ikke her, er den vaerdi, vi sammenligner med i naeste push, ikke
			// den, der staar i raekken -- og idempotensen ville afhaenge af, hvor
			// mange decimaler kundens system tilfaeldigvis sendte.
			startsAt = startsAt.truncatedTo(ChronoUnit.MICROS);
			endsAt = truncatedToMicros(endsAt);
			salesEndsAt = truncatedToMicros(salesEndsAt);
			// Et dokument valideret mod ARRANGOER-felterne maa ikke kunne
			// skrives paa et event. Kontrollen ligger her, fordi det er den ene
			// doer.
			if (customFields.scope() != CustomFieldScope.EVENT) {
				throw new IllegalArgumentException("The custom fields of event [" + ref.value()
						+ "] were validated for scope " + customFields.scope() + ", not EVENT");
			}
		}

		private static Instant truncatedToMicros(Instant value) {
			return (value == null) ? null : value.truncatedTo(ChronoUnit.MICROS);
		}

		/** Vaerdien navngives ALDRIG: den er kundens data og ender i loggen. */
		private static void requireNoNul(String ref, String field, String value) {
			if (value != null && value.indexOf(NUL) >= 0) {
				throw new IllegalArgumentException("The " + field + " of event [" + ref
						+ "] contains a NUL character (U+0000), which PostgreSQL text cannot store");
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
	 * hver eneste nyoprettelse til en {@code event.updated} i audit-loggen fra
	 * dag ét.
	 *
	 * <p>{@code beforeState} er {@code null} for en nyoprettelse. Begge
	 * tilstande er jsonb-tekst, ikke et træ: de går ubearbejdet videre til
	 * {@code audit_log}.
	 *
	 * <p>Recorden er med vilje gentaget i hver af de tre stores frem for at være
	 * én delt type. De tre niveauer forbruges hver for sig — hvert med sit eget
	 * {@code entity_type} og sine egne handlingsnavne — og en fælles type ville
	 * skulle ligge i sin egen fil.
	 */
	public record UpsertOutcome(UUID id, String ref, boolean created, String beforeState, String afterState) {
	}

}

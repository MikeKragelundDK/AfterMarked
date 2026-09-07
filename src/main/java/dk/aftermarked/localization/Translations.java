package dk.aftermarked.localization;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

import dk.aftermarked.tenancy.OperatorContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Oversættelser af <em>kundens egne</em> tekster: operatørnavne, event-titler,
 * spillesteder og billettypenavne. Systemets egne tekster ligger i
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
 *
 * <p><strong>Ændret i Sprint 2 — en tværmodul-ændring, sagt højt.</strong>
 * Katalog-ingesten (modulet {@code catalog}) er kalderen, men ændringen
 * ligger her, fordi {@code translation} er dette moduls tabel. CLAUDE.md kræver,
 * at en ændring i et modul, sprintet ikke ejer, siger sin grund højt — den er
 * denne:
 *
 * <p><strong>1) No-op-guarden på {@link #put}.</strong> Upserten havde et
 * ubetinget {@code DO UPDATE}. Et identisk andet katalog-push skrev derfor hver
 * eneste oversættelsesrække om igen: {@code updated_at} rykkede, rækken fik ny
 * {@code xmin}, og hvert natligt push producerede en ny generation af rækker,
 * som autovacuum bagefter skulle rydde op efter. Værre er, at sprintets
 * exit-kriterie — "push to gange, ingenting ændrer sig" — dermed var falsk for
 * hvert eneste oversatte felt, og <em>ingen</em> eksisterende test kiggede på
 * det: idempotens blev målt på katalogtabellerne alene, så hullet var grønt
 * hele vejen. {@code WHERE translation.value IS DISTINCT FROM EXCLUDED.value}
 * lukker det, og returværdien gør det målbart.
 *
 * <p><strong>2) {@link #replaceAll} — mængdeerstatning, batchet og scopet.</strong>
 * Et katalog-push er en komplet entitet: de sprog, der ikke længere står i
 * payloaden, skal forsvinde. Alternativet — en {@code put} pr. (entitet, felt,
 * sprog) — er titusindvis af sætninger for et første dump inde i netop den
 * transaktion, der også holder skrivestiens advisory-lås.
 *
 * @see TranslationRow
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
	 *
	 * <p>{@code WHERE translation.value IS DISTINCT FROM EXCLUDED.value} er ikke
	 * pynt: uden den flytter et identisk andet push {@code updated_at} og
	 * {@code xmin} på hver eneste række, og løftet om at et gentaget push ikke
	 * ændrer noget er falsk for hvert oversat felt.
	 *
	 * <p>{@code IS DISTINCT FROM} og ikke {@code <>}: kolonnen er ganske vist
	 * {@code NOT NULL}, men de to operatorer skal betyde det samme her og i
	 * {@link #replaceAll}, hvor formen er den, der bærer.
	 *
	 * @return {@code true} hvis værdien faktisk ændrede sig (eller rækken blev
	 *         oprettet), {@code false} hvis skrivningen var en no-op. En
	 *         {@code false} er et SUCCES-signal — ikke en fejl.
	 */
	@Transactional
	public boolean put(String entityType, UUID entityId, String field, Locale locale, String value) {
		Objects.requireNonNull(value, "value");
		OperatorContext.required();
		// operator_id skrives som current_operator_id() og bindes ALDRIG som
		// parameter: så findes der ingen kodesti, der KAN bære et forkert
		// tenant-id ind i en indsættelse -- samme doktrin som replaceAll.
		return jdbc.sql("""
				INSERT INTO translation (operator_id, entity_type, entity_id, field, locale, value)
				VALUES (current_operator_id(), :entityType, :entityId, :field, :locale, :value)
				ON CONFLICT (operator_id, entity_type, entity_id, field, locale)
				DO UPDATE SET value = EXCLUDED.value, updated_at = now()
				 WHERE translation.value IS DISTINCT FROM EXCLUDED.value
				""")
				.param("entityType", entityType)
				.param("entityId", entityId)
				.param("field", field)
				.param("locale", tag(locale))
				.param("value", value)
				.update() > 0;
	}

	/**
	 * Mængdeerstatning af oversættelser for én entitetstype, batchet i to
	 * sætninger uanset hvor mange rækker der er.
	 *
	 * <p><strong>{@code ownedFields} er den bærende del af signaturen.</strong>
	 * Sletningen er scopet til (entitet, felt)-parrene <em>kalderen ejer</em> —
	 * {@code name} for en arrangør, {@code title} og {@code venue} for et event,
	 * {@code name} for en billettype. Uden det scope ville et natligt push slette
	 * operatørens egne admin-oversættelser af <em>andre</em> felter kl. 03 uden
	 * så meget som en linje i audit-loggen: teksterne ville bare være væk næste
	 * morgen, og ingen ville kunne se hvorfor.
	 *
	 * <p><strong>To sætninger, ikke en løkke.</strong> Først en DELETE af de
	 * ejede rækker, payloaden ikke længere indeholder, så en batchet upsert af
	 * dem den gør — begge over det samme jsonb-dokument gennem
	 * {@code jsonb_to_recordset}. En løkke pr. (entitet, felt, sprog) ville være
	 * titusindvis af sætninger for et første dump, inde i den transaktion der
	 * også holder katalogets advisory-lås.
	 *
	 * <p>Upserten bærer samme no-op-guard som {@link #put}, så en uændret række
	 * ikke skrives om. {@code written} tæller derfor kun de rækker, der faktisk
	 * blev oprettet eller ændret — og et andet, identisk push giver
	 * {@code new TranslationChange(0, 0)}.
	 *
	 * <p><strong>Tre ting afvises højlydt, før nogen SQL kører</strong>, fordi de
	 * alle tre ville efterlade en række, mængdeerstatningen aldrig kan nå igen
	 * (DELETE'en er scopet til præcis {@code entityIds} og {@code ownedFields},
	 * så en række uden for scopet ville være udødelig):
	 * <ul>
	 *   <li>en række, hvis {@code entityId} ikke står i {@code entityIds}</li>
	 *   <li>en række, hvis {@code field} ikke står i {@code ownedFields}</li>
	 *   <li>to rækker med samme (entitet, felt, sprog) — PostgreSQL afviser dem
	 *       selv med <em>"ON CONFLICT DO UPDATE command cannot affect row a
	 *       second time"</em>, og den besked peger ikke på kalderens fejl</li>
	 * </ul>
	 * Alle tre er {@link IllegalArgumentException}.
	 *
	 * <p>Tomme input er derimod lovlige og gør det rigtige: tom {@code rows} er
	 * en ren sletning af alt ejet i scopet, og tom {@code entityIds} eller tom
	 * {@code ownedFields} er en no-op, der ikke rører databasen.
	 *
	 * @param entityType  entitetstypen, fælles for hele batchen ({@code event},
	 *                    {@code organizer}, {@code ticket_type})
	 * @param entityIds   de entiteter, erstatningen dækker — sletningen når
	 *                    aldrig uden for dem
	 * @param ownedFields de felter, kalderen ejer. Alt andet på de samme
	 *                    entiteter overlever urørt
	 * @param rows        de oversættelser, der skal stå tilbage bagefter
	 * @return hvor mange rækker der blev skrevet, og hvor mange der blev fjernet
	 */
	@Transactional
	public TranslationChange replaceAll(String entityType, Collection<UUID> entityIds,
			Set<String> ownedFields, List<TranslationRow> rows) {
		TranslationChanges changes = replaceAllWithChanges(entityType, entityIds, ownedFields, rows);
		return new TranslationChange(changes.written(), changes.removed());
	}

	/**
	 * Samme erstatning som {@link #replaceAll}, men med de faktisk ændrede
	 * rækkers før/efter-tilstand til kalderens auditspor. Udvidelsen bruges af
	 * katalog-modulet; SQL og RLS bliver i localization, og audit-aktøren bliver
	 * hos kalderen. RETURNING læser tilstanden i selve skrivningen, så et
	 * samtidigt PUT ikke kan gøre et separat før-opslag forældet.
	 *
	 * <p>Oprettelser har ingen før-tilstand, sletninger ingen efter-tilstand.
	 * Identiske rækker returneres ikke. Begge SQL-sætninger og kalderens audit
	 * deler transaktion; den oprindelige tællebaserede signatur bevares.
	 */
	@Transactional
	public TranslationChanges replaceAllWithChanges(String entityType, Collection<UUID> entityIds,
			Set<String> ownedFields, List<TranslationRow> rows) {
		Objects.requireNonNull(entityType, "entityType");
		Objects.requireNonNull(entityIds, "entityIds");
		Objects.requireNonNull(ownedFields, "ownedFields");
		Objects.requireNonNull(rows, "rows");
		// Samme kontrakt som put: en oversaettelse uden ejer hoerer ingen steder
		// hjemme. Uden dette kald ville et kontekstloest kald i stilhed ramme nul
		// raekker gennem RLS og rapportere succes.
		OperatorContext.required();
		rejectRowsOutsideTheReplacedSet(entityIds, ownedFields, rows);

		// Intet at scope sletningen til. Raekkerne er allerede kontrolleret
		// ovenfor, saa listen kan ikke indeholde noget, der ville gaa tabt her.
		if (entityIds.isEmpty() || ownedFields.isEmpty()) {
			return new TranslationChanges(List.of());
		}

		String pushed = pushedAsJson(rows);
		List<TranslationRowChange> changes = new ArrayList<>(jdbc.sql("""
				DELETE FROM translation t
				 WHERE t.entity_type = :entityType
				   AND t.entity_id = ANY(CAST(:entityIds AS uuid[]))
				   AND t.field     = ANY(CAST(:ownedFields AS text[]))
				   AND NOT EXISTS (SELECT 1
				                     FROM jsonb_to_recordset(CAST(:pushed AS jsonb))
				                            AS p(entity_id uuid, field text, locale text, value text)
				                    WHERE p.entity_id = t.entity_id AND p.field = t.field AND p.locale = t.locale)
				RETURNING t.entity_id, to_jsonb(t.*) AS before_state
				""")
				.param("entityType", entityType)
				.param("entityIds", uuidArrayLiteral(entityIds))
				.param("ownedFields", textArrayLiteral(ownedFields))
				.param("pushed", pushed)
				.query((rs, row) -> new TranslationRowChange(rs.getObject("entity_id", UUID.class),
						rs.getString("before_state"), null))
				.list());

		// Et tomt dokument ville indsaette nul raekker, men saetningen ville
		// stadig tage sine laase. Sletningen ovenfor var hele arbejdet.
		if (rows.isEmpty()) {
			return new TranslationChanges(changes);
		}

		changes.addAll(jdbc.sql("""
				INSERT INTO translation (operator_id, entity_type, entity_id, field, locale, value)
				SELECT current_operator_id(), :entityType, p.entity_id, p.field, p.locale, p.value
				  FROM jsonb_to_recordset(CAST(:pushed AS jsonb))
				         AS p(entity_id uuid, field text, locale text, value text)
				 ORDER BY p.entity_id, p.field, p.locale
				ON CONFLICT (operator_id, entity_type, entity_id, field, locale) DO UPDATE
				   SET value = EXCLUDED.value, updated_at = now()
				 WHERE translation.value IS DISTINCT FROM EXCLUDED.value
				RETURNING new.entity_id,
				          CASE WHEN old.id IS NULL THEN NULL ELSE to_jsonb(old.*) END AS before_state,
				          to_jsonb(new.*) AS after_state
				""")
				.param("entityType", entityType)
				.param("pushed", pushed)
				.query((rs, row) -> new TranslationRowChange(rs.getObject("entity_id", UUID.class),
						rs.getString("before_state"), rs.getString("after_state")))
				.list());

		return new TranslationChanges(changes);
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

	/**
	 * Kontrollerne, der gør mængdeerstatningen til en mængdeerstatning. Se
	 * {@link #replaceAll} for hvorfor hver af dem er en fejl og ikke en
	 * bekvemmelighed.
	 */
	private static void rejectRowsOutsideTheReplacedSet(Collection<UUID> entityIds, Set<String> ownedFields,
			List<TranslationRow> rows) {
		Set<UUID> replaced = new HashSet<>(entityIds);
		Set<String> seen = new HashSet<>();
		for (TranslationRow row : rows) {
			if (!replaced.contains(row.entityId())) {
				throw new IllegalArgumentException("Translation row for entity " + row.entityId()
						+ " is outside the replaced set — the delete is scoped to entityIds,"
						+ " so such a row could never be removed again");
			}
			if (!ownedFields.contains(row.field())) {
				throw new IllegalArgumentException("Translation row for field [" + row.field()
						+ "] is outside the owned fields " + ownedFields
						+ " — writing it would create a translation no later push could remove");
			}
			if (!seen.add(row.entityId() + "/" + row.field() + "/" + row.locale())) {
				throw new IllegalArgumentException("Duplicate translation row for [" + row.field() + "/"
						+ row.locale() + "] on entity " + row.entityId()
						+ " — ON CONFLICT DO UPDATE cannot affect the same row twice in one statement");
			}
		}
	}

	/**
	 * Batchen som ét jsonb-dokument: {@code [{"entity_id": …, "field": …,
	 * "locale": …, "value": …}, …]}.
	 *
	 * <p>Nøglerne er snake_case og matcher kolonnenavnene i
	 * {@code jsonb_to_recordset(...) AS p(entity_id uuid, …)} ordret — den
	 * funktion er case-sensitiv, og en camelCase-nøgle ville give en tavs NULL i
	 * stedet for en fejl.
	 *
	 * <p><strong>Hvorfor en håndskreven writer.</strong> Jackson ville trække en
	 * afhængighed ind i et modul, der ikke har nogen JSON-flade, alene for at
	 * bygge fire strengfelter. Til gengæld <em>skal</em> escapingen være rigtig:
	 * værdien er kundens tekst og kan indeholde citationstegn, backslash,
	 * linjeskift og alt uden for ASCII. Non-ASCII sendes råt — forbindelsen er
	 * UTF-8, og jsonb tager imod det — mens styretegn under {@code U+0020}
	 * escapes, fordi JSON ikke tillader dem rå. {@code U+0000} kan slet ikke
	 * lagres og er allerede afvist i {@link TranslationRow}.
	 */
	private static String pushedAsJson(List<TranslationRow> rows) {
		StringBuilder json = new StringBuilder(rows.size() * 96).append('[');
		for (int i = 0; i < rows.size(); i++) {
			TranslationRow row = rows.get(i);
			if (i > 0) {
				json.append(',');
			}
			json.append("{\"entity_id\":");
			appendJsonString(json, row.entityId().toString());
			json.append(",\"field\":");
			appendJsonString(json, row.field());
			json.append(",\"locale\":");
			appendJsonString(json, row.locale());
			json.append(",\"value\":");
			appendJsonString(json, row.value());
			json.append('}');
		}
		return json.append(']').toString();
	}

	/** Én JSON-streng, escaped efter RFC 8259 §7. */
	private static void appendJsonString(StringBuilder json, String text) {
		json.append('"');
		for (int i = 0; i < text.length(); i++) {
			char character = text.charAt(i);
			switch (character) {
				case '"' -> json.append("\\\"");
				case '\\' -> json.append("\\\\");
				case '\b' -> json.append("\\b");
				case '\f' -> json.append("\\f");
				case '\n' -> json.append("\\n");
				case '\r' -> json.append("\\r");
				case '\t' -> json.append("\\t");
				default -> {
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
	 * Postgres' tekstform for et {@code uuid[]}: <code>{a,b,c}</code>. En UUID's
	 * {@code toString()} er hex og bindestreger, så der er intet at citere. En
	 * tom samling giver <code>{}</code>, som er et gyldigt tomt array — derfor
	 * kan sætningen bygges ens uanset antal.
	 */
	private static String uuidArrayLiteral(Collection<UUID> ids) {
		StringJoiner literal = new StringJoiner(",", "{", "}");
		for (UUID id : ids) {
			literal.add(id.toString());
		}
		return literal.toString();
	}

	/**
	 * Samme for {@code text[]}, men her citeres hvert element. Feltnavnene er
	 * vores egne konstanter i dag, og citeringen er derfor ikke en beskyttelse
	 * mod noget aktuelt — den er der, fordi et array-literal med et komma eller
	 * en tuborgklamme i en værdi ellers ville dele elementet i to uden at fejle.
	 */
	private static String textArrayLiteral(Collection<String> values) {
		StringJoiner literal = new StringJoiner(",", "{", "}");
		for (String value : values) {
			literal.add('"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"');
		}
		return literal.toString();
	}

	/**
	 * Hvad en mængdeerstatning faktisk gjorde ved databasen.
	 *
	 * <p>{@code written} tæller de rækker, der blev oprettet eller <em>ændret</em>
	 * — no-op-guarden gør, at en uændret række ikke tælles med. Et andet,
	 * identisk push giver derfor {@code new TranslationChange(0, 0)}, og det er
	 * netop dét, sprintets exit-kriterie kan måles på.
	 */
	public record TranslationChange(int written, int removed) {
	}

	/** Ændringerne fra RETURNING; tællerne afledes af de samme rækker som auditsporet. */
	public record TranslationChanges(List<TranslationRowChange> rows) {

		public TranslationChanges {
			rows = List.copyOf(rows);
		}

		public int written() {
			return (int) rows.stream().filter(row -> row.afterState() != null).count();
		}

		public int removed() {
			return rows.size() - written();
		}

	}

	/** Subjektet er katalogentiteten; dokumenterne indeholder også felt, sprog og oversættelsens eget id. */
	public record TranslationRowChange(UUID entityId, String beforeState, String afterState) {

		public TranslationRowChange {
			Objects.requireNonNull(entityId, "entityId");
			if (beforeState == null && afterState == null) {
				throw new IllegalArgumentException("A translation change must have a before or after state");
			}
		}

	}

}

package dk.aftermarked.invariant.audit;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import dk.aftermarked.TestcontainersConfiguration;
import dk.aftermarked.audit.AuditActor;
import dk.aftermarked.audit.AuditEntry;
import dk.aftermarked.audit.AuditLog;
import dk.aftermarked.invariant.migration.AggregateSchema;
import dk.aftermarked.tenancy.OperatorContext;
import dk.aftermarked.tenancy.OperatorId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INVARIANT — audit-loggen (V5, PLAN §2.3). Denne test er spec.
 *
 * <p>Loggen har tre egenskaber, som hver især er værdiløse uden de to andre, og
 * som alle tre kun kan måles mod en rigtig database:
 * <ul>
 *   <li><strong>Append-only for applikationen.</strong> V1's
 *       {@code ALTER DEFAULT PRIVILEGES} giver automatisk fuld CRUD på hver
 *       fremtidig tabel, så uden V5's {@code REVOKE} ville loggen kunne
 *       omskrives af præcis den kode, den skal holde øje med.</li>
 *   <li><strong>Tenant-isoleret.</strong> En kundes handlinger må aldrig kunne
 *       læses af en anden.</li>
 *   <li><strong>Sletbar sammen med kunden.</strong> Offboarding er et
 *       {@code DELETE FROM operator}, og otte testklasser rydder op med præcis
 *       den sætning mod den delte Spring-kontekst.</li>
 * </ul>
 *
 * <p><strong>Den tredje og den første ser ud til at udelukke hinanden</strong>, og
 * derfor er {@link #givenAnOperatorWithAuditRows_whenTheOperatorIsDeleted_thenTheAuditRowsCascadeAwayDespiteTheRevokedDelete()}
 * den test, migrationen står og falder med: den beviser, at en referentiel
 * handling ikke tjekker den kaldende rolles rettigheder, så kaskaden lykkes,
 * selv om {@code DELETE} er trukket tilbage. Fejler den, er faldback'en at
 * beholde kaskaden og kun trække {@code UPDATE} tilbage — og dét skal siges
 * højt, for det svækker append-only.
 *
 * <p>Oprydningen kører derfor på en <em>ejer</em>-forbindelse
 * ({@code ownerConnection()}-idiomet fra {@code TranslationSchemaInvariantTest}):
 * runtime-rollen KAN ikke slette i loggen, og at testens egen oprydning er nødt
 * til at gå uden om applikationens rolle, er i sig selv et pænt andet bevis for,
 * at tilbagetrækningen virker.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditLogInvariantTest {

	static final OperatorId A = new OperatorId(UUID.fromString("00000000-0000-4000-8000-0000000000f1"));

	static final OperatorId B = new OperatorId(UUID.fromString("00000000-0000-4000-8000-0000000000f2"));

	/** Subjektet for de seedede linjer — et event, der aldrig oprettes som række. */
	static final UUID SEEDED_SUBJECT = UUID.fromString("00000000-0000-4000-8000-00000000e0a1");

	/** Subjektet for den arrangør, der oprettes og slettes igen nedenfor. */
	static final UUID ORGANIZER = UUID.fromString("00000000-0000-4000-8000-00000000aa01");

	static final String PUSH_ID = "5f1c9b60-0f0a-4a5a-9c1e-9a1d2e3f4a5b";

	/**
	 * Rettigheder, runtime-rollen IKKE må have på loggen.
	 * {@code MAINTAIN} findes fra Postgres 17 og accepteres af
	 * {@code has_table_privilege} på 18.6.
	 */
	static final List<String> WITHHELD_PRIVILEGES = List.of("UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER",
			"MAINTAIN");

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	AuditLog auditLog;

	/**
	 * Grundlinjen skrives som EJEREN og ikke gennem porten. Kaskade-testen
	 * nedenfor gater migrationen, og den må ikke kunne blive rød af en fejl i
	 * Java-skriveren — den skal måle databasen. Porten prøves af round-trip- og
	 * subjekt-testene, som er dem, den hører til i.
	 */
	@BeforeEach
	void seedTwoOperatorsWithAnAuditTrailEach() throws SQLException {
		try (Connection owner = ownerConnection(); Statement sql = owner.createStatement()) {
			sql.execute("DELETE FROM audit_log");
			sql.execute("DELETE FROM operator");
			sql.execute("""
					INSERT INTO operator (id, slug, name, hostname) VALUES
					  ('%s', 'au-a', 'Operator A', 'a.audit.test'),
					  ('%s', 'au-b', 'Operator B', 'b.audit.test')
					""".formatted(A, B));
			sql.execute("""
					INSERT INTO audit_log (operator_id, actor_type, actor_ref, action, entity_type,
					                       entity_id, entity_ref, before_state, after_state) VALUES
					  ('%s', 'catalog_push', 'seed-push-a', 'event.created', 'event',
					   '%s', 'a-summer-2027', NULL, '{"title": "A"}'),
					  ('%s', 'catalog_push', 'seed-push-b', 'event.created', 'event',
					   '%s', 'b-summer-2027', NULL, '{"title": "B"}')
					""".formatted(A, SEEDED_SUBJECT, B, SEEDED_SUBJECT));
		}
	}

	// --- Skema og round-trip -----------------------------------------------

	/**
	 * Begge veje, som PLAN §3 kræver af hvert aggregat. {@code operator_id}
	 * trækkes FRA tabellens kolonner og lægges aldrig TIL recordens: kolonnen
	 * findes med vilje ikke som felt, fordi skriveren skriver
	 * {@code current_operator_id()} ordret i SQL'en.
	 */
	@Test
	void givenTheAuditRowRecord_whenComparingItToTheAuditLogTable_thenEveryFieldHasAColumnAndEveryColumnAField() {
		Set<String> columns = AggregateSchema.columnsOf(jdbc, "audit_log");
		columns.removeAll(AggregateSchema.DATABASE_OWNED);

		assertThat(columns)
				.as("a column without a field is data nobody reads; a field without a column is a write that fails")
				.containsExactlyInAnyOrderElementsOf(AggregateSchema.columnsCoveredBy(AuditEntry.class));
	}

	/**
	 * Skrivning og læsning ligger begge inde i en operatør-kontekst, og ingen af
	 * dem åbner en transaktion udenom: kontekstens værdi lægges på databasen ved
	 * forbindelses-checkout, så en forbindelse taget FØR {@code runAs} ser nul
	 * rækker — hvilket ligner en mapping-fejl og ikke er det.
	 *
	 * <p>De to tilstandskolonner sammenlignes i SQL og ikke som strenge:
	 * {@code jsonb} normaliserer nøglerækkefølge og whitespace, så
	 * <code>{"title":"Old"}</code> kommer tilbage som
	 * <code>{"title": "Old"}</code>. En tekstsammenligning ville bestå ved held
	 * og fejle den dag et dokument blev skrevet i en anden form.
	 */
	@Test
	void givenAnAuditEntry_whenWrittenAndReadBack_thenItIsEqualToTheOriginal() {
		AuditEntry written = AuditEntry.of(AuditActor.catalogPush(PUSH_ID), "event.updated", "event", SEEDED_SUBJECT,
				"a-summer-2027", "{\"title\":\"Old\"}", "{\"title\":\"New\"}");

		OperatorContext.runAs(A, () -> auditLog.record(written));
		AuditEntry readBack = OperatorContext.callAs(A, () -> entryFor("event.updated"));

		assertThat(readBack.id()).as("the identity column fills itself").isNotNull();
		assertThat(readBack.occurredAt()).as("occurred_at DEFAULT now() fills itself").isNotNull();
		assertThat(withoutTheColumnsTheDatabaseOwns(readBack))
				.as("a forgotten column in the INSERT or a forgotten line in the RowMapper shows up here")
				.isEqualTo(withoutTheColumnsTheDatabaseOwns(written));
		assertThat(OperatorContext.callAs(A, () -> statesMatchInTheDatabase("event.updated", written)))
				.as("the two jsonb documents must come back as the same documents")
				.isTrue();
	}

	// --- Append-only --------------------------------------------------------

	/**
	 * Uden {@code REVOKE UPDATE} ville en linje kunne rettes af den samme kode,
	 * den er skrevet af — og en log, der kan rettes bagudrettet, er ikke en log.
	 */
	@Test
	void givenAnAuditRow_whenTheApplicationTriesToUpdateIt_thenPermissionIsDenied() throws SQLException {
		assertThatThrownBy(() -> OperatorContext.runAs(A,
				() -> jdbc.sql("UPDATE audit_log SET action = 'event.tampered'").update()))
				.isInstanceOf(DataAccessException.class)
				.rootCause().hasMessageContaining("permission denied");

		assertThat(actionsAsOwner(A)).containsExactly("event.created");
	}

	@Test
	void givenAnAuditRow_whenTheApplicationTriesToDeleteIt_thenPermissionIsDenied() throws SQLException {
		assertThatThrownBy(() -> OperatorContext.runAs(A, () -> jdbc.sql("DELETE FROM audit_log").update()))
				.isInstanceOf(DataAccessException.class)
				.rootCause().hasMessageContaining("permission denied");

		assertThat(entityRefsAsOwner(A)).containsExactly("a-summer-2027");
	}

	/**
	 * Rettighedslisten læses fuldt ud og ikke som to booleans: hullet, testen
	 * findes for, er en rettighed, ingen tænkte på — og {@code TRUNCATE} er den
	 * værste af dem, fordi den omgår RLS helt.
	 *
	 * <p>{@code has_table_privilege} med to argumenter spørger for
	 * {@code current_user}, altså {@code theticket_runtime}: præcis den rolle
	 * appen forbinder som, og den arver alt, den har, fra gruppen
	 * {@code theticket_app}.
	 */
	@Test
	void givenTheAuditTable_whenReadingTheRuntimeRolesPrivileges_thenOnlySelectAndInsertAreGranted() {
		assertThat(privilege("SELECT")).as("operator admin must be able to read the trail").isTrue();
		assertThat(privilege("INSERT")).as("every module must be able to append a line").isTrue();

		for (String withheld : WITHHELD_PRIVILEGES) {
			assertThat(privilege(withheld))
					.as("[%s] on audit_log would make the log rewritable by the code it watches", withheld)
					.isFalse();
		}
	}

	// --- Tenant-isolation ---------------------------------------------------

	@Test
	void givenTwoOperators_whenOneReadsTheOthersAuditTrail_thenItSeesNothing() {
		assertThat(OperatorContext.callAs(A, this::visibleEntityRefs))
				.containsExactly("a-summer-2027");
		assertThat(OperatorContext.callAs(B, this::visibleEntityRefs))
				.containsExactly("b-summer-2027");
		assertThat(visibleEntityRefs())
				.as("no operator context must mean nothing rather than everything")
				.isEmpty();
	}

	// --- Offboarding --------------------------------------------------------

	/**
	 * Den test, V5 står og falder med. Referentielle handlinger kører gennem
	 * interne triggere, der skifter til tabelejeren, så kaskaden lykkes uanset
	 * runtime-rollens tilbagetrukne {@code DELETE}. Var det ikke sandt, ville
	 * enten offboarding være umulig, eller loggen skulle kunne slettes af
	 * applikationen — og de otte testklasser, der rydder op med
	 * {@code DELETE FROM operator}, ville fejle på en fremmednøgle, så snart der
	 * fandtes én audit-række.
	 */
	@Test
	void givenAnOperatorWithAuditRows_whenTheOperatorIsDeleted_thenTheAuditRowsCascadeAwayDespiteTheRevokedDelete()
			throws SQLException {
		assertThat(entityRefsAsOwner(A))
				.as("an empty trail would make the assertion below true for the wrong reason")
				.isNotEmpty();

		int deleted = OperatorContext.callAs(A,
				() -> jdbc.sql("DELETE FROM operator WHERE id = :id").param("id", A.value()).update());

		assertThat(deleted).as("the runtime role owns offboarding; only the log is append-only").isOne();
		assertThat(entityRefsAsOwner(A)).isEmpty();
		assertThat(entityRefsAsOwner(B)).as("only the deleted customer's trail goes away").isNotEmpty();
	}

	/**
	 * Loggen skal overleve sit eget subjekt. Der er med vilje ingen fremmednøgle
	 * på {@code entity_id}: en arrangør, et event eller en billettype kan
	 * forsvinde, og linjen skal stadig kunne læses af et menneske — derfor
	 * står {@code entity_ref} ved siden af id'et.
	 */
	@Test
	void givenADeletedSubject_whenReadingTheAuditEntry_thenItSurvivesWithItsEntityRef() {
		OperatorContext.runAs(A, () -> {
			jdbc.sql("""
					INSERT INTO organizer (id, operator_id, ref, name)
					VALUES (:id, current_operator_id(), 'acme', 'Acme Live')
					""").param("id", ORGANIZER).update();
			auditLog.record(AuditEntry.of(AuditActor.catalogPush(PUSH_ID), "organizer.created", "organizer", ORGANIZER,
					"acme", null, "{\"name\":\"Acme Live\"}"));
			assertThat(jdbc.sql("DELETE FROM organizer WHERE id = :id").param("id", ORGANIZER).update()).isOne();
		});

		AuditEntry survivor = OperatorContext.callAs(A, () -> entryFor("organizer.created"));

		assertThat(survivor.entityId()).isEqualTo(ORGANIZER);
		assertThat(survivor.entityRef()).as("the ref is what makes an orphaned line readable").isEqualTo("acme");
		assertThat(survivor.beforeState()).as("a creation has nothing before it").isNull();
	}

	// -----------------------------------------------------------------------

	private List<String> visibleEntityRefs() {
		return jdbc.sql("SELECT entity_ref FROM audit_log ORDER BY id").query(String.class).list();
	}

	private boolean privilege(String privilege) {
		return jdbc.sql("SELECT has_table_privilege('audit_log', :privilege)")
				.param("privilege", privilege).query(Boolean.class).single();
	}

	private AuditEntry entryFor(String action) {
		return jdbc.sql("""
				SELECT id, occurred_at, actor_type, actor_ref, action, entity_type, entity_id, entity_ref,
				       before_state, after_state
				  FROM audit_log
				 WHERE action = :action
				""").param("action", action).query((rs, rowNumber) -> toAuditEntry(rs)).single();
	}

	/**
	 * {@code timestamptz} læses som {@code OffsetDateTime} og ikke som en rå
	 * {@code Instant}: driveren kan ikke selv udlede SQL-typen, og en
	 * {@code LocalDateTime} ville flytte værdien med den lokale offset uden at
	 * fejle.
	 */
	private static AuditEntry toAuditEntry(ResultSet rs) throws SQLException {
		return new AuditEntry(rs.getLong("id"), rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
				new AuditActor(rs.getString("actor_type"), rs.getString("actor_ref")), rs.getString("action"),
				rs.getString("entity_type"), rs.getObject("entity_id", UUID.class), rs.getString("entity_ref"),
				rs.getString("before_state"), rs.getString("after_state"));
	}

	/**
	 * Linjen uden de fire kolonner, der ikke kan sammenlignes med {@code equals}:
	 * {@code id} og {@code occurred_at} fyldes af databasen, og de to
	 * jsonb-dokumenter normaliseres af den.
	 */
	private static AuditEntry withoutTheColumnsTheDatabaseOwns(AuditEntry entry) {
		return new AuditEntry(null, null, entry.actor(), entry.action(), entry.entityType(), entry.entityId(),
				entry.entityRef(), null, null);
	}

	private boolean statesMatchInTheDatabase(String action, AuditEntry written) {
		return jdbc.sql("""
				SELECT before_state IS NOT DISTINCT FROM CAST(:before AS jsonb)
				   AND after_state  IS NOT DISTINCT FROM CAST(:after  AS jsonb)
				  FROM audit_log
				 WHERE action = :action
				""")
				.param("action", action)
				.param("before", written.beforeState())
				.param("after", written.afterState())
				.query(Boolean.class).single();
	}

	/**
	 * Læser uden om RLS og uden om runtime-rollens rettigheder — det er den
	 * eneste vej til at se, hvad der FAKTISK står i loggen, når applikationen
	 * hverken må rette eller slette i den.
	 */
	private List<String> entityRefsAsOwner(OperatorId operator) throws SQLException {
		return columnAsOwner("SELECT entity_ref FROM audit_log WHERE operator_id = ? ORDER BY id", operator);
	}

	private List<String> actionsAsOwner(OperatorId operator) throws SQLException {
		return columnAsOwner("SELECT action FROM audit_log WHERE operator_id = ? ORDER BY id", operator);
	}

	/** Kører en af de to faste sætninger ovenfor. Værdien bindes; sætningen er en konstant. */
	private List<String> columnAsOwner(String query, OperatorId operator) throws SQLException {
		List<String> values = new ArrayList<>();
		try (Connection owner = ownerConnection(); var sql = owner.prepareStatement(query)) {
			sql.setObject(1, operator.value());
			try (ResultSet rs = sql.executeQuery()) {
				while (rs.next()) {
					values.add(rs.getString(1));
				}
			}
		}
		return values;
	}

	private Connection ownerConnection() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

}

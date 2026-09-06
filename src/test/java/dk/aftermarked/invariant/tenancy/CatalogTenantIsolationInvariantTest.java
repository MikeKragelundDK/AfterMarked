package dk.aftermarked.invariant.tenancy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dk.aftermarked.TestcontainersConfiguration;
import dk.aftermarked.tenancy.OperatorContext;
import dk.aftermarked.tenancy.OperatorId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INVARIANT — tenant-isolation på kataloget (V5, PLAN §2.7). Denne test er spec.
 *
 * <p>Den generelle test i denne pakke fanger, at RLS er slået til, og at der
 * <em>findes</em> en policy på hver tabel med {@code operator_id}. Denne prøver
 * aktivt at bryde isolationen på de tre katalogtabeller — og skal fejle i
 * forsøget.
 *
 * <p><strong>De to fremmednøgle-tests er de vigtigste i filen.</strong> RLS
 * gælder <em>ikke</em> for referentiel integritet: Postgres tjekker en
 * fremmednøgle med sin egen snapshot og uden policy. En FK på én kolonne mod
 * {@code organizer (id)} kan derfor pege på en ANDEN kundes arrangør, hvis nogen
 * kender uuid-værdien — og det ville lykkes med {@code INSERT 0 1}. Derfor er
 * hver FK sammensat med {@code operator_id}, og derfor prøver testen at bruge en
 * anden kundes id med fuld gyldig operator-kontekst.
 *
 * <p>Den tredje FK-kolonne er valutaen: {@code ticket_type} peger på
 * {@code (operator_id, id, currency)} i {@code event}, så "ét event = én valuta"
 * (PLAN §2.5) håndhæves af databasen og ikke af disciplin. Alle tre kolonner
 * skal forblive NOT NULL — {@code MATCH SIMPLE} er defaulten, og den springer
 * HELE tjekket over, hvis bare én FK-kolonne er NULL.
 *
 * <p>Seedningen kører som EJEREN: runtime-rollen er blokeret af RLS på
 * {@code operator} og kan ikke lægge to kunders data ind ved siden af hinanden.
 * At testens egen opsætning er nødt til at gå uden om applikationens rolle er i
 * sig selv et lille bevis for, at isolationen virker.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CatalogTenantIsolationInvariantTest {

	static final OperatorId A = new OperatorId(UUID.fromString("00000000-0000-4000-8000-0000000000c8"));

	static final OperatorId B = new OperatorId(UUID.fromString("00000000-0000-4000-8000-0000000000c9"));

	static final UUID A_ORGANIZER = UUID.fromString("00000000-0000-4000-8000-00000000c201");

	static final UUID A_EVENT = UUID.fromString("00000000-0000-4000-8000-00000000c202");

	static final UUID B_ORGANIZER = UUID.fromString("00000000-0000-4000-8000-00000000c211");

	static final UUID B_EVENT = UUID.fromString("00000000-0000-4000-8000-00000000c212");

	/** Begge kunder bruger med vilje den SAMME ref — nøglerummene skal være disjunkte. */
	static final String SHARED_REF = "summer-2027";

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void seedTwoOperatorsWithACatalogEach() throws SQLException {
		try (Connection owner = ownerConnection(); Statement sql = owner.createStatement()) {
			sql.execute("DELETE FROM operator");
			sql.execute("""
					INSERT INTO operator (id, slug, name, hostname) VALUES
					  ('%s', 'iso-a', 'Isolation A', 'a.isolation.test'),
					  ('%s', 'iso-b', 'Isolation B', 'b.isolation.test')
					""".formatted(A, B));
			sql.execute("""
					INSERT INTO organizer (id, operator_id, ref, name) VALUES
					  ('%s', '%s', 'acme', 'Acme A'),
					  ('%s', '%s', 'acme', 'Acme B')
					""".formatted(A_ORGANIZER, A, B_ORGANIZER, B));
			sql.execute("""
					INSERT INTO event (id, operator_id, organizer_id, ref, title, starts_at, sales_status, currency)
					VALUES
					  ('%s', '%s', '%s', '%s', 'A summer', TIMESTAMPTZ '2027-06-30 16:00:00+00', 'ON_SALE', 'DKK'),
					  ('%s', '%s', '%s', '%s', 'B summer', TIMESTAMPTZ '2027-06-30 16:00:00+00', 'ON_SALE', 'DKK')
					""".formatted(A_EVENT, A, A_ORGANIZER, SHARED_REF, B_EVENT, B, B_ORGANIZER, SHARED_REF));
			sql.execute("""
					INSERT INTO ticket_type (operator_id, event_id, ref, name, face_value_minor, currency)
					VALUES ('%s', '%s', 'standard', 'Standard', 33000, 'DKK')
					""".formatted(A, A_EVENT));
		}
	}

	// --- RLS ----------------------------------------------------------------

	@Test
	void givenTwoOperators_whenOneReadsTheOthersEvents_thenItSeesNothing() {
		assertThat(OperatorContext.callAs(A, this::visibleEventTitles)).containsExactly("A summer");
		assertThat(OperatorContext.callAs(B, this::visibleEventTitles)).containsExactly("B summer");

		assertThat(OperatorContext.callAs(A, () -> titleOf(B_EVENT)))
				.as("knowing another customer's uuid must not be enough to read the row")
				.isEmpty();
	}

	/**
	 * Kataloget er et spejl af KUNDENS system, så ref'en er kundens nøglerum —
	 * ikke vores. En global UNIQUE ville være et orakel i sig selv: fejlen ville
	 * afsløre, at en anden kunde bruger den ref, og et {@code DO NOTHING} ville
	 * rapportere succes uden at skrive noget. Derfor starter hver unik nøgle med
	 * {@code operator_id}.
	 */
	@Test
	void givenTwoOperators_whenBothUseTheSameRef_thenBothRowsAreStored() throws SQLException {
		assertThat(titlesAsOwner("SELECT title FROM event WHERE ref = ? ORDER BY title", SHARED_REF))
				.containsExactly("A summer", "B summer");

		assertThat(OperatorContext.callAs(A, this::visibleEventRefs)).containsExactly(SHARED_REF);
		assertThat(OperatorContext.callAs(B, this::visibleEventRefs)).containsExactly(SHARED_REF);
	}

	@Test
	void givenNoOperatorContext_whenReadingTheCatalog_thenItSeesNothingRatherThanEverything() {
		assertThat(visibleEventTitles()).isEmpty();
		assertThat(count("SELECT count(*) FROM organizer")).isZero();
		assertThat(count("SELECT count(*) FROM event")).isZero();
		assertThat(count("SELECT count(*) FROM ticket_type")).isZero();
	}

	// --- Sammensatte fremmednoegler ----------------------------------------

	/**
	 * Operatør B har en fuldt gyldig kontekst og skriver sin EGEN
	 * {@code operator_id} gennem {@code current_operator_id()} — RLS' WITH CHECK
	 * er derfor tilfreds. Det er den sammensatte fremmednøgle, og kun den, der
	 * stopper rækken.
	 */
	@Test
	void givenAnotherTenantsOrganizerId_whenPointingAnEventAtIt_thenTheCompositeForeignKeyRejectsIt() {
		assertThatThrownBy(() -> OperatorContext.runAs(B, () -> jdbc.sql("""
				INSERT INTO event (operator_id, organizer_id, ref, title, starts_at, sales_status, currency)
				VALUES (current_operator_id(), :organizerId, 'stolen-2027', 'Stolen',
				        TIMESTAMPTZ '2027-06-30 16:00:00+00', 'UNKNOWN', 'DKK')
				""").param("organizerId", A_ORGANIZER).update()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause().hasMessageContaining("event_organizer_fk");

		assertThat(OperatorContext.callAs(B, this::visibleEventRefs)).containsExactly(SHARED_REF);
	}

	@Test
	void givenAnotherTenantsEventId_whenPointingATicketTypeAtIt_thenTheCompositeForeignKeyRejectsIt() {
		assertThatThrownBy(() -> OperatorContext.runAs(B, () -> jdbc.sql("""
				INSERT INTO ticket_type (operator_id, event_id, ref, name, face_value_minor, currency)
				VALUES (current_operator_id(), :eventId, 'stolen', 'Stolen', 1000, 'DKK')
				""").param("eventId", A_EVENT).update()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause().hasMessageContaining("ticket_type_event_fk");
	}

	/**
	 * Samme fremmednøgle, tredje kolonne: valutaen. Et beløb i EUR ved siden af
	 * et DKK-event ville være en tilstand, domænet forbyder — og den ville ikke
	 * kunne ses i en enkelt række, kun i sammenligningen mellem to.
	 */
	@Test
	void givenAnEventInDkk_whenInsertingATicketTypeInEur_thenTheCompositeForeignKeyRejectsIt() {
		assertThatThrownBy(() -> OperatorContext.runAs(A, () -> jdbc.sql("""
				INSERT INTO ticket_type (operator_id, event_id, ref, name, face_value_minor, currency)
				VALUES (current_operator_id(), :eventId, 'euro', 'Euro', 1000, 'EUR')
				""").param("eventId", A_EVENT).update()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause().hasMessageContaining("ticket_type_event_fk");
	}

	/**
	 * Den anden retning af den samme regel. {@code ON UPDATE} står bevidst på
	 * NO ACTION: en valutaændring på et levende event er en PENGEHÆNDELSE, ikke
	 * en katalogrettelse, og den skal stoppe et natligt push og eskalere til et
	 * menneske. {@code ON UPDATE CASCADE} ville omskrive pengenær data uden
	 * versionsbump og uden en linje i audit-loggen.
	 */
	@Test
	void givenTicketTypesOnAnEvent_whenChangingTheEventCurrency_thenTheForeignKeyRejectsIt() {
		assertThatThrownBy(() -> OperatorContext.runAs(A, () -> jdbc
				.sql("UPDATE event SET currency = 'EUR' WHERE id = :id").param("id", A_EVENT).update()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause().hasMessageContaining("ticket_type_event_fk");

		assertThat(OperatorContext.callAs(A, () -> jdbc.sql("SELECT currency FROM event WHERE id = :id")
				.param("id", A_EVENT).query(String.class).single()))
				.isEqualTo("DKK");
	}

	// -----------------------------------------------------------------------

	private List<String> visibleEventTitles() {
		return jdbc.sql("SELECT title FROM event ORDER BY title").query(String.class).list();
	}

	private List<String> visibleEventRefs() {
		return jdbc.sql("SELECT ref FROM event ORDER BY ref").query(String.class).list();
	}

	private List<String> titleOf(UUID eventId) {
		return jdbc.sql("SELECT title FROM event WHERE id = :id").param("id", eventId).query(String.class).list();
	}

	/**
	 * Hver sætning er en hel konstant fra kaldstedet. Et tabelnavn kan ikke
	 * parametriseres, og det er præcis dér, risikoen samler sig — derfor
	 * sammensættes strengen aldrig, heller ikke i en test.
	 */
	private Integer count(String query) {
		return jdbc.sql(query).query(Integer.class).single();
	}

	/** Læser uden om RLS — den eneste vej til at se, hvad der FAKTISK står i tabellen. */
	private List<String> titlesAsOwner(String query, String ref) throws SQLException {
		List<String> titles = new ArrayList<>();
		try (Connection owner = ownerConnection(); var sql = owner.prepareStatement(query)) {
			sql.setString(1, ref);
			try (ResultSet rs = sql.executeQuery()) {
				while (rs.next()) {
					titles.add(rs.getString(1));
				}
			}
		}
		return titles;
	}

	private Connection ownerConnection() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

}

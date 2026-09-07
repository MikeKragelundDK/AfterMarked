package dk.aftermarked.catalog;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dk.aftermarked.TestcontainersConfiguration;
import dk.aftermarked.catalog.internal.CatalogIngestService;
import dk.aftermarked.catalog.internal.CustomFieldColumns;
import dk.aftermarked.catalog.internal.EventJdbcStore;
import dk.aftermarked.catalog.internal.OrganizerJdbcStore;
import dk.aftermarked.catalog.internal.TicketTypeJdbcStore;
import dk.aftermarked.money.Money;
import dk.aftermarked.tenancy.OperatorContext;
import dk.aftermarked.tenancy.OperatorId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** Lazy enrichment respekterer både en eksisterende event-arrangør og operatørens default. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LazyEnrichmentDefaultOrganizerTest {

	static final OperatorId A = new OperatorId(UUID.fromString("00000000-0000-4000-8000-00000000ed01"));

	static final OperatorId B = new OperatorId(UUID.fromString("00000000-0000-4000-8000-00000000ed02"));

	static final UUID ORGANIZER = UUID.fromString("00000000-0000-4000-8000-00000000ed11");

	static final OrganizerRef REAL_REF = new OrganizerRef("acme");

	static final EventRef EVENT_REF = new EventRef("summer-2027");

	static final TicketTypeRef TICKET_REF = new TicketTypeRef("standard");

	static final Instant STARTS_AT = Instant.parse("2027-06-30T16:00:00Z");

	static final Money FACE_VALUE = Money.of(33000, "DKK");

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	CatalogIngestService ingest;

	@Autowired
	OrganizerJdbcStore organizers;

	@Autowired
	EventJdbcStore events;

	@Autowired
	TicketTypeJdbcStore ticketTypes;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void seedTwoOperatorsWithoutCatalogs() throws SQLException {
		try (Connection owner = DriverManager.getConnection(
				postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
			try (PreparedStatement delete = owner.prepareStatement("DELETE FROM operator WHERE id IN (?, ?)")) {
				delete.setObject(1, A.value());
				delete.setObject(2, B.value());
				delete.executeUpdate();
			}
			try (PreparedStatement insert = owner.prepareStatement("""
					INSERT INTO operator (id, slug, name, hostname) VALUES
					  (?, 'default-a', 'Default A', 'a.default-enrich.test'),
					  (?, 'default-b', 'Default B', 'b.default-enrich.test')
					""")) {
				insert.setObject(1, A.value());
				insert.setObject(2, B.value());
				insert.executeUpdate();
			}
		}
	}

	@Test
	void givenAnExistingEventUnderARealDefaultOrganizer_whenEnriching_thenItsRowsRemainUnchanged() {
		seedOrganizer(A, true);
		Event before = seedExistingEventAndTicketType();

		Event enriched = OperatorContext.callAs(A, () -> ingest.enrich(verifiedTicket()));

		assertThat(enriched).isEqualTo(before);
		assertThat(organizerRefs(A)).containsExactly(REAL_REF.value());
		assertThat(OperatorContext.callAs(A, organizers::findDefaultId)).contains(ORGANIZER);
		assertThat(auditRows(A)).isZero();
	}

	@Test
	void givenAnExistingEventWithoutADefaultOrganizer_whenEnriching_thenNoUnusedOrganizerIsCreated() {
		seedOrganizer(A, false);
		Event before = seedExistingEventAndTicketType();

		Event enriched = OperatorContext.callAs(A, () -> ingest.enrich(verifiedTicket()));

		assertThat(enriched).isEqualTo(before);
		assertThat(organizerRefs(A)).containsExactly(REAL_REF.value());
		assertThat(OperatorContext.callAs(A, organizers::findDefaultId)).isEmpty();
		assertThat(auditRows(A)).isZero();
	}

	@Test
	void givenARealDefaultOrganizer_whenEnrichingANewEvent_thenTheExistingDefaultIsReused() {
		seedOrganizer(A, true);

		Event created = OperatorContext.callAs(A, () -> ingest.enrich(verifiedTicket()));
		Event repeated = OperatorContext.callAs(A, () -> ingest.enrich(verifiedTicket()));

		assertThat(created.organizerId()).isEqualTo(ORGANIZER);
		assertThat(repeated).isEqualTo(created);
		assertThat(organizerRefs(A)).containsExactly(REAL_REF.value());
		assertThat(OperatorContext.callAs(A, () -> ticketTypes.findByRef(created.id(), TICKET_REF)))
				.isPresent().get().extracting(TicketType::faceValue).isEqualTo(FACE_VALUE);
		assertThat(auditRows(A)).isEqualTo(2);
	}

	@Test
	void givenAnotherOperatorsDefaultOrganizer_whenEnrichingANewEvent_thenItCannotBecomeTheParent() {
		seedOrganizer(B, true);

		Event created = OperatorContext.callAs(A, () -> ingest.enrich(verifiedTicket()));

		assertThat(created.organizerId()).isNotEqualTo(ORGANIZER);
		assertThat(organizerRefs(A)).containsExactly(OrganizerRef.RESERVED_DEFAULT);
		assertThat(organizerRefs(B)).containsExactly(REAL_REF.value());
		assertThat(OperatorContext.callAs(A, organizers::findDefaultId)).contains(created.organizerId());
		assertThat(OperatorContext.callAs(B, organizers::findDefaultId)).contains(ORGANIZER);
		assertThat(auditRows(A)).as("the synthesized organizer, the event and the ticket type").isEqualTo(3);
		assertThat(auditRows(B)).isZero();
	}

	private void seedOrganizer(OperatorId operator, boolean isDefault) {
		OperatorContext.runAs(operator, () -> organizers.insert(
				new Organizer(ORGANIZER, REAL_REF, "Acme Live", isDefault, 1, STARTS_AT, STARTS_AT),
				CustomFieldColumns.empty(ORGANIZER)));
	}

	/** Seedningen går direkte gennem storen, så audit kun måler enrichment-kaldet. */
	private Event seedExistingEventAndTicketType() {
		return OperatorContext.callAs(A, () -> {
			UUID eventId = events.insertSkeleton(ORGANIZER, EVENT_REF, STARTS_AT, FACE_VALUE.currency())
					.orElseThrow().id();
			ticketTypes.insertSkeleton(eventId, TICKET_REF, "Standard", FACE_VALUE);
			return events.findByRef(EVENT_REF).orElseThrow();
		});
	}

	private List<String> organizerRefs(OperatorId operator) {
		return OperatorContext.callAs(operator,
				() -> jdbc.sql("SELECT ref FROM organizer ORDER BY ref").query(String.class).list());
	}

	private long auditRows(OperatorId operator) {
		return OperatorContext.callAs(operator,
				() -> jdbc.sql("SELECT count(*) FROM audit_log").query(Long.class).single());
	}

	private static VerifiedTicket verifiedTicket() {
		return new VerifiedTicket(EVENT_REF, TICKET_REF, "Standard", FACE_VALUE, STARTS_AT);
	}

}

package dk.aftermarked.invariant.migration;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import dk.aftermarked.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.EventPublication;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INVARIANT — the outbox and its deliberate exemption from RLS (PLAN §2.4, §2.7).
 * This test is spec.
 *
 * <p>{@code event_publication} is owned by Spring Modulith and deliberately has no
 * {@code operator_id}: the registry republishes incomplete publications at startup,
 * without any operator context. The exemption costs two things, and both have to be
 * proven here:
 *
 * <ol>
 *   <li>The runtime role must be able to read and write the table <em>without</em> a
 *       context — otherwise the outbox stalls the first time a poller runs.</li>
 *   <li>The compensation: every event through the registry carries an operator id, and
 *       the listener binds the context itself. There are no listeners yet, so that test
 *       is empty today — but it is deliberately written now, so it is in place the day
 *       the first listener arrives.</li>
 * </ol>
 *
 * <p>There is no Hibernate and therefore no {@code ddl-auto=validate} to catch a wrong
 * column type at startup (PLAN §3, decided 29-08-2026): the registry now runs on
 * {@code spring-modulith-starter-jdbc}. Column types, CHECK constraints and privileges
 * are all held by this test instead.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EventPublicationSchemaInvariantTest {

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ApplicationContext context;

	/**
	 * Full CRUD without an operator context. Proves two things at once: that
	 * {@code ALTER DEFAULT PRIVILEGES} from V1 actually reached a table created in V2,
	 * and that no RLS policy stands in the way of the registry.
	 */
	@Test
	void givenNoOperatorContext_whenTheRuntimeRoleUsesTheOutboxTable_thenItHasFullCrud() {
		UUID id = UUID.randomUUID();
		try {
			assertThat(insert(id, EventPublication.Status.PUBLISHED)).isOne();

			assertThat(jdbc.sql("SELECT listener_id FROM event_publication WHERE id = :id")
					.param("id", id).query(String.class).single()).isEqualTo("invariant-test");

			assertThat(jdbc.sql("""
					UPDATE event_publication
					   SET status = 'COMPLETED', completion_date = now(),
					       completion_attempts = completion_attempts + 1
					 WHERE id = :id
					""").param("id", id).update()).isOne();

			assertThat(jdbc.sql("SELECT completion_attempts FROM event_publication WHERE id = :id")
					.param("id", id).query(Integer.class).single()).isOne();
		}
		finally {
			jdbc.sql("DELETE FROM event_publication WHERE id = :id").param("id", id).update();
		}
		assertThat(jdbc.sql("SELECT count(*) FROM event_publication WHERE id = :id")
				.param("id", id).query(Integer.class).single()).isZero();
	}

	/**
	 * The CHECK constraint on {@code status} must know ALL the values in Modulith's
	 * {@code EventPublication.Status}. Hibernate does not validate CHECK constraints, so
	 * a Modulith upgrade that adds a status would first show up as a failed INSERT in
	 * the middle of a saga — that is, a lost message in the outbox. The test reads the
	 * enum off the class path and tries every single value.
	 */
	@Test
	void givenEveryStatusValueFromModulith_whenWritingItToTheOutbox_thenTheCheckConstraintAcceptsIt() {
		assertThat(EventPublication.Status.values()).isNotEmpty();
		for (EventPublication.Status status : EventPublication.Status.values()) {
			UUID id = UUID.randomUUID();
			try {
				assertThat(insert(id, status)).as("status [%s] must be writable", status).isOne();
			}
			finally {
				jdbc.sql("DELETE FROM event_publication WHERE id = :id").param("id", id).update();
			}
		}
	}

	/** And the other way round: a status outside the enum must not be writable. */
	@Test
	void givenAStatusOutsideTheEnum_whenWritingItToTheOutbox_thenTheCheckConstraintRejectsIt() {
		UUID id = UUID.randomUUID();
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO event_publication
				  (id, listener_id, event_type, serialized_event, publication_date, status)
				VALUES (:id, 'invariant-test', 'dk.aftermarked.Test', '{}', now(), 'FANTASY')
				""").param("id", id).update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	/**
	 * The exemptions from the RLS rule are two and only two: {@code event_publication},
	 * which Modulith owns, and {@code flyway_schema_history}, which Flyway owns. The
	 * list stands here because extending it must cost a deliberate edit — a new table
	 * without RLS must not be able to slide in by accident.
	 */
	@Test
	void givenTheMigratedSchema_whenListingTablesWithoutRls_thenExactlyTwoAreExempt() {
		List<String> withoutRls = jdbc.sql("""
				SELECT c.relname
				FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
				WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND NOT c.relrowsecurity
				ORDER BY c.relname
				""").query(String.class).list();

		assertThat(withoutRls).containsExactly("event_publication", "flyway_schema_history");

		assertThat(jdbc.sql("""
				SELECT count(*) FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'event_publication' AND column_name = 'operator_id'
				""").query(Integer.class).single())
				.as("the exemption only holds as long as the table does not itself carry a tenant key")
				.isZero();
	}

	/**
	 * The indexes must be able to answer the registry's two frequent questions — "what
	 * is incomplete" ({@code completion_date IS NULL}) and "what has status X" — without
	 * a full scan.
	 */
	@Test
	void givenTheOutboxTable_whenInspectingItsIndexes_thenBothFrequentLookupsAreIndexed() {
		List<String> indexed = jdbc.sql("""
				SELECT a.attname
				FROM pg_index i
				JOIN pg_class c ON c.oid = i.indrelid
				JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = i.indkey[0]
				WHERE c.relname = 'event_publication' AND NOT i.indisprimary
				ORDER BY a.attname
				""").query(String.class).list();

		assertThat(indexed).contains("completion_date", "status");
	}

	/**
	 * The compensation for the missing {@code operator_id}: when an event goes through
	 * the registry, the registry does not know the tenant — the event does. So every
	 * {@code @TransactionalEventListener} (which Modulith's
	 * {@code @ApplicationModuleListener} builds on) must receive an event that itself
	 * carries an operator id, so the listener can bind the context with
	 * {@code OperatorContext.runAs} before it touches tenant data.
	 *
	 * <p>There are no listeners in the skeleton yet, so the test is empty today. It is
	 * written now, because otherwise the rule would only be enforced in the commit
	 * where it has already been broken.
	 */
	@Test
	void givenEveryTransactionalEventListener_whenInspectingItsEventType_thenTheEventItselfCarriesAnOperatorId() {
		List<String> missing = new ArrayList<>();

		for (String name : context.getBeanDefinitionNames()) {
			Class<?> type = context.getType(name);
			if (type == null || !type.getName().startsWith("dk.aftermarked.")) {
				continue;
			}
			for (Method method : ReflectionUtils.getUniqueDeclaredMethods(ClassUtils.getUserClass(type))) {
				if (!AnnotatedElementUtils.hasAnnotation(method, TransactionalEventListener.class)
						|| method.getParameterCount() == 0) {
					continue;
				}
				Class<?> eventType = method.getParameterTypes()[0];
				if (!carriesOperatorId(eventType)) {
					missing.add(type.getSimpleName() + "." + method.getName() + "(" + eventType.getSimpleName() + ")");
				}
			}
		}

		assertThat(missing)
				.as("the outbox does not know the tenant — the event must carry the operator id, so the listener can bind the context itself")
				.isEmpty();
	}

	private static boolean carriesOperatorId(Class<?> eventType) {
		return Arrays.stream(eventType.getMethods())
				.anyMatch(method -> method.getParameterCount() == 0
						&& (method.getName().equals("operatorId") || method.getName().equals("operator")));
	}

	private int insert(UUID id, EventPublication.Status status) {
		return jdbc.sql("""
				INSERT INTO event_publication
				  (id, listener_id, event_type, serialized_event, publication_date, status, completion_attempts)
				VALUES (:id, 'invariant-test', 'dk.aftermarked.Test', '{}', :now, :status, 0)
				""")
				.param("id", id)
				.param("now", Instant.now().atOffset(java.time.ZoneOffset.UTC))
				.param("status", status.name())
				.update();
	}

}

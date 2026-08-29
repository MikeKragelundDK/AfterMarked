package dk.aftermarked.tenancy;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import dk.aftermarked.TestcontainersConfiguration;
import dk.aftermarked.tenancy.internal.TenantAwareDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.ForwardedHeaderFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the preconditions that tenant isolation rests on, and that are not visible
 * inside the tenancy package itself. If one of them breaks, isolation stops working
 * without a single line in {@code tenancy} having changed — and that is exactly the
 * kind of thing that otherwise surfaces first in production.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TenancyWiringTest {

	@Autowired
	ApplicationContext context;

	@Autowired
	DataSource dataSource;

	@Autowired
	Environment environment;

	/**
	 * ONE data source, and it is tenant aware. The wrapping happens on the bean name
	 * "dataSource" (see {@code TenancyConfigurationTest}), so a second data source —
	 * for instance the read-only replica PLAN §2.7 hints at — would NOT be wrapped. If
	 * this one goes red, that is the day: wrap the new data source, or give it a
	 * deliberate justification on a par with Flyway's.
	 */
	@Test
	void givenTheApplicationContext_whenLookingForDataSources_thenThereIsExactlyOneAndItIsTenantAware() {
		assertThat(context.getBeanNamesForType(DataSource.class))
				.as("more than one DataSource demands a decision about the wrapping")
				.containsExactly("dataSource");
		assertThat(context.getBean(DataSource.class)).isInstanceOf(TenantAwareDataSource.class);
	}

	/**
	 * Erstatter den tidligere EntityManagerFactory-kontrol, som forsvandt med JPA
	 * (PLAN §3, 29-08-2026). Hensigten er den samme og nu bredere: der findes ingen
	 * ORM med sin egen vej til databasen uden om wrapperen — fordi der ikke findes
	 * en ORM overhovedet. Byggets øvrige værn mod at genindføre en er
	 * {@code PersistenceStyleInvariantTest}.
	 */
	@Test
	void givenTheApplicationContext_whenLookingForAnOrm_thenNoneIsPresentToBypassTheWrapper() {
		List<String> ormBeans = Arrays.stream(context.getBeanDefinitionNames())
				.filter(name -> {
					Class<?> type = context.getType(name);
					return type != null && (type.getName().contains("EntityManagerFactory")
							|| type.getName().contains("JpaTransactionManager")
							|| type.getName().startsWith("org.hibernate"));
				})
				.toList();

		assertThat(ormBeans).as("an ORM would have its own route to the database around the wrapper").isEmpty();
	}

	/**
	 * Modulith's outbox skriver til databasen og skal gøre det gennem den samme
	 * wrapper som alt andet. JDBC-varianten tager en {@link DataSource} ind, så
	 * kontrollen er, at der ikke findes en anden at tage — kombineret med testen
	 * ovenfor er det bevist, at outboxen ikke har sin egen forbindelse.
	 */
	@Test
	void givenTheEventPublicationRegistry_whenResolvingItsDataSource_thenItIsTheTenantAwareOne() {
		assertThat(context.getBeanProvider(DataSource.class).stream())
				.singleElement()
				.isInstanceOf(TenantAwareDataSource.class);
	}

	/**
	 * {@code getConnection(user, pass)} is the classic back door around a pool. Hikari
	 * does not support it, so there is no unbound connection to fetch that way — and
	 * the wrapper lets the error through instead of swallowing it.
	 */
	@Test
	void givenTheDataSource_whenAskingForAConnectionWithCredentials_thenThereIsNoWayAroundThePool() {
		assertThatThrownBy(() -> dataSource.getConnection("someone", "secret")).isInstanceOf(SQLException.class);
	}

	/**
	 * Flyway is the deliberate exception: it runs as the migration user on its own
	 * connection and must precisely NOT go through the tenant-aware data source —
	 * otherwise it could not create tables and policies.
	 */
	@Test
	void givenFlyway_whenReadingItsDataSource_thenItDeliberatelyBypassesTheTenantAwareOne() {
		DataSource flywayDataSource = context.getBean(Flyway.class).getConfiguration().getDataSource();
		assertThat(flywayDataSource).isNotInstanceOf(TenantAwareDataSource.class).isNotSameAs(dataSource);
	}

	/** The application connects as the runtime role — never as the owner. */
	@Test
	void givenTheResolvedProperties_whenReadingTheDatabaseUsers_thenTheAppConnectsAsRuntimeAndNotAsTheOwner() {
		assertThat(environment.getProperty("spring.datasource.username"))
				.isEqualTo(TestcontainersConfiguration.RUNTIME_USER);
		assertThat(environment.getProperty("spring.flyway.user"))
				.isNotEqualTo(TestcontainersConfiguration.RUNTIME_USER);
	}

	/**
	 * WATCHDOG: if {@code server.forward-headers-strategy} is turned on,
	 * {@code request.getServerName()} starts returning {@code X-Forwarded-Host} — and
	 * then the operator is suddenly resolved from a header instead of from the host
	 * name, which PLAN §2.7 rule 2 forbids.
	 *
	 * <p>It may be the right choice behind a load balancer that overwrites the header
	 * itself, but it is a decision, not a configuration detail. If this one goes red:
	 * decide whether the proxy is trusted, make sure it sets the header itself, and
	 * then update the test together with a note in PLAN.
	 */
	@Test
	void givenTheApplicationContext_whenLookingForForwardedHeaderHandling_thenNothingTakesOverTheHostName() {
		assertThat(environment.getProperty("server.forward-headers-strategy", "none"))
				.isEqualToIgnoringCase("none");

		boolean registered = context.getBeansOfType(FilterRegistrationBean.class).values().stream()
				.anyMatch(registration -> registration.getFilter() instanceof ForwardedHeaderFilter);
		assertThat(registered).as("a ForwardedHeaderFilter would move the source of the operator context").isFalse();
	}

	/**
	 * The operator filter sits before Spring Security, and it does not sit on top of
	 * Boot's own filters at {@code HIGHEST_PRECEDENCE}, where the order would be
	 * undefined.
	 */
	@Test
	void givenTheRegisteredFilters_whenComparingTheirOrder_thenTheOperatorFilterIsRegisteredBeforeSpringSecurity() {
		FilterRegistrationBean<?> operator = (FilterRegistrationBean<?>) context.getBean("operatorResolutionFilter");
		assertThat(operator.getOrder()).isLessThan(SecurityFilterProperties.DEFAULT_FILTER_ORDER);
		assertThat(operator.getOrder()).isGreaterThan(Integer.MIN_VALUE);
	}

}

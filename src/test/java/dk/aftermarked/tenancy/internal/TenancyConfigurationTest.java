package dk.aftermarked.tenancy.internal;

import javax.sql.DataSource;

import dk.aftermarked.tenancy.OperatorRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The wiring around tenant isolation: the wrapping of the data source and the place of
 * the filter in the chain. No Spring context — the bean methods are called directly, so
 * the test is fast and says something about the rules, not about autoconfiguration.
 */
class TenancyConfigurationTest {

	private final BeanPostProcessor bpp = TenancyConfiguration.tenantAwareDataSourcePostProcessor();

	@Test
	void givenARawDataSource_whenPostProcessedUnderTheNameDataSource_thenItIsWrapped() {
		DataSource raw = mock(DataSource.class);
		assertThat(bpp.postProcessAfterInitialization(raw, "dataSource"))
				.isInstanceOf(TenantAwareDataSource.class);
	}

	@Test
	void givenAnAlreadyWrappedDataSource_whenPostProcessedAgain_thenItIsNotWrappedTwice() {
		TenantAwareDataSource already = new TenantAwareDataSource(mock(DataSource.class));
		assertThat(bpp.postProcessAfterInitialization(already, "dataSource")).isSameAs(already);
	}

	@Test
	void givenABeanThatIsNotADataSource_whenPostProcessed_thenItIsLeftUntouched() {
		Object other = new Object();
		assertThat(bpp.postProcessAfterInitialization(other, "dataSource")).isSameAs(other);
	}

	/**
	 * DOCUMENTED GAP: the wrapping happens on the bean NAME "dataSource". Another data
	 * source — for instance the read-only replica PLAN §2.7 hints at — is NOT wrapped
	 * and therefore never sets {@code app.operator_id}.
	 *
	 * <p>It fails closed (without the variable, RLS sees zero rows), but it does fail.
	 * {@code TenancyWiringTest} guards that only one data source exists, so the day the
	 * second one arrives, the build goes red and someone has to make a decision.
	 */
	@Test
	void givenADataSourceUnderAnotherBeanName_whenPostProcessed_thenItIsNOTWrapped() {
		DataSource replica = mock(DataSource.class);
		assertThat(bpp.postProcessAfterInitialization(replica, "readOnlyDataSource"))
				.as("name-based wrapping only hits the bean called 'dataSource'")
				.isSameAs(replica);
	}

	/** Operator resolution must sit before Spring Security. */
	@Test
	void givenTheConfiguredFilterOrder_whenComparedWithSpringSecurity_thenTheFilterRunsFirst() {
		assertThat(TenancyConfiguration.FILTER_ORDER).isLessThan(SecurityFilterProperties.DEFAULT_FILTER_ORDER);
	}

	/**
	 * …but not on {@code HIGHEST_PRECEDENCE} itself. Boot places both
	 * {@code OrderedCharacterEncodingFilter} and {@code ForwardedHeaderFilter}
	 * (at {@code server.forward-headers-strategy=framework}) precisely there, and a tie
	 * gives an undefined order. An undefined order for the filter that picks the tenant
	 * is an undefined tenant.
	 */
	@Test
	void givenTheConfiguredFilterOrder_whenComparedWithBootsOwnFilters_thenItDoesNotCollideAtHighestPrecedence() {
		assertThat(TenancyConfiguration.FILTER_ORDER).isGreaterThan(Ordered.HIGHEST_PRECEDENCE);
	}

	@Test
	void givenTheFilterRegistrationBean_whenInspectingIt_thenItUsesTheAgreedOrderAndFilter() {
		var registration = new TenancyConfiguration().operatorResolutionFilter(mock(OperatorRegistry.class));
		assertThat(registration.getOrder()).isEqualTo(TenancyConfiguration.FILTER_ORDER);
		assertThat(registration.getFilter()).isInstanceOf(OperatorResolutionFilter.class);
	}

}

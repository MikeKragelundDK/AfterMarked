package dk.aftermarked.tenancy.internal;

import javax.sql.DataSource;

import dk.aftermarked.tenancy.OperatorRegistry;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
class TenancyConfiguration {

	/**
	 * Pakker applikationens {@code dataSource}-bønne ind, så ALLE forbindelser —
	 * JdbcClient, Modulith-outboxen, actuator — går gennem {@link TenantAwareDataSource}.
	 * Statisk, så den er på plads før nogen datasource instantieres.
	 */
	@Bean
	static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) {
				if ("dataSource".equals(beanName) && bean instanceof DataSource dataSource
						&& !(bean instanceof TenantAwareDataSource)) {
					return new TenantAwareDataSource(dataSource);
				}
				return bean;
			}
		};
	}

	/**
	 * Først i kæden — før Spring Security (order {@code -100}). Uden operatør er
	 * der intet at autentificere imod.
	 *
	 * <p>Ordenen er {@code HIGHEST_PRECEDENCE + 1}, ikke {@code HIGHEST_PRECEDENCE}
	 * selv: Boot registrerer både {@code OrderedCharacterEncodingFilter} og —
	 * hvis {@code server.forward-headers-strategy=framework} — {@code ForwardedHeaderFilter}
	 * på præcis {@code HIGHEST_PRECEDENCE}. Ved uafgjort er rækkefølgen udefineret,
	 * og for et filter, der vælger tenant ud fra værtsnavnet, er "udefineret
	 * rækkefølge" det samme som "udefineret tenant". Ét trin ned gør den afgjort.
	 */
	static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

	@Bean
	FilterRegistrationBean<OperatorResolutionFilter> operatorResolutionFilter(OperatorRegistry registry) {
		var registration = new FilterRegistrationBean<>(new OperatorResolutionFilter(registry));
		registration.setOrder(FILTER_ORDER);
		registration.setName("operatorResolutionFilter");
		return registration;
	}

}

package dk.aftermarked;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Minimal adgangskontrol for skelettet.
 *
 * Aabner KUN forsiden, fejlsiden, statiske filer og /actuator/health.
 * Alt andet kraever login. /actuator/prometheus er bevidst IKKE aabent.
 *
 * JWT-handoff'et fra kundens platform (PLAN.md §2.2, OAuth2 resource server)
 * er ikke konfigureret her endnu — det er naeste stop-zone-opgave.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		return http
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/", "/error").permitAll()
						.requestMatchers("/css/**", "/js/**", "/images/**", "/webjars/**").permitAll()
						.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
						.anyRequest().authenticated())
				.httpBasic(Customizer.withDefaults())
				.build();
	}

}

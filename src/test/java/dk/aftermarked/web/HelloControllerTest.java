package dk.aftermarked.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Forsiden skal vaere aaben uden login, og Thymeleaf-skabelonen skal rendere.
 * Kraever ikke Docker — modsat integrationstesten mod rigtig Postgres.
 */
@WebMvcTest(HelloController.class)
@Import(dk.aftermarked.SecurityConfig.class)
class HelloControllerTest {

	@Autowired
	MockMvcTester mvc;

	@Test
	void forsidenErAabenOgRendererSiden() {
		assertThat(mvc.get().uri("/"))
				.hasStatusOk()
				.hasViewName("hello")
				.bodyText().contains("Hallo verden");
	}

	@Test
	void ukendtSideKraeverLogin() {
		assertThat(mvc.get().uri("/operatoer")).hasStatus(401);
	}

}

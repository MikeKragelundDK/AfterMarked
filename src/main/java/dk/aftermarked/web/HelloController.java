package dk.aftermarked.web;

import java.util.Locale;

import dk.aftermarked.localization.Translations;
import dk.aftermarked.tenancy.Operator;
import dk.aftermarked.tenancy.OperatorRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class HelloController {

	/** Entitetsnavnet i {@code translation}. Skal matche tabellens snake_case-regel. */
	static final String OPERATOR_ENTITY = "operator";

	private final OperatorRegistry operators;

	private final Translations translations;

	HelloController(OperatorRegistry operators, Translations translations) {
		this.operators = operators;
		this.translations = translations;
	}

	@GetMapping("/")
	String frontPage(Model model, Locale locale) {
		Operator operator = operators.current();
		model.addAttribute("operator", operator);
		// Kundens eget navn kan vaere oversat; findes der ingen oversaettelse til
		// sproget, staar grundteksten fra operator-tabellen ved magt.
		model.addAttribute("operatorName", translations.localize(
				OPERATOR_ENTITY, operator.id().value(), "name", locale, operator.name()));
		return "hello";
	}

}

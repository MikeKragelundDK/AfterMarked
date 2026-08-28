package dk.aftermarked.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class HelloController {

	@GetMapping("/")
	String forside(Model model) {
		model.addAttribute("modul", "The Ticket");
		return "hello";
	}

}

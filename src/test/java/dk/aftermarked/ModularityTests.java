package dk.aftermarked;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Haandhaever modulgraenserne fra PLAN.md §2.1.
 * Fejler denne, er koden forkert — ikke testen.
 */
class ModularityTests {

	static final ApplicationModules MODULES = ApplicationModules.of(TheTicketApplication.class);

	@Test
	void verificerModulstruktur() {
		MODULES.verify();
	}

}

package dk.aftermarked;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Enforces the module boundaries from PLAN.md §2.1.
 * If this test fails, the code is wrong — not the test.
 */
class ModularityTests {

	static final ApplicationModules MODULES = ApplicationModules.of(TheTicketApplication.class);

	@Test
	void givenTheApplication_whenVerifyingTheModuleStructure_thenAllModuleBoundariesHold() {
		MODULES.verify();
	}

}

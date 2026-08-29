package dk.aftermarked.invariant.persistence;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * INVARIANT — persistensstil (PLAN §3, besluttet 29-08-2026). Denne test er spec.
 *
 * <p>Beslutningen er: <strong>ingen ORM.</strong> Aggregater er immutable records,
 * læst og skrevet med håndskrevet SQL gennem {@code JdbcClient}. Begrundelsen er
 * fejlprofilen — N+1, lazy initialization og implicit flush-timing er usynlige i
 * test og synlige i produktion, og der findes hverken reviewer eller APM til at
 * fange dem.
 *
 * <p>Testen findes, fordi en beslutning, der kun står i et dokument, ikke
 * overlever kontakt med en kodeassistent, der har set en million JPA-eksempler.
 * JPA er langt det mest udbredte mønster i træningsdata, så det er dét, en agent
 * griber som default. Her fejler bygget i stedet.
 *
 * <p>Skal beslutningen omgøres, er det en bevidst ændring af PLAN §3 og
 * CLAUDE.md — ikke en tilføjet import.
 */
class PersistenceStyleInvariantTest {

	/**
	 * Kun produktionskode. Testkode må gerne referere hvad som helst — fx for at
	 * verificere Modulith's eget outbox-skema, som modulet selv ejer.
	 */
	static final JavaClasses PRODUCTION_CODE = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("dk.aftermarked");

	@Test
	void givenOurProductionCode_whenScanningDependencies_thenNoJakartaPersistenceTypesAreUsed() {
		ArchRule rule = noClasses()
				.should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..")
				.because("aggregates are records with hand-written SQL — there is no @Entity (PLAN §3)");

		rule.check(PRODUCTION_CODE);
	}

	@Test
	void givenOurProductionCode_whenScanningDependencies_thenHibernateIsNeverReferenced() {
		ArchRule rule = noClasses()
				.should().dependOnClassesThat().resideInAnyPackage("org.hibernate..")
				.because("Hibernate leaves the stack; the outbox moves to spring-modulith-starter-jdbc (PLAN §3)");

		rule.check(PRODUCTION_CODE);
	}

	/**
	 * Både Spring Data JPA og Spring Data JDBC er fravalgt. Den sidste er værd at
	 * lukke eksplicit: den deler annotationsnavne med JPA fra andre pakker med
	 * anden semantik, og er derfor en kendt kilde til plausibel-men-forkert kode.
	 */
	@Test
	void givenOurProductionCode_whenScanningDependencies_thenNoSpringDataMappingFrameworkIsUsed() {
		ArchRule rule = noClasses()
				.should().dependOnClassesThat().resideInAnyPackage(
						"org.springframework.data.jpa..",
						"org.springframework.data.relational..",
						"org.springframework.orm..")
				.because("persistence is JdbcClient with hand-written SQL, not a mapping framework (PLAN §3)");

		rule.check(PRODUCTION_CODE);
	}

	/**
	 * Vagthund mod den stille vej udenom: en agent, der ikke kan importere
	 * {@code jakarta.persistence}, kan stadig finde på at genindføre en ORM ved
	 * at lade en klasse hedde noget med Entity eller Repository. Reglen fanger
	 * ikke alt, men den fanger vanen.
	 */
	@Test
	void givenOurProductionCode_whenScanningClassNames_thenNoTypeIsNamedLikeAnOrmArtifact() {
		ArchRule rule = noClasses()
				.should().haveSimpleNameEndingWith("Entity")
				.orShould().haveSimpleNameEndingWith("JpaRepository")
				.because("naming must reflect that there is no ORM in the project (PLAN §3)");

		rule.check(PRODUCTION_CODE);
	}

}

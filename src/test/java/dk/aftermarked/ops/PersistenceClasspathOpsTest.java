package dk.aftermarked.ops;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * OPERATIONS — no ORM on the classpath (PLAN §3, decided 29-08-2026).
 *
 * <p>{@code PersistenceStyleInvariantTest} proves that our own code does not
 * <em>reference</em> JPA or Hibernate. That is the important half, but it is only half:
 * ArchUnit reads our classes, not the classpath. A starter that pulls Hibernate in
 * transitively would leave that rule green — while Boot would auto-configure an
 * {@code EntityManagerFactory}, {@code ddl-auto} would become a live setting again, and
 * the next agent that reaches for {@code @Entity} would find that it compiles.
 *
 * <p>This test closes that half: the types must not be there at all. The test classpath
 * is a superset of the runtime classpath, so absent here means absent in production.
 *
 * <p>It also reads {@code pom.xml}, because a dependency can be declared long before
 * anything imports it — and the declaration is where the decision is actually made.
 */
class PersistenceClasspathOpsTest {

	/** One marker type per ORM we have decided against. */
	static final List<String> ORM_TYPES = List.of(
			"jakarta.persistence.Entity",
			"jakarta.persistence.EntityManager",
			"org.hibernate.Session",
			"org.hibernate.SessionFactory",
			"org.springframework.data.jpa.repository.JpaRepository",
			"org.springframework.data.relational.core.mapping.Table",
			"org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean");

	/** Artifact ids that must never appear in the build — under any scope. */
	static final List<String> ORM_ARTIFACTS = List.of(
			"spring-boot-starter-data-jpa",
			"spring-boot-starter-data-jdbc",
			"spring-modulith-starter-jpa",
			"hibernate-core",
			"spring-data-jpa",
			"spring-orm");

	@Test
	void givenTheTestClasspath_whenLookingForOrmTypes_thenNoneOfThemCanBeLoaded() {
		List<String> present = ORM_TYPES.stream().filter(PersistenceClasspathOpsTest::isOnTheClasspath).toList();

		assertThat(present)
				.as("persistence is records + JdbcClient; an ORM on the classpath is auto-configured "
						+ "whether we import it or not (PLAN §3)")
				.isEmpty();
	}

	/**
	 * The guard against a green tautology: if {@code isOnTheClasspath} were ever broken
	 * — a swallowed error, a wrong class loader — the test above would pass without
	 * proving anything. A type we know is present must therefore be found.
	 */
	@Test
	void givenATypeWeKnowIsPresent_whenProbingForIt_thenTheProbeItselfIsProvenToWork() {
		assertThat(isOnTheClasspath("org.springframework.jdbc.core.simple.JdbcClient"))
				.as("JdbcClient IS the persistence layer — if the probe cannot find it, it cannot find anything")
				.isTrue();
		assertThatCode(() -> Class.forName("org.springframework.jdbc.core.simple.JdbcClient"))
				.doesNotThrowAnyException();
	}

	@Test
	void givenThePomFile_whenReadingItsDependencies_thenNoOrmArtifactIsDeclared() {
		String pom = ProjectFile.text("pom.xml");
		List<String> declared = ORM_ARTIFACTS.stream()
				.filter(artifact -> pom.contains("<artifactId>" + artifact + "</artifactId>"))
				.toList();

		assertThat(declared)
				.as("the outbox uses spring-modulith-starter-jdbc and persistence uses "
						+ "spring-boot-starter-jdbc — nothing else (PLAN §3)")
				.isEmpty();
	}

	/**
	 * The positive counterpart: the two starters the decision actually rests on must be
	 * declared. Without them the test above would also be green — in a project with no
	 * database at all.
	 */
	@Test
	void givenThePomFile_whenReadingItsDependencies_thenTheJdbcStartersAreDeclared() {
		String pom = ProjectFile.text("pom.xml");

		assertThat(pom).contains("<artifactId>spring-boot-starter-jdbc</artifactId>");
		assertThat(pom).contains("<artifactId>spring-modulith-starter-jdbc</artifactId>");
	}

	private static boolean isOnTheClasspath(String type) {
		try {
			Class.forName(type, false, PersistenceClasspathOpsTest.class.getClassLoader());
			return true;
		}
		catch (ClassNotFoundException | LinkageError ex) {
			return false;
		}
	}

}

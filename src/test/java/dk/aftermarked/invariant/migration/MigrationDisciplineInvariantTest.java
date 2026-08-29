package dk.aftermarked.invariant.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INVARIANT — migration discipline (CLAUDE.md: expand-migrate-contract and
 * N-1 compatibility; PLAN §2.7 rule 1). This test is spec.
 *
 * <p>Everything here is read straight from the SQL files and needs neither Docker nor
 * Spring. The point is that the rules must catch a faulty migration in the commit it
 * is written in — not first when it has been rolled out onto a database.
 *
 * <p>The two rules that cannot be checked on a finished schema are exactly the ones
 * standing here: that RLS came along in the <em>same</em> migration as the table, and
 * that no migration tears away something an N-1 replica is still using.
 */
class MigrationDisciplineInvariantTest {

	static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

	static final Path DEVSEED = Path.of("src/main/resources/db/devseed");

	static final Path RESOURCES = Path.of("src/main/resources");

	/** {@code CREATE TABLE <name> ( ... \n);} — the migrations are formatted that way, and it is enforced below. */
	static final Pattern CREATE_TABLE = Pattern.compile(
			"CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z_][a-z0-9_]*)\\s*\\((.*?)\\n\\);",
			Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

	/**
	 * Destructive schema changes. Expand-migrate-contract says the column or the table
	 * may only disappear in a LATER release than the code that stopped using it — and
	 * with two replicas in the air at a time (compose.yaml) the schema has to be
	 * carryable by both the old and the new version.
	 */
	static final Pattern DESTRUCTIVE = Pattern.compile(
			"\\bDROP\\s+(TABLE|COLUMN|SCHEMA|INDEX|VIEW|TYPE|SEQUENCE|CONSTRAINT|DATABASE)\\b"
					+ "|\\bRENAME\\b"
					+ "|\\bTRUNCATE\\b",
			Pattern.CASE_INSENSITIVE);

	@Test
	void givenTheMigrationFolder_whenReadingTheFileNames_thenEveryMigrationFollowsTheNamingConvention()
			throws IOException {
		assertThat(migrationFiles()).isNotEmpty().allSatisfy(file -> assertThat(file.getFileName().toString())
				.as("versioned migrations are named V<n>__snake_case.sql")
				.matches("^V\\d+__[a-z0-9_]+\\.sql$"));
	}

	/**
	 * Version numbers are unique and contiguous from 1. A gap means either a migration
	 * that was deleted after it may have run somewhere, or two branches that each
	 * picked their own number — both give a database where the Flyway history no longer
	 * matches the repository.
	 */
	@Test
	void givenTheMigrationFolder_whenReadingTheVersionNumbers_thenTheyAreUniqueAndContiguous() throws IOException {
		List<Integer> versions = migrationFiles().stream()
				.map(file -> Integer.valueOf(file.getFileName().toString().replaceFirst("^V(\\d+)__.*$", "$1")))
				.sorted()
				.toList();

		assertThat(versions).doesNotHaveDuplicates();
		assertThat(versions).containsExactlyElementsOf(
				java.util.stream.IntStream.rangeClosed(1, versions.size()).boxed().toList());
	}

	@Test
	void givenEveryMigration_whenScanningItsSql_thenNoneDropsOrRenamesAnything() throws IOException {
		List<String> violations = new ArrayList<>();
		for (Path file : migrationFiles()) {
			Matcher found = DESTRUCTIVE.matcher(withoutComments(Files.readString(file, StandardCharsets.UTF_8)));
			while (found.find()) {
				violations.add(file.getFileName() + ": " + found.group().replaceAll("\\s+", " "));
			}
		}
		assertThat(violations)
				.as("expand-migrate-contract: the schema must be N-1 compatible, so nothing may be removed "
						+ "in the same release as the code that stopped using it")
				.isEmpty();
	}

	/**
	 * PLAN §2.7, rule 1: every table carrying {@code operator_id} gets its RLS policy in
	 * the <em>same</em> Flyway migration as the table itself. The existing invariant
	 * test in {@code invariant.tenancy} checks the finished database; it would also be
	 * green if the policy arrived in a cleanup round afterwards. This test closes that
	 * gap.
	 *
	 * <p>{@code operator} itself is included, because it carries the tenant key as
	 * {@code id}.
	 */
	@Test
	void givenAMigrationCreatingATableWithOperatorId_whenReadingIt_thenRlsAndItsPolicyAreInTheSameMigration()
			throws IOException {
		List<String> violations = new ArrayList<>();
		int examinedTables = 0;

		for (Path file : migrationFiles()) {
			String sql = withoutComments(Files.readString(file, StandardCharsets.UTF_8));

			long createTableCount = Pattern.compile("CREATE\\s+TABLE", Pattern.CASE_INSENSITIVE)
					.matcher(sql).results().count();
			List<Matcher> matched = new ArrayList<>();
			Matcher table = CREATE_TABLE.matcher(sql);
			while (table.find()) {
				matched.add(table);
				String name = table.group(1);
				String body = table.group(2);
				boolean tenantTable = "operator".equals(name) || body.toLowerCase().contains("operator_id");
				if (!tenantTable) {
					continue;
				}
				examinedTables++;
				if (!Pattern.compile("ALTER\\s+TABLE\\s+" + name + "\\s+ENABLE\\s+ROW\\s+LEVEL\\s+SECURITY",
						Pattern.CASE_INSENSITIVE).matcher(sql).find()) {
					violations.add(file.getFileName() + ": " + name + " is missing ENABLE ROW LEVEL SECURITY");
				}
				if (!Pattern.compile("CREATE\\s+POLICY\\s+\\w+\\s+ON\\s+" + name + "\\b",
						Pattern.CASE_INSENSITIVE).matcher(sql).find()) {
					violations.add(file.getFileName() + ": " + name + " is missing CREATE POLICY");
				}
			}
			assertThat((long) matched.size())
					.as("%s: every CREATE TABLE must be parseable by the test — otherwise a table slips "
							+ "past the RLS requirement unseen", file.getFileName())
					.isEqualTo(createTableCount);
		}

		assertThat(examinedTables).as("the operator table must be covered as a minimum").isGreaterThanOrEqualTo(1);
		assertThat(violations).as("tables with operator_id without RLS in the same migration").isEmpty();
	}

	/** The dev seed must not be mistakable for a real migration. */
	@Test
	void givenTheMigrationFolder_whenLookingForTheDevSeed_thenItLivesOutsideTheMigrationFolder() throws IOException {
		try (Stream<Path> files = Files.list(MIGRATIONS)) {
			assertThat(files.map(file -> file.getFileName().toString()))
					.as("no repeatable migration in db/migration — R__ files always run and would take the seed into production")
					.noneMatch(name -> name.startsWith("R__"));
		}
		assertThat(DEVSEED.resolve("R__dev_operator.sql")).exists();
	}

	/**
	 * The dev seed runs ONLY under the {@code dev} profile. The source of that rule is
	 * {@code spring.flyway.locations}, and it may therefore only be mentioned in
	 * {@code application-dev.properties}. {@code FlywayExecutionInvariantTest} proves
	 * the same thing on the actually configured Flyway bean.
	 */
	@Test
	void givenTheProfileProperties_whenReadingTheFlywayLocations_thenOnlyTheDevProfilePointsAtTheDevSeed()
			throws IOException {
		assertThat(Files.readString(RESOURCES.resolve("application.properties"), StandardCharsets.UTF_8))
				.as("the default configuration must not touch devseed")
				.doesNotContain("devseed")
				.doesNotContain("spring.flyway.locations");
		assertThat(Files.readString(RESOURCES.resolve("application-prod.properties"), StandardCharsets.UTF_8))
				.as("the production profile must NEVER run the dev seed")
				.doesNotContain("devseed");
		assertThat(Files.readString(RESOURCES.resolve("application-dev.properties"), StandardCharsets.UTF_8))
				.as("the dev profile is the one place the seed is pulled in")
				.contains("spring.flyway.locations")
				.contains("classpath:db/devseed");
	}

	private static List<Path> migrationFiles() throws IOException {
		try (Stream<Path> files = Files.list(MIGRATIONS)) {
			return files.filter(Files::isRegularFile).sorted().toList();
		}
	}

	/** Strips {@code --} comments, so an explanatory word like "DROP" in a comment does not raise a false alarm. */
	private static String withoutComments(String sql) {
		return sql.lines().map(line -> line.replaceAll("--.*$", "")).reduce("", (a, b) -> a + "\n" + b);
	}

}

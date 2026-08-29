package dk.aftermarked.ops;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import dk.aftermarked.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPERATIONS — the schema belongs to Flyway, and to nothing else.
 *
 * <p>The rule it holds is written down in {@code application.properties} but was
 * enforced by nothing: <em>Modulith's outbox must never create its own tables.</em> The
 * property that switches that off ({@code spring.modulith.events.jdbc.schema-initialization.enabled})
 * fails silently if it is ever misspelled or if the key is renamed in a Modulith
 * upgrade — and the failure mode is the quiet one. A table that no migration created is
 * a table no migration can change either, and after the no-ORM decision (PLAN §3) there
 * is no {@code ddl-auto=validate} left to notice.
 *
 * <p>So the test compares the tables that actually exist against the tables the
 * migrations in the repository say should exist. It is deliberately an equality, not a
 * subset: a table appearing out of nowhere is exactly what has to fail.
 *
 * <p>It also closes the general case — any library, starter or auto-configuration that
 * takes DDL into its own hands is caught here, not in production.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaOwnershipOpsTest {

	static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

	/** Flyway's own bookkeeping table. The only table no migration creates. */
	static final String FLYWAY_HISTORY = "flyway_schema_history";

	static final Pattern CREATE_TABLE = Pattern.compile(
			"CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z_][a-z0-9_]*)", Pattern.CASE_INSENSITIVE);

	@Autowired
	JdbcClient jdbc;

	@Test
	void givenTheMigratedDatabase_whenListingItsTables_thenEveryOneOfThemWasCreatedByAMigration() {
		Set<String> declared = tablesDeclaredByMigrations();
		Set<String> existing = new TreeSet<>(jdbc
				.sql("SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename")
				.query(String.class).list());

		assertThat(declared)
				.as("the test must find the migrations — otherwise it proves nothing")
				.contains("operator", "event_publication", "translation");

		declared.add(FLYWAY_HISTORY);
		assertThat(existing)
				.as("a table that no migration created cannot be changed by one either — and after PLAN §3 "
						+ "there is no ddl-auto=validate left to notice it")
				.isEqualTo(declared);
	}

	private static Set<String> tablesDeclaredByMigrations() {
		Set<String> tables = new TreeSet<>();
		for (Path file : migrationFiles()) {
			Matcher table = CREATE_TABLE.matcher(read(file));
			while (table.find()) {
				tables.add(table.group(1).toLowerCase());
			}
		}
		return tables;
	}

	private static List<Path> migrationFiles() {
		try (Stream<Path> files = Files.list(MIGRATIONS)) {
			return files.filter(Files::isRegularFile).sorted().toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** The SQL without {@code --} comments, so a table name in a comment is not counted. */
	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8).lines()
					.map(line -> line.replaceAll("--.*$", ""))
					.reduce("", (a, b) -> a + "\n" + b);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}

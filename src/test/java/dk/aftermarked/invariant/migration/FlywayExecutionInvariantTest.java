package dk.aftermarked.invariant.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import dk.aftermarked.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INVARIANT — how the migrations RUN (CLAUDE.md: expand-migrate-contract,
 * N-1 compatibility; PLAN §2.7). This test is spec.
 *
 * <p>Three things that can only be proven on a real database: that a second run
 * changes nothing, that the dev seed is not included without the {@code dev} profile,
 * and that two replicas starting at the same time do not run the same migration twice.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlywayExecutionInvariantTest {

	@Autowired
	Flyway flyway;

	@Autowired
	PostgreSQLContainer postgres;

	/**
	 * The configuration production safety rests on. {@code clean} must be disabled (it
	 * empties the schema), {@code validateOnMigrate} must be enabled (it is the
	 * checksum check that detects a migration changed after it ran), and
	 * {@code outOfOrder} must be disabled — otherwise V4 can run before V3 on one
	 * replica and after it on the other.
	 */
	@Test
	void givenTheConfiguredFlywayBean_whenReadingItsSettings_thenTheyAreProductionSafe() {
		var configuration = flyway.getConfiguration();
		assertThat(configuration.isCleanDisabled()).as("clean must never be able to run").isTrue();
		assertThat(configuration.isValidateOnMigrate())
				.as("checksum validation is what catches a changed, already applied migration").isTrue();
		assertThat(configuration.isOutOfOrder()).as("the order must be the same on every replica").isFalse();
		assertThat(configuration.isBaselineOnMigrate())
				.as("an empty database must be migrated, not stamped as already migrated").isFalse();
	}

	/**
	 * The dev seed must NEVER run in production. The proof is taken here on the
	 * actually configured bean — without the {@code dev} profile active — and not just
	 * on the text in the properties file.
	 */
	@Test
	void givenTheDefaultConfiguration_whenReadingTheFlywayLocations_thenTheDevSeedIsNotAmongThem() {
		List<String> locations = Arrays.stream(flyway.getConfiguration().getLocations())
				.map(Object::toString).toList();

		assertThat(locations).containsExactly("classpath:db/migration");
		assertThat(locations).noneMatch(location -> location.contains("devseed"));
	}

	/**
	 * The history must contain exactly the versioned migrations and nothing else. Had
	 * the seed slipped in, there would be an {@code R__} row here. The test is
	 * independent of what other tests have written into {@code operator}.
	 */
	@Test
	void givenTheMigratedDatabase_whenReadingTheFlywayHistory_thenItHoldsOnlyTheVersionedMigrations()
			throws SQLException {
		record Row(String version, String script, boolean success) {
		}
		List<Row> history = new ArrayList<>();
		try (Connection owner = flyway.getConfiguration().getDataSource().getConnection();
				Statement sql = owner.createStatement();
				ResultSet rs = sql.executeQuery(
						"SELECT version, script, success FROM flyway_schema_history ORDER BY installed_rank")) {
			while (rs.next()) {
				history.add(new Row(rs.getString(1), rs.getString(2), rs.getBoolean(3)));
			}
		}

		assertThat(history).allSatisfy(row -> assertThat(row.success()).isTrue());
		assertThat(history).extracting(Row::version).containsExactlyElementsOf(versionsInRepository());
		assertThat(history).extracting(Row::script)
				.as("no repeatable migration — the dev seed has not run")
				.noneMatch(script -> script.startsWith("R__"));
	}

	/**
	 * A second run must change nothing, and {@code validate} must be clean. That is the
	 * mechanical half of "a migration is never changed after it has run": if someone
	 * edits an already applied file, the checksum breaks and Flyway refuses to start.
	 */
	@Test
	void givenAnAlreadyMigratedDatabase_whenMigratingASecondTime_thenNothingChangesAndValidationIsClean() {
		assertThat(flyway.migrate().migrationsExecuted).isZero();
		assertThat(flyway.validateWithResult().validationSuccessful)
				.as("the checksum for the already applied migrations must match").isTrue();
	}

	/**
	 * Two replicas start at the same time (compose.yaml always runs two). Flyway takes
	 * an advisory lock in Postgres, so only one of them migrates — but that is an
	 * assumption about a library, not something we wrote ourselves, so it has to be
	 * proven. Without the lock both would run V1, and the second would fail halfway
	 * through a partial migration.
	 *
	 * <p>Runs against a fresh database on the same container, so the shared test
	 * database is not touched. Note that the {@code CREATE ROLE} block in V1 gets its
	 * trial at the same time: the role {@code theticket_app} already exists (roles are
	 * global in the cluster), so the guard in the {@code DO} block has to skip creation
	 * instead of failing.
	 */
	@Test
	void givenTwoFlywayRunsStartingAtTheSameTime_whenBothMigrate_thenEachVersionIsAppliedExactlyOnce()
			throws Exception {
		String database = "migration_parallel_test";
		createDatabase(database);
		try {
			CyclicBarrier start = new CyclicBarrier(2);
			Callable<MigrateResult> run = () -> {
				start.await();
				return Flyway.configure()
						.dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
						.locations("classpath:db/migration")
						.load()
						.migrate();
			};

			List<MigrateResult> results = new ArrayList<>();
			ExecutorService pool = Executors.newFixedThreadPool(2);
			try {
				List<Future<MigrateResult>> futures = List.of(pool.submit(run), pool.submit(run));
				for (Future<MigrateResult> future : futures) {
					results.add(future.get());
				}
			}
			finally {
				pool.shutdownNow();
			}

			assertThat(results).allSatisfy(result -> assertThat(result.success).isTrue());
			assertThat(results.stream().mapToInt(result -> result.migrationsExecuted).sum())
					.as("every versioned migration in the repository — each exactly once, no matter who won the lock")
					.isEqualTo(versionsInRepository().size());

			try (Connection fresh = DriverManager.getConnection(jdbcUrl(database), postgres.getUsername(),
					postgres.getPassword());
					Statement sql = fresh.createStatement();
					ResultSet rs = sql.executeQuery(
							"SELECT version, count(*) FROM flyway_schema_history WHERE version IS NOT NULL "
									+ "GROUP BY version ORDER BY version")) {
				List<String> rows = new ArrayList<>();
				while (rs.next()) {
					rows.add(rs.getString(1) + "=" + rs.getInt(2));
				}
				assertThat(rows).as("no version may appear twice in the history")
						.containsExactlyElementsOf(versionsInRepository().stream().map(v -> v + "=1").toList());
			}
		}
		finally {
			dropDatabase(database);
		}
	}

	/**
	 * The dev seed has to work when it IS enabled — otherwise a bug in the seed is only
	 * discovered the day a new developer clones the project. Runs against a fresh
	 * database with exactly the locations {@code application-dev.properties} sets.
	 *
	 * <p>A second run must be clean: a repeatable migration is only run again when its
	 * checksum changes, and the upsert must therefore produce neither a duplicate nor a
	 * unique violation on {@code hostname}.
	 */
	@Test
	void givenTheDevSeedEnabled_whenMigratingAFreshDatabaseTwice_thenItCreatesOneLocalhostOperatorAndIsRerunnable()
			throws Exception {
		String database = "migration_devseed_test";
		createDatabase(database);
		try {
			Flyway withSeed = Flyway.configure()
					.dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
					.locations("classpath:db/migration", "classpath:db/devseed")
					.load();

			assertThat(withSeed.migrate().migrationsExecuted)
					.as("every versioned migration plus the repeatable seed")
					.isEqualTo(versionsInRepository().size() + 1);

			try (Connection fresh = DriverManager.getConnection(jdbcUrl(database), postgres.getUsername(),
					postgres.getPassword());
					Statement sql = fresh.createStatement();
					ResultSet rs = sql.executeQuery("SELECT slug, hostname FROM operator")) {
				assertThat(rs.next()).as("the seed must have created an operator").isTrue();
				assertThat(rs.getString("slug")).isEqualTo("dev");
				assertThat(rs.getString("hostname"))
						.as("the host name must be able to pass the CHECK constraint").isEqualTo("localhost");
				assertThat(rs.next()).as("exactly one operator — an upsert, not a second insert").isFalse();
			}

			assertThat(withSeed.migrate().migrationsExecuted)
					.as("an unchanged seed is not run again").isZero();
		}
		finally {
			dropDatabase(database);
		}
	}

	/**
	 * De versionsnumre, der faktisk ligger i {@code db/migration}, i rækkefølge.
	 *
	 * <p>Udledt frem for hardkodet med vilje. Et fast {@code "1", "2"} ville gøre
	 * enhver ny migration til en rød test — og dermed friste til at rette tallet
	 * uden at læse hvad testen egentlig påstår. Invarianten er ikke "der findes
	 * to migrationer"; den er "historikken indeholder præcis de migrationer, der
	 * ligger i repoet, hver af dem én gang". Det er dét, der står her nu.
	 *
	 * <p>At numrene er unikke og sammenhængende, bevises af
	 * {@code MigrationDisciplineInvariantTest} — derfor må den her nøjes med at
	 * læse dem.
	 */
	private static List<String> versionsInRepository() {
		try (java.util.stream.Stream<java.nio.file.Path> files =
				java.nio.file.Files.list(java.nio.file.Path.of("src/main/resources/db/migration"))) {
			List<String> versions = files
					.map(file -> file.getFileName().toString())
					.filter(name -> name.matches("^V\\d+__.*\\.sql$"))
					.map(name -> name.replaceFirst("^V(\\d+)__.*$", "$1"))
					.sorted(java.util.Comparator.comparingInt(Integer::parseInt))
					.toList();
			assertThat(versions).as("there must be at least one versioned migration in the repository").isNotEmpty();
			return versions;
		}
		catch (java.io.IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	}

	private String jdbcUrl(String database) {
		return "jdbc:postgresql://%s:%d/%s".formatted(postgres.getHost(), postgres.getFirstMappedPort(), database);
	}

	private void createDatabase(String name) throws SQLException {
		dropDatabase(name);
		try (Connection owner = ownerConnection(); Statement sql = owner.createStatement()) {
			sql.execute("CREATE DATABASE " + name);
		}
	}

	private void dropDatabase(String name) throws SQLException {
		try (Connection owner = ownerConnection(); Statement sql = owner.createStatement()) {
			sql.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
		}
	}

	private Connection ownerConnection() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

}

package dk.aftermarked.ops;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The same Postgres as {@link dk.aftermarked.TestcontainersConfiguration}, but
 * started statically and registered through {@code @DynamicPropertySource}.
 *
 * <p><strong>Why not simply import TestcontainersConfiguration?</strong>
 * It registers its properties through a {@code DynamicPropertyRegistrar} bean. That
 * bean is only called once singletons are instantiated — but with
 * {@code webEnvironment = RANDOM_PORT} Spring builds the real Tomcat in
 * {@code onRefresh()}, that is, <em>before</em> then. Starting Tomcat pulls in
 * {@code operatorResolutionFilter} → {@code OperatorRegistry} → {@code JdbcClient}
 * → Flyway, so the data source is created while the container URL is not yet
 * registered. The result is an attempt to connect to the hard-coded default
 * (localhost:5432), and the context fails.
 *
 * <p>{@code @DynamicPropertySource} is static and is evaluated before refresh, which
 * is why it works in both cases. The container is a singleton: started once per JVM
 * and cleaned up by the Testcontainers Ryuk container.
 *
 * <p>The configuration is deliberately identical to TestcontainersConfiguration —
 * same image, same init script, same two roles — so the operations tests measure the
 * same database as the rest of the test suite.
 */
final class OpsPostgres {

	static final String RUNTIME_USER = "theticket_runtime";

	static final String RUNTIME_PASSWORD = "runtime-dev-only";

	static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
			.withCopyFileToContainer(
					MountableFile.forHostPath("deploy/postgres/init/01-roles.sh", 0755),
					"/docker-entrypoint-initdb.d/01-roles.sh");

	static {
		CONTAINER.start();
	}

	private OpsPostgres() {
	}

	/** Registers the runtime role for the app and the migration user for Flyway — as in production. */
	static void register(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", CONTAINER::getJdbcUrl);
		registry.add("spring.datasource.username", () -> RUNTIME_USER);
		registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
		registry.add("spring.flyway.url", CONTAINER::getJdbcUrl);
		registry.add("spring.flyway.user", CONTAINER::getUsername);
		registry.add("spring.flyway.password", CONTAINER::getPassword);
	}

}

package dk.aftermarked;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * A real Postgres in Docker — mocked persistence never catches locking, RLS and
 * transaction bugs, and those are precisely the ones this system lives on.
 *
 * <p>Same role model as production: the container superuser is the migration user
 * (Flyway), while the application connects as {@code theticket_runtime}, which is
 * created by the very same init script compose.yaml uses. The script path is
 * relative to the project root — Maven runs from there.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	public static final String RUNTIME_USER = "theticket_runtime";
	public static final String RUNTIME_PASSWORD = "runtime-dev-only";

	@Bean
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
				.withCopyFileToContainer(
						MountableFile.forHostPath("deploy/postgres/init/01-roles.sh", 0755),
						"/docker-entrypoint-initdb.d/01-roles.sh");
	}

	@Bean
	DynamicPropertyRegistrar databaseProperties(PostgreSQLContainer postgres) {
		return registry -> {
			registry.add("spring.datasource.url", postgres::getJdbcUrl);
			registry.add("spring.datasource.username", () -> RUNTIME_USER);
			registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
			registry.add("spring.flyway.url", postgres::getJdbcUrl);
			registry.add("spring.flyway.user", postgres::getUsername);
			registry.add("spring.flyway.password", postgres::getPassword);
		};
	}

}

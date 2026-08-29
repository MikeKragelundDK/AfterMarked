package dk.aftermarked.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SIKKERHEDSREVIEW — pipelinen som angrebsflade.
 *
 * <p>{@code DeliveryPipelineOpsTest} beviser, at gatene kan fejle, og at
 * værktøjskæden er pinnet. Det her handler om det modsatte spørgsmål: hvad kan
 * pipelinen selv komme til at give fra sig?
 *
 * <p>Pipelinen kører fremmed kode — tre pinnede scanner-images og en håndfuld
 * actions — på en runner, der har en GitHub-token stående. {@code checkout}
 * efterlader som default den token i {@code .git/config}, og {@code .git} ligger
 * i den mappe, semgrep-jobbet monterer direkte ind i sin container. En token,
 * ingen af jobbene har brug for, er en token, der ikke skal være der.
 */
class DeliveryPipelineSecurityTest {

	private static final String PATH = ".github/workflows/ci.yml";

	private static final String TEXT = text(PATH);

	@SuppressWarnings("unchecked")
	private static final Map<String, Object> WORKFLOW = new Yaml().load(TEXT);

	@SuppressWarnings("unchecked")
	private static final Map<String, Object> JOBS = (Map<String, Object>) WORKFLOW.get("jobs");

	/**
	 * Ingen af de fire jobs skriver til repoet — {@code permissions} er
	 * {@code contents: read}. Så skal tokenen heller ikke ligge og vente i
	 * arbejdsmappen bagefter.
	 */
	@Test
	void givenEveryCheckoutStep_whenInspectingIt_thenNoneLeavesTheTokenBehindInTheWorkingCopy() {
		List<Map<String, Object>> checkouts = allSteps().stream()
				.filter(step -> String.valueOf(step.get("uses")).contains("actions/checkout"))
				.toList();

		assertThat(checkouts).as("every job starts by checking the repository out").hasSize(4);
		assertThat(checkouts).allSatisfy(step -> {
			Object with = step.get("with");
			assertThat(with)
					.as("a checkout without 'with:' uses the default, and the default persists the token")
					.isInstanceOf(Map.class);
			assertThat(((Map<?, ?>) with).get("persist-credentials"))
					.as("""
							checkout writes GITHUB_TOKEN into .git/config by default. That is the \
							same directory the Semgrep job mounts into a third party container.""")
					.isEqualTo(false);
		});
	}

	/**
	 * Semgrep-jobbet giver en fremmed container hele arbejdsmappen. Den skal
	 * læse koden — den skal ikke kunne ændre den, mens den er der.
	 */
	@Test
	void givenTheScannerContainers_whenTheyAreGivenTheWorkingCopy_thenTheyGetItReadOnly() {
		List<String> mounts = allSteps().stream()
				.map(step -> String.valueOf(step.getOrDefault("run", "")))
				.filter(command -> command.contains("docker run"))
				.filter(command -> command.contains("-v "))
				.toList();

		assertThat(mounts).as("the pipeline does hand the working copy to containers").isNotEmpty();
		assertThat(mounts).allSatisfy(command -> assertThat(command)
				.as("a scanner that can write to the checkout can also change what the next step builds: %s", command)
				.contains(":ro"));
	}

	/**
	 * {@code pull_request_target} kører workflowet fra basisbranchen MED
	 * secrets og med en skrive-token, mens koden er den, der reviewes. Det er
	 * den ene event-type, der gør en PR til fjernudførelse.
	 */
	@Test
	void givenTheWorkflowTriggers_whenInspectingThem_thenNoneRunsForeignCodeWithSecrets() {
		assertThat(WORKFLOW.get(true) != null ? WORKFLOW.get(true) : WORKFLOW.get("on"))
				.as("the workflow must declare its triggers")
				.isNotNull();
		assertThat(TEXT)
				.as("pull_request_target hands a fork's pull request the repository's own secrets")
				.doesNotContain("pull_request_target");
		assertThat(TEXT)
				.as("no job in this pipeline needs a secret; the day one does, it is a decision on its own")
				.doesNotContain("secrets.");
	}

	// -----------------------------------------------------------------------

	private static String text(String path) {
		Path file = Path.of(path);
		if (!Files.isRegularFile(file)) {
			throw new AssertionError("The pipeline file " + path + " does not exist");
		}
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> allSteps() {
		List<Map<String, Object>> all = new ArrayList<>();
		for (Object job : JOBS.values()) {
			Object steps = ((Map<String, Object>) job).get("steps");
			if (steps instanceof List<?> list) {
				all.addAll((List<Map<String, Object>>) list);
			}
		}
		return all;
	}

}

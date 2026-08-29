package dk.aftermarked.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPERATIONS — the delivery pipeline (the gates in CLAUDE.md, PLAN §1 "no version drift").
 *
 * <p>A gate that cannot fail is not a gate. The tests here pin down that every step
 * can actually turn the build red, that the toolchain is pinned, and that the order
 * inside the {@code build} job is the one the thresholds assume: unit and integration
 * tests → image → scanning → running stack → smoke test.
 */
class DeliveryPipelineOpsTest {

	private static final String PATH = ".github/workflows/ci.yml";

	private static final String TEXT = ProjectFile.text(PATH);

	private static final Map<String, Object> WORKFLOW = ProjectFile.yaml(PATH);

	private static final Map<String, Object> JOBS = ProjectFile.map(WORKFLOW, "jobs");

	private static final Pattern FULL_SHA = Pattern.compile("^[^@]+@[0-9a-f]{40}$");

	// -----------------------------------------------------------------------
	// The gate must be able to fail
	// -----------------------------------------------------------------------

	/**
	 * {@code || true} makes a step independent of whether the command succeeded. That
	 * is correct for cleanup and for collecting logs after a failure — and catastrophic
	 * everywhere else, because the build then goes green no matter what.
	 */
	@Test
	void givenEveryPipelineStep_whenAStepSwallowsItsExitCode_thenItIsOnlyCleanupOrFailureCollection() {
		for (Map.Entry<String, Object> job : JOBS.entrySet()) {
			for (Map<String, Object> step : steps(job.getValue())) {
				String command = String.valueOf(step.getOrDefault("run", ""));
				if (!command.contains("|| true")) {
					continue;
				}
				assertThat(String.valueOf(step.get("if")))
						.as("step '%s' in job '%s' swallows its failure without being cleanup or failure collection",
								step.get("name"), job.getKey())
						.matches("(failure\\(\\)|always\\(\\))");
			}
		}
	}

	@Test
	void givenTheWorkflowFile_whenSearchingForContinueOnError_thenNoStepIsMarkedWithIt() {
		assertThat(TEXT)
				.as("continue-on-error turns a step into decoration; a gate has to be able to block")
				.doesNotContain("continue-on-error");
	}

	@Test
	void givenTheSecurityScanners_whenInspectingTheirThresholds_thenTheyBlockInsteadOfOnlyReporting() {
		List<Map<String, Object>> trivy = allSteps().stream()
				.filter(t -> String.valueOf(t.get("uses")).contains("trivy-action"))
				.toList();

		assertThat(trivy).as("both dependency and image scanning must run").hasSize(2);
		assertThat(trivy).allSatisfy(t -> {
			Map<String, Object> with = ProjectFile.map(t, "with");
			assertThat(with.get("exit-code"))
					.as("without exit-code 1 Trivy is a report, not a gate")
					.isEqualTo("1");
			assertThat(String.valueOf(with.get("severity")))
					.as("the threshold from the CI header: HIGH + CRITICAL")
					.isEqualTo("HIGH,CRITICAL");
		});

		assertThat(TEXT)
				.as("--error turns any Semgrep finding into a non-zero exit")
				.contains("--error");
	}

	@Test
	void givenTheSecretsJob_whenInspectingItsCheckout_thenItFetchesTheWholeHistory() {
		Map<String, Object> checkout = steps(JOBS.get("secrets")).stream()
				.filter(t -> String.valueOf(t.get("uses")).contains("actions/checkout"))
				.findFirst().orElseThrow();

		assertThat(ProjectFile.map(checkout, "with").get("fetch-depth"))
				.as("with the default (depth 1) TruffleHog effectively scans nothing")
				.isEqualTo(0);
	}

	@Test
	void givenEveryJobInTheWorkflow_whenInspectingItsConfiguration_thenItHasATimeLimit() {
		assertThat(JOBS.keySet()).containsExactlyInAnyOrder("build", "sca", "sast", "secrets");
		for (Map.Entry<String, Object> job : JOBS.entrySet()) {
			assertThat(ProjectFile.map(job.getValue()).get("timeout-minutes"))
					.as("job '%s' without a timeout can hang for the runner's maximum of 6 hours", job.getKey())
					.isNotNull();
		}
	}

	// -----------------------------------------------------------------------
	// No version drift in the toolchain
	// -----------------------------------------------------------------------

	/**
	 * A tag can be repointed by whoever owns the action — that happened to
	 * trivy-action in March 2025. A full commit SHA cannot.
	 */
	@Test
	void givenEveryActionUsedInTheWorkflow_whenInspectingItsReference_thenItIsPinnedToAFullCommitSha() {
		List<String> uses = allSteps().stream()
				.map(t -> t.get("uses"))
				.filter(java.util.Objects::nonNull)
				.map(String::valueOf)
				.toList();

		assertThat(uses).as("the workflow does use actions").isNotEmpty();
		assertThat(uses).allSatisfy(u -> assertThat(FULL_SHA.matcher(u).matches())
				.as("'%s' is not pinned to a 40-character commit SHA", u)
				.isTrue());
	}

	@Test
	void givenTheWorkflowFile_whenLookingForFloatingVersions_thenNoToolIsPulledFromLatest() {
		assertThat(TEXT)
				.as("':latest' is version drift by definition — for scanner images too")
				.doesNotContain(":latest");
		assertThat(TEXT)
				.as("the TruffleHog action's 'version' defaults to latest and must be set explicitly")
				.contains("version: 3.97.1");
	}

	// -----------------------------------------------------------------------
	// The order inside the build job
	// -----------------------------------------------------------------------

	/**
	 * The order is not cosmetic. The image scan has to hit the image that was just
	 * built from the jar the tests just approved, and the smoke test has to hit the
	 * stack running exactly that image. Swap them around and you scan and smoke-test
	 * something other than what you ship.
	 */
	@Test
	void givenTheBuildJob_whenReadingItsSteps_thenVerifyBuildsScansAndSmokeTestsInThatOrder() {
		List<String> commands = steps(JOBS.get("build")).stream()
				.map(t -> String.valueOf(t.getOrDefault("run", t.getOrDefault("uses", ""))))
				.toList();

		int verify = indexContaining(commands, "mvnw -B -ntp verify");
		int image = indexContaining(commands, "docker build");
		int scan = indexContaining(commands, "trivy-action");
		int stack = indexContaining(commands, "docker compose up");
		int smokeTest = indexContaining(commands, "grafana/k6");

		assertThat(verify).as("mvn verify must run first").isLessThan(image);
		assertThat(image).as("the image must be built before it is scanned").isLessThan(scan);
		assertThat(scan).as("an image with known CVEs must never get as far as being started").isLessThan(stack);
		assertThat(stack).as("the smoke test must hit a running stack").isLessThan(smokeTest);
	}

	@Test
	void givenTheBuildJob_whenInspectingItsMavenInvocation_thenItRunsTheWholeVerifyPhase() {
		assertThat(TEXT)
				.as("'verify' is what runs the Testcontainers integration tests and the Modulith verification")
				.contains("./mvnw -B -ntp verify");
	}

	@Test
	void givenTheBuildJob_whenTheRunFails_thenTheStackIsStillTornDown() {
		Map<String, Object> cleanup = steps(JOBS.get("build")).stream()
				.filter(t -> String.valueOf(t.getOrDefault("run", "")).contains("docker compose down"))
				.findFirst().orElseThrow(() -> new AssertionError("the build job never tears the stack down"));

		assertThat(String.valueOf(cleanup.get("if")))
				.as("without always() volumes and containers are left behind after a failed run")
				.isEqualTo("always()");
		assertThat(String.valueOf(cleanup.get("run")))
				.as("-v also removes the database volume, so the next run starts clean")
				.contains("-v");
	}

	@Test
	void givenTheWorkflowFile_whenInspectingItsTokenPermissions_thenTheyAreTheLeastPossible() {
		assertThat(ProjectFile.map(WORKFLOW, "permissions"))
				.as("the default token has write permissions; the pipeline only needs to read")
				.containsExactly(Map.entry("contents", "read"));
	}

	// -----------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> steps(Object job) {
		Object steps = ProjectFile.map(job).get("steps");
		return steps instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
	}

	private static List<Map<String, Object>> allSteps() {
		List<Map<String, Object>> all = new ArrayList<>();
		JOBS.values().forEach(job -> all.addAll(steps(job)));
		return all;
	}

	private static int indexContaining(List<String> commands, String keyword) {
		for (int i = 0; i < commands.size(); i++) {
			if (commands.get(i).contains(keyword)) {
				return i;
			}
		}
		throw new AssertionError("No step in the build job contains '" + keyword + "'");
	}

}

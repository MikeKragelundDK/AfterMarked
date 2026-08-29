package dk.aftermarked.ops;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPERATIONS — the container image (PLAN §2.1 "one container", CLAUDE.md "no version drift").
 *
 * <p>The Dockerfile and .dockerignore are production code without a compiler. A
 * forgotten {@code USER} line, a base image that suddenly follows a tag, or a build
 * context that accidentally drags {@code .env} into the layer — all of them are
 * mistakes that surface only in production, and that no other test touches.
 */
class ContainerImageOpsTest {

	private static final String DOCKERFILE = "Dockerfile";

	private static final String CONTENT = ProjectFile.text(DOCKERFILE);

	/** One instruction per element: lines broken with a backslash are joined back together. */
	private static final List<String> INSTRUCTIONS = ProjectFile.activeLines(DOCKERFILE).stream()
			.reduce(new java.util.ArrayList<String>(), (joined, line) -> {
				if (!joined.isEmpty() && joined.getLast().endsWith("\\")) {
					String previous = joined.removeLast();
					joined.add(previous.substring(0, previous.length() - 1).strip() + " " + line);
				}
				else {
					joined.add(line);
				}
				return joined;
			}, (a, b) -> {
				a.addAll(b);
				return a;
			});

	@Test
	void givenTheDockerfile_whenInspectingItsFromInstructions_thenEveryBaseImageIsPinnedByDigest() {
		List<String> from = INSTRUCTIONS.stream()
				.filter(line -> line.regionMatches(true, 0, "FROM ", 0, 5))
				.toList();

		assertThat(from).as("the Dockerfile must have at least one FROM").isNotEmpty();
		assertThat(from)
				.as("""
						CLAUDE.md: no version drift. A tag can be repointed by its owner, \
						and then the next release builds a different OS than the one that was scanned.""")
				.allSatisfy(line -> assertThat(line).contains("@sha256:"));
	}

	@Test
	void givenTheDockerfile_whenInspectingTheRuntimeStage_thenItRunsAsAnUnprivilegedUser() {
		int lastFrom = lastIndexOf("FROM ");
		List<String> runtime = INSTRUCTIONS.subList(lastFrom, INSTRUCTIONS.size());

		List<String> userLines = runtime.stream()
				.filter(line -> line.regionMatches(true, 0, "USER ", 0, 5))
				.toList();

		assertThat(userLines)
				.as("without USER the process runs as root inside the container")
				.hasSize(1);
		assertThat(userLines.getFirst().substring(5).strip())
				.isNotEqualTo("root").isNotEqualTo("0");
		assertThat(runtime.stream().anyMatch(l -> l.regionMatches(true, 0, "RUN ", 0, 4)
				&& l.contains("adduser")))
				.as("the user must be created in the image, not assumed to exist")
				.isTrue();
	}

	@Test
	void givenTheDockerfile_whenInspectingTheRuntimeStage_thenTheOsPackagesAreUpgraded() {
		assertThat(INSTRUCTIONS.subList(lastIndexOf("FROM "), INSTRUCTIONS.size()))
				.as("""
						apk upgrade closes the CVEs in the base image that have a fix in Alpine, \
						without waiting for a new temurin tag. Otherwise the Trivy gate in CI blocks.""")
				.anySatisfy(line -> assertThat(line).contains("apk upgrade"));
	}

	/**
	 * Exec form ({@code ["java", ...]}) makes java PID 1, so SIGTERM hits the JVM
	 * directly. Shell form would put {@code /bin/sh -c} in between, and the shell does
	 * not forward the signal — then {@code server.shutdown=graceful} would never be
	 * triggered, and every deploy would cut off in-flight requests.
	 */
	@Test
	void givenTheDockerfile_whenInspectingTheEntrypoint_thenItIsExecFormSoTheJvmReceivesSigterm() {
		String entrypoint = theSingleInstruction("ENTRYPOINT");

		assertThat(entrypoint).startsWith("ENTRYPOINT [").contains("\"java\"").contains("\"-jar\"");
		assertThat(CONTENT)
				.as("SIGTERM is the default, but it is written explicitly — it is the contract behind graceful shutdown")
				.contains("STOPSIGNAL SIGTERM");
	}

	@Test
	void givenTheDockerfile_whenInspectingItsHealthcheck_thenItProbesReadinessOnTheManagementPort() {
		String healthcheck = theSingleInstruction("HEALTHCHECK");

		assertThat(healthcheck)
				.as("liveness says 'the process is alive', readiness says 'it can take traffic' — the LB needs the latter")
				.contains("/actuator/health/readiness")
				.contains("8081");
	}

	@Test
	void givenTheDockerfile_whenLookingForJvmFlags_thenTheyAreInTheEntrypointAndNotInJavaToolOptions() {
		String entrypoint = theSingleInstruction("ENTRYPOINT");

		assertThat(entrypoint)
				.as("a heap ceiling inside the container and a fast death on OOM — otherwise the container is OOM-killed silently")
				.contains("MaxRAMPercentage")
				.contains("ExitOnOutOfMemoryError");
		assertThat(CONTENT)
				.as("""
						JAVA_TOOL_OPTIONS makes the JVM write "Picked up JAVA_TOOL_OPTIONS" \
						on stderr at every start. In the prod profile stdout/stderr are ECS JSON, \
						so that line is unparseable for the log collector.""")
				.doesNotContain("ENV JAVA_TOOL_OPTIONS");
	}

	@Test
	void givenTheDockerfile_whenScanningItsInstructions_thenNoSecretIsBakedIntoTheImage() {
		Pattern suspicious = Pattern.compile("(?i)^(ENV|ARG)\\s+.*(password|secret|token|api_?key|credential)");

		assertThat(INSTRUCTIONS)
				.as("an image is shared and stored; a secret in a layer cannot be deleted afterwards")
				.noneSatisfy(line -> assertThat(suspicious.matcher(line).find()).isTrue());
		assertThat(INSTRUCTIONS)
				.as("ADD from a URL pulls unverified content into the image — use COPY")
				.noneSatisfy(line -> assertThat(line).matches("(?i)^ADD\\s+https?://.*"));
	}

	/**
	 * The build context is a deny list with exactly one exception. That is what keeps
	 * {@code .git}, local {@code .env} files, IDE config and source code from ever
	 * landing in a layer — no matter what anyone later drops in the project root.
	 */
	@Test
	void givenTheDockerignoreFile_whenInspectingItsRules_thenOnlyTheBuiltJarEntersTheBuildContext() {
		List<String> rules = ProjectFile.activeLines(".dockerignore");

		assertThat(rules.getFirst())
				.as(".dockerignore must start by excluding everything")
				.isEqualTo("*");
		assertThat(rules.stream().filter(r -> r.startsWith("!")).toList())
				.as("exactly one exception — every extra one is a new way to leak a file into the image")
				.containsExactly("!target/*.jar");
	}

	// -----------------------------------------------------------------------

	private static int lastIndexOf(String prefix) {
		for (int i = INSTRUCTIONS.size() - 1; i >= 0; i--) {
			if (INSTRUCTIONS.get(i).regionMatches(true, 0, prefix, 0, prefix.length())) {
				return i;
			}
		}
		throw new AssertionError("No " + prefix + " instruction in the Dockerfile");
	}

	/** The instruction as a single line, also when it is broken across several with a backslash. */
	private static String theSingleInstruction(String name) {
		Matcher m = Pattern.compile("(?im)^" + name + "\\b(?:[^\\n\\\\]|\\\\\\s*\\n)*")
				.matcher(CONTENT);
		assertThat(m.find()).as("%s is missing from the Dockerfile", name).isTrue();
		return m.group().replace("\\\n", " ").replaceAll("\\s+", " ").strip();
	}

}

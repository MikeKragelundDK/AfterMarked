package dk.aftermarked.ops;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

/**
 * Reads the operations files from the project root.
 *
 * <p>Dockerfile, compose.yaml, nginx.conf and the CI workflow are code that runs in
 * production — but they have no compiler, no types and no tests, and a mistake in
 * them is typically discovered only once the stack rolls. So they are read here and
 * held up against the invariants PLAN.md and CLAUDE.md impose.
 *
 * <p>The paths are relative to the project root, which is Maven's working directory.
 */
final class ProjectFile {

	private ProjectFile() {
	}

	static String text(String path) {
		Path file = Path.of(path);
		if (!Files.isRegularFile(file)) {
			throw new AssertionError(
					"The operations file " + path + " does not exist — the test cannot prove anything about it");
		}
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** The file as lines, without comments and blank lines — what is actually configuration. */
	static List<String> activeLines(String path) {
		return text(path).lines()
				.map(String::strip)
				.filter(line -> !line.isEmpty() && !line.startsWith("#"))
				.toList();
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> yaml(String path) {
		return new Yaml().load(text(path));
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> map(Object node, String... keys) {
		Object current = node;
		for (String key : keys) {
			if (!(current instanceof Map<?, ?> m) || !m.containsKey(key)) {
				throw new AssertionError("Missing key '" + key + "' in " + current);
			}
			current = m.get(key);
		}
		return (Map<String, Object>) current;
	}

	/** "30s" / "2m" / "90" -> seconds. The Docker Compose duration format. */
	static long seconds(String duration) {
		String v = duration.strip();
		if (v.endsWith("ms")) {
			return Long.parseLong(v.substring(0, v.length() - 2)) / 1000;
		}
		if (v.endsWith("s")) {
			return Long.parseLong(v.substring(0, v.length() - 1));
		}
		if (v.endsWith("m")) {
			return Long.parseLong(v.substring(0, v.length() - 1)) * 60;
		}
		if (v.endsWith("h")) {
			return Long.parseLong(v.substring(0, v.length() - 1)) * 3600;
		}
		return Long.parseLong(v);
	}

}

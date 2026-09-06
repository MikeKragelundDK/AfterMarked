package dk.aftermarked.ops;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPERATIONS — det net, Modulith ikke kan spænde ud: SQL er strenge, ikke typer.
 *
 * <p>{@code CustomFieldIsolationInvariantTest} og Modulith's egen verifikation
 * ser TYPEAFHÆNGIGHEDER. Men {@code SELECT custom_fields FROM event} inde i
 * {@code orders} er en tekstblok — den refererer ingen type fra
 * {@code catalog.fields}, den kompilerer, og begge de to andre net er grønne,
 * mens saga-koden læser feltværdier direkte ud af databasen.
 *
 * <p>Derfor denne kildescanning. Den er billig at have, fordi kolonnen med vilje
 * hedder {@code custom_fields} og ikke {@code fields}: det distinkte navn gør
 * scanningen falsk-positiv-fri, så et {@code contains} er nok, og der skal ikke
 * skrives en SQL-parser for at holde reglen.
 *
 * <p>De tre moduler er dem, PLAN §2.6 navngiver — penge, saga og allokering.
 * Listen strammes gerne; den løsnes aldrig.
 *
 * <p>Der scannes efter små bogstaver på en småbogstaveret kilde, fordi
 * identifiers i Postgres er case-insensitive: {@code CUSTOM_FIELDS} er præcis
 * den samme kolonne.
 */
class CustomFieldSqlIsolationOpsTest {

	static final String COLUMN = "custom_fields";

	/** Penge-, saga- og allokeringsmodulerne (PLAN §2.6). */
	static final List<String> SEALED_MODULES = List.of("orders", "payments", "marketplace");

	/** Produktionskildens rod. Maven kører fra projektroden. */
	static final Path SOURCE_ROOT = Path.of("src", "main", "java", "dk", "aftermarked");

	@Test
	void givenTheMoneySagaAndAllocationModules_whenScanningTheirSource_thenNoneNamesTheCustomFieldColumn() {
		for (String module : SEALED_MODULES) {
			for (Path file : sourceFilesOf(module)) {
				assertThat(lowercaseSourceOf(file))
						.as("%s must never name the custom field column — custom fields are presentation "
								+ "and metadata, never behaviour (PLAN §2.6)", file)
						.doesNotContain(COLUMN);
			}
		}
	}

	/**
	 * Vagthunden mod en grøn tautologi, i to led. Et forkert stinavn ville få
	 * scanningen til at læse nul filer og bestå uden at have set noget; og en
	 * scanning, der kun kiggede på filnavne, ville bestå uden at have læst
	 * noget. Første led tæller filerne, andet led beviser på et kendt sted, at
	 * indholdet faktisk læses.
	 */
	@Test
	void givenTheScan_whenCountingTheFilesItReads_thenItIsNotCheckingAnEmptySet() {
		for (String module : SEALED_MODULES) {
			assertThat(sourceFilesOf(module))
					.as("the scan must actually read the source of %s", module)
					.isNotEmpty();
		}

		boolean readerSeesFileContent = sourceFilesOf("localization").stream()
				.map(CustomFieldSqlIsolationOpsTest::lowercaseSourceOf)
				.anyMatch(source -> source.contains("operator_id"));

		assertThat(readerSeesFileContent)
				.as("the scan must read file content, not just file names — localization does name operator_id")
				.isTrue();
	}

	/** Hver fil under ét modul. Fejler højlydt, hvis modulmappen ikke findes. */
	static List<Path> sourceFilesOf(String module) {
		Path directory = SOURCE_ROOT.resolve(module);
		if (!Files.isDirectory(directory)) {
			throw new AssertionError(
					"The module directory " + directory + " does not exist — the scan cannot prove anything about it");
		}
		try (Stream<Path> files = Files.walk(directory)) {
			return files.filter(Files::isRegularFile).sorted().toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	static String lowercaseSourceOf(Path file) {
		return ProjectFile.text(file.toString()).toLowerCase(Locale.ROOT);
	}

}

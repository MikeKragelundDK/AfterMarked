package dk.aftermarked.ops;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import dk.aftermarked.LocalizationConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPERATIONS — lokalisering af systemets egne tekster.
 *
 * <p>Fejlen, testen findes for, er stille: nogen tilføjer en tekst til
 * {@code messages_da.properties} og glemmer {@code messages_en.properties}.
 * Intet knækker ved oversættelsen — den engelske bruger får bare den danske
 * tekst fra fallback-bundlen, eller i værste fald et nøglenavn. Det opdages
 * først af en kunde.
 *
 * <p>Læser filerne direkte og kræver hverken Docker eller Spring.
 */
class LocalizationOpsTest {

	private static final Path RESOURCES = Path.of("src/main/resources");

	private static final Path TEMPLATES = RESOURCES.resolve("templates");

	/** {@code #{en.noegle}} — kun literale nøgler; de sammensatte håndteres nedenfor. */
	private static final Pattern LITERAL_KEY = Pattern.compile("#\\{([a-z][a-zA-Z0-9._]*)}");

	/** {@code th:text="'hardkodet'"} — en tekst, der aldrig kan oversættes. */
	private static final Pattern LITERAL_TEXT = Pattern.compile("th:(text|utext)\\s*=\\s*\"\\s*'");

	/** Nøglen, der navngiver stakkens versioner på forsiden. */
	private static final String STACK_KEY = "hello.stack";

	@Test
	void givenEveryLanguageBundle_whenComparingTheirKeys_thenAllOfThemHoldExactlyTheSameKeySet() throws IOException {
		Set<String> fallback = keysIn(RESOURCES.resolve("messages.properties"));
		assertThat(fallback).as("the fallback bundle must have keys to fall back on").isNotEmpty();

		for (Path bundle : languageBundles()) {
			Set<String> keys = keysIn(bundle);
			assertThat(keys)
					.as("%s is missing keys that exist in messages.properties — the user would see "
							+ "another language or a key name", bundle.getFileName())
					.containsAll(fallback);
			assertThat(fallback)
					.as("%s has keys that messages.properties does not know — there is no "
							+ "fallback if they are missing in a third language", bundle.getFileName())
					.containsAll(keys);
		}
	}

	/**
	 * Hvert sprog i {@code SUPPORTED_LOCALES} skal have en bundle. Uden den ville
	 * sprogvælgeren tilbyde et sprog, der bare viser fallback-teksten.
	 */
	@Test
	void givenTheSupportedLocales_whenLookingForTheirBundles_thenEveryLanguageHasOne() {
		assertThat(LocalizationConfig.SUPPORTED_LOCALES).isNotEmpty();
		for (Locale locale : LocalizationConfig.SUPPORTED_LOCALES) {
			assertThat(RESOURCES.resolve("messages_" + locale.getLanguage() + ".properties"))
					.as("the language [%s] is on the list, but has no texts", locale)
					.exists();
		}
		assertThat(LocalizationConfig.SUPPORTED_LOCALES)
				.as("the default language must itself be supported")
				.contains(LocalizationConfig.DEFAULT_LOCALE);
	}

	@Test
	void givenEveryMessageKeyUsedInATemplate_whenLookingItUp_thenItExistsInTheFallbackBundle() throws IOException {
		Set<String> fallback = keysIn(RESOURCES.resolve("messages.properties"));
		List<String> missing = new ArrayList<>();

		for (Path template : templates()) {
			Matcher key = LITERAL_KEY.matcher(Files.readString(template, StandardCharsets.UTF_8));
			while (key.find()) {
				if (!fallback.contains(key.group(1))) {
					missing.add(template.getFileName() + ": " + key.group(1));
				}
			}
		}
		assertThat(missing).as("a key without a text renders as '??key??' on the page").isEmpty();
	}

	/**
	 * Den sammensatte nøgle {@code #{'language.name.' + ${tag}}} kan ikke slås op
	 * statisk. Den ene, der findes, dækkes derfor eksplicit: hvert understøttet
	 * sprog skal have sit eget visningsnavn.
	 */
	@Test
	void givenTheLanguageSwitcher_whenLookingUpEachLanguageName_thenEverySupportedLanguageHasOne() throws IOException {
		Set<String> fallback = keysIn(RESOURCES.resolve("messages.properties"));
		for (Locale locale : LocalizationConfig.SUPPORTED_LOCALES) {
			assertThat(fallback)
					.as("the language switcher builds the key 'language.name.<language>' dynamically")
					.contains("language.name." + locale.getLanguage());
		}
	}

	/**
	 * Forsiden viser stakkens versioner som brugervendt tekst. Den tekst står i
	 * tre bundles og bliver ikke opdateret af en Maven-opgradering — så dagen
	 * {@code pom.xml} rykker til Boot 4.2, står der stadig 4.1 på siden i alle
	 * tre sprog. "Ingen version drift" (CLAUDE.md) gælder også dét, vi påstår
	 * over for brugeren.
	 */
	@Test
	void givenTheStackTextInEveryBundle_whenComparingItToThePom_thenItNamesTheVersionsWeActuallyBuildWith()
			throws IOException {
		String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
		String bootVersion = capture(pom, "<artifactId>spring-boot-starter-parent</artifactId>\\s*"
				+ "<version>(\\d+\\.\\d+)[.\\d\\w-]*</version>");
		String javaVersion = capture(pom, "<java\\.version>(\\d+)</java\\.version>");

		for (Path bundle : allBundles()) {
			String stack = valueOf(bundle, STACK_KEY);
			assertThat(stack)
					.as("%s: the front page claims a Spring Boot version the build does not use", bundle.getFileName())
					.contains("Spring Boot " + bootVersion);
			assertThat(stack)
					.as("%s: the front page claims a Java version the build does not use", bundle.getFileName())
					.contains("Java " + javaVersion);
		}
	}

	/**
	 * Brugervendt tekst hører i bundlen, ikke i skabelonen. En
	 * {@code th:text="'…'"} med en literal streng er den nemme vej udenom — og
	 * den kan aldrig oversættes.
	 */
	@Test
	void givenEveryTemplate_whenScanningItsTextAttributes_thenNoneRenderAHardcodedLiteral() throws IOException {
		List<String> hardcoded = new ArrayList<>();
		for (Path template : templates()) {
			if (LITERAL_TEXT.matcher(Files.readString(template, StandardCharsets.UTF_8)).find()) {
				hardcoded.add(template.getFileName().toString());
			}
		}
		assertThat(hardcoded).as("use #{key} instead of a literal text").isEmpty();
	}

	// -----------------------------------------------------------------------

	private static List<Path> languageBundles() throws IOException {
		try (Stream<Path> files = Files.list(RESOURCES)) {
			return files.filter(f -> f.getFileName().toString().matches("^messages_[a-z]{2}\\.properties$"))
					.sorted().toList();
		}
	}

	/** Fallback-bundlen og hvert sprog — alle de filer, en nøgle skal stå i. */
	private static List<Path> allBundles() throws IOException {
		List<Path> bundles = new ArrayList<>(languageBundles());
		bundles.add(RESOURCES.resolve("messages.properties"));
		return bundles;
	}

	/** Én værdi fra én bundle. Fejler højlydt, hvis nøglen er væk — den skal findes. */
	private static String valueOf(Path bundle, String key) {
		Properties properties = load(bundle);
		String value = properties.getProperty(key);
		assertThat(value).as("%s has no key '%s'", bundle.getFileName(), key).isNotNull();
		return value;
	}

	/** Første gruppe i det første match — eller en rød test, hvis mønsteret ikke findes. */
	private static String capture(String text, String regex) {
		Matcher matcher = Pattern.compile(regex).matcher(text);
		assertThat(matcher.find()).as("pom.xml no longer matches [%s] — the test cannot read the version", regex)
				.isTrue();
		return matcher.group(1);
	}

	private static List<Path> templates() throws IOException {
		try (Stream<Path> files = Files.walk(TEMPLATES)) {
			return files.filter(Files::isRegularFile)
					.filter(f -> f.getFileName().toString().endsWith(".html"))
					.sorted().toList();
		}
	}

	/** Nøglerne i én bundle. Læses som UTF-8, som Boot også gør. */
	private static Set<String> keysIn(Path bundle) {
		return new TreeSet<>(load(bundle).stringPropertyNames());
	}

	private static Properties load(Path bundle) {
		Properties properties = new Properties();
		try {
			properties.load(new StringReader(Files.readString(bundle, StandardCharsets.UTF_8)));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return properties;
	}

}

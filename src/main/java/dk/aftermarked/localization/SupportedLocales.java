package dk.aftermarked.localization;

import java.util.List;
import java.util.Locale;

/**
 * Sprogene, fladen renderer — dem der findes en
 * {@code messages_<sprog>.properties} til.
 *
 * <p>Listen bor her i {@code localization} og ikke i rodpakkens
 * {@code LocalizationConfig}, fordi katalog-indlæsningen skal kunne spørge
 * "renderer vi det her sprog?" uden at trække en {@code WebMvcConfigurer}
 * med servlet-imports ind i sin klasse-indlæsning. Rodpakken er usynlig for
 * Modulith, så en import derfra ville være en modulafhængighed, ingen
 * verifikationstest kunne se. {@code LocalizationConfig.SUPPORTED_LOCALES}
 * spejler listen, så resten af koden er uændret.
 *
 * <p>Udvides listen, skal bundlen med i samme ændring — det håndhæver
 * {@code LocalizationOpsTest}.
 */
public final class SupportedLocales {

	/** Sprog uden land: {@code da} og {@code en}, i den rækkefølge. */
	public static final List<Locale> ALL = List.of(Locale.of("da"), Locale.of("en"));

	private SupportedLocales() {
	}

	/**
	 * Om sproget renderes — sammenlignet på SPROG og ikke på hele tagget:
	 * {@code en-GB} er engelsk, og engelsk renderer vi.
	 */
	public static boolean isSupported(Locale locale) {
		return locale != null && ALL.stream()
				.anyMatch(supported -> supported.getLanguage().equals(locale.getLanguage()));
	}

}

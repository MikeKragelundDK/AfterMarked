package dk.aftermarked;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import dk.aftermarked.localization.SupportedLocales;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * Sprogvalg for hele fladen.
 *
 * <p>Rækkefølgen er: <b>1)</b> {@code ?lang=} sætter en cookie, der huskes på
 * tværs af besøg — <b>2)</b> ingen cookie: browserens {@code Accept-Language},
 * hvis vi understøtter sproget — <b>3)</b> ellers {@link #DEFAULT_LOCALE}.
 *
 * <p><strong>Sprog er ikke en sikkerhedsgrænse.</strong> Det er derfor i orden,
 * at det kommer fra bruger-input, hvor operatør-konteksten aldrig må gøre det
 * (PLAN §2.7 regel 2). De to må ikke forveksles: {@code ?lang=} skifter sprog og
 * kun sprog — den kan ikke nå hverken operatør, arrangør eller data.
 *
 * <p>Input valideres <em>fail-closed</em>: et ukendt sprog i {@code ?lang=}
 * afvises af {@link SupportedLocaleChangeInterceptor} og ignoreres i stilhed, så
 * brugerens hidtidige valg står ved magt. Ingen fejlside, og aldrig en vilkårlig
 * locale, vi ikke har tekster til.
 *
 * <p><strong>Begge ender af kanalen valideres.</strong> {@code ?lang=} er den
 * ene ende; cookien er den anden, og den er lige så meget bruger-input — den
 * ligger i klientens egen browser. Derfor validerer
 * {@link SupportedLocaleCookieResolver} også ved LÆSNING, med præcis samme
 * regel. Validerede vi kun ved skrivning, kunne en cookie sat udenom os (af
 * klienten selv, af en søskende-vært på et fælles forældredomæne, eller
 * efterladt af en tidligere version med andre sprog) enten vælte siden eller
 * sætte den i et sprog, vi ikke har tekster til — og cookien lever 365 dage.
 */
@Configuration
@Import(LocalizationConfig.SupportedLanguagesAdvice.class)
public class LocalizationConfig implements WebMvcConfigurer {

	/** Dansk, indtil brugeren siger andet. Første kunde er dansk (PLAN §1). */
	public static final Locale DEFAULT_LOCALE = Locale.of("da");

	/**
	 * Sprogene der findes en {@code messages_<sprog>.properties} til. Selve
	 * listen bor i {@link SupportedLocales} i localization-modulet, så
	 * katalog-indlæsningen kan læse den uden at afhænge af web-laget; her
	 * spejles den. Udvides listen, skal bundlen med i samme ændring — det
	 * håndhæver {@code LocalizationOpsTest}.
	 */
	public static final List<Locale> SUPPORTED_LOCALES = SupportedLocales.ALL;

	static final String LOCALE_COOKIE_NAME = "THETICKET_LANG";

	static final String LOCALE_PARAM_NAME = "lang";

	private static final Logger log = LoggerFactory.getLogger(LocalizationConfig.class);

	static boolean isSupported(Locale locale) {
		return SupportedLocales.isSupported(locale);
	}

	/**
	 * Den locale fra {@link #SUPPORTED_LOCALES}, der svarer til {@code candidate},
	 * eller tom hvis vi ikke har tekster til sproget.
	 *
	 * <p>Det er ALTID vores egen instans, der returneres, aldrig kalderens. En
	 * regionsvariant af et sprog, vi har ({@code en-GB}, {@code en-US-x-hack}),
	 * er ikke skrald — brugeren beder om engelsk — men formen bestemmer vi:
	 * ellers ville brugerens ordrette streng blive gemt i cookien, læst tilbage
	 * ved hvert senere request og sendt videre som opslagsnøgle til
	 * {@code Translations}, hvor kun de kanoniske sprogkoder findes.
	 */
	static Optional<Locale> supported(Locale candidate) {
		if (candidate == null) {
			return Optional.empty();
		}
		return SUPPORTED_LOCALES.stream()
				.filter(known -> known.getLanguage().equals(candidate.getLanguage()))
				.findFirst();
	}

	@Bean
	LocaleResolver localeResolver() {
		CookieLocaleResolver resolver = new SupportedLocaleCookieResolver();
		resolver.setDefaultLocale(DEFAULT_LOCALE);
		// Uden cookie: brug browserens sprog, men kun hvis vi rent faktisk har
		// tekster til det. Ellers ville en tysk browser få nøglenavne at se.
		resolver.setDefaultLocaleFunction(LocalizationConfig::fromAcceptLanguage);
		resolver.setCookieMaxAge(Duration.ofDays(365));
		resolver.setCookiePath("/");
		// Sproget læses kun af serveren; JavaScript har intet at bruge det til.
		resolver.setCookieHttpOnly(true);
		// Lax holder cookien væk fra cross-site POSTs uden at ødelægge et
		// almindeligt link ind til siden.
		resolver.setCookieSameSite("Lax");
		return resolver;
	}

	private static Locale fromAcceptLanguage(HttpServletRequest request) {
		return Collections.list(request.getLocales()).stream()
				.filter(LocalizationConfig::isSupported)
				.findFirst()
				.orElse(DEFAULT_LOCALE);
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new SupportedLocaleChangeInterceptor());
	}

	/**
	 * Gør sprogvalgene tilgængelige for ENHVER skabelon, ikke kun forsiden — en
	 * sprogvælger, der kun findes ét sted, er en, brugeren taber undervejs.
	 */
	@ControllerAdvice
	static class SupportedLanguagesAdvice {

		@ModelAttribute("supportedLanguages")
		List<String> supportedLanguages() {
			return SUPPORTED_LOCALES.stream().map(Locale::getLanguage).toList();
		}

	}

	/**
	 * {@code ?lang=} må kun kunne vælge et sprog, vi har tekster til.
	 *
	 * <p>{@code parseLocaleValue} kaster på alt andet, og med
	 * {@code ignoreInvalidLocale} betyder det, at værdien ignoreres uden at
	 * ændre cookien. {@code ?lang=klingon} lader altså brugerens hidtidige sprog
	 * stå — den giver hverken 500 eller et halvoversat svar.
	 */
	static final class SupportedLocaleChangeInterceptor extends LocaleChangeInterceptor {

		SupportedLocaleChangeInterceptor() {
			setParamName(LOCALE_PARAM_NAME);
			setIgnoreInvalidLocale(true);
			// Sprogskift er en GET-handling; et POST skal ikke kunne flytte sproget
			// som sidegevinst.
			setHttpMethods("GET");
		}

		@Override
		protected Locale parseLocaleValue(String value) {
			// NORMALISÉR til den locale, vi selv har på listen — returnér aldrig
			// brugerens egen streng. "en-US-x-hack" har sproget "en" og skal give
			// engelsk, men det er OS, der bestemmer formen: uden det her ville
			// brugerens ordrette tekst blive gemt i cookien og læst tilbage ved
			// hvert eneste senere request.
			return supported(super.parseLocaleValue(value))
					.orElseThrow(() -> new IllegalArgumentException("Unsupported locale [" + value + "]"));
		}

	}

	/**
	 * Cookien er den anden ende af den samme kanal som {@code ?lang=}, og den
	 * valideres med den samme regel.
	 *
	 * <p>To ting går galt uden det. <b>1)</b> En værdi,
	 * {@code StringUtils.parseLocale} ikke kan læse ({@code THETICKET_LANG=!!}),
	 * får {@code CookieLocaleResolver} til at kaste — den har
	 * {@code rejectInvalidCookies} slået til som default — og resultatet er 500
	 * på hvert eneste request, så længe cookien ligger der. <b>2)</b> En værdi,
	 * der er velformet, men som vi ikke har tekster til ({@code THETICKET_LANG=de}),
	 * bliver requestets locale: {@code html lang} siger tysk, teksterne kommer
	 * fra en fallback-bundle på et andet sprog, og {@code Translations} slår op
	 * på en sprogkode, fladen ikke tilbyder.
	 *
	 * <p>Begge dele bliver her til <em>tom</em> — {@code parseLocaleValue} må
	 * returnere null — og så falder resolveren tilbage på
	 * {@code defaultLocaleFunction}, altså {@code Accept-Language} og dernæst
	 * {@link #DEFAULT_LOCALE}. {@code rejectInvalidCookies} slås fra, fordi
	 * cookieværdien også parses uden for {@code parseLocaleValue}
	 * (tidszone-delen efter et mellemrum), og en ugyldig cookie aldrig må kunne
	 * blive til en fejlside.
	 */
	static final class SupportedLocaleCookieResolver extends CookieLocaleResolver {

		SupportedLocaleCookieResolver() {
			super(LOCALE_COOKIE_NAME);
			setRejectInvalidCookies(false);
		}

		@Override
		protected Locale parseLocaleValue(String value) {
			try {
				return supported(super.parseLocaleValue(value)).orElse(null);
			}
			catch (IllegalArgumentException ex) {
				// Ikke en locale overhovedet. Ignoreres i stilhed, præcis som et
				// ukendt ?lang= — beskeden er udviklervendt og indeholder
				// klientens egen streng, så den bliver på debug-niveau.
				log.debug("Ignoring unreadable [{}] cookie", LOCALE_COOKIE_NAME, ex);
				return null;
			}
		}

	}

}

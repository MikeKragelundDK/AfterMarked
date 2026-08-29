package dk.aftermarked.tenancy.internal;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import dk.aftermarked.tenancy.OperatorContext;
import dk.aftermarked.tenancy.OperatorId;
import dk.aftermarked.tenancy.OperatorRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolver operatøren fra værtsnavnet — den eneste tilladte kilde (PLAN §2.6)
 * — og binder den som kontekst for resten af requestet.
 *
 * <p><strong>Fejler lukket.</strong> Et ukendt værtsnavn får 421 Misdirected
 * Request og når aldrig ind i applikationen. Der findes ingen default-operatør
 * at falde tilbage på; det ville være præcis den fejl, der viser én kundes
 * markedsplads på et fremmed domæne.
 *
 * <p>Kilden er {@code request.getServerName()} — Host-headeren uden port, som
 * containeren har parset den. {@code X-Forwarded-Host} og {@code Forwarded}
 * ignoreres, fordi {@code server.forward-headers-strategy} er slået fra; den
 * dag den slås til, resolves operatøren pludselig fra en header, og det er en
 * beslutning, ikke en konfigurationsdetalje. {@code TenancyWiringTest} fejler
 * højlydt, hvis den bliver slået til uden at nogen har taget stilling.
 *
 * <p><strong>Async-dispatch er ikke dækket.</strong> {@code OncePerRequestFilter}
 * springer ASYNC-dispatch over som default, og {@link ScopedValue}-bindingen
 * findes alligevel kun på den tråd, der åbnede scopet. En controller, der
 * returnerer {@code Callable}/{@code DeferredResult}, mister derfor konteksten.
 * Der findes ingen async-endpoints i dag; kommer der nogen, skal de binde
 * konteksten selv præcis som et baggrundsjob.
 *
 * <p>Opslaget koster ét databasekald pr. request. Det er bevidst usofistikeret
 * i skelettet; en cache (nøglet på værtsnavn, ikke på operatør) hører til i
 * hærdningen (E15).
 */
final class OperatorResolutionFilter extends OncePerRequestFilter {

	static final int MISDIRECTED_REQUEST = 421;

	static final String ACTUATOR_PATH = "/actuator";

	/**
	 * Headerne på afvisningen. Filteret sidder FØR Spring Security (og skal
	 * blive der — uden operatør er der intet at autentificere imod), så
	 * {@code HeaderWriterFilter} har ikke pakket svaret ind, når afvisningen
	 * skrives. Uden de her linjer er afvisningen det ENESTE svar, applikationen
	 * giver uden nogen header-politik — og det er samtidig det svar, hele
	 * internettet kan få, fra ethvert domæne der peger på os.
	 *
	 * <p>{@code no-store} er den vigtigste: et cachet 421 er et kundedomæne, der
	 * bliver ved med at være i stykker, efter kunden er provisioneret.
	 */
	private static final Map<String, String> REJECTION_HEADERS = Map.of(
			"Cache-Control", "no-store",
			"X-Content-Type-Options", "nosniff",
			"X-Frame-Options", "DENY");

	private static final Logger log = LoggerFactory.getLogger(OperatorResolutionFilter.class);

	private final OperatorRegistry registry;

	OperatorResolutionFilter(OperatorRegistry registry) {
		this.registry = registry;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		// Actuator er ikke tenant-scopet og serveres fra management-porten.
		// Ekskluderes her for en sikkerheds skyld, uanset port.
		//
		// Undtagelsen matcher på SEGMENTGRÆNSE. Et rent startsWith("/actuator")
		// undtager også /actuatorx og /actuator-admin — stier, der ikke er
		// actuator, men som ville slippe forbi operator-resolutionen. Alt andet
		// end præcis /actuator og /actuator/… går gennem filteret og fejler
		// lukket på et ukendt værtsnavn. getRequestURI() er rå og hverken
		// normaliseret eller dekodet; det er med vilje, at //actuator/health,
		// /%61ctuator/health og /actuator/../ derfor IKKE er undtaget.
		String uri = request.getRequestURI();
		return uri.equals(ACTUATOR_PATH) || uri.startsWith(ACTUATOR_PATH + "/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String host = request.getServerName();
		Optional<OperatorId> operator = registry.findByHost(host);
		if (operator.isEmpty()) {
			log.warn("Rejected request to unknown host name [{}]", host);
			reject(response);
			return;
		}
		try {
			OperatorContext.runAs(operator.get(), () -> chain.doFilter(request, response));
		}
		catch (IOException | ServletException | RuntimeException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new ServletException(ex);
		}
	}

	/**
	 * Afvisningen: status, headere, tom krop — og intet andet.
	 *
	 * <p>Bevidst {@code setStatus} og IKKE {@code sendError}. {@code sendError}
	 * beder containeren om at lave en ERROR-dispatch til {@code /error}, og så
	 * kører DispatcherServlet og fejl-controlleren alligevel — for et request,
	 * som filteret lige har sagt aldrig skulle nå ind i applikationen. To ting
	 * fulgte med: fejlsvarets JSON gengav requestets egen sti tilbage til en
	 * anonym kalder på et domæne, ingen kunde ejer, og hvert eneste
	 * vaertsnavns-scan kostede en fuld tur gennem servlet-stakken.
	 *
	 * <p>Et tomt svar er også det rigtige svar indholdsmæssigt: en kalder på et
	 * ukendt værtsnavn skal ikke have at vide, hvad der ellers findes her.
	 * Årsagen står i loggen, hvor den hører til.
	 */
	private static void reject(HttpServletResponse response) throws IOException {
		response.setStatus(MISDIRECTED_REQUEST);
		REJECTION_HEADERS.forEach(response::setHeader);
		response.setContentLength(0);
		response.flushBuffer();
	}

}

package dk.aftermarked.catalog.internal;

import java.util.Collection;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import dk.aftermarked.money.CurrencyMismatchException;
import dk.aftermarked.tenancy.Operator;
import dk.aftermarked.tenancy.OperatorContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * De to ting, der gør katalogets skrivesti sikker: serialiseringen pr. operatør
 * og enkelt-valuta-politikken.
 *
 * <p><strong>Kaldes af BÅDE katalog-pushet og lazy enrichment.</strong> Det er
 * hele grunden til, at klassen findes som en klasse og ikke som et par linjer
 * inde i {@code CatalogIngestService.apply}. Ligger kontrollen kun i den ene af
 * de to skrivestier, findes reglen kun dér: et natligt DKK-push og et samtidigt
 * verify-drevet EUR-skelet ser hver især et konsistent én-valuta-katalog og
 * committer begge. Resultatet er en operatør med to valutaer, som ingen af de
 * to kodestier nogensinde har set — og ingen fejl undervejs.
 *
 * <p><strong>Rækkefølgen er bærende: lås FØRST, læs derefter.</strong>
 * {@link #serialiseWritesForCurrentOperator()} skal være den første sætning i
 * transaktionen, og {@link #requireSingleCurrency(Currency)} må først køre
 * bagefter. Låsen er en {@code pg_advisory_xact_lock}, altså bundet til
 * transaktionen og frigivet ved commit; under READ COMMITTED tager den
 * efterfølgende {@code SELECT} et friskt snapshot, så den taber sig selv ser
 * vinderens allerede committede række. Byttes de to om, er tjekket tilbage til
 * at være en race.
 *
 * <p><strong>Politikken er DATA og ikke en constraint</strong>
 * ({@code operator.single_currency}, V4). Begrundelsen er ikke smag, og den er
 * grunden til, at {@code OperatorCurrencyInvariantTest} kan dække BEGGE
 * flagtilstande og aldrig behøver at blive rørt:
 * <ul>
 *   <li>{@code MigrationDisciplineInvariantTest} forbyder {@code DROP
 *       CONSTRAINT} for altid — en CHECK ville altså være permanent;</li>
 *   <li>PLAN lover, at den internationale kunde koster "én linje";</li>
 *   <li>CLAUDE.md forbyder at slette eller svække en invariant-test.</li>
 * </ul>
 * De tre løfter kan kun holdes samtidig, når reglen er et flag: invariant-testen
 * beskriver begge tilstande, og den internationale kunde er en {@code UPDATE}
 * uden deploy. Reglen er PER OPERATØR — PLAN §2.5's "alle events i instansen" er
 * skrevet før beslutningen om én delt instans for alle kunder.
 *
 * <p><strong>Der står ingen SQL mod {@code event} her.</strong> Valutalæsningen
 * er {@link EventJdbcStore#distinctCurrencies()}, og denne klasse er det ENESTE
 * sted, den metode må kaldes fra: uden låsen ovenfor er dens svar et øjebliksbillede,
 * der er forældet, inden det bruges. En invariant-test scanner kilderne og gør
 * bygget rødt, hvis en anden klasse end {@link EventJdbcStore} skriver den tabel.
 *
 * @see EventJdbcStore#distinctCurrencies()
 */
@Component
public class CatalogWriteGuard {

	/**
	 * Låsens namespace. Skrevet ordret i sætningen og ikke bundet som parameter:
	 * {@code pg_advisory_xact_lock} findes i både en {@code (bigint)}- og en
	 * {@code (int, int)}-udgave, og en bundet parameter ved siden af
	 * {@code hashtext(...)} ville lade overload-opløsningen afhænge af, hvilken
	 * type driveren tilfældigvis sender. Værdien er vores egen konstant og aldrig
	 * bruger-input, så der er intet at binde imod.
	 *
	 * <p>Nøglen er {@code hashtext} af operatørens id: to forskellige operatører
	 * blokerer aldrig hinanden, og to samtidige skrivninger for SAMME operatør
	 * står i kø. En kollision mellem to hashværdier ville koste unødig ventetid —
	 * aldrig korrekthed.
	 */
	private static final String SERIALISE_WRITES = """
			SELECT pg_advisory_xact_lock(4711, hashtext(CAST(current_operator_id() AS text)))
			 WHERE current_operator_id() IS NOT NULL
			""";

	private final JdbcClient jdbc;

	private final EventJdbcStore events;

	CatalogWriteGuard(JdbcClient jdbc, EventJdbcStore events) {
		this.jdbc = jdbc;
		this.events = events;
	}

	/**
	 * Sætter alle katalog-skrivninger for den aktuelle operatør i kø.
	 *
	 * <p><strong>Kald den som den FØRSTE sætning inde i transaktionen.</strong>
	 * Låsen er {@code xact}-scopet og frigives af commit eller rollback; tages
	 * den uden for en transaktion, er den væk igen, inden batchen er skrevet.
	 *
	 * <p>⚠ Funktionen returnerer {@code void} — men den returnerer det som ÉN
	 * RÆKKE (verificeret på 18.6). Den skal derfor køres som en QUERY, og
	 * resultatet kasseres. Et {@code .update()} giver pgjdbc's <em>"A result was
	 * returned when none was expected"</em>, altså en fejl på skrivestien fra
	 * første kald.
	 *
	 * @throws IllegalStateException hvis der ikke er nogen operatør i konteksten —
	 *     hverken i Java eller på databasesiden. De to kan skille sig ad, hvis
	 *     forbindelsen blev checket ud FØR {@code runAs}: så er Java-konteksten
	 *     sat, men {@code current_operator_id()} er NULL, og
	 *     {@code hashtext(NULL)} tager INGEN lås — i stilhed (målt på 18.6).
	 *     Derfor filtrerer sætningen på databasens egen kontekst og giver nul
	 *     rækker, når den mangler, og nul rækker er en fejl her
	 */
	public void serialiseWritesForCurrentOperator() {
		// Konteksten kommer fra værtsnavnet ved request-indgangen eller fra
		// jobrækkens egen operator_id -- aldrig fra en payload.
		OperatorContext.required();
		// Rækken kasseres, men dens FRAVÆR er beviset: uden en række blev der
		// ikke taget nogen lås, og så må skrivningen ikke fortsætte.
		if (this.jdbc.sql(SERIALISE_WRITES).query().listOfRows().isEmpty()) {
			throw new IllegalStateException(
					"No operator context on the database session; the catalog write lock was not taken");
		}
	}

	/**
	 * Parserens tidlige krydstjek gælder kun et katalog, der endnu ikke er
	 * blandet. Kaldes efter operatørlåsen: et eksisterende blandet katalog skal
	 * kunne genafspilles efter et flagskift, mens {@link #requireSingleCurrency(Operator, Collection)}
	 * stadig afviser enhver ny valuta før skrivningen. RLS afgrænser opslaget.
	 *
	 * <p>Operatøren gives ind og læses ikke igen her: kalderen har allerede
	 * læst rækken én gang under låsen, og to læsninger under READ COMMITTED er
	 * to snapshots, hvor flaget kan nå at skifte imellem.
	 */
	boolean requiresSingleCurrencyPayload(Operator operator) {
		Objects.requireNonNull(operator, "operator");
		OperatorContext.required();
		return operator.singleCurrency() && this.events.distinctCurrencies().size() <= 1;
	}

	/**
	 * Håndhæver "alle events hos DENNE operatør deler valuta", hvis operatørens
	 * flag er sat.
	 *
	 * <p>Bekvemmelighedsformen for lazy enrichment, som altid bærer præcis én
	 * valuta: verify-svarets face value.
	 *
	 * @throws CurrencyMismatchException hvis flaget er sat, og kataloget ville
	 *     ende med mere end én valuta
	 */
	public void requireSingleCurrency(Operator operator, Currency incoming) {
		Objects.requireNonNull(incoming, "incoming");
		requireSingleCurrency(operator, List.of(incoming));
	}

	/**
	 * Samme regel for en hel batch på én gang — formen katalog-pushet bruger.
	 *
	 * <p><strong>Hvorfor mængden og ikke ét kald pr. valuta.</strong> Kaldes den
	 * enkelte form i en løkke FØR nogen række er skrevet, ser hvert kald det
	 * samme tomme katalog: et push, der selv bærer både DKK og EUR, slipper
	 * igennem med to grønne tjek og lander begge events. Kaldes den derimod
	 * EFTER skrivningen, er kontrollen ikke længere en forudsætning, men en
	 * observation af noget, der allerede står i tabellen. Mængdeformen løser
	 * begge dele: unionen af det, kataloget har, og det, batchen bærer, skal
	 * kunne være i én valuta — målt før den første række røres.
	 *
	 * <p>En tom {@code incoming} er en no-op: et push uden events kan ikke
	 * tilføje en valuta, og det må ikke afvises, blot fordi et gammelt katalog
	 * står med to.
	 *
	 * @param operator den aktuelle operatør, læst ÉN gang af kalderen under låsen
	 * @param incoming valutaerne, skrivningen ville tilføje
	 * @throws CurrencyMismatchException hvis flaget er sat, og unionen af
	 *     kataloget og batchen har mere end én valuta — eller, hvis kataloget
	 *     allerede er blandet, hvis batchen bærer en valuta, kataloget ikke har i
	 *     forvejen. Beskeden navngiver begge koder — den ene af dem er den
	 *     forkerte, og ingen kan se hvilken uden dem begge
	 */
	public void requireSingleCurrency(Operator operator, Collection<Currency> incoming) {
		Objects.requireNonNull(operator, "operator");
		Objects.requireNonNull(incoming, "incoming");
		OperatorContext.required();
		if (incoming.isEmpty()) {
			return;
		}
		if (!operator.singleCurrency()) {
			// Den internationale kunde. Flaget er politik og ikke en constraint,
			// så den kunde koster en UPDATE og ingen migration.
			return;
		}
		// Kataloget først og i en fast rækkefølge: SELECT DISTINCT giver ingen
		// garanteret orden, og en fejlbesked, der skifter ordlyd mellem to
		// kørsler, er ikke til at skrive en test imod.
		Set<Currency> existing = new LinkedHashSet<>();
		this.events.distinctCurrencies().stream()
				.sorted((one, other) -> one.getCurrencyCode().compareTo(other.getCurrencyCode()))
				.forEach(existing::add);
		if (existing.size() > 1) {
			// Kataloget er ALLEREDE blandet, selv om flaget er sat. Det sker, når
			// flaget slås til igen, efter at kunden har været international. Blev
			// unionen målt her, ville hvert eneste push og hver eneste enrichment
			// blive afvist for evigt, uden nogen vej ud for kunden. Derfor: sættet
			// må ikke VOKSE, men det, der allerede findes, må stadig skrives.
			// Selve flag-skiftet skal gates i operatør-admin (S9), ikke her.
			for (Currency currency : incoming) {
				if (!existing.contains(currency)) {
					throw new CurrencyMismatchException(existing.iterator().next(), currency);
				}
			}
			return;
		}
		Set<Currency> union = new LinkedHashSet<>(existing);
		union.addAll(incoming);
		if (union.size() <= 1) {
			return;
		}
		Currency established = null;
		for (Currency currency : union) {
			if (established == null) {
				established = currency;
			}
			else if (!established.equals(currency)) {
				// Ikke en CatalogException: en valuta-uenighed skal kunne skelnes
				// fra dårligt input hele vejen op. Den skal eskalere, ikke
				// retry'es og ikke logges og fortsættes (CLAUDE.md).
				throw new CurrencyMismatchException(established, currency);
			}
		}
	}

}

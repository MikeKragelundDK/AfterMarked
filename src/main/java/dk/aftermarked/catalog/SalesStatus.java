package dk.aftermarked.catalog;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Om primærsalget på et event er åbent (PLAN §2.6). Værdien driver adfærd: den
 * pauser og genoptager ventelisten, og den afgør, om køberen sendes til kundens
 * eget salg eller til aftermarkedet.
 *
 * <p>Kolonnen {@code event.sales_status} er NOT NULL, og dens CHECK håndhæver
 * kun FORMATET ({@code ^[A-Z][A-Z0-9_]{0,31}$}) — de lovlige værdier står her.
 * Sådan skal det være: en lukket {@code IN}-liste i databasen kan aldrig løsnes
 * igen ({@code MigrationDisciplineInvariantTest} forbyder {@code DROP CONSTRAINT}
 * for altid), mens denne enum kan udvides med en almindelig ændring.
 *
 * <p>Rækken gemmer altid navnet på en af DISSE konstanter. Kundens rå token
 * bevares ikke: den rapporteres i svarets {@code warnings[]} med sin STI, og
 * ingest-servicen logger én samlet WARN pr. push med antallet — tokenen selv
 * logges aldrig. Den lagres som {@link #UNKNOWN}. Det er prisen for, at aggregatet og
 * kolonnen kan udtrykke præcis det samme — alternativet, en nullable kolonne med
 * en {@code Optional}-komponent, betyder at en admin-redigering GENNEM
 * aggregatet skriver NULL og sletter kundens egen værdi i stilhed.
 */
public enum SalesStatus {

	/** Kundens eget primærsalg er åbent. Ventelisten er pauset. */
	ON_SALE,

	/** Udsolgt hos kunden. Det er her aftermarkedet og ventelisten lever. */
	SOLD_OUT,

	/**
	 * Den neutrale fallback (PLAN §2.6, DoD punkt 9). Både et skelet-event fra
	 * lazy enrichment, der aldrig har haft en status, OG en værdi fra kundens
	 * katalog, vi ikke kender ({@code PRESALE}, {@code CANCELLED}), lander her.
	 *
	 * <p>UNKNOWN er ALDRIG "til salg" og genoptager aldrig ventelisten. Det er
	 * den sikre retning: at tro et udsolgt event er åbent sender køberen til en
	 * salgsside uden billetter, mens det modsatte blot lader aftermarkedet stå
	 * åbent.
	 */
	UNKNOWN;

	private static final Logger log = LoggerFactory.getLogger(SalesStatus.class);

	/**
	 * Den ENESTE vej fra en streng til en konstant — både fra kundens payload og
	 * fra en læst række.
	 *
	 * <p>{@code valueOf()} må ikke bruges nogen af de to steder. Fra wire ville
	 * den gøre en ukendt status til en 500 i stedet for et event, vi bare ikke
	 * kender salgsstatus på. Og ved LÆSNING ville den sprænge på en række skrevet
	 * af en nyere replika, der kender en konstant, denne gør ikke — altså præcis
	 * i den forskudte rollout, skemaet ellers er bygget til at overleve.
	 *
	 * <p>Uppercaser med {@link Locale#ROOT} (kunder sender {@code sold_out}); i
	 * tyrkisk lokalitet ville {@code "i"} ellers blive et prikløst I, og
	 * genkendelsen ville afhænge af maskinens sprogindstilling.
	 *
	 * <p>En ukendt værdi logges ALDRIG med sin token: den er kundens data, kan
	 * indeholde linjeskift, og et push med 2.000 events i samme ukendte status
	 * ville give 2.000 linjer. Sporet er svarets {@code warnings[]}, hvor kalderen
	 * ved ingest lægger STIEN som {@code UNKNOWN_ENUM_VALUE}. Her kun en
	 * DEBUG-linje uden værdien. {@code null} er derimod tavs — og kommer aldrig
	 * fra wire: parseren kræver feltet og afviser fravær som
	 * {@code MISSING_REQUIRED_FIELD}, så {@code null} her er en læsning af en
	 * række, ikke en kundes udeladelse.
	 */
	public static SalesStatus fromWire(String value) {
		if (value == null) {
			return UNKNOWN;
		}
		SalesStatus recognised = recognise(value);
		if (recognised == null) {
			log.debug("Unrecognised sales status token of {} characters in catalog data; storing UNKNOWN",
					value.length());
			return UNKNOWN;
		}
		return recognised;
	}

	/**
	 * Om værdien er en, vi kender — uden at logge og uden at kaste.
	 *
	 * <p>Findes, fordi {@link #fromWire} med vilje giver {@link #UNKNOWN} for
	 * BÅDE vores egen token {@code "UNKNOWN"} og for en værdi, vi aldrig har
	 * hørt om: returværdien alene kan derfor ikke afgøre, om der skal en linje i
	 * ingest-svarets {@code warnings[]}. Uden denne metode ville kalderen skulle
	 * gentage normaliseringen ({@code strip} + {@link Locale#ROOT}-uppercase) ved
	 * siden af sin egen sammenligning — to stavemåder af den samme regel, der før
	 * eller siden kommer fra hinanden.
	 *
	 * <p>{@code null} er IKKE genkendt, men er heller ikke noget at advare om: så
	 * har kunden slet ikke sendt en status, og {@link #fromWire} er tavs af samme
	 * grund. Spørg derfor kun, når feltet faktisk står i payloaden.
	 */
	public static boolean isRecognised(String value) {
		return value != null && recognise(value) != null;
	}

	/**
	 * Den ENE normalisering og den ENE tabel over kendte tokens. {@code null}
	 * betyder "ikke en værdi, vi kender" — og det er kalderens valg, om det
	 * fortjener en advarsel i svaret eller bare et {@code false}.
	 */
	private static SalesStatus recognise(String value) {
		// Locale.ROOT og ikke maskinens egen: i tyrkisk lokalitet bliver "i" til
		// et prikløst I, og genkendelsen ville afhænge af sprogindstillingen.
		return switch (value.strip().toUpperCase(Locale.ROOT)) {
			case "ON_SALE" -> ON_SALE;
			case "SOLD_OUT" -> SOLD_OUT;
			// Vores egen neutrale token. Den kommer tilbage fra hver eneste
			// læsning af et skelet-event, så den må ikke støje i loggen.
			case "UNKNOWN" -> UNKNOWN;
			default -> null;
		};
	}

	/**
	 * Om primærsalget er åbent.
	 *
	 * <p>Findes som metode og ikke som et {@code == ON_SALE} ude i kalderne,
	 * fordi en tredje konstant en dag gør hvert af de kaldsteder til et sted, der
	 * kan glemmes. Her står reglen ét sted: alt andet end {@link #ON_SALE} er
	 * ikke til salg — {@link #UNKNOWN} inklusive.
	 */
	public boolean isOnSale() {
		return this == ON_SALE;
	}

}

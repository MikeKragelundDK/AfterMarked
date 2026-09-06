package dk.aftermarked.invariant.migration;

import java.lang.reflect.RecordComponent;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * TESTHJÆLPER, ikke en test. Den erstatter det, {@code ddl-auto=validate} gjorde
 * før PLAN §3: den holder et aggregat-record op mod sin tabel <em>begge veje</em>,
 * så hverken en glemt kolonne i et {@code INSERT} eller et felt uden en kolonne
 * kan slippe igennem.
 *
 * <p><strong>Sammenligningen SUBTRAHERER, den ADDERER aldrig.</strong> Formen er
 * {@code columnsOf(tabel) MINUS DATABASE_OWNED == columnsCoveredBy(records)}.
 * Grunden er {@code operator}: den tabel har ingen {@code operator_id}-kolonne,
 * for dens tenant-nøgle ER dens {@code id}. En hjælper, der ubetinget lagde
 * tenant-kolonnen til det forventede sæt, ville derfor gøre
 * {@code OperatorSchemaInvariantTest} rød fra dag ét — og den fejl ville se ud
 * som om recorden manglede et felt.
 *
 * <p><strong>Pr. TABEL, mod UNIONEN af de records der mapper den.</strong> En
 * tabel behøver ikke svare til ét record: {@code event} dækkes af det offentlige
 * {@code Event} plus {@code catalog.internal.CustomFieldColumns}, som er den
 * eneste type, der ser {@code custom_fields}. Union-reglen er ikke bekvemmelighed
 * — den er dét, der holder invarianten om feltisolation i live. Den nærliggende
 * "rettelse" af en fejlende event-skema-test er nemlig at føje en
 * {@code customFields}-komponent til det <em>offentlige</em> {@code Event}, og i
 * samme sekund kan ethvert modul med {@code allowedDependencies = {"catalog"}}
 * læse kundens feltværdier gennem en type, det lovligt må se (PLAN §2.6, DoD
 * punkt 4). <strong>Gør det aldrig.</strong> Findes der en kolonne, ingen record
 * dækker, hører den til i et record i {@code internal} — ikke i det offentlige
 * aggregat.
 *
 * <p>Hjælperen er offentlig, fordi skema-testene ligger i flere pakker under
 * {@code invariant} ({@code migration}, {@code audit}, {@code tenancy}) og skal
 * regne kolonner ud på præcis samme måde. Én forkert håndrullet kopi af den
 * regel er nok til at gøre en af dem grøn af den forkerte grund.
 */
public final class AggregateSchema {

	/** Kolonner databasen ejer, og som med vilje IKKE findes på noget record. */
	public static final Set<String> DATABASE_OWNED = Set.of("operator_id");

	/**
	 * Værdityper, der fylder mere eller mindre end én kolonne, slået op på
	 * <em>simpelt typenavn</em>. Med vilje uden en compile-tids-afhængighed til
	 * {@code money}, {@code catalog} og {@code audit}: hjælperen skal kunne
	 * oversættes alene, også før de moduler findes, og en tabel er alligevel
	 * altid navngivet med en streng her.
	 *
	 * <p>Funktionen får komponentens navn, så navnet kan indgå i kolonnenavnet:
	 * et {@code Money faceValue} bliver til {@code face_value_minor} plus
	 * rækkens delte {@code currency} (beløb er heltal i mindste enhed + valuta,
	 * CLAUDE.md). {@code AuditActor} bliver derimod altid til det samme par,
	 * fordi {@code audit_log} kun har ét aktør-felt.
	 *
	 * <p>Skal en ny værditype ind, hører den her — ikke i en {@code if} ude i en
	 * test. En {@code java.util.Currency} står med vilje IKKE på listen: den
	 * fylder præcis én kolonne og dækkes af standardreglen.
	 */
	private static final Map<String, Function<String, Set<String>>> SPANNING_TYPES = Map.of(
			"Money", component -> Set.of(snake(component) + "_minor", "currency"),
			"AuditActor", component -> Set.of("actor_type", "actor_ref"));

	private AggregateSchema() {
	}

	/**
	 * Kolonnerne, de opgivne records tilsammen dækker. Standardreglen er
	 * {@code snake(komponentnavn)}, og den gælder også en-komponent-wrappere som
	 * {@code OperatorId}, {@code EventRef}, {@code OrganizerRef} og
	 * {@code TicketTypeRef}: de findes for at gøre {@code upsert(organizerRef,
	 * eventRef)} umulig at kalde med byttede argumenter, ikke for at ændre
	 * skemaet.
	 *
	 * <p>Et record med flere komponenter, der ikke står i {@link #SPANNING_TYPES},
	 * er derimod en fejl frem for et gæt. Uden den regel ville et {@code Money}
	 * stilfærdigt blive til den ene kolonne {@code face_value}, og skema-testen
	 * ville rapportere en manglende kolonne i stedet for den manglende
	 * registrering, der er den egentlige fejl.
	 */
	public static Set<String> columnsCoveredBy(Class<?>... records) {
		Set<String> columns = new TreeSet<>();
		for (Class<?> type : records) {
			if (!type.isRecord()) {
				throw new IllegalArgumentException(
						type.getSimpleName() + " is not a record; aggregates are records (PLAN 3)");
			}
			for (RecordComponent component : type.getRecordComponents()) {
				columns.addAll(columnsFor(component));
			}
		}
		return columns;
	}

	/**
	 * Tabellens kolonner, som den rolle testen kører som kan se dem. Sættet er
	 * friskt og sorteret ved hvert kald, så kalderen frit kan trække
	 * {@link #DATABASE_OWNED} ud af det.
	 *
	 * <p>{@code information_schema.columns} viser kun kolonner, rollen har en
	 * rettighed på. Et tomt svar betyder derfor enten en stavefejl i tabelnavnet
	 * eller en manglende {@code GRANT} — begge dele ville ellers se ud som "hele
	 * recorden mangler i databasen", så det fejler højlydt her i stedet.
	 */
	public static Set<String> columnsOf(JdbcClient jdbc, String table) {
		Set<String> columns = new TreeSet<>(jdbc.sql("""
				SELECT column_name FROM information_schema.columns
				 WHERE table_schema = 'public' AND table_name = :table
				""").param("table", table).query(String.class).list());
		if (columns.isEmpty()) {
			throw new IllegalStateException(
					"No columns visible on table " + table + " — wrong table name, or the role has no privilege on it");
		}
		return columns;
	}

	private static Set<String> columnsFor(RecordComponent component) {
		Class<?> type = component.getType();
		Function<String, Set<String>> spanning = SPANNING_TYPES.get(type.getSimpleName());
		if (spanning != null) {
			return spanning.apply(component.getName());
		}
		if (type.isRecord() && type.getRecordComponents().length != 1) {
			throw new IllegalArgumentException("Value type " + type.getSimpleName() + " on component "
					+ component.getName() + " spans an unknown number of columns; register it in AggregateSchema");
		}
		return Set.of(snake(component.getName()));
	}

	/** {@code customFieldsSchemaVersion} bliver til {@code custom_fields_schema_version}. */
	private static String snake(String name) {
		return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
	}

}

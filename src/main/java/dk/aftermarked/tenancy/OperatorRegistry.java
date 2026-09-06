package dk.aftermarked.tenancy;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Opslag af operatører. To metoder, med hver sin sikkerhedsmodel:
 *
 * <ul>
 *   <li>{@link #findByHost} kører <em>uden</em> operator-kontekst og går gennem
 *       SECURITY DEFINER-funktionen {@code operator_id_for_host}. Det er det ene
 *       sted, appen slår op på tværs af tenants — og den kan kun få ét uuid ud
 *       af det, aldrig en række.</li>
 *   <li>{@link #current} læser {@code operator}-tabellen gennem RLS og får
 *       derfor præcis den række, konteksten giver adgang til.</li>
 * </ul>
 */
@Component
public class OperatorRegistry {

	private final JdbcClient jdbc;

	OperatorRegistry(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Operatøren bag et værtsnavn (uden port), eller tom hvis værtsnavnet er ukendt. */
	public Optional<OperatorId> findByHost(String host) {
		if (host == null || host.isBlank()) {
			return Optional.empty();
		}
		return jdbc.sql("SELECT operator_id_for_host(:host)")
				.param("host", host)
				.query(UUID.class)
				.optional()
				.map(OperatorId::new);
	}

	/**
	 * Den aktuelle operatør, som RLS ser den. Fejler hvis ingen kontekst er bundet.
	 *
	 * <p>Der filtreres på {@code id} ud over RLS — bevidst dobbelt. RLS er
	 * sandheden, men de to kan nå at pege forskellige steder hen: konteksten
	 * lever på tråden, mens {@code app.operator_id} sættes ved
	 * forbindelses-checkout. Et {@code runAs} inde i en allerede åben
	 * transaktion flytter tråd-konteksten uden at flytte forbindelsens. Uden
	 * {@code WHERE id} ville metoden dér returnere den FORRIGE operatørs række
	 * og kalde den den aktuelle. Med filteret bliver uenigheden i stedet en
	 * højlydt fejl.
	 *
	 * <p>To detaljer i mappingen, som begge er stille fejlkilder:
	 * {@code default_locale} læses med {@link Locale#forLanguageTag(String)} og
	 * ikke {@code Locale.of(...)}, fordi kolonnen bærer et sprogtag
	 * ({@code en-GB}) og ikke et sprog plus et land — {@code Locale.of("en-GB")}
	 * ville give et sprog, der hedder "en-gb". Og {@code created_at} læses som
	 * {@link OffsetDateTime}, fordi {@code getTimestamp} ville lægge JVM'ens
	 * standardzone ned over et tidspunkt, databasen har gemt i UTC.
	 */
	public Operator current() {
		OperatorId id = OperatorContext.required();
		return jdbc.sql("""
				SELECT id, slug, name, hostname, default_locale, single_currency, created_at
				  FROM operator WHERE id = :id
				""")
				.param("id", id.value())
				.query((rs, row) -> new Operator(
						new OperatorId(rs.getObject("id", UUID.class)),
						rs.getString("slug"),
						rs.getString("name"),
						rs.getString("hostname"),
						Locale.forLanguageTag(rs.getString("default_locale")),
						rs.getBoolean("single_currency"),
						rs.getObject("created_at", OffsetDateTime.class).toInstant()))
				.optional()
				.orElseThrow(() -> new IllegalStateException(
						"Operator " + id + " is in context, but RLS returned no row"));
	}

}

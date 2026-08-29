package dk.aftermarked.tenancy;

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
	 */
	public Operator current() {
		OperatorId id = OperatorContext.required();
		return jdbc.sql("SELECT id, slug, name, hostname FROM operator WHERE id = :id")
				.param("id", id.value())
				.query((rs, row) -> new Operator(
						new OperatorId(rs.getObject("id", UUID.class)),
						rs.getString("slug"),
						rs.getString("name"),
						rs.getString("hostname")))
				.optional()
				.orElseThrow(() -> new IllegalStateException(
						"Operator " + id + " is in context, but RLS returned no row"));
	}

}

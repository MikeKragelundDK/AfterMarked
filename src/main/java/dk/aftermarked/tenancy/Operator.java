package dk.aftermarked.tenancy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;

/**
 * Operatøren som læst fra databasen — kun den række, RLS lader os se.
 *
 * <p>{@code defaultLocale} er operatørens <em>eget</em> basissprog (PLAN §2.9),
 * ikke den globale {@code LocalizationConfig.DEFAULT_LOCALE}. Forskellen er
 * ikke akademisk: den flersprogede importform skal indeholde netop dette sprog,
 * og målt mod den globale konstant ville en svensk kundes fuldt gyldige
 * {@code {"sv": …, "en": …}} blive afvist for ikke at indeholde dansk. Sproget
 * her er kundens, ikke husets.
 *
 * <p>{@code singleCurrency} er politikken "alle events hos DENNE operatør deler
 * valuta" (PLAN §2.5). Den ligger som data og ikke som en constraint, fordi den
 * internationale kunde så koster en {@code UPDATE} frem for en migration — hele
 * begrundelsen står i V4.
 *
 * <p><strong>{@code createdAt} trunkeres til mikrosekunder i den kompakte
 * konstruktør.</strong> Postgres' {@code timestamptz} gemmer mikrosekunder, og
 * pgjdbc runder HALV-OP, så en {@code Instant} med nanosekunder ikke kommer
 * tilbage som den blev skrevet. Trunkeringen hører HER og ikke i round-trip-testen:
 * lægges den i testen, er det testen, der bliver flaky i omtrent halvdelen af
 * kørslerne, mens koden fortsat sammenligner to værdier, der ikke er ens.
 */
public record Operator(OperatorId id, String slug, String name, String hostname,
		Locale defaultLocale, boolean singleCurrency, Instant createdAt) {

	public Operator {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(slug, "slug");
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(hostname, "hostname");
		Objects.requireNonNull(defaultLocale, "defaultLocale");
		Objects.requireNonNull(createdAt, "createdAt");
		createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
	}

}

package dk.aftermarked.audit;

import java.util.List;

/**
 * Skrive-porten til audit-loggen. Det er den ENESTE måde et modul skriver en
 * linje, og den har med vilje ingen læsemetoder: operatør-admins to opslag
 * (alt om én entitet, og det seneste først) hører i den sprint, der bygger
 * siden, og en læsesti uden kalder er stillads.
 *
 * <p><strong>Kun nye linjer.</strong> Der er hverken {@code update} eller
 * {@code delete}, og det er ikke en forglemmelse: V5 trækker {@code UPDATE} og
 * {@code DELETE} på {@code audit_log} tilbage fra applikationens rolle, så en
 * metode her ville alligevel fejle med {@code permission denied}. En linje, der
 * er skrevet, står. En linje, der ikke skulle have været skrevet, rettes med en
 * NY linje.
 *
 * <p><strong>Skrivningen deler transaktion med kalderen.</strong> Det er hele
 * pointen: ruller katalog-pushet tilbage, forsvinder audit-linjerne med det, og
 * loggen kommer aldrig til at påstå en ændring, databasen ikke har. Omvendt
 * betyder det, at en fejlet skrivning HER vælter kalderens transaktion — og
 * det er det rigtige: en tilstandsovergang, der ikke kunne logges, skal ikke
 * committes i stilhed.
 *
 * <p><strong>Batch frem for løkke.</strong> Et katalog-push skriver én linje
 * pr. ændret række; et førstedump af 5.000 events ville være 5.000 sætninger
 * inde i netop den transaktion, der også holder skrivestiens advisory-lås.
 * {@link #recordAll} er derfor ÉN sætning uanset antal, og {@link #record} er
 * bekvemmeligheden ved siden af.
 */
public interface AuditLog {

	/**
	 * Skriver én linje. Kræver en bundet operatør-kontekst — linjens
	 * {@code operator_id} kommer fra {@code current_operator_id()} og aldrig fra
	 * et argument.
	 *
	 * @throws IllegalStateException    hvis der ikke er nogen operatør i
	 *                                  konteksten
	 * @throws IllegalArgumentException hvis linjen allerede er skrevet, dvs.
	 *                                  bærer et id eller et tidspunkt fra
	 *                                  databasen (se {@link AuditEntry#of})
	 */
	void record(AuditEntry entry);

	/**
	 * Skriver alle linjerne i én sætning, i listens rækkefølge. En tom liste er
	 * lovlig og rører ikke databasen.
	 *
	 * @throws IllegalStateException    hvis der ikke er nogen operatør i
	 *                                  konteksten
	 * @throws IllegalArgumentException hvis en af linjerne allerede er skrevet
	 */
	void recordAll(List<AuditEntry> entries);

}

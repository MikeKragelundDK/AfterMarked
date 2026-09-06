/**
 * Audit-loggen: hvem gjorde hvad hvornår (PLAN §2.3). Alle tilstandsovergange og
 * alle admin-handlinger ender her, og operatør-admin læser dem to veje — alt om
 * én entitet, og det seneste først.
 *
 * <p>Modulet er et BLAD. Det kender kun uigennemsigtige id'er og refs og har
 * ingen mening om, hvad en {@code event} eller en {@code listing} er. Derfor kan
 * hvert modul skrive til loggen uden at nogen skriver tilbage: afhængigheden går
 * kun én vej, og et nyt modul i S4 tilføjer ingen kant hertil.
 *
 * <p>{@code tenancy} er den eneste tilladte afhængighed, fordi hver linje bærer
 * {@code operator_id} og skrives under RLS. Der er med vilje ingen afhængighed
 * til {@code catalog} eller {@code money}: kunne loggen holde et aggregat, ville
 * en ændring af aggregatet ændre betydningen af en linje, der allerede er
 * skrevet — og en log, der kan omfortolkes bagudrettet, er ikke en log.
 *
 * <p>Loggen er append-only for applikationen ({@code REVOKE UPDATE, DELETE} i
 * V5), ikke for tabelejeren. Ægte manipulationsbevis kræver en hash-kæde, og den
 * er krypto-nær — altså en egen stop-zone-beslutning.
 */
@ApplicationModule(displayName = "Audit", allowedDependencies = { "tenancy" })
package dk.aftermarked.audit;

import org.springframework.modulith.ApplicationModule;

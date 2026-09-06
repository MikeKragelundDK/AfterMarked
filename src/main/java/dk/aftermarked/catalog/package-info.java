/**
 * Kundens katalog: arrangører, events og billettyper, spejlet fra kundens eget
 * system (PLAN §2.2/§2.6). Ejerskabet af data ligger hos kunden — vi holder en
 * kopi til visning og regler.
 *
 * <p>Den pligtige kerne er offentlig i denne pakke. Custom felter — skema OG
 * værdier — ligger bag den navngivne grænseflade {@code fields}, fordi de er
 * præsentation og aldrig adfærd: penge-, saga- og allokeringsmodulerne må aldrig
 * kunne læse dem (PLAN §2.6, DoD punkt 4).
 *
 * <p><strong>Vigtigt:</strong> {@code Event} bærer med vilje INGEN komponent med
 * feltværdier, og payload-typerne for {@code PUT /catalog} ligger i
 * {@code internal} — de bærer feltværdier, og lå de her, kunne ethvert modul med
 * {@code allowedDependencies = {"catalog"}} nå feltlageret gennem den unavngivne
 * grænseflade, uden at ArchUnit-reglen nogensinde fyrede.
 *
 * <p>Modulet er et BLAD mod provider og marketplace: det kalder aldrig ud.
 * Sprint 3 tilføjer pull-porten HER, og {@code provider} implementerer den;
 * lazy enrichment kaldes af den, der har kaldt verify. Vendte pilen, ville
 * Modulith's cyklus-tjek gå rødt.
 */
@ApplicationModule(displayName = "Catalog",
		allowedDependencies = { "tenancy", "money", "localization", "audit" })
package dk.aftermarked.catalog;

import org.springframework.modulith.ApplicationModule;

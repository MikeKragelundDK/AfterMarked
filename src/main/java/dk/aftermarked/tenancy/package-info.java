/**
 * Tenant-isolation: hvem er operatøren i dette request eller job, og hvordan
 * når det ned i databasen (PLAN §2.7, "Tenant-isolation — disciplinen").
 *
 * Modulet er ren infrastruktur og må ikke afhænge af forretningsmoduler.
 * Alt uden for denne pakke ({@code internal}) er skjult for de andre moduler.
 */
@ApplicationModule(displayName = "Tenancy", allowedDependencies = {})
package dk.aftermarked.tenancy;

import org.springframework.modulith.ApplicationModule;

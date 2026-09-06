/**
 * Listings, tilstandsmaskine, venteliste og scoping.
 *
 * <p>Listen er LUKKET — og det er en BEVIDST stramning ud over sprintets ordlyd,
 * som kun nævner orders, payments og allokering. Grunden er, hvad der lander her
 * i S4: {@code FeePolicy} og {@code PriceCapPolicy} er penge-kode og hører under
 * penge-stop-zonen, uanset hvilket modul de bor i. Vente-med-at-lukke ville
 * betyde, at listen først blev skrevet i det sprint, hvor den skulle bevise
 * noget.
 *
 * <p>{@code catalog::fields} står med vilje IKKE på listen: prisloftet regnes af
 * face value fra verify, aldrig af et custom felt (PLAN §2.6).
 */
@ApplicationModule(displayName = "Marketplace", allowedDependencies = { "tenancy", "money", "catalog" })
package dk.aftermarked.marketplace;

import org.springframework.modulith.ApplicationModule;

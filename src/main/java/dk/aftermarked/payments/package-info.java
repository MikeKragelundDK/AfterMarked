/**
 * Stripe Connect, PayoutPlans og refunderinger.
 *
 * <p>Listen er LUKKET. Alt her er penge-stop-zone: split-beregning, payout-hold,
 * refunds og transfer reversals. {@code money} er beløbstypen, {@code catalog}
 * giver face value til prisloftet, og {@code tenancy} er den kontekst, RLS
 * hviler på — mere er der ikke brug for.
 *
 * <p>{@code catalog::fields} står med vilje IKKE på listen: et custom felt må
 * aldrig kunne påvirke et beløb (PLAN §2.6).
 */
@ApplicationModule(displayName = "Payments", allowedDependencies = { "tenancy", "money", "catalog" })
package dk.aftermarked.payments;

import org.springframework.modulith.ApplicationModule;

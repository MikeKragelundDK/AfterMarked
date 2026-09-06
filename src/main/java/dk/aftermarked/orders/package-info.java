/**
 * Købs-sagaen, reservationer og eskaleringskø.
 *
 * <p>Listen er LUKKET. Modulet er den ene stop-zone, hvor en fejl koster rigtige
 * penge: sagaen betaler før den bytter, og fejler byttet, refunderes køberen.
 * En afhængighed, der sniger sig ind, er en ny vej ind i det forløb.
 *
 * <p>{@code catalog} står med, fordi sagaen skal kende event og billettype —
 * men KUN den pligtige kerne. Den navngivne grænseflade {@code catalog::fields}
 * er med vilje IKKE på listen: custom felter er præsentation og aldrig adfærd,
 * og ét felt, der kunne påvirke pris eller tilstand, ville give N pengeveje
 * (PLAN §2.6). {@code CustomFieldIsolationInvariantTest} håndhæver det samme fra
 * den anden side.
 */
@ApplicationModule(displayName = "Orders", allowedDependencies = { "tenancy", "money", "catalog" })
package dk.aftermarked.orders;

import org.springframework.modulith.ApplicationModule;

/**
 * Beløb: heltal i mindste enhed + ISO-4217-kode. Ét beløb findes aldrig uden sin
 * valuta (PLAN §2.5, CLAUDE.md).
 *
 * <p>Ren værditype. {@code allowedDependencies = {}} er hele pointen: modulet må
 * ikke kunne komme til at kende hverken en tenant, et katalog eller en saga — for
 * så ville nogen før eller siden lade et beløb afhænge af dem, og et beløb, der
 * afhænger af en kontekst, er et beløb, der kan blive et andet beløb undervejs.
 *
 * <p>Modulet er dermed et blad i afhængighedsgrafen. Det er også grunden til, at
 * typerne ligger her og ikke i rodpakken: en type i rodpakken er usynlig for
 * enhver af Modulith's afhængighedsregler, så en tom liste her er en reel
 * garanti, hvor rodpakken kun ville være en hensigt.
 */
@ApplicationModule(displayName = "Money", allowedDependencies = {})
package dk.aftermarked.money;

import org.springframework.modulith.ApplicationModule;

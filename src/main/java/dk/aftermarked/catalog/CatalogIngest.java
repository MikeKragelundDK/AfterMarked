package dk.aftermarked.catalog;

/**
 * Den offentlige port ind i kataloget. Kaldes af marketplace i S4, når en sælger
 * har fået sin billet verificeret.
 *
 * <p>Porten bærer med vilje KUN {@link #enrich(VerifiedTicket)}. Push-porten —
 * den, kundens natlige katalog-dump går igennem — er intern, fordi dens payload
 * bærer feltværdier: lå den her, kunne ethvert modul med
 * {@code allowedDependencies = {"catalog"}} nå feltlageret gennem den unavngivne
 * grænseflade, uden at ArchUnit-reglen på {@code catalog.fields} nogensinde
 * fyrede (PLAN §2.6, DoD punkt 4).
 *
 * <p>Retningen er også bevidst: {@code catalog} er et BLAD mod {@code provider}
 * og {@code marketplace} og kalder aldrig ud. Lazy enrichment kaldes af den, der
 * har kaldt verify — kataloget spørger ikke selv. Vendte pilen, ville Modulith's
 * cyklus-tjek gå rødt.
 */
public interface CatalogIngest {

	/**
	 * Sikrer, at eventet og dets billettype findes, og returnerer eventet.
	 *
	 * <p>Dette er GULVET under kataloget, ikke en strategi (PLAN §2.2): en kunde,
	 * der aldrig pusher et katalog, kan stadig få en listing, fordi den første
	 * verificerede billet skaber de rækker, der skal til.
	 *
	 * <p>Der skrives TO rækker: eventet OG dets billettype. Verify-svaret bærer
	 * begge, og en S4-listing refererer en billettype — et skelet uden en er
	 * ubrugeligt.
	 *
	 * <p>Findes eventet i forvejen, valideres verify-svarets valuta mod eventets,
	 * og en afvigelse afvises. Findes det ikke, oprettes det med
	 * {@code title} = ref, {@code sales_status} = {@link SalesStatus#UNKNOWN} og
	 * {@code primary_purchase_url} = NULL. Det er præcis derfor lazy enrichment
	 * er et gulv og ikke en erstatning for kundens katalog: ventelisten afhænger
	 * af netop de to felter, og UNKNOWN er aldrig "til salg". Hænger eventet ikke
	 * på en arrangør, syntetiseres den reserverede
	 * {@link OrganizerRef#RESERVED_DEFAULT}-arrangør (PLAN §2.3).
	 *
	 * <p><strong>{@code required: true}-custom-felter gælder IKKE her</strong> —
	 * kun ved katalog-push. Ellers ville én påkrævet feltdeklaration i kundens
	 * feltskema gøre den første listing hos den kunde umulig: et verify-svar
	 * kender ingen feltværdier og kan aldrig komme til at bære dem. Et
	 * skelet-event skrives derfor med et tomt feltdokument, og pushet udfylder
	 * det senere.
	 *
	 * <p>Metoden er idempotent: den skriver med
	 * {@code ON CONFLICT ... DO NOTHING} og overskriver ALDRIG katalogdata. Den
	 * modsatte vej er tilladt — katalog-pushet er den fulde upsert og overskriver
	 * gerne et skelet.
	 *
	 * @param verified verify-svaret, oversat til katalogets sprog
	 * @return eventet, som det står efter kaldet — nyt eller allerede kendt
	 * @throws dk.aftermarked.money.CurrencyMismatchException hvis eventet findes i
	 *     en anden valuta, end verify svarede i, eller hvis operatøren kører
	 *     enkelt-valuta og kataloget ville få sin anden valuta
	 * @throws CatalogException hvis rækkerne ikke kunne skrives
	 * @throws IllegalArgumentException hvis verify-svarets face value er ugyldigt
	 *     for eventet ud over valutaen (fx negativt) — {@code Event.acceptFaceValue}
	 *     kaster, og transaktionen ruller et netop skrevet skelet tilbage
	 */
	Event enrich(VerifiedTicket verified);

}

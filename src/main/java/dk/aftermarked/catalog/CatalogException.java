package dk.aftermarked.catalog;

import java.io.Serial;

/**
 * En katalog-operation kunne ikke gennemføres.
 *
 * <p>Egen basistype, så en kalder kan fange præcis katalogets fejl. Uden den
 * ville alternativet være at fange {@code RuntimeException} — og et bredt
 * {@code catch} i den kode, der senere ligger ved siden af købs-sagaen, er
 * netop dét CLAUDE.md forbyder: en slugt fejl i penge- eller sagakode er tabte
 * penge.
 *
 * <p>Typen er UDVIKLERVENDT. Beskeden er engelsk, den ender i logs, og den vises
 * aldrig til en bruger — brugervendt tekst kommer fra en nøgle i
 * {@code messages*.properties} (CLAUDE.md). Af samme grund bærer beskeden aldrig
 * kundens egne værdier: de kan være persondata eller 20 KB richtext.
 *
 * <p>{@link CatalogValidationException} er den ene specialisering, der findes i
 * dag: kundens payload var forkert, hvilket er kalderens fejl at rette og bliver
 * til en 422. Alt andet — en række, der ikke kunne skrives — er vores.
 *
 * <p>Bemærk, at valuta-uenighed IKKE er en {@code CatalogException}. Den er en
 * {@link dk.aftermarked.money.CurrencyMismatchException}, fordi den skal kunne
 * skelnes fra dårligt input hele vejen op: to valutaer, der mødes inde i vores
 * egen aritmetik, er en programfejl eller korrupte data og skal eskalere til et
 * menneske.
 */
public class CatalogException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	public CatalogException(String message) {
		super(message);
	}

	public CatalogException(String message, Throwable cause) {
		super(message, cause);
	}

}

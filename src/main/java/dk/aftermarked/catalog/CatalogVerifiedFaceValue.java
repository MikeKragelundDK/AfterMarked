package dk.aftermarked.catalog;

import java.util.Objects;
import java.util.UUID;

import dk.aftermarked.money.Money;

/**
 * Den ENESTE implementation af {@link VerifiedFaceValue} — package-private med
 * vilje.
 *
 * <p>Typen er offentlig som grænseflade og skjult som konstruktion: et andet
 * modul kan holde, læse og videregive en {@code VerifiedFaceValue}, men kun kode
 * i {@code dk.aftermarked.catalog} kan lave en, og i praksis kun
 * {@link Event#acceptFaceValue(Money)}. Skal der en dag mintes et beløb et andet
 * sted, er det en ændring HER — og dermed en, der bliver reviewet som den
 * ændring af invariant nr. 1, den er.
 *
 * <p>Klassen må aldrig gøres public, og der må aldrig komme en implementation
 * mere. {@code VerifiedFaceValueInvariantTest} går rødt på begge dele.
 */
record CatalogVerifiedFaceValue(UUID eventId, Money amount) implements VerifiedFaceValue {

	CatalogVerifiedFaceValue {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(amount, "amount");
	}

}

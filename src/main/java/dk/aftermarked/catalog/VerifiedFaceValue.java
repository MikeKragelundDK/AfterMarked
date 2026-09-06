package dk.aftermarked.catalog;

import java.util.UUID;

import dk.aftermarked.money.Money;

/**
 * Et face value, der ER tjekket mod et event (SPRINTS S2, invariant nr. 1).
 *
 * <p>Face value fra verify er autoriteten for prisloftet på den konkrete billet;
 * katalogets eget tal er visning og default og kan aldrig overskrive det
 * (PLAN §2.2). Listing-koden i S4 tager derfor en {@code VerifiedFaceValue} og
 * ALDRIG et {@link Money} — og en {@code VerifiedFaceValue} kan kun opstå ét
 * sted: {@link Event#acceptFaceValue(Money)}.
 *
 * <p><strong>Hvorfor en forseglet grænseflade og ikke bare en record.</strong>
 * Formen skal give "enhver må HOLDE typen, kun {@code catalog} må MØNTE den". En
 * public record med package-private kanonisk konstruktør ville være det oplagte
 * — men den KOMPILERER IKKE: JLS §8.10.4 kræver, at en records kanoniske
 * konstruktør ikke er mindre tilgængelig end recorden selv
 * (<em>"attempting to assign stronger access privileges; was public"</em>). Med
 * en forseglet grænseflade og en package-private implementation er invarianten i
 * stedet en compile-fejl at omgå, ikke en runtime-kontrol nogen kan glemme.
 *
 * <p><strong>Forbrugeren SKAL sammenligne {@link #eventId()} med sit eget
 * event-id.</strong> Typen beviser, at beløbet er tjekket mod ET event — ikke
 * mod DETTE. Med {@code operator.single_currency} slået fra kan en operatør have
 * både et DKK- og et EUR-event, og en VerifiedFaceValue møntet fra det ene ville
 * tilfredsstille compileren i den andens prisloft. Compileren beviser at
 * kontrollen er UDFØRT; kun kalderen kan bevise at den var den rigtige.
 */
public sealed interface VerifiedFaceValue permits CatalogVerifiedFaceValue {

	/** Eventet, beløbet blev tjekket imod. Sammenlign det med dit eget. */
	UUID eventId();

	/** Beløbet, verify svarede med — i eventets valuta, ellers fandtes typen ikke. */
	Money amount();

}

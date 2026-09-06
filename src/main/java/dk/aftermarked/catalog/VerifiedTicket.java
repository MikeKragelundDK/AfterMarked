package dk.aftermarked.catalog;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import dk.aftermarked.money.Money;

/**
 * Alt, et verify-svar kan bidrage med til kataloget — inputtet til lazy
 * enrichment (PLAN §2.2).
 *
 * <p>Sælgeren indtaster sin billetreference, provideren svarer, og svaret bærer
 * netop disse fem ting: hvilket event og hvilken billettype billetten hører til,
 * hvad billettypen hedder, hvad den kostede, og hvornår eventet starter. Det er
 * gulvet under kataloget: en kunde, der aldrig pusher et katalog, får alligevel
 * de rækker, en listing kræver.
 *
 * <p><strong>Typen bærer INGEN custom felter</strong> — derfor må den ligge på
 * den offentlige grænseflade. Et verify-svar kender ingen feltdeklarationer, og
 * payload-typerne for katalog-pushet, som gør, ligger i {@code catalog.internal}
 * (PLAN §2.6, DoD punkt 4).
 *
 * <p>Den bærer heller ingen operatør. Tenanten kommer fra værtsnavnet ved
 * request-indgangen og aldrig fra en payload — en komponent her ville være
 * præcis den kodesti, RLS-designet er bygget for at undgå.
 *
 * <p>{@code faceValue}'s valuta valideres mod eventets, når rækken skrives:
 * findes eventet i forvejen, afvises en afvigelse, og findes det ikke, bliver
 * verify-svarets valuta eventets. Se {@link Event#acceptFaceValue(Money)} for
 * den vej, prisloftet går.
 */
public record VerifiedTicket(EventRef eventRef, TicketTypeRef ticketTypeRef,
		String ticketTypeName, Money faceValue, Instant eventStartsAt) {

	public VerifiedTicket {
		Objects.requireNonNull(eventRef, "eventRef");
		Objects.requireNonNull(ticketTypeRef, "ticketTypeRef");
		Objects.requireNonNull(ticketTypeName, "ticketTypeName");
		Objects.requireNonNull(faceValue, "faceValue");
		Objects.requireNonNull(eventStartsAt, "eventStartsAt");
		// Trunkeres her og ikke i testen: værdien lander i event.starts_at, som
		// er en timestamptz med MIKROsekunders opløsning, og pgjdbc runder
		// HALV-OP. Ellers ville en test, der holder et skelet-events starts_at
		// op mod det VerifiedTicket, der skabte det, være flaky i ca. 50 % af
		// tilfældene.
		eventStartsAt = eventStartsAt.truncatedTo(ChronoUnit.MICROS);
	}

}

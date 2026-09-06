package dk.aftermarked;

import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Applikationens ur. Én bønne, så tiden kan skiftes ud i en test i stedet for at
 * blive læst fra {@code Instant.now()} spredt ud over koden — og så hvert modul,
 * der stempler en række, stempler den med den samme opløsning.
 *
 * <p>Opløsningen er mikrosekunder, fordi det er præcis dét, {@code timestamptz}
 * kan gemme. Uden trunkeringen bliver et {@link java.time.Instant} med
 * nanosekunder rundet HALVT OP af pgjdbc på vej ned i databasen, og et
 * {@code WHERE created_at = :ts} med det utrunkerede {@code Instant} rammer
 * derefter nul rækker — i stilhed, fordi nul rækker ikke er en fejl.
 * Trunkeringen sker altså ét sted i stedet for at skulle huskes ved hver
 * skrivning, der IKKE går gennem en kompakt konstruktør.
 *
 * <p>Modulith's egen event-registrering henter selv en {@link Clock} gennem en
 * {@code ObjectProvider}, så outbox-rækkernes tidsstempler arver den samme
 * opløsning. Det er en gevinst, ikke en sidevirkning: publiceringstidspunkter,
 * der ikke kan sammenlignes med de rækker, de handler om, er umulige at
 * fejlsøge.
 *
 * <p>BEMÆRK: {@code Clock.tickMicros()} FINDES IKKE. JDK'et har
 * {@code tickMillis} og {@code tickSeconds}, og mikrosekunder skal skrives med
 * {@link Clock#tick(Clock, Duration)}.
 */
@Configuration
public class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1000));
	}

}

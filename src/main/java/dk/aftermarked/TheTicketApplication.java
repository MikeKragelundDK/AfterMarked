package dk.aftermarked;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Applikationens indgang.
 *
 * <p>{@link ConfigurationPropertiesScan} er ikke pynt. Uden den registreres
 * ingen {@code @ConfigurationProperties}-type som bønne, og
 * {@code FieldSchemaProperties} ville binde INTET: {@code base} og
 * {@code operators} ville stå tomme, hver operatør ville falde tilbage til et
 * tomt feltskema, og hvert eneste custom felt i et natligt push ville blive
 * afvist som en ukendt nøgle. Fejlen ville hverken være en opstartsfejl eller en
 * rød test — den ville være et katalog, der pludselig ikke kunne tage imod
 * felter.
 *
 * <p>Scanningen starter i denne pakke og går nedad, så en ny
 * {@code @ConfigurationProperties} i et modul opdages uden en linje her. Det er
 * bevidst valgt frem for {@code @EnableConfigurationProperties} med en eksplicit
 * liste: den liste ville skulle vedligeholdes af den, der tilføjer typen, og en
 * glemt linje giver præcis den tavse binding, annotationen findes for at undgå.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TheTicketApplication {

	public static void main(String[] args) {
		SpringApplication.run(TheTicketApplication.class, args);
	}

}

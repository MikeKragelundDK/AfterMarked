package dk.aftermarked;

import org.springframework.boot.SpringApplication;

public class TestTheTicketApplication {

	public static void main(String[] args) {
		SpringApplication.from(TheTicketApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

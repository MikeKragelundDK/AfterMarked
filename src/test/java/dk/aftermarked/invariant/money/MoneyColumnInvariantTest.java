package dk.aftermarked.invariant.money;

import java.util.List;

import dk.aftermarked.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INVARIANT — et beløb kan ikke GEMMES uden sin valuta (CLAUDE.md, PLAN §2.5).
 * Denne test er spec.
 *
 * <p>Typen lukker den ene halvdel: {@code Money} har ingen fabrik uden en
 * valuta, så et beløb uden valuta er en <em>compile</em>-fejl at konstruere. Men
 * en type kan ikke se en tabel. En migration kan tilføje en
 * {@code %_minor}-kolonne uden at give den en {@code currency} ved siden af, og
 * i det øjeblik findes tilstanden i databasen, uanset hvad Java tillader. Denne
 * test er den anden halvdel: den scanner det FAKTISKE skema, så det bliver en
 * rød build i stedet for en opdagelse i en afstemning.
 *
 * <p>Valutakolonnen er RÆKKENS og ikke beløbets: ét event = én valuta, så alle
 * beløb i en række deler den. Reglen er derfor "en tabel med en beløbskolonne
 * har også en {@code currency}-kolonne" og ikke "hver beløbskolonne har sin
 * egen". Den dag en række bærer TO beløb (S4's {@code FeePolicy}), skal
 * {@code MoneyColumns} have en {@code bindRow}, der kaster ved uenighed —
 * ellers vinder det sidste {@code .param} i stilhed.
 *
 * <p>Mønstret matches som en regex ({@code '_minor$'}) og ikke med
 * {@code LIKE '%\_minor'}: understregen er LIKE'ens eget jokertegn og skal
 * escapes, og en glemt escape ville gøre mønstret bredere, ikke smallere — altså
 * en fejl, der ser ud som om reglen virker.
 *
 * <p>Scanningen kører som runtime-rollen, ikke som ejeren.
 * {@code information_schema.columns} viser kun kolonner, den kaldende rolle har
 * en rettighed på, og det er præcis de tabeller, applikationen kan skrive et
 * beløb i.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MoneyColumnInvariantTest {

	@Autowired
	JdbcClient jdbc;

	@Test
	void givenEveryTableInTheSchema_whenLookingForAmountColumns_thenEachOneHasACurrencyColumnBesideIt() {
		List<String> orphans = jdbc.sql("""
				SELECT c.table_name || '.' || c.column_name
				  FROM information_schema.columns c
				 WHERE c.table_schema = 'public'
				   AND c.column_name ~ '_minor$'
				   AND NOT EXISTS (SELECT 1
				                     FROM information_schema.columns m
				                    WHERE m.table_schema = c.table_schema
				                      AND m.table_name   = c.table_name
				                      AND m.column_name  = 'currency')
				 ORDER BY 1
				""").query(String.class).list();

		assertThat(orphans)
				.as("an amount column without a currency column beside it is an amount without a meaning")
				.isEmpty();
	}

	/**
	 * Vagthunden mod en grøn tautologi. Ændres suffikset — {@code _amount},
	 * {@code _cents} — eller forsvinder den sidste beløbskolonne i en
	 * refaktorering, ville reglen ovenfor bestå ved at tjekke ingenting. Så
	 * fejler denne i stedet.
	 *
	 * <p>Gulvet er med vilje lavt: i dag findes præcis én beløbskolonne
	 * ({@code ticket_type.face_value_minor}), og S4/S6 lægger flere til.
	 */
	@Test
	void givenTheScan_whenCountingTheAmountColumnsItSees_thenItIsNotCheckingAnEmptySet() {
		Integer amountColumns = jdbc.sql("""
				SELECT count(*) FROM information_schema.columns
				 WHERE table_schema = 'public' AND column_name ~ '_minor$'
				""").query(Integer.class).single();

		assertThat(amountColumns)
				.as("the rule must actually see an amount column — otherwise it is green for the wrong reason")
				.isGreaterThanOrEqualTo(1);
	}

	/**
	 * Højst ÉN beløbskolonne pr. tabel — indtil {@code MoneyColumns} har en
	 * {@code bindRow}, der kaster ved uenighed.
	 *
	 * <p>Reglen ovenfor siger "en tabel med et beløb har en {@code currency}" og
	 * er dermed grøn for en tabel med TO beløbskolonner. Men {@code MoneyColumns.bind}
	 * binder rækkens {@code currency} én gang pr. {@code Money}, så to beløb i
	 * samme række betyder, at det sidste {@code .param} vinder i stilhed — præcis
	 * det hul, {@code MoneyColumns} selv beskriver. Den dag S4's {@code FeePolicy}
	 * lægger den anden kolonne til, skal denne test fjernes i SAMME ændring som
	 * {@code bindRow} kommer til — ikke før.
	 */
	@Test
	void givenEveryTableInTheSchema_whenCountingAmountColumnsPerTable_thenNoTableCarriesMoreThanOne() {
		List<String> crowded = jdbc.sql("""
				SELECT table_name || ' (' || count(*) || ')'
				  FROM information_schema.columns
				 WHERE table_schema = 'public'
				   AND column_name ~ '_minor$'
				 GROUP BY table_name
				HAVING count(*) > 1
				 ORDER BY 1
				""").query(String.class).list();

		assertThat(crowded)
				.as("MoneyColumns.bind writes the row's currency once per Money — a second amount column in the "
						+ "same row lets the last .param win in silence; add bindRow before adding the column")
				.isEmpty();
	}

}

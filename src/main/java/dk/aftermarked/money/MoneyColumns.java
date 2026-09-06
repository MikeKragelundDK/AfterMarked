package dk.aftermarked.money;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * Den ENESTE vej mellem et {@link Money} og de to kolonner, det bor i. Der
 * findes med vilje ingen overload, der tager et beløb uden sin valuta — så kan
 * den ikke kaldes forkert.
 *
 * <p>Valutakolonnen er RÆKKENS og ikke beløbets: ét event = én valuta
 * (PLAN §2.5), så alle beløb i en række deler den. En valutakolonne pr. beløb
 * ville tillade en tilstand, domænet forbyder — og ville gøre den sammensatte
 * fremmednøgle, der binder billettypens valuta til eventets, umulig at skrive.
 *
 * <p><strong>Kendt grænse, ikke lukket.</strong> I Sprint 2 findes der præcis
 * ét {@code Money} pr. række, så hullet er ikke nåeligt. Den dag en række bærer
 * TO beløb ({@code FeePolicy} i S4), skal der en
 * {@code bindRow(spec, Currency rowCurrency, …)} til, som kaster
 * {@link CurrencyMismatchException}, hvis de to beløb er uenige — ellers vinder
 * det sidste {@code .param("currency", …)} i stilhed, og rækken gemmer det ene
 * beløbs mindste enheder ved siden af det andets valuta. Byg den, når den
 * første række får to beløb, ikke før.
 */
public final class MoneyColumns {

	/** Kolonne- og parameternavnet for valutaen. Én pr. række — se klassens javadoc. */
	public static final String CURRENCY_PARAM = "currency";

	private MoneyColumns() {
	}

	/**
	 * Binder beløbet som {@code <name>Minor} plus den fælles {@code currency}.
	 *
	 * <p>Navnesuffikset {@code Minor} er ikke pynt: kolonnerne hedder
	 * {@code %_minor}, og invariant-testen, der scanner skemaet for en
	 * beløbskolonne uden en valutakolonne ved siden af, kender dem på præcis det
	 * suffiks. Hedder parameteren noget andet end kolonnen, er sætningen ikke til
	 * at læse ved siden af sit skema.
	 */
	public static StatementSpec bind(StatementSpec spec, String name, Money money) {
		Objects.requireNonNull(spec, "spec");
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(money, "money");
		return spec.param(name + "Minor", money.minorUnits()).param(CURRENCY_PARAM, money.currencyCode());
	}

	/**
	 * Læser de to kolonner tilbage som ét beløb.
	 *
	 * <p>Begge NULL-tjek er nødvendige og ingen af dem er paranoia.
	 * {@link ResultSet#getLong} returnerer {@code 0} for SQL NULL uden at sige
	 * det, så en glemt kolonne i et {@code INSERT} eller en {@code LEFT JOIN},
	 * der ikke fandt sin række, ville blive til et gyldigt nul-beløb — præcis det
	 * "beløb uden mening", typen findes for at forhindre. Og en manglende valuta
	 * ville give en {@link NullPointerException} langt væk fra sin årsag.
	 * Kolonnenavnet står i beskeden, så fejlen kan findes uden en debugger.
	 */
	public static Money read(ResultSet rs, String amountColumn, String currencyColumn) throws SQLException {
		Objects.requireNonNull(rs, "rs");
		Objects.requireNonNull(amountColumn, "amountColumn");
		Objects.requireNonNull(currencyColumn, "currencyColumn");
		long minorUnits = rs.getLong(amountColumn);
		if (rs.wasNull()) {
			throw new SQLException("Column [" + amountColumn + "] is NULL — an amount without a value is not an amount");
		}
		String code = rs.getString(currencyColumn);
		if (code == null) {
			throw new SQLException(
					"Column [" + currencyColumn + "] is NULL — an amount without a currency is not an amount");
		}
		return Money.of(minorUnits, code);
	}

}

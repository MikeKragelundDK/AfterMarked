package dk.aftermarked.tenancy;

import java.util.Objects;
import java.util.Optional;

/**
 * Den aktuelle operatør — bundet som {@link ScopedValue}, så den er immutabel
 * for hele det stykke arbejde, den er sat for, og forsvinder af sig selv, når
 * det er færdigt.
 *
 * <p>Konteksten sættes to steder, og kun der:
 * <ul>
 *   <li>Requests: {@code OperatorResolutionFilter} resolver operatøren fra
 *       værtsnavnet. Aldrig fra bruger-input, parameter, header eller payload.</li>
 *   <li>Baggrundsjobs: jobbet kalder selv {@link #runAs} med operatøren fra
 *       sin egen jobrække. Et job uden kontekst ser nul rækker.</li>
 * </ul>
 *
 * <p><strong>Vigtigt for jobs:</strong> konteksten anvendes på databasen ved
 * <em>forbindelses-checkout</em> ({@code TenantAwareDataSource}). En transaktion,
 * der er begyndt <em>før</em> {@code runAs}, har allerede sin forbindelse og ser
 * derfor IKKE konteksten. Jobs skal starte deres transaktion <em>inde i</em>
 * {@code runAs} — aldrig omvendt.
 */
public final class OperatorContext {

	private static final ScopedValue<OperatorId> CURRENT = ScopedValue.newInstance();

	private OperatorContext() {
	}

	/** Operatøren for det aktuelle arbejde, eller tom hvis intet er bundet. */
	public static Optional<OperatorId> current() {
		return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
	}

	/** Som {@link #current()}, men fejler højlydt hvis ingen operatør er bundet. */
	public static OperatorId required() {
		return current().orElseThrow(() -> new IllegalStateException(
				"No operator in context — the call happens outside a request and without OperatorContext.runAs"));
	}

	/**
	 * Kører {@code action} med {@code operator} som aktuel operatør. Bindingen
	 * gælder kun inde i kaldet og kun på den kaldende tråd.
	 */
	public static <X extends Throwable> void runAs(OperatorId operator, Action<X> action) throws X {
		Objects.requireNonNull(operator, "operator");
		Objects.requireNonNull(action, "action");
		ScopedValue.where(CURRENT, operator).call(() -> {
			action.run();
			return null;
		});
	}

	/** Som {@link #runAs(OperatorId, Action)}, med resultat. */
	public static <T, X extends Throwable> T callAs(OperatorId operator, Supplier<T, X> supplier) throws X {
		Objects.requireNonNull(operator, "operator");
		Objects.requireNonNull(supplier, "supplier");
		return ScopedValue.where(CURRENT, operator).call(supplier::get);
	}

	@FunctionalInterface
	public interface Action<X extends Throwable> {
		void run() throws X;
	}

	@FunctionalInterface
	public interface Supplier<T, X extends Throwable> {
		T get() throws X;
	}

}

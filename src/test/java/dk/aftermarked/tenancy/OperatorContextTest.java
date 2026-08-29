package dk.aftermarked.tenancy;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OperatorContext} is ScopedValue based. This test pins down exactly what that
 * means — including the places where the binding does NOT follow along, because those
 * are where tenant isolation gets bypassed by accident (PLAN §2.7, rule 3).
 *
 * <p>No Docker and no Spring: what is tested here is pure language semantics.
 */
class OperatorContextTest {

	static final OperatorId A = new OperatorId(UUID.fromString("00000000-0000-4000-8000-00000000000a"));
	static final OperatorId B = new OperatorId(UUID.fromString("00000000-0000-4000-8000-00000000000b"));

	@Test
	void givenNoBinding_whenReadingTheCurrentContext_thenItIsEmpty() {
		assertThat(OperatorContext.current()).isEmpty();
	}

	@Test
	void givenNoBinding_whenCallingRequired_thenItFailsLoudly() {
		assertThatThrownBy(OperatorContext::required)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("No operator in context");
	}

	@Test
	void givenABoundOperator_whenTheCallReturns_thenTheBindingAppliedOnlyInsideTheCall() {
		OperatorContext.runAs(A, () -> assertThat(OperatorContext.current()).contains(A));
		assertThat(OperatorContext.current()).as("the binding must be gone again afterwards").isEmpty();
	}

	@Test
	void givenABoundOperator_whenUsingCallAs_thenTheResultIsReturned() {
		assertThat(OperatorContext.callAs(A, () -> OperatorContext.required())).isEqualTo(A);
	}

	/** A nested runAs shadows the outer binding and restores it on exit. */
	@Test
	void givenAnOuterBinding_whenANestedRunAsShadowsIt_thenTheOuterBindingIsRestoredOnExit() {
		OperatorContext.runAs(A, () -> {
			assertThat(OperatorContext.current()).contains(A);
			OperatorContext.runAs(B, () -> assertThat(OperatorContext.current()).contains(B));
			assertThat(OperatorContext.current()).as("the outer binding must be back").contains(A);
		});
		assertThat(OperatorContext.current()).isEmpty();
	}

	@Test
	void givenABoundOperator_whenTheActionThrows_thenTheBindingIsClearedAnyway() {
		assertThatThrownBy(() -> OperatorContext.runAs(A, () -> {
			throw new IllegalArgumentException("boom");
		})).isInstanceOf(IllegalArgumentException.class).hasMessage("boom");

		assertThat(OperatorContext.current()).isEmpty();
	}

	/** Checked exceptions propagate unchanged — no wrapping, no swallowed errors. */
	@Test
	void givenAnActionThrowingACheckedException_whenRunAsPropagatesIt_thenItArrivesUnwrapped() {
		assertThatThrownBy(() -> OperatorContext.runAs(A, () -> {
			throw new IOException("io");
		})).isExactlyInstanceOf(IOException.class);
	}

	@Test
	void givenANullOperatorOrAction_whenCallingRunAsOrCallAs_thenTheyAreRejected() {
		assertThatThrownBy(() -> OperatorContext.runAs(null, () -> {
		})).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> OperatorContext.runAs(A, null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> OperatorContext.callAs(null, () -> 1)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> OperatorContext.callAs(A, null)).isInstanceOf(NullPointerException.class);
	}

	/**
	 * TRAP: a new platform thread started inside the scope does NOT inherit the
	 * binding. A job that hands work to another thread loses the context and sees zero
	 * rows (fail-closed) — but it is still a bug, not a feature.
	 */
	@Test
	void givenABoundScope_whenANewPlatformThreadIsStartedInsideIt_thenTheThreadDoesNotInheritTheContext()
			throws Exception {
		AtomicReference<Optional<OperatorId>> seen = new AtomicReference<>();
		OperatorContext.runAs(A, () -> {
			Thread t = new Thread(() -> seen.set(OperatorContext.current()));
			t.start();
			t.join();
		});
		assertThat(seen.get()).as("a ScopedValue does not follow along to a new thread").isEmpty();
	}

	/** The same trap on a virtual thread — and the app runs with spring.threads.virtual.enabled=true. */
	@Test
	void givenABoundScope_whenAVirtualThreadIsStartedInsideIt_thenTheThreadDoesNotInheritTheContext()
			throws Exception {
		AtomicReference<Optional<OperatorId>> seen = new AtomicReference<>();
		OperatorContext.runAs(A, () -> {
			Thread t = Thread.ofVirtual().unstarted(() -> seen.set(OperatorContext.current()));
			t.start();
			t.join();
		});
		assertThat(seen.get()).isEmpty();
	}

	/**
	 * The same trap through a thread pool — which is exactly what {@code @Async} and
	 * every {@code TaskExecutor} does. The context has to be bound afresh inside the
	 * task.
	 */
	@Test
	void givenABoundScope_whenSubmittingATaskToAThreadPool_thenTheTaskMustBindTheContextItself() throws Exception {
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<Optional<OperatorId>> without = OperatorContext.callAs(A,
					() -> pool.submit(OperatorContext::current));
			assertThat(without.get()).as("the pooled thread sees no context").isEmpty();

			// The right way: the task binds for itself, from its own job row.
			Future<Optional<OperatorId>> with = pool.submit(() -> OperatorContext.callAs(B, OperatorContext::current));
			assertThat(with.get()).contains(B);
		}
	}

	/**
	 * Two concurrent threads must never be able to see each other's operator.
	 *
	 * <p>Tidsgraensen goer et barriere-deadlock til en roed test i stedet for en
	 * CI-koersel, der haenger.
	 */
	@Test
	@Timeout(30)
	void givenTwoConcurrentThreads_whenEachBindsItsOwnOperator_thenTheirContextsStayIndependent() throws Exception {
		CyclicBarrier both = new CyclicBarrier(2);
		BlockingQueue<String> seen = new ArrayBlockingQueue<>(2);
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<OperatorId>> futures = List.of(
					pool.submit(() -> OperatorContext.callAs(A, () -> awaitBoth(both, seen, "a"))),
					pool.submit(() -> OperatorContext.callAs(B, () -> awaitBoth(both, seen, "b"))));
			assertThat(futures.get(0).get()).isEqualTo(A);
			assertThat(futures.get(1).get()).isEqualTo(B);
		}
		assertThat(seen).containsExactlyInAnyOrder("a", "b");
	}

	/** The binding is immutable: there is no setter, only runAs/callAs. */
	@Test
	void givenThePublicApiOfOperatorContext_whenLookingForAMutator_thenThereIsNoWayToSetTheContextWithoutRunAs() {
		assertThat(OperatorContext.class.getMethods())
				.extracting(java.lang.reflect.Method::getName)
				.doesNotContain("set", "setCurrent", "bind", "clear", "remove");
	}

	/** Both threads are inside their own scope AT THE SAME TIME when they read the context. */
	private static OperatorId awaitBoth(CyclicBarrier both, BlockingQueue<String> seen, String name) {
		try {
			seen.put(name);
			both.await();
			return OperatorContext.required();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
		catch (BrokenBarrierException ex) {
			throw new IllegalStateException(ex);
		}
	}

}

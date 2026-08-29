package dk.aftermarked.ops;

import java.util.Set;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPERATIONS — the two naming rules in CLAUDE.md that a compiler cannot catch.
 *
 * <p><b>1) Test methods follow Given/When/Then in English.</b> The convention is only
 * worth something as long as it is unbroken: the day half the suite is named
 * {@code testStuff}, nobody can read a failure report as a list of statements about
 * the system any more.
 *
 * <p><b>2) No Danish letters in identifiers.</b> "The code is English, the comments are
 * Danish" — an {@code æ}, {@code ø} or {@code å} in a class, method or field name is
 * the cheapest possible proof that the rule slipped.
 *
 * <p>Both rules were green when they were written; they exist to keep them that way.
 * A rule that is enforced only by review is enforced only as long as someone reviews.
 *
 * <p><strong>Limitation.</strong> ArchUnit reads bytecode, so it sees classes, methods
 * and fields — not local variables, and not comments. Local variables are therefore
 * left to review. That is deliberate: the alternative is a regular expression over Java
 * source, which would have to understand text blocks and string literals to avoid
 * false alarms on legitimately Danish comments.
 */
class CodeConventionsOpsTest {

	/** Everything under {@code dk.aftermarked} — production code AND the test suite. */
	static final JavaClasses ALL_CLASSES = new ClassFileImporter().importPackages("dk.aftermarked");

	/**
	 * The JUnit annotations that mark a method as a test. Matched by simple name so the
	 * test does not need a compile-time dependency on every JUnit artifact — a
	 * {@code @ParameterizedTest} must follow the same convention as a {@code @Test}.
	 */
	static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory",
			"TestTemplate");

	/** {@code givenX_whenY_thenZ} — camelCase inside each part, underscore between them. */
	static final String GIVEN_WHEN_THEN = "given[A-Z0-9][A-Za-z0-9]*_when[A-Z0-9][A-Za-z0-9]*_then[A-Z0-9][A-Za-z0-9]*";

	/** Danish letters, upper and lower case. */
	static final String NO_DANISH_LETTERS = "[^æøåÆØÅ]*";

	static final DescribedPredicate<JavaMethod> ANNOTATED_AS_A_TEST =
			new DescribedPredicate<>("annotated as a test") {
				@Override
				public boolean test(JavaMethod method) {
					return method.getAnnotations().stream()
							.map(annotation -> annotation.getRawType().getSimpleName())
							.anyMatch(TEST_ANNOTATIONS::contains);
				}
			};

	@Test
	void givenEveryTestMethod_whenReadingItsName_thenItFollowsGivenWhenThen() {
		ArchRule rule = methods().that(ANNOTATED_AS_A_TEST)
				.should().haveNameMatching(GIVEN_WHEN_THEN)
				.because("a test name is the sentence the failure report prints (CLAUDE.md)");

		rule.check(ALL_CLASSES);
	}

	/**
	 * The guard against a green tautology: if the predicate above ever stops matching —
	 * a renamed annotation, a changed JUnit package, an empty import — the rule would
	 * pass by checking nothing at all. This test fails instead.
	 *
	 * <p>The floor is deliberately far below the actual number, so it does not have to
	 * be maintained every time a test is added or removed.
	 */
	@Test
	void givenTheTestSuite_whenCountingTheMethodsTheRuleSees_thenItIsNotCheckingAnEmptySet() {
		long testMethods = ALL_CLASSES.stream()
				.flatMap(type -> type.getMethods().stream())
				.filter(ANNOTATED_AS_A_TEST)
				.count();

		assertThat(testMethods)
				.as("the naming rule must actually see the test suite — otherwise it is green for the wrong reason")
				.isGreaterThan(50);
	}

	@Test
	void givenEveryTypeInTheProject_whenReadingItsName_thenNoneContainsADanishLetter() {
		ArchRule rule = classes()
				.should().haveNameMatching(NO_DANISH_LETTERS)
				.because("the code is English; Danish belongs in comments, commits and user-facing text (CLAUDE.md)");

		rule.check(ALL_CLASSES);
	}

	@Test
	void givenEveryMethodInTheProject_whenReadingItsName_thenNoneContainsADanishLetter() {
		ArchRule rule = methods()
				.should().haveNameMatching(NO_DANISH_LETTERS)
				.because("the code is English; Danish belongs in comments, commits and user-facing text (CLAUDE.md)");

		rule.check(ALL_CLASSES);
	}

	@Test
	void givenEveryFieldInTheProject_whenReadingItsName_thenNoneContainsADanishLetter() {
		ArchRule rule = fields()
				.should().haveNameMatching(NO_DANISH_LETTERS)
				.because("the code is English; Danish belongs in comments, commits and user-facing text (CLAUDE.md)");

		rule.check(ALL_CLASSES);
	}

}

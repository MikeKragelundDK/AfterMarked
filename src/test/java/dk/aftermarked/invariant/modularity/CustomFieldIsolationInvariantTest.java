package dk.aftermarked.invariant.modularity;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * INVARIANT — custom felter er præsentation og metadata, aldrig adfærd
 * (PLAN §2.6). Denne test er spec.
 *
 * <p>Reglen, PLAN skriver ordret, er at penge-, saga- og allokeringsmodulerne
 * ALDRIG læser feltværdi-lageret. Ét felt, der kan ændre pris, gebyr, prisloft,
 * allokering eller saga-forløb, giver N pengeveje og river netop den invariant
 * ned, hele white-label-modellen bygger på. Derfor er reglen håndhævbar og ikke
 * en hensigt.
 *
 * <p><strong>Allow-liste og ikke deny-liste.</strong> Et modul, der tilføjes
 * senere, er nægtet som default. Listen VIL legitimt vokse — {@code notifications}
 * kan få brug for et role-mærket branding-felt til temasatte mails — og det er en
 * bevidst redigering med review. Den må ALDRIG få {@code orders},
 * {@code payments} eller {@code marketplace}.
 *
 * <p><strong>Hvorfor reglen køres direkte på en frisk importer.</strong> Den
 * nærliggende genvej er at lade Modulith's egen verifikation gøre arbejdet, men
 * {@code ApplicationModules} holdes i en statisk cache, og {@code verify()}
 * sætter en {@code verified}-latch. Har {@code ModularityTests} kørt først i
 * samme JVM, evalueres reglen aldrig igen — og testen er grøn af den forkerte
 * grund. En egen {@link ClassFileImporter} har ingen latch.
 *
 * <p>Modulith's egen kontrol er stadig det primære værn: {@code catalog.fields}
 * er en navngiven grænseflade, og {@code orders}, {@code payments} og
 * {@code marketplace} lister den ikke. Denne test er det uafhængige net under —
 * den overlever, at nogen tilføjer {@code "catalog::fields"} til en liste.
 */
class CustomFieldIsolationInvariantTest {

	/**
	 * Kun produktionskode. Testkode må gerne referere feltlageret — det er
	 * netop dét, en test af valideringen skal kunne.
	 */
	static final JavaClasses PRODUCTION_CODE = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("dk.aftermarked");

	static final String FIELD_STORE = "dk.aftermarked.catalog.fields..";

	/**
	 * Reglen er skrevet som sin kontraposition — "ingen klasse uden for
	 * allow-listen afhænger af feltlageret" — fordi ArchUnit kun tilbyder
	 * {@code dependOnClassesThat()} efter {@code should()}, ikke efter
	 * {@code that()}. Betydningen er den samme; kun stavemåden er ArchUnits.
	 */
	@Test
	void givenOurProductionCode_whenScanningDependenciesOnTheFieldStore_thenOnlyCatalogAndTheRenderingModulesReachIt() {
		ArchRule rule = noClasses()
				.that().resideOutsideOfPackages("dk.aftermarked.catalog..",
						"dk.aftermarked.web..",
						"dk.aftermarked.operatoradmin..")
				.should().dependOnClassesThat().resideInAnyPackage(FIELD_STORE)
				.because("custom fields are presentation and metadata, never behaviour: money, saga "
						+ "and allocation code must never read the field value store (PLAN §2.6)");

		rule.check(PRODUCTION_CODE);
	}

	/**
	 * Vagthunden mod en grøn tautologi. Peger importeren det forkerte sted hen —
	 * en flyttet pakke, en tom {@code target/classes} — passerer reglen ved at
	 * kontrollere ingenting. Denne test fejler i stedet.
	 *
	 * <p>Gulvene ligger med vilje langt under de faktiske tal, så de ikke skal
	 * vedligeholdes, hver gang der kommer en klasse til.
	 */
	@Test
	void givenTheRule_whenCountingTheClassesItSees_thenItIsNotCheckingAnEmptySet() {
		long productionClasses = PRODUCTION_CODE.stream().count();
		long fieldStoreClasses = PRODUCTION_CODE.stream()
				.filter(type -> type.getPackageName().startsWith("dk.aftermarked.catalog.fields"))
				.count();

		assertThat(productionClasses)
				.as("the rule must actually see the production code — otherwise it is green for the wrong reason")
				.isGreaterThan(15);
		assertThat(fieldStoreClasses)
				.as("the field value store must exist, or the rule has nothing to protect")
				.isGreaterThan(3);
	}

}

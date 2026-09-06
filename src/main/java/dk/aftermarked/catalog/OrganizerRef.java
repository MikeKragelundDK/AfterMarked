package dk.aftermarked.catalog;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Kundens egen stabile arrangør-reference (PLAN §2.2). Kataloget er et spejl af
 * kundens system, så det er KUNDENS ref — ikke vores uuid — der er den naturlige
 * nøgle: vores uuid står aldrig i kundens verden.
 *
 * <p>Egen type og ikke en {@code String}, så {@code upsert(organizerRef, eventRef)}
 * ikke kan kaldes med byttede argumenter. To strenge af samme form er ens for
 * compileren; to records er det ikke.
 *
 * <p>Formatet er PRÆCIS det, V5's {@code organizer_ref_format} håndhæver. De to
 * skal være enige: ref'en står ordret i URL-stien {@code /o/{organizerRef}}, så
 * den skal være URL-sikker uden escaping, og en værdi, typen accepterer men
 * databasen afviser, ville blive til en 23514 midt i et natligt push i stedet
 * for en læsbar 422.
 *
 * <p><strong>{@value #RESERVED_DEFAULT} er reserveret.</strong> Det er ref'en på
 * den arrangør, VI syntetiserer for kunder, der ikke skelner mellem arrangører
 * (PLAN §2.3), og den skrives udelukkende af lazy enrichment. Denne type
 * ACCEPTERER den — ellers kunne vores egen indsættelse ikke bygge sit eget
 * argument — mens et katalog-push aldrig må kunne kræve den: kunne en kunde
 * omdøbe eller overtage {@value #RESERVED_DEFAULT}, ville alle skelet-events
 * hænge på en arrangør, kunden pludselig ejede.
 *
 * <p>Afvisningen fra wire hører derfor i parseren, ikke her: kun den kan
 * rapportere {@code RESERVED_REF} med den JSON-Pointer, der peger på præcis den
 * arrangør i payloaden. Konstanten er offentlig, så parseren kan afvise den uden
 * at skrive strengen af — to stavemåder af den samme reservation ville før eller
 * siden komme fra hinanden.
 */
public record OrganizerRef(String value) {

	/**
	 * Ref'en på den arrangør, vi selv syntetiserer for en kunde uden arrangører.
	 * Tilladt af V5's CHECK, tilladt af denne type — og afvist af parseren, når
	 * den kommer fra et katalog-push.
	 */
	public static final String RESERVED_DEFAULT = "_default";

	/** Ordret den samme regex som V5's {@code organizer_ref_format}. */
	private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._~-]{0,63}$");

	public OrganizerRef {
		Objects.requireNonNull(value, "value");
		if (!isValid(value)) {
			throw new IllegalArgumentException(
					"Organizer ref does not match the required format ^[A-Za-z0-9][A-Za-z0-9._~-]{0,63}$");
		}
	}

	/**
	 * Om en rå streng kan blive til en {@code OrganizerRef} — uden at kaste.
	 *
	 * <p>Parseren skal kunne SAMLE alle overtrædelser i én liste og svare kunden
	 * på én gang; en exception pr. dårlig ref ville gøre den første fejl til den
	 * eneste, kunden fik at vide.
	 *
	 * <p>⚠ Den siger {@code true} for {@value #RESERVED_DEFAULT}. Reservationen
	 * er en SELVSTÆNDIG kontrol i parseren ({@code RESERVED_REF}), fordi
	 * databasen med vilje tillader værdien.
	 */
	public static boolean isValid(String candidate) {
		return candidate != null && (RESERVED_DEFAULT.equals(candidate) || FORMAT.matcher(candidate).matches());
	}

	/** Om det er den arrangør, vi selv har syntetiseret. */
	public boolean isReservedDefault() {
		return RESERVED_DEFAULT.equals(this.value);
	}

}

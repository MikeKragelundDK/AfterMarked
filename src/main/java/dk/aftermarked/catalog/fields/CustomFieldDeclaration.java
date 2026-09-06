package dk.aftermarked.catalog.fields;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Én deklareret custom felt-definition fra operatørens versionerede feltskema
 * (PLAN §2.6). Deklarationen er config, ikke skema — at give en kunde et nyt
 * felt er en config-ændring og ikke en Flyway-migration, og det er hele pointen
 * med JSONB-lageret.
 *
 * <p>Fordi deklarationen er config, er den kompakte konstruktør det eneste sted,
 * en stavefejl kan stoppes. Alt herunder fejler ved OPSTART og ikke ved første
 * push: en ugyldig deklaration, der først opdages klokken 03, er en kundes
 * natlige import, der fejler uden nogen til at læse loggen.
 *
 * <p>Reglerne, og hvorfor de findes:
 * <ul>
 *   <li><strong>{@code key}</strong> er camelCase ({@code organizerLogo}), samme
 *       konvention som PLAN §2.6's eksempler. Det er også JSONB-nøglen, så den
 *       skal kunne stå i en JSON-nøgle uden escaping.</li>
 *   <li><strong>{@code role}</strong> må mangle (de fleste felter har ingen
 *       særlig betydning). Findes den, er den en punktopdelt sti af camelCase-led
 *       — {@code branding.logo}, {@code branding.eventImage}. Kundens frontend
 *       forgrener på {@code role}, mens {@code type} styrer validering; blandes
 *       de to, mister vi valideringen.</li>
 *   <li><strong>{@code values}</strong> hører kun til {@code ENUM} og er
 *       IDENTIFIERS, aldrig brugervendt tekst: det, brugeren ser, kommer fra en
 *       nøgle (CLAUDE.md). Derfor {@code SCREAMING_SNAKE} og derfor afvises
 *       værdier på en type, der ikke er et enum — ellers ville en fejlplaceret
 *       liste blive stiltiende ignoreret.</li>
 *   <li><strong>{@code translatable}</strong> kan kun være sand for de
 *       tekstformede typer. Et oversat {@code bool} er en modsigelse, og det er
 *       typen — ikke formen på JSON'en — der afgør, om et objekt i lageret
 *       betyder "oversættelser".</li>
 *   <li><strong>{@code MONEY} uden for {@code EVENT} og {@code TICKET_TYPE} er
 *       ulovlig.</strong> En arrangør har intet event at validere valutaen imod,
 *       og beløbet ville ligge for evigt uvalideret i en jsonb, som
 *       {@code MoneyColumnInvariantTest}s {@code %_minor}-skanning aldrig kan
 *       se. Reglen er skrevet som en allow-liste, så et scope tilføjet senere er
 *       nægtet som default.</li>
 *   <li><strong>{@code since}/{@code until}</strong> er feltskema-versioner:
 *       {@code since} er inklusiv, {@code until} EKSKLUSIV — den første version,
 *       hvor feltet er væk. Det er expand-migrate-contract anvendt på config, og
 *       det er dét, der gør en forskudt rollout læsbar. Nedre grænse er 0 og
 *       ikke 1: {@code custom_fields_schema_version} har DEFAULT 0 i V5, og en
 *       udeladt {@code since} i YAML'en binder til 0 — en strengere grænse ville
 *       gøre en udeladelse til en opstartsfejl i stedet for en default.</li>
 * </ul>
 *
 * <p><strong>Bevidst afvigelse fra sprintets udkast:</strong> {@code role}-regexen
 * er skrevet med camelCase-led. Udkastets {@code ^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)*$}
 * ville afvise dens eget eksempel {@code branding.eventImage} og dermed sprænge
 * pilotkundens feltskema ved opstart.
 *
 * <p>Der er med vilje ingen {@code label} her. Feltets navn er SYSTEMTEKST og
 * hører i {@code messages*.properties} under {@code field.<key>.label}; kun
 * feltets VÆRDI er kundetekst (PLAN §2.9). En label i en YAML omgår
 * {@code MessageSource}, og {@code LocalizationOpsTest} ville aldrig se en
 * manglende oversættelse.
 */
public record CustomFieldDeclaration(String key, CustomFieldType type, CustomFieldScope scope,
		String role, boolean required, boolean translatable, List<String> values,
		int since, Integer until) {

	/** camelCase, som PLAN §2.6's {@code organizerLogo}. Højst 63 tegn. */
	static final Pattern KEY_FORMAT = Pattern.compile("^[a-z][a-zA-Z0-9]{0,62}$");

	/**
	 * Punktopdelt sti af camelCase-led: {@code branding.logo},
	 * {@code branding.eventImage}. Punktet står som tegnklassen {@code [.]} og
	 * ikke som et escapet punkt — samme betydning, én kilde til fejl mindre.
	 */
	static final Pattern ROLE_FORMAT = Pattern.compile("^[a-z][a-zA-Z0-9]{0,62}([.][a-z][a-zA-Z0-9]{0,62})*$");

	/** Enum-værdier er identifiers, aldrig brugervendt tekst. */
	static final Pattern ENUM_VALUE_FORMAT = Pattern.compile("^[A-Z][A-Z0-9_]{0,62}$");

	public CustomFieldDeclaration {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(scope, "scope");
		if (!KEY_FORMAT.matcher(key).matches()) {
			throw new IllegalArgumentException(
					"Custom field key [" + key + "] must match " + KEY_FORMAT.pattern());
		}
		if (role != null && !ROLE_FORMAT.matcher(role).matches()) {
			throw new IllegalArgumentException("Custom field [" + key + "] declares role [" + role
					+ "], which must match " + ROLE_FORMAT.pattern() + " — omit the role rather than leaving it blank");
		}
		// List.copyOf afviser null-elementer, så en YAML-liste med et tomt punkt
		// stopper her og ikke ved første validering af en værdi.
		values = (values == null) ? List.of() : List.copyOf(values);
		if (type == CustomFieldType.ENUM) {
			if (values.isEmpty()) {
				throw new IllegalArgumentException(
						"Custom field [" + key + "] of type ENUM must declare at least one value");
			}
			for (String value : values) {
				if (!ENUM_VALUE_FORMAT.matcher(value).matches()) {
					throw new IllegalArgumentException("Custom field [" + key + "] declares enum value [" + value
							+ "], which must match " + ENUM_VALUE_FORMAT.pattern()
							+ " — enum values are identifiers, never user-facing text");
				}
			}
			if (Set.copyOf(values).size() != values.size()) {
				throw new IllegalArgumentException(
						"Custom field [" + key + "] of type ENUM declares the same value twice");
			}
		}
		else if (!values.isEmpty()) {
			throw new IllegalArgumentException("Custom field [" + key + "] of type " + type
					+ " must not declare enum values — they would be silently ignored");
		}
		if (translatable && !type.isTranslatable()) {
			throw new IllegalArgumentException("Custom field [" + key + "] of type " + type
					+ " cannot be translatable — only TEXT and RICHTEXT carry translations");
		}
		if (type == CustomFieldType.MONEY
				&& scope != CustomFieldScope.EVENT && scope != CustomFieldScope.TICKET_TYPE) {
			throw new IllegalArgumentException("Custom field [" + key + "] of type MONEY is only legal in scope EVENT "
					+ "or TICKET_TYPE, not " + scope + " — there is no event to validate the currency against");
		}
		if (since < 0) {
			throw new IllegalArgumentException(
					"Custom field [" + key + "] declares since " + since + ", which must not be negative");
		}
		if (until != null && until <= since) {
			throw new IllegalArgumentException("Custom field [" + key + "] declares until " + until
					+ ", which must be greater than since " + since);
		}
	}

	/**
	 * Om feltet er en del af feltskemaet i netop den version.
	 *
	 * <p>{@code since} er inklusiv og {@code until} eksklusiv, så et felt, der
	 * udgår i version 7, står som {@code until: 7} og er væk fra og med 7. Det
	 * er den samme regnemåde som en halvåben tidsperiode, og den gør to på
	 * hinanden følgende vinduer sammenhængende uden overlap.
	 */
	public boolean isActiveAt(int schemaVersion) {
		return schemaVersion >= this.since && (this.until == null || schemaVersion < this.until);
	}

}

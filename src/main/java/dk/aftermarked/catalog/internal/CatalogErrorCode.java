package dk.aftermarked.catalog.internal;

/**
 * Den komplette liste over grunde til, at et katalog-push kan afvises — plus de
 * to, der kun er advarsler.
 *
 * <p>Listen er dét, der gør endpointet implementerbart uden at kunden skriver
 * til os: koden er stabil og maskinlæsbar, og kunden kan forgrene på den. Den
 * bliver til {@code CatalogValidationException.Violation.code()} som
 * {@link #name()} — altså ordret konstantens navn.
 *
 * <p><strong>Koden bærer aldrig værdien.</strong> Hverken koden, JSON-Pointeren
 * eller beskeden må indeholde det, kunden sendte: værdien kan være persondata
 * eller 20 KB richtext, og alle tre ender i logs. Kunden ved fra stien præcis,
 * hvor i sin egen payload værdien står.
 *
 * <p><strong>Enum'et ligger i {@code internal}</strong> (spec-beslutning #11 og
 * {@code CatalogValidationException}s javadoc): kom det ud på en offentlig
 * signatur, ville den interne fejlmodel blive til offentlig API, som en ny
 * fejlkode ikke længere kunne tilføjes til uden en API-ændring. Den offentlige
 * form er tre strenge.
 *
 * <p>De to sidste konstanter er ADVARSLER og afviser aldrig et push. De bæres
 * af {@link CatalogWarning} i stedet — samme kodetype, fordi en kunde skal
 * kunne slå begge op ét sted, og fordi en advarsel en dag kan blive en
 * afvisning uden at skifte navn.
 */
public enum CatalogErrorCode {

	/**
	 * En nøgle, vores kontrakt ikke kender. Afvises EKSPLICIT — Jackson 3
	 * leverer {@code FAIL_ON_UNKNOWN_PROPERTIES} slået fra, og
	 * {@code @JsonIgnoreProperties(ignoreUnknown = false)} gør intet (se
	 * {@link CatalogPayloadParser}). Rammer også et {@code tenant}- eller
	 * {@code operator}-felt: operatøren kommer fra værtsnavnet og fra intet
	 * andet.
	 */
	UNKNOWN_PROPERTY,

	/** Et pligtigt kernefelt mangler. En eksplicit {@code null} tæller som fraværende. */
	MISSING_REQUIRED_FIELD,

	/** En ref, der ikke matcher formatet i V5's {@code *_ref_format}-CHECK. */
	INVALID_REF_FORMAT,

	/**
	 * Kunden kræver {@code OrganizerRef.RESERVED_DEFAULT}. Databasens CHECK
	 * TILLADER værdien, fordi vi selv skriver den række; her afvises den fra
	 * wire, så en kunde ikke kan overtage den arrangør, alle skelet-events
	 * hænger på.
	 */
	RESERVED_REF,

	/** Den samme ref to gange i ét push. Hvilken af de to vandt, ville være tilfældigt. */
	DUPLICATE_REF,

	/**
	 * En tekst, der kun er mellemrum. V5's {@code CHECK (name ~ '\S')} ville
	 * afvise den som 23514 midt i et natligt push; her bliver den en læsbar 422.
	 */
	BLANK_VALUE,

	/** Et tidspunkt, der ikke er ISO-8601 med offset. */
	INVALID_TIMESTAMP,

	/** {@code endsAt} ligger før {@code startsAt} — V5's {@code event_ends_after_start}. */
	ENDS_BEFORE_START,

	/**
	 * Et beløb, der ikke er et helt tal i mindste enhed, der kan gemmes.
	 * Kontrollen er {@code isIntegralNumber() && canConvertToLong()} — begge
	 * konjunkter, se {@link CatalogPayloadParser}. En streng med cifre i er
	 * heller ikke et tal.
	 */
	MONEY_NOT_INTEGRAL,

	/** Et beløb uden valuta. Et beløb uden valuta er ikke et beløb (CLAUDE.md). */
	MONEY_MISSING_CURRENCY,

	/**
	 * En valutakode, ISO 4217 ikke kender — eller en kode uden mindste enhed
	 * (XXX, XTS), som aldrig kan bære et beløb. Se {@code Money.hasMinorUnit}.
	 */
	UNKNOWN_CURRENCY,

	/** En billettype i en anden valuta end sit event. Ét event = én valuta (PLAN §2.5). */
	CURRENCY_MISMATCH,

	/**
	 * To forskellige valutaer i ÉT push. Parseren afviser kun den payload, der
	 * modsiger sig selv; POLITIKKEN "alle events hos denne operatør deler
	 * valuta" ({@code operator.single_currency}, V4) håndhæves af
	 * {@code CatalogWriteGuard} mod databasen, bag advisory-låsen.
	 */
	MULTIPLE_CURRENCIES,

	/**
	 * Objektformen mangler operatørens eget basissprog. Uden det står
	 * fallback-kædens trin 4 — grundfeltet på entiteten — tomt (PLAN §2.9).
	 */
	MISSING_BASE_LOCALE,

	/** En nøgle i objektformen, der ikke er et sprogtag på formen {@code aa} eller {@code aa-BB}. */
	INVALID_LOCALE_KEY,

	/**
	 * Et custom felt, der ikke er deklareret {@code translatable}, står som et
	 * objekt af sprog. Rejses af {@code CustomFieldValidator}: TYPEN afgør, aldrig
	 * formen på JSON'en.
	 */
	NOT_TRANSLATABLE,

	/** Et custom felt, operatørens feltskema ikke kender. */
	UNKNOWN_FIELD_KEY,

	/** Et custom felt, der er deklareret på en anden entitetstype. */
	FIELD_WRONG_SCOPE,

	/**
	 * En værdi af den forkerte form. Bruges både af {@code CustomFieldValidator}
	 * og af parseren for de kernefelter, hvor ingen anden kode passer — se
	 * {@link CatalogPayloadParser}s note om {@code primaryPurchaseUrl}.
	 */
	FIELD_TYPE_MISMATCH,

	/** Et {@code required}-deklareret custom felt mangler i et push. */
	FIELD_REQUIRED,

	/**
	 * U+0000 i en streng. PostgreSQL kan ikke lagre tegnet i en {@code text}- eller
	 * {@code jsonb}-værdi, og driveren afviser det dybt nede uden at navngive
	 * rækken — så det fanges her, før det når databasen.
	 */
	ILLEGAL_CHARACTER,

	/**
	 * Pushet er over en af batch-grænserne. Ikke høflighed: ét push er én
	 * transaktion, og et no-op-upsert rækkelåser stadig hver konfliktende række
	 * til commit, så en afgrænset batch afgrænser låsevinduet.
	 */
	BATCH_TOO_LARGE,

	// --- Advarsler. Afviser ALDRIG et push -----------------------------------

	/**
	 * En {@code salesStatus}, vi ikke kender. Værdien lagres som
	 * {@code SalesStatus.UNKNOWN}, som aldrig er "til salg" og aldrig genoptager
	 * ventelisten. Den rå token persisteres ikke (spec §7 punkt 5) og står
	 * heller ikke i advarslen: den er kundens data og ender i logs.
	 */
	UNKNOWN_ENUM_VALUE,

	/**
	 * Et velformet sprogtag, vi ikke renderer endnu (ikke i
	 * {@code SupportedLocales.ALL}). Teksten GEMMES — kunden må
	 * gerne ligge foran os — men fladen viser den ikke, før bundlen findes.
	 */
	UNSUPPORTED_LOCALE;

	/**
	 * Om koden er en advarsel og ikke en afvisning.
	 *
	 * <p>Reglen står ét sted, så en ny advarsel ikke skal huskes to steder.
	 * {@link CatalogWarning} håndhæver den ikke: ingest-servicen skal kunne
	 * advare om noget, parseren ikke kender, uden at typen står i vejen.
	 */
	public boolean isWarning() {
		return this == UNKNOWN_ENUM_VALUE || this == UNSUPPORTED_LOCALE;
	}

}

package dk.aftermarked.localization;

import java.util.Objects;
import java.util.UUID;

/**
 * Én oversat værdi i den form, {@link Translations#replaceAll} tager imod dem:
 * entitetens id, feltnavnet, sprogkoden og teksten. {@code entity_type} og
 * {@code operator_id} står bevidst <em>ikke</em> her — entitetstypen er den
 * samme for hele batchen og gives som argument, og operatøren kommer fra
 * {@code OperatorContext}, aldrig fra en række kalderen selv har fyldt ud.
 *
 * <p>Sprogkoden er en {@code String} og ikke en {@code Locale}, fordi rækken
 * beskriver præcis det, der står i kolonnen {@code translation.locale}: den
 * kanoniske sprogkode ({@code da}, {@code en}, {@code en-GB}), som CHECK-en i
 * V3 kræver. Et {@code Locale} ville invitere til, at to forskellige stavemåder
 * af samme sprog blev til to rækker.
 *
 * <p><strong>Hvorfor NUL-tegnet afvises her.</strong> Batchen sendes til
 * databasen som ét jsonb-dokument, og PostgreSQL kan slet ikke lagre
 * {@code U+0000} i en {@code text}-værdi. Uden denne kontrol ville tegnet få
 * enten driveren eller jsonb-parseren til at fejle midt i en transaktion med en
 * besked, der ikke peger på den række, der er noget galt med. Her er fejlen i
 * stedet knyttet til den værdi, den handler om.
 *
 * <p>Blanke værdier afvises derimod bevidst <em>ikke</em> her: reglen "en tom
 * oversættelse er ikke en oversættelse" ligger i CHECK-en
 * {@code translation_value_not_blank} (V3), og den skal blive ved med at være
 * databasens svar — ellers findes reglen to steder og kan komme til at være
 * uenig med sig selv.
 */
public record TranslationRow(UUID entityId, String field, String locale, String value) {

	/**
	 * Tegnet PostgreSQL ikke kan lagre i en {@code text}-kolonne.
	 *
	 * <p>Skrevet som den <em>oktale</em> escape {@code '\0'} med vilje. Den
	 * anden stavemåde — en unicode-escape — erstattes af oversætteren
	 * <em>før</em> tokeniseringen, så den ville lægge et råt NUL-tegn i selve
	 * kildefilen.
	 */
	private static final char NUL = '\0';

	public TranslationRow {
		Objects.requireNonNull(entityId, "entityId");
		Objects.requireNonNull(field, "field");
		Objects.requireNonNull(locale, "locale");
		Objects.requireNonNull(value, "value");
		// Vaerdien selv navngives ALDRIG i beskeden -- den er kundens tekst, og
		// en exception-besked er udviklervendt og havner i loggen.
		if (value.indexOf(NUL) >= 0) {
			throw new IllegalArgumentException("The translation of [" + field + "/" + locale + "] on entity "
					+ entityId + " contains a NUL character (U+0000), which PostgreSQL text cannot store");
		}
	}

}

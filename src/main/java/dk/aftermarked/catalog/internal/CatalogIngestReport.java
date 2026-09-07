package dk.aftermarked.catalog.internal;

import java.util.List;
import java.util.Objects;

/**
 * Hvad ét katalog-push faktisk gjorde — pr. niveau, plus de advarsler,
 * parseren samlede undervejs.
 *
 * <p>Rapporten er sprintets EXIT-KRITERIE gjort målbart: "push to gange, og
 * anden gang ændrer ingenting". Et no-op-push giver
 * {@code created = 0, updated = 0} på alle tre niveauer, nul skrevne og nul
 * fjernede oversættelser — og skriver dermed heller ingen audit-linjer, som er
 * det andet, uafhængige bevis.
 *
 * <p><strong>{@code unchanged} er et SUCCES-tal, ikke en fejl.</strong>
 * Upsertens {@code WHERE ... IS DISTINCT FROM ...} springer den uændrede række
 * over, så {@code .update()} giver 0. Kode, der behandler 0 som en fejl,
 * forvandler hvert no-op-push til en falsk alarm klokken 03.
 *
 * <p>Typen ligger i {@code internal} sammen med resten af ingest-modellen
 * (spec-beslutning #11): den rejser sammen med {@link CatalogWarning}, som
 * bærer {@link CatalogErrorCode}.
 *
 * @param organizers hvad der skete på arrangør-niveau
 * @param events hvad der skete på event-niveau
 * @param ticketTypes hvad der skete på billettype-niveau
 * @param translationsWritten oversættelsesrækker, der faktisk ændrede sig.
 *     No-op-guarden på {@code Translations.put} gør tallet sandt: et identisk
 *     andet push skriver nul
 * @param translationsRemoved oversættelsesrækker, mængdeerstatningen fjernede,
 *     scopet til de (entitet, felt)-par pushet ejer
 * @param warnings advarslerne i payloadens egen rækkefølge. Bærer aldrig
 *     kundens værdier
 */
public record CatalogIngestReport(LevelCounts organizers, LevelCounts events, LevelCounts ticketTypes,
		int translationsWritten, int translationsRemoved, List<CatalogWarning> warnings) {

	public CatalogIngestReport {
		Objects.requireNonNull(organizers, "organizers");
		Objects.requireNonNull(events, "events");
		Objects.requireNonNull(ticketTypes, "ticketTypes");
		Objects.requireNonNull(warnings, "warnings");
		warnings = List.copyOf(warnings);
		requireNotNegative("translationsWritten", translationsWritten);
		requireNotNegative("translationsRemoved", translationsRemoved);
	}

	/**
	 * Om pushet ændrede noget som helst.
	 *
	 * <p>Findes som metode og ikke som en sammenligning ude i kalderne: reglen
	 * skal stå ét sted, den dag et fjerde tal kommer til, og det er præcis den
	 * regel, idempotens-testen og "skriv ingen audit-linjer"-beslutningen begge
	 * hviler på.
	 */
	public boolean changedNothing() {
		return this.organizers.changedNothing() && this.events.changedNothing()
				&& this.ticketTypes.changedNothing()
				&& this.translationsWritten == 0 && this.translationsRemoved == 0;
	}

	private static void requireNotNegative(String name, int count) {
		if (count < 0) {
			throw new IllegalArgumentException("The count " + name + " must not be negative");
		}
	}

	/**
	 * Tre tal for ét niveau. {@code unchanged} tælles med vilje MED: uden det kan
	 * en læser ikke skelne "pushet rørte ingenting" fra "pushet bar ingenting", og
	 * det er netop den forskel, en kundes cron-bug ville gemme sig i.
	 *
	 * @param created rækker, der ikke fandtes før
	 * @param updated rækker, hvis værdier faktisk ændrede sig
	 * @param unchanged rækker, pushet bar, og som allerede stod rigtigt
	 */
	public record LevelCounts(int created, int updated, int unchanged) {

		public LevelCounts {
			requireNotNegative("created", created);
			requireNotNegative("updated", updated);
			requireNotNegative("unchanged", unchanged);
		}

		/** Det tomme niveau — dét et push uden entiteter på niveauet giver. */
		public static LevelCounts none() {
			return new LevelCounts(0, 0, 0);
		}

		/** Om intet blev skrevet på niveauet. En uændret række er ikke en skrivning. */
		public boolean changedNothing() {
			return this.created == 0 && this.updated == 0;
		}

		/** Hvor mange rækker pushet i alt bar på niveauet. */
		public int total() {
			return this.created + this.updated + this.unchanged;
		}

	}

}

-- V4 — operatoerens basissprog og valutatilstand.
--
-- To kolonner paa `operator`, begge med DEFAULT, saa migrationen er
-- N-1-kompatibel: en replika, der ikke kender kolonnerne, INSERT'er videre uden
-- dem, og defaulten udfylder. Ingen eksisterende raekke og ingen eksisterende
-- seeder roeres.
--
-- 1) default_locale er operatoerens EGET basissprog (PLAN 2.9). Det er den
--    vaerdi, den flersprogede importform SKAL indeholde: "objektformen SKAL
--    indeholde operatoerens basissprog", ellers kan fallback-kaedens trin 4 staa
--    tom. Indtil nu har reglen kun kunnet maales mod den GLOBALE konstant
--    LocalizationConfig.DEFAULT_LOCALE = da, som PLAN selv kalder en
--    midlertidighed -- og som ville afvise en svensk kundes fuldt gyldige
--    {"sv": ..., "en": ...} for ikke at indeholde dansk. Kolonnen er dermed en
--    FORUDSAETNING for katalog-importen i V5, ikke en pyntekolonne.
--
--    Bemaerk: den per-request locale-resolver, der OGSAA skal bruge kolonnen,
--    er en runde for sig (PLAN 2.9). Her laeses den kun af importen.
--
--    DEFAULT 'da' er ikke stil. Otte filer indsaetter i `operator` uden at
--    naevne kolonnen, og tre af dem ligger i invariant-mapper, hvor de ikke maa
--    aendres. NOT NULL uden default ville goere dem roede i tests, der intet har
--    med sprog at goere.
--
-- 2) single_currency er politikken "alle events hos DENNE operatoer deler
--    valuta" (PLAN 2.5). Den staar som DATA og ikke som en constraint, og det
--    er et bevidst valg med en grund, der ikke er smag:
--      * MigrationDisciplineInvariantTest forbyder DROP CONSTRAINT for altid,
--        saa en CHECK eller en FK ville vaere PERMANENT.
--      * PLAN lover, at den internationale kunde koster "kun at slette
--        validering (2) -- een linje".
--      * CLAUDE.md forbyder at slette eller svaekke en invariant-test.
--    De tre loefter kan KUN holdes samtidig, hvis reglen er data: saa beskriver
--    invariant-testen BEGGE tilstande af flaget og skal aldrig roeres, og den
--    internationale kunde er en UPDATE uden deploy.
--
--    Reglen er PER OPERATOER. PLAN 2.5 skriver "alle events i instansen", men
--    den saetning er fra 15/8, FOER beslutningen 27/8 om een delt instans for
--    alle kunder. Laest ordret i dag ville den forbyde en dansk og en norsk
--    kunde at ligge i samme database -- hvilket er hele arkitekturen.

ALTER TABLE operator
    ADD COLUMN default_locale text NOT NULL DEFAULT 'da';

ALTER TABLE operator
    ADD COLUMN single_currency boolean NOT NULL DEFAULT true;

-- PRAECIS samme regex som V3's translation_locale_format. Bevidst identisk: en
-- operatoer, hvis basissprog ikke kan staa i translation.locale, ville goere
-- fallback-kaeden uskrivelig. To constraints, der skal vaere enige om, hvad en
-- sprogkode er, skrives ens med vilje.
ALTER TABLE operator
    ADD CONSTRAINT operator_default_locale_format CHECK (default_locale ~ '^[a-z]{2}(-[A-Z]{2})?$');

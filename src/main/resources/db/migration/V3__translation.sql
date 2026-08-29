-- Oversaettelser af BRUGERENS egne tekster (operatoernavn, senere event-titler,
-- beskrivelser). Systemets egne tekster ligger i messages*.properties og hoerer
-- ikke til her -- de foelger koden, ikke kunden.
--
-- SIDECAR frem for kolonner: tabellen roerer ingen eksisterende tabel og
-- tilfoejer hverken kolonne eller constraint til `operator`. Det er
-- expand-migrate-contract i sin reneste form -- en N-1-replika, der intet ved om
-- oversaettelser, koerer videre uberoert, fordi grundfeltet (fx operator.name)
-- stadig baerer teksten. Oversaettelsen er et LAG OVENPAA, aldrig erstatningen.
--
-- Alternativet -- en jsonb-kolonne pr. felt -- ville kraeve en ALTER TABLE pr.
-- entitet, vi vil oversaette, og goere hvert nyt felt til en skemaaendring.

CREATE TABLE translation (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id  uuid        NOT NULL REFERENCES operator (id) ON DELETE CASCADE,
    entity_type  text        NOT NULL,
    entity_id    uuid        NOT NULL,
    field        text        NOT NULL,
    locale       text        NOT NULL,
    value        text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),

    -- Sprogkode, evt. med region: 'da', 'en', 'en-GB'. Bevidst LOESERE end de
    -- sprog, fladen understoetter i dag: databasen skal kunne baere en
    -- oversaettelse, foer UI'et faar knappen til den. En strammere regel kan
    -- tilfoejes senere; en loesere kan ikke fjernes uden at tabe data.
    CONSTRAINT translation_locale_format      CHECK (locale ~ '^[a-z]{2}(-[A-Z]{2})?$'),

    -- entity_type og field indgaar i opslagsnoeglen og skrives af kode, ikke af
    -- brugere. Snake_case holder dem sammenlignelige paa tvaers af moduler.
    CONSTRAINT translation_entity_type_format CHECK (entity_type ~ '^[a-z][a-z0-9_]{0,61}$'),
    CONSTRAINT translation_field_format       CHECK (field ~ '^[a-z][a-z0-9_]{0,61}$'),

    -- En tom oversaettelse er ikke en oversaettelse. Uden denne ville et blankt
    -- felt skygge for grundteksten og vise brugeren ingenting.
    --
    -- '\S' (mindst eet ikke-whitespace-tegn) og IKKE btrim(value) <> '': btrim
    -- uden andet argument trimmer kun MELLEMRUM, saa en vaerdi bestaaende af et
    -- tabulatortegn eller et linjeskift ville slippe igennem.
    CONSTRAINT translation_value_not_blank    CHECK (value ~ '\S'),

    -- Een oversaettelse pr. felt pr. sprog. Indekset baerer ogsaa opslaget
    -- (operator_id, entity_type, entity_id) som praefiks.
    CONSTRAINT translation_unique             UNIQUE (operator_id, entity_type, entity_id, field, locale)
);

-- PLAN 2.7 regel 1: tabellen baerer operator_id, saa RLS og policy foelger med
-- HER -- i samme migration som tabellen, ikke i en oprydningsrunde bagefter.
ALTER TABLE translation ENABLE ROW LEVEL SECURITY;

-- Samme form som operator_tenant_isolation: uden FOR og uden TO, saa den daekker
-- alle kommandoer for alle roller. USING styrer SELECT/UPDATE/DELETE, WITH CHECK
-- styrer de raekker INSERT og UPDATE efterlader. Mangler den ene halvdel, findes
-- hullet kun i den ene retning.
CREATE POLICY translation_tenant_isolation ON translation
    USING      (operator_id = current_operator_id())
    WITH CHECK (operator_id = current_operator_id());

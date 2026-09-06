-- V5 — kataloget: arrangoer, event, billettype -- plus audit-loggen.
--
-- Kataloget er et SPEJL af kundens data (PLAN 2.2). Ejerskabet ligger hos
-- kunden; vi holder en kopi til visning og regler. Derfor er kundens egen
-- stabile reference (`ref`) den eneste naturlige noegle -- vores uuid er
-- internt og staar aldrig i kundens system.
--
-- Fire tabeller, fire RLS-policies, i SAMME migration som tabellerne
-- (PLAN 2.7 regel 1). Ud over policyen bruges tre moenstre, som RLS IKKE giver
-- gratis:
--
--   1. TENANT-SAMMENSATTE FREMMEDNOEGLER. RLS gaelder IKKE for referentiel
--      integritet: Postgres tjekker fremmednoegler med sin egen snapshot og
--      uden policy. En FK paa een kolonne mod organizer (id) kan derfor pege
--      paa en ANDEN kundes arrangoer, hvis nogen kender uuid-vaerdien --
--      verificeret paa Postgres 18.6, hvor netop det gav INSERT 0 1. Hver FK
--      peger derfor paa (operator_id, ...), og hver foraeldretabel faar en
--      UNIQUE at pege paa. Den ser overfloedig ud, fordi id allerede er
--      PRIMARY KEY, men Postgres kraever, at en FK peger paa PRAECIS de
--      kolonner, en UNIQUE eller PRIMARY KEY daekker.
--
--   2. UNIKKE NOEGLER STARTER MED operator_id. En global UNIQUE ville vaere et
--      orakel i sig selv: fejlen afsloerer, at en anden kunde bruger den ref, og
--      ON CONFLICT DO NOTHING ville rapportere succes uden at skrive noget. Med
--      operator_id foerst er noeglerummene disjunkte, og to kunder kan frit
--      bruge samme ref.
--
--   3. VALUTAEN LIGGER I FREMMEDNOEGLEN. ticket_type peger paa
--      (operator_id, id, currency) i event, saa "eet event = een valuta"
--      (PLAN 2.5) haandhaeves af databasen og ikke af disciplin.
--
-- Punkt 1 og 3 er KOERT mod en rigtig Postgres 18.6 (2026-09-05) og ikke
-- raesonneret: en krydstenant-reference og en billettype i EUR paa et
-- DKK-event blev begge afvist, mens den samme billettype i DKK gik igennem.
--
-- Om operator_id: kolonnen findes paa hver tabel, men den findes IKKE som felt
-- paa noget Java-record, og INGEN insert binder den som parameter. Vaerdien
-- skrives altid som current_operator_id() i selve SQL'en. Der findes dermed
-- ingen kodesti, der KAN baere et forkert tenant-id ind i en indsaettelse.
--
-- Om CHECK-lister: en lukket IN-liste kan kun STRAMMES senere, aldrig loesnes,
-- fordi MigrationDisciplineInvariantTest forbyder DROP CONSTRAINT for altid.
-- Derfor staar FORMATET i databasen og den lukkede enum i Java -- praecis som
-- V3 goer det for locale. S4 (listing-overgange) og S6 (roede knapper) faar
-- begge brug for nye actor_type-vaerdier.
--
-- Om sletning: der er BEVIDST ingen archived_at og ingen tombstones. Fravaer
-- fra et push kan ikke betyde sletning uden at bryde PLAN 2.2's loefte om, at
-- et fuldt dump og et delta giver samme resultat -- og et "full"-push, der
-- ankommer tomt (kundens cron-bug, en auth-fejl der returnerer {}), ville
-- tomme hele kataloget i stilhed kl. 03. En nullable kolonne senere er en
-- ikke-destruktiv ALTER TABLE og kraever ingen spekulativ stillads nu.

CREATE TABLE organizer (
    id                           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id                  uuid        NOT NULL REFERENCES operator (id) ON DELETE CASCADE,
    -- Kundens egen reference. Staar i URL-stien (/o/{organizerRef}), saa den
    -- skal vaere URL-sikker uden escaping. Vaerdien '_default' er reserveret til
    -- den arrangoer, VI syntetiserer for kunder, der ikke skelner (PLAN 2.3).
    -- Constrainten TILLADER den, fordi vi selv skriver raekken -- validatoren i
    -- Java AFVISER den fra et katalog-push, saa en kunde aldrig kan omdoebe den
    -- arrangoer, alle skelet-events haenger paa.
    ref                          text        NOT NULL,
    name                         text        NOT NULL,
    -- Vores flag, ikke kundens. Katalog-pushet skriver det ALDRIG.
    is_default                   boolean     NOT NULL DEFAULT false,
    -- Feltskemaets custom felter (PLAN 2.6). jsonb og ikke json: jsonb
    -- normaliserer noegle-raekkefoelge, whitespace og dubletter, saa to
    -- semantisk ens objekter sammenlignes ens. Det er netop DET, upsertens
    -- IS DISTINCT FROM hviler paa -- `json` har slet ingen lighedsoperator.
    --
    -- Kolonnen hedder custom_fields og ikke fields, saa en kilde-scanning kan
    -- soege efter ordet uden falske alarmer: penge-, saga- og
    -- allokeringsmoduler maa aldrig naevne det (PLAN 2.6, DoD punkt 4), og
    -- Modulith ser typeafhaengigheder -- ikke SQL i en tekstblok.
    custom_fields                jsonb       NOT NULL DEFAULT '{}'::jsonb,
    -- Hvilken version af operatoerens feltskema vaerdierne blev VALIDERET under.
    -- Stemplet beskriver vaerdierne, saa det opdateres kun, naar vaerdierne selv
    -- aendrer sig. Det goer den forskudte rollout laesbar: replika B paa v1 skal
    -- kunne se, at raekken er skrevet af replika A paa v2 -- og lade felterne
    -- staa.
    custom_fields_schema_version integer     NOT NULL DEFAULT 0,
    version                      integer     NOT NULL DEFAULT 1,
    created_at                   timestamptz NOT NULL DEFAULT now(),
    updated_at                   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT organizer_ref_format          CHECK (ref ~ '^[A-Za-z0-9][A-Za-z0-9._~-]{0,63}$' OR ref = '_default'),
    CONSTRAINT organizer_name_not_blank      CHECK (name ~ '\S'),
    CONSTRAINT organizer_fields_is_object    CHECK (jsonb_typeof(custom_fields) = 'object'),
    CONSTRAINT organizer_schema_version_sane CHECK (custom_fields_schema_version >= 0),
    CONSTRAINT organizer_version_positive    CHECK (version > 0),
    CONSTRAINT organizer_unique_ref          UNIQUE (operator_id, ref),
    -- Foraeldrenoegle for event'ets sammensatte FK nedenfor.
    CONSTRAINT organizer_tenant_key          UNIQUE (operator_id, id)
);

-- Hoejst een default-arrangoer pr. operatoer. PARTIELT unikt INDEKS og ikke en
-- constraint: en UNIQUE-constraint kan ikke vaere partiel i Postgres
-- (CONSTRAINT c UNIQUE (a) WHERE b er en syntaksfejl). Konsekvens for koden:
-- indekset kan ikke vaere DEFERRABLE, saa et skift af default SKAL vaere to
-- saetninger (ryd, saet) i een transaktion. Etlinjers-varianten
-- SET is_default = (id = :id) lykkes eller fejler afhaengigt af raekkernes
-- FYSISKE raekkefoelge -- verificeret begge veje. Den virker i dev og fejler i
-- produktion.
CREATE UNIQUE INDEX organizer_one_default_per_operator_idx ON organizer (operator_id) WHERE is_default;

CREATE TABLE event (
    id                           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id                  uuid        NOT NULL REFERENCES operator (id) ON DELETE CASCADE,
    organizer_id                 uuid        NOT NULL,
    ref                          text        NOT NULL,
    -- NOT NULL med vilje (PLAN 2.9: fallback-kaedens trin 4 er grundfeltet paa
    -- entiteten). Et skelet-event fra verify har ingen titel og faar sin EGEN
    -- ref som titel; foerste katalog-push retter den.
    title                        text        NOT NULL,
    venue                        text,
    starts_at                    timestamptz NOT NULL,
    ends_at                      timestamptz,
    -- Driver primaersalgs-adfaerden og venteliste-pausen (PLAN 2.6). Formatet
    -- staar her, de lovlige vaerdier i Java, hvor UNKNOWN er den neutrale
    -- fallback (DoD punkt 9): baade et skelet-event fra lazy enrichment og en
    -- vaerdi, kunden sender, som vi ikke kender, lander der. UNKNOWN er aldrig
    -- "til salg" og genoptager aldrig ventelisten.
    --
    -- NOT NULL og ikke nullable: en nullable kolonne med en Optional-komponent
    -- paa aggregatet betyder, at en admin-redigering GENNEM aggregatet skriver
    -- NULL og sletter kundens egen vaerdi i stilhed. Kolonne og record skal
    -- kunne udtrykke praecis det samme.
    sales_status                 text        NOT NULL,
    sales_ends_at                timestamptz,
    -- Kernekolonne og IKKE et custom felt. PLAN 2.6 siger, at custom felter
    -- aldrig maa paavirke adfaerd -- og PLAN 2.2/2.6 siger samtidig, at
    -- ventelisten AFHAENGER af primaryPurchaseUrl (den staar i "godt nyt"-mailen
    -- og styrer, hvor koeberen sendes hen). Et adfaerdsbaerende felt kan derfor
    -- ikke ligge i feltvaerdi-lageret.
    --
    -- Formatet er bevidst LOEST (baade http og https), fordi en CHECK ikke kan
    -- loesnes igen. Politikken -- kun https -- ligger i Java, hvor den kan
    -- aendres uden en migration.
    primary_purchase_url         text,
    -- eet event = een valuta (PLAN 2.5). Ingen transaktion krydser events, saa
    -- veksling findes ikke i systemet. At alle events hos EEN operatoer deler
    -- valuta er derimod POLITIK og ligger i operator.single_currency (V4).
    currency                     text        NOT NULL,
    custom_fields                jsonb       NOT NULL DEFAULT '{}'::jsonb,
    custom_fields_schema_version integer     NOT NULL DEFAULT 0,
    version                      integer     NOT NULL DEFAULT 1,
    created_at                   timestamptz NOT NULL DEFAULT now(),
    updated_at                   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT event_ref_format          CHECK (ref ~ '^[A-Za-z0-9][A-Za-z0-9._~-]{0,63}$'),
    CONSTRAINT event_title_not_blank     CHECK (title ~ '\S'),
    CONSTRAINT event_venue_not_blank     CHECK (venue IS NULL OR venue ~ '\S'),
    CONSTRAINT event_currency_format     CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT event_sales_status_format CHECK (sales_status ~ '^[A-Z][A-Z0-9_]{0,31}$'),
    -- Laengdegraensen ligger i length() og ikke i moenstret: Postgres' regex-motor
    -- tillader hoejst 255 i en {m,n}-graense, og '\S{1,2000}' fejler foerst ved
    -- den FOERSTE INSERT med en URL -- ikke ved CREATE TABLE.
    CONSTRAINT event_purchase_url_format CHECK (primary_purchase_url IS NULL
                                                OR (primary_purchase_url ~ '^https?://\S+$'
                                                    AND length(primary_purchase_url) <= 2000)),
    CONSTRAINT event_ends_after_start    CHECK (ends_at IS NULL OR ends_at >= starts_at),
    CONSTRAINT event_fields_is_object    CHECK (jsonb_typeof(custom_fields) = 'object'),
    CONSTRAINT event_schema_version_sane CHECK (custom_fields_schema_version >= 0),
    CONSTRAINT event_version_positive    CHECK (version > 0),
    CONSTRAINT event_unique_ref          UNIQUE (operator_id, ref),
    -- Foraeldrenoegle for billettypens tre-kolonners FK nedenfor.
    CONSTRAINT event_tenant_currency_key UNIQUE (operator_id, id, currency),
    -- ON DELETE CASCADE er noedvendig, ikke bekvem: uden den ville
    -- DELETE FROM operator forsoege at slette arrangoeren, mens events stadig
    -- peger paa den. Otte testklasser rydder op med praecis den saetning mod den
    -- delte Spring-kontekst, og tre af dem ligger i invariant-mapper. ON UPDATE
    -- staar bevidst tilbage paa NO ACTION.
    --
    -- Fler-vejs-kaskaden (operator -> organizer -> event OG operator -> event)
    -- er koert mod 18.6 (2026-09-05): eet DELETE FROM operator toemte alle tre
    -- katalogtabeller uden konflikt.
    CONSTRAINT event_organizer_fk        FOREIGN KEY (operator_id, organizer_id)
                                         REFERENCES organizer (operator_id, id) ON DELETE CASCADE
);

CREATE TABLE ticket_type (
    id                           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id                  uuid        NOT NULL REFERENCES operator (id) ON DELETE CASCADE,
    event_id                     uuid        NOT NULL,
    ref                          text        NOT NULL,
    name                         text        NOT NULL,
    -- Beloeb er heltal i mindste enhed + ISO-4217-kode (CLAUDE.md). Aldrig
    -- numeric, aldrig float, aldrig et beloeb uden sin valuta ved siden af.
    -- bigint og ikke integer: JPY har nul decimaler, og 2,1 mia. oere er kun
    -- 21 mio. kr.
    --
    -- currency er RAEKKENS og ikke beloebets: eet event = een valuta, saa alle
    -- beloeb i raekken deler den. En valutakolonne pr. beloeb ville tillade en
    -- tilstand, domaenet forbyder.
    face_value_minor             bigint      NOT NULL,
    currency                     text        NOT NULL,
    custom_fields                jsonb       NOT NULL DEFAULT '{}'::jsonb,
    custom_fields_schema_version integer     NOT NULL DEFAULT 0,
    version                      integer     NOT NULL DEFAULT 1,
    created_at                   timestamptz NOT NULL DEFAULT now(),
    updated_at                   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ticket_type_ref_format          CHECK (ref ~ '^[A-Za-z0-9][A-Za-z0-9._~-]{0,63}$'),
    CONSTRAINT ticket_type_name_not_blank      CHECK (name ~ '\S'),
    CONSTRAINT ticket_type_currency_format     CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ticket_type_face_value_sane     CHECK (face_value_minor >= 0),
    CONSTRAINT ticket_type_fields_is_object    CHECK (jsonb_typeof(custom_fields) = 'object'),
    CONSTRAINT ticket_type_schema_version_sane CHECK (custom_fields_schema_version >= 0),
    CONSTRAINT ticket_type_version_positive    CHECK (version > 0),
    -- Kundens billettype-ref er kun unik INDEN I eventet.
    CONSTRAINT ticket_type_unique_ref          UNIQUE (operator_id, event_id, ref),
    -- Tre kolonner i een FK. Den binder billettypen til samme operatoer OG til
    -- eventets valuta: en billettype i EUR paa et DKK-event kan ikke gemmes, og
    -- eventets valuta kan ikke aendres, mens der haenger billettyper paa det.
    --
    -- ON UPDATE staar bevidst paa NO ACTION (defaulten): en valutaaendring paa
    -- et levende event er en PENGEHAENDELSE, ikke en katalogrettelse, og skal
    -- stoppe et natligt push og eskalere til et menneske. ON UPDATE CASCADE er
    -- udelukket -- den ville omskrive pengenaer data uden versionsbump og uden
    -- en linje i audit-loggen.
    --
    -- ALLE TRE KOLONNER SKAL FORBLIVE NOT NULL. MATCH SIMPLE er defaulten, og
    -- den springer HELE tjekket over, hvis bare een FK-kolonne er NULL.
    CONSTRAINT ticket_type_event_fk            FOREIGN KEY (operator_id, event_id, currency)
                                               REFERENCES event (operator_id, id, currency) ON DELETE CASCADE
);

-- Audit-loggen (PLAN 2.3, SPRINTS S2): hvem gjorde hvad hvornaar.
--
-- bigint IDENTITY og ikke uuid: loggen laeses altid i raekkefoelge, og en
-- monoton noegle giver baade sortering og stabil paginering. To raekker skrevet
-- i samme transaktion deler now(), saa der sorteres paa id og ikke paa tid.
CREATE TABLE audit_log (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- ON DELETE CASCADE, modsat hvad man foerst taenker om en log. To grunde:
    -- at slette en operatoer er en afvikling af HELE kunden, og en log uden
    -- sletningsvej ville goere offboarding umulig, nu hvor DELETE er trukket
    -- tilbage fra applikationen. Og otte testklasser rydder op med
    -- DELETE FROM operator mod den delte Spring-kontekst -- uden kaskade ville
    -- de fejle paa en fremmednoegle, saa snart der fandtes een audit-raekke, og
    -- med en FK-loes kolonne ville naeste test arve forrige tests audit-spor,
    -- fordi seederne genbruger faste uuid'er.
    operator_id  uuid        NOT NULL REFERENCES operator (id) ON DELETE CASCADE,
    occurred_at  timestamptz NOT NULL DEFAULT now(),
    -- Formatet i databasen, de lovlige aktoerer i Java: S4 tilfoejer seller og
    -- buyer, S6 og S9 tilfoejer flere admin-handlinger, og en IN-liste kan ikke
    -- loesnes bagefter.
    actor_type   text        NOT NULL,
    actor_ref    text,
    action       text        NOT NULL,
    entity_type  text        NOT NULL,
    -- Bevidst UDEN fremmednoegle: subjektet kan vaere vaek, og loggen skal
    -- overleve det. entity_ref staar ved siden af, saa raekken kan laeses af et
    -- menneske, ogsaa naar id-vaerdien ikke findes laengere.
    entity_id    uuid,
    entity_ref   text,
    before_state jsonb,
    after_state  jsonb,
    CONSTRAINT audit_log_actor_type_format  CHECK (actor_type ~ '^[a-z][a-z0-9_]{0,31}$'),
    CONSTRAINT audit_log_action_format      CHECK (action ~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$'),
    CONSTRAINT audit_log_entity_type_format CHECK (entity_type ~ '^[a-z][a-z0-9_]{0,61}$'),
    CONSTRAINT audit_log_has_a_subject      CHECK (entity_id IS NOT NULL OR entity_ref IS NOT NULL)
);

ALTER TABLE organizer   ENABLE ROW LEVEL SECURITY;
ALTER TABLE event       ENABLE ROW LEVEL SECURITY;
ALTER TABLE ticket_type ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log   ENABLE ROW LEVEL SECURITY;

-- Samme form som operator_tenant_isolation og translation_tenant_isolation:
-- uden FOR og uden TO, saa policyen daekker alle kommandoer for alle roller.
-- USING styrer SELECT/UPDATE/DELETE, WITH CHECK styrer de raekker, INSERT og
-- UPDATE efterlader. Mangler den ene halvdel, findes hullet i den ene retning.
CREATE POLICY organizer_tenant_isolation ON organizer
    USING      (operator_id = current_operator_id())
    WITH CHECK (operator_id = current_operator_id());

CREATE POLICY event_tenant_isolation ON event
    USING      (operator_id = current_operator_id())
    WITH CHECK (operator_id = current_operator_id());

CREATE POLICY ticket_type_tenant_isolation ON ticket_type
    USING      (operator_id = current_operator_id())
    WITH CHECK (operator_id = current_operator_id());

CREATE POLICY audit_log_tenant_isolation ON audit_log
    USING      (operator_id = current_operator_id())
    WITH CHECK (operator_id = current_operator_id());

-- APPEND-ONLY for applikationen. V1's ALTER DEFAULT PRIVILEGES giver automatisk
-- SELECT/INSERT/UPDATE/DELETE paa alle FREMTIDIGE tabeller til theticket_app,
-- saa loggen ville som udgangspunkt kunne omskrives af praecis den kode, den
-- skal holde oeje med. Login-rollen theticket_runtime har ingen rettigheder ud
-- over dem, den arver fra gruppen (deploy/postgres/init/01-roles.sh giver kun
-- medlemskab), saa en tilbagetraekning fra gruppen er nok.
--
-- Retten til at toemme tabellen blev aldrig givet og arves ikke af sig selv.
-- Skriv den derfor ALDRIG i en REVOKE: selve ordet staar i
-- MigrationDisciplineInvariantTest's DESTRUCTIVE-regex og goer bygget roedt.
--
-- Tilbagetraekningen og kaskaden ovenfor er koert SAMMEN mod 18.6
-- (2026-09-05): som runtime-rollen fejler baade UPDATE og DELETE paa audit_log
-- med "permission denied", mens DELETE FROM operator stadig kaskaderer
-- audit-raekkerne vaek. Referentielle handlinger tjekker ikke den kaldende
-- rolles rettigheder, saa append-only og en offboarding-vej kan holdes
-- samtidig. AuditLogInvariantTest holder begge dele i live.
--
-- Aerligt om graensen, og det skal med i commit-beskeden: dette goer loggen
-- append-only for APPLIKATIONEN. Tabelejeren (Flyway-brugeren) kan stadig
-- omskrive den. AEgte manipulationsbevis kraever en hash-kaede, og den er
-- krypto-naer -- altsaa en egen stop-zone-beslutning.
REVOKE UPDATE, DELETE ON audit_log FROM theticket_app;
REVOKE ALL ON audit_log FROM PUBLIC;

-- Laesestierne fra PLAN 2.6. HVERT indeks starter med operator_id, fordi
-- RLS-policyen laegger operator_id = current_operator_id() paa ENHVER
-- forespoergsel. current_operator_id() er STABLE (V1), saa planlaeggeren maa
-- bruge den som indeksnoegle -- var den VOLATILE, blev policyen et filter oven
-- paa en fuld scanning af hver eneste tenant-tabel.
--
-- Opslagene /o/{organizerRef} og /e/{eventRef} har allerede deres indeks i
-- UNIQUE-constraints ovenfor, og billettyperne paa eet event daekkes af
-- praefikset (operator_id, event_id) i ticket_type_unique_ref. Kun de to
-- tidssorterede lister mangler.
--
-- Der oprettes med vilje INTET jsonb-indeks: custom_fields laeses via
-- primaernoeglen og er aldrig et praedikat. Et GIN-indeks ville koste
-- skriveforstaerkning paa hvert eneste natligt push for nul laesegevinst. Og
-- intet indeks paa sales_status eller title: PLAN 5 maaler katalog-laesninger
-- til ca. 0,7 i sekundet, og hvert spekulativt indeks er ren skriveomkostning.
CREATE INDEX event_by_start_idx           ON event (operator_id, starts_at);
CREATE INDEX event_by_organizer_start_idx ON event (operator_id, organizer_id, starts_at);

-- Operatoer-admin laeser loggen to veje: alt om een entitet, og det seneste
-- foerst. id DESC og ikke occurred_at DESC -- to raekker skrevet i samme
-- transaktion deler now(), og en sortering med uafgjorte pladser pagineres
-- forkert (raekker springes over og gentages).
CREATE INDEX audit_log_by_entity_idx ON audit_log (operator_id, entity_type, entity_id, id DESC);
CREATE INDEX audit_log_recent_idx    ON audit_log (operator_id, id DESC);

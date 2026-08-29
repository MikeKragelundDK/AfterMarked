-- V2 — Spring Modulith event publication registry: outboxen (PLAN §2.4, §2.7).
--
-- Tabellen ejes af Spring Modulith (spring-modulith-starter-jdbc). Kolonnerne
-- svarer til registrets eget v2-skema for PostgreSQL i version 2.1.1. Der er
-- ingen Hibernate og dermed ingen ddl-auto=validate til at fange en afvigelse
-- ved opstart (PLAN §3) — det goer EventPublicationSchemaInvariantTest i stedet.
--
-- BEVIDST UNDTAGELSE FRA RLS-REGLEN: tabellen har ingen operator_id, fordi
-- Modulith laeser og skriver den paa egen haand — bl.a. genudsendelse af
-- ufuldstaendige publikationer ved opstart, uden nogen operator-kontekst.
-- Konsekvensen er en regel for alt, der lytter paa events:
--
--   Enhver event, der publiceres gennem registret, SKAL baere operator-id i sin
--   payload, og lytteren SKAL binde konteksten selv (OperatorContext.runAs)
--   FOER den roerer tenant-data. Registret kender ikke tenanten — det goer
--   eventet.
--
-- Samme undtagelse gaelder flyway_schema_history. Invariant-testen scanner
-- kun tabeller med operator_id og rammer derfor ikke disse to.

CREATE TABLE event_publication (
    id                      uuid        PRIMARY KEY,
    listener_id             text        NOT NULL,
    event_type              text        NOT NULL,
    serialized_event        text        NOT NULL,
    publication_date        timestamptz NOT NULL,
    completion_date         timestamptz,
    status                  text        CHECK (status IN ('PUBLISHED', 'PROCESSING', 'COMPLETED', 'FAILED', 'RESUBMITTED')),
    completion_attempts     integer     NOT NULL DEFAULT 0,
    last_resubmission_date  timestamptz
);

-- Registret sporger efter ufuldstaendige publikationer (completion_date IS NULL)
-- og efter status — begge skal kunne besvares uden fuld scanning.
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);
CREATE INDEX event_publication_by_status_idx ON event_publication (status);

-- markCompleted(event, listener) slaar op paa HELE den serialiserede payload.
-- Uden indeks er det en fuld scanning, hver gang en event afsluttes. Et btree
-- kan ikke bruges: raekkegraensen paa 2704 bytes ville sprænge ved en stor
-- payload. Hash-indekset har ingen stoerrelsesgraense og understoetter praecis
-- den lighedssammenligning, opslaget laver. Det er ogsaa det indeks, Modulith
-- selv opretter i sit v2-skema, naar den staar for skemaet.
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);

-- Runtime-rollen faar rettigheder via ALTER DEFAULT PRIVILEGES fra V1.

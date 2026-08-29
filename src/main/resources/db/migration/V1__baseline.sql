-- V1 — baseline.
--
-- Den eneste tabel er `operator`: tenanten. Alt andet kommer i senere migrationer.
-- Men migrationen etablerer samtidig det moenster, HVER senere tabel med
-- `operator_id` skal gentage (PLAN §2.7, "Tenant-isolation — disciplinen"):
--
--   1. ENABLE ROW LEVEL SECURITY paa tabellen — i samme migration som tabellen.
--   2. En policy, der matcher raekkens operator mod current_operator_id().
--   3. Ingen grants ud over dem, runtime-rollen reelt skal bruge.
--
-- To roller:
--   * Migrationsbrugeren (den, Flyway koerer som) ejer alt og er den eneste,
--     der ser paa tvaers af tenants. Appen forbinder ALDRIG som den.
--   * `theticket_app` er en NOLOGIN-grupperolle, som denne migration giver
--     rettigheder til. Den faktiske login-rolle oprettes ved provisionering
--     (lokalt: deploy/postgres/init/01-roles.sh) som medlem af gruppen — og
--     har aldrig SUPERUSER eller BYPASSRLS. Invariant-testen kontrollerer det.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'theticket_app') THEN
        CREATE ROLE theticket_app NOLOGIN;
    END IF;
END
$$;

-- Operator-konteksten saettes af appen pr. forbindelse som session-variablen
-- app.operator_id (tenancy.TenantAwareDataSource). Er den ikke sat — eller
-- sat til '' — ser forespoergslen ingenting. Det er med vilje: et job uden
-- kontekst skal se nul raekker, ikke alle.
CREATE FUNCTION current_operator_id() RETURNS uuid
    LANGUAGE sql STABLE
AS $$
    SELECT NULLIF(current_setting('app.operator_id', true), '')::uuid
$$;

CREATE TABLE operator (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    slug        text        NOT NULL UNIQUE,
    name        text        NOT NULL,
    -- Vaertsnavnet er den ENESTE tilladte kilde til operator-kontekst
    -- (PLAN §2.6). Gemmes uden port og i smaa bogstaver.
    --
    -- To constraints, ikke én: `_lowercase` siger HVORFOR (kontekstopslaget
    -- lower()'er sit input, saa en raekke med store bogstaver ville vaere
    -- uopnaaelig), `_format` siger HVAD der er et lovligt vaertsnavn.
    -- Formatet afviser tom streng, mellemrum, portnummer (':'), understreg,
    -- jokertegn og alt over 253 tegn. Et vaertsnavn er den eneste noegle til
    -- en tenant; skrald deri er skrald i tenant-grænsen.
    hostname    text        NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT operator_hostname_lowercase CHECK (hostname = lower(hostname)),
    CONSTRAINT operator_hostname_format CHECK (hostname ~ '^[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?$'),
    CONSTRAINT operator_slug_format CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,62}$')
);

-- Hoenen og aegget: for at saette konteksten skal appen slaa vaertsnavnet op i
-- `operator`, men `operator` er selv RLS-beskyttet. Loesningen er en
-- SECURITY DEFINER-funktion, der koerer som ejeren (migrationsbrugeren) og
-- kun returnerer id'et — aldrig raekken. Det er det ene sted, appen laeser
-- paa tvaers af tenants, og den kan kun faa ét uuid ud af det.
--
-- pg_temp staar EKSPLICIT SIDST i search_path. Udelades pg_temp, soeger
-- Postgres det ALLIGEVEL — og som det FOERSTE skema, foer pg_catalog, naar
-- der slaas relationer op. En kaldende session, der laver
-- `CREATE TEMP TABLE operator (...)`, ville dermed faa sin egen tabel laest
-- inde i SECURITY DEFINER-funktionen og selv bestemme, hvilket operator-id
-- den fik tilbage. Temp-tabeller lever lige saa laenge som forbindelsen i
-- puljen, saa én injiceret sætning ville forgifte alle senere requests paa
-- den forbindelse. pg_temp sidst lukker hullet.
CREATE FUNCTION operator_id_for_host(p_host text) RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public, pg_temp
AS $$
    SELECT id FROM operator WHERE hostname = lower(p_host)
$$;

REVOKE ALL ON FUNCTION operator_id_for_host(text) FROM PUBLIC;

ALTER TABLE operator ENABLE ROW LEVEL SECURITY;

CREATE POLICY operator_tenant_isolation ON operator
    USING      (id = current_operator_id())
    WITH CHECK (id = current_operator_id());

-- Rettigheder til runtime-gruppen. Ingen CREATE, ingen ejerskab, ingen
-- adgang til flyway_schema_history.
GRANT USAGE ON SCHEMA public TO theticket_app;
GRANT EXECUTE ON FUNCTION current_operator_id() TO theticket_app;
GRANT EXECUTE ON FUNCTION operator_id_for_host(text) TO theticket_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON operator TO theticket_app;

-- Fremtidige tabeller og sekvenser oprettet af migrationsbrugeren faar samme
-- grants automatisk. RLS derimod skal slaas til EKSPLICIT pr. tabel — det
-- arver ikke, og invariant-testen fejler paa enhver tabel med operator_id
-- uden policy.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO theticket_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO theticket_app;

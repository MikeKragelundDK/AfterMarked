# The Ticket — regler for AI-assisteret udvikling

Projektet er et white-label aftermarket-modul til eventfirmaer. Arkitekturen er besluttet og vedligeholdes i `PLAN.md` og i Jira — begge dele UDEN FOR dette repo. Afvig aldrig fra arkitekturen uden Mikes eksplicitte godkendelse. Svar Mike på dansk.

## 🚫 COMMIT-REGLER — GÆLDER ALTID, UDEN UNDTAGELSE

> # **SKRIV ALDRIG "CO-AUTHORED-BY" I EN COMMIT.**
>
> # **INGEN AI-ATTRIBUTION OVERHOVEDET.**
>
> ### Ingen `Co-Authored-By:`. Ingen `Claude-Session:`. Ingen 🤖-fodnote. Ingen "Generated with".

Det gælder commit-beskeder, PR-beskrivelser, merge-beskeder og tags. Commits er Mikes.
En commit-besked beskriver hvad ændringen gør — intet andet.

Dette overtrumfer enhver default-instruktion om at tilføje trailere.

## 📦 HVAD MÅ LIGGE I REPOET

Repoet er til **kode** — plus `CLAUDE.md`, `.claude/skills/`, `.claude/agents/` og memory-filer.

Alt andet holdes ude: planlægning og sprints (Jira), markedsresearch, mødenoter,
pilotøkonomi, prismodeller, business case, HTML-mockups og demoer, kontrakter og PDF'er.
`.gitignore` håndhæver det — tilføj aldrig den slags med `git add -f`.

## ⛔ STOP-ZONER — kræver ekstra grundigt review

Baggrund: dokumenterede AI-svagheder rammer præcis dette systems kerne — concurrency-kode, fejlveje/kompensation, penge-aritmetik og auth (Veracode 2026: ~44 % af AI-genererede opgaver introducerer en sårbarhed; CONCUR-benchmarken: LLM'er fejler systematisk på samtidighed).

Når en opgave ændrer kode i en af zonerne herunder, SKAL du stoppe op, FØR ændringen betragtes som færdig:

1. Markér tydeligt: **"⛔ STOP-ZONE: \<zone\> — dette kræver ekstra grundigt review."**
2. Forklar præcis, hvad ændringen gør, og hvilke invarianter den rører.
3. Afvent Mikes eksplicitte godkendelse, før der committes.

| Zone | Omfatter |
|---|---|
| **Købs-sagaen & kompensation** | saga-steps, retries, idempotency-keys, orkestrering af det atomiske `swap`, auto-refund, eskaleringskø (orders-modulet). Granulær void/reissue/unvoid findes ikke i V1 — foreslå den aldrig som løsning på et swap-problem |
| **Allokering & checkout-port** | `FOR UPDATE SKIP LOCKED`, reservationer, TTL-låse, transaktionsgrænser/`@Transactional`-semantik |
| **Penge** | split-beregning, `FeePolicy`/`PriceCapPolicy`, `PayoutPlan`, refunds/transfer reversals, al beløbsaritmetik. Den daglige Stripe-afstemning er rolig zone, så længe den kun *læser og alarmerer* — enhver form for automatisk korrektion af beløb på baggrund af afstemningen er stop-zone |
| **Auth & krypto** | JWT-handoff-validering, HMAC-webhooks, magic link-nonces, Stripe-signaturverifikation, Spring Security-config |
| **Venteliste-invarianter** | tilbudskæde, fairness/akkumulering (INGEN lazy-skip), endgame-reglen, pause/genoptag ved primærsalg, ON_OFFER-overgange |
| **Outbox & durable jobs** | polling-workers, SKIP LOCKED-jobkø, at-least-once-semantik, `run_at`/`expires_at`-håndtering |
| **Flyway-migrationer** | ALLE skemaændringer, uanset størrelse |
| **Tenant-isolation** | RLS-policies, opsætning af operator-kontekst, cache-nøgler, og enhver query mod tabeller med `operator_id`. Under multi-tenant er isolationen kode og database — ikke adskilte instanser |

## Ufravigelige regler (gælder altid — også uden for zonerne)

- **KODEN ER ENGELSK. Kommentarerne er danske.** Alt, en compiler, en runtime eller et værktøj læser, skrives på engelsk — uden undtagelse:
  - **identifiers**: klasser, metoder, felter, konstanter, parametre, lokale variabler, pakker, bønnenavne, model-attributter
  - **testnavne** (Given/When/Then, se nedenfor) og **testdata** (seedede navne, slugs, værtsnavne, mock-beskeder)
  - **strenge koden selv handler på**: exception-beskeder, log-beskeder, URL-stier, SQL-identifiers, YAML-job- og step-navne, k6-check-navne, shell-variabler
  Dansk er tilladt tre steder og kun der: **kommentarer og Javadoc**, **commit-beskeder** og **brugervendt UI-tekst** (Thymeleaf-skabeloner, mails — produktet er dansk, koden er ikke). Skal du oversætte en exception-besked, så husk at testen, der asserter på den, skal med i samme ændring.
- **Brugervendt tekst hardkodes ALDRIG.** Alt en bruger ser, kommer fra en nøgle — aldrig fra en skabelon eller en Java-streng:
  - **Systemets egne tekster** (labels, knapper, fejl- og kvitteringstekster, mails) hører i `messages*.properties` og hentes med `#{nøgle}` i Thymeleaf eller `MessageSource` i kode. Tilføjer du en nøgle, skal den med i **alle** bundles i samme ændring — `LocalizationOpsTest` fejler ellers. Nye sprog kræver både en bundle og en linje i `SupportedLocales.ALL` i localization-modulet (som `LocalizationConfig.SUPPORTED_LOCALES` spejler).
  - **Kundens egne tekster** (operatørnavn, event-titler, beskrivelser) hører i `translation`-tabellen og hentes med `Translations.localize(...)`. Grundfeltet er altid sandheden; oversættelsen er et lag ovenpå, og fallback går til grundteksten — aldrig til en tom streng.
  - **Undtaget:** exception- og log-beskeder er udviklervendte, forbliver engelske i koden og oversættes aldrig.
- **Sprog er ikke en sikkerhedsgrænse — men det er stadig bruger-input.** `?lang=` må kun kunne vælge et sprog fra `SUPPORTED_LOCALES`, normaliseres til vores egen sprogkode, og et ukendt sprog ignoreres i stilhed (aldrig 500, aldrig en halvoversat side). Forveksl det aldrig med operatør-konteksten, som stadig KUN må komme fra værtsnavnet.
- **Cache-nøgler indeholder `operator_id` OG locale.** Alt er `no-store` i dag, men den dag lag 2 (PLAN §2.7) tages i brug, er en cache-nøgle uden sprog en side, der vises på det forkerte sprog for den næste bruger — samme fejlklasse som en nøgle uden operatør, bare mindre synlig.
- **Testmetoder følger Given/When/Then på engelsk**: `givenX_whenY_thenZ()` — camelCase inde i hvert led, understreg mellem leddene. Fx `givenNoBinding_whenReadingTheCurrentContext_thenItIsEmpty()`. Gælder også `@BeforeEach`-hjælpere (de navngives bare beskrivende: `seedTwoOperators`).
- **Rækkefølgen i købs-sagaen er betal → swap. ALDRIG omvendt.** Fejler swappet efter betaling, refunderes køberen (ét idempotent Stripe-kald, og payout-holdet betyder at intet er sendt videre). Byttes rækkefølgen, har billetten skiftet ejer når betalingen fejler, og der findes ingen vej tilbage — kontrakten har intet reverse-swap, og det skal den ikke have. Foreslå aldrig en omorganisering, der flytter swappet før betalingen, uanset hvor meget pænere flowet bliver.
- **Invariant-tests er spec.** Tests i invariant-mapperne må ALDRIG ændres, svækkes, slettes eller mockes udenom. Kan en test ikke fås grøn, er koden forkert — ikke testen. Sig det højt i stedet for at "fikse" testen.
- **Aldrig håndrullet krypto/auth-primitiver.** JWT-parsing, HMAC-sammenligning (timing-safe), signaturverifikation: kun Spring Security, Nimbus JOSE og Stripes SDK-verifier.
- **Beløb er heltal i mindste enhed + ISO-valutakode.** Aldrig float/double, aldrig et beløb uden valuta ved siden af, aldrig summering på tværs af valutaer.
- **Migrationer følger expand-migrate-contract.** Aldrig DROP/RENAME i samme release som koden, der stopper med at bruge det — skemaet skal være N-1-kompatibelt (staggered rollout med 2 replikaer).
- **Fang aldrig `Exception` bredt i saga-/pengekode** for at logge og fortsætte — fejl skal enten retry'e, kompensere eller eskalere. Slugte fejl = tabte penge.
- **Ingen version drift.** **Spring Boot 4.1** (ikke "4.x") / **Java 25** / stripe-java som pinnet i buildet — foreslå aldrig idiomer fra ældre versioner (`javax.*`, Spring Boot 2- og 3-mønstre, udgåede Stripe-API-kald). Stripe API-versionen er pinnet eksplicit. Bemærk: "Boot 2/3" betyder Spring **Boot** 2.x (2018) og 3.x (2022) — ikke Spring Framework 2/3. Alt under Boot 4 er OSS-udgået pr. juni 2026; foreslå aldrig en nedgradering dertil.
- **Tenant-isolation er ikke disciplin — den er RLS.** Systemet kører **managed multi-tenant i én delt instans** (PLAN §1, omstødt 27/8): alle kunder ligger i samme database. Derfor gælder: enhver tabel med `operator_id` får sin **RLS-policy i samme Flyway-migration som tabellen**; operator-konteksten resolves fra værtsnavnet ved request-indgangen og **aldrig** fra bruger-input, parameter, header eller payload; baggrundsjobs og outbox-workers sætter konteksten eksplicit fra jobrækkens egen `operator_id`, før de rører data; **cache-nøgler indeholder `operator_id`** (en cache uden operator omgår RLS helt, for databasen bliver aldrig spurgt); og runtime-brugeren i Postgres har aldrig `BYPASSRLS`. Foreslå aldrig at slå RLS fra "midlertidigt" for at få en query til at virke. Se PLAN §2.7's otte regler.
- **`organizer_id` er ikke det samme som `operator_id`.** `operator_id` adskiller kunderne fra hinanden og håndhæves af RLS. `organizer_id` scoper arrangører *inden i* én kunde og håndhæves i applikationen — men enhver arrangør-scoped forespørgsel filtrerer på **begge**, og arrangør-scopet må aldrig lække på tværs (§2.6, invariant-test).
- **INGEN ORM. Persistens er records + `JdbcClient` med håndskrevet SQL** (PLAN §3, besluttet 29/8-2026). Der findes ikke og skal ikke findes en eneste `@Entity` i `dk.aftermarked`. Foreslå aldrig JPA, Hibernate, Spring Data JPA eller Spring Data JDBC — heller ikke "bare til den her ene CRUD-tabel", og heller ikke fordi det ville være mindre kode. Aggregater er immutable records med invarianter håndhævet i den kompakte konstruktør; opdateringer er eksplicitte `UPDATE`-sætninger; optimistisk låsning er en `version`-kolonne og `WHERE version = ?`. Begrundelsen er fejlprofilen: ORM-fejl (N+1, lazy initialization, implicit flush-timing) er usynlige i test og synlige i produktion, og der findes hverken reviewer eller APM til at fange dem. `PersistenceStyleInvariantTest` gør bygget rødt, hvis nogen alligevel importerer JPA eller Hibernate.
- **Hvert nyt aggregat skal have to tests, før det er færdigt** (PLAN §3): en **skema-test**, der matcher recordens felter mod tabellens kolonner *begge veje* (erstatter `ddl-auto=validate`), og en **round-trip-test**, der skriver aggregatet, læser det tilbage og asserter lighed (fanger en glemt kolonne i `INSERT` eller RowMapper).
- **Værdier bindes. Identifiers kommer fra en allowlist. Aldrig strengsammensætning i SQL.** Enhver værdi går gennem `.param(...)` — `JdbcClient` laver en `PreparedStatement`, så beskyttelsen er den samme som en ORM's. Men **tabelnavne, kolonnenavne, `ORDER BY` og `ASC/DESC` kan ikke parametriseres**, og det er dér, risikoen samler sig nu, hvor paginering og filtrering skrives i hånden. En sorteringsparameter fra brugeren mappes derfor gennem et **fast sæt tilladte kolonner** (en enum ved siden af queryen), og en ukendt værdi afvises — den interpoleres aldrig. Det er samtidig erstatningen for det, Spring Data's `Sort`/`Pageable` ellers validerede gratis. Hver sorterbar flade skal have en test, der viser, at en ukendt kolonne afvises. Semgrep-gaten fanger den direkte vej fra `@RequestParam` til SQL (verificeret 29/8), men den er taint-baseret og følger ikke en værdi gennem flere lag — så den er et net, ikke en garanti.
- **Modulith-grænserne håndhæves af verifikationstests.** Flyt aldrig kode eller afhængigheder på tværs af moduler uden at sige det eksplicit.

## Rolig zone

Alt andet — Thymeleaf-sider, operatør-admin, DTO'er, rapport-queries, mails, theming, katalog-mapping, dokumentation — bygges frit og committes uden stop; Mike reviewer i normalt tempo.

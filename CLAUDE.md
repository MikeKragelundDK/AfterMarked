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

## Ufravigelige regler (gælder altid — også uden for zonerne)

- **Rækkefølgen i købs-sagaen er betal → swap. ALDRIG omvendt.** Fejler swappet efter betaling, refunderes køberen (ét idempotent Stripe-kald, og payout-holdet betyder at intet er sendt videre). Byttes rækkefølgen, har billetten skiftet ejer når betalingen fejler, og der findes ingen vej tilbage — kontrakten har intet reverse-swap, og det skal den ikke have. Foreslå aldrig en omorganisering, der flytter swappet før betalingen, uanset hvor meget pænere flowet bliver.
- **Invariant-tests er spec.** Tests i invariant-mapperne må ALDRIG ændres, svækkes, slettes eller mockes udenom. Kan en test ikke fås grøn, er koden forkert — ikke testen. Sig det højt i stedet for at "fikse" testen.
- **Aldrig håndrullet krypto/auth-primitiver.** JWT-parsing, HMAC-sammenligning (timing-safe), signaturverifikation: kun Spring Security, Nimbus JOSE og Stripes SDK-verifier.
- **Beløb er heltal i mindste enhed + ISO-valutakode.** Aldrig float/double, aldrig et beløb uden valuta ved siden af, aldrig summering på tværs af valutaer.
- **Migrationer følger expand-migrate-contract.** Aldrig DROP/RENAME i samme release som koden, der stopper med at bruge det — skemaet skal være N-1-kompatibelt (staggered rollout med 2 replikaer).
- **Fang aldrig `Exception` bredt i saga-/pengekode** for at logge og fortsætte — fejl skal enten retry'e, kompensere eller eskalere. Slugte fejl = tabte penge.
- **Ingen version drift.** **Spring Boot 4.1** (ikke "4.x") / **Java 25** / stripe-java som pinnet i buildet — foreslå aldrig idiomer fra ældre versioner (`javax.*`, Spring Boot 2- og 3-mønstre, udgåede Stripe-API-kald). Stripe API-versionen er pinnet eksplicit. Bemærk: "Boot 2/3" betyder Spring **Boot** 2.x (2018) og 3.x (2022) — ikke Spring Framework 2/3. Alt under Boot 4 er OSS-udgået pr. juni 2026; foreslå aldrig en nedgradering dertil.
- **Modulith-grænserne håndhæves af verifikationstests.** Flyt aldrig kode eller afhængigheder på tværs af moduler uden at sige det eksplicit.

## Rolig zone

Alt andet — Thymeleaf-sider, operatør-admin, DTO'er, rapport-queries, mails, theming, katalog-mapping, dokumentation — bygges frit og committes uden stop; Mike reviewer i normalt tempo.

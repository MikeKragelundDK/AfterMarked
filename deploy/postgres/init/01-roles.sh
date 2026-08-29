#!/bin/bash
# Opretter runtime-rollerne ved FOERSTE opstart af en tom Postgres
# (docker-entrypoint-initdb.d). Bruges af compose.yaml og af Testcontainers.
#
# I produktion (managed Postgres) goer provisioneringen det samme:
#   CREATE ROLE theticket_app NOLOGIN;                      -- oprettes ogsaa af V1, hvis den mangler
#   CREATE ROLE theticket_runtime LOGIN PASSWORD '<secret>' IN ROLE theticket_app;
#
# Runtime-rollen faar aldrig SUPERUSER, BYPASSRLS eller ejerskab. Rettighederne
# gives af Flyway-migrationerne til gruppen theticket_app.
set -euo pipefail

: "${THETICKET_RUNTIME_PASSWORD:=runtime-dev-only}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<EOSQL
CREATE ROLE theticket_app NOLOGIN;
CREATE ROLE theticket_runtime LOGIN PASSWORD '${THETICKET_RUNTIME_PASSWORD}' IN ROLE theticket_app;
EOSQL

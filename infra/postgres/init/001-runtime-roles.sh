#!/bin/sh
set -eu

: "${AUTHWEAVE_CORE_DB_PASSWORD:?AUTHWEAVE_CORE_DB_PASSWORD is required}"
: "${AUTHWEAVE_WEB_DB_PASSWORD:?AUTHWEAVE_WEB_DB_PASSWORD is required}"

psql \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --no-psqlrc \
  --set=ON_ERROR_STOP=1 \
  --set=core_password="$AUTHWEAVE_CORE_DB_PASSWORD" \
  --set=web_password="$AUTHWEAVE_WEB_DB_PASSWORD" <<'SQL'
CREATE ROLE authweave_core_runtime
  LOGIN
  NOSUPERUSER
  NOCREATEDB
  NOCREATEROLE
  NOINHERIT
  PASSWORD :'core_password';

CREATE ROLE authweave_web_runtime
  LOGIN
  NOSUPERUSER
  NOCREATEDB
  NOCREATEROLE
  NOINHERIT
  PASSWORD :'web_password';
SQL

#!/bin/bash
# Provisions the least-privilege application role (§2.9).
#
# The container's POSTGRES_USER is the privileged role that runs migrations (creates tables).
# The app connects as APP_DB_USER, which is granted ONLY DML — never DDL, never superuser.
# ALTER DEFAULT PRIVILEGES makes those grants apply automatically to the tables the migration
# role creates afterwards, so no manual re-granting is needed after Flyway runs.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE ROLE "${APP_DB_USER}" LOGIN PASSWORD '${APP_DB_PASSWORD}';

    GRANT CONNECT ON DATABASE "${POSTGRES_DB}" TO "${APP_DB_USER}";
    GRANT USAGE ON SCHEMA public TO "${APP_DB_USER}";

    ALTER DEFAULT PRIVILEGES IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${APP_DB_USER}";
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO "${APP_DB_USER}";
EOSQL

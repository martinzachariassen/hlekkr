# Deploying to Railway

How the [live site](https://short.mlz.no) is deployed. None of this is required to
run the project — the stack is three ordinary containers and runs anywhere (see the
[root README](../README.md)) — these are the concrete notes for one hosting choice:
[Railway](https://railway.app).

Three services in one Railway project: **web** (public), **api** (private), and
**Postgres** (private). Only `web` is exposed to the internet — the API and database are
reachable *solely* over Railway's private network. `web` and `api` build from this repo,
each with its own `railway.toml` and `Dockerfile`; set each service's **root directory**
to `frontend` and `backend` respectively.

```mermaid
flowchart LR
    subgraph Project["Railway project — private network"]
        Web["web — Caddy<br/>root: frontend<br/>the only public domain"]
        Api["api — Ktor<br/>root: backend<br/>SERVER_HOST=:: · no public domain"]
        PG[("Postgres<br/>no public proxy")]
        Web -->|"/api/* · /{code} · /swagger"| Api
        Api --> PG
    end

    Internet(("Internet")) --> Web
```

Deploy in that order (Postgres → api → web) so each service can find the one it depends on.

> [!TIP]
> Runtime secrets (`INTERNAL_API_KEY`, DB credentials) live in **Railway service
> variables**, not GitHub — GitHub secrets are only for what CI needs. The `INTERNAL_API_KEY`
> must be the *same* value on `api` and `web`.

## Postgres — keep it private

Railway gives the database a private hostname (`*.railway.internal`) and a `DATABASE_URL`.
Leave its **public networking / TCP proxy disabled** so only services in this project can
reach it — that alone satisfies "only my own services talk to the database."

For least privilege the app connects as a **DML-only role**, separate from the privileged
role that runs migrations. Railway's managed Postgres starts with only the `postgres`
superuser, so create the app role once — open the database's **Data / Query** tab (or
`psql` with its connection string) and run:

```sql
CREATE ROLE shortener_app LOGIN PASSWORD '<a strong password>';
GRANT CONNECT ON DATABASE railway TO shortener_app;
GRANT USAGE   ON SCHEMA   public  TO shortener_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES    TO shortener_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT               ON SEQUENCES TO shortener_app;
```

`ALTER DEFAULT PRIVILEGES` makes the grants apply automatically to the tables Flyway
creates next, so there's nothing to re-grant afterwards (this mirrors the `app_role_init`
config inlined in `docker-compose.yml`, which does the same locally). *Prefer simplicity
over the extra layer?* Skip this and use `postgres` for both migrations and the app — you
lose least privilege but nothing else works differently.

## api service — private (root directory `backend`)

**Do not generate a public domain for this service.** It is reached only over the private
network by `web`. Variables:

- **`SERVER_HOST=::`** — bind IPv6 so the service is reachable at `api.railway.internal`.
  Railway's private network is IPv6-only; without this, `web` cannot connect. *(This is the
  single most common Railway mistake.)*
- **Database** — the app uses the restricted role; migrations run on boot under the
  privileged one:
  - `DATABASE_URL` = `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}`
    — note the `jdbc:` prefix and the **private** `PGHOST`. (Railway's own `DATABASE_URL`
    is the `postgresql://user:pass@host/db` form with credentials inline; this app takes the
    JDBC URL and the credentials separately, so build it as shown.)
  - `DATABASE_USER=shortener_app`, `DATABASE_PASSWORD=<the password you set above>`.
  - `RUN_MIGRATIONS_ON_STARTUP=true`, `DATABASE_MIGRATION_USER=${{Postgres.PGUSER}}`,
    `DATABASE_MIGRATION_PASSWORD=${{Postgres.PGPASSWORD}}` — the schema is applied under
    `postgres`, then the app serves under `shortener_app`. **Both** migration variables must
    be set, or migrations fall back to the app role (which has no DDL rights) and fail.
    *(Single-role setup: point all four at `postgres` and omit the migration pair.)*
- **`INTERNAL_API_KEY`** — a long random value (`openssl rand -base64 32`); set the *same*
  value on `web`. This is what locks the management API to your frontend.
- **`BASE_URL`** — the **web** service's public URL (e.g. `https://short.mlz.no`).
  Short links are built from this and resolve on the public domain; it's also the
  self-referential target check.
- **`TRUST_PROXY_HEADERS=true`** — so per-IP rate limiting reads the real client IP from
  `X-Forwarded-For` (Caddy / Railway's edge sets it) instead of bucketing every visitor
  together.
- **`CORS_ALLOWED_ORIGINS`** — leave empty. The frontend calls the API same-origin through
  the proxy.

## web service — public (root directory `frontend`)

The only service you generate a public domain for. Variables:

- **`API_UPSTREAM=api.railway.internal:8080`** — the `api` service over the private network.
- **`INTERNAL_API_KEY`** — the same value as on `api`; Caddy injects it as `X-Internal-Key`.
- **`VITE_UMAMI_SRC` / `VITE_UMAMI_WEBSITE_ID`** — optional; omit to ship zero analytics.
  Read at *build* time (Vite inlines them), so Railway passes them as Docker build args.

Because `api` has no public domain, the management routes simply aren't reachable from the
internet. Everything genuinely public — the `/{code}` redirect, the `/openapi.yaml`
contract, and `/swagger` — is proxied through the public `web` domain. This topology is
documented in the OpenAPI `info` description so anyone reading the spec understands the API
isn't meant to be called directly.

## Troubleshooting

- **`Railpack could not determine how to build the app` (only `README.md` analyzed).** The
  service's **root directory** isn't set, so Railway only pulled the repo root. Set it to
  `backend` / `frontend` (Settings → Source) — that's what makes Railway pick up each app's
  `Dockerfile` and `railway.toml`.
- **Deploy succeeds but the container won't start / `run.sh not found`.** A leftover **custom
  start command** is overriding the Dockerfile `ENTRYPOINT`. Clear it (Settings → Deploy). If it
  contains an unfamiliar payload, the service likely came from an untrusted template — recreate it
  from the GitHub repo and rotate any secrets it could read (`INTERNAL_API_KEY`, DB password).
- **`/ready` healthcheck fails with "service unavailable".** The app can't reach Postgres or
  crashed on boot — read the **api** service's Deploy Logs. Common causes: `SERVER_HOST` not set to
  `::` (binds IPv4, fails the IPv6 probe); missing DB vars; or `DATABASE_URL` pointing at Railway's
  own `postgresql://…` string instead of the `jdbc:postgresql://…` form this app expects.
- **Front-end shows "No link found" on every action.** The `X-Internal-Key` gate answers `404`, so
  a mismatched `INTERNAL_API_KEY` looks like "not found." Ensure the value is identical on `api` and
  `web`, and redeploy `web` (Caddy reads it at container start).
- **Flyway NPE at startup (`PluginRegister … null`).** The shadow jar must merge Flyway's split SPI
  registry; the build enforces this (`duplicatesStrategy = INCLUDE` plus a `verifyFlywayServiceMerge`
  check). Don't remove either.

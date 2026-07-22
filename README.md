<div align="center">

# 🔗 short

**A link shortener that doesn't track you.** Paste a URL, get a short one back —
no accounts, no logins, clicks counted but never profiled. Built as a portfolio
project to *show the reasoning* behind persistence, concurrency, and security,
with a [Kotlin](https://kotlinlang.org) + [Ktor](https://ktor.io) API over raw
JDBC and a framework-free [React](https://react.dev) client.

[![CI](https://img.shields.io/github/actions/workflow/status/martinzachariassen/url-shortener/ci.yml?branch=main&label=CI&style=flat-square)](https://github.com/martinzachariassen/url-shortener/actions/workflows/ci.yml)
[![Frontend CI](https://img.shields.io/github/actions/workflow/status/martinzachariassen/url-shortener/frontend-ci.yml?branch=main&label=Frontend&style=flat-square)](https://github.com/martinzachariassen/url-shortener/actions/workflows/frontend-ci.yml)
[![CodeQL](https://img.shields.io/github/actions/workflow/status/martinzachariassen/url-shortener/codeql.yml?branch=main&label=CodeQL&style=flat-square)](https://github.com/martinzachariassen/url-shortener/actions/workflows/codeql.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/martinzachariassen/url-shortener/badge)](https://scorecard.dev/viewer/?uri=github.com/martinzachariassen/url-shortener)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue?style=flat-square)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Ktor](https://img.shields.io/badge/Ktor-3.5-087CFA?style=flat-square&logo=ktor&logoColor=white)](https://ktor.io)
[![JDK](https://img.shields.io/badge/JDK-25-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-18-4169E1?style=flat-square&logo=postgresql&logoColor=white)](https://www.postgresql.org)
[![React](https://img.shields.io/badge/React-19-61DAFB?style=flat-square&logo=react&logoColor=black)](https://react.dev)
[![Deployed on Railway](https://img.shields.io/badge/Railway-deploy-0B0D0E?style=flat-square&logo=railway&logoColor=white)](https://railway.app)

[**Live site**](https://short.up.railway.app) · [What it is](#what-it-is) · [Quick start](#quick-start) · [How it works](#how-it-works) · [API](#api) · [Security](#security-decisions) · [Deployment](#deploying-to-railway)

<a href="https://short.up.railway.app">
  <img src="frontend/public/assets/social/og.png" alt="short — a link shortener that doesn't track you" width="640" />
</a>

</div>

## What it is

**short** turns a long URL into a 7-character link and counts the clicks — that's
the whole product. The interesting part is *how* it's built. There's no Spring, no
ORM, no auto-configuration: every plugin, query, and dependency is wired by hand so
the reasoning behind each persistence, concurrency, and security decision stays
visible instead of hidden behind a framework. It's a monorepo of two independent
apps:

- **The API** ([`backend/`](backend)) — Kotlin + Ktor on Netty, talking to PostgreSQL
  through hand-written, parameterized JDBC. SSRF-preventive input validation, random
  non-enumerable codes, hashed owner tokens, per-IP rate limiting, and a least-privilege
  database role — each mapped to an explicit test.
- **The web client** ([`frontend/`](frontend)) — a single non-scrolling screen (Vite +
  React + TypeScript, no UI framework, one stylesheet). Paste a URL, get a short link
  and a one-time owner key; look a link up by code + key to see clicks or delete it.

Privacy is the pitch, so the redirect stores only a link id and a timestamp — no IP,
no user-agent, no referrer — and analytics is opt-in and cookieless.

## Tech stack

| Layer         | Choice                                                                 | Why                                                        |
| ------------- | --------------------------------------------------------------------- | --------------------------------------------------------- |
| API           | [Kotlin](https://kotlinlang.org) 2.4 · [Ktor](https://ktor.io) 3.5 (Netty) | Thin, coroutine-native, nothing reflective or auto-wired  |
| Persistence   | Raw JDBC + [HikariCP](https://github.com/brettwooldridge/HikariCP) over [PostgreSQL](https://www.postgresql.org) 18 | Hand-written `PreparedStatement`s — no ORM, no N+1, auditable SQL |
| Migrations    | [Flyway](https://flywaydb.org) 13 (`.sql`)                            | Versioned, reviewable, run under a privileged role         |
| Runtime       | [JDK](https://adoptium.net) 25                                        | Pinned via [mise](https://mise.jdx.dev)                    |
| Web client    | [Vite](https://vite.dev) 7 · [React](https://react.dev) 19 · TypeScript | One screen, one stylesheet, no framework or state library  |
| Edge / proxy  | [Caddy](https://caddyserver.com)                                     | The single public entrypoint; injects the service key      |
| Deploy        | [Railway](https://railway.app) · Docker                              | Three private-networked services                           |
| Toolchain     | [mise](https://mise.jdx.dev)                                          | Pins java/node/pnpm and owns every dev task                |

## Quick start

You need [mise](https://mise.jdx.dev) and Docker. One command pins the toolchain
(java/node/pnpm) and brings up **everything** — Postgres, the API from source, and
the web app — with dev-only defaults, so there's nothing to configure for a first run:

```bash
mise install      # first time only: fetch the pinned tools
mise run dev      # API on :8080, web app on :5173 — Ctrl-C stops all
```

Open the web app at [localhost:5173](http://localhost:5173), or explore the API
directly at [`/swagger`](http://localhost:8080/swagger).

`mise tasks` lists the rest:

```bash
mise run backend    # just the API against local Postgres
mise run web        # just the web app (expects the API running)
mise run test       # backend suite: unit + Testcontainers + e2e (needs Docker)
mise run lint       # compile gate: allWarningsAsErrors
mise run build      # backend jar + frontend bundle
mise run up / down / logs   # the whole stack in Docker
```

Prefer just the API in Docker? `mise run up` starts Postgres 18, runs Flyway
migrations under the admin role (a one-shot `flyway` service), then starts the API
under the least-privilege app role. To override any default (passwords, CORS,
`INTERNAL_API_KEY`), copy `.env.example` to `.env` and edit — `.env` is git-ignored.

> [!NOTE]
> `mise run dev`/`backend` apply migrations on startup (admin creds) for convenience.
> Containers turn that off (`RUN_MIGRATIONS_ON_STARTUP=false`) because migrations are a
> separate, privileged step there.

## How it works

Redirects are the hot path and stay fast: the click write is pushed onto a coroutine
`Channel` and flushed in batches by a background consumer, so `GET /{code}` never waits
on a click insert.

```
                      ┌────────────────────────────────────────────────┐
   POST /links        │  Ktor (Netty)                                  │
   GET  /{code}       │                                                │
   GET  /links/…      │  SecurityHeaders · CallId · CORS · StatusPages │
   DELETE /links/…    │                    │                           │
  ───────────────────▶│  Rate limiter (token bucket, per client IP)    │
                      │                    │                           │
                      │  LinkRoutes ─▶ LinkService ─▶ LinkRepository ──┼──▶ HikariCP pool
                      │                   │                            │    (DML-only role)
                      │     UrlValidator · CodeGenerator · OwnerToken  │         │
                      │                   │                            │         ▼
                      │                   │                            │  ┌───────────────┐
                      │  ClickTracker ─ Channel ─ batched inserts ─────┼─▶│  PostgreSQL   │
                      └────────────────────────────────────────────────┘  │ links · clicks│
                                                                          └───────▲───────┘
                                                Flyway migrations (admin role) ───┘
```

### Why Ktor + raw JDBC over Spring/ORM

- **Ktor, not Spring.** Ktor is a thin, coroutine-native server. Nothing is
  auto-configured or reflective — plugin install order, the request pipeline, and every
  dependency are explicit in `Application.kt`. For a project meant to *demonstrate* how
  the pieces fit, that visibility is the point.
- **Raw JDBC, not an ORM.** Every query is a hand-written, parameterized
  `PreparedStatement` wrapped in a few small extension helpers (`repository/Database.kt`).
  No lazy loading, no N+1 surprise, no dialect leakage — the SQL that runs is the SQL in
  the file. It also makes the SQL-injection posture trivially auditable: grep for string
  concatenation in SQL and you'll find none.
- **Flyway for migrations.** Plain `.sql` files, versioned and reviewable, run under a
  privileged role at deploy time.

The tradeoff is more boilerplate than Spring Data would need. That boilerplate is the
deliverable here, not an accident.

## Project structure

```
url-shortener/
├── backend/                     # Ktor API (Kotlin, raw JDBC, Gradle)
│   └── src/main/kotlin/no/mlz/shortener/
│       ├── config/  plugins/    # AppConfig + explicit Ktor feature install
│       ├── routes/  service/    # LinkRoutes → LinkService
│       ├── repository/          # hand-written PreparedStatements (Database.kt)
│       └── security/            # UrlValidator, CodeGenerator, OwnerToken, HostBlocklist
├── frontend/                    # single-screen web client (Vite + React + TS)
│   ├── src/                     # components + one stylesheet
│   └── Caddyfile                # the single public entrypoint (prod)
├── mise.toml                    # pinned toolchain + one-command dev stack
├── docker-compose.yml           # Postgres + Flyway + API
└── .github/                     # CI: build/test, CodeQL, Trivy, Scorecard, blocklist refresh
```

## API

Interactive docs are served from the running app: **Swagger UI at
[`/swagger`](http://localhost:8080/swagger)** and the raw OpenAPI 3.1 spec at
[`/openapi.yaml`](http://localhost:8080/openapi.yaml) (both public). The spec is
hand-authored (`backend/src/main/resources/openapi/documentation.yaml`) — explicit and
reviewable, in keeping with the rest of the project.

| Method   | Path                  | Access                          | Notes                                                                    |
| -------- | --------------------- | ------------------------------- | ------------------------------------------------------------------------ |
| `POST`   | `/links`              | frontend key (rate-limited)     | Body `{targetUrl, expiresAt?}` → `{code, shortUrl, ownerToken}`. `ownerToken` is shown **once**. |
| `GET`    | `/{code}`             | **public**                      | `302` redirect. Records a click asynchronously. Generic `404` if missing/expired/deleted. |
| `GET`    | `/links/{code}/stats` | frontend key + Bearer owner token (rate-limited) | `{totalClicks, last7Days: [{date, count}]}`                 |
| `DELETE` | `/links/{code}`       | frontend key + Bearer owner token (rate-limited) | Soft delete.                                                |
| `GET`    | `/health`             | **public**                      | Liveness probe → `200 OK` (no dependency checks).                        |
| `GET`    | `/ready`              | **public**                      | Readiness probe → `200 READY`, or `503` if Postgres is unreachable. Railway's health check. |

"frontend key" = the `X-Internal-Key` header described in [Restricting the API to your
frontend](#restricting-the-api-to-your-frontend). It is unset (open) in local development.

```bash
# Create (locally, with no INTERNAL_API_KEY set)
curl -s -X POST localhost:8080/links \
  -H 'Content-Type: application/json' \
  -d '{"targetUrl":"https://example.com"}'
# → {"code":"d10ndrX","shortUrl":"http://localhost:8080/d10ndrX","ownerToken":"g3UQ…"}

# Redirect
curl -i localhost:8080/d10ndrX          # 302 Location: https://example.com

# Stats / delete (owner token required)
curl localhost:8080/links/d10ndrX/stats -H "Authorization: Bearer g3UQ…"
curl -X DELETE localhost:8080/links/d10ndrX -H "Authorization: Bearer g3UQ…"

# Once INTERNAL_API_KEY is set, management calls also need the service header:
curl -X POST localhost:8080/links -H 'X-Internal-Key: <key>' \
  -H 'Content-Type: application/json' -d '{"targetUrl":"https://example.com"}'
```

## Security decisions

Written as decisions with reasoning, because that's what a reviewer reads. Each maps to
code and to an explicit test. The short version:

| Threat | Defense |
| ------ | ------- |
| SSRF / internal-network targets | `UrlValidator`: scheme allow-list; rejects loopback, private, link-local & cloud-metadata ranges — including alternate IPv4 encodings — plus embedded credentials and self-referential URLs |
| Link enumeration / scraping | 7-char random base62 codes from `SecureRandom`, never sequential; fail-closed retry on collision |
| Probing which codes exist | Missing, expired, deleted, and auth-failed all return the same generic `404` — never `403` |
| Owner-token theft from the DB | Tokens returned once, stored only as SHA-256, compared in constant time |
| Spam / abuse | Per-IP token-bucket rate limiting, strict on `POST /links` → `429` + `Retry-After` |
| Malware / phishing / adult targets | Suffix-matched domain blocklist, baked into the image at build time and refreshed weekly |
| Anyone-with-`curl` API use | `X-Internal-Key` service gate held by the Caddy proxy, never the browser |
| Detail leaks in errors | `StatusPages` maps everything to clean responses; correlation id only |
| Runaway or hostile queries | DML-only DB role, statement timeout, bounded connection pool |
| Oversized payloads | Bodies streamed with a hard 16 KB cap → `413` |

The reasoning behind each:

- **Input validation is SSRF-preventive, on purpose.** `UrlValidator` allows only
  `http`/`https`, and rejects loopback/link-local/private ranges (`127/8`, `10/8`,
  `172.16/12`, `192.168/16`, `169.254/16` incl. the `169.254.169.254` cloud-metadata
  endpoint, `::1`, `fc00::/7`), `localhost`, embedded credentials, and URLs pointing back
  at this service's own domain. This service does **not** fetch the target today, so none
  of this is load-bearing yet — it exists so that adding a preview/screenshot feature
  later cannot silently open an SSRF hole. DNS is deliberately *not* resolved at creation
  time: it would add nondeterminism and its own lookup surface and would be stale by fetch
  time, so any future fetch must re-validate the resolved address then.
- **Content policy via a domain blocklist.** Separately from the SSRF checks,
  `UrlValidator` consults a `HostBlocklist` and refuses to shorten targets on it (adult,
  malware, phishing). Matching is by domain *suffix* — one entry covers a domain and all its
  subdomains — and each in-memory check walks the host's label suffixes, so even a
  100k-domain feed costs nothing per request. The production image **bakes two free,
  maintained lists at build time** — [HaGeZi NSFW](https://github.com/hagezi/dns-blocklists)
  (adult, ~115k domains) and [URLhaus](https://urlhaus.abuse.ch/) (fresh malware) — so there
  is **no runtime dependency**; a weekly
  [`refresh-blocklist`](.github/workflows/refresh-blocklist.yml) workflow re-fetches both
  feeds (failing loudly if either 404s or shrinks suspiciously — a canary for upstream URL
  rot), bumps a cache-bust marker, and lets Railway's auto-deploy rebuild with current lists.
  Local Compose builds skip the fetch (`FETCH_BLOCKLISTS=false`) and run with an empty,
  no-op list. Operators can override with `BLOCKED_HOSTS` (inline) or `BLOCKED_HOSTS_FILE`
  (one domain per line, or `hosts`-file format). Live URL-level verdicts (e.g. Google Safe
  Browsing) are deliberately not wired in: a synchronous third-party call gating every write
  isn't worth it here, and it wouldn't cover adult content anyway.
- **Short codes are random, not sequential.** A base62-of-autoincrement scheme is
  enumerable — walk `/1`, `/2`, `/3` and scrape every link ever made. Codes are 7 random
  base62 characters from `SecureRandom` with a DB uniqueness check and a bounded retry that
  **fails closed**. (Sqids was considered and rejected: it is reversible given the salt.)
- **Owner tokens, hashed at rest.** No user accounts. Creation returns a 256-bit
  `SecureRandom` token once; only its SHA-256 hash is stored. Stats/delete compare against
  the hash in constant time and return **`404`, not `403`**, on mismatch — so the API can't
  be used to probe which codes exist.
- **Undifferentiated 404s.** Missing, expired, deleted, and auth-failed all return the same
  generic `404` on the public surface. Detail is reserved for authenticated calls.
- **Rate limiting.** In-memory per-IP token bucket: strict on `POST /links` (the
  spam/phishing surface), looser on `GET /{code}`. Exceeding it returns `429` with
  `Retry-After`. This is single-instance state and does **not** survive horizontal scaling
  — Redis is the swap-in for that (see out-of-scope).
- **No raw errors leak.** A global `StatusPages` handler maps known failures to clean 4xx
  and everything else to a generic `500`. Exception messages, stack traces, and SQL text
  are logged server-side with a correlation id that is also returned to the client
  (`X-Correlation-Id` and in the error body) for support — never the underlying detail.
- **Frontend-only management surface.** `POST /links`, stats, and delete require an
  `X-Internal-Key` header matching a per-deployment secret that only the frontend holds; a
  constant-time compare gates it and a miss returns the same generic `404`. The redirect
  stays public. See [Restricting the API to your frontend](#restricting-the-api-to-your-frontend)
  for why this — not CORS — is the real gate.
- **Hardening headers on every response.** HSTS, `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, and `Content-Security-Policy:
  default-src 'none'` (this is a JSON API that serves no HTML — the Swagger UI docs get a
  narrowly-relaxed same-origin CSP so the page can load its own assets). CORS is an explicit
  allow-list, never `*`, and credentials are not enabled.
- **Least-privilege database role.** Migrations run under a privileged admin role (the
  `flyway` step in Compose, or a deploy job); the application connects under a role with
  **DML only** — no DDL, not a superuser. `ALTER DEFAULT PRIVILEGES` grants the app DML on
  the tables the admin creates. A statement timeout and a bounded Hikari pool cap the blast
  radius of a runaway query.
- **Bounded request bodies.** `POST /links` bodies are capped (16 KB) and streamed with a
  hard limit → `413`, so an oversized payload can't hang or OOM the server.
- **Secrets only from the environment.** DB credentials come from env vars; the app **fails
  fast at startup** if a required one is missing or blank. `.env` is git-ignored; only
  `.env.example` is committed. The connection string is never logged.

### Restricting the API to your frontend

The goal: only the project's own frontend can create, inspect, or delete links — not
anyone with `curl`. The redirect endpoint `GET /{code}` is the exception; it *is* the
short link, so it must stay public.

**CORS is not enough.** CORS is a browser-enforced policy: it stops a malicious *web page*
from reading your API cross-origin, but `curl`, Postman, and any server ignore it
entirely. Relying on CORS alone would leave `POST /links` open to the world. So the real
gate is a **shared service key**: the management routes require an `X-Internal-Key` header
whose value only the frontend knows, checked in constant time, with a miss returning the
same generic `404` as everything else.

The key belongs to the frontend's **server**, never the browser. Here that server is the
[Caddy](https://caddyserver.com) proxy in front of the web app (`frontend/Caddyfile`),
which is the **only service with a public domain**. The API sits on the private network
with no public ingress of its own, so every public request goes through Caddy: it injects
`X-Internal-Key` on `/api/*` and passes the genuinely public routes (redirect, spec,
Swagger) straight through. The secret never ships to the client, and because the calls are
same-origin, no CORS is involved at all.

```
                              Caddy (public, holds INTERNAL_API_KEY)      Shortener API (private)
Browser ──/api/*────────────▶ inject X-Internal-Key ────────────────────▶ /links*      key required
Visitor ──/{code}───────────▶ pass through ─────────────────────────────▶ GET /{code}  public
Anyone  ──/openapi.yaml,/swagger ─▶ pass through ───────────────────────▶ docs         public
```

This composes with the existing per-link owner token to give two independent layers: the
service key answers *"is this the frontend?"* and the owner token answers *"does this
caller own this link?"*. Locally, leaving `INTERNAL_API_KEY` unset disables the gate (the
app logs a warning at startup) so development stays friction-free.

## Deploying to Railway

Three services in one Railway project: **web** (public), **api** (private), and
**Postgres** (private). Only `web` is exposed to the internet — the API and database are
reachable *solely* over Railway's private network. `web` and `api` build from this repo,
each with its own `railway.toml` and `Dockerfile`; set each service's **root directory**
to `frontend` and `backend` respectively.

```
        ┌───────────────────── Railway project · private network ──────────────────────┐
Internet ─▶ web (Caddy, PUBLIC) ─ /api/* + /{code} + /swagger ─▶ api (Ktor, PRIVATE) ─▶ Postgres
            root: frontend                                       root: backend          (PRIVATE)
            the only public domain                               SERVER_HOST=::          no public proxy
        └──────────────────────────────────────────────────────────────────────────────┘
```

Deploy in that order (Postgres → api → web) so each service can find the one it depends on.

> [!TIP]
> Runtime secrets (`INTERNAL_API_KEY`, DB credentials) live in **Railway service
> variables**, not GitHub — GitHub secrets are only for what CI needs. The `INTERNAL_API_KEY`
> must be the *same* value on `api` and `web`.

### Postgres — keep it private

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

### api service — private (root directory `backend`)

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
- **`BASE_URL`** — the **web** service's public URL (e.g. `https://short.up.railway.app`).
  Short links are built from this and resolve on the public domain; it's also the
  self-referential target check.
- **`TRUST_PROXY_HEADERS=true`** — so per-IP rate limiting reads the real client IP from
  `X-Forwarded-For` (Caddy / Railway's edge sets it) instead of bucketing every visitor
  together.
- **`CORS_ALLOWED_ORIGINS`** — leave empty. The frontend calls the API same-origin through
  the proxy.

### web service — public (root directory `frontend`)

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

### Troubleshooting

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

## Testing & CI

```bash
cd backend
./gradlew build test
```

- **Unit** (JUnit 5 + MockK): `UrlValidator`, `LinkService`, `CodeGenerator`, rate limiter,
  owner-token hashing, and the service-key constant-time compare.
- **Repository** (Testcontainers, real Postgres 18): parameterized queries, uniqueness,
  soft-delete/expiry filtering, click aggregation, and a `'; DROP TABLE links; --`
  inertness test.
- **End-to-end** (Ktor test client + Testcontainers): every endpoint plus the explicit
  security cases — dangerous schemes, private/metadata targets, oversized body → `413`,
  malformed JSON → clean `400`, missing/wrong owner token → `404`, missing/wrong service key
  → `404` (with the redirect still public), rate limit → `429 + Retry-After` (create *and*
  stats), the security headers, CORS scheme-pinning, the DB-backed readiness probe, the
  self-hosted (CDN-free) Swagger UI, and that `/health` and `/openapi.yaml` stay public.

CI (GitHub Actions) runs `./gradlew build` (compile + full test suite), a
[Trivy](https://trivy.dev) dependency scan that fails on any HIGH/CRITICAL CVE,
`dependency-review` on PRs, [OpenSSF Scorecard](https://scorecard.dev), and a
Conventional-Commit PR-title check. Dependabot keeps Gradle, npm, Actions, and Docker
dependencies current, and `main` is protected by a ruleset requiring the `build` check and
a PR.

Static analysis runs on two fronts. The frontend is scanned by **CodeQL**
(JavaScript/TypeScript), with findings uploaded to the Security tab. On the backend it's
enforced at the compiler: `allWarningsAsErrors = true` fails the build on any Kotlin
warning. A dedicated SAST scanner for Kotlin (CodeQL / detekt) is intentionally *not* wired
in yet — as of this writing neither supports the bleeding-edge **JDK 25 + Kotlin 2.4**
toolchain this project pins (CodeQL's Kotlin extractor sees "no source"; detekt's bundled
compiler rejects JVM target 25). It should be added once the tooling catches up, or by
pinning an older toolchain.

## Out of scope (and why)

- **Multi-instance rate limiting.** The token bucket is in-memory, so limits are
  per-instance. Correct behind a single instance; a horizontally-scaled deployment would
  move the buckets to Redis. Kept in-memory here to avoid a second piece of infrastructure
  for a demo. The map is bounded — buckets that have refilled to full are evicted once it
  grows large — so churning/spoofed source IPs can't grow it without limit.
- **Click analytics beyond a count.** `clicks` stores only `link_id` and a timestamp — no
  IP, user-agent, or referrer. Collecting those would make this a tracking product and add a
  privacy-compliance surface that isn't the point. A crash between enqueue and flush can lose
  at most one un-flushed batch of counts — acceptable for analytics, not for billing-grade
  data.
- **User accounts / sessions.** Authorization is a single per-link owner token by design. A
  full identity system is a different project.

## License

[MIT](LICENSE) © [Martin Zachariassen](https://mlz.no)
</content>
</invoke>

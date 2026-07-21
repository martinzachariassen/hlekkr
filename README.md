# URL Shortener API

A production-minded, security-hardened URL shortener REST API built in **Kotlin + Ktor**
with **raw JDBC** (no ORM) over **PostgreSQL**. This is a portfolio project: the goal is to
show reasoning about persistence, concurrency, and security without a framework hiding the
decisions.

The repository is a monorepo. The backend lives in [`backend/`](backend); the web client
that consumes it lives in [`frontend/`](frontend) as an independent app.

```
url-shortener/
├── backend/     # Ktor API (this project)
├── frontend/    # single-screen web client (Vite + React + TS)
├── mise.toml    # one-command dev stack + pinned toolchain
├── docker-compose.yml
└── .github/     # CI: build/test, dependency scan, scorecard, frontend build
```

## Contents

- [Architecture](#architecture)
- [Why Ktor + raw JDBC over Spring/ORM](#why-ktor--raw-jdbc-over-springorm)
- [API](#api)
- [Security decisions](#security-decisions)
- [Restricting the API to your frontend](#restricting-the-api-to-your-frontend)
- [Frontend](#frontend)
- [Running locally](#running-locally)
- [Deploying to Railway](#deploying-to-railway)
- [Testing](#testing)
- [Out of scope (and why)](#out-of-scope-and-why)

## Architecture

```
                      ┌──────────────────────────────────────────────┐
   POST /links        │  Ktor (Netty)                                 │
   GET  /{code}       │                                               │
   GET  /links/../    │   SecurityHeaders ─ CallId ─ CORS ─ StatusPages│
   DELETE /links/..   │        │                                      │
  ───────────────────▶│   Rate limiter (token bucket, per-IP)         │
                      │        │                                      │
                      │   LinkRoutes ──▶ LinkService ──▶ LinkRepository│──┐
                      │                     │            (PreparedStmt)│  │  Hikari
                      │                     ▼                          │  │  pool
                      │              UrlValidator                      │  │  (DML-only role)
                      │              CodeGenerator                     │  ▼
                      │              OwnerToken                        │ ┌───────────────┐
                      │                     │                          │ │  PostgreSQL   │
                      │              ClickTracker ─ Channel ─ batch ───▶│ │  links, clicks│
                      └──────────────────────────────────────────────┘ └───────────────┘
                                                                          ▲
                                       Flyway migrations (admin role) ────┘
```

Redirects are the hot path and stay fast: the click write is pushed onto a coroutine
`Channel` and flushed in batches by a background consumer, so `GET /{code}` never waits on a
click insert.

## Why Ktor + raw JDBC over Spring/ORM

- **Ktor, not Spring.** Ktor is a thin, coroutine-native server. Nothing is auto-configured
  or reflective — plugin install order, the request pipeline, and every dependency are
  explicit in `Application.kt`. For a project meant to *demonstrate* how the pieces fit, that
  visibility is the point.
- **Raw JDBC, not an ORM.** Every query is a hand-written, parameterized `PreparedStatement`
  wrapped in a few small extension helpers (`repository/Database.kt`). There is no lazy
  loading, no N+1 surprise, no dialect leakage — the SQL that runs is the SQL in the file.
  This also makes the SQL-injection posture trivially auditable: grep for string concatenation
  in SQL and you will find none.
- **Flyway for migrations.** Plain `.sql` files, versioned and reviewable, run under a
  privileged role at deploy time (see below).

The tradeoff is more boilerplate than Spring Data would need. That boilerplate is the
deliverable here, not an accident.

## API

Interactive docs are served from the running app: **Swagger UI at [`/swagger`](http://localhost:8080/swagger)**
and the raw OpenAPI 3.1 spec at [`/openapi.yaml`](http://localhost:8080/openapi.yaml) (both public). The
spec is hand-authored (`backend/src/main/resources/openapi/documentation.yaml`) — explicit and
reviewable, in keeping with the rest of the project.

| Method   | Path                  | Access                          | Notes                                                                    |
| -------- | --------------------- | ------------------------------- | ------------------------------------------------------------------------ |
| `POST`   | `/links`              | frontend key (rate-limited)     | Body `{targetUrl, expiresAt?}` → `{code, shortUrl, ownerToken}`. `ownerToken` is shown **once**. |
| `GET`    | `/{code}`             | **public**                      | `302` redirect. Records a click asynchronously. Generic `404` if missing/expired/deleted. |
| `GET`    | `/links/{code}/stats` | frontend key + Bearer owner token | `{totalClicks, last7Days: [{date, count}]}`                            |
| `DELETE` | `/links/{code}`       | frontend key + Bearer owner token | Soft delete.                                                            |
| `GET`    | `/health`             | **public**                      | Liveness probe → `200 OK`.                                               |

"frontend key" = the `X-Internal-Key` header described in [Restricting the API to your frontend](#restricting-the-api-to-your-frontend).
It is unset (open) in local development.

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

Written as decisions with reasoning, because that is what a reviewer reads. Each maps to code
and to an explicit test.

- **Input validation is SSRF-preventive, on purpose.** `UrlValidator` allows only `http`/`https`,
  and rejects loopback/link-local/private ranges (`127/8`, `10/8`, `172.16/12`, `192.168/16`,
  `169.254/16` incl. the `169.254.169.254` cloud-metadata endpoint, `::1`, `fc00::/7`),
  `localhost`, embedded credentials, and URLs pointing back at this service's own domain. This
  service does **not** fetch the target today, so none of this is load-bearing yet — it exists
  so that adding a preview/screenshot feature later cannot silently open an SSRF hole. DNS is
  deliberately *not* resolved at creation time: it would add nondeterminism and its own lookup
  surface and would be stale by fetch time, so any future fetch must re-validate the resolved
  address then.
- **Short codes are random, not sequential.** A base62-of-autoincrement scheme is enumerable —
  walk `/1`, `/2`, `/3` and scrape every link ever made. Codes are 7 random base62 characters
  from `SecureRandom` with a DB uniqueness check and a bounded retry that **fails closed**.
  (Sqids was considered and rejected: it is reversible given the salt.)
- **Owner tokens, hashed at rest.** No user accounts. Creation returns a 256-bit `SecureRandom`
  token once; only its SHA-256 hash is stored. Stats/delete compare against the hash in constant
  time and return **`404`, not `403`**, on mismatch — so the API can't be used to probe which
  codes exist.
- **Undifferentiated 404s.** Missing, expired, deleted, and auth-failed all return the same
  generic `404` on the public surface. Detail is reserved for authenticated calls.
- **Rate limiting.** In-memory per-IP token bucket: strict on `POST /links` (the spam/phishing
  surface), looser on `GET /{code}`. Exceeding it returns `429` with `Retry-After`. This is
  single-instance state and does **not** survive horizontal scaling — Redis is the swap-in for
  that (see out-of-scope).
- **No raw errors leak.** A global `StatusPages` handler maps known failures to clean 4xx and
  everything else to a generic `500`. Exception messages, stack traces, and SQL text are logged
  server-side with a correlation id that is also returned to the client (`X-Correlation-Id` and
  in the error body) for support — never the underlying detail.
- **Frontend-only management surface.** `POST /links`, stats, and delete require an
  `X-Internal-Key` header matching a per-deployment secret that only the frontend holds; a
  constant-time compare gates it and a miss returns the same generic `404`. The redirect stays
  public. See [Restricting the API to your frontend](#restricting-the-api-to-your-frontend) for
  why this — not CORS — is the real gate.
- **Hardening headers on every response.** HSTS, `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, and `Content-Security-Policy:
  default-src 'none'` (this is a JSON API that serves no HTML — the Swagger UI docs get a
  narrowly-relaxed same-origin CSP so the page can load its own assets). CORS is an explicit
  allow-list, never `*`, and credentials are not enabled.
- **Least-privilege database role.** Migrations run under a privileged admin role (the
  `flyway` step in Compose, or a deploy job); the application connects under a role with **DML
  only** — no DDL, not a superuser. `ALTER DEFAULT PRIVILEGES` grants the app DML on the tables
  the admin creates. A statement timeout and a bounded Hikari pool cap the blast radius of a
  runaway query.
- **Bounded request bodies.** `POST /links` bodies are capped (16 KB) and streamed with a hard
  limit → `413`, so an oversized payload can't hang or OOM the server.
- **Secrets only from the environment.** DB credentials come from env vars; the app **fails fast
  at startup** if a required one is missing or blank. `.env` is git-ignored; only `.env.example`
  is committed. The connection string is never logged.

## Restricting the API to your frontend

The goal: only the project's own frontend can create, inspect, or delete links — not anyone with
`curl`. The redirect endpoint `GET /{code}` is the exception; it *is* the short link, so it must
stay public.

**CORS is not enough.** CORS is a browser-enforced policy: it stops a malicious *web page* from
reading your API cross-origin, but `curl`, Postman, and any server ignore it entirely. Relying on
CORS alone would leave `POST /links` open to the world. So the real gate is a **shared service
key**: the management routes require an `X-Internal-Key` header whose value only the frontend
knows, checked in constant time, with a miss returning the same generic `404` as everything else.

The key belongs to the frontend's **server**, never the browser. Here that server is the
[Caddy](https://caddyserver.com) proxy in front of the web app (`frontend/Caddyfile`): the browser
calls `/api/*` same-origin, Caddy injects `X-Internal-Key` and proxies to this API over Railway's
private network. The secret never ships to the client — and because the calls are same-origin, no
CORS is involved at all.

```
Browser ──/api/*──> Caddy (holds INTERNAL_API_KEY) ──X-Internal-Key──> Shortener API
                                                                         /links*  key required
Anyone ────────────────────────────────────────────────────────────────> GET /{code}  public
```

This composes with the existing per-link owner token to give two independent layers: the service
key answers *"is this the frontend?"* and the owner token answers *"does this caller own this
link?"*. Locally, leaving `INTERNAL_API_KEY` unset disables the gate (the app logs a warning at
startup) so development stays friction-free.

## Frontend

A single-screen web client (Vite + React + TypeScript) lives in [`frontend/`](frontend): paste a
URL to get a short one plus a one-time owner key, then look a link up by code + key to see clicks
or delete it. The whole thing fits one non-scrolling screen — result and stats states swap in
place. The palette (warm paper, teal accent) borrows from [mlz.no](https://mlz.no); the body type is
[Atkinson Hyperlegible](https://www.brailleinstitute.org/freefont/), designed for legibility, paired
with Instrument Serif for the headline.

Privacy is the pitch, so analytics is opt-in: it integrates [Umami](https://umami.is) (cookieless),
but ships **zero** analytics code unless `VITE_UMAMI_SRC` / `VITE_UMAMI_WEBSITE_ID` are set. See
[`frontend/README.md`](frontend/README.md) for its configuration.

## Running locally

With [mise](https://mise.jdx.dev) installed, one command pins the toolchain (java/node/pnpm) and
brings up **everything** — Postgres, the API from source, and the web app:

```bash
mise install     # first time only: fetch the pinned tools
mise run dev      # API on :8080, web app on :5173 — Ctrl-C stops all
```

`mise tasks` lists the rest — `mise run backend` (API only), `mise run web`, `mise run test`,
`mise run lint`, `mise run build`, and the Docker shortcuts below.

Prefer just the API in Docker? The Compose file ships dev-only defaults, so there is **nothing to
configure** for a first run:

```bash
mise run up      # docker compose up --build
```

This starts Postgres 18, runs Flyway migrations under the admin role (a one-shot `flyway`
service), then starts the API on `http://localhost:8080` under the least-privilege app role.
Open [`/swagger`](http://localhost:8080/swagger) to explore it. `mise run logs` tails it and
`mise run down` tears it down. To override any default (passwords, CORS, `INTERNAL_API_KEY`), copy
`.env.example` to `.env` and edit.

Both `mise run dev` and `mise run backend` run the API from source with migrations applied on startup
(using admin credentials) — convenient for local iteration. In containers that is turned off
(`RUN_MIGRATIONS_ON_STARTUP=false`) because migrations are a separate privileged step.

## Deploying to Railway

Two Railway services from this one repo — each has its own `railway.toml` and `Dockerfile`; set each
service's **root directory** accordingly:

```
                        ┌───────────────────────────── Railway ─────────────────────────────┐
Visitor ──/api/*──▶ web (Caddy)  ──X-Internal-Key──▶  api (Ktor)  ◀──▶  Postgres
          static ◀──  root: frontend    private net    root: backend        plugin
Visitor ──short link──────────────────────────────▶  api  GET /{code}  (public redirect)
                        └────────────────────────────────────────────────────────────────────┘
```

**`api` service** (root directory `backend`) — serves the public redirects and the gated management
API:

- **Postgres** — add a Railway Postgres plugin and set `DATABASE_URL`, `DATABASE_USER`,
  `DATABASE_PASSWORD`. For least privilege, run migrations under an admin role and let the app
  connect as a DML-only role: set `RUN_MIGRATIONS_ON_STARTUP=true` with `DATABASE_MIGRATION_USER` /
  `DATABASE_MIGRATION_PASSWORD` pointing at the privileged role, so the schema is applied on boot
  and the app then serves under the restricted role.
- **`INTERNAL_API_KEY`** — a long random value (`openssl rand -base64 32`); set the *same* value on
  the `web` service so Caddy can inject it. This is what locks the management API to your frontend.
- **`BASE_URL`** — this service's own public URL, used to build `shortUrl`s and to reject
  self-referential targets.
- **`TRUST_PROXY_HEADERS=true`** — so per-IP rate limiting reads the real client IP from
  `X-Forwarded-For` (Railway's edge sets it) instead of bucketing every visitor together.
- **`CORS_ALLOWED_ORIGINS`** — leave empty. The frontend calls the API same-origin through the proxy,
  so no cross-origin access is needed.

**`web` service** (root directory `frontend`) — Caddy serving the static bundle and proxying `/api/*`:

- **`API_UPSTREAM=api.railway.internal:8080`** — the `api` service over Railway's private network.
- **`INTERNAL_API_KEY`** — the same value as on `api`; Caddy injects it as `X-Internal-Key`.
- **`VITE_PUBLIC_API_URL`** — the `api` service's public URL (used for the Swagger link, which skips
  the proxy). **`VITE_UMAMI_SRC` / `VITE_UMAMI_WEBSITE_ID`** — optional; omit to ship zero analytics.
  These three are read at *build* time (Vite inlines them), which Railway passes as Docker build args.

The management API is reachable publicly on the `api` domain too, but every management route is gated
by `INTERNAL_API_KEY` (a miss is an indistinguishable `404`), so only the proxy — which holds the key
— can use it. The redirect endpoint stays public by design.

## Testing

```bash
cd backend
./gradlew build test
```

- **Unit** (JUnit 5 + MockK): `UrlValidator`, `LinkService`, `CodeGenerator`, rate limiter,
  owner-token hashing, and the service-key constant-time compare.
- **Repository** (Testcontainers, real Postgres 18): parameterized queries, uniqueness,
  soft-delete/expiry filtering, click aggregation, and a `'; DROP TABLE links; --` inertness test.
- **End-to-end** (Ktor test client + Testcontainers): every endpoint plus the explicit security
  cases — dangerous schemes, private/metadata targets, oversized body → `413`, malformed JSON →
  clean `400`, missing/wrong owner token → `404`, missing/wrong service key → `404` (with the
  redirect still public), rate limit → `429 + Retry-After`, the security headers, and that
  `/health` and `/openapi.yaml` stay public.

CI (GitHub Actions) runs `./gradlew build` (compile + full test suite), a Trivy dependency
scan that fails on any HIGH/CRITICAL CVE, `dependency-review` on PRs, OpenSSF Scorecard, and a
Conventional-Commit PR-title check. Dependabot keeps Gradle, Actions, and Docker dependencies
current, and `main` is protected by a ruleset requiring the `build` check and a PR.

Static analysis is enforced at the compiler: `allWarningsAsErrors = true` fails the build on any
Kotlin warning. A dedicated SAST scanner (CodeQL / detekt) is intentionally *not* wired in yet —
as of this writing neither supports the bleeding-edge **JDK 25 + Kotlin 2.4** toolchain this
project pins (CodeQL's Kotlin extractor sees "no source"; detekt's bundled compiler rejects
JVM target 25). It should be added once the tooling catches up, or by pinning an older toolchain.

## Out of scope (and why)

- **Multi-instance rate limiting.** The token bucket is in-memory, so limits are per-instance.
  Correct behind a single instance; a horizontally-scaled deployment would move the buckets to
  Redis. Kept in-memory here to avoid a second piece of infrastructure for a demo. The map is
  bounded — buckets that have refilled to full are evicted once it grows large — so churning/spoofed
  source IPs can't grow it without limit.
- **Click analytics beyond a count.** `clicks` stores only `link_id` and a timestamp — no IP,
  user-agent, or referrer. Collecting those would make this a tracking product and add a
  privacy-compliance surface that isn't the point. A crash between enqueue and flush can lose at
  most one un-flushed batch of counts — acceptable for analytics, not for billing-grade data.
- **User accounts / sessions.** Authorization is a single per-link owner token by design. A full
  identity system is a different project.

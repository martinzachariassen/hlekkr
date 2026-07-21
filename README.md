# URL Shortener API

A production-minded, security-hardened URL shortener REST API built in **Kotlin + Ktor**
with **raw JDBC** (no ORM) over **PostgreSQL**. This is a portfolio project: the goal is to
show reasoning about persistence, concurrency, and security without a framework hiding the
decisions.

The repository is a monorepo. The backend lives in [`backend/`](backend); a web client will
be added later under [`frontend/`](frontend) as an independent app.

```
url-shortener/
├── backend/     # Ktor API (this project)
├── frontend/    # reserved for the web client (later)
├── docker-compose.yml
└── .github/     # CI: build/test, dependency scan, CodeQL
```

## Contents

- [Architecture](#architecture)
- [Why Ktor + raw JDBC over Spring/ORM](#why-ktor--raw-jdbc-over-springorm)
- [API](#api)
- [Security decisions](#security-decisions)
- [Running locally](#running-locally)
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

| Method   | Path                  | Auth               | Notes                                                                    |
| -------- | --------------------- | ------------------ | ------------------------------------------------------------------------ |
| `POST`   | `/links`              | none (rate-limited)| Body `{targetUrl, expiresAt?}` → `{code, shortUrl, ownerToken}`. `ownerToken` is shown **once**. |
| `GET`    | `/{code}`             | none               | `302` redirect. Records a click asynchronously. Generic `404` if missing/expired/deleted. |
| `GET`    | `/links/{code}/stats` | Bearer owner token | `{totalClicks, last7Days: [{date, count}]}`                              |
| `DELETE` | `/links/{code}`       | Bearer owner token | Soft delete.                                                             |

Example:

```bash
# Create
curl -s -X POST localhost:8080/links \
  -H 'Content-Type: application/json' \
  -d '{"targetUrl":"https://example.com"}'
# → {"code":"d10ndrX","shortUrl":"http://localhost:8080/d10ndrX","ownerToken":"g3UQ…"}

# Redirect
curl -i localhost:8080/d10ndrX          # 302 Location: https://example.com

# Stats / delete (owner token required)
curl localhost:8080/links/d10ndrX/stats -H "Authorization: Bearer g3UQ…"
curl -X DELETE localhost:8080/links/d10ndrX -H "Authorization: Bearer g3UQ…"
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
- **Hardening headers on every response.** HSTS, `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, and `Content-Security-Policy:
  default-src 'none'` (this is a JSON API that serves no HTML). CORS is an explicit allow-list,
  never `*`, and credentials are not enabled.
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

## Running locally

Requires JDK 25 and Docker.

### Full stack (Docker Compose)

```bash
cp .env.example .env     # fill in passwords
docker compose up --build
```

This starts Postgres 18, runs Flyway migrations under the admin role (a one-shot `flyway`
service), then starts the API on `http://localhost:8080` connected under the least-privilege
app role.

### App against a local database (`./gradlew run`)

```bash
cp .env.example .env
docker compose up -d db        # just Postgres
cd backend
DATABASE_URL=jdbc:postgresql://localhost:5432/shortener \
DATABASE_USER=shortener_app DATABASE_PASSWORD=<app pw> \
DATABASE_MIGRATION_USER=shortener_admin DATABASE_MIGRATION_PASSWORD=<admin pw> \
BASE_URL=http://localhost:8080 \
./gradlew run
```

Here the app applies migrations itself on startup (using the admin credentials it is given) —
convenient for local iteration. In containers this is turned off (`RUN_MIGRATIONS_ON_STARTUP=false`)
because migrations are a separate privileged step.

## Testing

```bash
cd backend
./gradlew build test
```

- **Unit** (JUnit 5 + MockK): `UrlValidator`, `LinkService`, `CodeGenerator`, rate limiter,
  owner-token hashing.
- **Repository** (Testcontainers, real Postgres 18): parameterized queries, uniqueness,
  soft-delete/expiry filtering, click aggregation, and a `'; DROP TABLE links; --` inertness test.
- **End-to-end** (Ktor test client + Testcontainers): every endpoint plus the explicit security
  cases — dangerous schemes, private/metadata targets, oversized body → `413`, malformed JSON →
  clean `400`, missing/wrong owner token → `404`, rate limit → `429 + Retry-After`, and the
  presence of security headers.

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
  Redis. Kept in-memory here to avoid a second piece of infrastructure for a demo.
- **Click analytics beyond a count.** `clicks` stores only `link_id` and a timestamp — no IP,
  user-agent, or referrer. Collecting those would make this a tracking product and add a
  privacy-compliance surface that isn't the point. A crash between enqueue and flush can lose at
  most one un-flushed batch of counts — acceptable for analytics, not for billing-grade data.
- **User accounts / sessions.** Authorization is a single per-link owner token by design. A full
  identity system is a different project.

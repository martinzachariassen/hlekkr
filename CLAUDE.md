# CLAUDE.md

How to work in this repo — a security-hardened URL shortener, built as a portfolio
project to *show the reasoning* behind persistence, concurrency, and security
without a framework hiding the decisions. Read this before proposing changes; the
[README](README.md) carries the full architecture and the rationale behind every
security choice, and [`docs/backend.md`](docs/backend.md) /
[`docs/frontend.md`](docs/frontend.md) cover each app in depth (the per-app READMEs
are short pointers to those).

> General working defaults (git, code style, communication) come from the global
> agent config outside this repo. This file covers what's specific to this project,
> and its rules win where they overlap.

## Layout — a two-app monorepo

- [`backend/`](backend) — the API. Kotlin + Ktor (Netty) + **raw JDBC** over
  PostgreSQL, Gradle (Kotlin DSL). Source under
  `src/main/kotlin/no/mlz/shortener/`, split by role: `config/`, `plugins/` (Ktor
  feature install), `routes/`, `service/`, `repository/`, `security/`. Tests mirror
  that tree under `src/test/kotlin/`.
- [`frontend/`](frontend) — a single-screen web client (Vite + React + TypeScript,
  no UI framework). One stylesheet, a handful of components; served in production by
  [Caddy](frontend/Caddyfile).
- **Root** orchestrates: [`mise.toml`](mise.toml) (pinned toolchain + one-command
  dev stack), [`docker-compose.yml`](docker-compose.yml), and `.github/` (CI).

## Running & tasks — go through mise

`mise` owns the toolchain (java 25, node lts, pnpm) and every dev verb. Don't invoke
`gradlew`/`pnpm` by hand when a task exists.

```sh
mise install        # first time: fetch pinned tools
mise run dev        # Postgres + API (from source) + web app — Ctrl-C stops all
mise run backend    # just the API against local Postgres
mise run web        # just the web app (expects the API running)
mise run test       # backend suite: unit + Testcontainers + e2e (needs Docker)
mise run lint       # compileKotlin + compileTestKotlin — the static-analysis gate
mise run build      # backend jar + frontend bundle
mise run up / down / logs   # the whole stack in Docker
```

`mise run dev`/`backend` apply Flyway migrations on startup (admin creds) for
convenience; containers turn that off (`RUN_MIGRATIONS_ON_STARTUP=false`) because
migrations are a separate privileged step there.

## Comments

Keep them to an absolute minimum, in both apps. Explain **why**, never **what** —
the code already says what, so don't restate the line below or narrate obvious
steps. A comment earns its place only for the genuinely non-obvious: a security
posture that isn't visible in the call (the 404-not-403 probe defense, constant-time
compares), a concurrency subtlety, a deliberate gotcha (why Shadow over buildFatJar),
or a magic value's meaning (`62^7` collision space). When one does earn its place,
keep it short. Prefer a clear name or small refactor over a comment; leave no
commented-out code behind.

## Backend conventions

- **Wiring is explicit, not magic.** `Application.kt` installs every Ktor plugin,
  in order, and hand-builds the dependency graph (`AppComponents`). Nothing is
  reflective or auto-configured — that visibility is the point of the project. New
  dependencies get constructed there and passed down, not resolved by a container.
- **Raw JDBC, always parameterized.** Every query is a hand-written
  `PreparedStatement` via the small helpers in `repository/Database.kt`. **Never
  build SQL with string concatenation/interpolation** — the auditable "grep finds no
  concatenation" posture is a feature. There is a repository test asserting
  injection inertness; keep it green.
- **Migrations are Flyway `.sql` files** under
  `src/main/resources/db/migration/`, versioned (`V<n>__name.sql`) and reviewable.
  The app connects under a **DML-only role**; migrations run under a privileged one.
  Schema changes are blast-radius changes — flag them, don't slip them in.
- **Config is env-driven and fails fast.** Non-secret defaults live in
  `application.conf`; secrets arrive via `${?VAR}` substitution and `AppConfig.load`
  refuses to boot if a required one is blank. Add new config to `AppConfig`, keep
  secrets out of the committed files (`.env` is git-ignored; only `.env.example`
  ships), and never log the connection string.
- **The redirect is the hot path.** `GET /{code}` must never block on a click
  write — clicks go through `ClickTracker`'s coroutine `Channel` and flush in
  batches. Don't add synchronous work to that path.
- Kotlin **2.4** on **JDK 25**, `allWarningsAsErrors = true` — a warning fails the
  build, so `mise run lint` before pushing. Package the runnable jar with
  `shadowJar` (not Ktor's buildFatJar) so Flyway's service files merge.

## Security — treat the posture as load-bearing

The README's *Security decisions* section is the spec; each item maps to code and an
explicit test. When touching `security/`, `service/UrlValidator`, auth, or the
routes, **do not weaken these without calling it out**, and add/adjust the matching
test:

- `UrlValidator` is SSRF-preventive (scheme allow-list; rejects loopback/link-local/
  private/metadata ranges, `localhost`, embedded creds, self-referential targets)
  **and** consults `HostBlocklist` for content policy.
- Short codes are random base62 from `SecureRandom` with a fail-closed retry — never
  sequential/enumerable. Owner tokens are returned once and stored only as a SHA-256
  hash, compared in constant time.
- Auth/ownership failures return a generic **`404`, never `403`**, so the API can't
  be used to probe which codes exist. The `X-Internal-Key` service gate and the
  per-link owner token are two independent layers.
- Every response carries the hardening headers; errors go through `StatusPages`
  which leaks no raw detail (correlation id only). Bodies are bounded (16 KB → 413).

## Frontend conventions

- Vite + React + TS, one stylesheet — no UI framework, no state library. Keep it to
  the single non-scrolling screen (result/stats states swap in place).
- **All config is `VITE_*` build-time — treat the bundle as public.** Never put a
  secret in it. There is deliberately **no** internal-key var here; in production the
  key lives on the Caddy proxy and is injected server-side.
- Analytics (Umami) is opt-in and cookieless: ship **zero** analytics code unless
  `VITE_UMAMI_SRC` + `VITE_UMAMI_WEBSITE_ID` are set. Don't add a tracker that
  contradicts the privacy promise on the page.

## Quality gates

Pre-commit and CI run overlapping checks — pass them locally before pushing:

```sh
pre-commit run --all-files    # shellcheck, typos, gitleaks, actionlint (+ gradle build/test on pre-push)
mise run test                 # or: cd backend && ./gradlew build
```

- CI (`.github/workflows/`) runs `./gradlew build` (compile + full suite), a Trivy
  scan that **fails on any HIGH/CRITICAL CVE**, `dependency-review` on PRs, OpenSSF
  Scorecard, and a Conventional-Commit PR-title check.
- Tests: unit (JUnit 5 + MockK), repository (Testcontainers, real Postgres 18), and
  e2e (Ktor test client). The security cases are part of the suite — keep them.
- A dedicated SAST scanner (CodeQL/detekt) is intentionally not wired in yet: neither
  supports the JDK 25 + Kotlin 2.4 toolchain as of writing. Don't add one until it
  does.

## Git & PRs

- **Always open a PR; never push directly to `main`** — CI must get a chance to run,
  and `main` is a protected ruleset requiring the `build` check and a PR. This
  overrides the "act and commit" autonomy from the global config.
- Conventional Commits for every commit *and* PR title (`<type>(<scope>): <subject>`,
  ≤ 72 chars) — CI enforces the PR title. Fill in the
  [PR template](.github/pull_request_template.md).
- **Never commit secrets** — `gitleaks` scans every commit and push. Use
  placeholders or `.env.example`, never real values.

## Deployment

Railway, three services: **web** (Caddy, the only public domain), **api** (private,
`SERVER_HOST=::` for IPv6), **Postgres** (private). The full variable list lives in
`docs/deployment.md` and the "why CORS isn't the gate" reasoning in the README — consult
both before changing the deploy topology, `railway.toml`, Dockerfiles, or the Caddyfile.

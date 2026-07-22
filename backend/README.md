# backend — URL shortener API

Kotlin + [Ktor](https://ktor.io) (Netty) over raw JDBC and PostgreSQL. The full architecture,
security rationale, and deployment guide live in the [repository README](../README.md).

```bash
./gradlew build          # compile + full test suite: unit, Testcontainers, e2e (needs Docker)
./gradlew run            # needs DATABASE_* env vars — or use `mise run backend` from the repo root
./gradlew shadowJar      # runnable fat jar in build/libs/*-all.jar
```

Source is split by role under `src/main/kotlin/no/mlz/shortener/`:

| Package | Responsibility |
| --- | --- |
| `config/` | env-driven `AppConfig`, fails fast on missing secrets |
| `plugins/` | explicit Ktor feature install (CORS, StatusPages, OpenAPI, serialization) |
| `routes/` | HTTP surface + DTOs |
| `service/` | `LinkService`, `UrlValidator`, `ClickTracker` |
| `repository/` | hand-written `PreparedStatement`s over HikariCP |
| `security/` | code generation, owner tokens, rate limiting, blocklist, headers |

Tests mirror the same tree under `src/test/kotlin/`.

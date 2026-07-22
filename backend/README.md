# backend — URL shortener API

Kotlin + [Ktor](https://ktor.io) (Netty) over raw JDBC and PostgreSQL.

- **Setup, configuration, and the API contract:** [`docs/backend.md`](../docs/backend.md)
- **Architecture and security reasoning:** [root README](../README.md)

```bash
./gradlew build          # compile + full test suite (needs Docker)
./gradlew run            # needs DATABASE_* env vars — or `mise run backend` from the root
./gradlew shadowJar      # runnable fat jar in build/libs/*-all.jar
```

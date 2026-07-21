# backend — URL Shortener API

Kotlin + Ktor + raw JDBC over PostgreSQL. Full architecture, security rationale, run and test
instructions are in the [repository README](../README.md).

Quick start:

```bash
./gradlew build test        # unit + Testcontainers + e2e (needs Docker)
./gradlew run               # needs DATABASE_* env vars — see ../README.md
./gradlew shadowJar         # runnable fat jar in build/libs/*-all.jar
```

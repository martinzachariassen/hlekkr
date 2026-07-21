# syntax=docker/dockerfile:1

# ---- Build stage: full JDK + Gradle, produces a fat jar ----
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app

# Warm the dependency cache separately from source for faster incremental builds.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies >/dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew --no-daemon shadowJar

# ---- Runtime stage: minimal JRE, non-root, no build tools ----
FROM eclipse-temurin:25-jre-alpine AS runtime
WORKDIR /app

# Run as an unprivileged user; never as root.
RUN addgroup -S app && adduser -S -G app app
USER app

COPY --from=build /app/build/libs/*-all.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
    CMD wget -qO- http://localhost:8080/health || exit 1

# Container-aware heap sizing; fail fast if the JVM can't start.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

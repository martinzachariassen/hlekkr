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

# ---- Blocklist stage: fetch content-policy domain lists, baked into the image ----
# HaGeZi NSFW (adult) + URLhaus (malware). Suffix-matched by HostBlocklist at startup, so the
# registrable-domain lists cover subdomains too. Refreshed on every rebuild — no runtime network
# dependency. Fetch is best-effort (retries); a briefly-unreachable feed won't fail the build, and
# the app logs how many domains it actually loaded. Off by default for local Compose builds, which
# pass FETCH_BLOCKLISTS=false to stay fast and offline (the list is then simply empty = a no-op).
FROM alpine:3 AS blocklist
ARG FETCH_BLOCKLISTS=true
WORKDIR /lists
# Cache-bust marker: the refresh-blocklist workflow bumps .blocklist-epoch weekly. Copying it before
# the fetch invalidates the layer below, so a cached rebuild still re-downloads current lists. Without
# this, an unchanged Dockerfile would cache the fetch and ship stale domains forever.
COPY .blocklist-epoch ./.epoch
RUN set -eux; \
    touch hosts.txt; \
    if [ "$FETCH_BLOCKLISTS" = "true" ]; then \
        apk add --no-cache curl; \
        curl -fsSL --retry 5 --retry-all-errors --max-time 60 \
            https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/nsfw-onlydomains.txt \
            -o nsfw.txt || true; \
        curl -fsSL --retry 5 --retry-all-errors --max-time 60 \
            https://urlhaus.abuse.ch/downloads/hostfile/ \
            -o malware.txt || true; \
        cat nsfw.txt malware.txt >> hosts.txt 2>/dev/null || true; \
    fi; \
    wc -l hosts.txt

# ---- Runtime stage: minimal JRE, non-root, no build tools ----
FROM eclipse-temurin:25-jre-alpine AS runtime
WORKDIR /app

# Run as an unprivileged user; never as root.
RUN addgroup -S app && adduser -S -G app app
USER app

COPY --from=build /app/build/libs/*-all.jar app.jar

# Baked content-policy blocklist; BLOCKED_HOSTS_FILE points the app at it (empty file => no-op).
COPY --from=blocklist /lists/hosts.txt /app/blocklist/hosts.txt
ENV BLOCKED_HOSTS_FILE=/app/blocklist/hosts.txt

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
    CMD wget -qO- http://localhost:8080/health || exit 1

# Container-aware heap sizing; fail fast if the JVM can't start.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

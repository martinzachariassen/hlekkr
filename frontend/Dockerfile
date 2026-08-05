# syntax=docker/dockerfile:1

# ---- Build the static bundle ----
FROM node:24-alpine AS build
WORKDIR /app
RUN corepack enable

COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY . .

# Vite inlines import.meta.env.* at build time. /api routes every call through the same-origin
# Caddy proxy, so the private backend needs no public domain of its own.
ARG VITE_API_BASE=/api
ARG VITE_UMAMI_SRC
ARG VITE_UMAMI_WEBSITE_ID
ENV VITE_API_BASE=$VITE_API_BASE \
    VITE_UMAMI_SRC=$VITE_UMAMI_SRC \
    VITE_UMAMI_WEBSITE_ID=$VITE_UMAMI_WEBSITE_ID
RUN pnpm build

# ---- Serve static + proxy /api to the backend ----
FROM caddy:2-alpine AS runtime
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/dist /srv
EXPOSE 8080

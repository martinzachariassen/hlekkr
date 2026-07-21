# syntax=docker/dockerfile:1

# ---- Build the static bundle ----
FROM node:22-alpine AS build
WORKDIR /app
RUN corepack enable

COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY . .

# Vite inlines import.meta.env.* at build time, so these must be present when `pnpm build` runs.
# VITE_API_BASE=/api routes management calls through the same-origin Caddy proxy (see Caddyfile).
# VITE_PUBLIC_API_URL is the backend's own public origin (Swagger link, must skip the proxy).
ARG VITE_API_BASE=/api
ARG VITE_PUBLIC_API_URL
ARG VITE_UMAMI_SRC
ARG VITE_UMAMI_WEBSITE_ID
ENV VITE_API_BASE=$VITE_API_BASE \
    VITE_PUBLIC_API_URL=$VITE_PUBLIC_API_URL \
    VITE_UMAMI_SRC=$VITE_UMAMI_SRC \
    VITE_UMAMI_WEBSITE_ID=$VITE_UMAMI_WEBSITE_ID
RUN pnpm build

# ---- Serve static + proxy /api to the backend ----
FROM caddy:2-alpine AS runtime
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/dist /srv
EXPOSE 8080

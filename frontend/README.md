# Frontend

A single-screen web client for the [`backend`](../backend) URL-shortener API. Paste a URL, get a
short one back plus a one-time owner key; look a link up by code + key to see its clicks or delete
it. Everything lives on one non-scrolling screen.

**Stack:** Vite + React + TypeScript. No UI framework — a handful of components and one stylesheet.
Theme (warm paper, teal accent) is borrowed from [mlz.no](https://mlz.no); body/UI type is
[Inter](https://rsms.me/inter/) with Instrument Serif for the display headline.

## Run

From the repo root, `mise run dev` starts Postgres, the API, and this app together. To run the
frontend alone against an already-running API:

```bash
pnpm install
pnpm dev            # http://localhost:5173
```

## Configuration

Copy `.env.example` to `.env.local` and adjust. All vars are build-time (`VITE_*`), so treat the
bundle as public — never put a real secret in it.

| Variable | Purpose |
| --- | --- |
| `VITE_API_BASE` | Where API calls go. Dev: `http://localhost:8080` (default; the Swagger link points here too). Prod: `/api`, same-origin through the Caddy proxy. |
| `VITE_UMAMI_SRC` + `VITE_UMAMI_WEBSITE_ID` | Enable [Umami](https://umami.is) analytics. Unset ⇒ zero analytics code ships. |

There is deliberately **no** internal-key variable here: in production the key lives on the Caddy
proxy and is injected server-side, so it never reaches the browser. See the root README
(*Restricting the API to your frontend*).

## Production build & proxy

`Dockerfile` builds the static bundle and serves it from [Caddy](https://caddyserver.com), the only
public service. Caddy fronts the private backend: it injects `X-Internal-Key` on `/api/*` and passes
the public routes (`/{code}` redirect, `/openapi.yaml`, `/swagger`) straight through, so the app and
its API are same-origin (no CORS) and the key stays server-side. See `Caddyfile`.

## Analytics

Umami is cookieless and privacy-first, which is why it's the one tracker here — it never contradicts
the "no tracking" promise on the page. It loads only when both env vars are set. Actionable controls
carry `data-umami-event` attributes (Umami binds them on click); a few outcomes Umami can't observe
(`shorten-success`, `stats-view`, `delete-success`) are reported via `umami.track(...)`.

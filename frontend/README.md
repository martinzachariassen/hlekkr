# Frontend

A single-screen web client for the [`backend`](../backend) URL-shortener API. Paste a URL, get a
short one back plus a one-time owner key; look a link up by code + key to see its clicks or delete
it. Everything lives on one non-scrolling screen.

**Stack:** Vite + React + TypeScript. No UI framework — a handful of components and one stylesheet.
Theme (warm paper, teal accent, mono-first type) is borrowed from [mlz.no](https://mlz.no).

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
| `VITE_API_BASE` | API origin. Defaults to `http://localhost:8080`. |
| `VITE_UMAMI_SRC` + `VITE_UMAMI_WEBSITE_ID` | Enable [Umami](https://umami.is) analytics. Unset ⇒ zero analytics code ships. |
| `VITE_INTERNAL_KEY` | Only for a direct-to-API deploy enforcing `INTERNAL_API_KEY`. Prefer a server-side proxy in production — see the root README. |

## Analytics

Umami is cookieless and privacy-first, which is why it's the one tracker here — it never contradicts
the "no tracking" promise on the page. It loads only when both env vars are set. Actionable controls
carry `data-umami-event` attributes (Umami binds them on click); a few outcomes Umami can't observe
(`shorten-success`, `stats-view`, `delete-success`) are reported via `umami.track(...)`.

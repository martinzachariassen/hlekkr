# Docs

The [root README](../README.md) is the overview. These pages carry the depth:

- [`security.md`](security.md) — every security decision with its full reasoning, and why
  the service key (not CORS) gates the API
- [`backend.md`](backend.md) — the API: setup, configuration, testing, and the contract it
  exposes to the frontend
- [`frontend.md`](frontend.md) — the web client: setup, configuration, and what it expects
  from the API and the Caddy proxy
- [`deployment.md`](deployment.md) — how the live site is deployed (Railway); the stack
  itself runs on any container platform

# Frontend — coming soon

This directory is reserved for the web client that will consume the
[`backend`](../backend) URL-shortener API.

It is intentionally empty for now: the backend is a self-contained, independently
deployable service, and the frontend will be added as a separate app in this monorepo
(its own build, its own CI job path-filtered to `frontend/**`) without disturbing the
backend.

Planned: a small single-page app to create short links and view per-link stats using the
owner token returned at creation time.

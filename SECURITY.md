# Security policy

Hlekkr is a solo-maintained portfolio project, but the security posture is
real and load-bearing (see [`docs/security.md`](docs/security.md)) — reports
are taken seriously.

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting rather than a public
issue: open the [**Security** tab](https://github.com/martinzachariassen/hlekkr/security)
on this repo and click **Report a vulnerability**. That opens a private
advisory only the maintainer can see.

There's no formal SLA — this is a single-maintainer project — but expect an
initial response within a few days.

## Scope

In scope: the API (`backend/`), the web client (`frontend/`), and the
deployment configuration in this repo. Out of scope: the third-party
blocklist feeds it consumes (report those upstream) and the hosting
platform itself (Railway).

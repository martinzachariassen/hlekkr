## Summary

<!-- What does this PR change, in one or two lines? -->

## Motivation

<!-- Why is this change needed? What problem does it solve or what does it enable? -->

## Changes

<!-- The notable changes, as a short list. -->
-

## Type of change

<!-- Match the Conventional Commit type of the PR title. -->
- [ ] feat — new capability
- [ ] fix — bug fix
- [ ] docs — documentation only
- [ ] refactor — no behavior change
- [ ] chore / build / ci — tooling, dependencies, pipelines
- [ ] style / perf / test

## How tested

<!-- The exact commands you ran. Delete lines that don't apply. -->
- [ ] `cd backend && ./gradlew build test` is green (unit + Testcontainers + e2e)
- [ ] `docker compose up --build` boots and endpoints respond
- [ ] `pre-commit run --all-files` is clean

## Risk & rollout

<!-- Blast radius: does this touch the DB schema/migrations, auth (owner tokens),
     rate limiting, the least-privilege DB role, container build, or CI? Anything
     to do after merge (e.g. run migrations under the admin role)? -->

## Checklist

- [ ] PR title is a Conventional Commit (`type(scope): subject`, ≤ 72 chars)
- [ ] Every SQL statement is a parameterized `PreparedStatement` — no string interpolation
- [ ] No secret, credential, or stack trace added to logs or responses
- [ ] Security-relevant changes have a matching test (see `LinkRoutesTest`, `UrlValidatorTest`)
- [ ] Docs updated (`README.md`) if behavior, endpoints, or security posture changed
- [ ] CI is green

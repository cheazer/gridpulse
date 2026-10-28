# 0001. Monorepo with one folder per service

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

GridPulse has three services in three languages (Java, Python, C#) that share one event contract and one database
schema. A single developer maintains it, and reviewers (including interviewers) should be able to clone one thing
and run it.

## Options considered

1. **Polyrepo**: one repository per service plus one for schemas. Strong isolation and independent release
   cadence, but contract changes need coordinated PRs across repos and version pinning between them. That overhead
   buys nothing for a single team.
2. **Monorepo, language-native builds per folder**: each service keeps its own idiomatic build (Gradle, uv,
   dotnet). CI uses path filters so a change to `services/scheduler/**` only builds the scheduler.
3. **Monorepo with a polyglot build tool (Bazel, Pants)**: hermetic, incremental builds across languages, but a
   steep learning curve and unidiomatic for each ecosystem.

## Decision

Option 2. Top-level `schemas/`, `db/`, `deploy/`, `observability/`, `loadtest/` and `docs/` hold cross-cutting
assets; `services/<name>/` holds each deployable with its native toolchain.

## Consequences

- A schema change and every consumer update land in **one atomic PR**, and CI validates them together.
- Each service still builds, tests and ships as an independent container image, so the deployment model is the
  same as a polyrepo's.
- Path-filtered workflows must be kept accurate; a shared-folder change (`schemas/`) triggers all services.
- If the services were ever owned by separate teams, splitting them out is mechanical because no service imports
  another's code. They only share the schema files.

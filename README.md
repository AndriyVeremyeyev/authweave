# AuthWeave

**AI-assisted identity architecture and change assurance platform for evidence-backed authentication decisions.**

> Status: local application foundation. No production release is available yet.

AuthWeave is an engineering workspace for designing identity and authentication
architecture. It will help engineers collect application requirements, compare identity
provider capabilities, evaluate authentication patterns and produce reviewable
architecture decisions backed by dated evidence.

The project follows three core principles:

- deterministic constraints before AI-generated explanations;
- structured, source-backed provider facts before semantic retrieval;
- human ownership of every final architecture decision.

Development is local-first and incremental. This README describes the project at a
high level; additional documentation remains private while the product is being
shaped.

## Local development

Required tools are Java 21, Node.js 24 with npm 11, Python 3.13, Docker Desktop and
GNU Make. From the repository root, install project-local dependencies and create the
ignored local environment file:

```shell
make setup
```

The setup command generates local database passwords only when `infra/.env` does not
already exist. It never overwrites an existing environment file.

Start PostgreSQL and run all local checks:

```shell
make infra-up
make check
```

Run `make help` to see component-specific checks and development-server commands.

## Current behavior

The Core API supports workspace provisioning and assessment creation, reading and
profile replacement in PostgreSQL. It validates profile structure and domain
contradictions, returns structured problem details and detects stale updates.
Saving an unchanged profile preserves its status, version and update timestamp.

The API is currently unauthenticated. It binds to `127.0.0.1` by default and refuses
to start with a non-loopback `server.address`. Keep it local; do not expose it through
a reverse proxy or tunnel. Workspace IDs scope data queries but do not authenticate
callers. Shared deployment requires authentication and workspace authorization.

The web application is a landing page and the AI worker exposes health endpoints.
Provider evaluation, assessment history, ADR export and authenticated workflows are
still planned.

## Profiles and contracts

Requirement criticality has five explicit values: `REQUIRED`, `PREFERRED`,
`NOT_REQUIRED`, `FORBIDDEN` and `UNKNOWN`. `NOT_REQUIRED` imposes no constraint;
`FORBIDDEN` excludes a capability. User population alone never implies a prohibition.
AI requirement proposals use the same vocabulary and still require human acceptance.
The unreleased AI contract previously used `hard-requirement`, `important` and
`preference`; consumers must now send the canonical values above. Existing stored
`NOT_REQUIRED` values are not automatically converted to `FORBIDDEN`. Review profiles
that previously used that value to express an actual prohibition.

Profile replacement requires every documented section and field. Unknown properties,
duplicate array items or JSON keys, numeric enum values, implicit string-to-number
conversions and fractional versions are rejected. Invalid requests return
`application/problem+json` with a stable `code` and field `violations`; contradictory
profiles return domain `issues` with status 422. State and version conflicts return 409.

`make check-core` runs Java unit and PostgreSQL integration tests, then validates the
captured MVC request/response payloads against the shared JSON Schemas using AJV. It
requires `make setup-contracts` and Node.js as well as Java and Docker. The same check
runs in CI; `make check-contracts` separately validates OpenAPI and synthetic fixtures.

## Database configuration

Set `AUTHWEAVE_POSTGRES_PORT`, `AUTHWEAVE_POSTGRES_DB` and
`AUTHWEAVE_POSTGRES_ADMIN_USER` in ignored `infra/.env` to customize local PostgreSQL.
`make dev-core` and `make generate-jooq` load that file. Runtime connections and code
generation use the same port and database settings. Changing the database name or
initialization credentials does not reconfigure an existing PostgreSQL volume.

`AUTHWEAVE_CORE_DB_URL` optionally overrides the runtime JDBC URL.
`AUTHWEAVE_MIGRATION_DB_URL` optionally overrides the Flyway and jOOQ JDBC URL; otherwise
they use the runtime URL. Maven `-Dauthweave.codegen.url` and
`-Dauthweave.codegen.user` remain available as explicit code-generation overrides.

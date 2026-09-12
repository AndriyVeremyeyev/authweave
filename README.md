# AuthWeave

**AI-assisted identity architecture and change assurance platform for evidence-backed authentication decisions.**

> Status: early requirements preview, actively in development. Provider recommendations and architecture decisions are not available yet.

[Try the requirements preview](https://authweave.veremyeyev.com/preview).

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

The web application includes a browser-only requirements preview at `/preview`.
Start with a fictional B2B SaaS profile or a blank draft, edit six sections, review
open questions and download a JSON profile or Markdown requirements brief. Answers
stay in memory in the current workspace; they are not sent to the API or saved for
later. Download before leaving or refreshing the page.

The preview checks the shared JSON Schema only. It does not check cross-field domain
contradictions, verify compliance or compute provider recommendations. Empty
selections mean no choice was recorded, not that a topic is unnecessary.

The Core API stores immutable assessment revisions and atomic state-change events,
with workspace-scoped paginated history reads. Runtime database roles cannot update
or delete history. The API remains local-only, without authenticated workspace access.
The Core API also offers a read-only capability preflight against explicitly fictional
plans. Full provider evaluation, ADR export and authenticated workflows are still
planned. The AI worker exposes health endpoints.

To run just the preview, only Node.js and npm are required:

```shell
cd apps/web
npm ci
npm run dev
```

Run `npm test`, `npm run lint` and `npm run build` in `apps/web` to verify it. The
browser preview does not require PostgreSQL, the Java API, API keys or accounts.

## Profiles and contracts

### Synthetic assessments

With the local database configured and running, use `make seed-core` to add three
fictional assessments: B2B SaaS, public-sector portal and internal workforce. The
command uses `infra/.env`, prints their API paths and exits without starting an HTTP
server. It does not contact identity providers or AI services.

All three belong to the dedicated synthetic workspace
`60000000-0000-4000-8000-000000000001`. Each starts as a draft at version 0 with its
complete profile, revision and event. Re-running the command skips existing IDs,
including assessments you have edited or archived. It never resets your changes.
Normal API startup does not seed data. The scenarios preserve unknown inputs; they
are not provider evidence, compliance claims or computed recommendations.

### Synthetic capability preflight

After seeding and starting the local Core API, inspect the B2B example:

```shell
curl --fail --silent --show-error \
  http://127.0.0.1:8080/api/v1/workspaces/60000000-0000-4000-8000-000000000001/assessments/60000000-0000-4000-8000-000000000101/capability-preflight
```

This GET does not change assessment state or history. It checks nine protocol,
provisioning and MFA capabilities against three fictional plan/region options.
Checks include stable reason codes and dated synthetic evidence. Missing, unreviewed,
future and more-than-90-day-old facts cannot prove a match or exclusion. A forbidden
capability is acceptable only when absent or optional and kept disabled.

`MATCHES_CHECKED_REQUIREMENTS` is deliberately narrower than full eligibility:
`deferredPaths` lists unassessed dimensions and `recommendationReady` is always false.
No scores, winners, real provider claims or persisted evaluations are produced.
All evidence URLs use reserved `.invalid` hosts and are never fetched. The fixture's
observation dates are fixed, not refreshed automatically; tests use an explicit clock.
The catalog and policy versions plus evaluation instant identify the inputs to this
partial check. PostgreSQL catalog publication and durable result pinning remain planned.

### Contract validation

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

The web profile schema, synthetic example and dependency-free browser validator are
generated from `packages/contracts` and committed so the web application can build
independently. After changing the profile contract or its example fixture, run:

```shell
node packages/contracts/scripts/generate-web-profile.mjs
```

CI verifies that these generated artifacts are current. This is structural validation;
the Java domain rules remain the source of cross-field validation.

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

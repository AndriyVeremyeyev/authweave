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

### Optional local identity lab

Local ZITADEL infrastructure, an AuthWeave OIDC project/application and two ordinary synthetic
users support the local BFF sign-in flow. This does not provision application workspaces,
protect the Core API or grant a catalog-curator role. The public browser-only preview and
loopback-only Core API are unchanged. Discovery and synthetic password factors have been
verified locally; browser sign-in still needs a manual end-to-end check.

The separate `authweave-identity` Compose project contains ZITADEL API/Login v4.17.3,
PostgreSQL 17.10 and Traefik 3.7.7, pinned by tag and multi-platform digest. It owns separate
database/bootstrap volumes and a separate network. Only the proxy publishes `127.0.0.1:8081`;
it routes exclusively to ZITADEL, never to Core API, and has no Docker socket or dashboard.
This follows the [official ZITADEL Compose topology](https://zitadel.com/docs/self-hosting/deploy/compose)
with static proxy routes. Image metadata was checked for AMD64 and ARM64 support.

This is an HTTP loopback-only lab for synthetic identities, not a hosted deployment or a TLS
exception for future production sessions. It uses an isolated database superuser and root
bootstrap containers, as a development trade-off; no application database credentials are shared.
Do not expose it through a tunnel, reuse real account credentials or assume the host is isolated
from other local users. Do not share rendered Compose configuration, container inspection output,
tokens or unreviewed logs.

From the repository root, with Docker Desktop running:

```shell
make setup-auth
make check-auth-config
make auth-up
make auth-status
make auth-check
make auth-password-check
make auth-register
make auth-registration-check
```

`setup-auth` creates ignored `infra/zitadel/.env` with mode 600, a random 32-character master
key, distinct database/admin passwords and a Login UI service-token expiry 90 days ahead.
It never overwrites existing credentials or starts services. `auth-up` explicitly downloads
missing images and waits for four healthy services; normal `setup`/`infra-up` do not include
this lab. `auth-check` verifies the exact issuer `http://localhost:8081`, same-origin OIDC
endpoints, Code/PKCE S256 support and Login UI readiness, without logging in or following redirects.
`auth-password-check` uses the local Login UI service identity and ZITADEL Session API to verify
the synthetic administrator's username/password factors, then deletes its temporary session. It
does not print credentials, establish a browser session or prove AuthWeave login/authorization.
`auth-register` idempotently creates the `AuthWeave Local` project, a Web OIDC application with
Authorization Code, PKCE-compatible public-client settings and exact localhost callbacks, plus
ordinary `alice@authweave.localhost` and `bob@authweave.localhost` users. It generates their
distinct passwords in ignored `infra/zitadel/synthetic-users.env.local` and writes the issuer
and non-secret client ID to ignored `apps/web/.env.local`; both files have mode 600. It never
prints credentials or tokens. `auth-registration-check` verifies the existing registration
and both password factors without creating persistent resources or files. Both commands create
short-lived verification sessions and delete them.

Open `http://localhost:8081/ui/console` (use `localhost`, not `127.0.0.1`). Sign in as
`admin@authweave.localhost` using `AUTHWEAVE_ZITADEL_ADMIN_PASSWORD` from the ignored file,
opened privately in your editor. This synthetic account administers only the local IdP;
it is not an AuthWeave curator. Test an incorrect password once, then the correct password.
Neither ordinary synthetic user is an IdP administrator or an AuthWeave curator. No cloud
account, SMTP service or paid subscription is needed for this lab.

The BFF uses Authorization Code with PKCE, a one-use browser-bound login transaction and an
opaque, HttpOnly application cookie. Session state is held in the isolated `web` PostgreSQL
schema, with a 30-minute idle and eight-hour absolute limit. Provider tokens are not sent to
the browser or retained in the application database. With the application PostgreSQL container
running and ignored `infra/.env` restricted to mode 600, run `make migrate-web-auth` to apply
the replay-safe migration. Then run `make dev-web` and open `http://localhost:3000/account` to
try local sign-in and sign-out with a synthetic user. Start the separate identity lab first
with `make auth-up`; `apps/web/.env.local` must already contain the ignored issuer and client ID
created by `make auth-register`. `make check-web-auth-db` tests state replay, expiry, session
rotation/revocation and database role isolation. This local sign-in is not yet an authenticated
personal workspace or curator authorization. Do not expose the local HTTP lab or Core API.

Use `make auth-down` to stop only this stack and preserve both volumes. Keep the master key
with its database: losing or changing it can make stored encrypted data unusable. Bootstrap
password/expiry settings apply at first initialization; editing `.env` does not rotate an
existing administrator password or Login UI token. Arrange explicit token rotation before
expiry; do not regenerate `.env` or delete volumes as a troubleshooting shortcut.
`make check-auth-config` is also in CI and uses only synthetic values without starting services.

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
The Core API also offers read-only capability and context preflights against explicitly
fictional plans. Full provider evaluation, ADR export and authenticated workflows are still
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

### Synthetic context compatibility

Use the same assessment URL with `/eligibility-preflight` to combine the nine
capability checks with application type, clients, user populations, tenancy and
organization membership:

```shell
curl --fail --silent --show-error \
  http://127.0.0.1:8080/api/v1/workspaces/60000000-0000-4000-8000-000000000001/assessments/60000000-0000-4000-8000-000000000101/eligibility-preflight
```

Each selected context value is checked against its own plan/region evidence. Missing
evidence means unknown, not unsupported. A reviewed, fresh incompatibility excludes
the option even when its protocols match. All checks stay visible, including unknowns.
`OTHER` application type needs classification. An empty human population is not applied
only for machine-only clients; tenancy and membership still need explicit values.
Independent category checks do not establish combined configuration compatibility or
workload authorization. Hosting is a preference, not an elimination rule.

The response separates `capabilityChecks` and `contextChecks` and identifies both
policies. It still has `recommendationReady: false`: security dimensions beyond MFA
and operational constraints remain deferred. The existing `/capability-preflight`
response shape and scope are unchanged. Both endpoints now use catalog v4; the v1/v2/v3
fixtures and schemas remain as compatibility baselines. Neither endpoint writes to the database.

### Architecture pattern preflight

Use `/architecture-pattern-preflight` on the same assessment URL to compare BFF/session,
server-side session, SPA Code+PKCE, native Code+PKCE and M2M client credentials. The
response includes advantages, tradeoffs, prerequisites and references for each pattern.
Patterns address individual client types; mixed applications may need several patterns.

Only client selection and browser token minimization are checked. `PREFERRED` preserves
the browser alternatives without scoring. For `REQUIRED`, server-side patterns satisfy
the token-handling check under their stated prerequisites; SPA needs clarification of
acceptable exposure. Minimization is not a blanket token ban. `FORBIDDEN` minimization
needs clarification too. Unselected client patterns are `NOT_APPLICABLE`; an empty
client selection needs information. Browser criteria do not assess native/workload storage.

This is a partial comparison: prerequisites, provider/protocol compatibility and the
remaining security/operations requirements are not verified. `recommendationReady`
remains false, and the GET preserves assessment state/history. Definitions and references
are versioned with the policy; references are never fetched during evaluation. Sources:
[browser patterns](https://www.ietf.org/ietf-ftp/rfc/rfc10017.html),
[native apps](https://www.rfc-editor.org/rfc/rfc8252.html) and
[client credentials](https://www.rfc-editor.org/rfc/rfc6749.html#section-4.4).

### Residency inputs and profile versions

The local API v2 records where identity data may be stored at rest. Under
`/api/v2/workspaces/{workspaceId}/assessments`, use POST to create, GET `/{assessmentId}`
to read and PUT `/{assessmentId}/profile` to replace a complete profile with
`expectedVersion`. Workspace provisioning and events retain their v1 URLs; versioned
eligibility preflights are described below.

Profile v2 adds `security.dataResidencyDetails` alongside the existing
`security.dataResidency` criticality. For example, this fragment permits either listed
country for every selected category; it is not a complete update request:

```json
{
  "dataResidency": "REQUIRED",
  "dataResidencyDetails": {
    "allowedCountries": ["DE", "FR"],
    "dataCategories": ["USER_PROFILES", "BACKUPS"]
  }
}
```

Categories are `USER_PROFILES`, `CREDENTIALS`, `AUDIT_LOGS` and `BACKUPS`. Countries
must be uppercase ISO 3166-1 alpha-2 codes recognized by the backend. Empty arrays mean
unrecorded, not unrestricted. Both arrays must be explicit in v2; incomplete drafts
are allowed. `FORBIDDEN` is not interpreted as a country denylist. These fields do not
describe processing locations, support access, international transfers or compliance.
The v2 eligibility preflight below checks this scope; v1 preflights continue to defer it.

V2 reads project old profiles with empty details and `profileSchemaVersion: 2` without
changing stored data. This field identifies the response format, not a database update.
Without recorded authentication controls, compliance scope or usage inputs, the database retains v1 when both arrays
are empty and uses v2 when either is populated.
No-op saves change neither assessment version nor history. An explicit v2 clear may
return the current stored format to v1; previous v2 revisions remain unchanged.

V1 reads and updates return 409 `profile-upgrade-required` when the current profile
contains residency details, preventing silent data loss. GET `/{assessmentId}/revisions`
under v2 returns original v1/v2 snapshots with each stored `profileSchemaVersion`.
V1 history rejects pages containing v2 snapshots rather than dropping those details.
Flyway V4 only widens version constraints; it does not rewrite profiles or history.
The browser-only preview and its downloadable profile remain v1 for now.

### Scoped residency eligibility

GET `/api/v2/workspaces/{workspaceId}/assessments/{assessmentId}/eligibility-preflight`
adds `residencyChecks` to the existing capability and context checks. It works with
all supported stored profile versions, but does not evaluate the v3 authentication
controls described below. Unrecorded inputs remain unknown, not an unrestricted-storage
assumption. The response identifies catalog v4 and the
capability, context, residency and combined policy versions.

Catalog evidence lists confirmed storage countries for each category in the exact
option, not a menu of configurable locations. Region labels alone are not evidence.
`COMPLETE` covers all destinations, including replicas, for that category; recovery
copies are covered separately under `BACKUPS`. `PARTIAL` lists known destinations
but cannot rule out others. `UNKNOWN` records no countries. Empty evidence never
proves that a category is not stored. Alternative configurations need separate options.

For `REQUIRED`, complete, reviewed, fresh evidence wholly inside the allowlist passes.
A confirmed outside destination fails even when the remaining list is incomplete.
Missing categories, incomplete in-country coverage, unreviewed, future or stale evidence
give unknown, not a match. The existing inclusive 90-day evidence window applies.
`PREFERRED` is not scored and cannot eliminate; `NOT_REQUIRED` imposes no constraint.
`UNKNOWN` and `FORBIDDEN` require clarification rather than an inferred restriction.

For example, the first fictional option stores primary profiles in `DE` and backups
in `DE`/`FR`. Allowing only `DE` passes the profile check but fails when `BACKUPS` is
also required; allowing `DE` and `FR` passes both checks. Any checked failure determines
`DOES_NOT_MATCH`, without hiding other unknown checks. Unselected categories are not
evaluated. V1 eligibility keeps its previous scope and result shape.

This remains a synthetic, read-only preflight with `recommendationReady: false`.
It changes neither assessment state nor history. Processing, remote access, transfers,
compliance and remaining security/operations constraints are not covered. There is no
score, final recommendation, real-provider claim or persisted evaluation yet.

### Independent authentication controls

Profile API v3 adds `security.authenticationControls`. These are requirements for the
application being evaluated, not changes to AuthWeave's own login. All three fields
are explicit and initially `UNKNOWN`; the existing MFA requirement is not duplicated:

- `phishingResistance`: authentication cryptographically bound to the legitimate
  verifier. A manually entered one-time code alone does not provide this property.
- `nonExportableKeys`: authentication keys cannot leave their protected authenticator.
  Supporting passkeys, or disabling synchronization alone, does not establish this.
- `stepUpAuthentication`: request and verify stronger authentication before a sensitive
  action, not simply repeat the same-strength login.

For example, the following security fragment requires phishing resistance, records a
preference for non-exportable keys and leaves step-up undecided:

```json
{
  "authenticationControls": {
    "phishingResistance": "REQUIRED",
    "nonExportableKeys": "PREFERRED",
    "stepUpAuthentication": "UNKNOWN"
  }
}
```

`BASELINE`, `ELEVATED` and `HIGH` remain planning expectations. They neither set these
fields automatically nor correspond to NIST AAL1/2/3. Technical background:
[NIST authenticator requirements](https://pages.nist.gov/800-63-4/sp800-63b/authenticators/)
and [assurance levels](https://pages.nist.gov/800-63-4/sp800-63b/aal/).

Under `/api/v3/workspaces/{workspaceId}/assessments`, POST, GET `/{assessmentId}` and
PUT `/{assessmentId}/profile` use a complete v3 profile with the existing optimistic
lock. Reads project older profiles without modifying them. Without recorded compliance
scope or usage inputs, storage uses v3 only when
at least one control is not `UNKNOWN`, otherwise the existing lossless v1/v2 rules
apply. V3 snapshots include both residency details and controls. GET `/{assessmentId}/revisions`
returns original mixed v1/v2/v3 snapshots; old history is never rewritten.

V1/v2 current reads and writes reject recorded controls with 409 `profile-upgrade-required`.
Older history APIs reject only pages containing unsupported formats. An intentional
v3 clear can restore older current-profile compatibility, but cannot erase v3 history.
Flyway V5 widens version constraints without rewriting existing data.

GET `/{assessmentId}/eligibility-preflight` under v3 adds `authenticationControlChecks`
to the capability/context/residency checks. Catalog v4 distinguishes availability
from enforceability for each exact plan/region, human client and user population.
Requirements apply to every selected human client/population pair. Browser evidence
cannot establish native support; missing populations or facts remain unknown.
Machine-only profiles mark these human controls `NOT_APPLIED`.

`REQUIRED` passes only with reviewed, fresh evidence of both availability and the
ability to require the control in that flow. Confirmed lack of either fails. Missing,
unreviewed, future or stale evidence proves neither outcome; the inclusive 90-day
window is unchanged. `PREFERRED` is not scored, `NOT_REQUIRED` imposes no constraint,
and `UNKNOWN`/`FORBIDDEN` require clarification. Any failure dominates without hiding
other unknown checks. V1/v2 eligibility endpoints retain their previous, narrower scope.

This does not verify deployed policy, sensitive-action wiring, enrollment, recovery,
session lifecycle, combined configuration or full assurance. No AAL/certification,
winner or final recommendation is produced. Broad assurance remains deferred;
`assuranceExpectation` is context only and `recommendationReady` stays false.
Catalog v1/v2/v3 fixtures remain frozen; runtime loads only v4, not historical catalog
replay. The browser preview still uses profile v1 and has no controls UI yet.

### Explicit compliance requirements scope

Profile API v4 adds `security.complianceScopeStatus` alongside the existing
`complianceTargets` list. It records what the assessment owner has established:

- `UNKNOWN`: the requirements scope is not yet recorded. Empty and partially recorded
  target lists are both allowed; existing labels are never dropped or treated as proof.
- `NONE_IDENTIFIED`: no requirements have been identified for this assessment;
  `complianceTargets` must be empty. This is not a legal exemption or compliance finding.
- `TARGETS_IDENTIFIED`: at least one target label is recorded. Detailed obligations,
  applicable service scope and supporting evidence still need investigation.

The last two states cannot contradict the target list; inconsistent updates return
422 without changing data. Unknown or missing fields return 400. A framework label,
including `OTHER`, never verifies or rejects a provider by itself.

Use POST/GET/PUT and revision history under
`/api/v4/workspaces/{workspaceId}/assessments`, with the same complete-profile and
`expectedVersion` rules. V4 reads project every older format with `UNKNOWN` scope,
preserving even non-empty target lists. They do not infer that an empty list means
no requirements. No-op saves do not change data or history. Without usage inputs, recorded scope requires
stored format v4, including residency and authentication fields; otherwise the existing
minimal v1/v2/v3 rules apply. Flyway V6 widens constraints without rewriting old records.

V1/v2/v3 current-profile reads and writes reject recorded scope with 409
`profile-upgrade-required`; old history endpoints reject pages containing unsupported
versions. V4 history returns original mixed v1/v2/v3/v4 snapshots. An explicit reset
to `UNKNOWN` may restore older current-profile compatibility, but never erases history.

V4 `/{assessmentId}/eligibility-preflight` adds one shared `complianceScopeCheck`.
`UNKNOWN` scope requires more information. `NONE_IDENTIFIED` yields `NOT_APPLIED`,
not `PASS`. Selected targets remain unknown because target-specific evidence is not
evaluated yet. An unresolved scope check changes otherwise matching candidates to
`NEEDS_INFORMATION`; existing checked failures still take precedence. Every earlier
candidate check remains visible. Compliance verification remains deferred and
`verificationPerformed` is always false. There is no legal applicability decision,
certification, score or final recommendation.

The synthetic catalog remains v4 with unchanged facts and dates. V1/v2/v3 eligibility
keeps its earlier scope and shapes. This is a local backend step; the browser preview
remains v1 and does not yet expose these controls or compliance-scope choices.

### Usage inputs and planning assumptions

Profile API v5 adds `operations.usagePlanning` for the application being assessed,
not AuthWeave's own running costs. It records a `scopeDescription` (up to 500 characters),
up to 10 distinct, nonblank `assumptions` (up to 500 characters each), and a `volumes`
map. Describe the planning month or observation period, environments and expected
growth in the scope and assumptions; free text is stored as data, not executed.

| Metric | Meaning |
| --- | --- |
| `MONTHLY_ACTIVE_USERS` | Distinct human users authenticating in one planning month, not registered accounts or login count. |
| `ENTERPRISE_SSO_CONNECTIONS` | Configured upstream enterprise IdP connections, not organization count. |
| `MONTHLY_M2M_TOKEN_ISSUANCES` | M2M access-token issuances per planning month, not downstream API requests. |
| `PEAK_HUMAN_LOGINS_PER_SECOND` | Successful human logins during the busiest one-second interval, not monthly active users. |

Each recorded quantity has `basis: ASSUMED | OBSERVED` and an integer `value` from
0 through 9007199254740991. `OBSERVED` is an owner's assertion, not verified evidence.
An omitted metric is unknown; an explicit zero is a recorded value. No client type,
requirement or budget-sensitivity label automatically supplies zeros or a spending cap.
These definitions are planning units, not a vendor's billable-unit definitions.
Partial inputs can be saved, for example:

```json
{
  "scopeDescription": "Pilot month; one production environment",
  "assumptions": ["No machine clients in the pilot"],
  "volumes": {
    "MONTHLY_ACTIVE_USERS": { "basis": "ASSUMED", "value": 500 },
    "MONTHLY_M2M_TOKEN_ISSUANCES": { "basis": "ASSUMED", "value": 0 }
  }
}
```

Use POST/GET/PUT and revision history under `/api/v5/workspaces/{workspaceId}/assessments`
with a complete profile and `expectedVersion`. V5 reads project older profiles with
empty planning inputs without changing storage. Any recorded context, assumption or
quantity requires stored format v5, including all prior security fields. Clearing all
three restores the smallest lossless v1/v2/v3/v4 format; history is never erased.
V1 through v4 current-profile reads/writes return 409 `profile-upgrade-required` when
planning inputs are recorded. V5 history returns exact mixed v1 through v5 snapshots;
older history endpoints reject pages containing unsupported formats. Flyway V7 only
widens constraints. No-op saves, optimistic locking and atomic history remain unchanged.

GET `/{assessmentId}/usage-planning-preflight` is a separate, read-only input check.
It lists all four metrics with their units, definitions, values and basis, using
`UNKNOWN` with `input: null` for missing quantities. `INPUTS_RECORDED` requires a
nonblank scope and all four quantities; if any is `ASSUMED`, at least one assumption
is required. Otherwise `NEEDS_INFORMATION` includes exact `missingPaths`. All-observed
inputs need no invented assumptions. Recorded inputs are not necessarily correct or
sufficient for a vendor-specific estimate.

`pricingEvaluated` and `recommendationReady` are always false. No tariff lookup,
cost quote, free-tier promise, affordability check, score or provider elimination is
performed. Dated prices, paid feature gates, billable-unit mapping and infrastructure,
additional environments and operational costs still need a separate cost model.
V1/v2/v3/v4 eligibility endpoints, policies and synthetic catalog v4 remain unchanged;
there is no v5 eligibility endpoint. The browser preview remains v1 with no usage-input UI.

### Provider catalog drafts: validation before review

`POST /api/v1/catalog-drafts/validate` accepts a separate, versioned
`provider-catalog-draft.v1` document and returns a read-only validation report.
It neither saves nor activates the draft. The existing synthetic catalog and all
assessment preflights remain unchanged. No authentication or remote source fetcher
is implemented for this endpoint; it is subject to the Core API's loopback-only boundary.

Each option identifies the provider, product, plan, `MANAGED` or `SELF_HOSTED`
deployment, region and configuration variant. Each proposed capability, context,
residency or human-authentication fact requires explicit `conditions` and an evidence
object: HTTPS `sourceUrl`, `observedAt` and a bounded paraphrase (`summary`). Omitted
facts remain unknown, not unsupported. Conditions are recorded for later human review;
they are not executed or interpreted as rules. No pricing or full assurance model is added.

Malformed shapes, missing provenance, unknown fields and forged review/publication
metadata return 400. Well-formed drafts receive 200 with `VALID_DRAFT` or `INVALID_DRAFT`.
Semantic issues identify duplicate option IDs/scopes, options without facts,
unrecognized country codes, inconsistent residency coverage, and enforcement claims
without availability. Configuration variants can distinguish options with otherwise
identical product/plan/deployment/region labels; scope comparison uses exact values.

Every fact remains `UNREVIEWED`. `CURRENT`, `STALE` and `FUTURE` describe observation
times only, using the initial inclusive 90-day window. Neither a recent date nor
`SUPPORTED` nor `VALID_DRAFT` establishes truth, completeness, approval or applicability.
`sourceVerificationPerformed`, `approvalGranted`, `writesPerformed` and `evaluationReady`
are always false. URL validation checks syntax, not source authenticity or an ingestion
allowlist; URLs are never fetched. The example contains fictional claims only.

Reports include `contentSha256` with policy `catalog-draft-validation-1` and
canonicalization `catalog-draft-canonical-json-1`. The application-specific digest
sorts object keys and all v1 unordered collections (options, conditions, countries),
normalizes timestamps to instants and preserves text. It hashes the supplied data,
not the remote page, and is not a signature or publication ID. The observation clock
can change freshness without changing this digest. Version names are not reserved;
authorized approval, catalog activation and durable assessment/result pinning remain
subsequent steps. Semantic diff and local proposal history are available below.

With the local Core API already running, from the repository root:

```shell
curl --fail-with-body --silent --show-error \
  -H 'Content-Type: application/json' \
  --data-binary @packages/contracts/tests/fixtures/provider-catalog-draft.valid.json \
  http://127.0.0.1:8080/api/v1/catalog-drafts/validate
```

The fixture yields nine unreviewed facts and `VALID_DRAFT`; freshness depends on the
current time. This is a validation-only backend slice, not a real-provider baseline,
curator UI, published catalog or final recommendation. It requires no new accounts,
dependencies, migrations or paid services.

### Catalog change proposal previews

`POST /api/v1/catalog-change-proposals/preview` compares two supplied draft v1 documents
without saving them. For example, it shows a proposed SCIM assertion changing from
`OPTIONAL` to `UNAVAILABLE`, with the original and proposed evidence side by side.
The endpoint remains local-only and unauthenticated; it cannot approve or publish data.

The request includes `schemaVersion: 1`, a caller-generated UUID `proposalId`, a bounded
`rationale`, `base`, `candidate` and `expectedBaseSha256` from the base validation report.
Both documents are caller supplied: the digest checks that the supplied base matches
the intended payload, not that it is a trusted published version or current database
state. The proposal ID is a correlation value, not a reserved or authenticated identity.
`proposalSha256` binds the entire request, including both drafts, the ID and rationale,
using `catalog-draft-canonical-json-1`; it is neither a signature nor a remote-page hash.

The preview matches options by ID and facts by typed path. It preserves typed `before`
and `after` values, conditions and provenance. Changes distinguish the claim, conditions,
source URL, observation date and source paraphrase; one fact can have several changed
aspects. An added or removed fact has `null` on the absent side: omission means unknown,
not unsupported. Changing the provider, product, plan, deployment, region or configuration
sets `requiresAllFactsReview`, even if the fact values did not change. Renaming an option
is removal plus addition, not automatic identity matching. Collection ordering and
equivalent timestamps do not produce changes.

With policy `catalog-change-preview-1`, well-formed requests return:

- `BLOCKED`: an invalid draft, base-digest mismatch or changed content reusing the same
  catalog version. `diffComputed` is false; empty change lists do not mean no changes.
- `NO_CONTENT_CHANGES`: comparison succeeded with no option or fact changes. A version
  label change alone is reported separately as `catalogVersionChanged`.
- `REVIEW_REQUIRED`: `affectedOptionIds`, `optionChanges` and `factChanges` describe the
  changes for later human review. This is not approval or a verified recommendation.

Malformed inputs and forged authority fields return 400. Both draft reviews use the
same reference instant and include validation issues and freshness counts. All facts
remain `UNREVIEWED`. `proposalState: PROPOSED` is a preview label, not a stored workflow
state. `baselineVerified`, `sourceVerificationPerformed`, `approvalGranted`,
`writesPerformed`, `evaluationReady` and `impactAnalysisPerformed` are always false.
Rationale and evidence text remain inert data; sources are never fetched. There is no
approval/rejection action, initial publication, curator UI or impact run in this
endpoint. The separate conditional impact endpoint is described below. The active
synthetic catalog and existing assessments remain unchanged.

With the local Core API already running, from the repository root:

```shell
curl --fail-with-body --silent --show-error \
  -H 'Content-Type: application/json' \
  --data-binary @packages/contracts/tests/fixtures/catalog-change-preview-request.valid.json \
  http://127.0.0.1:8080/api/v1/catalog-change-proposals/preview
```

This fictional fixture yields `REVIEW_REQUIRED`, one `facts.SCIM` claim change from
`OPTIONAL` to `UNAVAILABLE`, and all six flags false. No new accounts, dependencies,
migrations or paid services are required for the preview. Local proposal history is
described below, followed by conditional rule impact. Authorized curator decisions,
complete decision coverage, activation and catalog-version pinning remain subsequent work.

### Local proposal storage and history

An explicit non-web command can now save an unreviewed proposal to PostgreSQL. Normal
Core API startup does not enable its `local-catalog-write` component; there is no HTTP
create/update/approve/reject/publish action. This development command is not authenticated
curator authorization and must not be exposed as a remote user-facing write interface.
Use only synthetic, non-sensitive input until the authentication and data-handling flow
is implemented. The active catalog and assessments are not changed.

The command accepts the same preview request as a regular local JSON file (absolute
path, at most 32 MiB). Only `REVIEW_REQUIRED` proposals are stored; malformed, blocked
and content-unchanged comparisons are rejected. Starting from the repository root with
local PostgreSQL running:

```shell
AUTHWEAVE_CATALOG_PROPOSAL_FILE="$PWD/packages/contracts/tests/fixtures/catalog-change-preview-request.valid.json" \
  make store-catalog-proposal
```

Leave `AUTHWEAVE_CATALOG_EXPECTED_VERSION` unset for creation. The first call reports
`SAVED ... version=0 state=PROPOSED`; the identical creation retry reports `UNCHANGED`.
To revise an existing proposal, provide a changed file with the same `proposalId` and
`AUTHWEAVE_CATALOG_EXPECTED_VERSION=0` (or its current version). A successful revision
increments the proposal version. This version is separate from the draft's `catalogVersion`;
it does not reserve or publish a catalog version. Missing/stale expected versions cannot
overwrite an existing proposal. Stale-version checks precede no-op detection; a matching
current version with identical canonical content preserves the original snapshot and event.

Each successful change commits the head, immutable request/preview snapshot and minimal
event together. Failure rolls back all three. Events identify `SERVICE/core-api-local-catalog`,
not a verified human, and contain IDs, versions and a digest, not rationale or source text.
Runtime permissions deny rewriting/deleting history; the web database role has no access.
This protects against runtime history mutation, not a database administrator.

With the local Core API running, GET `/api/v1/catalog-change-proposals/{proposalId}`
returns the latest stored revision; `/revisions` and `/events` accept `afterVersion`
(exclusive, non-negative safe integer) and `limit` (1–100, default 50). These local-only
reads are not workspace-authorized endpoints. They return historical snapshots without
recomputing freshness. The nested preview's `writesPerformed: false` describes that
preview operation, not whether the containing proposal was saved. State is only `PROPOSED`;
there is no review decision, verified baseline, approval or activation. This command does
not save an impact report; the separate report command is described below.

Flyway V8 adds the proposal head, revision and event tables without rewriting assessment
data. jOOQ types are generated from the migration. No dependencies, accounts or paid
services are added; the command exits without leaving a development server running.

### Conditional catalog impact

`POST /api/v1/catalog-change-proposals/impact-preview` accepts the same change-preview
request and asks: if these assertions are confirmed and their conditions apply, which
selected rules would produce a different result? It runs 24 source-controlled probes
covering capability requirements, selected compatibility contexts, storage allowlists
and partner-browser authentication controls. These are focused rule probes, not complete
application profiles, all golden scenarios or an approval gate.

The report contains `caseDefinitions`, affected `cases` with typed before/after outcomes,
and `uncoveredChanges` for changed fact paths without a probe. An option-scope change
rechecks every probe and also lists unprobed facts in that scope. A provenance or condition
change affects a probe even when its conditional outcome stays the same.
`conditionalResultChanged` compares the outcome and reason, not freshness or conditions.
`coverageComplete` is always false; an empty changed-case list does not authorize publication.

The explicit analysis basis is `ASSUMED_TRUE_CLAIMS_AND_APPLICABLE_CONDITIONS`.
Outcomes are `WOULD_SATISFY`, `WOULD_VIOLATE`, `INDETERMINATE` or `NOT_APPLIED`, never
real eligibility decisions. Missing facts remain unknown, preferences are not scored,
and freshness is reported separately. The shared claim predicates also support the
existing evaluators, whose evidence and scope checks remain in place. This hypothetical
analysis neither verifies conditions nor makes unreviewed, stale or future evidence usable
by the active evaluator. No source fetches, real provider baselines or recommendations
are produced.

With the local Core API running, from the repository root:

```shell
curl --fail-with-body --silent --show-error \
  -H 'Content-Type: application/json' \
  --data-binary @packages/contracts/tests/fixtures/catalog-change-preview-request.valid.json \
  http://127.0.0.1:8080/api/v1/catalog-change-proposals/impact-preview
```

The fictional SCIM correction affects five probes; only `required-scim` changes its
conditional result: `WOULD_SATISFY` to `WOULD_VIOLATE`. Optional availability and absence
are both avoidable for a forbidden requirement; preferred/not-required/unknown requirements
also retain their respective outcomes. `ANALYZED` means the conditional comparison ran,
not that the candidate is correct. Invalid comparisons return `BLOCKED` without a run.

For a previously stored example, explicitly select revision 0:

```shell
curl --fail-with-body --silent --show-error \
  http://127.0.0.1:8080/api/v1/catalog-change-proposals/33333333-3333-4333-8333-333333333333/revisions/0/impact-preview
```

This GET verifies the stored request digest and reports `storedProposalVersion` and
`storedRequestDigestVerified: true`; it does not verify the supplied baseline or identity
of a curator. POST has no storage binding. The report binds its input digest, current
server time, policy `catalog-impact-preview-1`, claim rules `assertion-claim-rules-1`,
and case set `catalog-impact-probes-1` with a canonical digest of its definitions.
Reanalysis uses that exact stored input with current rules/time, without rewriting the
old preview, advancing the proposal version or recording an event. Missing revisions
return 404; incompatible stored input/digest returns 409 `catalog-proposal-replay-unavailable`
while historical reads remain available.

`impactAnalysisPerformed` and `hypotheticalEvaluationPerformed` describe this run;
the nested `changePreview.impactAnalysisPerformed` remains false because that sub-operation
only computes a diff. Baseline/source verification, approval, writes, evaluation readiness,
recommendation readiness and complete coverage remain false. Both endpoints are local-only
and unauthenticated. No migration, dependency, account or paid service is added.

### Full-profile scenario impact

`POST /api/v1/catalog-change-proposals/scenario-impact-preview` accepts the same proposal
request and compares the checked outcome for each of three frozen synthetic profiles:
`b2b-saas`, `public-sector-portal` and `internal-workforce`. These are complete v5 profile
inputs based on the existing seeds, not saved user assessments. Newer residency, human-control,
compliance-scope and usage fields remain explicitly unrecorded; no requirements are inferred
from an assurance label or population. The original seeds and the 24-probe endpoint are unchanged.

The scenario plan derives applicable checks from the existing capability, topology, residency,
human-authentication and compliance-scope preflights using an empty option with no facts.
Only then are draft assertions examined with the shared conditional claim rules. This does
not promote drafts into reviewed synthetic evidence or bypass the real evaluator's evidence
gates. Tests compare the conditional outcomes with production preflights on current, reviewed,
fictional evidence, including unknown scopes, prohibitions and mixed/machine-only clients.

For every changed option, `scenarios` contains all three comparisons, including unchanged
results. Each side includes every scoped check and a conditional status. One known conditional
violation takes precedence over unknowns; otherwise missing information prevents a checked
match. Even `WOULD_SATISFY_CHECKED_REQUIREMENTS` is conditional and scoped, not a recommendation.
An added/removed option is explicitly `OPTION_ABSENT` on the missing side.

`usesFact` identifies checks that actually consume a catalog assertion. `factPresent` and
freshness describe the fact at that address even if the requirement does not consume it.
`affectedFactPaths` lists changed consumed dependencies; `changedCheckIds` compares outcomes
and reasons, while `conditionalStatusChanged` compares the aggregate checked status.
Scope changes are always flagged. Changed provenance/conditions still appear in `changePreview`
even if no status changes. `uncoveredChanges` lists paths with no consumed scenario dependency.
These three profiles currently consume 24 of the 68 possible fact paths; they are not an
exhaustive catalog regression suite. The separate 24 rule probes also exercise requirements
not selected in these frozen profiles.

With the local Core API running, from the repository root:

```shell
curl --fail-with-body --silent --show-error \
  -H 'Content-Type: application/json' \
  --data-binary @packages/contracts/tests/fixtures/catalog-change-preview-request.valid.json \
  http://127.0.0.1:8080/api/v1/catalog-change-proposals/scenario-impact-preview
```

The fictional SCIM correction changes B2B from `INDETERMINATE` to
`WOULD_VIOLATE_CHECKED_REQUIREMENTS`. Public-sector and workforce remain `INDETERMINATE`:
SCIM is respectively not required and unknown. None was previously a verified match.
The profiles produce 23, 19 and 24 scoped checks per option, including compliance uncertainty.

For the previously stored example, GET
`/api/v1/catalog-change-proposals/33333333-3333-4333-8333-333333333333/revisions/0/scenario-impact-preview`
uses exact revision 0 and verifies its request digest. Missing/incompatible revisions retain
the 404/409 behavior described above. The report identifies policy `catalog-scenario-impact-1`,
profile policy `eligibility-preflight-4`, shared claim rules and `catalog-profile-scenarios-1`
with a canonical digest of all profile definitions. Reanalysis uses current rules/time;
the proposal, historical preview and events are not changed. These preview endpoints do
not save their reports; explicit report storage is described below.

Full profiles do not imply full decision coverage. `deferredPaths` includes browser-token
exposure, auditability, assurance, compliance obligations and operations/cost. Human-control
availability does not verify configured flows; SCIM availability does not verify lifecycle
execution, so these broader areas remain deferred too. Coverage, baseline/source verification,
approval, writes, evaluation and recommendation readiness remain false. No source fetches,
scoring, AI calls, installations, migrations or paid services are introduced. These endpoints
remain local-only and unauthenticated; authorized curator decisions and activation are separate work.

### Stored scenario impact reports

An explicit local command can run the three-profile analysis for an exact proposal revision
and store its result for later review. It requires a caller-selected report UUID, proposal UUID
and revision; it never silently selects the latest revision or accepts a caller-supplied report.
With local PostgreSQL running and the fictional proposal above already stored, run from the
repository root:

```shell
AUTHWEAVE_CATALOG_IMPACT_REPORT_ID=44444444-4444-4444-8444-444444444444 \
AUTHWEAVE_CATALOG_PROPOSAL_ID=33333333-3333-4333-8333-333333333333 \
AUTHWEAVE_CATALOG_PROPOSAL_VERSION=0 \
  make store-catalog-impact
```

The first call reports `SAVED`; the identical retry reports `UNCHANGED`, returning the original
result without rerunning analysis or adding an event. To request a new analysis with current
rules/time, use a new report UUID. Reusing an existing UUID for another proposal or revision
is rejected. Missing or incompatible input cannot create a report. A stored `BLOCKED` result,
if analysis is blocked, explicitly records that no hypothetical evaluation was performed.

Each snapshot contains `reportSchemaVersion`, `canonicalizationVersion`, `proposalSha256`,
`reportSha256`, `recordedAt` and the original report JSON. The nested report retains its
`evaluatedAt`, rule/policy versions, case-set version/digest, full scenario definitions,
outcomes and coverage limitations. Historical reads do not replay current rules, refresh
evidence or reinterpret the report as the current domain model. The report digest uses the
documented catalog canonicalization, not raw response bytes; it is not a signature or proof
that source claims are true. The nested `report.writesPerformed: false` describes the pure
analysis operation, not whether its containing snapshot was saved.

With Core API running, these local-only GET routes use the prefix
`/api/v1/catalog-change-proposals/{proposalId}/revisions/{version}/impact-reports`:

- The prefix itself lists reports for that exact revision. `limit` is 1–100 (default 50);
  `afterReportNumber` is an optional exclusive non-negative safe-integer cursor.
  Continue with `nextAfterReportNumber` until it is null. Report numbers can have gaps;
  they are ordering keys, not counts. Writes for the same proposal are serialized before
  allocating numbers so an in-flight lower-numbered report cannot be skipped.
- `/{reportId}` reads one immutable snapshot. A report under a different proposal or
  revision returns 404 `catalog-impact-report-not-found`.
- `/{reportId}/event` reads the single recording event: `catalog-impact.recorded`, IDs,
  revision, digest, timestamp and `SERVICE/core-api-local-catalog`, without raw report text.

Flyway V9 adds report/event tables and binds each report to its exact proposal revision
and digest. Report and event commit together; the database rejects a report without its
matching event. Core runtime cannot update/delete/truncate either table; the web role has
no access. This is runtime history protection, not protection from a database administrator.
The proposal head/version, its old snapshots/events, active catalog and assessments stay unchanged.

The command runs under `local-catalog-impact-write`, exits without a server and is not enabled
by normal Core API startup. HTTP has no report writes, curator authentication, approval or
activation. Use only synthetic, non-sensitive data; these reads are not workspace-authorized.
Recording a service event does not identify a human reviewer or make the report an approval
gate. Coverage, baseline/source verification, approval and readiness remain false. The
separate 24-rule-probe preview is still stateless. No new dependencies, accounts or paid
services are required.

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
or number/boolean-to-text conversions and fractional versions are rejected. Invalid requests return
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

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

## Phase 3 decision acceptance target

The [reserved result schema](packages/contracts/schemas/decision-result.v1.schema.json),
[decision policy](packages/contracts/decision-core/policy.v1.json) and
[18 acceptance cases](packages/contracts/decision-core/cases.v1.json) specify the next
deterministic result. This is a tested contract target, **not an implemented endpoint,
verified vendor catalog, publisher or finished recommendation engine**.

All 34 existing profile inputs have explicit routes. Numeric scoring covers only the
nine explicitly preferred capabilities: weights total 100 and have no hidden defaults.
Unknown required evidence blocks eligibility; confirmed hard failure takes precedence.
Unknown preference evidence exposes a lower/upper score bound and its missing-information
weight, and withholds ranking until all eligible options have complete preference evidence.
Equal scores share a rank; no-preference cases retain an unranked eligible set.
Other operational, cost, assurance and compliance inputs remain explicit limitations,
not invented scores or proof of deployment/certification.

The cases are six normal, six missing/contradictory and six adversarial/failure targets
across B2B, public-sector and workforce profiles. Tests validate frozen expected envelopes,
input coverage, arithmetic, source gating and exact profile/catalog/policy/weights/time
bindings. They do not run a real-provider decision engine. The current runtime continues
to use the unchanged synthetic preflights and closed publication policy.

### Pinned candidate assembly

`make prepare-decision-candidate` emits a reproducible, unreviewed draft payload to
stdout from the [pinned selection](packages/contracts/decision-core/catalog-selection.v1.json).
It selects eight separate options across the five provider families; WorkOS AuthKit,
Connect and Directory Sync stay separate, as does Entra's M2M Premium add-on.
Original scopes, conditions and observation dates are preserved. Any selected source
content change requires explicitly updating its pin; other drafts cannot donate facts.

The output includes a pending source-review task for every recorded claim, with no
verdict, human confirmation or authority grant. Missing context, residency and
auditability facts remain unknown. These initial options do **not** yet establish a
positive end-to-end acceptance case or a reviewed catalog. The command fetches no
sources, writes no files and does not change the runtime, accounts or publication gate.

Two digests are deliberately separate: `bootstrapCandidateSha256` replays the existing
Core source-review canonicalization (unordered arrays); `decisionCatalogSha256`
preserves array order for future decision bindings. Core tests parse the actual generated
payload, reproduce the bootstrap digest and verify complete pending review coverage.

The [additional browser/SCIM case](packages/contracts/decision-core/catalog-case.b2b-browser-scim.v1.json)
uses a single-customer application instance, one dedicated Keycloak realm and a
confidential browser client. SCIM remains required; SAML has an explicit preference
weight. Five dated context proposals now accompany that exact option. Single-organization
mapping is an application-owned design assumption, **not native Organizations support**.
The assembly checks the case's exact claim dependencies; it does not evaluate or rank it.
Source review and the runtime engine are still required before the expected conditional
eligibility can be established. The primary multi-tenant B2B profile and the 18 frozen
synthetic cases are unchanged. Multi-client federation, required residency, auditability,
strong controls and application-side lifecycle are not cleared by this narrower case.

## Local development

Required tools are Java 21, Node.js 24 with npm 11, Python 3.13, Docker Desktop and
GNU Make. From the repository root, install project-local dependencies and create the
ignored local environment file:

```shell
make setup
```

The setup command generates local database passwords only when `infra/.env` does not
already exist. It never overwrites an existing environment file.

Install the pinned Chromium test browser, start PostgreSQL and run all local checks.
Stop local development and identity servers first: the isolated browser check needs
ports 3000, 8080 and 8081 free, and refuses to replace existing services.

```shell
make setup-browser
make infra-up
make check
```

Run `make help` to see component-specific checks and development-server commands.

CI audits both npm workspaces, including development dependencies, and fails on
known moderate-or-higher vulnerabilities. Run `npm audit --audit-level=moderate`
inside `apps/web` or `packages/contracts` for the same check locally. A clean audit
only reflects the registry's currently reported advisories; it is not proof that
the application is secure.

The Web runtime and Next.js ESLint configuration are pinned together to 16.3.8,
a [security patch release](https://github.com/vercel/next.js/releases/tag/v16.3.8).
The plugin's development-only `fast-glob` dependency is scoped to a local
directory-search adapter using `tinyglobby` 0.2.17. This removes the unpatched
[`braces` dependency](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm), rather than
ignoring its advisory or downgrading Next.js. The adapter supports only the
plugin's root-directory discovery call, not the full `fast-glob` API; regression
tests exercise the actual Next.js resolver and lint rule. Review this override
when upgrading the plugin, and remove it once upstream no longer needs the
vulnerable chain. Reinstall with `npm ci` in `apps/web` after pulling this change.

### Optional local identity lab

Local ZITADEL infrastructure, an AuthWeave OIDC project/application and two ordinary synthetic
users support the local BFF sign-in flow. First login provisions a personal workspace;
versioned workspace routes require the BFF's server-only credential and an OIDC identity
that owns that workspace. Signed-in users can create a private assessment draft, edit
selected fields through the BFF, and browse a bounded list of their assessments.
Catalog-curator permissions are not enabled yet. The public browser-only preview is
unchanged. Ordinary local B2B, citizen-portal and workforce paths through the saved
editors, Review, fictional Comparison, Architecture and downloaded saved briefs have
been manually checked with synthetic inputs supported by the current editors.
All 32 exported rows per scenario matched Review. Temporary BFF and workload
conditions stayed separate from saved requirements and reset on step navigation.
B2B also covered a stale-tab conflict, sign-out and a second user's denied read.
This is not automated browser coverage, a hosted security assessment, complete
golden-profile evaluation, a final architecture recommendation or ADR export.

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
`auth-register` idempotently creates the `AuthWeave Local` project, its closed
`catalog_curator` project-role definition, a Web OIDC application with Authorization Code,
PKCE-compatible public-client settings and exact localhost callbacks, plus ordinary
`alice@authweave.localhost` and `bob@authweave.localhost` users. It does not grant the role to
any user. Role existence alone does not authorize a curator rejection.
The command generates distinct user passwords in ignored `infra/zitadel/synthetic-users.env.local`
and writes the issuer, non-secret client ID, project ID and organization ID to ignored
`apps/web/.env.local`; both files have mode 600. It never prints credentials or tokens.
`auth-registration-check` verifies the existing registration, role definition and both
password factors without creating persistent resources or files.
The check does not audit out-of-band role grants. Both commands create
short-lived verification sessions and delete them.

With the project and organization IDs configured, the BFF requests a project-scoped role
claim through ZITADEL UserInfo at sign-in. Only the exact `catalog_curator` assignment for
both IDs is recorded in the server-side session; a failed lookup grants no curator access.
Subject-validated UserInfo also supplies display name and verified email when those
claims are omitted from the ID Token. Existing ID Token claims take precedence;
email and its verification flag are never mixed between sources. These are profile
metadata only: workspace identity and reauthentication remain issuer/subject-bound.
The sensitive-action policy requires authentication within the preceding 15 minutes.
The account page offers a same-account reauthentication action using `prompt=login` and
`max_age=0`. Its one-use transaction is bound to the existing session; the callback
checks a recent `auth_time`, rejects a different issuer or subject, and rotates the
session ID only after success. The explicit local browser check below verifies ordinary
Alice/Bob reauthentication and curator denial. Owner hands-on validation and a positive
role-assignment case remain separate, unverified checkpoints.
Do not treat role storage as catalog authorization or publication readiness.

Core now reserves a separate curator boundary for future proposal decisions and exposes
`GET /internal/v1/catalog-curator/authorization` as a read-only, server-to-server probe.
It requires the BFF service credential, an asserted OIDC issuer/subject, the exact
`catalog_curator` role and configured project/organization IDs, plus an authentication
time no older than 15 minutes (with 30 seconds of clock skew). Both Core IDs must be
configured as `AUTHWEAVE_OIDC_PROJECT_ID` and `AUTHWEAVE_OIDC_ORG_ID`; otherwise the
probe fails closed. Core trusts only the credentialed BFF assertion here; it does not
query ZITADEL or grant a role itself. The server-rendered `/account` page now checks
the probe only when its database-backed session matches the configured OIDC issuer,
contains the exact project/organization grant and has a fresh authentication time;
absent, mismatched and stale grants make
no Core request. `make dev-core` loads the non-secret scope IDs from the ignored
`apps/web/.env.local` when present. The page shows a diagnostic status, not a catalog
action. Neither a positive assigned-role browser case nor the step-up browser flow has
been manually verified. A protected rejection endpoint exists; positive synthetic
tests do not prove a real curator grant, approval or publication.

Open `http://localhost:8081/ui/console` (use `localhost`, not `127.0.0.1`). Sign in as
`admin@authweave.localhost` using `AUTHWEAVE_ZITADEL_ADMIN_PASSWORD` from the ignored file,
opened privately in your editor. This synthetic account administers only the local IdP;
it is not an AuthWeave curator. Test an incorrect password once, then the correct password.
Neither ordinary synthetic user is an IdP administrator or an AuthWeave curator. No cloud
account, SMTP service or paid subscription is needed for this lab.

The BFF uses Authorization Code with PKCE, a one-use browser-bound login transaction and an
opaque, HttpOnly application cookie. Session state is held in the isolated `web` PostgreSQL
schema, with a 30-minute idle and eight-hour absolute limit. Provider tokens are not sent to
the browser or retained in the application database. After validating an OIDC identity, the
BFF calls a credential-protected Core API endpoint that idempotently binds its issuer and
subject to a personal workspace record; the workspace ID is stored in the server-side session.

For an existing local installation, with Docker Desktop running, use `make infra-up`,
`make auth-up` and `make setup-core-service-token`. The last command adds one random credential
to the ignored mode-600 `infra/.env` and leaves an existing credential unchanged. Start
`make dev-core` in one terminal so Flyway applies the Core ownership migration. In another
terminal, run `make migrate-web-auth` to apply the replay-safe web migrations, then
`make dev-web`. Open `http://localhost:3000/account` to try sign-in, same-account
reauthentication and sign-out with a synthetic user. Once signed in, use
**Create assessment draft** to open a private
version-6 assessment page with selected draft editors. **View your assessments**
lists up to 20 recent summaries per page, with an Older link for earlier drafts. The ignored
`apps/web/.env.local` must already contain the issuer, client ID, project ID and organization ID
created by `make auth-register`. `make check-web-auth-db` tests state replay, expiry, session
rotation/revocation and database role isolation. It also exercises sequential
context/capability/audit/usage saves for B2B, citizen and workforce scenarios through
the actual BFF handlers, with a live session database and a stateful Core test double:
section preservation, saved Review/Comparison input projections, explicit zero
versus unknown, stale forms
and a conditional-write race. That regression does not run ZITADEL, the browser or
the real Core evaluation engine.

The internal provisioning route and every `/api/vN/workspaces/...` route require the
server-only bearer credential. Versioned workspace routes additionally require
`X-AuthWeave-Oidc-Issuer` and `X-AuthWeave-Oidc-Subject` headers from the validated
BFF session; Core checks their immutable mapping against the path workspace ID.
The browser must never supply these credentials or principal headers directly.
The BFF creates, lists, reads and edits selected draft fields using only the workspace
from its server-side session. Writes require an exact same-origin request and optimistic
version check; anonymous sessions are denied. Catalog routes remain unauthenticated and
loopback-only. Curator authorization verification, automated authenticated browser
coverage and complete golden-profile architecture/export walkthroughs are still pending.
Do not expose the local HTTP lab or Core API.

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

Versioned assessment routes require the local BFF service credential and a mapped
OIDC issuer/subject; a workspace ID alone does not authenticate a caller. Catalog
routes remain unauthenticated. Core binds to `127.0.0.1` by default and refuses to
start with a non-loopback `server.address`. Keep it local; do not expose it through
a reverse proxy or tunnel. This server-to-server boundary is not a complete hosted
authentication and authorization design.

The web application includes a browser-only requirements preview at `/preview`.
Start with a fictional B2B SaaS profile or a blank draft, edit six sections, review
open questions and download a JSON profile or Markdown requirements brief. Answers
stay in memory in the current workspace; they are not sent to the API or saved for
later. Download before leaving or refreshing the page.

The preview checks the shared JSON Schema only. It does not check cross-field domain
contradictions, verify compliance or compute provider recommendations. Empty
selections mean no choice was recorded, not that a topic is unnecessary.

Separately, the protected local assessment workspace at `/assessments/{id}` groups
the existing editors and previews into seven steps: Context, Requirements, Audit,
Usage, Review, Comparison and Architecture. Only one section is shown at a time, with
responsive navigation and keyboard focus on the selected heading. Draft inputs
require an explicit Save; switching steps with unsaved edits offers stay/discard,
and leaving through the workspace's back button uses the same in-page dialog.
Stay or Escape preserves the inputs and returns focus to the navigation button;
only **Discard and leave** returns to the list without saving. Navigation and exit
are blocked during a section save. Browser reload/close keeps its native warning.
Successful saves and known validation/version conflicts return to the relevant
step. The step URL remembers location, not unsaved answers or completion.

After the live browser session and canonical saved profile are resolved, six
independent preview reads start together: comparison evidence, architecture,
usage, operations, auditability and assurance/compliance. Every read keeps its
existing saved-version/input binding and response guard. Unreadable input sections
skip only their dependent reads; each rejection uses its own fixed unavailable
state without hiding other previews or the saved profile. Saved context, editors
and the Review overview render without waiting for these reads. Each preview has
its own loading state for the saved version and streams in independently; a slow
preview does not hold back the other sections. Session and canonical-profile gates
remain before this protected frame. Existing per-read deadlines are unchanged;
no retry, cache, default result or write is added.

Requirements explains all nine capabilities next to their saved selections, with
expandable use cases, limits and questions to discuss with your team. A separate
guide distinguishes all five requirement levels, including not-required versus
forbidden and hard constraints versus preferences. Native disclosures do not
change answers or save the draft. Concept references link to standards or official
documentation; they are not evidence of a provider's support, plan or pricing.
The existing explicit-save and version-conflict behavior is unchanged.

Context groups application/audience, at-rest storage, authentication controls,
assurance expectation and compliance scope separately. Five security fields have expandable examples, limits
and team questions. Security-level help reflects the existing partial policies:
forbidden needs clarification here, rather than implying weaker authentication or
a country denylist. Compliance help distinguishes the three scope states and six
target labels, with official concept references. These labels are not a legal
applicability decision, certification or proof of provider compliance. No answers,
controls or evidence are inferred, and the same scoped Save form is preserved.

The existing saved `security.assurance` is editable in Context as a planning label:
Baseline, Elevated, High or Unknown / not recorded. Expandable help explains these
internal labels; they are not AAL, IAL or FAL levels, a security verdict or evidence
of provider support. Changing a label does not set MFA, independent controls or
compliance targets. Only explicit Save records it, using the same session/workspace
and optimistic-version guards. The Core acknowledgement must match the requested
or preserved label before success is shown. Older forms that omit the field preserve
its saved value; missing legacy values are not replaced with a guessed default.
Review and the saved Markdown requirements brief show the same checked planning
label; Unknown stays a recorded input gap, not a security exemption.

Audit separates provider-side event scope from export and retention requirements.
Each of its six criteria has expandable examples, limits and team questions; the
shared-level help describes the separate partial auditability policy. Retention
help distinguishes the requested provider minimum from a configurable maximum,
source age and an external sink's duration. Illustrative numbers do not fill the
input. Concept references are not a compliance baseline or provider evidence.
The existing six selections, conditional duration, explicit Save and version
checks remain unchanged; opening help neither selects criteria nor saves a draft.

Usage shows the four project planning units beside uniquely labelled inputs, with
examples, limits and team questions for each metric. Scope and basis help separate
monthly volumes, configured inventory and a one-second peak, as well as Unknown,
Assumed and owner-stated Observed values. Unknown requires a blank value; an
explicit zero stays zero. Assumption examples do not fill fields or prevent partial
saves. These definitions are not provider billing units, verified measurements,
capacity results or a cost estimate. The existing four pairs, ten assumption fields,
explicit Save and saved-version input check remain unchanged.

Usage also checks the existing form rules before submitting the same form payload.
An Unknown/value mismatch, a missing Assumed/Observed number or an invalid assumption
stays on the same page with fixed feedback and links to the affected fields. Nothing
is sent or automatically corrected; edits and the unsaved-navigation guard remain.
The small client form wrapper receives server-rendered controls and keeps the same
POST route and payload. The server still validates every submission independently;
client feedback is not a security check, a successful-save receipt or input readiness.

Context and Audit use the same local error summary with links to the existing fields
when their existing parsers refuse a payload. Country-list syntax, repeated codes and
length/count limits point to the country input. Missing or noncanonical retention
points to its duration input; an orphan duration points to the Log retention checkbox
instead of a disabled field. Messages
never echo or correct the supplied values. Existing parsers decide whether the form
can be sent; country membership and domain policy are still checked by Core. Native
Context format/length and Audit number/range/required checks remain enabled. Empty
selections and explicit clear do not acquire new requirements. Following a field link or correcting an input
does not save or release the unsaved-navigation guard.

Within the guided workspace, Context, Requirements, Audit and Usage share one form
save boundary and opt into a small same-origin JSON acknowledgement on their existing
POST routes. The response binds only the assessment ID, submitted
version and outcome; ownership, origin, form and optimistic-write checks still run
on the server. A version conflict keeps the form and edits in the current tab,
without merging, overwriting or automatically retrying. Loading the current saved
version requires explicit confirmation that local edits will be discarded. Until
then, summaries belong to the version originally loaded, not the current server
version. Pending requests freeze controls and step navigation. Lost, timed-out or
malformed replies are uncertain: the write may have committed, so the user must
inspect the current saved version before resubmitting. Only a checked save
acknowledgement automatically reloads saved inputs. Native submissions without the
JSON opt-in keep their existing redirects. Existing section payloads, partial
selection rules and Audit retention/explicit-clear behavior are unchanged. Form
feedback is scoped to its assessment, section and loaded version; it does not carry
over to another editor after navigation. The unsaved-navigation dialog asks the user
to review the section's save feedback, not to retry a refused write.

Before acknowledging any of the five profile forms, BFF checks the complete Core
write response against the submitted profile, including preserved sections. Object
key order and the six domain-set orders may differ; values, membership and ordered
usage assumptions may not. Missing, added or altered profile values cannot report
success. A same-version response is accepted only for an unchanged profile; the
next version remains valid. Successful replies require JSON and at most 64 KiB of
actual UTF-8 bytes; the existing Core write deadline also covers the body read.
An invalid or unreadable acknowledgement returns a fixed unavailable response and
the enhanced form keeps its edits as uncertain. The Core write may already have
committed: there is no automatic rollback, retry or merge. Native submissions do
not receive a success redirect for a rejected acknowledgement either.

The protected `/assessments` list identifies saved drafts by application type, users
and clients, with status, saved version, UTC timestamps and a secondary ID. Unknown
and empty selections stay unrecorded; Other needs definition. An unreadable stored
profile keeps its metadata with an unavailable-context notice. Cards open the same
personal assessment; bounded older-page navigation can return to the latest page.
The additive Core `/api/v6/workspaces/{workspaceId}/assessments/context-index` route
projects only these three context fields from the same stored row as its metadata,
without per-item API reads or full profiles in the list response. The existing
metadata-only list contract is unchanged. BFF validates the bounded response and
derives workspace ownership from its server session. Listing does not change
assessment versions, timestamps or history, and these labels do not assert
completeness, applicability or recommendation readiness.

A saved-context summary above every step identifies the recorded application type,
user populations and client types. It uses the same checked context projection as
the editor, not unsaved edits or an inferred scenario. Unknown and empty fields stay
unrecorded; Other still needs definition. An unreadable context shows a bounded
notice without hiding the remaining assessment sections. This summary is not a
validation, applicability or readiness result.

Review shows six read-only cards from the existing saved editor projections:
application/audience, security/compliance scope, identity capabilities, provider
auditability, usage assumptions and operational preferences. Unknown or empty inputs remain visibly
unrecorded; "Other" needs definition, explicit not-required stays distinct, and
observed zero usage is not substituted for missing usage. Cards offer scoped
navigation back to the editors without starting a write. Unreadable sections show
a bounded unavailable notice instead of inferred answers. These display labels are
not Core validation errors, applicability rules or a completeness/readiness score;
other profile fields remain available in the saved JSON.

The operational card shows the four saved choices already editable in Usage:
identity hosting preference, application deployment target, team identity expertise
and budget sensitivity. Unknown and Undecided remain unrecorded; explicit No hosting
preference is an answer, not a missing value. Its projection is independent of usage
quantities, so an unreadable quantity cannot hide readable preferences and vice versa.
These are owner-stated planning inputs, not verified capacity, an IdP location,
provider eligibility, a spending cap or a claim that a service is free. Editing
returns to Usage through the existing guarded step navigation; read-only assessments
do not show an edit action. All previous saved row IDs remain unchanged.

Review groups its existing "Not recorded" and "Needs definition" display labels
into a compact saved-input discussion list. Native links focus the exact saved
row; they do not open an editor, choose an answer or save anything. Unreadable
sections are listed separately without an inferred field count. Explicit
not-required/forbidden answers and observed zero quantities stay out of this list.
Counts describe displayed fields only; some inputs may not apply, and no missing
label is a new validation error or a completeness/readiness conclusion. Full cards,
editor navigation and independent Core checks remain unchanged.

Review also offers **Download saved brief (.md)**: a deterministic, version-bound
Markdown export of those six saved input sections and their display gaps, labelled
`authweave-saved-requirements-brief-v2` for the expanded scope. The
same-origin, session-scoped BFF re-reads the assessment and rejects a stale page
version before downloading; it does not write the assessment or store an export.
Free text remains literal, output is bounded and responses are not cached. This
download blocks duplicate requests while pending and is cancelled when Review is
unmounted; a late response cannot trigger a download after leaving that step. This
is not a full-profile backup, evaluation snapshot, final ADR or provider
recommendation; temporary what-if answers and comparison results are excluded.
Review the file for private details before sharing it.

Comparison presents Core's three partial statuses, their option counts, exclusion
reasons, information gaps and capability preferences separately. Known checked
paths show the related saved answers and offer navigation to the existing editor;
read-only assessments return to Review instead. Unknown paths never guess an editor,
and unreadable inputs never invent a value. Catalog evidence gaps are not proof of
incompatibility and cannot be resolved by merely weakening a requirement. Core's
explanations, reason codes, scope paths, catalog version and evaluation time remain
available; technical identifiers are in expandable details. Deferred topics stay
visible. Options retain Core's order, not a ranking, and a partial pass is not a
real-provider recommendation or a complete suitability check.

A clarification summary groups Core's returned unknowns across options, separating
saved-requirement questions, catalog-evidence gaps and remaining check boundaries.
Hard-check gaps and unknown preferences are never merged. Every scoped explanation
is retained, including multiple criteria sharing a reason; counts are affected
options, not severity or completeness. Excluded options keep their gaps without
being revived. Exact option links and allowlisted editor navigation support manual
review; unknown or unreadable paths never guess an editor. This is display-only,
not a new question generator, policy, penalty, recommendation or saved task list.

Each option also has collapsed fictional evidence details: recorded claims, source
references, observation dates and explicit missing/unreviewed/future/stale states.
Identity capabilities, compatibility, storage destinations, human authentication
controls and IdP auditability stay separate. The exact plan/region and audit
configuration are shown; listed facts are not necessarily applied checks. Missing
evidence is not proof of incompatibility, and a fresh REVIEWED fixture label is not
real source or deployed-behavior verification. All `.invalid` references are plain
text, never fetched. Expanding evidence does not change the verdict or rank options.

The same guarded evidence can be compared side by side for up to three options.
Choose columns (including excluded or unresolved options) and switch between the
five evidence groups. The initial columns are the first three in Core order for
readability, not quality. These choices change only the table; nothing is saved,
scored or re-evaluated. The initial capability table works without JavaScript;
changing its display requires JavaScript. Narrow screens use a contained,
keyboard-focusable horizontal scroll region.

This workspace requires the local BFF session, Core API and PostgreSQL. It does not
add autosave, expand editable profile fields, change ownership/version checks or
turn synthetic comparisons into real-provider recommendations. Temporary what-if
inputs reset when switching steps. The public landing page and `/preview` are
unchanged; pushing this code does not deploy the protected workspace.

The Core API stores immutable assessment revisions and atomic state-change events,
with workspace-scoped paginated history reads. Runtime database roles cannot update
or delete history. Versioned workspace routes now enforce a local BFF credential and
personal-workspace ownership; the BFF exposes authenticated draft creation,
bounded summary listing and selected draft editors through an optimistic Core write.
The Core API also offers read-only capability and context preflights against explicitly
fictional plans. Full provider evaluation, ADR export and curator workflows are still
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

The historical B2B fixture path is:

```text
GET /api/v1/workspaces/60000000-0000-4000-8000-000000000001/assessments/60000000-0000-4000-8000-000000000101/capability-preflight
```

This seeded workspace has no personal OIDC owner and is not directly readable over
HTTP after the workspace authorization boundary. It remains available for local
contract tests; an authorized fixture-viewing workflow is pending.

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

```text
GET /api/v1/workspaces/60000000-0000-4000-8000-000000000001/assessments/60000000-0000-4000-8000-000000000101/eligibility-preflight
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

The personal workspace's Architecture step separates saved-input results from
temporary design-condition previews. It shows the two recorded inputs, a Context
navigation button, readable token locations and per-criterion outcomes; Not applied
is explicitly not a pass. All five alternatives remain in the policy's display order,
not a ranking. Core explanations, pros/trade-offs, prerequisites and protocol links
are preserved; technical paths/reasons remain available in expandable details.

A read-only overview compares all five patterns' client scopes, OAuth token
locations, saved-input results and the two exact checked boundaries in Core order.
Empty client input stays unknown; Not applied is never a pass. Token location is a
pattern property, not evidence of deployed storage or the absence of browser
credentials. Mixed applications may use several patterns. The responsive table
works without JavaScript and links to each detailed card, retaining pros, trade-offs
and temporary what-if forms. It does not select, rank, save or verify a design.

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

### Temporary architecture prerequisite preview

Each pattern card on a personal assessment offers a prerequisite what-if form.
Choose `SATISFIED`, `NOT_SATISFIED` or `UNKNOWN` for conditions in your proposed design;
every control defaults to unknown. Answers and results are temporary, never saved.
The form does not read or change IdP settings, tokens or credentials.
Unknown or unselected client scope has an explicit notice before declarations are
entered. Changing a declaration clears the current result and requires another
preview; leaving the step or reloading discards these temporary inputs and results.

Each form has one in-flight preview, an explicit Cancel preview action and a
ten-second request deadline. Canceling keeps the entered declarations and unlocks
that form for a manual retry. Changing a declaration or leaving Architecture cancels
pending work; late replies cannot restore an obsolete result or unlock a newer
request. Session, stale-version and other failures use fixed feedback, never raw
network, parser or response text. Replies still require the loaded assessment version
and exact pattern, client scope and declaration/result checks. This UI recovery does
not verify a design, save declarations or change the independent preflight verdict.

Successful replies in both Architecture forms are decoded as fatal UTF-8 with a
32 KiB actual-byte limit before JSON parsing, including when `Content-Length` is
missing or understated. Cancel and the existing ten-second deadline interrupt a
pending body read; rejected bodies are canceled and reader locks/listeners are
released. Invalid replies keep fixed feedback and require a manual retry, without
restoring a stale result or discarding proposed settings. Version, scope and
conditional-result checks remain unchanged.

Both temporary Architecture forms summarize only unmet and unknown items from the
validated preview, with native links back to their exact fields. Conditions and
concrete settings stay separate; the summary never chooses answers or changes the
reference rules. Unknown or unselected saved client scope points to Context instead
of being presented as missing design answers. Editing removes the summary together
with the old result. No open items is not configuration verification or a recommendation;
all original checks and limitations remain visible below it.

The same-origin BFF POST `/api/assessments/{id}/architecture-prerequisites` requires a
live database-backed session. It calls the separate Core POST
`/api/v1/workspaces/{workspaceId}/assessments/{assessmentId}/architecture-prerequisite-preview`
using only server-held credentials and session identity. Core binds the exact
`expectedVersion`, derives client scope from the stored assessment and accepts only
typed prerequisites belonging to the selected `patternId`. Missing declarations
remain unknown; stale versions return 409. Neither call changes assessment state,
revisions or events.

Results explain each condition independently and stay explicitly conditional on
unverified design declarations. Unknown or unselected client scope cannot become a
match. An unmet condition preserves other unknown conditions. Even an all-met design
cannot override the separate client/token-exposure preflight, verify configuration or
provider compatibility, or establish recommendation/approval/deployment readiness.
The existing GET response and historical impact receipts remain unchanged.

### Proposed architecture settings

The separate Core POST
`/api/v1/workspaces/{workspaceId}/assessments/{assessmentId}/architecture-configuration-preview`
checks concrete proposed settings, rather than only declaring a prerequisite met.
The exact request contains `expectedVersion`, one of the five `patternId` values and
a sparse typed `settings` map. Thirteen setting IDs cover the primary flow, public/
confidential client, credential custody, token location, PKCE, redirect matching,
session cookie/CSRF policy, resource path, browser endpoint access, native user agent
and workload authorization. Each pattern accepts only its own settings and each
setting only its own enum values. Missing or explicit `UNKNOWN` answers stay unknown.
No secret, actual URL, evidence, client-scope assertion, extra field or query is accepted.

Core checks the exact saved version, derives client scope from the stored profile
and returns the unchanged independent client/token preflight alongside ordered
conditional setting checks, source-owned definitions and deferred boundaries.
Unknown scope cannot match; an unselected client is not applicable; an incompatible
setting takes precedence without hiding other unknown settings. A proposed match
cannot resolve the SPA's separately unknown acceptable browser-token exposure.
The endpoint is protected by service credentials and personal-workspace ownership;
stale versions return 409. Current v6 profiles are read without downgrade. No profile,
revision, event, catalog, provider registration or credential is changed.

Policy `architecture-configuration-design-1` conservatively requires S256 for all
human reference designs. This is AuthWeave project policy, not a universal normative
MUST for every confidential OIDC client. The redirect port exception applies only to
native loopback IP-literal redirects, never wildcard hosts or paths. Resource-access
checks describe the primary reference path; additional direct browser APIs need
independent assessment. See [OAuth security guidance](https://www.rfc-editor.org/rfc/rfc9700.html#section-2.1),
[browser patterns](https://www.ietf.org/ietf-ftp/rfc/rfc10017.html),
[native redirects](https://www.rfc-editor.org/rfc/rfc8252.html#section-7.3) and
[workload authorization](https://www.rfc-editor.org/rfc/rfc6749.html#section-4.4).

The analysis basis is `UNVERIFIED_PROPOSED_CONFIGURATION`: all observation,
configuration/provider/runtime verification, recommendation/publication and write
flags remain false. Actual registration, redirect ownership, protocol defenses,
API authorization, storage/session security, CORS and remaining lifecycle/security/
operations requirements are deferred. These settings describe the assessed target
application, not AuthWeave's own login. Observed configuration verification remains
a separate follow-up.

The personal Architecture step now has a collapsed **Try concrete settings** form
on each pattern card. Forty scoped controls across the five designs start Unknown;
each choice has an explanation and a readable selected-value label. Results show
the proposed and reference values separately. Choices are temporary: editing clears
the result, cancel preserves choices for a manual retry, and leaving the step or
reloading resets them. No setting is recommended, saved or applied to an IdP.

Same-origin, live-session BFF POST `/api/assessments/{id}/architecture-configuration`
accepts only the version, pattern and scoped setting enums in a bounded form. It
reads the canonical v6 saved profile, uses server-held workspace/identity credentials,
and independently replays the bounded Core response: exact version/client scope,
saved-input preflight, ordered settings, definitions and false verification/readiness
flags. It returns only the checked analysis and assessment version, never credentials
or upstream identity/details. Invalid, stale, unavailable or malformed replies have
fixed feedback. Inputs lock during a request; cancellation, step changes and the
ten-second deadline suppress late results without automatic retries. Existing
design-condition forms and the separate saved-input checks remain unchanged.

### Proposed architecture settings regression

The source-controlled regression library exercises 252 explicit synthetic cases
and 2,004 setting checks across four frozen v5 profiles and five patterns. Three
client-context variants cover the original profile, unknown clients and an excluded
pattern client. Designs cover missing, explicit unknown, matching reference and
incompatible settings with an independent remaining gap, plus the native loopback
redirect exception. These overlays are test inputs, not user defaults or observations.

Credential-protected GET
`/internal/v1/catalog-architecture-configuration/regression-preflight` accepts no
query or body and returns only no-store metadata, hashes, counts and deferred
boundaries. Core checks the complete ordered report against independent fixture
expectations; the fixed-clock HTTP contract suite independently replays expected
counts and rejects shape-valid hash, scope and balanced-count substitutions.
Conditional settings matches cannot remove separate saved-input preflight gaps.

This is a fresh synthetic regression, not candidate-change impact, an IdP inspection
or a stored receipt. All verification, full-coverage, readiness and write flags stay
false. Saved assessments, provider configuration, historical receipts and existing
coverage/publication gates are unchanged. The personal UI is unchanged in this slice.

### Provisioning lifecycle design preview

Core POST `/api/v1/workspaces/{workspaceId}/assessments/{assessmentId}/provisioning-lifecycle-preview`
compares an explicit SCIM, login-time JIT or SCIM-and-JIT design with the exact saved
provisioning criticalities. The [request](packages/contracts/schemas/provisioning-lifecycle-request.v1.schema.json)
contains only `expectedVersion`, `patternId` and typed temporary declarations;
missing declarations stay unknown. Foreign-pattern conditions, supplied requirements,
evaluation time, extra fields and query parameters are rejected. The existing service
credential and personal-workspace ownership boundary applies. Stale versions return
409; missing or cross-workspace assessments return 404 after authorization.

The [no-store preview](packages/contracts/schemas/provisioning-lifecycle-preview.v1.schema.json)
includes all three alternatives' advantages, tradeoffs, scoped conditions and concept
references. JIT-only cannot satisfy required SCIM; a hybrid cannot bypass forbidden
SCIM or JIT. Explicit preferences are not scored. Required or forbidden group
synchronization remains unknown: selecting SCIM does not establish group delivery or
application role mapping. A hard mismatch takes precedence without hiding other gaps.

Declarations cover tenant/subject correlation, attribute ownership, offboarding and
access revocation, failure recovery, SCIM direction/User operations, trusted JIT
linking and hybrid collision policy. Even all-satisfied declarations are an unverified
design hypothesis, not observed provider support, interoperability, session revocation
or lifecycle verification. Assessment state, revisions, events, existing eligibility,
coverage and publication gates are unchanged. No declarations or result are saved,
and no IdP configuration, accounts, dependencies or paid calls are added.

Core additionally exposes a separate [v2 design request](packages/contracts/schemas/provisioning-lifecycle-request.v2.schema.json)
at POST `/api/v2/workspaces/{workspaceId}/assessments/{assessmentId}/provisioning-lifecycle-preview`.
It requires an explicit `groupStrategy`: `UNKNOWN`, `NONE`, `SCIM_GROUPS` or
`APPLICATION_BRIDGE`. No group plan cannot satisfy required group synchronization;
an active plan cannot bypass forbidden groups. An application bridge cannot replace
required SCIM user provisioning. This model's SCIM Group option uses a selected SCIM
user-lifecycle pattern; JIT-only with that option produces a conditional mismatch.

The [v2 preview](packages/contracts/schemas/provisioning-lifecycle-preview.v2.schema.json)
separates account disablement/new-login blocking, application-session invalidation
and token revocation or bounded expiry. Active group plans additionally require
source/member mapping, out-of-band delivery/reconciliation, application role
enforcement, removal/access rechecks and transport-specific operations. All omitted
conditions remain unknown, hard failures retain every gap, and even all-met answers
are only a conditional design hypothesis. Every verification, write and readiness
flag stays false. V1, saved profiles, history, eligibility and publication gates are
unchanged; the personal BFF/UI now uses v2, while the v1 route remains available. V2 does not inspect an IdP or
execute a provisioning, group, logout or revocation operation.

The personal assessment's Architecture step now shows all three alternatives with
advantages, trade-offs, saved SCIM/JIT/group requirements and temporary condition
forms. All answers start unknown; no architecture or provider is selected or saved.
Changing an answer clears its result, and leaving the step or reloading discards all
temporary answers. Each form permits one in-flight request, explicit cancellation
and a ten-second deadline; canceled or late replies cannot restore stale results.
Every requirement and condition remains visible, including unknown gaps after a
hard mismatch. Each form also explains four explicit group strategies and their
advantages and limits: unknown, no synchronization, SCIM Groups or an application-owned
bridge. Changing the strategy clears the result and resets its group conditions to
unknown; account lifecycle answers are retained. Account disablement, application
sessions and token revocation/expiry have separate conditions. Group delivery and
actual access revocation are not established by these declarations.

Cancel, declaration/group changes, leaving the step and the existing ten-second
deadline also interrupt a pending provisioning response-body read. The 32 KiB
actual-byte/fatal UTF-8 guard stays in place; rejected bodies release their reader.
Manual retry gets a fresh deadline and retains the current group strategy. Late
body completion cannot restore an obsolete result or unlock a newer request.

Checked provisioning previews now summarize follow-ups separately: conflicts with
saved requirements, unknown saved requirements, group-plan gaps, declared unmet
conditions and unknown temporary conditions. Short links return to this pattern's
exact group/condition fields without choosing an answer. Unknown saved requirements
lead back to Requirements; an unknown group strategy is not mislabeled as an unknown
saved requirement. Hard mismatches do not hide the remaining gaps, and declarations
cannot supply missing SCIM or remove a forbidden mechanism. Full checks and
unverified boundaries remain visible; the summary is not a task list, saved choice
or deployment approval.

Same-origin POST `/api/assessments/{id}/provisioning-lifecycle-v2` requires a live
database-backed browser session. It rejects query parameters, duplicate form fields,
foreign conditions and caller-supplied requirements. The BFF reads the canonical v6
assessment, binds its version and saved criticalities, then sends only the typed
preview request with server-held credentials and the session's issuer/subject.
Bounded response guards independently replay every check and status, require the
exact educational inventory and reject promoted readiness or verification flags.
The browser receives only a checked version-bound analysis, not credentials or raw
upstream errors. Stale versions require reload; session and transport failures use
fixed safe messages. The v1 BFF route remains available for its unchanged v1 design
contract; the UI does not silently fall back to it. Existing assessment, history and
publication gates are unchanged.

Concept sources are [SCIM operations](https://www.rfc-editor.org/rfc/rfc7644.html),
[SCIM User and Group schemas](https://www.rfc-editor.org/rfc/rfc7643.html), and a
[login-time creation/update example](https://zitadel.com/docs/guides/integrate/identity-providers/introduction).
The last reference illustrates an implementation, not any plan's entitlement or a
verified provider catalog fact. Evaluation never fetches these references.
Group and offboarding concepts also reference the
[SCIM Group schema](https://www.rfc-editor.org/rfc/rfc7643.html#section-4.2) and
[OAuth token-revocation implementation notes](https://www.rfc-editor.org/rfc/rfc7009.html#section-3);
these are protocol context, not evidence of a vendor or application's enforcement.

### Synthetic lifecycle v2 regression

`GET /internal/v1/catalog-provisioning-lifecycle/regression-preflight` is a protected,
input-free internal diagnostic. It replays 2016 source-controlled synthetic cases:
four frozen v5 profiles with their explicit v6 auditability supplement, six
provisioning-requirement contexts, all three patterns and four group strategies.
Only provisioning criticalities are overlaid; all other profile inputs remain intact.
Six common declaration variants distinguish omitted/explicit unknown, all-met,
all-unmet, session/token gaps and failure-with-gap. Two group-removal variants apply
only to explicit group transports. These are test inputs, never owner defaults.
The bounded matrix is not every possible combination or candidate-change coverage.

Independent expectations check all 16 scoped conditions, hard SCIM/group conflicts,
account/session/token separation, group removal/access rechecks and failure precedence
without hiding unknown gaps. The diagnostic requires one server-only credential,
refuses body/query input and returns `no-store` inventories, reason/outcome counts and
binding digests. It returns no profiles, declarations, rows or identity and writes
nothing. Complete ordered reports are replayed before summarization; independent
contract checks rebuild inputs, checks and digests from frozen source files rather
than Core output. A fresh clock changes only analysis binding, not evidence freshness.
All configuration, compatibility, lifecycle, group synchronization, access revocation,
coverage, approval, publication, recommendation and write claims stay false. Existing
v1/v2 previews, personal BFF/UI, history and coverage gates are unchanged. No new
dependencies, migrations, provider calls, accounts or subscriptions are added.

### Auditability capability rule kernel

The separate `auditability-capability-preflight-1` kernel evaluates explicit requirements
against synthetic, dated identity-provider capability evidence. Six separately selectable criteria
cover successful authentication events, failed authentication events, administrative changes,
provisioning changes, log export and minimum log retention. No criterion is selected by
default. Requiring auditability without selecting its scope remains unknown.

Evidence is bound to an exact option ID, plan, region and configuration; application
logs, foreign scope and duplicate facts cannot supply provider evidence. Existing
review/future-date/90-day freshness gates apply before support or incompatibility is
considered. Missing or unusable evidence stays unknown. A documented unavailable
required capability fails without hiding other unknowns. Preferences are not scored,
and an ambiguous prohibition is not interpreted as an instruction to weaken logging.

Retention requires an explicit positive minimum in days. A supported retention capability
with no documented minimum remains unknown. A documented minimum below the requested
threshold fails; equal or greater satisfies only that check. Provider retention is not
inferred from source age, a configurable maximum or an external export sink. The 36,500-day
input ceiling is a project validation bound, not a default or compliance requirement.

The distinction between chosen event scope and explicit retention parameters is informed
by [NIST SP 800-53 AU-2 and AU-11](https://csrc.nist.gov/CSRC/media/Projects/risk-management/800-53%20Downloads/800-53r5/SP_800-53_v5_1-derived-OSCAL.pdf).
These project criteria are not a NIST baseline or a compliance assessment. Record content
and client/population coverage, integrity, access controls, failure handling, export
delivery, deployed configuration and compliance evidence remain unverified even when all
selected capability checks pass. Configuration/compliance/recommendation readiness stay false.

The evaluator remains a pure rule kernel with synthetic facts, not a live provider
integration. Profile v6 can record its requirement inputs as described below, but
catalog v4 does not carry the corresponding scoped facts;
legacy preflights, source profiles, impact coverage and historical receipts are unchanged.
Auditability remains deferred in those legacy reports; the new v6 constraint integration
below does not reinterpret their results.
AuthWeave's own append-only decision/audit history is a separate responsibility.

### Scoped synthetic auditability evidence and private Core preview

[Auditability evidence v1](services/core-api/src/main/resources/catalog/auditability-evidence.v1.json)
is a separate immutable-in-memory sidecar, explicitly bound to the unchanged catalog
v4 version and its option IDs, plans and regions. Each scope names one synthetic
configuration; different configurations are never pooled or treated as a menu of
verified deployments. Core rejects foreign scopes, application emitters, duplicate
criteria/scopes, and missing base option bindings. Facts have support, review status,
an observation date, an HTTPS `.invalid` source and an explicit nullable documented
minimum retention. The [sidecar contract](packages/contracts/schemas/synthetic-auditability-catalog.v1.schema.json)
does not make this evidence a published catalog or a human source-review receipt.

The local-only GET
`/api/v6/workspaces/{workspaceId}/assessments/{assessmentId}/auditability-capability-preflight`
reads the saved requirements and returns the assessment version, exact candidate scopes,
dated evidence and six ordered checks per scope. It requires the existing server-only
credential and personal workspace ownership, returns `Cache-Control: no-store`, and
does not update the profile, timestamps, revisions, events or evidence dates.
Core recomputes each candidate result from the returned evidence; unusable/missing
facts stay unknown. Legacy unrecorded scope stays unrecorded without an automatic save.

The fictional fixtures intentionally demonstrate a 90-day minimum, a 7-day minimum,
an unavailable export capability, a missing provisioning-event fact and unreviewed
claims. These are not facts about ZITADEL or any real vendor. Source URLs are not
fetched. Passing selected criteria establishes only a synthetic capability match;
configuration, compliance, source verification and recommendation readiness stay false.
Legacy comparison/impact policies, frozen source profiles, receipts and publication
gates remain unchanged. This standalone endpoint is not itself merged provider eligibility,
ranking or a complete auditability assessment.

The personal assessment page now reads this separate preview through the existing
BFF/session boundary. It binds the session workspace, assessment ID, exact version
and saved criteria/duration; no browser-supplied identity or credential is used.
The BFF bounds the streamed response to 1 MiB, rejects unknown fields, readiness claims,
foreign/duplicate scopes or facts, and recomputes every reason/outcome/status from the
returned evidence. UTC evidence comparisons preserve Core's nanosecond precision,
including the exact 90-day freshness boundary. Actual Core HTTP responses are also
checked against this BFF consumer in CI, independently of Web fixtures.

The server-rendered UI explains each selected or unapplied criterion, the scoped
synthetic claim and observation date, provider retention versus the requested minimum,
and the deferred verification boundaries. Fictional source URLs are plain text,
never fetched or made into navigation links. Preferred is not scored; forbidden intent
needs clarification and does not advise disabling logs. A stale, malformed or unavailable
preview produces a sanitized unavailable section without blocking the assessment or
other independent previews. Reads do not edit inputs or refresh evidence dates.

### Auditability in combined Core constraints

The v6 `hard-constraint-preflight`, `comparison-preflight`,
`weighted-comparison-preview` and `weight-sensitivity-preview` assessment endpoints
now combine the existing eligibility checks with the scoped auditability kernel.
Their [hard-constraint v2](packages/contracts/schemas/hard-constraint-preflight.v2.schema.json)
and [comparison v2](packages/contracts/schemas/synthetic-comparison.v2.schema.json)
responses include the exact synthetic auditability evidence and requirements. One
assessment read and one evaluation instant bind both analyses; foreign versions,
catalogs, plans/regions, option identities and ambiguous configurations are rejected.

A required unsupported audit criterion or insufficient documented minimum retention
excludes the option without hiding other information gaps. Unknown intent, missing
criteria and missing/unreviewed/future/stale evidence remain unresolved. A preference
or an audit capability pass cannot reverse another hard failure. Explicitly preferred
or not-required auditability does not become a hard constraint or a scored preference.
An affirmative audit check can establish a checked match when the only previous gap
was the absence of any affirmative check. An empty or unapplied scope cannot do so.

Excluded or unresolved options receive neither weighted scores nor sensitivity deltas.
Independent HTTP checks replay the embedded audit evidence, exact legacy baseline,
merged findings, verdicts, scores and deltas. Reads change no profile, revisions,
events or evidence dates. Full audit verification, catalog publication and final
recommendations remain unavailable; the auditability deferred path still denotes
unchecked deployed logging/compliance boundaries, not ignored capability inputs.

The personal Web Comparison and both temporary weight previews use v6
analyses through the existing session-bound BFF. Its bounded response guard
replays embedded audit evidence against the exact saved criteria and retention,
binds catalog/time/option scopes, and rejects hidden or mismatched audit findings.
Failures and gaps link to saved Audit inputs; fixed explanatory copy distinguishes
documented capabilities from deployed logs and compliance. Neither preview saves
answers or awards scores/deltas to excluded or unresolved options. Actual Core HTTP
samples also pass this strict Web consumer. The v5 endpoints and historical
policies/receipts remain unchanged.

### Inspecting fictional comparison evidence

The protected GET
`/api/v6/workspaces/{workspaceId}/assessments/{assessmentId}/comparison-evidence-preview`
returns the native comparison with the exact immutable synthetic catalog used to
evaluate it. One saved-profile read and one evaluation instant bind the analyses;
it accepts no query or body and requires the existing service credential and
personal-workspace owner. Responses are not cached and no state is written.
The [evidence envelope](packages/contracts/schemas/comparison-evidence-preview.v1.schema.json)
includes a catalog content digest, not a signature or source-verification proof.

The personal Comparison page uses this envelope instead of independently joining
catalog facts to a result. Its 1 MiB guard checks the saved assessment/version,
exact candidate/plan/region inventory, catalog digest and preference-source binding,
and reuses the scoped auditability guard. It distinguishes missing, unreviewed,
future and older-than-90-day evidence without losing timestamp nanoseconds. The
74-row inventory per option includes omitted known facts, not 74 applied checks
or proof of complete coverage. Claims remain claims; UNKNOWN is not promoted.
Unreadable or mismatched evidence makes Comparison unavailable, without falling
back to another catalog or an older policy. Other independently read page panels
are not one page-wide snapshot. Existing comparison/weight endpoints, verdicts,
scores and historical receipts remain unchanged. No sources are fetched, real
provider baseline activated, recommendation granted or publication performed.

The display matrix joins these already-guarded inventories by exact option ID and
fact path, not array position. An incomplete or mismatched inventory makes the
matrix unavailable instead of fabricating missing facts; the option cards are
unchanged. Source references and dates remain expandable plain text. Display
choices reset when Comparison is remounted or its evidence snapshot changes;
they are not saved requirements, shortlist selections or new trust assertions.

### Scoped synthetic auditability regression

The [source-controlled scenario overlays](services/core-api/src/main/resources/catalog/scoped-auditability-scenarios.v1.json)
add explicit fictional auditability criteria and retention to the four unchanged
scoped profile v5 fixtures. Their base digest is checked before compiling complete
profile v6 inputs. These selections are test cases, not customer defaults, legal
retention requirements or a compliance baseline.

The service-credential-protected GET
`/internal/v1/catalog-auditability/regression-preflight` accepts no query or body.
It evaluates every compiled scenario against every exact scope in the synthetic
sidecar using the existing evidence policy. The body-free, `no-store` summary binds
the scenario, profile schema, rule definitions, evidence and analysis digests, and
separates matches, mismatches and information gaps across six ordered criteria.
Missing, unreviewed, future or stale facts are not promoted to passing evidence;
reading the diagnostic neither refreshes observation dates nor writes to storage.

This is fixture regression, not before/after analysis of proposed catalog changes,
a durable impact receipt or full auditability verification. Existing profile v5
coverage inventory, historical receipts and publication gates remain unchanged;
auditability is still deferred in those older reports. Coverage, configuration,
compliance, source verification, approval and publication/evaluation/recommendation
readiness remain false. The seven deferred verification boundaries are explicit.

### Profile v6 coverage composition

`catalog-profile-impact-coverage-5` inventories all 34 semantic inputs of profile v6
without rewriting the existing v5 manifest, scoped profiles or historical receipts.
The service-credential-protected, input-free GET
`/internal/v1/catalog-profile-impact/coverage-preflight` returns a body-free, `no-store`
[coverage summary](packages/contracts/schemas/catalog-profile-impact-coverage.v1.schema.json).
It binds the unchanged structural coverage digest to the supplemental v6 scenario
digest and the fresh, separately scoped auditability regression at the same instant.

The four scenarios produce 136 dependency rows. Catalog claim rules and architecture
pattern rules remain distinct from synthetic auditability capability rules. Explicit
criteria dependencies use the sidecar vocabulary, never borrowed catalog fact paths.
An unselected retention criterion stays a scope guard, not an exercised capability.
Rule presence is independent of whether dated evidence passes, fails or remains unknown.
The 36 deferred input rows and 40 scenario-bound verification gaps remain explicit,
including all seven auditability verification boundaries in every scenario.

Fresh Core publication preflight policy `catalog-publication-preflight-12` consumes
both the unchanged v5 structural summary and this new v6 composition for exact stored
proposals or bootstrap reviews. Missing/invalid inputs keep coverage `NOT_CHECKED`;
failed calculation or inconsistent time/digests fails closed. These are fresh checks,
not replacement receipts. The sidecar is not proposed-catalog auditability data:
`candidateAuditabilityChangesEvaluated` remains false. Full coverage, deployed logging,
compliance, source verification and publication authority remain unverified and blocked.
No endpoint publishes, refreshes evidence dates or writes an assessment/report/audit row.

### Auditability inputs and profile v6

The local-only `/api/v6/workspaces/{workspaceId}/assessments` API adds
`security.auditabilityRequirements` to the complete profile. It contains an explicit
`selectedCriteria` array of the six IDs in the
[profile v6 contract](packages/contracts/schemas/application-identity-profile.v6.schema.json)
and `minimumRetentionDays`.
No selection means unresolved scope, not a logging exemption. Selecting
`AUDIT_LOG_RETENTION` requires an integer from 1 to 36,500 days; otherwise the duration
must be explicitly null. The existing `security.auditability` criticality applies to
the selected criteria. Recording a requirement does not confirm provider capability.

GET projects v1-v5 profiles with an empty selection and null duration without changing
data, timestamps, versions or history. PUT `/{assessmentId}/profile` uses
`expectedVersion`; scope changes atomically append a minimal security-section event
and snapshot. Criteria are serialized in enum order; reordering a selection is a no-op.
No-op saves preserve storage. Recorded scope requires stored profile v6,
including when usage inputs also exist. Flyway V18 only widens allowed format versions.

V1-v5 reads/writes refuse an unsupported current profile with
`profile-upgrade-required`, preventing silent data loss. An explicit v6 clear can
restore older current-profile compatibility; earlier v6 snapshots remain unchanged.
GET `/{assessmentId}/revisions` under v6 returns exact mixed v1-v6 snapshots. Older
history APIs reject only pages containing unsupported formats, not compatible pages.

These Core routes retain the server-only service credential and personal workspace
ownership boundary. Personal assessment create/read/list and all full-profile edits
now use v6 through the BFF. The private draft page provides six labelled criteria,
one shared criticality and a retention input enabled only when retention is selected.
Unselecting retention clears its duration; unchecking all criteria explicitly clears
the recorded scope. Context, capability and usage edits preserve these fields.
Every save reads the latest profile and submits `expectedVersion`; stale drafts and
non-drafts are not overwritten. The bounded, same-origin form accepts no workspace,
identity assertions or arbitrary profile JSON from the browser. Core response checks
bind the workspace, assessment, format, version and saved auditability scope.

Legacy projection remains read-only until an explicit save. The existing synthetic
comparison, architecture and usage previews keep their current policies and scopes;
auditability is still deferred, not marked as checked merely because inputs exist.
No provider evidence is loaded and no preflight/impact coverage, historical receipt
or publication authority is upgraded by recording these inputs. The public browser
preview/download remains v1 and is separate from private assessment editing.

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

The read-only v5 `/{assessmentId}/hard-constraint-preflight` summarizes those existing
checks for each synthetic option. `EXCLUDED` lists all confirmed failures;
`UNRESOLVED` lists missing, stale or unreviewed evidence and unclear inputs. A failure
still takes precedence without hiding information gaps. `PASSES_CHECKED_REQUIREMENTS`
means only that the currently checked constraints pass. Deferred dimensions remain
explicit; the endpoint does not publish provider facts, score options, choose a winner
or change assessment state. Its result is not yet a provider recommendation.

The v5 `/{assessmentId}/comparison-preflight` combines that hard-constraint verdict
with availability evidence for capabilities explicitly marked `PREFERRED`. It shows
an excluded option's preferences without letting them reverse its exclusion.
Missing, stale, future or unreviewed preference facts remain `UNKNOWN`; an unavailable
preference is not a hard failure. Only capability preferences are compared. There are
no weights, scores, ranking or winner, and other preference dimensions remain deferred.
The comparison uses the same fictional catalog and does not write assessment data.
The private assessment page calls out stale facts in the checked constraints or
capability preferences. Such facts cannot establish support or exclusion, and an
affected diagnostic score is withheld. Merely changing an observation date does not
verify its source. This warning does not refresh or approve catalog evidence.

The optional weighted and alternative-weight controls use only explicit weights
totaling 100. They share one in-flight calculation: both sets of inputs are locked,
with an explicit Cancel calculation action and a ten-second request deadline.
Changing weights clears the affected result; changing the baseline also invalidates
the alternative. Leaving Comparison cancels its request, and late canceled replies
cannot restore results or unlock a newer request. Refusals preserve the entered
weights for a manual retry. These are temporary, read-only fictional diagnostics,
not saved preferences, provider rankings or recommendations.

A fixed-clock Phase 3 regression suite reuses the three frozen B2B, public-sector and
workforce profiles with the synthetic catalog. It pins reason-coded verdicts and
preference outcomes. A test-only B2B variant makes explicit choices and supplies
weights to the separate preview; only a fully checked option receives a score.
After the evidence ages past 90 days, the previous SCIM exclusion and score both
become unresolved.
These synthetic regression cases are not real provider evidence or the planned
full 18-case evaluation dataset; they do not establish a final recommendation.

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
The private assessment page can edit these inputs in a draft. Each save reads the
latest complete v6 profile through the BFF, changes only `usagePlanning`, and uses
`expectedVersion`; other profile fields are preserved. Leave a metric blank with
`Unknown` basis to keep it unknown, or choose `Assumed`/`Observed` and enter a number.
The form accepts up to 10 separate assumptions. Do not enter secrets or personal data.
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
GET on the v5 collection returns at most 20 summary rows by default (50 maximum),
newest first. `nextBeforeId` is an exclusive same-workspace cursor for the next page;
profile contents are never included in the list.
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
The private assessment page shows this check next to the usage editor. Its BFF verifies
the response against the current assessment version and saved inputs before displaying
missing fields; an unavailable check does not make the assessment itself unavailable.

`pricingEvaluated` and `recommendationReady` are always false. No tariff lookup,
cost quote, free-tier promise, affordability check, score or provider elimination is
performed. Dated prices, paid feature gates, billable-unit mapping and infrastructure,
additional environments and operational costs still need a separate cost model.
V1/v2/v3/v4 eligibility endpoints, policies and synthetic catalog v4 remain unchanged;
there is no v5 eligibility endpoint. The separate browser-only `/preview` remains v1
without a usage-input editor; the private authenticated draft page has one.

### Operations planning: managed and self-hosted alternatives

Credential- and personal-workspace-protected GET
`/api/v1/workspaces/{workspaceId}/assessments/{assessmentId}/operations-planning-preflight`
compares two generic identity-service operating models against the exact saved
assessment version. It accepts no query or body and returns no-store results.
Hosting alignment is only a preference comparison: both alternatives remain visible.
Limited declared identity expertise prompts separate integration/operator support
planning; advanced expertise is not evidence of operational readiness. Deployment
target describes the assessed application, not a verified IdP location or compatibility.

Results include shared and model-specific responsibilities, advantages, tradeoffs,
typed support/cost-model follow-ups and missing inputs. Usage output contains only
recorded metric IDs and missing paths, never quantities, scope text or assumptions.
An explicit zero is recorded, not unknown. `INPUTS_RECORDED` means the operational
preferences and existing usage-input requirements are filled in, not verified.
Budget sensitivity is not a spending cap; even zero usage cannot establish a free
tier or total cost. Provider eligibility, deployment compatibility, pricing, cost
model, budget fit, configuration, readiness, recommendation and write flags stay false.

The generic responsibility model is informed by
[shared responsibility guidance](https://learn.microsoft.com/en-us/azure/security/fundamentals/shared-responsibility)
and [self-operated identity production concerns](https://www.keycloak.org/server/configuration-production),
not verified vendor-plan facts. This assesses the target application's identity design,
not AuthWeave's own hosting or budget. V6 profiles are read without downgrade;
assessment state/history, catalogs, coverage gates and the existing usage check are
unchanged. No dependencies, accounts, subscriptions, price lookups or provider calls
are added.

The personal assessment's Usage step shows both operating models side by side on
desktop and stacked on narrow screens, with saved preferences, input gaps, support
planning and responsibilities. Drafts have a separate four-choice operational
preferences form: identity hosting, application deployment target, declared team
expertise and qualitative budget sensitivity. Unknown/undecided answers are explicit;
the form neither selects an IdP location nor records a monetary spending cap.
Usage quantities and assumptions remain editable in their own form.
The comparison's server-side BFF uses
only its live session's workspace and identity, a body-free fixed-origin GET,
no-store, refused redirects, a timeout and a 32 KiB streamed response limit. It
independently replays the saved preferences and usage presence, exact assessment
version, ordered alternatives, narratives and all false authority flags. Unknown
inputs have no invented defaults; stale or malformed responses produce a sanitized
unavailable section without blocking the saved assessment or inventing a result.
The browser receives no service credential, workspace identity or raw Core payload.

`POST /api/assessments/{assessmentId}/operational-preferences` accepts only those
four enums and the exact saved version, from a same-origin live personal session.
The streamed form body is limited to 1 KiB; duplicates, foreign fields, query
parameters and noncanonical values are refused before any Core call. The BFF reads
the session-owned v6 draft and patches only the four choices, preserving all usage,
audit and other profile values. Core still owns the optimistic write; stale or
archived drafts cannot be overwritten. A successful Core reply must preserve the
whole submitted profile, not just look successful. Native forms use a fixed 303
return to Usage; enhanced forms check a small target/version/outcome receipt.
Conflict or uncertain saves keep local inputs, without merge or automatic retry.
Only one form can save at a time. If another form has unsaved edits, a confirmed
save does not automatically reload and discard them: explicit discard is required.
The comparison continues to show the originally loaded saved snapshot until reload;
unsaved selections never recalculate it or become provider/cost recommendations.

### Assurance and compliance investigation planning

`GET /api/v1/workspaces/{workspaceId}/assessments/{assessmentId}/assurance-compliance-planning-preflight`
returns a read-only investigation inventory bound to the exact saved assessment
version. It requires the server-only service credential and personal-workspace
ownership, rechecks ownership after routing, accepts no body/query and returns
`no-store`. All six stored profile formats and archived assessments can be read
without rewriting their profile, history or events. Only selected enums are
included; owner free text, provider facts and source documents are not returned.

Seven generic assurance items identify where inputs need clarification or evidence
still needs investigation: the objective, human scope, independent authentication
controls, enrollment/recovery, sessions/reauthentication, workload identity and
federation/trust boundaries. `BASELINE`, `ELEVATED` and `HIGH` are planning labels,
not formal assurance levels or automatic requirements. MFA and each control remain
independent. Explicit machine-only scope marks human-flow items `NOT_APPLIED`,
not verified; missing clients/populations never become wildcard scope. Workload
flows remain separate and mixed human/machine clients retain both investigations.

The existing compliance-scope check is reused without changing eligibility rules.
Unknown scope retains partial target lists; `NONE_IDENTIFIED` with an empty list
removes that target check, not legal obligations. Recorded targets still need
evidence and `OTHER` needs a concrete definition. Generic prompts ask for concrete
criteria, applicability review, exact service scope, responsibilities and dated
source material. This is not a framework-specific checklist, legal determination,
certification or source verification. No target-specific obligations are inferred.

Overall status stays `NEEDS_INFORMATION`; all verification, eligibility, readiness
and write flags remain false. Strict contracts and independent saved-input replay
check row order, completeness, explanations, scope and time/version binding, not
just response shape. No provider calls, standards mapping, new dependencies,
migrations, accounts or subscriptions are added. Existing candidate-change and
publication coverage gates remain closed. The personal BFF/UI boundary below
does not open those gates.

The protected personal assessment's Review step displays this inventory for its
saved version: the seven assurance investigations, selected compliance targets,
independent control requirements and expandable generic prompts. A planning label
is not a formal level; `HIGH` does not silently set MFA. Mixed and machine-only
scope remain distinct, partial target lists are preserved, and an empty target
list is not a legal exemption. Existing context and capability editors remain the
only places to change these inputs; this display collects no answers or evidence.
Archived assessments show the same read-only inventory without editing shortcuts.

The server page resolves a live session before contacting Core and sends only a
fixed, credentialed, `no-store` GET. The BFF bounds the response and independently
replays every row against the loaded saved inputs, owner scope and version; stale,
malformed or substituted results become a sanitized unavailable section while the
saved assessment and other checks remain usable. `evaluatedAt` must be a valid UTC
timestamp, but is calculation metadata, not evidence freshness. No workspace
identity, server credential, raw profile text or verification flags enter the
browser projection. Tests cover the real asynchronous page, isolated live-session
revocation and the consumer against actual Core HTTP responses; a display-only
browser fixture is not an authenticated OIDC end-to-end test.

### Synthetic assurance and compliance investigation regression

`GET /internal/v1/catalog-assurance-compliance/regression-preflight` is a protected,
input-free internal diagnostic. It replays 36 synthetic cases: four unchanged frozen
v5 profiles with the explicit v6 auditability supplement, each with nine assurance,
control and scope variants. Only investigation inputs are overlaid; unrelated
requirements remain intact. The bounded set covers all planning labels, independent
control criticalities, all target labels, partial unknown scope, explicit no-target
scope and human/machine/mixed clients. It is not every possible input combination.

Complete ordered reports are replayed before summarization. Independent contract
expectations reconstruct the profiles, questions, scope checks, counts and binding
digests from source-controlled inputs, not Core output. The endpoint requires one
server-only credential, refuses query/body input and returns `no-store` counts,
digests and inventories only: 252 assurance items and 45 recorded target items.
No profile bodies, source documents, free text or identities are returned or saved.

Every case still needs information. A fresh calculation changes the analysis digest,
not evidence freshness; neither an unselected flow nor an empty target list proves
safety or exemption. Source verification, assurance/compliance verification,
candidate-change coverage, approval and publication remain separate boundaries;
their flags stay false.
Existing coverage policies, historical receipts and the personal UI are unchanged.
No provider calls, dependencies, migrations, accounts or subscriptions are added.

### Composed profile planning coverage

`GET /internal/v1/catalog-profile-planning/coverage-preflight` freshly combines the
existing v6 structural coverage with the separate architecture configuration,
lifecycle v2, operations and assurance/compliance regression checks. It requires
one server-only service credential, refuses query/body input and returns `no-store`
output without profile bodies, identities, source documents or writes.

The report retains every old structural state across four frozen scenarios and
all 34 semantic inputs (136 dimensions). Each dimension lists the planning
regression families that consume that input, not verified provider capabilities.
All 36 historically deferred dimensions now have a separate planning route;
their structural state still remains `DEFERRED_DIMENSION`. Auditability stays
bound to its existing synthetic structural regression rather than being relabeled
as observed logging. A route is not exhaustive coverage of every input combination.

All four planning summaries must independently replay at one clock and bind the
same unchanged base. Exact policy, definition, source, analysis and full-check
digests identify their 252 architecture, 2016 lifecycle, 140 operations and
36 assurance/compliance cases. Architecture fixtures keep their original v5
schema; the other three retain the explicit v6 auditability supplement. Missing,
stale or substituted inputs fail rather than falling back to an older result.
Independent HTTP contract checks bind the separately obtained structural response
and reconstruct the planning bindings, input routes, manifest and complete digest.

The old 40 scenario-specific verification gaps and all 22 additional planning
boundaries remain visible. `INCOMPLETE`, `coverageComplete: false` and all
verification/publication flags stay unchanged. This is not candidate-change
impact, observed configuration or lifecycle, a price calculation, verified
assurance/compliance, a durable receipt or publication authority. Historical
receipts and the personal UI are unchanged. No dependencies, migrations,
provider calls, accounts or subscriptions are added.

Fresh Core publication preflight policy `catalog-publication-preflight-12` now
consumes this report for an exact stored proposal revision or bootstrap source
review. `profilePlanningCoverage` is present only when structural coverage was
actually checked; it is `null` on missing/invalid input or an unreviewed raw
bootstrap draft, without running the planning kernels. Checked reports must share
the preflight instant and the complete structural result, not merely its manifest.
Calculation failures, absent checked reports and mismatched clocks or structural
content propagate rather than falling back or producing a successful partial read.

The native consumer retains its read-only, repeatable-read transaction; its mandatory full-coverage,
curator-authorization and publication-workflow blockers are preserved. Real
database tests compare count/content digests for 25 application and audit tables,
verify the planner runs in that same transaction, and preserve historical proposal
and bootstrap receipts. No saved receipt is reinterpreted as verified planning,
and a fresh calculation does not refresh source evidence or confer permission.

A separate bounded, body-free display contract now exposes the fresh denial through
`GET /internal/v1/catalog-curator/proposals/{id}/revisions/{version}/publication-preflight`
and `GET /internal/v1/catalog-curator/bootstrap-reviews/{id}/publication-preflight`.
Both require the BFF service credential and a fresh singular curator assertion for
the configured project/organization. They accept exactly one `expectedSha256`,
no body or caller-supplied baseline/evidence/readiness. Bootstrap binds the complete
stored review digest, not the candidate digest. Missing or invalid stored inputs
return `BLOCKED` with planning absent; calculation errors propagate without fallback.
Successful reads are `no-store` and leave application/audit data unchanged.

The protected proposal review and exact stored bootstrap-review page show a shared
**Fresh publication check** panel: exact input/time, whole-candidate manual counts,
136 exercised structural dimensions, four synthetic planning case counts, all
mandatory blockers and the 40 structural/22 additional planning boundaries.
These counts are not a completion percentage or verified provider behavior. The
BFF independently checks the exact requested binding, recent clock, known inventory,
balanced counts, mandatory blockers and false authority flags within a 16 KiB body
limit. This compact display does not replay the omitted native analysis or establish
cryptographic proof from its digest. Other panels remain separate reads, not one
atomic page-wide snapshot. An unavailable fresh read shows fixed feedback and cannot
reuse a historical receipt. Curator read access is not publication-write authorization.
Ordinary users receive no curator role automatically; a real assigned-role/step-up
browser walkthrough remains pending. No approval, publication or hosted security
readiness is added, and the personal assessment/public preview are unchanged.

### Synthetic operations planning regression

`GET /internal/v1/catalog-operations-planning/regression-preflight` is a protected,
input-free internal diagnostic, not a public or personal assessment endpoint.
It replays 140 source-controlled synthetic cases: four unchanged frozen v5 profiles
with their explicit v6 auditability supplement, seven preference contexts and five
usage variants. Only operational inputs are overlaid. Every preference enum is
exercised, but this is a bounded matrix, not all possible combinations or complete
candidate-change operations/cost coverage. Unknown usage, explicit observed zero,
assumed quantities with/without assumptions and sparse usage remain distinct.
Each case retains both managed and self-hosted alternatives; alignment is not
eligibility, recorded inputs are not verified capacity, and no price is calculated.

The endpoint requires one server-only service credential, refuses body/query input
and returns `no-store` counts, exact inventories and binding digests only. Profiles,
free-text usage, identities and individual rows are not returned or persisted.
Complete ordered reports are replayed before summarization; an independent contract
check reconstructs inputs, outcomes, counts and digests from the frozen source files,
not Core output. A fresh clock changes the analysis digest, not evidence freshness
or prices. All verification, readiness, full-coverage, approval, publication and write
claims remain false; existing assessment history and coverage gates are unchanged.
No dependencies, migrations, provider calls, accounts or subscriptions are added.

### Proposed auditability supplement: exact binding before source review

`POST /internal/v1/catalog-auditability/drafts/validate` accepts a
[validation envelope](packages/contracts/schemas/catalog-auditability-draft-validation-request.v1.schema.json)
containing the unchanged `provider-catalog-draft.v1` base and a separate
[auditability draft v1](packages/contracts/schemas/catalog-auditability-draft.v1.schema.json).
It requires the server-only service credential, rejects query parameters and returns
`no-store`; it does not assert a curator identity. Neither document is saved or loaded
as evaluation evidence. No dependencies, migrations, provider accounts or paid calls
are added. The [fixture](packages/contracts/tests/fixtures/catalog-auditability-draft.valid.json)
contains fictional claims and binds the existing base draft fixture.

The supplement records its own evidence version, exact typed base content SHA-256
and base catalog version. Every base option needs one matching option ID, plan,
region and configuration, including an explicit empty facts array when unknown.
The base digest additionally binds provider, product, deployment and all original
facts. Labels are compared exactly, not treated as wildcard scopes. Up to six
identity-provider criteria use `SUPPORTED`, `UNSUPPORTED` or `UNKNOWN`, explicit
conditions and the existing HTTPS URL/date/bounded-paraphrase evidence object.
There is no caller-supplied review status. A documented retention minimum may be
null, or 0–36500 days only for supported retention; it is not a retention maximum,
customer requirement, external-sink guarantee or observed configuration.

Malformed or forged inputs return 400. Well-formed inputs produce `VALID_DRAFT`
or `INVALID_DRAFT`; invalid base content, digest/version or scope binding produces
no partial review targets. A valid [report](packages/contracts/schemas/catalog-auditability-draft-validation.v1.schema.json)
addresses every recorded claim with `auditability.<CRITERION>` and a domain-labelled
target hash binding the full supplement, exact base hash, scope and fact. The target-set
hash binds the complete inventory; changing any supplement content invalidates every
address. All collections are unordered under existing `catalog-draft-canonical-json-1`;
normalized instants preserve addresses across equivalent timestamp representations.
Changing the check clock changes freshness, not target hashes. The independent HTTP
contract check recomputes these addresses from actual responses.

Every target remains `UNREVIEWED`, including current supported claims. A valid
address is not a completed source review, remote-page hash, signature, approval or
verified provider fact. `sourceReviewWorkflowAvailable`, `sourceVerificationPerformed`,
`candidateImpactPerformed`, `approvalGranted`, `writesPerformed`, `publicationReady`,
`evaluationReady` and `recommendationReady` remain false. This v1 validation-only report
does not advertise or authorize the separate scoped curator workflow below; it is not
runtime capability discovery. Before/after auditability impact is a subsequent step. Existing proposal,
bootstrap review and publication paths do not accept or consume this supplement;
the synthetic sidecar, draft v1 hashes and historical receipts remain unchanged.

### Immutable manual auditability source reviews

`POST /internal/v1/catalog-curator/auditability-reviews` records a separate
[review request v1](packages/contracts/schemas/catalog-auditability-review-request.v1.schema.json).
It carries the full unchanged draft v1 base and auditability supplement, exact expected
base/supplement/target-set SHA-256 values, a fresh review UUID, and one explicit
`SOURCE_SUPPORTS_CLAIM`, `SOURCE_DOES_NOT_SUPPORT_CLAIM` or `INSUFFICIENT_EVIDENCE`
observation per recorded auditability fact. Each observation binds option ID, criterion
and exact target hash. `MANUAL_AUDITABILITY_SOURCE_REVIEW` is a distinct required human
confirmation. Zero recorded facts cannot produce a review; omitted criteria remain
unknown. All six possible criteria are not implicitly required in every scope.

The existing internal curator boundary requires a singular server service credential,
issuer/subject pair, `catalog_curator` role, configured project/org and authentication
within 15 minutes (at most 30 seconds ahead). The controller rechecks this boundary;
query parameters are rejected. This is a Core-only workflow, not a browser form or
hosted authentication boundary. Never expose Core through a proxy or tunnel.

Migration V19 appends the exact request and one mandatory body-free curator audit
atomically. The Core runtime role can only select/append these tables, not update/delete/truncate
history or supply database timestamps; Web has no access. A per-review transaction lock
also protects direct inserts. Equivalent unordered content under the same key by the
same actor returns the original receipt (200, no new rows); the first save returns 201.
Different content or actor under that key conflicts. Corrections use a new review UUID
and do not erase earlier observations. Database metadata does not authenticate an actor
or become curator approval; the protected current HTTP boundary remains mandatory.

`GET /internal/v1/catalog-curator/auditability-reviews/{reviewId}?expectedSha256=...`
requires the same fresh scoped assertions, one exact digest parameter and no body.
It reads a repeatable snapshot, bounds stored request bytes, replays all draft/target/
request digests, complete observations, schema/policy/counts and matching historical
audit/timing. Missing reviews return 404; wrong digests, changed-key retries and
unreadable/corrupt stored reviews return 409. No fresh calculation substitutes for a
missing or failed historical replay. Malformed/binding requests return 400; the separate
[problem contract](packages/contracts/schemas/catalog-auditability-review-problem.v1.schema.json)
does not widen historical Core problem contracts.

The `no-store` [receipt](packages/contracts/schemas/catalog-auditability-review.v1.schema.json)
contains only binding hashes, versions, counts and database recording time, not draft/
source bodies or actor identity. Independent HTTP checks recompute the hashes and
observation counts from actual requests and receipts. `sourceReviewRecorded: true`
means a human assertion was saved; source verification, fact trust change, candidate
impact, approval, catalog writes and publication/evaluation/recommendation readiness
remain false. Reviewing stale/future evidence never refreshes its observation date or
turns draft facts into reviewed evaluation evidence. Existing proposal/bootstrap reviews,
synthetic snapshots, historical receipts and publication-preflight policy are unchanged.
The separate conditional comparison below does not change these receipt semantics.

### Conditional auditability candidate-change impact

`POST /internal/v1/catalog-curator/auditability-impact/preview` compares two exact
stored auditability reviews using [request v1](packages/contracts/schemas/catalog-auditability-impact-request.v1.schema.json).
It accepts only review UUIDs and expected review hashes, not supplied drafts, actors,
evaluation time or approval claims. Current fresh scoped curator assertions are required;
query parameters are rejected. Both full stored requests and mandatory audits are replayed
in one repeatable-read snapshot. Missing reviews return 404; wrong hashes, unavailable
integrity replay or different base drafts return 409. There is no fallback to fresh
caller-supplied data. Neither side is a trusted published baseline. Keep Core local-only.

For the unchanged exact base and option scopes, the fresh
[comparison](packages/contracts/schemas/catalog-auditability-impact.v1.schema.json)
checks six audit criteria across all four explicit synthetic profiles. It shows the
bound profile requirements, before/after conditional reasons, source-verdict metadata,
claim/target hashes, original dates and freshness. `WOULD_SATISFY` and `WOULD_VIOLATE`
assume untrusted claims; they are not actual eligibility. Missing, unknown, stale or
future claims remain indeterminate; recorded conditions requiring verification also
remain indeterminate. Human supporting/contradicting/insufficient verdicts are visible
but never promote trust or alter the hypothetical assumption. Omitted profile criteria
are not applied. No source or actor bodies are returned.

For example, changing an unconditional documented retention minimum from 180 to 30 days
changes the public-sector fixture's conditional check from meeting its explicit 90-day
requirement to violating it. Changed-fact counts are unique per option/criterion;
changed-check counts include reason or reported retention-duration changes, even if
the conditional outcome stays the same. Independent HTTP checks recompute every side,
the complete scenario inventory, source bindings, counts and analysis digest.

This is a `no-store` computation, not a persisted impact receipt, source verification,
full-profile coverage, observed configuration, compliance, approval, publication or
recommendation. Existing synthetic evidence, review receipts, publication policies
and profile-coverage consumers remain unchanged and cannot infer new readiness.
No browser form, accounts, paid calls or new dependencies are added.

### Candidate auditability coverage preview

`POST /internal/v1/catalog-curator/auditability-impact/coverage-preview` accepts the
same [review references](packages/contracts/schemas/catalog-auditability-impact-request.v1.schema.json)
and scoped curator assertions as the impact preview. It replays both stored reviews
in one repeatable-read snapshot, then binds conditional candidate impact and fresh
structural profile-v6 coverage at one evaluation instant. It never accepts supplied
facts, coverage claims or evaluation time, and returns a `no-store`
[coverage report](packages/contracts/schemas/catalog-auditability-impact-coverage.v1.schema.json).

Policy `catalog-auditability-impact-coverage-1` binds both analyses, their hashes,
exact scenario/profile and option scopes, and the three auditability input paths.
Each of the four explicit synthetic profiles gets three rows per candidate option:
criticality, selected criteria and minimum retention. Rows show only selected
criteria's before/after conditional outcomes and change counts. Unselected retention
is a zero-count scope guard, never a successful check. Criteria can occur in several
dependent rows, so their counts must not be summed as unique facts.

The other 124 scenario/input rows remain structural-only; all 40 verification gaps
remain visible. Independent HTTP checks replay the original candidate requests,
derive the exact selected-input matrix and recompute all hashes. Even altered counts
with a recomputed digest are rejected by this semantic check.

`candidateAuditabilityChangesEvaluated: true` describes this bound conditional
analysis only. Status stays `INCOMPLETE`: full coverage, source/configuration/compliance
verification, durable impact recording, approval, publication and recommendation
readiness remain false. Original evidence dates are preserved. Historical coverage
responses retain their false candidate-impact flag, and existing publication gates
are unchanged. There is no browser form or new write path.

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

### Initial real-provider research drafts

The [baseline research pack](services/core-api/src/main/resources/catalog/baselines)
contains five separate draft v1 files for Entra External ID, Auth0, WorkOS, ZITADEL
Cloud and self-hosted Keycloak. Each records three initial research entries: OIDC,
SCIM and group synchronization, with official documentation URLs, observation times
and unresolved conditions. These are partial research inputs, not approved baselines.
Commercial tiers, regions and exact configurations have not been verified: all 15
entries deliberately remain `UNKNOWN` and `UNREVIEWED`. Documentation-level support
is recorded in evidence summaries, not promoted into scoped availability.

From the repository root, with contract dependencies already installed:

```shell
make inspect-provider-baselines
```

The read-only, offline command checks the fixed research pack against draft v1 and
its research-specific invariants, then prints scope, conditions, dated evidence,
freshness and omitted capabilities. It never fetches URLs, refreshes observations,
writes files or database rows, or approves/activates data. Matching an official URL
host is only an input check, not source verification. A recent observation does not
make an assertion true. Contract and Core tests protect this separation in CI.

The entries distinguish provisioning direction and integration responsibilities.
They do not equate outbound provisioning, inbound SCIM and an application-side
directory bridge. Native SCIM user support does not imply group support; missing
documentation does not mean `UNAVAILABLE`. The ZITADEL Cloud scope is separate from
the local self-hosted identity lab. Compatibility, residency, authentication controls,
auditability and costs still need evidence; omitted facts remain unknown. Existing
review preparation accepts the original draft JSON, not the inspection output, and
still requires explicit human review. No review decisions are preselected or saved.

The active catalog and evaluator remain synthetic. Real-provider baseline completion,
authorized approval/publication and end-user recommendations are still pending.
No accounts, subscriptions, live provider calls or deployments were added.

#### Schema-path inventory, not baseline readiness

The same command includes `schemaPathInventory`, constrained by a separate
[inventory v2 schema](packages/contracts/schemas/provider-baseline-inventory.v2.schema.json).
Policy `provider-baseline-schema-path-inventory-2` derives the draft v1 address
vocabulary: 9 capabilities, 19 compatibility paths, 4 residency categories and
36 human authentication-control paths. Each exact option partitions those 68
addresses into sorted `recordedPaths` and `omittedPaths`. `recordedUnknownPaths`
distinguishes explicit `UNKNOWN` proposals from omissions; proposed availability
and recorded freshness have separate aggregate counts. V2 separates capability
`proposedAvailabilityCounts` from context `proposedCompatibilityCounts` and reports
`recordedFamilyCounts`; `SUPPORTED` is not converted to `OPTIONAL`. The original
capability-only [inventory v1 schema](packages/contracts/schemas/provider-baseline-inventory.v1.schema.json)
is preserved, not silently widened or reinterpreted. Option identities and
catalog versions join back to the original scope, conditions and evidence.

The current pack has 144 recorded and 2,780 omitted option-paths across 43 distinct
scopes (2,924 possible addresses). The 82 capability proposals comprise 45 `OPTIONAL`,
3 `UNAVAILABLE` and 34 `UNKNOWN`; thirty-five separate compatibility proposals comprise
thirty-one `SUPPORTED` and four `UNKNOWN`. Fifteen authentication-control records retain
their own availability/enforcement pairs, not capability or compatibility counts.
An `UNKNOWN` in either control field appears in `recordedUnknownPaths`.
Twelve separate residency records across three scopes retain `coverage: UNKNOWN` and empty
`storageCountries`, also appearing in `recordedUnknownPaths`. They are not converted
to capability availability, compatibility support or authentication enforcement.
All remain `UNREVIEWED`. These are structural
counts, **not a completion percentage**. Addresses may be irrelevant to a particular
customer profile; omissions do not mean unsupported features or a requirement to
fill every address. Research, native and upstream scopes never borrow each other's facts.

The closed schema constrains output shape and false authority/readiness flags;
tests separately replay partitions and arithmetic against the schema and typed
Core vocabulary. Commercial entitlement, auditability, cost, lifecycle enforcement,
configuration/source verification and curator approval remain explicit unverified
boundaries outside this address inventory. `requirementCoverageEstablished`,
`fullCoverageEstablished` and `evaluationReady` remain false regardless of counts
or freshness. This output is not a draft import, review decision or activation request.

#### Release-scoped Keycloak documentation candidate

A separate [Keycloak 26.8.0 draft](services/core-api/src/main/resources/catalog/baselines/scoped/keycloak-26.8.0.v1.json)
narrows the distribution and native integration scope without changing the five
initial research files. Its OIDC, SAML, SCIM and group-provisioning assertions are
`OPTIONAL` proposals with explicit configuration prerequisites, all still
`UNREVIEWED`. `OPTIONAL` means the documented feature can be configured, not that
an application integration has passed testing. The upstream
[26.8.0 release notes](https://github.com/keycloak/keycloak/releases/tag/26.8.0)
describe native SCIM as supported. Evidence links use the exact source commit
`4246609cf2024c85016d3fb1254c3d2533367c31`, resolved from that release, rather than
mutable `latest` documentation. Pinning a source is not a signature, source-content
capture, security assessment or human approval.

The combined inspection retains the unknown Keycloak research option alongside the
scoped candidate;
it does not merge facts or inherit review status. The four new assertions remain
unreviewed even when their observations are current. Offline checks reject release,
source-path or configuration drift and unexpected coverage claims, but cannot prove
the truth of a paraphrase or execute its conditions.

The candidate concerns native inbound realm provisioning, not automatic updates to
SaaS permissions or sessions. Its conditions preserve SCIM authorization and group
membership limitations. Hosting destinations, application context, authentication
controls, auditability, commercial support and operating costs remain unverified.
This is not an installation, an upgrade recommendation or a change to AuthWeave's
ZITADEL authentication. Full baseline coverage and authorized publication remain pending.

#### Plan-scoped ZITADEL Cloud documentation candidate

A separate [ZITADEL Cloud Free draft](services/core-api/src/main/resources/catalog/baselines/scoped/zitadel-cloud-free.v1.json)
narrows the offer and native integration path, without changing the existing managed
research draft or AuthWeave's self-hosted identity lab. OIDC and SAML are proposed
`OPTIONAL` based on the application guide and [public Free offer](https://zitadel.com/pricing).
This is not an account-entitlement check, a pinned Cloud release, a deployment test or
a future zero-cost guarantee. No Cloud account or subscription was created.

SCIM remains `UNKNOWN`: the [API reference](https://zitadel.com/docs/apis/scim2) is
marked Preview, and Free-plan access and the deployed version have not been verified.
The [SCIM guide](https://zitadel.com/docs/guides/manage/user/scim2) excludes Group
provisioning. `GROUP_SYNC: UNAVAILABLE` is therefore a negative proposal limited to
that native SCIM interface **without a bridge**, not a provider-wide claim about
every API or membership integration. Inbound identity provisioning does not verify
the consuming application's permissions, deprovisioning or local-session enforcement.

`make inspect-provider-baselines` now reports forty-three distinct options and 144 recorded
entries: five unresolved research scopes, one release-scoped, four plan-scoped and
ten upstream-scoped drafts (Okta and Entra workforce for each of the five providers),
plus five public-client-scoped drafts (Keycloak, ZITADEL Cloud Free, Auth0 B2B Free,
Entra External ID Basic and WorkOS AuthKit Connect staging), plus five organization-context
drafts (ZITADEL Cloud Free, Auth0 B2B Free, primary WorkOS AuthKit staging, Keycloak 26.8.0
and Entra External ID Basic), plus five machine-client scopes
(Keycloak, ZITADEL Cloud Free, Auth0 B2B Free, WorkOS AuthKit Connect staging
and Entra External ID with the paid M2M Premium add-on), plus five browser/customer
authentication-control scopes (release-pinned Keycloak, ZITADEL Cloud Free, Auth0 B2B Free
and primary WorkOS AuthKit staging, plus Entra External ID Basic), plus three
residency scopes: release-pinned operator Keycloak, dated ZITADEL Cloud Free and
Auth0 B2B Free Public Cloud.
All remain `UNREVIEWED`; freshness cannot grant approval. The offline checker rejects
plan/deployment/source-path drift and preserves observations, but does not fetch sources
or establish their truth. Mutable Cloud documentation is dated, not falsely release-pinned.
Residency, full application compatibility, authentication controls, auditability and operating
costs remain unverified; advertised region choices do not fill storage-country evidence.
The active evaluator stays synthetic. Full baselines and authorized publication remain pending.

#### Client-scoped Keycloak OIDC documentation candidate

A separate [Keycloak public-client draft](services/core-api/src/main/resources/catalog/baselines/scoped/keycloak-26.8.0-public-oidc-clients.v1.json)
uses the same exact 26.8.0 source commit, without changing earlier research/native/broker
drafts. It records one `OIDC: OPTIONAL` proposal and two typed compatibility proposals:
`BROWSER` and `NATIVE_MOBILE: SUPPORTED`, all `UNREVIEWED`. The pinned
[grant guide](https://github.com/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/docs/guides/securing-apps/partials/oidc/supported-grant-types.adoc)
describes Authorization Code use for web/native applications. Conditions require
separate public clients, Standard Flow, S256 PKCE and narrowly registered callbacks;
a blank PKCE setting is not enforcement.

Native conditions explicitly require an external user-agent under
[RFC 8252](https://www.rfc-editor.org/rfc/rfc8252.html), not an embedded credential-capturing
WebView. Platform callbacks, token storage, SDK compatibility and application sessions
have not been tested. Machine clients, confidential BFF, application/population/tenancy
compatibility, SCIM, residency and authentication-control evidence are not inherited.
No integration, account, provider switch or approval was performed; AuthWeave still
uses ZITADEL and the active evaluator remains synthetic.

#### Browser/customer-scoped Keycloak authentication-control candidate

The separate [Keycloak authentication draft](services/core-api/src/main/resources/catalog/baselines/scoped/keycloak-26.8.0-browser-authentication-controls.v1.json)
records `MFA: OPTIONAL` and three controls limited to `BROWSER`/
`EXTERNAL_CUSTOMERS`. All remain `UNREVIEWED`, with sources pinned to the same
26.8.0 commit as the earlier candidates. Other clients/populations and the seven
earlier Keycloak scopes do not inherit these records.

[WebAuthn](https://github.com/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/docs/documentation/server_admin/topics/authentication/webauthn.adoc)
provides a conditional phishing-resistant mechanism proposal, not verified journey
enforcement: availability is `SUPPORTED`, enforcement `UNKNOWN`. Required WebAuthn,
registration, RP/origin policy and disabled weaker alternatives differ from the
default conditional/alternative 2FA flow. Enrollment, recovery, existing SSO,
credential removal and application sessions still need bypass tests.

[Passkeys](https://github.com/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/docs/documentation/server_admin/topics/authentication/passkeys.adoc)
can be synced or device-bound. Neither passkey support, required UI mediation nor
successful authentication proves non-exportable keys; both fields remain `UNKNOWN`.
Authenticator attestation/key-protection evidence and existing-credential policy
would require separate review.

[OIDC step-up](https://github.com/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/docs/documentation/server_admin/topics/authentication/flows.adoc)
proposes availability/enforcement `SUPPORTED` conditionally on coherent LoA mapping,
essential `acr` requests, explicit Max Age and validated returned claims.
Non-essential `acr_values`, SSO reuse and `acr=0` are not substitutes for checking
the required level before an application operation. The guide's OTP example does
not demonstrate phishing-resistant WebAuthn composition, and local LoA is not a
certified assurance level. No installation, accounts, flow changes, devices,
spending or live acceptance tests occurred; ZITADEL/BFF and the synthetic evaluator
remain unchanged.

#### Operator-scoped Keycloak residency documentation candidate

A separate [Keycloak operator-residency draft](services/core-api/src/main/resources/catalog/baselines/scoped/keycloak-26.8.0-operator-residency.v1.json)
records four typed categories: `USER_PROFILES`, `CREDENTIALS`, `AUDIT_LOGS` and
`BACKUPS`. All have `coverage: UNKNOWN`, empty `storageCountries` and
`UNREVIEWED` evidence pinned to the same upstream 26.8.0 source commit.
The scope is self-hosted, native local users and operator-selected database,
logging and backup destinations, excluding federation, custom providers and
hosted Keycloak services. No deployment inventory or storage country was verified.

The [database guide](https://github.com/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/docs/guides/server/db.adoc)
identifies relational user/client/realm storage. Its at-rest section includes
hashed passwords, client credentials and realm signing keys, with attention to
database files, WAL/redo and backups. Hashing and recommended encryption are not
proof of enabled encryption or country coverage. Primary storage, replicas,
snapshots, exports, caches and persisted sessions need their own inventory;
[cache behavior](https://github.com/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/docs/guides/server/caching.adoc)
does not identify an operator's at-rest destinations.

[User-event persistence](https://github.com/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/docs/documentation/server_admin/topics/events/login.adoc)
is configurable and off by default; event selection, expiration and listeners
matter. [Admin-event storage](https://github.com/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/docs/documentation/server_admin/topics/events/admin.adoc)
is separate and can include representations.
[Console, file and Syslog output](https://github.com/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/docs/guides/server/logging.adoc)
can reach different stores, including runtime-persisted console streams.
Unknown destinations do not mean no logs exist, and this residency proposal
does not verify auditing completeness, retention or immutability.

The [import/export guide](https://github.com/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/docs/guides/server/importExport.adoc)
limits realm exports: user/admin events, persisted sessions, workflow state and
revoked tokens are omitted. Consistency requires stopped nodes; that statement
does not authorize stopping an installation or running export/import. Admin
Console partial export excludes users and masks sensitive values. Neither is
evidence of complete recovery coverage. Database backups, WAL archives, snapshots,
replicas and export copies require independent destination, retention, encryption
and recovery evidence.

Explicit `UNKNOWN` is not an omitted record, `UNSUPPORTED`, absence of storage or
a data-localization/compliance guarantee. The offline inspector preserves typed
coverage/countries and rejects scope, source and claimed-country drift; it neither
fetches sources nor verifies their truth. No configuration, credentials, logs,
backup/export/restore, cloud resources, accounts, subscriptions or paid services
were changed. Earlier candidates, ZITADEL/BFF, UI and the synthetic evaluator
remain unchanged; full baselines and authorized approval/publication remain pending.

#### Machine-scoped Keycloak OAuth API documentation candidate

A separate [Keycloak machine-client draft](services/core-api/src/main/resources/catalog/baselines/scoped/keycloak-26.8.0-machine-clients.v1.json)
uses the same exact 26.8.0 source commit. It records `OAUTH2_APIS: OPTIONAL` and
`MACHINE_TO_MACHINE: SUPPORTED`, both `UNREVIEWED`. The selected configuration is
a confidential service-account client using `client_credentials` and
`client_secret_basic`; it is not user login or delegated user access.

The [service-account guide](https://github.com/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/docs/documentation/server_admin/topics/clients/oidc/proc-using-a-service-account.adoc)
requires client authentication, enabled service-account roles and explicit role-scope
mappings. Token roles are their intersection; deprecated Full Scope Allowed is not
a least-privilege shortcut. Basic client authentication must be explicitly restricted,
protected by TLS and kept server-side. Signed JWT and mTLS are separate configurations.
The documented default returns an access token without a refresh token or user session;
the example's lifetime is not a guaranteed deployment setting.

The [audience guide](https://github.com/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/docs/documentation/server_admin/topics/clients/oidc/con-audience.adoc)
requires intended API audiences and service-side validation. Requesting-client identity
or a scope string does not grant API permissions. The resource server must validate
issuer, signature, expiry, audience, permissions and resource binding. Disabling a
client or changing mappings does not establish immediate rejection of already issued
tokens. No client, credential, role assignment, deployment or live API request was
created or tested. Earlier scopes remain unchanged; AuthWeave still uses ZITADEL
and the evaluator remains synthetic.

#### Organization-scoped Keycloak documentation candidate

A separate [Keycloak organization-context draft](services/core-api/src/main/resources/catalog/baselines/scoped/keycloak-26.8.0-organization-context.v1.json)
uses the same exact upstream 26.8.0 commit. It proposes `SUPPORTED` for `B2B_SAAS`,
`PARTNER_PORTAL`, `MULTI_TENANT_ORGANIZATIONS` and `MULTIPLE_ORGANIZATIONS_PER_USER`;
all remain `UNREVIEWED`. Its one-realm scope requires
[Organizations enabled](https://github.com/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/docs/documentation/server_admin/topics/organizations/managing-organization.adoc)
and explicit unmanaged memberships. It is not realm-per-customer isolation.

The [member guide](https://github.com/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/docs/documentation/server_admin/topics/organizations/managing-members.adoc)
distinguishes account ownership: removing unmanaged membership preserves the realm
account, while removing managed membership can delete it. Switching membership
type is a privileged lifecycle change, not a harmless retry. Disabling an
organization does not necessarily disable an unmanaged user's realm login.
Non-imported LDAP users cannot use the documented organization-membership path.

Invitation acceptance is not a durable invitation-list audit: accepted records
are deleted. The guide's `/orgs` API examples must be reconciled with the pinned
release's `/organizations` route before implementation. Portal business roles
must not imply unrestricted realm administration.

The [claim guide](https://github.com/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/docs/documentation/server_admin/topics/organizations/mapping-organization-claims.adoc)
defines optional organization scopes and selection. IDs require an explicit
mapper setting; an all-membership claim is not one active tenant. The application
must validate tokens and resource permissions, including rejected switches and
missing or foreign organization context.

Capabilities, clients, populations, residency and controls stay empty; earlier
scopes and observations are unchanged. No Keycloak deployment, administrative
write, invitation, hosting purchase or live isolation test was performed.
AuthWeave still uses ZITADEL; the active evaluator remains synthetic.

#### Client-scoped ZITADEL Cloud OIDC documentation candidate

A separate [ZITADEL Cloud public-client draft](services/core-api/src/main/resources/catalog/baselines/scoped/zitadel-cloud-free-public-oidc-clients.v1.json)
records `OIDC: OPTIONAL` and `BROWSER`/`NATIVE_MOBILE: SUPPORTED` proposals, all
`UNREVIEWED`. The [application guide](https://zitadel.com/docs/guides/manage/console/applications-overview)
and [flow guide](https://zitadel.com/docs/guides/integrate/login/oidc/oauth-recommended-flows)
distinguish public User Agent/Native clients from confidential Web and API clients.
Conditions require separate registrations, exact callbacks and
[code with S256 PKCE](https://zitadel.com/docs/guides/integrate/login/oidc/login-users).

The [Free offer](https://zitadel.com/pricing) is not verified account entitlement or
a future cost guarantee. Mutable sources are dated, not release-pinned. Production
redirect/Development Mode behavior, SDKs, native callback ownership and application
sessions remain untested; external-browser use is a separate RFC 8252 prerequisite.
Earlier research/native/workforce scopes and their observations are unchanged.
No SCIM, machine-client, BFF, residency or authentication-control claims are inherited.
No account, deployment, source approval or AuthWeave authentication change was performed.

#### Browser/customer-scoped ZITADEL Cloud authentication-control candidate

The separate [ZITADEL Cloud Free authentication draft](services/core-api/src/main/resources/catalog/baselines/scoped/zitadel-cloud-free-browser-authentication-controls.v1.json)
records `MFA: OPTIONAL` and three controls for local `BROWSER`/
`EXTERNAL_CUSTOMERS` only. All remain `UNREVIEWED`; earlier ZITADEL scopes,
AuthWeave's self-hosted ZITADEL/BFF and the synthetic evaluator are unchanged.
The [Free security offer](https://zitadel.com/pricing) is not verified account
entitlement, delivery cost, deployed version or a future zero-cost guarantee.
No account, subscription, credentials or live provider calls were needed.

The [settings guide](https://zitadel.com/docs/guides/manage/console/default-settings)
distinguishes configurable MFA methods, Force MFA and local-only enforcement;
the [settings API](https://zitadel.com/docs/reference/api/settings/zitadel.settings.v2.SettingsService.GetLoginSettings)
preserves organization overrides. Neither a method toggle nor an API example
proves deployed policy. Hosted WebAuthn/passkeys provide a conditional
`PHISHING_RESISTANCE` mechanism (`SUPPORTED` availability, `UNKNOWN` enforcement),
not compulsory protection of enrollment, password fallback, recovery or SSO reuse.
The [hosted-login guide](https://zitadel.com/docs/guides/integrate/login/hosted-login)
and [Login App guide](https://zitadel.com/docs/guides/integrate/login-ui/login-app)
describe different setup limitations/features. Their discrepancy and domain-bound
credentials remain explicit, without assuming the deployed Login V2 behavior.

`NON_EXPORTABLE_KEYS` stays `UNKNOWN` in both fields. The
[custom-browser passkey guide](https://zitadel.com/docs/guides/integrate/login-ui/passkey)
is not hardware-only hosted-login evidence: authenticator selection, user
verification and example attestation do not establish key non-exportability.
`STEP_UP_AUTHENTICATION` stays `UNKNOWN` in both fields. Documented
[OIDC reauthentication parameters](https://zitadel.com/docs/apis/openidoauth/endpoints)
do not establish the ability to request and verify stronger authentication or an
essential-ACR contract; repeating the same login is insufficient. The
application must independently gate sensitive operations on sufficient fresh,
validated evidence; this draft does not implement that gate.
Neither Keycloak LoA semantics, external-IdP assurance nor other clients/populations
are inherited. Offline inspection keeps these limitations and unknown enforcement
visible without review, activation or readiness.

#### Plan-scoped ZITADEL Cloud residency documentation candidate

A separate [ZITADEL Cloud Free residency draft](services/core-api/src/main/resources/catalog/baselines/scoped/zitadel-cloud-free-residency.v1.json)
records `USER_PROFILES`, `CREDENTIALS`, `AUDIT_LOGS` and `BACKUPS`, all
`coverage: UNKNOWN`, with empty `storageCountries` and `UNREVIEWED` evidence.
It concerns native local Cloud users and provider-managed storage/logs/backups,
not AuthWeave's self-hosted lab. Customer federation, export destinations,
SIEM and custom notifications are separate configurations, not verified here.
The earlier eight ZITADEL scopes and their observations remain unchanged.

The [Free offer](https://zitadel.com/pricing) lists EU, US, Switzerland and
Australia residency choices. The [service description](https://zitadel.com/docs/legal/service-description/cloud-service-description#data-location)
defines a location as a potentially multi-country region and separately limits
transit guarantees. The [Cloud page](https://zitadel.com/zitadel-cloud) advertises
EU placement for records, auth logs and cryptographic metadata; this is not an
instance-specific enumeration of every country, replica or backup. No instance,
region, entitlement or applicable customer terms were inspected.
[Sub-processor documentation](https://zitadel.com/docs/legal/subprocessors)
also needs instance-specific destination review; region labels and egress IPs
are not complete at-rest evidence. Transit and storage are distinct boundaries,
not automatically contradictory claims or a compliance verdict.

The [general secrets guide](https://zitadel.com/docs/concepts/architecture/secrets)
distinguishes hashed/encrypted database secrets and external masterkey/TLS
material. It does not verify this Free Cloud deployment's cryptographic settings,
key protection or credential-copy countries. Encryption is not geography.

The [streaming guide](https://zitadel.com/docs/guides/integrate/external-audit-log)
distinguishes resource-change events, Actions stdout and webhook/API output.
Cloud stdout limitations do not mean events or provider logs are absent.
[Support documentation](https://help.zitadel.com/access-and-runtime-logs)
separates stdout access from subscription-dependent retrieval limits. Exact
instance retention, completeness, immutability and log destinations remain
unverified; customer-side API pulls and SIEM copies need separate evidence.

The [backup description](https://zitadel.com/docs/legal/service-description/cloud-service-description#backup),
displaying an April 5, 2024 update date, assigns Cloud backup operations to ZITADEL
and describes generic full/differential backups and recovery goals. It does not
verify current Free contractual coverage, backup destinations or measured recovery.
An API/export path is not proof of complete disaster recovery.
Sources are dated mutable documentation, not a release pin or human approval.
No account, subscription, country selection, credential/log retrieval, Action,
backup/export/restore/transfer, paid service or runtime change occurred.
The evaluator remains synthetic; full baselines and authorized publication remain pending.

#### Machine-scoped ZITADEL Cloud documentation candidate

A separate [ZITADEL machine-client draft](services/core-api/src/main/resources/catalog/baselines/scoped/zitadel-cloud-free-machine-clients.v1.json)
proposes `OAUTH2_APIS: OPTIONAL` and `MACHINE_TO_MACHINE: SUPPORTED`, both `UNREVIEWED`.
The [service-account JWT profile](https://zitadel.com/docs/guides/integrate/service-accounts/private-key-jwt)
exchanges an RS256 assertion through the JWT bearer grant for an access token.
This is not `client_credentials` with `private_key_jwt` client authentication.
The guide's overview and concrete request disagree on `client_assertion` versus
`assertion`; the discrepancy stays explicit rather than being silently reconciled.

The resource API has separate [introspection credentials](https://zitadel.com/docs/guides/integrate/token-introspection/private-key-jwt).
Grant assertion, API client assertion and returned bearer access token are distinct.
The token can be opaque or JWT; signing the grant does not establish offline token
validation. Project audience, assigned roles and service-to-resource authorization
must be checked independently; neither a scope request nor `active` alone grants access.
Administrator grants for ZITADEL management are not application API permissions.

The [Free offer](https://zitadel.com/pricing) documents service users, not verified
account entitlement or a future cost guarantee. Mutable sources are dated, not
release-pinned. No keys, accounts, grants, live token exchange, deployment or runtime
enforcement were added. Earlier scopes/dates, AuthWeave's ZITADEL/BFF configuration
and the synthetic evaluator remain unchanged; no human controls or SCIM are inherited.

#### Organization-scoped ZITADEL Cloud documentation candidate

A separate [ZITADEL organization-context draft](services/core-api/src/main/resources/catalog/baselines/scoped/zitadel-cloud-free-organization-context.v1.json)
records only four compatibility proposals: `B2B_SAAS`, `PARTNER_PORTAL`,
`MULTI_TENANT_ORGANIZATIONS` and `MULTIPLE_ORGANIZATIONS_PER_USER`, all conditional
`SUPPORTED` and `UNREVIEWED`. It has no capability assertions and borrows no
OIDC, SCIM, public-client, machine-client or workforce facts from other ZITADEL scopes.

The [B2B guide](https://zitadel.com/docs/guides/solution-scenarios/b2b) describes
vendor projects and customer grants. [Organizations](https://zitadel.com/docs/guides/manage/console/organizations-overview)
provide IAM boundaries, not verified isolation of the consuming application's
database, APIs or sessions. Login routing or a browser-selected organization
is not tenant authorization; the app must validate the subject and selected
tenant/project/grant context.

[External user role assignments](https://zitadel.com/docs/concepts/features/external-user-grant)
allow one identity to access other organizations' projects without creating
multiple home accounts. Equal email addresses can represent distinct identities;
grant removal and local-session enforcement need separate tests. Business roles
do not confer IAM manager or AuthWeave curator authority.

The [portal example](https://zitadel.com/docs/examples/login/nextjs-b2b) uses server-held
management credentials; it is not a public SPA/mobile proof or secure production
default. The [Free offer](https://zitadel.com/pricing) advertises organizations within
usage quotas, not verified administration/API entitlement or unlimited free usage.
No account, grant, portal implementation, source approval or live acceptance was added;
the active evaluator and AuthWeave authentication remain unchanged.

#### Plan-scoped Auth0 documentation candidate

A separate [Auth0 B2B Free draft](services/core-api/src/main/resources/catalog/baselines/scoped/auth0-b2b-free.v1.json)
records Public Cloud with one generic OIDC Enterprise Connection and inbound SCIM,
without a paid add-on or outbound bridge. The [Free offer](https://auth0.com/pricing)
lists inbound SCIM and one Enterprise Connection; the [B2B announcement](https://auth0.com/blog/auth0-b2b-plans-upgraded/)
identifies the offer explicitly. This is not a tenant-entitlement check, trial benefit,
cost guarantee, AuthWeave provider switch or subscription.

`OIDC`, `ENTERPRISE_SSO` and `SCIM` are `OPTIONAL` proposals, all `UNREVIEWED`.
Downstream application OIDC and upstream enterprise federation have different
sources and prerequisites. For the [generic OIDC provisioning path](https://auth0.com/docs/authenticate/protocols/scim/configure-inbound-scim-for-identity-providers-using-saml-or-openid),
the identity provider must align its ID-token `sub` with SCIM `externalId`.
Connection-specific authorization, mapping and consuming-application lifecycle/session
enforcement still require testing; native inbound SCIM does not implement an outbound bridge.

`GROUP_SYNC` remains `UNKNOWN`, not unsupported: the [SCIM guide](https://auth0.com/docs/authenticate/protocols/scim/configure-inbound-scim)
describes Group endpoints, but group-specific Free entitlement was not established.
User-only group members, separate organization/role configuration and downstream
integration are explicit limitations. SAML and the other omitted capabilities remain
unassessed, not unavailable. No region, compatibility, security-control or full cost
coverage is inferred. The fixed offline inspection retains the original unknown Auth0
research option separately; neither option is approved or activated.

#### Client-scoped Auth0 OIDC documentation candidate

A separate [Auth0 public-client draft](services/core-api/src/main/resources/catalog/baselines/scoped/auth0-b2b-free-public-oidc-clients.v1.json)
records `OIDC: OPTIONAL` and `BROWSER`/`NATIVE_MOBILE: SUPPORTED`, all `UNREVIEWED`.
Its first-party SPA/Native scope requires separate registrations, public token
authentication, exact callbacks and
[code with S256 PKCE](https://auth0.com/docs/get-started/authentication-and-authorization-flow/authorization-code-flow-with-pkce/add-login-using-the-authorization-code-flow-with-pkce).
Application-type labels alone do not prove the actual configuration.

Native conditions preserve the [callback impersonation caveat](https://auth0.com/docs/secure/security-guidance/measures-against-app-impersonation):
prefer claimed HTTPS Universal/App Links, verify ownership and retain applicable
non-verifiable-callback confirmation. PKCE is not callback-ownership evidence.
External-browser use is a separate RFC 8252 prerequisite. SDKs, browser privacy,
token storage and local sessions remain untested. Mutable sources are dated,
not release-pinned; the [Free offer](https://auth0.com/pricing) is not verified
entitlement or a future cost guarantee.

Research/native/workforce observations are unchanged; SCIM, enterprise federation,
machine/BFF/third-party clients, residency and authentication controls are not inherited.
No tenant, subscription, live integration, approval or AuthWeave provider change occurred.

#### Browser/customer Auth0 authentication-control documentation candidate

A separate [Auth0 authentication draft](services/core-api/src/main/resources/catalog/baselines/scoped/auth0-b2b-free-browser-authentication-controls.v1.json)
retains `MFA: UNKNOWN`, `PHISHING_RESISTANCE: SUPPORTED/UNKNOWN`, and
`NON_EXPORTABLE_KEYS`/`STEP_UP_AUTHENTICATION: UNKNOWN/UNKNOWN`, all `UNREVIEWED`.
It selects local `BROWSER`/`EXTERNAL_CUSTOMERS`, Universal Login and database
passkeys, without trial, paid, brokered, native, custom-database or machine inheritance.
The [Free comparison](https://auth0.com/pricing) includes passkeys but excludes
Pro MFA factors; generic MFA documentation is not verified Free entitlement.
Passkey inclusion alone does not establish the project's multifactor capability
or effective policy; `UNKNOWN` is not a provider-wide unavailable claim.

Phishing resistance is a conditional domain-bound WebAuthn mechanism, not a
complete protected journey. The [configuration guide](https://auth0.com/docs/authenticate/database-connections/passkeys/configure-passkey-policy)
retains password fallback and invitation/enrollment exceptions. Recovery, credential
removal, SSO and administrator bypasses are unverified. The
[passkey guide](https://auth0.com/docs/authenticate/database-connections/passkeys)
describes credential synchronization, not hardware-only key protection.

The [generic web step-up guide](https://auth0.com/docs/secure/multi-factor-authentication/step-up-authentication/configure-step-up-authentication-for-web-apps)
requires MFA and validated claims. Its `any` factor/remember-browser example and
silent/refresh `amr` caveats do not prove Free-plan stronger-factor availability or
fresh operation gating. Step-up means stronger authentication, not repeated login.
No tenant, account, subscription, enrollment, Action or live integration was created.
Seven earlier Auth0 scopes and their observations, runtime ZITADEL/BFF and the
synthetic evaluator are unchanged; no approval, import, activation or cost guarantee.

#### Plan-scoped Auth0 Public Cloud residency documentation candidate

A separate [Auth0 B2B Free residency draft](services/core-api/src/main/resources/catalog/baselines/scoped/auth0-b2b-free-residency.v1.json)
records `USER_PROFILES`, `CREDENTIALS`, `AUDIT_LOGS` and `BACKUPS`, all
`coverage: UNKNOWN`, empty `storageCountries` and `UNREVIEWED` evidence.
It selects native hosted-database password users, without federation, custom
databases, customer exports, log streams or Private Cloud. The earlier eight
Auth0 scopes and their observations are unchanged.

The [hosted-store guide](https://auth0.com/docs/secure/security-guidance/data-security/user-data-storage)
describes a storage mechanism; [tenant localities](https://auth0.com/docs/get-started/auth0-overview/create-tenants)
control hosting but do not enumerate every country, replica or backup. External
connection and application copies have separate boundaries. No tenant, region
or applicable customer terms were inspected; Private Cloud guarantees are not inherited.

The [export policy](https://auth0.com/docs/troubleshoot/customer-support/operational-policies/data-export-and-transfer-policy)
excludes hosted password hashes and private keys from API access. This is not
geography or non-exportable-key evidence. [Retention documentation](https://auth0.com/docs/deploy-monitor/logs/log-data-retention)
uses subscription tiers and warns of indexing delays; the [Free comparison](https://auth0.com/pricing)
advertises one day, not verified instance retention, immutability or completeness.
Generic [log streaming](https://auth0.com/docs/customize/log-streams) does not establish
Free entitlement or the countries of later customer-controlled copies.

[Official support](https://support.auth0.com/center/s/article/backup-and-restore-features-provided-by-auth0-for-tenants)
assigns Public Cloud customer backup/restore responsibility to tenant administrators.
That does not prove provider-internal DR copies are absent. [Export tools](https://support.auth0.com/center/s/article/Backup-Auth0-Data)
separate configuration from users; ordinary exports omit password hashes.
An export is not complete recovery or measured RPO/RTO. All destinations remain unverified.
No account, support ticket, subscription, export/restore, live logs, paid service,
runtime change, human approval or catalog activation occurred. Sources are dated
mutable documentation; the evaluator remains synthetic and full baselines remain pending.

#### Machine-scoped Auth0 OAuth API documentation candidate

A separate [Auth0 machine-client draft](services/core-api/src/main/resources/catalog/baselines/scoped/auth0-b2b-free-machine-clients.v1.json)
proposes `OAUTH2_APIS: OPTIONAL` and `MACHINE_TO_MACHINE: SUPPORTED`, both `UNREVIEWED`.
It selects a first-party confidential application, `client_credentials` and
[`client_secret_post`](https://auth0.com/docs/get-started/applications/credentials),
with an explicit custom API audience and requested scopes. Client authentication
is separate from the API's RS256 token signature; the client secret is not its signing key.

The [client access policy](https://auth0.com/docs/get-started/apis/api-access-policies-for-applications)
requires an explicit least-privilege client grant; user-delegated access is denied for
this machine-only API. The resource server must
[validate access tokens](https://auth0.com/docs/secure/tokens/access-tokens/validate-access-tokens)
and bind the service principal and granted scopes to its own resources. An API
Identifier, decoded JWT or successful token exchange is not resource authorization.

The [Free comparison](https://auth0.com/pricing) lists 1,000 M2M authentications, not
verified account quota, entitlement or a future cost guarantee. Custom-audience
tokens consume quota; internal Auth0 audience rules do not make a custom API free
or confer Management API permissions. Reuse valid tokens within expiry rather
than exchanging on every API call; no refresh-token or immediate-revocation promise is made.

Organization support is deliberately excluded. The
[M2M Organizations guide](https://auth0.com/docs/manage-users/organizations/organizations-for-m2m-applications)
names B2B Professional/Enterprise availability, while the public pricing table
lists Select Enterprise Plans. This discrepancy is retained; human memberships
and Free Organizations do not establish organization-scoped M2M access.
No tenant, credentials, grants, subscription or live integration was created.
Earlier scopes/dates, the synthetic evaluator and AuthWeave's ZITADEL/BFF remain unchanged.

#### Organization-scoped Auth0 documentation candidate

A separate [Auth0 organization-context draft](services/core-api/src/main/resources/catalog/baselines/scoped/auth0-b2b-free-organization-context.v1.json)
proposes four typed `SUPPORTED` contexts: `B2B_SAAS`, `PARTNER_PORTAL`,
`MULTI_TENANT_ORGANIZATIONS` and `MULTIPLE_ORGANIZATIONS_PER_USER`. All remain
`UNREVIEWED`; capabilities, clients, populations, residency and controls are empty.
It represents multiple customer organizations within one Auth0 tenant using
first-party Universal Login and explicit memberships, not multiple Auth0 tenants.
The [organization guide](https://auth0.com/docs/manage-users/organizations/organizations-overview)
requires a deliberate shared-connection identity model; equal emails or separate
connection accounts are not automatically the same identity.

The [Free offer](https://auth0.com/pricing) advertises five Organizations, not
unlimited customers. Actual B2B entitlement, connection/API quotas, paid organization
roles, self-service and optional per-application access require separate review.
No Free RBAC entitlement, trial benefit or future zero-cost guarantee is inferred.
The [membership role API](https://auth0.com/docs/api/management/v2/organizations/get-organization-member-roles)
documents multiple memberships but does not establish Free role management.

Following the [token guidance](https://auth0.com/docs/manage-users/organizations/using-tokens),
the application must validate the trusted `org_id` and segment resource access.
Membership/login is not product entitlement or database isolation. Browser selection,
email, branding and routing are not sufficient authorization. Auto-membership through
an enabled connection needs its own admission-policy review; switching, cached tokens
and session/offboarding enforcement still need live negative tests.

Partner business-admin permissions are not Dashboard, privileged Management API or
AuthWeave curator permissions. [Partner administration](https://auth0.com/docs/manage-users/organizations)
requires application work and confidential management credentials; it is not evidence
of public-client support, SCIM synchronization or secure production acceptance.
Earlier scopes and observations are unchanged. No account, membership, subscription,
live integration, approval, evaluator activation or AuthWeave provider change occurred.

#### Staging-scoped WorkOS Directory Sync candidate

The [WorkOS Directory Sync draft](services/core-api/src/main/resources/catalog/baselines/scoped/workos-directory-sync-staging.v1.json)
records staging with one Custom SCIM v2.0 directory, bearer authentication and a required
application-owned Events API/state-reconciliation bridge. It proposes only `SCIM` and
`GROUP_SYNC` as `OPTIONAL`, both `UNREVIEWED`. Login, SSO and WorkOS Connect are omitted,
not declared unsupported or inherited from the original unknown WorkOS research option.

WorkOS documents [staging connections without charges](https://workos.com/docs/authkit/environments),
while [production Directory Sync](https://workos.com/pricing) is priced per connection.
AuthKit's free user allowance is not free production Directory Sync. No account, payment
method, connector, subscription or runtime integration was created or tested. This
documentation scope is not a production entitlement, zero-cost guarantee or complete cost model.

The [custom SCIM endpoint](https://workos.com/docs/integrations/scim) belongs to WorkOS;
the app reads directory data and must implement lifecycle, permissions and session
enforcement. It neither exposes a native SaaS SCIM endpoint nor writes back upstream.
An [Events API consumer](https://workos.com/docs/events/data-syncing/events-api) must
retain its cursor, tolerate replay and reconcile state; no latency or enforcement was measured.

The [directory event lifecycle](https://workos.com/docs/directory-sync/understanding-events)
requires explicit group-deletion cleanup without individual membership-removal events.
Membership changes do not advance a user's `updated_at`; use paginated membership
queries rather than the deprecated user `groups` field. Directory removal of an
[inactive user](https://workos.com/docs/directory-sync/handle-inactive-users) is not
automatic deletion from the SaaS. Other memberships and local sessions need their own
policy. Compatibility, residency, authentication controls and authorized publication remain pending.

#### Client-scoped WorkOS Connect OIDC documentation candidate

A separate [WorkOS Connect public-client draft](services/core-api/src/main/resources/catalog/baselines/scoped/workos-connect-staging-public-oidc-clients.v1.json)
selects first-party Public OAuth applications in staging, not Directory Sync or
primary AuthKit authentication. It records `OIDC: OPTIONAL`, `NATIVE_MOBILE: SUPPORTED`
and `BROWSER: UNKNOWN`, all `UNREVIEWED`. [Connect metadata](https://workos.com/docs/reference/workos-connect/metadata)
is a documentation example, not an observed issuer or enforcement test.

The [Public application branch](https://workos.com/docs/authkit/connect/oauth) describes
PKCE for clients that cannot keep secrets. Conditions require code/S256, exact
callbacks, no embedded credentials and an external user-agent for mobile login.
The generic [token reference](https://workos.com/docs/reference/workos-connect/token)
lists `client_secret`; the public exchange contract must be reconciled before
source approval. Reviewed sources did not establish Connect-specific SPA token
exchange/CORS. Browser `UNKNOWN` records this uncertainty, not incompatibility or
an omitted path; mobile context and generic AuthKit React/CORS do not fill that gap.

Use the selected environment's Connect issuer/discovery and `/oauth2` endpoints,
not primary AuthKit `/user_management` authentication or session tokens. Directory
Sync provisioning, organization/membership compatibility, API/M2M grants, BFF,
third-party consent, residency and authentication controls are not inherited.
Free [staging](https://workos.com/docs/authkit/environments) is not production
Connect entitlement. No account, billing, SDK, callback or live exchange was verified;
AuthWeave's ZITADEL/BFF and synthetic evaluator remain unchanged.

#### Browser/customer WorkOS AuthKit staging authentication-control candidate

A separate [WorkOS authentication draft](services/core-api/src/main/resources/catalog/baselines/scoped/workos-authkit-staging-browser-authentication-controls.v1.json)
proposes `MFA: OPTIONAL`, `PHISHING_RESISTANCE: SUPPORTED/UNKNOWN` and
`NON_EXPORTABLE_KEYS`/`STEP_UP_AUTHENTICATION: UNKNOWN/UNKNOWN`, all `UNREVIEWED`.
Its local `BROWSER`/`EXTERNAL_CUSTOMERS` scope uses primary hosted AuthKit, not Connect,
Directory Sync, native clients, brokered workforce or the standalone SMS MFA API.
[Hosted MFA](https://workos.com/docs/authkit/mfa) uses TOTP, excludes SSO users and
does not by itself prove the effective required policy.
[Organization MFA](https://workos.com/docs/authkit/organization-policies) is context-bound.

[Passkey](https://workos.com/docs/authkit/passkeys) availability is a conditional
WebAuthn mechanism, not a passkey-only journey. User verification can satisfy MFA;
enrollment can be skipped and weaker alternatives/recovery need independent review.
[Staging](https://workos.com/docs/authkit/environments) uses WorkOS domains and is
testing-only; production custom domains/billing are outside this scope.
The [Widgets API](https://workos.com/docs/widgets-api/authentication) documents separate
authenticated credential-management operations. Its token labels and opaque registration
options do not verify a hardware-only key policy or stronger authentication.

The [reauthentication guide](https://workos.com/docs/authkit/reauthentication) documents
`max_age` and `auth_time`, but AuthKit can choose password re-entry. Freshness is not
proof of a stronger factor under this project's step-up definition. Signed claims,
factor evidence and a fail-closed sensitive-operation gate remain unverified.
No account, subscription, environment, credential, hardware or live integration was
created. Seven older WorkOS scopes and observations, runtime ZITADEL/BFF and the
synthetic evaluator are unchanged. No approval, import, activation or future cost guarantee.

#### Machine-scoped WorkOS Connect staging candidate

A separate [WorkOS Connect machine-client draft](services/core-api/src/main/resources/catalog/baselines/scoped/workos-connect-staging-machine-clients.v1.json)
proposes `OAUTH2_APIS: OPTIONAL` and `MACHINE_TO_MACHINE: SUPPORTED`, both `UNREVIEWED`.
The [M2M guide](https://workos.com/docs/authkit/connect/m2m) permits third-party applications
bound to a customer/partner organization, not first-party background services.
The selected `client_credentials` exchange uses `client_secret_post` on a trusted
backend; its application secret is not a WorkOS management API key.

The [claims guide](https://workos.com/docs/authkit/connect/token-claims) fixes the M2M
audience to the environment client ID, not the requesting application's client ID
or a configurable per-application API resource. Require a verified issuer, trusted
environment JWKS, allowed algorithm, expiry, machine identity, expected `org_id`
and granted scopes, then check application-owned resource permissions. A signed
organization claim is not tenant isolation, user membership or administrative authority.
M2M tokens do not inherit user-token consent, JWT templates or custom claims.

Assign explicit least-privilege scopes using the
[application reference](https://workos.com/docs/reference/workos-connect/applications);
requested scopes are not proof of the returned grant. Preserve documentation gaps:
the [OpenID metadata example](https://workos.com/docs/reference/workos-connect/metadata)
includes `client_credentials`, but its OAuth metadata example omits it;
the [token reference](https://workos.com/docs/reference/workos-connect/token)
types `org_id` as optional despite the organization-bound guide;
the [introspection example](https://workos.com/docs/reference/workos-connect/introspection)
uses `/oauth2/introspection`, but its typed heading says `/oauth2/token`.
Reconcile these before approval or live integration. No introspection credential
policy, immediate JWT invalidation or deployed token lifetime is asserted.

[Free staging](https://workos.com/docs/authkit/environments) is testing-only,
not a production M2M entitlement, quota or future cost guarantee.
No account, credentials, application, organization, billing or live calls were created.
Directory Sync, primary AuthKit login, human compatibility, residency and controls
are not inherited. Earlier scopes/dates, AuthWeave's ZITADEL/BFF and synthetic evaluator
remain unchanged.

#### Organization-scoped WorkOS AuthKit staging candidate

A separate [WorkOS organization-context draft](services/core-api/src/main/resources/catalog/baselines/scoped/workos-authkit-staging-organization-context.v1.json)
proposes `B2B_SAAS`, `PARTNER_PORTAL`, `MULTI_TENANT_ORGANIZATIONS` and
`MULTIPLE_ORGANIZATIONS_PER_USER` as conditional `SUPPORTED`, all `UNREVIEWED`.
This is primary AuthKit, not Connect or Directory Sync. Capabilities, clients,
populations, residency and controls stay empty; earlier scopes and dates are unchanged.

[Organizations](https://workos.com/docs/authkit/users-organizations) model customer
workspaces, while the [membership reference](https://workos.com/docs/reference/authkit/organization-membership)
uses environment-scoped users and explicit statuses. Require active membership;
pending/inactive is not access. Listing defaults to active records, not a complete
audit. Creating a membership can reactivate it; reactivation retains roles.
Those writes need explicit authorization and role review, not automatic retry.
Documented provider-session effects are not measured cached-JWT or local-session
enforcement. Live switching/offboarding and tenant-isolation acceptance remain pending.

[Invitations](https://workos.com/docs/authkit/invitations) distinguish organization
membership from application-wide signup. Corporate-domain organization invitations
can be accepted by another address in that domain; do not infer exact-recipient
approval. Partner admission, business permissions and privileged management credentials
remain application responsibilities, not WorkOS workspace or AuthWeave curator authority.

[Session switching](https://workos.com/docs/authkit/sessions) requires an authorized
organization context; resource access must still be checked. Reconcile the guide's
HTTP JWKS example and issuer spelling with the
[HTTPS/client-specific reference](https://workos.com/docs/reference/authkit/session-tokens)
before source approval; neither is Connect token evidence.
Missing or mismatched `org_id`, routing and email do not authorize a tenant.

[Staging](https://workos.com/docs/authkit/environments) is testing-only, with separate
users, organizations and keys from production. Free staging and the
[AuthKit allowance](https://workos.com/pricing) do not establish free production SSO,
Directory Sync or unlimited operating capacity. No account, invitation, membership
mutation, billing setup, runtime provider switch, source approval or activation occurred.

#### Browser/customer Entra External ID authentication-control candidate

The separate [Entra authentication draft](services/core-api/src/main/resources/catalog/baselines/scoped/entra-external-id-basic-browser-authentication-controls.v1.json)
selects standard external-tenant local password accounts, not workforce guests,
Azure AD B2C, native authentication APIs, federation or paid M2M. `MFA: OPTIONAL`
is a configurable proposal, not observed compulsory use. All four records are
`UNREVIEWED`; earlier scopes and their evidence dates remain unchanged.

[External-tenant MFA](https://learn.microsoft.com/en-us/entra/external-id/customers/concept-multifactor-authentication-customers)
can use password plus email OTP; OTP as first factor cannot also serve as second
factor. SMS charges are excluded. Generic MFA does not establish phishing resistance.
[Customer passkeys](https://learn.microsoft.com/en-us/entra/external-id/customers/how-to-sign-in-with-passkey)
conditionally support that mechanism, but external-tenant Conditional Access cannot
require phishing-resistant MFA through authentication strengths. The pair is
`SUPPORTED/UNSUPPORTED`, with the negative limited to that policy route, not every
possible application-owned control. Passkeys need a custom URL domain and
app-built credential management. [The documented Front Door route](https://learn.microsoft.com/en-us/entra/external-id/customers/concept-custom-url-domain)
incurs separate charges; included passkey authentication is not free infrastructure.

`NON_EXPORTABLE_KEYS: UNKNOWN/UNKNOWN` preserves hardware-proof gaps.
[Passkey profiles](https://learn.microsoft.com/en-us/entra/identity/authentication/how-to-enable-passkey-fido2),
referenced by the external-tenant guide, permit type/attestation/AAGUID restrictions.
Device-bound labeling and model attestation are not verified hardware key protection;
registration-only attestation changes do not reject earlier unattested credentials.
No workforce authentication-strength or Authenticator behavior is imported.

`STEP_UP_AUTHENTICATION: SUPPORTED/UNKNOWN` is conditional password-to-MFA elevation
at a sensitive operation. [Authentication context](https://learn.microsoft.com/en-us/entra/identity-platform/developer-guide-conditional-access-authentication-context)
uses a claims challenge and exact `acrs` mapping, but an unprotected context can also
produce the claim. Effective policy, signed-token/session binding and factor freshness
must be checked at the server gate. The shared guide's P1/Free-edition wording needs
reconciliation with external-tenant feature documentation; Basic entitlement remains
unverified. No tenant, subscription, paid domain, Azure resource, key, policy change
or live test was created. ZITADEL/BFF, UI and the synthetic evaluator are unchanged.

#### Paid-add-on-scoped Entra External ID machine-client candidate

The separate [Entra M2M draft](services/core-api/src/main/resources/catalog/baselines/scoped/entra-external-id-m2m-addon-machine-clients.v1.json)
proposes `OAUTH2_APIS: OPTIONAL` and `MACHINE_TO_MACHINE: SUPPORTED`, both
`UNREVIEWED`. The [external-tenant overview](https://learn.microsoft.com/en-us/entra/external-id/customers/overview-customers-ciam)
requires the M2M Premium add-on; [billing](https://learn.microsoft.com/en-us/entra/external-id/external-identities-pricing)
is transaction-based, separate from Basic MAU. Free interactive-user allowances do
not establish free machine authentication. This is a comparison candidate, not a
billing setup or a proposal to change AuthWeave's ZITADEL/BFF authentication.

The selected confidential backend uses `client_credentials`/`client_secret_post`
and one custom API's `/.default`, with preassigned application roles and admin consent,
not delegated user permissions or dynamically narrowed roles. Certificates, federated
credentials, ACL-only authorization and generic workforce/common endpoints are outside
scope. The API explicitly selects v2 access tokens; a v2 endpoint alone does not
select their version. Trusted metadata/signature, exact issuer, API audience, lifetime,
external-directory `tid`, registered `azp`, configured `idtyp=app` and required roles
must be checked. Missing app-only identity or role-less tokens fail closed.
The directory `tid` is not a customer organization ID: resource authorization and
customer isolation remain application-owned and untested.

Cloud observations are dated, not release-pinned. The six earlier Entra scopes and
their evidence dates are unchanged; no SCIM, human membership, MFA or residency is
inherited. No account, tenant, subscription, credentials, grants, payment or live
exchange was created. Exact entitlement/cost and cached-token offboarding behavior
need separate review; paid testing requires a separate owner decision.

#### Plan-scoped Entra External ID Basic candidate

The [Entra Basic draft](services/core-api/src/main/resources/catalog/baselines/scoped/entra-external-id-basic.v1.json)
selects a standard-mode external tenant without paid inbound SCIM add-ons or a Graph
bridge, not workforce guest access, legacy Azure AD B2C or a trial. Its downstream
OIDC/SAML assertions are proposed `OPTIONAL`; inbound SCIM and group synchronization
remain `UNKNOWN`, not declared unsupported. All four entries are `UNREVIEWED`.
The original research option remains separate and unchanged; upstream SSO is omitted.

[Basic pricing](https://azure.microsoft.com/en-us/pricing/details/microsoft-entra-external-id/)
includes an initial MAU allowance, not a guarantee of free operation. No tenant,
subscription, entitlement check or runtime integration was created. For
[OIDC customer login](https://learn.microsoft.com/en-us/entra/external-id/customers/concept-supported-features-customers),
the tenant authority, app registration and user flow must be configured. The
[SAML guide](https://learn.microsoft.com/en-us/entra/external-id/customers/how-to-register-saml-app)
distinguishes an administrator's portal test from the customer's actual application login.

The separate [inbound SCIM API](https://learn.microsoft.com/en-us/entra/identity/app-provisioning/enable-scim-api)
has paid prerequisites outside this scope; its applicability here is not established.
[Standard-mode outbound SCIM](https://learn.microsoft.com/en-us/entra/external-id/customers/reference-service-limits)
must not be mistaken for inbound provisioning; HSC mode excludes that outbound feature.
[Graph group management and role assignments](https://learn.microsoft.com/en-us/entra/external-id/customers/reference-group-app-roles-support)
do not prove native inbound SCIM Group lifecycle or application access/session enforcement.
Full commercial, compatibility, residency and control coverage, manual review and
authorized catalog publication remain pending; the active evaluator stays synthetic.

#### Client-scoped Entra External ID OIDC documentation candidate

A separate [Entra public-client draft](services/core-api/src/main/resources/catalog/baselines/scoped/entra-external-id-basic-public-oidc-clients.v1.json)
proposes `OIDC: OPTIONAL` and `BROWSER`/`NATIVE_MOBILE: SUPPORTED`, all `UNREVIEWED`.
Its standard external-tenant scope uses public code/S256 clients, no client secret,
tenant-specific `ciamlogin.com` authority and one associated customer user flow per
registration. [External-tenant account types](https://learn.microsoft.com/en-us/entra/external-id/customers/concept-supported-features-customers)
are single-directory; this is not workforce/B2C or a tenant entitlement check.
S256 is a client prerequisite, not proof of S256-only server enforcement.

The [external-tenant SPA tutorial](https://learn.microsoft.com/en-us/entra/identity-platform/tutorial-single-page-app-javascript-prepare-app)
is distinct from Web/BFF registration. A redirect's `spa` type enables the
[protocol's CORS handling](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow);
exact callbacks, OIDC validation, browser storage and interactive fallback need review.

For mobile clients, select an external user-agent under RFC 8252 and verify platform
callback handling. [Browser-delegated mobile login](https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-mobile-app-call-api)
is not Microsoft's separate native-authentication UI/API approach. The quickstart's
account-type/trial wording and public-client toggle must be reconciled with the
[external-tenant planning guide](https://learn.microsoft.com/en-us/entra/external-id/customers/concept-planning-your-solution)
before approval; no broad toggle requirement or tested mobile SDK is inferred.

Mutable sources and the Basic MAU allowance do not prove deployment or free operation.
Previous scopes/dates are unchanged; SCIM, brokers, BFF/M2M/API grants, residency and
authentication controls are not inherited. No accounts, grants, subscriptions,
approval or AuthWeave authentication configuration changed.

#### Organization-scoped Entra External ID documentation candidate

A separate [Entra organization-context draft](services/core-api/src/main/resources/catalog/baselines/scoped/entra-external-id-basic-organization-context.v1.json)
proposes `B2B_SAAS: SUPPORTED` only as business-customer application context.
`PARTNER_PORTAL`, `MULTI_TENANT_ORGANIZATIONS` and
`MULTIPLE_ORGANIZATIONS_PER_USER` remain explicit `UNKNOWN` proposals, not
`UNSUPPORTED` features. All four records are `UNREVIEWED`.

The [CIAM overview](https://learn.microsoft.com/en-us/entra/external-id/customers/overview-customers-ciam)
describes an external customer directory, registered apps and customer user flows.
It does not establish the selected application's organization or membership model.
Single-directory registration is not proof that application multi-tenancy is
unsupported; [external and workforce tenants](https://learn.microsoft.com/en-us/entra/external-id/tenant-configurations)
are different identity-directory configurations.

The [admin guide](https://learn.microsoft.com/en-us/entra/external-id/customers/how-to-manage-admin-accounts)
limits guest invitations to administration, excluding customer CIAM user flows.
The broader invitations wording in the
[feature table](https://learn.microsoft.com/en-us/entra/external-id/customers/concept-supported-features-customers)
must not become partner admission or business-admin delegation.
A custom portal needs a separately reviewed application-owned enrollment and
organization mapping; no guest invitation or directory-role assignment was made.

[Groups and app roles](https://learn.microsoft.com/en-us/entra/external-id/customers/reference-group-app-roles-support)
are management primitives, not verified customer-organization memberships.
The support table's Graph-only paths and the
[RBAC guide's portal procedures](https://learn.microsoft.com/en-us/entra/external-id/customers/how-to-use-app-roles-customers)
need reconciliation against the actual tenant. No Graph bridge or complete group
claim is assumed. The [token reference](https://learn.microsoft.com/en-us/entra/identity-platform/id-token-claims-reference)
defines directory `tid` and app-specific subjects, not the application's customer
organization. Email and self-entered attributes do not authorize access to a SaaS
customer tenant; validate tokens and bind a trusted user to the application's
organization and resource permissions.

Facts, clients, populations, residency and controls stay empty; earlier Entra scopes
and observations are unchanged. Mutable Cloud evidence and a Basic MAU allowance
are not account entitlement or a zero-cost guarantee. No account, subscription,
membership write or live isolation test was performed. AuthWeave still uses ZITADEL;
the active evaluator remains synthetic and full baselines/publication remain pending.

#### Upstream workforce compatibility candidates for Auth0

Two separate documentation candidates use the existing draft format:
[Okta Workforce into Auth0](services/core-api/src/main/resources/catalog/baselines/scoped/auth0-b2b-free-upstream-okta.v1.json)
and [Entra ID workforce into Auth0](services/core-api/src/main/resources/catalog/baselines/scoped/auth0-b2b-free-upstream-entra.v1.json).
They propose `ENTERPRISE_SSO` as `OPTIONAL`; pair-specific SCIM and group availability
remain `UNKNOWN` pending entitlements and configuration checks. Every entry is
`UNREVIEWED`. Downstream OIDC/SAML and other provider pairings are omitted, not inferred.
Neither candidate changes AuthWeave's ZITADEL authentication or the synthetic evaluator.

The [dedicated Okta connection](https://auth0.com/docs/authenticate/identity-providers/okta)
is not the existing generic OIDC candidate or OIN Express Configuration. Its
[manual provisioning guide](https://auth0.com/docs/authenticate/protocols/scim/inbound-scim-for-okta-workforce-connections)
uses separate login/provisioning apps; application assignment alone is not Group Push.
The [dedicated Entra connection](https://auth0.com/docs/authenticate/identity-providers/enterprise-identity-providers/azure-active-directory/v2)
uses a workforce tenant, not Entra External ID. Its
[new-connection SCIM guide](https://auth0.com/docs/authenticate/protocols/scim/inbound-scim-for-new-azure-ad-connections)
correlates `oid/objectId` with `externalId`, unlike generic OIDC or legacy `sub` mapping.

[Auth0 B2B Free inclusion](https://auth0.com/pricing) does not establish free upstream
workforce provisioning. No accounts, paid plans, credentials or runtime integrations
were created. These routes provision into Auth0; downstream application permissions,
local sessions and group enforcement remain separate. Existing drafts retain their
original observations and do not transfer positive facts into these new scopes.
The offline inspector labels the pairings `UPSTREAM_SCOPED_DOCUMENTATION_DRAFT`,
but neither tests live interoperability nor approves facts. Remaining provider
pairings, full coverage, manual review and authorized publication are still pending.

#### Upstream-scoped ZITADEL workforce candidates

Separate [Okta](services/core-api/src/main/resources/catalog/baselines/scoped/zitadel-cloud-free-upstream-okta.v1.json)
and [Entra](services/core-api/src/main/resources/catalog/baselines/scoped/zitadel-cloud-free-upstream-entra.v1.json)
drafts select org-scoped ZITADEL Cloud Free login: generic OIDC for Okta and a fixed
workforce Tenant ID for the Microsoft template. `ENTERPRISE_SSO` and login-time `JIT`
are `OPTIONAL` proposals; neither changes AuthWeave's actual authentication.

The [provider settings](https://zitadel.com/docs/guides/integrate/identity-providers/introduction)
describe opt-in creation and profile updates at login, not background provisioning.
`SCIM` remains `UNKNOWN`: the [Okta provisioning guide](https://zitadel.com/docs/guides/integrate/scim-okta-guide)
assumes SAML, not the selected OIDC pair, and the [Preview API](https://zitadel.com/docs/apis/scim2)
does not establish this Entra pairing, Cloud release or commercial access.
Native inbound `GROUP_SYNC` is proposed `UNAVAILABLE` only for the
[User-only SCIM interface](https://zitadel.com/docs/guides/manage/user/scim2), without a bridge.

All eight entries are `UNREVIEWED`. Identity correlation, email trust, upstream
entitlements and SaaS lifecycle/session enforcement still need review and tests.
The original Free and research options retain their own observations; no facts are
inherited across configurations. Offline scope/source checks, typed validation and
HTTP regression coverage do not verify sources or activate recommendations.
Remaining pairings, complete baseline coverage and authorized publication stay open.

#### Release-and-upstream-scoped Keycloak workforce candidates

Separate [Okta](services/core-api/src/main/resources/catalog/baselines/scoped/keycloak-26.8.0-upstream-okta.v1.json)
and [Entra](services/core-api/src/main/resources/catalog/baselines/scoped/keycloak-26.8.0-upstream-entra.v1.json)
drafts select a realm-scoped generic OIDC broker in Keycloak 26.8.0. They propose
`ENTERPRISE_SSO` and first-login `JIT` as `OPTIONAL`, not certified or runtime-tested
pair compatibility. All eight entries remain `UNREVIEWED`; no deployment or account
was created and AuthWeave's ZITADEL authentication is unchanged.

The [pinned broker guide](https://github.com/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/docs/documentation/server_admin/topics/identity-broker/oidc.adoc)
documents code-flow, issuer and signature checks. The selected upstream scopes are
an [Okta org authorization server](https://developer.okta.com/docs/concepts/auth-servers/)
and [one Entra workforce tenant's v2 authority](https://learn.microsoft.com/en-us/entra/identity-platform/v2-protocols-oidc),
not interchangeable custom issuers, tenant selectors, External ID or B2C.
Only Keycloak documentation is release-pinned; upstream documentation is mutable and dated.
Hosting costs, commercial support and upstream entitlements are unverified.

The [first broker login flow](https://github.com/keycloak/keycloak/blob/4246609cf2024c85016d3fb1254c3d2533367c31/docs/documentation/server_admin/topics/identity-broker/first-login-flow.adoc)
can create a unique local account or require proof when linking an existing one.
JIT is not background provisioning. Pair-specific `SCIM` and `GROUP_SYNC` remain
`UNKNOWN`: native realm SCIM support and login-time claim mappers do not prove
identity correlation, group lifecycle or consuming-app authorization/session enforcement.
The original native and research options retain their observations and availability;
no positive facts are inherited. Offline inspection and typed tests neither verify
interoperability nor approve or activate these proposals. Full coverage and publication remain pending.

#### Upstream-scoped WorkOS Directory Sync staging candidates

Separate [Okta](services/core-api/src/main/resources/catalog/baselines/scoped/workos-directory-sync-staging-upstream-okta.v1.json)
and [Entra](services/core-api/src/main/resources/catalog/baselines/scoped/workos-directory-sync-staging-upstream-entra.v1.json)
drafts propose `SCIM` and `GROUP_SYNC` as `OPTIONAL` for organization-bound WorkOS
staging directories with bearer authentication and an app-owned Events API bridge.
All four entries are `UNREVIEWED`. Login, JIT, nested-group coverage, production
entitlement and measured access revocation are not inferred. AuthWeave is not connected
to WorkOS and still uses ZITADEL; no account, subscription or integration was created.

The [Okta guide](https://workos.com/docs/integrations/okta-scim) distinguishes
assignment groups from Push Groups and suspension from deactivation; group identifiers
use display names, and membership-removal gaps need recovery tests. The
[Entra guide](https://workos.com/docs/integrations/entra-id-scim) requires explicit
provisioning mappings/scope and describes membership restoration after soft deletion.
Scheduled or on-demand provisioning is not proof of immediate SaaS session revocation.

[Staging is not billed](https://workos.com/docs/authkit/environments), but this does not
establish upstream provisioning entitlement or free production Directory Sync.
The required app bridge must handle replay, reconcile state and enforce local access;
[group deletion](https://workos.com/docs/directory-sync/understanding-events) does not
emit individual member-removal events. These mutable, dated sources are not release pins
or live interoperability evidence. Original research/generic observations remain unchanged;
offline scope checks do not approve or activate facts. Full coverage and publication remain pending.

#### Upstream-scoped Entra External ID Basic candidates

Separate [Okta](services/core-api/src/main/resources/catalog/baselines/scoped/entra-external-id-basic-upstream-okta.v1.json)
and [Entra workforce](services/core-api/src/main/resources/catalog/baselines/scoped/entra-external-id-basic-upstream-entra.v1.json)
drafts select standard external-tenant browser OIDC and first-sign-up account creation.
`ENTERPRISE_SSO`/`JIT` are proposed `OPTIONAL`; `SCIM`/`GROUP_SYNC` remain `UNKNOWN`,
all eight entries `UNREVIEWED`. This is not workforce guest redemption, B2C or a
change to AuthWeave's ZITADEL login; no cloud tenant, account or integration was created.

The Okta pairing is a protocol-level inference, not a tested dedicated connector.
Its [org issuer](https://developer.okta.com/docs/concepts/auth-servers/) is separate
from `/oauth2/default` and does not supply SaaS API access tokens. Explicit
[client authentication](https://developer.okta.com/docs/api/openapi/okta-oauth/guides/client-auth)
must agree with [External ID's methods](https://learn.microsoft.com/en-us/entra/external-id/customers/how-to-custom-oidc-federation-customers):
the draft selects `client_secret_post`, not Okta's default Basic method or the
displayed-but-unsupported `private_key_jwt` option. The
[workforce guide](https://learn.microsoft.com/en-us/entra/external-id/customers/how-to-entra-id-federation-customers)
uses organizational discovery with a fixed tenant issuer; upstream MFA does not
automatically transfer assurance to the external tenant.

[Claim mappings](https://learn.microsoft.com/en-us/entra/external-id/customers/reference-oidc-claims-mapping-customers),
email verification and the workforce/generic guides' differing email instructions
need review before live sign-up. First-sign-up creation does not prove repeat-login
updates, account linking or offboarding. The separately billed
[SCIM server](https://learn.microsoft.com/en-us/entra/identity/app-provisioning/enable-scim-api)
is outside this Basic/no-add-on scope; [group administration](https://learn.microsoft.com/en-us/entra/external-id/customers/reference-group-app-roles-support)
does not prove upstream Group lifecycle sync. Neither `UNKNOWN` means `UNAVAILABLE`.
Original research/native observations remain unchanged. The ten-pair inventory is
not complete compatibility coverage, source approval or live interoperability;
the active evaluator remains synthetic and authorized publication stays closed.

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

Flyway V12 reserves separate append-only storage for a human `REJECTED` decision and
its audit event, bound to one exact proposal revision and digest. Both rows must commit
together. The database rejects `APPROVED`, unknown reason codes, stale authentication
times, service actors and duplicate decisions for the same revision. Core runtime may
insert only the required fields; database-generated timestamps cannot be overridden,
history cannot be updated or deleted, and the web database role has no access. The
audit row contains actor identifiers and scope IDs, but no free-text rationale, source
content, tokens or cookies. Database constraints alone cannot verify that a human
actually authenticated. The BFF now exposes a same-origin, session-bound POST at
`/api/catalog-change-proposals/{id}/rejection`; Core accepts only a credentialed BFF
assertion with the exact configured curator role/scope and authentication within 15
minutes. The request binds the current version and SHA-256, then inserts the rejection
and audit event in one transaction. A duplicate, stale version or changed digest fails
with 409. A curator-gated proposal review screen at `/catalog/review` now lists up to
20 current-revision summaries per page, newest first, with an older-page cursor.
The index includes only IDs, versions, digests, timestamps and whether a rejection
was recorded for that revision; it never includes proposal bodies or evidence.
The screen also accepts a proposal UUID and displays its stored semantic option/fact
changes, caller-supplied provenance as unverified text, revision and digest. For
displayed fact changes, a read-time 90-day observation-date cue marks old or future
dates without fetching or verifying sources. A separate candidate evidence list
includes all recorded candidate facts, including unchanged facts, with the product,
plan, region, configuration, submitted conditions and source paraphrase. Its v2
response also includes the exact typed claim: capability availability, context support,
storage coverage/countries, or separate authentication availability and enforcement.
The screen labels these values as submitted and unverified; unknown is not unsupported,
and partial storage coverage does not rule out other countries. Core checks
the stored request digest and applies its current date policy to that exact revision.
The list returns 20 rows per page, with observation-date counts for the whole candidate
and an explicit evaluation time for each page. Missing facts remain unknown and all
recorded sources remain unreviewed. It reads the current rejection decision separately
and shows the latest
stored conditional scenario impact report for that exact revision, if one exists.
Its evaluated time, rule/case-set versions,
scenario outcomes and uncovered changes are historical; no analysis is rerun and
incomplete coverage is not treated as approval. For proposal decisions, the screen can submit only the
protected rejection action after an explicit version-bound confirmation. It does not
verify sources or grant roles; no role is granted by setup. A missing rejection does
not mean approval. There is still no approval, active catalog
change or published state; existing proposal reads continue to report the immutable
snapshot as `PROPOSED`, with rejection stored separately.

Migration V13 reserves append-only storage for a curator's source-review verdict on one
recorded candidate fact: `SOURCE_SUPPORTS_CLAIM`, `SOURCE_DOES_NOT_SUPPORT_CLAIM`, or
`INSUFFICIENT_EVIDENCE`. Each observation binds the exact proposal revision/digest,
option ID and fact path and must commit with a matching minimal curator audit event.
Corrections append another observation; existing history cannot be rewritten by runtime
roles. PostgreSQL checks that the fact exists in the current unrejected revision and
assigns a per-revision review number while holding the proposal head lock shared by
Core revision/rejection writers. Runtime roles cannot supply the number or timestamps;
the web role cannot read or insert these records. Core now accepts a protected
`POST /api/v1/catalog-change-proposals/{proposalId}/fact-reviews`, forwarded by the
same-origin BFF JSON endpoint `/api/catalog-change-proposals/{id}/fact-reviews`.
Both boundaries require the configured curator scope and authentication within 15 minutes;
the browser cannot supply the audit actor. A request supplies a UUID `reviewId`, expected
revision and digest, option ID, fact path, verdict and `MANUAL_SOURCE_REVIEW` confirmation.
Core revalidates the immutable candidate and digest before a new write. The same key,
payload and actor return the original receipt (200 rather than 201) without another
audit event, even after head changes or rejection; authorization must still be fresh.
Reusing a key for another payload or actor conflicts. New observations on stale, rejected
or absent fact targets fail closed. No source-review CLI is available.
Database identity/freshness constraints do not prove an OIDC login. A human verdict is
not automatic source verification, evidence freshness,
approval or catalog publication, and existing catalog/evaluation behavior is unchanged.

The protected read endpoint
`GET /api/v1/catalog-change-proposals/{proposalId}/revisions/{version}/fact-reviews`
returns at most 20 observations in ascending per-revision review-number order. Its
exclusive `afterReviewNumber` cursor never mixes revisions. Reading does not replay the
proposal, refresh dates, fetch sources or expose audit actor identities, and history
remains available after rejection or a head change. New observations may append between
pages. The curator proposal screen now shows this ledger separately from candidate
evidence, preserving earlier verdicts and corrections as reported human observations,
not verified fact statuses. Browser pagination binds the cursor to the displayed
revision and requires a restart if the head changes. An empty history does not mean
verification or approval. The existing rejection action is unchanged.

Each displayed candidate fact now has a native HTML form for a manual source-review
observation. The curator must choose one of the three verdicts (none is preselected)
and explicitly confirm manual assessment of the source, typed claim, option scope
and conditions. The form binds the displayed revision/digest, option and fact path
and includes a server-generated UUID observation ID. It is unavailable on a rejected
revision. The existing BFF endpoint accepts JSON or strict URL-encoded forms, both
limited to 2,048 actual body bytes with the same origin, session and curator checks.
Duplicate fields, unknown fields, unsafe revisions and missing confirmation fail closed.
Native success and exact retries return a 303 to the revision-bound stored observation;
the page shows a confirmation only if that ID is present in validated history. A
conflict returns to current facts for review. An uncertain Core outcome returns a
no-store 503 page retaining the exact payload/key with an explicit retry form; it does
not claim failure to write or mint a replacement key. Check history before retrying.
Reusing that original key/payload/actor returns an existing receipt without another
observation, while a deliberate correction uses a new form/key. Sources remain
unreviewed, observation dates and active evaluation behavior are unchanged, and
approval/publication are still unavailable.

The protected
`GET /api/v1/catalog-change-proposals/{proposalId}/revisions/{version}/fact-reviews/summary`
validates the immutable candidate and summarizes every recorded fact, including
unchanged facts. It classifies each by its greatest committed review number:
no observation, source supports the claim, source does not support it, or insufficient
evidence. One SQL ledger snapshot supplies these whole-revision counts and
`reviewThroughNumber`; only the latest receipt per fact is loaded, not the entire
observation history. The response pages at most 20 sorted fact targets, aligned with
candidate evidence, with a receipt or null for each. Corrections change the summary
without erasing history. Scoped reads remain available after rejection or a head change.
The curator screen shows the counts and each displayed fact's latest reported conclusion
with a history link. The BFF checks exact revision/digest, target alignment, count sums,
observation bounds and authority flags; invalid data fails closed. Concurrent appends
can change later reads, and the independently fetched history can be newer than the
summary. Missing observations are not negative provider claims; supporting observations
are not capability values, verified evidence or an approval gate. Even support for every
recorded fact does not prove freshness, baseline trust, complete coverage or eligibility.
No source is fetched, stored dates or evidence trust changed, actor identity disclosed,
assessment evaluated or catalog published by this read.

The curator screen also explains **why approval is unavailable** in a display-only
prerequisites report derived from the already validated Core reads. It identifies
missing, non-supporting or insufficient latest manual observations, stale/future
evidence dates, a rejected revision and a missing or blocked exact-revision scenario
report. Whole-candidate counts include facts outside the visible page; categories
can overlap and must not be summed. Candidate evidence remains unreviewed, the
caller-supplied baseline untrusted, full impact coverage unestablished and
approval/publication unimplemented, even with all-supporting observations, current
dates, an analyzed report and zero uncovered changes. Detail links lead to evidence,
history, impact and the existing rejection boundary. Bound to the displayed
revision/digest, the report identifies the Core date-check time, observation
through-number and latest stored report number. These independent reads are not an
atomic approval snapshot, an exhaustive checklist or a Core approval policy. No additional API call, source fetch,
evaluation, date refresh, trust promotion or catalog write is performed. Existing
curator authorization and write preconditions remain authoritative.

### Reserved published snapshot format

`published-provider-catalog-snapshot.v1.schema.json` defines a separate, reserved
immutable manifest; it is not an active catalog, publication endpoint or approval
workflow. Scoped catalog content reuses the four draft fact families, preserving
claims, conditions and provenance without adding authority to the draft types.
A separate, declared `REVIEWED` status must address every recorded option/fact once.
The manifest also includes a snapshot UUID, a claimed publication decision/time and
an optional previous immutable snapshot reference. An initial null parent is not a
trusted bootstrap. References pin UUID, catalog label and manifest SHA-256 together;
a label alone is insufficient.

Core's offline `CatalogSnapshotInspector` checks canonical content/manifest hashes,
semantic catalog consistency, missing/duplicate/foreign status targets, self-parent
or reused immediate-parent labels and evidence dates after the declared publication.
The content hash covers the typed catalog data. The manifest hash excludes only
itself and binds identity, content/hash, parent, decision/time and declared statuses.
Both use the existing application-specific unordered-collection canonicalization,
not a signature or RFC 8785. The existing proposal v1 base-draft hash is a different
domain, including the draft kind; current proposals and synthetic evaluation remain
unchanged and cannot accept this snapshot or a new baseline reference.

Format validity and exact supplied-base agreement never set `baselineVerified`,
source verification, approval, writes or evaluation readiness. An attacker can
recompute every hash and declare a decision or reviewed evidence; authenticating
publication and lineage requires authoritative Core records written through a verified
publication workflow. That lookup is explicitly unavailable, even for a matching
initial manifest. Historical format inspection does not assert current evidence
freshness, impact coverage or eligibility. No source, database, publisher or evaluator
is called by the inspector. The shared `.invalid` fixture is format-valid synthetic
test data, not a real publication. Trusted baseline resolution, approval,
active catalog loading and assessment pinning are still unimplemented.

### Reserved publication registry storage

Flyway V14 reserves `core.catalog_publication_decisions`, `core.catalog_published_snapshots`
and `audit.catalog_publication_events`. Core runtime has SELECT only; Web has no access,
including through schema default privileges. No runtime INSERT, writer, publication CLI,
HTTP approval action, active catalog pointer or seed publication is available.

A reserved decision binds either an explicit `CURATED_BOOTSTRAP` origin or one exact
`PROPOSAL_APPROVAL` revision/digest. The latter locks the current proposal head and cannot
coexist with a rejection; the existing rejection insert now shares that database guard.
Decision, manifest and matching minimal curator-assertion audit must commit together.
After inserting the decision, manifest and event can be inserted in either order.
The manifest metadata must match the stored identity, catalog label, digests, decision,
publication time and full parent tuple. The supplied manifest time is bounded against a
separate DB receipt time. Audit authentication-time bounds and actor shape follow the
existing curator audit boundary, without copying raw sources, tokens or rationale.

A unique bootstrap root, globally unique catalog labels and one successor per exact parent
reserve a linear immutable history. Conflicting concurrent successors cannot fork it.
Runtime roles cannot rewrite, delete, truncate or reparent these records; a database
administrator remains outside that protection. This is not catalog activation or a
tamper-proof signature chain. SQL checks structural bindings, not full catalog semantics,
canonical hash correctness, evidence review, applicable impact coverage or OIDC identity.
Those checks and an authorized publication workflow remain required before runtime writes
or trusted baseline resolution can be enabled. Testcontainers fixtures use an admin role
with fictional data solely to exercise these constraints; they do not authenticate a curator
or grant publication authority. The offline inspector and synthetic evaluator are unchanged.

### Internal publication integrity lookup

Core now has a SELECT-only `CatalogPublicationLookup`, not an HTTP/BFF endpoint or a
publisher. A caller pins a snapshot UUID, catalog label and manifest SHA-256 together;
there is no latest-version or label-only fallback. The lookup strictly decodes stored
wire formats, recomputes content/manifest hashes and validates every ancestor through
the sole bootstrap root. It binds stored snapshot/decision/audit metadata, historical
actor-assertion shape and authentication-time bounds, exact proposal revision/request
digest, the entire candidate named by the recorded decision and the parent's full
base-draft content. It rejects missing/corrupt ancestors, reused labels, forks and
reversed publication times.
Changing the current proposal head does not change its historical publication binding.

All reads run in one read-only repeatable-read transaction. Limits are 64 snapshots,
32 MiB per manifest or proposal request and 64 MiB combined UTF-8 JSON text per lookup.
Oversized JSON stays on the database server; there is no partial-history success when a
limit is exceeded. Missing/mismatching references or invalid recorded data return
`UNAVAILABLE` with a bounded reason and no snapshot/partial lineage. Storage failures
propagate without being misreported as not-found. Successful lineage is requested-to-root.
Only validated catalog data and immutable references are returned, not audit actor identities.

`VALIDATED_STORED_LINEAGE` means recorded integrity, not an authenticated publication.
Even a self-consistent admin-created row can forge curator assertions. Authority remains
`VERIFIED_PUBLICATION_WORKFLOW_UNAVAILABLE`, and baseline/source verification, approval,
writes and evaluation readiness remain false. `compareBaseline` compares a supplied
base-draft digest and full content only after successful registry validation; matching
data does not upgrade those flags. Historical validation does not refresh evidence,
assert current eligibility or rerun impact. A verified curator workflow and explicit
bootstrap policy are still required before trusted baseline resolution can be enabled.
No API contract, migration, runtime write grant, active catalog loading, assessment
pinning, account or paid call is added by this step.

### Core publication preflight policy

`CatalogPublicationPreflight` adds a Core-owned read-only denial policy, not
a publisher or authorization token. Its separate protected HTTP projection is
described under composed profile planning coverage. Unlike the older display-only BFF summary,
it reads one repeatable-read database snapshot and checks an exact stored proposal
UUID/revision/SHA-256. It rejects missing, malformed, over-budget or tampered requests,
noncurrent heads, rejected/already published revisions, nonreviewable changes, invalid
candidates and previously published catalog labels. Stored previews are not trusted or read.

The policy checks the latest observation per **every recorded candidate fact**, including
unchanged facts. Missing observations, source contradictions and insufficient evidence
are separate blockers. Review identity/revision/digest/target/number/time bindings and
undeclared trust claims are checked; a corrupt ledger releases no partial supporting
count or watermark. Counts report manual assertions, not independently verified truth.
Freshness is reevaluated at the preflight time: supporting observations cannot refresh
stale or future source dates, and absent facts remain unknown rather than supported.
Requests are capped at 32 MiB of stored UTF-8 JSON text and withheld on the database
server when over budget; latest-review reads cap at 6,801 rows to detect overflow.
The separate lineage lookup retains its 64 MiB/64-ancestor limits.

A baseline must be an explicit exact registry reference, match the entire supplied
base and have no successor. Even matching, internally consistent admin assertions
retain `BASELINE_AUTHORITY_UNAVAILABLE`. An impact receipt's existence is only a receipt,
not full coverage. Preflight now verifies the **latest report for the exact proposal
revision**, its request/report hashes, schema/canonicalization versions, receipt times
and body-free service audit bindings. The database withholds report bodies over
32 MiB. An invalid newest report cannot fall back to an older valid report.

Compatible policy/rule/profile/case versions are required. Core then regenerates the
entire conditional scenario report at its original `evaluatedAt` and compares canonical
hashes, including definitions, dependencies, checks, outcomes, gaps and trust flags.
Stored JSON is compared without reinterpreting historical interface-valued facts.
Self-consistent altered results, omitted checks or invented coverage cannot pass replay.
The result exposes only checked report identity/time/hash and scenario/gap/deferred-path
counts; an invalid report releases no partial metadata or counts. History is not changed
or refreshed, and the historical display API remains unchanged.

`VERIFIED_PARTIAL_ANALYSIS` means recorded integrity and historical replay, not complete
evaluation or trusted evidence. The current three-profile report still defers seven
dimensions and cannot satisfy the mandatory `IMPACT_COVERAGE_INCOMPLETE` blocker.
Even a verified blocked analysis does not provide coverage or authority.

Preflight separately computes a **fresh fact-path regression** for all changed addresses
in draft v1. Its versioned suite declares all 68 supported paths: nine capabilities,
19 compatibility categories, four residency categories and 36 human authentication
control scopes (browser/native mobile, six populations, three controls). Scope changes,
option additions and removals run all 68 probes for each affected option; renames are distinct
removed/added options, with at most 200 option IDs and 13,600 checks. Ordinary fact or
provenance changes run the exact affected probes. Missing facts remain unknown rather
than borrowing another client/population's assertion or inferring residency from a label.

These are fixed **required-rule** probes, not user requirements or full-profile decisions.
The residency probe's DE allowlist is a synthetic test input, not a hosting recommendation.
`changedFactPathsCovered` means the changed addresses have probes, not that outcomes
passed or sources were verified. Blocked, no-op, gap or incomplete-count summaries
cannot claim that coverage. The body-free `factPaths` summary has the current time,
report/case-set hashes and bounded counts; it is independent of the historical `impact`
receipt and does not refresh it. Contract/schema drift and a pinned suite digest require
an explicit suite review rather than silently expanding coverage.

The separate `storedFactPaths` check now verifies the latest immutable 68-path receipt
for that exact revision, with bounded body, request/report hashes, schema, database
receipt/audit times and service-event bindings. It regenerates the entire bound report
at its original `evaluatedAt`, not the current time. Missing, invalid, incompatible or
replay-mismatched receipts are separate blockers; the fresh calculation cannot replace
one. `VERIFIED_FACT_PATH_ANALYSIS` only establishes replayed changed-path coverage.
Blocked or no-op reports can have valid stored integrity without that coverage.

The 24-probe and three-profile HTTP/history formats remain unchanged. Full-profile
deferred dimensions still prevent complete publication coverage. The local report
storage grants do not grant publication authority;
no HTTP endpoint, registry write or catalog activation is added.

First publication uses the distinct `CURATED_BOOTSTRAP` preflight: all three publication
tables must be empty, the candidate valid and its evidence current. Empty storage or a
null parent cannot bypass `BOOTSTRAP_REVIEW_WORKFLOW_UNAVAILABLE`; proposal review
observations are not silently repurposed as bootstrap approval. This raw-draft path
does not load or reuse a stored bootstrap review. The exact stored-review path described
below can account for manual observations, but still cannot approve or publish.
The raw-draft path retains `BOOTSTRAP_IMPACT_WORKFLOW_UNAVAILABLE`. Neither path reuses
proposal impact reports for the initial catalog version.

The exact stored-review path now computes a separate **candidate-only bootstrap impact**
at the same preflight time. Core loads and validates the review UUID/hash, then checks
all 68 declared fact paths and all three frozen scenarios for each candidate option,
including unchanged and absent facts. There is no fabricated empty baseline, proposal
revision or before/after diff. The bound report identifies the review/candidate hashes,
rule/case versions, definitions, conditional results and explicit gaps/deferred dimensions.
At most 100 options produce 6,800 path checks and 300 scenario results. Shared rule
evaluation preserves option/client/population isolation and does not infer residency
from a region label or promote supporting manual observations to trusted facts.

The body-free `bootstrapImpact` summary exposes bound identity/hash/time and bounded
counts, including missing, violating and indeterminate paths. `allDeclaredFactPathsChecked`
and `allFrozenScenariosChecked` describe checks performed, not successful outcomes,
complete evidence or full-profile coverage; scenario dependency gaps remain explicit.
Invalid candidates/digests yield no partial successful counts. Raw drafts, unavailable
reviews and proposal mode leave this summary `NOT_CHECKED`; storage/kernel failures
propagate. This remains a fresh pure calculation, not a recorded receipt:
`storedReportVerified` is false. The separate `storedBootstrapImpact` verifies the
latest durable receipt for the exact stored review, not a caller-supplied summary.
Coverage/authorization/publication blockers remain.

### Stored bootstrap impact receipts

An explicit local command records candidate-only analysis for an existing, validated
bootstrap source review. Start local PostgreSQL and supply the exact review UUID and
`reviewSha256` returned by the protected source-review workflow, plus a fresh report UUID:

```sh
AUTHWEAVE_CATALOG_BOOTSTRAP_IMPACT_REPORT_ID='<canonical report UUID>' \
AUTHWEAVE_CATALOG_BOOTSTRAP_REVIEW_ID='<exact stored review UUID>' \
AUTHWEAVE_CATALOG_BOOTSTRAP_REVIEW_SHA256='<exact lowercase review SHA-256>' \
  make store-catalog-bootstrap-impact
```

These are placeholders, not runnable sample identities. No JSON file, timestamp,
verdict, trusted flag, publication decision or fabricated proposal is accepted. The
`local-catalog-bootstrap-impact-write` profile is only enabled by this explicit command;
normal startup never records a receipt or opens an HTTP report-writing action.

V17 stores immutable raw JSON, format/canonicalization/hash, exact review/candidate
bindings, a database sequence number and receipt time. A mandatory body-free **SERVICE**
audit is committed in the same transaction; it is not a fresh curator/OIDC/source-truth
assertion. Reciprocal foreign keys prevent an unaudited commit. Core has bounded reads
and column-limited inserts, not update/delete, caller-controlled times/numbers or
publication writes; Web/PUBLIC have no access. The writer acquires the shared bootstrap
boundary before allocating a number. The database insert guard also requires an empty
publication registry under that lock. New reports stop after publication, while a retry
with the same report/review/hash returns the original snapshot/audit without recalculating
under new rules. A new report UUID requests a new analysis. Historical reads never rewrite
JSON, refresh evidence dates or create a missing receipt.

Internal preflight policy v6 verifies `storedBootstrapImpact` in the same read-only,
repeatable-read snapshot as the exact source review and fresh checks. It reads the latest
report by review UUID without filtering hashes/validity to expose an older successful
report. Bounded JSON, schema, canonical hash, review/candidate bindings, database/audit
times, service identity and policy/rule/profile/case/scenario versions must match.
The entire report is regenerated at its **original** `evaluatedAt` and canonically
compared, including definitions, all path/scenario results, gaps and false trust flags.
Self-consistent body/hash/audit tampering cannot hide an omitted result or inflated claim.

Missing, oversized, invalid, incompatible, replay-mismatched, blocked or incomplete
receipts have distinct blockers; fresh calculation cannot substitute for one. Invalid
receipts expose no partial identity or successful counts. Database failures propagate.
Raw drafts, unavailable source reviews and proposal mode leave this check `NOT_CHECKED`.
The verified check exposes receipt identity/hash plus a body-free historical `analysis`
summary; its nested kernel summary is not another stored receipt. A successful
`VERIFIED_BOOTSTRAP_ANALYSIS` means all 68 declared paths and three frozen scenarios were
replayed, not that their outcomes succeeded or that missing facts, scenario dependency
gaps and seven deferred profile dimensions disappeared. Full coverage, source truth,
baseline/approval/publication/evaluation readiness and writes remain false. No registry
write, catalog activation or existing HTTP/history format is changed.

### Profile impact coverage policy

Internal preflight policy v10 combines a fresh `profileImpactCoverage` policy v4 with
a separate `scopedProfileImpact` conditional regression.
Passing a receipt replay or checking every catalog address does not establish that
the frozen profiles exercise every decision-critical requirement. The versioned
coverage manifest maps **all 32 semantic inputs of profile v5** to their rule and
permitted fact dependencies. Array/map inputs remain bounded semantic inputs, not
invented provider billing units or a count of individual user values. Independent
schema-path and canonical-schema fingerprint tests require review when fields,
types, vocabulary or scope change. Unknown profile paths, duplicate rules or facts
borrowed from another requirement fail closed, rather than silently widening coverage.

The structural check emits one body-free row per input in four additional, versioned
scoped profiles: 128 rows. These synthetic B2B, partner-portal, public-sector and
workforce regression inputs explicitly exercise browser/native human controls,
population scope, machine-client compatibility and four residency categories.
They are not customer defaults or configured authentication flows. The original
three frozen profiles, their digests and all historical receipts remain unchanged.
`CONDITIONAL_RULE_PRESENT` means that scenario exercises an
evidence-dependent rule, not that the provider meets it. `PATTERN_RULE_PRESENT` means
the existing five profile-only architecture patterns were evaluated, without catalog
fact dependencies or verified prerequisites. `SCOPE_GUARD_ONLY` means the
rule handles an unknown/not-applied scope or criticality without exercising a fact
dependency; it is not evidence of support. `DEFERRED_DIMENSION` identifies an input
not yet evaluated by the profile regression. `MISSING_RULE` distinguishes an omitted
expected rule from an intentionally deferred dimension.

The current suite has ten deferred inputs per scenario: auditability, full assurance,
compliance evidence and operational/cost planning. Additional gaps explicitly retain
**observed architecture configuration**, **provisioning lifecycle** and **configured
human authentication flows**; conditional pattern suitability or feature/enforceability
availability cannot verify these behaviors.
The new scoped suite actively consumes all 68 declared fact addresses across its
profiles, leaving no `unexercisedFactPaths`. The earlier three frozen profiles still
leave 44 such addresses unconsumed; old receipts are not upgraded or reinterpreted.
Four profiles leave 40 deferred-input rows, four pattern-rule rows and twelve additional
behavior gaps.
Empty fact-address gaps,
positive conditional outcomes, owner labels, recorded usage values or a successful
historical replay cannot erase the other coverage boundaries.

The nested body-free `architectureImpact` summary evaluates BFF/session, server-side
session, SPA Code+PKCE, native Code+PKCE and M2M client credentials for each scoped
profile. It reuses the unchanged `architecture-pattern-preflight-1` evaluator; a
fingerprint binds its source-owned pattern metadata, prerequisites and references.
The fixed suite produces 20 pattern results: 15 conditional matches, three needing
information and two not applicable. Its source/profile digest, full analysis hash and
time are bound to the coverage check. `allDeclaredPatternsChecked` denotes performed
rules, not successful configuration verification, protocol/provider compatibility or
a chosen winner. For example, required browser-token minimization leaves SPA exposure
unknown rather than inventing a token ban; BFF/session matches remain conditional on
unverified prerequisites. Unknown/prohibited minimization is not interpreted as an
instruction to expose tokens. No catalog label, provenance text or owner declaration
is interpreted as observed configuration. The summary contains no profile values,
source URLs or actor identities, performs no network access and creates no durable
receipt. Configuration, prerequisites, compatibility and recommendation readiness
remain false, including when every selected pattern conditionally matches.

Architecture impact policy v2 also evaluates a typed prerequisite inventory using
`architecture-prerequisites-1`. Eleven source-owned IDs map one-to-one to the existing
pattern prerequisites: BFF proxy/session defenses, session resource access/direct API
assessment, SPA public-client/PKCE endpoints and token threat model, native user-agent/
redirect/PKCE and storage/API authorization, and confidential workload client,
authorization context and grant/API permissions. The grouped conditions retain their
existing meanings; this is not an exhaustive security checklist or a live configuration
model. Its fingerprint binds the IDs, pattern scopes and descriptions.

The pure kernel accepts only typed, unverified design declarations: `SATISFIED`,
`NOT_SATISFIED` or `UNKNOWN`. Missing declarations remain unknown. A declaration from
another pattern is rejected; unknown client scope cannot become a match and an
unselected pattern stays not applicable. An unmet condition produces a conditional
non-match without erasing other unknown conditions. Even all-satisfied declarations
cannot verify deployment, provider compatibility or recommendation readiness, and do
not change the existing client/token-exposure pattern results.

Current source profiles contain no configuration declarations. Fresh preflights pass
none, producing 44 prerequisite cells: 38 unknown and six not applicable, zero declared
successes. The full architecture analysis carries typed IDs/outcomes/reasons; its
body-free `prerequisiteCounts` summary is bound by the same profile/library/time hashes.
Source-only report constructors reject invented declarations and partial cells. The
configuration gap remains mandatory; no existing public endpoint or historical receipt
is changed. The separate personal what-if preview above does not alter source profiles,
catalog facts, source dates or runtime configuration, or infer them from free text.

The fresh conditional regression uses the same production rule kernel as the
historical analysis. Proposals evaluate four profiles for every affected option,
with real supplied before/after drafts and exact changed/scope dependencies. A disjoint
100-option base/candidate union is bounded to 800 profile results. Exact bootstrap
reviews evaluate only their candidate, up to 400 results; no baseline or before/after
history is invented. Missing facts cannot borrow another client, population, option or
region label. Conditional outcomes and source freshness remain separate; observations,
conditions and provenance are bound but never interpreted as proof or instructions.

The body-free `scopedProfileImpact` summary binds its input UUID/hash, candidate hash,
scenario digest, analysis hash and preflight time. `allScopedScenariosChecked` means
performed checks, not successful requirements, full coverage or stored-report replay.
Invalid comparisons/candidates withhold results; no-op comparisons cannot claim that
any profiles were checked. This additional fresh calculation creates no durable receipt.

The policies use source-controlled profiles and plans, not caller-supplied definitions
or completeness flags. It contains no profile values, source bodies or actor identities.
Parsed exact-proposal and validated exact-bootstrap-review preflights compute it at
their own preflight time; raw drafts and unavailable/invalid exact inputs leave it
`NOT_CHECKED`. Schema/rule-policy failures propagate. No historical JSON, rule/case
version or receipt format is changed, no missing receipt is manufactured and no write
or catalog activation occurs. The current manifest returns `INCOMPLETE`; the mandatory
coverage, fresh-authorization and publication-workflow gates remain closed. This is
the coverage-policy, active scoped-regression and architecture-pattern prerequisites,
not completion of deferred evaluators or permission to publish. Remaining configuration,
auditability, assurance/compliance, operations/cost and lifecycle gaps remain explicit.

Both modes always return `BLOCKED`. Curator authorization **at the eventual write** and
a verified publication workflow remain mandatory blockers; baseline/source verification,
coverage, approval, publication/evaluation readiness and writes remain false. A future
writer must recheck current state and authorization within its own atomic transaction,
not reuse this historical preflight result. Database outages propagate without a fallback.
The preflight itself adds no HTTP action or registry writes and leaves the active
evaluator unchanged.

### Protected bootstrap source review

Core now supports a separate whole-candidate manual source-review workflow for the
first catalog version. This is not a proposal revision, curator approval, a published
root or catalog activation. The protected local BFF form at `/catalog/bootstrap` is
linked from the curator review index. Never send the server-only Core credential from
a browser or expose the local Core service through a tunnel.

`POST /api/v1/catalog-bootstrap-reviews` requires the existing BFF credential and a
fresh, singular, project/organization-scoped `catalog_curator` assertion, including on
retries. The request supplies a fresh review UUID, exact **draft** candidate SHA-256,
the full candidate, one verdict per recorded fact and the distinct
`MANUAL_BOOTSTRAP_SOURCE_REVIEW` confirmation. Core recomputes the digest and checks
semantic validity and the entire fact target set across all four families: no missing,
foreign or duplicate targets. A supporting verdict for an unknown claim does not turn
it into an available capability. Unrecorded facts remain unknown.

V15 stores one immutable review and a mandatory, body-free curator assertion audit in
the same transaction. Core gets SELECT and column-limited INSERT on only these review
tables; UPDATE/DELETE/TRUNCATE, caller-controlled receipt times and Web access remain
denied. Publication tables are still SELECT-only for Core. This is not protection
against a database administrator. PostgreSQL receipt times and audit authentication
freshness are bounded; SQL shape/foreign-key checks do not authenticate an OIDC login
or independently verify sources and canonical hashes.

New reviews require all three publication tables to be empty. Service and DB review
insert checks share a transaction-level advisory lock with reserved publication
decision inserts, acquired before proposal head locks. This closes the concurrent
check/insert gap without opening a publisher. Equivalent retries of the same UUID
return the original receipt only for the same issuer/subject/project/organization and
canonical request; array order is insignificant. Changed assertions or another actor
require a new UUID. A retry creates no additional audit and remains possible after
publication, with fresh HTTP authorization. Reviews never overwrite one another or
inherit verdicts from a different candidate or proposal.

`GET /api/v1/catalog-bootstrap-reviews/{reviewId}?expectedSha256=<review-sha256>` also
requires a fresh scoped curator assertion. It pins UUID and the complete review digest,
strictly checks stored format, candidate/request hashes, target set and historical audit
bindings, and returns only the original small receipt with verdict counts: no actor,
candidate, observation list or source body. There is no latest or label-only fallback.
Stored requests are limited to 32 MiB of PostgreSQL UTF-8 JSON text; oversized bodies
are withheld on the server. Tampered or unavailable reviews release no partial counts.
Database outages propagate rather than becoming not-found or approval.

The internal preflight can now load an exact stored review UUID/digest instead of
accepting verdict totals from the caller. It reevaluates source-date freshness, registry
emptiness and catalog-label reuse. Manual review can include stale/future evidence,
contradictions or insufficient evidence, but those remain publication blockers; it
never refreshes `observedAt`. Even all-supporting, current observations and consistent
administrator assertions do not establish authenticated publication authority. Full
impact coverage, fresh authorization at an eventual atomic write and the publication
workflow remain unimplemented blockers. Source verification, approval, fact trust,
catalog writes and publication/evaluation readiness are not granted. No real curator
role, source fetch, account, paid call or deployment is created by this slice.

The BFF first imports an explicit complete `provider-catalog-draft.v1` candidate,
limited to 1 MiB of UTF-8 JSON. `POST /api/catalog-bootstrap-reviews/prepare` checks the
server-side session and fresh scoped curator authorization before calling the existing
read-only Core draft validator. Core's typed canonicalization supplies the draft digest;
the browser does not hash raw JSON or infer a baseline from proposals. Preparation
does not record a review. The form displays every recorded claim across all four
families with option scope, conditions, unverified provenance and Core's current date
assessment. Unknown, partial and availability/enforcement distinctions are preserved.
Source links open separately without a referrer; neither BFF nor Core fetches sources.

Every fact needs an explicit supporting, contradicting or insufficient-evidence
conclusion; none is selected by default. A separate whole-review checkbox supplies
`MANUAL_BOOTSTRAP_SOURCE_REVIEW`. `POST /api/catalog-bootstrap-reviews` enforces exact
same-origin Origin, a server-side session, fresh scoped authorization, a closed request
shape and the complete unique target set; Core recomputes hashes and enforces the
empty-registry/immutable actor-bound retry policy. Browser-supplied actor/approval
fields are rejected, and Core credentials are sent only by the server. Actual streamed
request/response bodies are capped at 4 MiB, including chunked requests; malformed
UTF-8 is rejected. Responses are no-store and do not disclose raw service errors.

Before the first write, the browser freezes the UUID and full candidate/conclusion
payload in memory. A timeout or uncertain/malformed write response never creates a
new key, changes conclusions or claims success. Keep that tab open: reload loses the
in-memory retry payload. Verify the same account in a separate tab if needed, then
explicitly confirm retrying the exact review. Retries still require fresh authorization.
After a validated receipt, the stored-receipt link pins UUID and **review** SHA-256,
not the candidate digest. Both the page and
`GET /api/catalog-bootstrap-reviews/{reviewId}?expectedSha256=<review-sha256>` require
fresh scoped authorization and return only checked body-free receipt metadata/counts.
No latest/label-only lookup, verdict inheritance, approval action or publication button
is added. Existing impact/publication blockers and the active evaluator are unchanged.

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
by the active evaluator. No source fetches, approved real-provider baselines or recommendations
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

### Stored fact-path regression reports

With local PostgreSQL running and an explicit proposal revision already stored, the
separate local-only command saves the complete 68-path required-rule report:

```shell
AUTHWEAVE_CATALOG_REGRESSION_REPORT_ID=55555555-5555-4555-8555-555555555555 \
AUTHWEAVE_CATALOG_PROPOSAL_ID=33333333-3333-4333-8333-333333333333 \
AUTHWEAVE_CATALOG_PROPOSAL_VERSION=0 \
  make store-catalog-regression
```

Only the `local-catalog-regression-write` command profile enables this writer; normal
server startup does not. Inputs select UUIDs/revision, never report JSON, trust flags
or times. Core reads the bounded stored request, verifies its digest and computes the
report using database time. V16 stores immutable JSON/schema/canonicalization/hash and
a mandatory body-free `catalog-fact-path.recorded` service event in one transaction.
Core cannot update/delete receipts or supply their database timestamps/numbers; Web
cannot access these tables. No human/source-authenticity or publication assertion is made.

The first call returns `SAVED`; retrying the same UUID/revision returns `UNCHANGED`
without regeneration or another event. A new UUID requests a new analysis; rebinding
an old UUID to another proposal/revision is rejected. Historical revisions remain
readable but do not satisfy a new head's receipt requirement. This storage is separate
from existing scenario history and has no HTTP read/write endpoint. Analysis flags
remain false for trust, full coverage, approval, writes and readiness; the enclosing
storage operation does not change their meaning. Missing inputs fail without a write.

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
- `/latest` returns the highest-numbered report for that exact revision, or 204 if
  the revision exists but has no report. It does not rerun analysis. The BFF uses
  this read only after checking a fresh scoped curator session for the review screen.
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

`make check-guided-bff` (also included in `make check` and CI) submits the three
synthetic guided scenarios through the actual BFF route handlers to a real Core
HTTP server, with authorization filters and separate Core/web PostgreSQL runtime
roles. Testcontainers supplies a fresh database and random loopback ports; local
`.env`, app data, IdP and named volumes are not used. Install web dependencies first
with `make setup-web`. It verifies creation, five saves and native no-ops, all six
saved previews, temporary architecture/lifecycle requests, all 37 Review/brief rows,
stale writes, cross-user denial and the exact revision/audit counts. Sessions are
synthetic test setup, not an OIDC login or browser E2E test. Saved context sets use
the declared option order for display (countries use code order), so Core's unordered
set serialization cannot reorder Review, list labels or the brief; ordered usage
assumptions, stored profiles and verdicts are unchanged.

`make check-browser` (also in `make check` and CI) runs eight Chromium E2E cases
against an isolated production Next.js standalone build, real Core HTTP and fresh
Testcontainers PostgreSQL. Run `make check-web` and `make setup-browser` first.
CI builds without OIDC configuration; `/account` must remain request-time rather
than cache a build-time authentication-unavailable screen.
The three synthetic scenarios run at desktop and mobile widths through OIDC sign-in,
five actual form saves, all 37 Review/downloaded-brief rows, fictional Comparison,
Architecture, saved-list resume and sign-out. SCIM/SSO labels and horizontal overflow
are checked as well. Failure paths cover dirty-step stay/discard, a stale tab,
cross-user read/write denial, CSRF, same-account session rotation, a rejected different
account, invalid nonce and callback replay. A delayed-JavaScript check verifies that
step/list navigation is disabled until hydration while native Save remains available;
early clicks after a save/reload must not be silently lost. SQL checks exact revisions/audit counts,
session cleanup and absence of workspaces for rejected identities.

The test-only OIDC protocol double checks PKCE S256 and one-use codes and returns
signed synthetic ID Tokens; the browser uses the actual login/callback/session routes,
not injected cookies or fabricated application sessions. A loopback proxy forwards
the fixed BFF Core origin to the random test server without bypassing its filters.
Local `.env` files, real ZITADEL users, app data, named volumes and external services
are not used. Both loopback address families are checked for busy ports; cleanup
stops only owned processes. Browser screenshots on failure remain ignored and traces
are disabled. This does not prove real-ZITADEL interoperability, curator grants,
owner understanding, accessibility conformance or hosted security readiness.

`make check-browser-zitadel` is the separate, explicit real-provider interoperability
check, not part of `make check` or CI. Use the existing local registration (`make auth-up`
and `make auth-registration-check`), the production build from `make check-web`, and
Chromium from `make setup-browser`. Ports 3000/8080 must be free; the existing ZITADEL
lab owns 8081. It never registers users, changes passwords or grants roles.

The check reads only the four OIDC registration values and the two fixed synthetic
Alice/Bob passwords from existing ignored mode-600, non-symlink files. Passwords are
entered into the pinned Login V2 UI, never passed to the application server. Fresh
browser contexts use real authorization/callback routes and PKCE, not injected
sessions. Real Core and a new Testcontainers application database remain isolated
from owner app data and named volumes. Alice uses desktop Chromium (1440 x 1000);
Bob uses mobile Chromium emulation (390 x 844, touch enabled), not a physical device.
Each completes the three synthetic B2B, citizen and workforce scenarios through
five actual form saves, all 37 Review/downloaded-brief rows, fictional Comparison,
Architecture and saved-list resume using the same UI assertions as the protocol-double
suite. SCIM/SSO labels and horizontal overflow are checked throughout these screens.
Both flows also verify profile claims, same-account password reauthentication,
session rotation with unchanged identity/workspace, revoked old cookies, cross-user
read/write 404 for all six assessments, personal-list isolation, curator denial and
logout with ZITADEL's `End Session` confirmation. SQL checks six version-5 assessments
with six revisions/events each, three assessments per workspace, and no remaining
application sessions or login transactions. Reauthentication, previews, downloads,
resume and denied writes must not add revisions/events.

Only checkpoint names and safe status counters are printed: browser debug output,
application server logs, screenshots, traces, videos and error objects are disabled
for this credential-bearing check. Browser traffic outside the two exact loopback
origins is blocked. Cleanup stops only owned temporary app resources; the identity
lab is left running until `make auth-down`, which preserves its volumes. This covers
the existing synthetic guided scenarios with real local authentication, not verified
provider facts, a curator grant, a cloud check, physical-device/accessibility conformance
or owner understanding. Provider authentication events may be recorded in the local IdP.

The fact-path report concurrency regression observes actual PostgreSQL blocking,
uncommitted visibility and report-number allocation with both a normal and a
saturated connection pool. Its separate same-role observer is test-only, not a
runtime connection pattern. A blocked writer must not advance the report sequence
before the preceding transaction is released.

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

import assert from "node:assert/strict";
import { after, test } from "node:test";
import { NextRequest } from "next/server.js";

import { POST as createAssessmentRoute } from "../src/app/api/assessments/route.ts";
import { POST as reauthenticateRoute } from "../src/app/api/auth/reauth/route.ts";
import { POST as updateCapabilitiesRoute } from "../src/app/api/assessments/[id]/capabilities/route.ts";
import { POST as weightedPreviewRoute } from "../src/app/api/assessments/[id]/weighted-preview/route.ts";
import { POST as prerequisitePreviewRoute } from "../src/app/api/assessments/[id]/architecture-prerequisites/route.ts";
import { prerequisiteAssessmentId, prerequisiteWorkspaceId, prerequisiteFixture, prerequisiteInput,
  prerequisiteProfile } from "./fixtures/architecture-prerequisites.mts";
import { POST as weightSensitivityRoute } from "../src/app/api/assessments/[id]/weight-sensitivity/route.ts";
import { POST as evaluationContextRoute } from "../src/app/api/assessments/[id]/evaluation-context/route.ts";
import { POST as usagePlanningRoute } from "../src/app/api/assessments/[id]/usage-planning/route.ts";
import { POST as auditabilityRoute } from "../src/app/api/assessments/[id]/auditability/route.ts";
import { POST as requirementsBriefRoute } from "../src/app/api/assessments/[id]/requirements-brief/route.ts";
import { requirementsBriefFilename } from "../src/lib/assessment/requirements-brief.ts";
import { readPersonalAssessment, readPersonalAuditability } from "../src/lib/auth/core-client.ts";
import { auditabilityFixture, auditabilityInput, auditabilityAssessmentId,
  auditabilityWorkspaceId } from "./fixtures/auditability-preview.mts";
import { POST as rejectProposalRoute } from "../src/app/api/catalog-change-proposals/[id]/rejection/route.ts";
import { POST as factReviewRoute } from "../src/app/api/catalog-change-proposals/[id]/fact-reviews/route.ts";
import { POST as prepareBootstrapRoute } from "../src/app/api/catalog-bootstrap-reviews/prepare/route.ts";
import { POST as recordBootstrapRoute } from "../src/app/api/catalog-bootstrap-reviews/route.ts";
import { GET as readBootstrapRoute } from "../src/app/api/catalog-bootstrap-reviews/[id]/route.ts";
import { capabilityFields } from "../src/lib/assessment/capabilities.ts";
import { savedRequirementGroups } from "../src/lib/assessment/saved-requirements.ts";
import { relatedComparisonInput } from "../src/lib/assessment/comparison-presentation.ts";
import { savedRequirementsFixture } from "./fixtures/assessment-ui.mts";
import { guidedScenarios } from "./fixtures/guided-scenarios.mts";
import { authDatabase, beginLogin, beginReauthentication, consumeLogin, createSession,
  revokeSession, touchSession } from
  "../src/lib/auth/store.ts";
import { freshCuratorGrant } from "../src/lib/auth/curator.ts";
import { opaqueHash, randomOpaqueValue, sessionCookieName } from "../src/lib/auth/session-policy.ts";

after(async () => { await authDatabase().end(); });

for (const scenario of guidedScenarios) test(`guided ${scenario.key} saves preserve sections and feed versioned Review/Comparison inputs`, async () => {
  const names = ["AUTHWEAVE_OIDC_ISSUER", "AUTHWEAVE_OIDC_CLIENT_ID", "AUTHWEAVE_PUBLIC_ORIGIN", "AUTHWEAVE_CORE_SERVICE_TOKEN"];
  const previous = Object.fromEntries(names.map(name => [name, process.env[name]])), previousFetch = globalThis.fetch;
  Object.assign(process.env, { AUTHWEAVE_OIDC_ISSUER: "http://localhost:8081", AUTHWEAVE_OIDC_CLIENT_ID: "synthetic-client",
    AUTHWEAVE_PUBLIC_ORIGIN: "http://localhost:3000", AUTHWEAVE_CORE_SERVICE_TOKEN: "synthetic-guided-flow-token-000000000000000000" });
  const id = "80000000-0000-4000-8000-000000000001", workspaceId = "70000000-0000-4000-8000-000000000001";
  const identity = { workspaceId, issuer: "http://localhost:8081", subject: "synthetic-guided-flow-owner",
    email: null, displayName: null, authenticatedAt: new Date() };
  const sessionId = await createSession(identity, undefined);
  const fixture = savedRequirementsFixture();
  fixture.security.auditability = "UNKNOWN";
  fixture.security.auditabilityRequirements = { selectedCriteria: [], minimumRetentionDays: null };
  let profile: Record<string, unknown> = fixture, version = 0, created = false, raceOnWrite = false;
  const calls: string[] = [], writes: number[] = [];
  const coreUrl = `http://127.0.0.1:8080/api/v6/workspaces/${workspaceId}/assessments`;
  const assessment = () => ({ id, workspaceId, status: "DRAFT", version, profileSchemaVersion: 6, profile });
  // Stateful Core test double, not an OIDC/browser E2E test. Only the session database is live.
  globalThis.fetch = async (input, init) => {
    calls.push(String(init?.method));
    const headers = init?.headers as Record<string, string>;
    assert.equal(headers["X-AuthWeave-Oidc-Subject"], identity.subject);
    assert.equal(headers["X-AuthWeave-Oidc-Issuer"], identity.issuer);
    assert.equal(headers.Authorization, `Bearer ${process.env.AUTHWEAVE_CORE_SERVICE_TOKEN}`);
    assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error");
    if (init?.method === "POST") {
      assert.equal(String(input), coreUrl); assert.equal(created, false); created = true;
      return Response.json(assessment(), { status: 201 });
    }
    assert.equal(created, true);
    assert.equal(String(input), `${coreUrl}/${id}${init?.method === "PUT" ? "/profile" : ""}`);
    if (init?.method === "GET") return Response.json(assessment());
    assert.equal(init?.method, "PUT");
    const update = JSON.parse(String(init?.body));
    assert.equal(update.expectedVersion, version);
    if (raceOnWrite) {
      // Another editor wins after this BFF read, before the conditional Core write.
      raceOnWrite = false;
      profile = { ...profile, operations: { ...profile.operations as Record<string, unknown>, hosting: "MANAGED" } };
      version++;
      return new Response("Private upstream conflict details", { status: 409 });
    }
    writes.push(update.expectedVersion); profile = update.profile; version++;
    return Response.json(assessment());
  };
  const context = { params: Promise.resolve({ id }) };
  const request = (path: string, form?: URLSearchParams) => new NextRequest(`http://localhost:3000/api/assessments${path}`, {
    method: "POST", headers: { Origin: "http://localhost:3000", "Content-Type": "application/x-www-form-urlencoded",
      Cookie: `${sessionCookieName(false)}=${sessionId}`, "X-AuthWeave-Oidc-Subject": "browser-spoof" }, body: form?.toString(),
  });
  const expectSaved = (response: Response, step: string, expectedVersion: number) => {
    assert.equal(response.status, 303); assert.equal(response.headers.get("cache-control"), "no-store");
    assert.equal(response.headers.get("location"), `http://localhost:3000/assessments/${id}?step=${step}`);
    assert.equal(version, expectedVersion);
  };
  try {
    const createdResponse = await createAssessmentRoute(request(""));
    assert.equal(createdResponse.status, 303);
    assert.equal(createdResponse.headers.get("location"), `http://localhost:3000/assessments/${id}`);
    const contextForm = new URLSearchParams({ expectedVersion: "0", applicationType: scenario.applicationType,
      tenancy: scenario.tenancy, membership: scenario.membership, dataResidency: "UNKNOWN", allowedCountries: "",
      browserTokenExposureMinimization: scenario.tokenExposure, phishingResistance: scenario.phishingResistance,
      nonExportableKeys: "UNKNOWN", stepUpAuthentication: "UNKNOWN", complianceScopeStatus: "UNKNOWN" });
    for (const client of scenario.clients) contextForm.append("clients", client);
    for (const population of scenario.populations) contextForm.append("selectedPopulations", population);
    expectSaved(await evaluationContextRoute(request(`/${id}/evaluation-context`, contextForm), context), "context", 1);
    const savedContext = structuredClone({ application: profile.application, audience: profile.audience });
    const capabilities = new URLSearchParams({ expectedVersion: "1" });
    const capabilityInputs: Readonly<Record<string, string>> = scenario.capabilities;
    for (const field of capabilityFields) capabilities.set(field.capability, capabilityInputs[field.capability] ?? "UNKNOWN");
    expectSaved(await updateCapabilitiesRoute(request(`/${id}/capabilities`, capabilities), context), "capabilities", 2);
    const savedCapabilities = structuredClone({ protocols: profile.protocols, provisioning: profile.provisioning });
    const audit = new URLSearchParams({ expectedVersion: "2", criticality: "REQUIRED", minimumRetentionDays: String(scenario.retention) });
    for (const criterion of scenario.audit) audit.append("selectedCriteria", criterion);
    expectSaved(await auditabilityRoute(request(`/${id}/auditability`, audit), context), "auditability", 3);
    const savedSecurity = structuredClone(profile.security);
    const usage = new URLSearchParams({ expectedVersion: "3", scopeDescription: `Synthetic ${scenario.key}: first-year monthly forecast` });
    for (let index = 0; index < 10; index++) usage.append("assumption", index === 0 ? scenario.assumption : "");
    for (const metric of ["MONTHLY_ACTIVE_USERS", "ENTERPRISE_SSO_CONNECTIONS", "MONTHLY_M2M_TOKEN_ISSUANCES", "PEAK_HUMAN_LOGINS_PER_SECOND"]) {
      usage.set(`basis_${metric}`, "UNKNOWN"); usage.set(`value_${metric}`, "");
    }
    for (const [metric, value] of [["MONTHLY_ACTIVE_USERS", scenario.monthlyUsers], ["ENTERPRISE_SSO_CONNECTIONS", scenario.ssoConnections],
      ["MONTHLY_M2M_TOKEN_ISSUANCES", scenario.m2mTokens], ["PEAK_HUMAN_LOGINS_PER_SECOND", scenario.peakLogins]] as const) {
      if (value !== null) { usage.set(`basis_${metric}`, "ASSUMED"); usage.set(`value_${metric}`, String(value)); }
    }
    expectSaved(await usagePlanningRoute(request(`/${id}/usage-planning`, usage), context), "usage", 4);
    assert.deepEqual({ application: profile.application, audience: profile.audience }, savedContext);
    assert.deepEqual({ protocols: profile.protocols, provisioning: profile.provisioning }, savedCapabilities);
    assert.deepEqual(profile.security, savedSecurity); assert.deepEqual(writes, [0, 1, 2, 3]);

    const live = await touchSession(sessionId); assert.ok(live);
    const fresh = await readPersonalAssessment(live, id); assert.ok(fresh); assert.equal(fresh.version, 4);
    const groups = savedRequirementGroups(fresh.profile);
    const row = (group: string, label: string) => groups.find(item => item.id === group)?.rows?.find(item => item.label === label);
    assert.equal(row("application", "Application type")?.value, scenario.expected.application);
    assert.equal(row("application", "User populations")?.value, scenario.expected.users);
    assert.equal(row("application", "Client types")?.value, scenario.expected.clients);
    assert.equal(row("auditability", "Minimum retention")?.value, `${scenario.retention} days`);
    assert.equal(row("usage", "Monthly M2M token issuances")?.value, `${scenario.m2mTokens.toLocaleString("en-US")} · Assumed`);
    assert.equal(row("usage", "Enterprise SSO connections")?.state, scenario.ssoConnections === null ? "not-recorded" : "recorded");
    assert.equal(relatedComparisonInput("provisioning.scim", groups)?.rows?.[0].value, scenario.expected.scim);
    assert.equal(relatedComparisonInput("protocols.enterpriseSingleSignOn", groups)?.rows?.[0].value, scenario.expected.sso);

    const beforeExport = structuredClone(profile);
    const exportForm = new URLSearchParams({ expectedVersion: "4" });
    const exported = await requirementsBriefRoute(request(`/${id}/requirements-brief`, exportForm), context);
    assert.equal(exported.status, 200); assert.equal(exported.headers.get("cache-control"), "no-store");
    assert.equal(exported.headers.get("content-disposition"), `attachment; filename="${requirementsBriefFilename(id, 4)}"`);
    const markdown = await exported.text(), readable = markdown.replace(/\\([!-~])/g, "$1");
    for (const label of [scenario.expected.application, scenario.expected.users, scenario.expected.clients, `${scenario.retention} days`, scenario.assumption]) assert.ok(readable.includes(label), label);
    assert.ok(markdown.includes("- Saved version: `4`")); assert.equal(markdown.includes(identity.subject), false);
    assert.equal(await (await requirementsBriefRoute(request(`/${id}/requirements-brief`, exportForm), context)).text(), markdown);
    assert.equal((await requirementsBriefRoute(request(`/${id}/requirements-brief`, new URLSearchParams({ expectedVersion: "3" })), context)).status, 409);
    assert.equal(version, 4); assert.deepEqual(writes, [0, 1, 2, 3]); assert.deepEqual(profile, beforeExport);

    const beforeConflict = structuredClone(profile), writeCount = writes.length;
    capabilities.set("SCIM", "PREFERRED"); // This form still carries saved version 1.
    const stale = await updateCapabilitiesRoute(request(`/${id}/capabilities`, capabilities), context);
    assert.equal(stale.headers.get("location"), `http://localhost:3000/assessments/${id}?step=capabilities&editError=stale`);
    assert.equal(version, 4); assert.equal(writes.length, writeCount); assert.deepEqual(profile, beforeConflict);
    capabilities.set("expectedVersion", "4"); raceOnWrite = true;
    const raced = await updateCapabilitiesRoute(request(`/${id}/capabilities`, capabilities), context);
    assert.equal(raced.headers.get("location"), `http://localhost:3000/assessments/${id}?step=capabilities&editError=stale`);
    assert.equal((await raced.text()).includes("Private upstream"), false);
    assert.equal(version, 5); assert.equal(writes.length, writeCount);
    assert.deepEqual(profile, { ...beforeConflict, operations: { ...beforeConflict.operations as Record<string, unknown>, hosting: "MANAGED" } });
    await revokeSession(sessionId);
    const callCount = calls.length;
    assert.equal((await usagePlanningRoute(request(`/${id}/usage-planning`, usage), context)).status, 401);
    assert.equal((await requirementsBriefRoute(request(`/${id}/requirements-brief`, exportForm), context)).status, 401);
    assert.equal(calls.length, callCount);
  } finally {
    await revokeSession(sessionId); globalThis.fetch = previousFetch;
    for (const name of names) if (previous[name] === undefined) delete process.env[name]; else process.env[name] = previous[name];
  }
});

test("saved-brief download requires a real live session, same origin and exact version with only a workspace-scoped Core GET", async () => {
  const names = ["AUTHWEAVE_OIDC_ISSUER", "AUTHWEAVE_OIDC_CLIENT_ID", "AUTHWEAVE_PUBLIC_ORIGIN", "AUTHWEAVE_CORE_SERVICE_TOKEN"];
  const previous = Object.fromEntries(names.map(name => [name, process.env[name]])), previousFetch = globalThis.fetch;
  Object.assign(process.env, { AUTHWEAVE_OIDC_ISSUER: "http://localhost:8081", AUTHWEAVE_OIDC_CLIENT_ID: "synthetic-client",
    AUTHWEAVE_PUBLIC_ORIGIN: "http://localhost:3000", AUTHWEAVE_CORE_SERVICE_TOKEN: "synthetic-export-token-000000000000000000000" });
  const id = "80000000-0000-4000-8000-000000000001", workspaceId = "70000000-0000-4000-8000-000000000001";
  const identity = { workspaceId, issuer: "http://localhost:8081", subject: "synthetic-export-owner",
    email: null, displayName: null, authenticatedAt: new Date() };
  const sessionId = await createSession(identity, undefined), context = { params: Promise.resolve({ id }) };
  let calls = 0, status = 200;
  let payload: Record<string, unknown> = { id, workspaceId, status: "ARCHIVED", version: 7, profileSchemaVersion: 6, profile: savedRequirementsFixture() };
  globalThis.fetch = async (url, init) => {
    calls++; assert.equal(url, `http://127.0.0.1:8080/api/v6/workspaces/${workspaceId}/assessments/${id}`);
    assert.equal(init?.method, "GET"); assert.equal(init?.body, undefined);
    assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error");
    const headers = init?.headers as Record<string, string>;
    assert.equal(headers["X-AuthWeave-Oidc-Subject"], identity.subject);
    assert.equal(headers["X-AuthWeave-Oidc-Issuer"], identity.issuer);
    assert.equal(headers.Authorization, process.env.AUTHWEAVE_CORE_SERVICE_TOKEN ? `Bearer ${process.env.AUTHWEAVE_CORE_SERVICE_TOKEN}` : "not-configured");
    return status === 200 ? Response.json(payload) : new Response("private Core failure and credential", { status });
  };
  const request = (body = "expectedVersion=7", overrides: { origin?: string; cookie?: string; type?: string; query?: string; contentLength?: string } = {}) =>
    new NextRequest(`http://localhost:3000/api/assessments/${id}/requirements-brief${overrides.query ?? ""}`, {
      method: "POST", headers: { Origin: overrides.origin ?? "http://localhost:3000", "Content-Type": overrides.type ?? "application/x-www-form-urlencoded",
        Cookie: overrides.cookie ?? `${sessionCookieName(false)}=${sessionId}`, "X-AuthWeave-Oidc-Subject": "browser-spoof",
        ...(overrides.contentLength === undefined ? {} : { "Content-Length": overrides.contentLength }) }, body,
    });
  const expect = async (response: Response, code: number) => {
    assert.equal(response.status, code); assert.equal(response.headers.get("cache-control"), "no-store");
    assert.equal((await response.text()).includes("private Core"), false);
  };
  try {
    await expect(await requirementsBriefRoute(request("expectedVersion=7", { origin: "https://other.invalid" }), context), 403);
    await expect(await requirementsBriefRoute(request("expectedVersion=7", { origin: "" }), context), 403);
    await expect(await requirementsBriefRoute(request("expectedVersion=7", { cookie: "" }), context), 401);
    await expect(await requirementsBriefRoute(request(), { params: Promise.resolve({ id: "../other" }) }), 404);
    await expect(await requirementsBriefRoute(request("{}", { type: "application/json" }), context), 415);
    await expect(await requirementsBriefRoute(request("expectedVersion=7", { query: "?workspaceId=other" }), context), 400);
    for (const body of ["", "expectedVersion=07", "expectedVersion=7&expectedVersion=7", "expectedVersion=7&profile=private", "expectedVersion=7&workspaceId=other", "expectedVersion=9007199254740992"]) {
      await expect(await requirementsBriefRoute(request(body), context), 400);
    }
    await expect(await requirementsBriefRoute(request("expectedVersion=7&padding=" + "x".repeat(500), { contentLength: "1" }), context), 413);
    assert.equal(calls, 0);
    const before = structuredClone(payload), response = await requirementsBriefRoute(request(), context);
    assert.equal(response.status, 200); assert.equal(response.headers.get("content-type"), "text/markdown; charset=utf-8");
    assert.equal(response.headers.get("x-content-type-options"), "nosniff"); assert.equal(response.headers.get("cache-control"), "no-store");
    const text = await response.text(); assert.ok(text.includes("- Assessment status: `ARCHIVED`"));
    for (const value of [identity.subject, identity.issuer, workspaceId, "browser-spoof", process.env.AUTHWEAVE_CORE_SERVICE_TOKEN!]) assert.equal(text.includes(value), false);
    assert.deepEqual(payload, before); assert.equal(calls, 1);
    await expect(await requirementsBriefRoute(request("expectedVersion=6"), context), 409); assert.equal(calls, 2);
    for (const code of [404, 403, 500]) {
      status = code; await expect(await requirementsBriefRoute(request(), context), code === 404 ? 404 : 503);
    }
    status = 200;
    for (const changed of [{ ...before, workspaceId: "70000000-0000-4000-8000-000000000002" },
      { ...before, id: "80000000-0000-4000-8000-000000000002" }, { ...before, profileSchemaVersion: 5 }]) {
      payload = changed; await expect(await requirementsBriefRoute(request(), context), 503);
    }
    payload = before;
    delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    const beforeMissingCredential = calls;
    await expect(await requirementsBriefRoute(request(), context), 503); assert.equal(calls, beforeMissingCredential);
    await revokeSession(sessionId); const beforeRevoked = calls;
    await expect(await requirementsBriefRoute(request(), context), 401); assert.equal(calls, beforeRevoked);
  } finally {
    await revokeSession(sessionId); globalThis.fetch = previousFetch;
    for (const name of names) if (previous[name] === undefined) delete process.env[name]; else process.env[name] = previous[name];
  }
});

test("auditability preview reads with a live DB session, cannot cross ownership/version and never writes", async () => {
  const names = ["AUTHWEAVE_OIDC_ISSUER", "AUTHWEAVE_OIDC_CLIENT_ID", "AUTHWEAVE_PUBLIC_ORIGIN", "AUTHWEAVE_CORE_SERVICE_TOKEN"];
  const saved = Object.fromEntries(names.map(name => [name, process.env[name]])), previousFetch = globalThis.fetch;
  Object.assign(process.env, { AUTHWEAVE_OIDC_ISSUER: "http://localhost:8081", AUTHWEAVE_OIDC_CLIENT_ID: "synthetic-client",
    AUTHWEAVE_PUBLIC_ORIGIN: "http://localhost:3000", AUTHWEAVE_CORE_SERVICE_TOKEN: "synthetic-auditability-preview-token-000000000000000000" });
  const identity = { workspaceId: auditabilityWorkspaceId, issuer: "http://localhost:8081", subject: "synthetic-auditability-preview-owner",
    email: null, displayName: null, authenticatedAt: new Date() };
  const sessionId = await createSession(identity, undefined);
  let calls = 0, fixture = auditabilityFixture();
  globalThis.fetch = async (url, init) => {
    calls++; assert.equal(init?.method, "GET"); assert.equal(init?.body, undefined);
    assert.equal(String(url), `http://127.0.0.1:8080/api/v6/workspaces/${identity.workspaceId}/assessments/${auditabilityAssessmentId}/auditability-capability-preflight`);
    assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error");
    const headers = init?.headers as Record<string, string>;
    assert.equal(headers["X-AuthWeave-Oidc-Subject"], identity.subject);
    assert.equal(headers["X-AuthWeave-Oidc-Issuer"], identity.issuer);
    return Response.json(fixture);
  };
  try {
    const live = await touchSession(sessionId); assert.ok(live);
    const before = structuredClone(fixture);
    const result = await readPersonalAuditability(live, auditabilityAssessmentId, 2, auditabilityInput);
    assert.equal(result.assessmentVersion, 2); assert.deepEqual(fixture, before);
    for (const foreign of [{ ...before, workspaceId: "70000000-0000-4000-8000-000000000002" },
      { ...before, assessmentId: "80000000-0000-4000-8000-000000000002" }, { ...before, assessmentVersion: 3 }]) {
      fixture = foreign; await assert.rejects(readPersonalAuditability(live, auditabilityAssessmentId, 2, auditabilityInput));
    }
    await revokeSession(sessionId);
    assert.equal(await touchSession(sessionId), null);
    assert.equal(calls, 4); // The page must resolve a live session before invoking this server-only reader.
  } finally {
    await revokeSession(sessionId); globalThis.fetch = previousFetch;
    for (const name of names) if (saved[name] === undefined) delete process.env[name]; else process.env[name] = saved[name];
  }
});

test("auditability route binds a real session to v6 writes, explicit clear and sanitized conflicts", async () => {
  const names = ["AUTHWEAVE_OIDC_ISSUER", "AUTHWEAVE_OIDC_CLIENT_ID", "AUTHWEAVE_PUBLIC_ORIGIN", "AUTHWEAVE_CORE_SERVICE_TOKEN"];
  const saved = Object.fromEntries(names.map(name => [name, process.env[name]])), previousFetch = globalThis.fetch;
  Object.assign(process.env, { AUTHWEAVE_OIDC_ISSUER: "http://localhost:8081", AUTHWEAVE_OIDC_CLIENT_ID: "synthetic-client",
    AUTHWEAVE_PUBLIC_ORIGIN: "http://localhost:3000", AUTHWEAVE_CORE_SERVICE_TOKEN: "synthetic-auditability-token-000000000000000000" });
  const id = "80000000-0000-4000-8000-000000000001", workspaceId = "70000000-0000-4000-8000-000000000001";
  const identity = { workspaceId, issuer: "http://localhost:8081", subject: "synthetic-auditability-owner",
    email: null, displayName: null, authenticatedAt: new Date() };
  const sessionId = await createSession(identity, undefined);
  const context = { params: Promise.resolve({ id }) };
  let profile = { application: { type: "B2B_SAAS" }, operations: { hosting: "MANAGED" },
    security: { assurance: "UNKNOWN", auditability: "REQUIRED", auditabilityRequirements: {
      selectedCriteria: ["AUTHENTICATION_FAILURE_EVENTS", "AUDIT_LOG_RETENTION"], minimumRetentionDays: 90 as number | null } } };
  const original = structuredClone(profile);
  let version = 2, upstream = 200, status = "DRAFT", wrongWorkspace = false;
  const calls: string[] = [];
  globalThis.fetch = async (url, init) => {
    calls.push(`${init?.method} ${url}`);
    assert.equal(String(url), `http://127.0.0.1:8080/api/v6/workspaces/${workspaceId}/assessments/${id}${init?.method === "PUT" ? "/profile" : ""}`);
    assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error"); assert.ok(init?.signal instanceof AbortSignal);
    assert.equal((init?.headers as Record<string, string>).Authorization, `Bearer ${process.env.AUTHWEAVE_CORE_SERVICE_TOKEN}`);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], identity.subject);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Issuer"], identity.issuer);
    if (init?.method === "GET") return Response.json({ id, workspaceId: wrongWorkspace ? "70000000-0000-4000-8000-000000000002" : workspaceId,
      status, version, profileSchemaVersion: 6, profile });
    if (upstream !== 200) return new Response("Private upstream details", { status: upstream });
    const update = JSON.parse(String(init?.body));
    assert.equal(update.expectedVersion, version);
    assert.deepEqual(update.profile.application, original.application); assert.deepEqual(update.profile.operations, original.operations);
    assert.equal(update.profile.security.assurance, original.security.assurance);
    profile = update.profile; version++;
    return Response.json({ id, workspaceId, status, version, profileSchemaVersion: 6, profile });
  };
  const form = "expectedVersion=2&criticality=REQUIRED&selectedCriteria=AUDIT_LOG_RETENTION&minimumRetentionDays=180";
  const request = (body: string | ArrayBuffer = form, cookie: string | null = sessionId, origin: string | null = "http://localhost:3000",
    contentType = "application/x-www-form-urlencoded") => new NextRequest(`http://localhost:3000/api/assessments/${id}/auditability`, {
      method: "POST", headers: { ...(origin ? { Origin: origin } : {}), "Content-Type": contentType,
        "X-AuthWeave-Oidc-Subject": "browser-spoof", "X-AuthWeave-Oidc-Issuer": "https://wrong.example.invalid",
        ...(cookie ? { Cookie: `${sessionCookieName(false)}=${cookie}` } : {}) }, body });
  try {
    for (const [req, code] of [[request(form, null), 401], [request(form, sessionId, null), 403],
      [request(form, sessionId, "https://wrong.example.invalid"), 403], [request(form, sessionId, "http://localhost:3000", "application/json"), 415],
      [request(form + "&workspaceId=" + workspaceId), 400], [request(form + "&configurationVerified=true"), 400],
      [request(form + "&selectedCriteria=AUDIT_LOG_RETENTION"), 400], [request(form.replace("180", "0")), 400],
      [request("x".repeat(2049)), 413], [request(new Uint8Array([0xff]).buffer), 400]] as const) {
      const result = await auditabilityRoute(req, context);
      assert.equal(result.status, code); assert.equal(result.headers.get("cache-control"), "no-store");
    }
    assert.equal(calls.length, 0);
    const result = await auditabilityRoute(request(), context);
    assert.equal(result.status, 303); assert.equal(result.headers.get("referrer-policy"), "no-referrer");
    assert.equal(result.headers.get("location"), `http://localhost:3000/assessments/${id}?step=auditability`);
    assert.equal(profile.security.auditabilityRequirements.minimumRetentionDays, 180);
    const stale = await auditabilityRoute(request(), context);
    assert.equal(stale.headers.get("location"), `http://localhost:3000/assessments/${id}?step=auditability&auditError=stale`);
    assert.equal(calls.length, 3);
    const clear = "expectedVersion=3&criticality=REQUIRED";
    for (const [code, error] of [[409, "stale"], [400, "invalid"], [422, "invalid"]] as const) {
      upstream = code;
      const failed = await auditabilityRoute(request(clear), context);
      assert.equal(failed.headers.get("location"), `http://localhost:3000/assessments/${id}?step=auditability&auditError=${error}`);
      assert.equal((await failed.text()).includes("Private upstream"), false);
    }
    for (const [code, expected] of [[404, 404], [403, 503], [503, 503]] as const) {
      upstream = code;
      const failed = await auditabilityRoute(request(clear), context);
      assert.equal(failed.status, expected); assert.equal((await failed.text()).includes("Private upstream"), false);
    }
    upstream = 200; status = "ARCHIVED";
    const locked = await auditabilityRoute(request(clear), context);
    assert.equal(locked.headers.get("location"), `http://localhost:3000/assessments/${id}?step=auditability&auditError=locked`);
    status = "DRAFT"; wrongWorkspace = true;
    assert.equal((await auditabilityRoute(request(clear), context)).status, 503);
    wrongWorkspace = false;
    assert.equal((await auditabilityRoute(request(clear), context)).status, 303);
    assert.deepEqual(profile.security.auditabilityRequirements, { selectedCriteria: [], minimumRetentionDays: null });
    assert.equal(profile.security.auditability, "REQUIRED");
    await revokeSession(sessionId);
    const count = calls.length;
    assert.equal((await auditabilityRoute(request(clear), context)).status, 401);
    assert.equal(calls.length, count);
  } finally {
    await revokeSession(sessionId); globalThis.fetch = previousFetch;
    for (const name of names) {
      if (saved[name] === undefined) delete process.env[name]; else process.env[name] = saved[name];
    }
  }
});

test("prerequisite preview uses a real DB session, same origin and server-only identity without assessment writes", async () => {
  const names = ["AUTHWEAVE_OIDC_ISSUER", "AUTHWEAVE_OIDC_CLIENT_ID", "AUTHWEAVE_PUBLIC_ORIGIN", "AUTHWEAVE_CORE_SERVICE_TOKEN"];
  const saved = Object.fromEntries(names.map(name => [name, process.env[name]]));
  const previousFetch = globalThis.fetch;
  Object.assign(process.env, { AUTHWEAVE_OIDC_ISSUER: "http://localhost:8081", AUTHWEAVE_OIDC_CLIENT_ID: "synthetic-client",
    AUTHWEAVE_PUBLIC_ORIGIN: "http://localhost:3000", AUTHWEAVE_CORE_SERVICE_TOKEN: "synthetic-prerequisite-token-000000000000000000" });
  const identity = { workspaceId: prerequisiteWorkspaceId, issuer: "http://localhost:8081", subject: "synthetic-prerequisite-owner",
    email: null, displayName: null, authenticatedAt: new Date() };
  const sessionId = await createSession(identity, undefined);
  const context = { params: Promise.resolve({ id: prerequisiteAssessmentId }) };
  const form = "expectedVersion=2&patternId=BFF_SESSION&BFF_BACKEND_API_PROXY=SATISFIED&BFF_SESSION_DEFENSES=UNKNOWN";
  const request = (body = form, cookie: string | null = sessionId, origin = "http://localhost:3000", contentType = "application/x-www-form-urlencoded") =>
    new NextRequest(`http://localhost:3000/api/assessments/${prerequisiteAssessmentId}/architecture-prerequisites`, {
      method: "POST", headers: { Origin: origin, "Content-Type": contentType,
        "X-AuthWeave-Oidc-Subject": "browser-spoof", "X-AuthWeave-Oidc-Issuer": "https://wrong.example.invalid",
        ...(cookie ? { Cookie: `${sessionCookieName(false)}=${cookie}` } : {}) }, body });
  let upstreamStatus = 200, version = 2;
  const calls: string[] = [];
  globalThis.fetch = async (url, init) => {
    calls.push(`${init?.method} ${url}`);
    const headers = init?.headers as Record<string, string>;
    assert.equal(headers["X-AuthWeave-Oidc-Subject"], identity.subject);
    assert.equal(headers["X-AuthWeave-Oidc-Issuer"], identity.issuer);
    assert.equal(headers.Authorization, `Bearer ${process.env.AUTHWEAVE_CORE_SERVICE_TOKEN}`);
    assert.ok(String(url).startsWith(`http://127.0.0.1:8080/api/v`));
    assert.ok(String(url).includes(`/workspaces/${identity.workspaceId}/assessments/${prerequisiteAssessmentId}`));
    if (init?.method === "GET") return Response.json({ id: prerequisiteAssessmentId, workspaceId: identity.workspaceId,
      status: "DRAFT", version, profileSchemaVersion: 6, profile: prerequisiteProfile });
    assert.equal(init?.method, "POST"); assert.ok(String(url).endsWith("/architecture-prerequisite-preview"));
    assert.deepEqual(JSON.parse(String(init?.body)), prerequisiteInput);
    return upstreamStatus === 200 ? Response.json(prerequisiteFixture()) : new Response("Private upstream details", { status: upstreamStatus });
  };
  try {
    for (const [req, code] of [[request(form, null), 401], [request(form, sessionId, "https://other.example.invalid"), 403],
      [request(form + "&clientScope=SELECTED"), 400], [request(form + "&workspaceId=" + identity.workspaceId), 400],
      [request(form + "&BFF_SESSION_DEFENSES=SATISFIED"), 400], [request(form + "&SPA_TOKEN_THREAT_MODEL=UNKNOWN"), 400],
      [request("x".repeat(2049)), 413], [request(form, sessionId, "http://localhost:3000", "application/json"), 415]] as const) {
      const result = await prerequisitePreviewRoute(req, context);
      assert.equal(result.status, code); assert.equal(result.headers.get("cache-control"), "no-store");
    }
    assert.equal(calls.length, 0);
    const result = await prerequisitePreviewRoute(request(), context);
    assert.equal(result.status, 200); assert.equal(result.headers.get("cache-control"), "no-store");
    const preview = await result.json();
    assert.equal(preview.analysis.status, "NEEDS_INFORMATION");
    assert.deepEqual(Object.keys(preview).sort(), ["analysis", "assessmentVersion"]);
    assert.equal(JSON.stringify(preview).includes(identity.subject), false);
    for (const [upstream, code] of [[400, 400], [404, 404], [409, 409], [403, 503], [503, 503]] as const) {
      upstreamStatus = upstream;
      const failed = await prerequisitePreviewRoute(request(), context);
      assert.equal(failed.status, code); assert.equal((await failed.text()).includes("Private upstream"), false);
    }
    upstreamStatus = 200; version = 3;
    const count = calls.length;
    assert.equal((await prerequisitePreviewRoute(request(), context)).status, 409);
    assert.equal(calls.length, count + 1);
    await revokeSession(sessionId);
    assert.equal((await prerequisitePreviewRoute(request(), context)).status, 401);
    assert.equal(calls.length, count + 1);
  } finally {
    await revokeSession(sessionId); globalThis.fetch = previousFetch;
    for (const name of names) if (saved[name] === undefined) delete process.env[name]; else process.env[name] = saved[name];
  }
});

test("bootstrap routes use real DB sessions, exact curator scope and same-origin writes without publication or source fetch", async () => {
  const names = ["AUTHWEAVE_OIDC_ISSUER", "AUTHWEAVE_OIDC_CLIENT_ID", "AUTHWEAVE_PUBLIC_ORIGIN",
    "AUTHWEAVE_CORE_SERVICE_TOKEN", "AUTHWEAVE_OIDC_PROJECT_ID", "AUTHWEAVE_OIDC_ORG_ID"];
  const saved = Object.fromEntries(names.map(name => [name, process.env[name]]));
  const previousFetch = globalThis.fetch;
  Object.assign(process.env, { AUTHWEAVE_OIDC_ISSUER: "http://localhost:8081", AUTHWEAVE_OIDC_CLIENT_ID: "synthetic-client",
    AUTHWEAVE_PUBLIC_ORIGIN: "http://localhost:3000", AUTHWEAVE_CORE_SERVICE_TOKEN: "synthetic-bootstrap-token-000000000000000000",
    AUTHWEAVE_OIDC_PROJECT_ID: "123456789012345678", AUTHWEAVE_OIDC_ORG_ID: "987654321098765432" });
  const scope = { projectId: process.env.AUTHWEAVE_OIDC_PROJECT_ID!, organizationId: process.env.AUTHWEAVE_OIDC_ORG_ID! };
  const identity = { workspaceId: "70000000-0000-4000-8000-000000000001", issuer: "http://localhost:8081",
    subject: "synthetic-bootstrap-curator", email: null, displayName: null, authenticatedAt: new Date(), curatorScope: scope };
  const ids: string[] = [];
  try {
    const curator = await createSession(identity, undefined); ids.push(curator);
    const ordinary = await createSession({ ...identity, curatorScope: null }, undefined); ids.push(ordinary);
    const stale = await createSession({ ...identity, authenticatedAt: new Date(Date.now() - 900001) }, undefined); ids.push(stale);
    const evidence = { sourceUrl: "https://bootstrap.example.invalid/source", observedAt: "2026-09-01T00:00:00Z", summary: "Fictional test claim only." };
    const candidate = { schemaVersion: 1, kind: "PROVIDER_CATALOG_DRAFT", catalogVersion: "bootstrap-fixture-1", options: [{
      id: "example-eu", providerId: "example", product: "Example Identity", plan: "Example Plan", deployment: "MANAGED",
      region: "EU", configuration: "Example", facts: { SCIM: { availability: "UNKNOWN", conditions: [], evidence } },
      compatibility: {}, residency: {}, authenticationControls: {},
    }] };
    const candidateSha256 = "a".repeat(64), reviewSha256 = "b".repeat(64);
    const request = (path: string, id: string | null, body?: unknown, origin = "http://localhost:3000") => new NextRequest(
      `http://localhost:3000${path}`, { method: body === undefined ? "GET" : "POST",
        headers: { Origin: origin, "Content-Type": "application/json", "X-AuthWeave-Oidc-Subject": "browser-spoof",
          ...(id ? { Cookie: `${sessionCookieName(false)}=${id}` } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const calls: string[] = [];
    let stored: Record<string, unknown> | null = null, writeStatus = 201;
    globalThis.fetch = async (url, init) => {
      calls.push(String(url));
      if (String(url).endsWith("/catalog-drafts/validate")) {
        assert.deepEqual(JSON.parse(String(init?.body)), candidate);
        return Response.json({ scope: "CATALOG_DRAFT_VALIDATION", policyVersion: "catalog-draft-validation-1",
          canonicalizationVersion: "catalog-draft-canonical-json-1", catalogVersion: candidate.catalogVersion, catalogSchemaVersion: 1,
          evaluatedAt: new Date().toISOString(), status: "VALID_DRAFT", contentSha256: candidateSha256, sourceVerificationPerformed: false,
          approvalGranted: false, writesPerformed: false, evaluationReady: false, optionCount: 1, factCount: 1, issues: [],
          facts: [{ optionId: "example-eu", path: "facts.SCIM", evidenceStatus: "UNREVIEWED", freshness: "CURRENT", conditions: [], evidence }] });
      }
      const headers = init?.headers as Record<string, string>;
      assert.equal(headers["X-AuthWeave-Oidc-Subject"], identity.subject);
      assert.equal(headers["X-AuthWeave-Curator-Project-Id"], scope.projectId);
      assert.equal(headers["X-AuthWeave-Curator-Org-Id"], scope.organizationId);
      assert.match(headers.Authorization, /^Bearer synthetic-bootstrap-token-/);
      if (String(url).endsWith("/authorization")) return new Response(null, { status: 204 });
      assert.ok(String(url).startsWith("http://127.0.0.1:8080/api/v1/catalog-bootstrap-reviews"));
      if (init?.method === "POST") {
        const input = JSON.parse(String(init.body));
        stored = { reviewId: input.reviewId, candidateSha256, reviewSha256, catalogVersion: candidate.catalogVersion,
          factCount: 1, counts: { supporting: 0, contradicting: 0, insufficient: 1 }, recordedAt: new Date().toISOString(),
          policyVersion: "catalog-bootstrap-source-review-1", kind: "HUMAN_BOOTSTRAP_SOURCE_REVIEW",
          sourceVerificationPerformed: false, approvalGranted: false, catalogWritesPerformed: false, factTrustChanged: false };
        return Response.json(stored, { status: writeStatus });
      }
      assert.ok(String(url).endsWith(`?expectedSha256=${reviewSha256}`));
      return Response.json(stored);
    };
    const path = "/api/catalog-bootstrap-reviews";
    for (const [id, origin, status] of [[curator, "https://evil.invalid", 403], [null, "http://localhost:3000", 401],
      [ordinary, "http://localhost:3000", 403], [stale, "http://localhost:3000", 403]] as const) {
      assert.equal((await prepareBootstrapRoute(request(`${path}/prepare`, id, candidate, origin))).status, status);
    }
    assert.deepEqual(calls, []);
    const preparation = await prepareBootstrapRoute(request(`${path}/prepare`, curator, candidate));
    assert.equal(preparation.status, 200);
    const prepared = await preparation.json();
    assert.equal(prepared.facts[0].claim.availability, "UNKNOWN");
    const input = { schemaVersion: 1, reviewId: prepared.reviewId, expectedCandidateSha256: candidateSha256, candidate,
      observations: [{ optionId: "example-eu", factPath: "facts.SCIM", verdict: "INSUFFICIENT_EVIDENCE" }], confirmation: "MANUAL_BOOTSTRAP_SOURCE_REVIEW" };
    calls.length = 0;
    assert.equal((await recordBootstrapRoute(request(path, curator, { ...input, actor: "caller" }))).status, 400);
    assert.equal((await recordBootstrapRoute(request(path, ordinary, input))).status, 403);
    assert.equal((await recordBootstrapRoute(request(path, stale, input))).status, 403);
    assert.deepEqual(calls, []);
    const recorded = await recordBootstrapRoute(request(path, curator, input));
    assert.equal(recorded.status, 201); assert.equal(recorded.headers.get("cache-control"), "no-store");
    assert.deepEqual(await recorded.json(), stored);
    writeStatus = 200;
    assert.equal((await recordBootstrapRoute(request(path, curator, input))).status, 200);
    const context = { params: Promise.resolve({ id: prepared.reviewId }) };
    assert.equal((await readBootstrapRoute(request(`${path}/${prepared.reviewId}?expectedSha256=${reviewSha256}`, ordinary), context)).status, 403);
    const read = await readBootstrapRoute(request(`${path}/${prepared.reviewId}?expectedSha256=${reviewSha256}`, curator), context);
    assert.equal(read.status, 200); assert.deepEqual(await read.json(), stored);
    assert.ok(calls.every((url: string) => url.startsWith("http://127.0.0.1:8080/")));
  } finally {
    globalThis.fetch = previousFetch;
    for (const id of ids) await revokeSession(id);
    for (const name of names) { if (saved[name] === undefined) delete process.env[name]; else process.env[name] = saved[name]; }
  }
});

test("login state is browser-bound, expires in the database and can be consumed only once", async () => {
  const pool = authDatabase();
  const state = randomOpaqueValue();
  const binding = randomOpaqueValue();
  await beginLogin(state, binding, "synthetic-code-verifier", "synthetic-nonce", pool);
  assert.equal(await consumeLogin(state, randomOpaqueValue(), pool), null);
  const consumed = await consumeLogin(state, binding, pool);
  assert.equal(consumed?.codeVerifier, "synthetic-code-verifier");
  assert.equal(consumed?.nonce, "synthetic-nonce");
  assert.equal(consumed?.purpose, "LOGIN");
  assert.ok(consumed?.startedAt instanceof Date);
  assert.equal(await consumeLogin(state, binding, pool), null);

  const expiredState = randomOpaqueValue();
  await pool.query(
    `INSERT INTO web.oidc_login_transactions
       (state_hash, browser_binding_hash, code_verifier, nonce, created_at, expires_at)
     VALUES ($1, $2, 'expired-verifier', 'expired-nonce',
             CURRENT_TIMESTAMP - INTERVAL '11 minutes',
             CURRENT_TIMESTAMP - INTERVAL '1 minute')`,
    [opaqueHash(expiredState), opaqueHash(binding)],
  );
  try {
    assert.equal(await consumeLogin(expiredState, binding, pool), null);
  } finally {
    await pool.query("DELETE FROM web.oidc_login_transactions WHERE state_hash = $1",
      [opaqueHash(expiredState)]);
  }
});

test("step-up transaction is one-use and bound to the existing session cookie", async () => {
  const pool = authDatabase();
  const identity = {
    workspaceId: "70000000-0000-4000-8000-000000000001",
    issuer: "https://synthetic.example.test", subject: "step-up-subject",
    email: null, displayName: null, authenticatedAt: new Date(),
  };
  const currentId = await createSession(identity, undefined, pool);
  const state = randomOpaqueValue();
  const binding = randomOpaqueValue();
  try {
    await beginReauthentication(state, binding, "step-up-verifier", "step-up-nonce", currentId, pool);
    assert.equal(await consumeLogin(state, randomOpaqueValue(), pool, currentId), null);
    assert.equal(await consumeLogin(state, binding, pool), null);
    assert.equal(await consumeLogin(state, binding, pool, randomOpaqueValue()), null);
    const consumed = await consumeLogin(state, binding, pool, currentId);
    assert.equal(consumed?.purpose, "REAUTH");
    assert.equal(consumed?.codeVerifier, "step-up-verifier");
    assert.ok(consumed?.startedAt instanceof Date);
    assert.equal(await consumeLogin(state, binding, pool, currentId), null);

    await assert.rejects(createSession({ ...identity, subject: "another-user" }, currentId,
      pool, true), /Could not establish web session/);
    assert.equal((await touchSession(currentId, pool))?.subject, identity.subject);
    const rotatedId = await createSession(identity, currentId, pool, true);
    try {
      assert.equal(await touchSession(currentId, pool), null);
      assert.equal((await touchSession(rotatedId, pool))?.subject, identity.subject);
      await assert.rejects(createSession(identity, currentId, pool, true), /Could not establish web session/);
    } finally {
      await revokeSession(rotatedId, pool);
    }
  } finally {
    await revokeSession(currentId, pool);
  }
});

test("opaque session rotates, idle expiry rejects access, and logout revokes locally", async () => {
  const pool = authDatabase();
  const identity = {
    workspaceId: "70000000-0000-4000-8000-000000000001",
    issuer: "https://synthetic.example.test",
    subject: "synthetic-subject",
    email: "synthetic@example.test",
    displayName: "Synthetic User",
    authenticatedAt: new Date(),
  };
  const ids: string[] = [];
  try {
    const first = await createSession(identity, undefined, pool);
    ids.push(first);
    assert.equal((await touchSession(first, pool))?.subject, identity.subject);
    assert.equal((await touchSession(first, pool))?.curatorScope, null);
    const second = await createSession(identity, first, pool);
    ids.push(second);
    assert.equal(await touchSession(first, pool), null);
    assert.equal((await touchSession(second, pool))?.email, identity.email);
    assert.equal((await touchSession(second, pool))?.workspaceId, identity.workspaceId);

    await pool.query(
      `UPDATE web.sessions SET created_at = CURRENT_TIMESTAMP - INTERVAL '1 hour',
         idle_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
       WHERE session_hash = $1`, [opaqueHash(second)],
    );
    assert.equal(await touchSession(second, pool), null);
    await revokeSession(second, pool);
    assert.equal(await touchSession(second, pool), null);

    const third = await createSession(identity, undefined, pool);
    ids.push(third);
    await pool.query(
      `UPDATE web.sessions SET created_at = CURRENT_TIMESTAMP - INTERVAL '9 hours',
         absolute_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second',
         idle_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
       WHERE session_hash = $1`, [opaqueHash(third)],
    );
    assert.equal(await touchSession(third, pool), null);
  } finally {
    for (const id of ids) await revokeSession(id, pool);
  }
});

test("curator grant is persisted only with its project and organization scope", async () => {
  const pool = authDatabase();
  const curatorScope = { projectId: "123456789012345678", organizationId: "987654321012345678" };
  const authenticatedAt = new Date();
  const id = await createSession({
    workspaceId: "70000000-0000-4000-8000-000000000001",
    issuer: "https://synthetic.example.test", subject: "synthetic-curator",
    email: null, displayName: null, authenticatedAt, curatorScope,
  }, undefined, pool);
  try {
    const session = await touchSession(id, pool);
    assert.deepEqual(session?.curatorScope, curatorScope);
    assert.equal(freshCuratorGrant(session?.curatorScope, curatorScope,
      session?.authenticatedAt ?? new Date("invalid")), true);
    assert.equal(freshCuratorGrant(session?.curatorScope, { ...curatorScope, projectId: "111" },
      session?.authenticatedAt ?? new Date("invalid")), false);
  } finally {
    await revokeSession(id, pool);
  }
  await assert.rejects(createSession({
    workspaceId: "70000000-0000-4000-8000-000000000001",
    issuer: "https://synthetic.example.test", subject: "malformed-curator-scope",
    email: null, displayName: null, authenticatedAt: new Date(),
    curatorScope: { projectId: "123", organizationId: "not-numeric" },
  }, undefined, pool), /Could not establish web session/);
});

test("curator rejection route requires same-origin, scoped fresh session and fixed Core write", async () => {
  const previous = {
    issuer: process.env.AUTHWEAVE_OIDC_ISSUER, clientId: process.env.AUTHWEAVE_OIDC_CLIENT_ID,
    origin: process.env.AUTHWEAVE_PUBLIC_ORIGIN, token: process.env.AUTHWEAVE_CORE_SERVICE_TOKEN,
    project: process.env.AUTHWEAVE_OIDC_PROJECT_ID, organization: process.env.AUTHWEAVE_OIDC_ORG_ID,
    fetch: globalThis.fetch,
  };
  process.env.AUTHWEAVE_OIDC_ISSUER = "http://localhost:8081";
  process.env.AUTHWEAVE_OIDC_CLIENT_ID = "synthetic-client";
  process.env.AUTHWEAVE_PUBLIC_ORIGIN = "http://localhost:3000";
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-internal-token-000000000000000000000";
  process.env.AUTHWEAVE_OIDC_PROJECT_ID = "123456789012345678";
  process.env.AUTHWEAVE_OIDC_ORG_ID = "987654321098765432";
  const proposalId = "90000000-0000-4000-8000-000000000001";
  const digest = "a".repeat(64);
  const input = { expectedVersion: 1, expectedSha256: digest, reasonCode: "OUT_OF_SCOPE" };
  const curatorScope = { projectId: process.env.AUTHWEAVE_OIDC_PROJECT_ID,
    organizationId: process.env.AUTHWEAVE_OIDC_ORG_ID };
  const identity = { workspaceId: "70000000-0000-4000-8000-000000000001",
    issuer: "http://localhost:8081", subject: "synthetic-rejection-curator", email: null,
    displayName: null, authenticatedAt: new Date(), curatorScope };
  const curatorSession = await createSession(identity, undefined);
  const ordinarySession = await createSession({ ...identity, subject: "ordinary-user", curatorScope: null }, undefined);
  const context = { params: Promise.resolve({ id: proposalId }) };
  const request = (origin: string, sessionId: string | null, body: unknown = input) => new NextRequest(
    `http://localhost:3000/api/catalog-change-proposals/${proposalId}/rejection`, {
      method: "POST", headers: { Origin: origin, "Content-Type": "application/json",
        ...(sessionId ? { Cookie: `${sessionCookieName(false)}=${sessionId}` } : {}) },
      body: JSON.stringify(body),
    });
  const calls: string[] = [];
  let rejectStatus = 201;
  globalThis.fetch = async (url, init) => {
    calls.push(`${init?.method} ${url}`);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], identity.subject);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Curator-Project-Id"],
      curatorScope.projectId);
    if (init?.method === "GET") return new Response(null, { status: 204 });
    assert.deepEqual(JSON.parse(String(init?.body)), input);
    if (rejectStatus === 409) return new Response(null, { status: 409 });
    return Response.json({ decisionId: "90000000-0000-4000-8000-000000000002",
      proposalId, proposalVersion: 1, proposalSha256: digest, decision: "REJECTED",
      reasonCode: "OUT_OF_SCOPE", recordedAt: new Date().toISOString() }, { status: 201 });
  };
  try {
    assert.equal((await rejectProposalRoute(request("https://evil.example.test", curatorSession), context)).status,
      403);
    assert.equal((await rejectProposalRoute(request("http://localhost:3000", null), context)).status, 401);
    assert.equal((await rejectProposalRoute(request("http://localhost:3000", ordinarySession), context)).status,
      403);
    assert.equal((await rejectProposalRoute(request("http://localhost:3000", curatorSession,
      { ...input, decision: "APPROVED" }), context)).status, 400);
    assert.deepEqual(calls, []);
    const response = await rejectProposalRoute(request("http://localhost:3000", curatorSession), context);
    assert.equal(response.status, 201);
    assert.equal(response.headers.get("cache-control"), "no-store");
    assert.equal((await response.json()).decision, "REJECTED");
    assert.deepEqual(calls, [
      "GET http://127.0.0.1:8080/internal/v1/catalog-curator/authorization",
      `POST http://127.0.0.1:8080/api/v1/catalog-change-proposals/${proposalId}/decisions/rejection`,
    ]);
    calls.length = 0;
    const form = new URLSearchParams({ expectedVersion: "1", expectedSha256: digest,
      reasonCode: "OUT_OF_SCOPE", confirm: "REJECT" });
    const formRequest = (body: string) => new NextRequest(
      `http://localhost:3000/api/catalog-change-proposals/${proposalId}/rejection`, {
        method: "POST", headers: { Origin: "http://localhost:3000",
          "Content-Type": "application/x-www-form-urlencoded",
          Cookie: `${sessionCookieName(false)}=${curatorSession}` }, body,
      });
    assert.equal((await rejectProposalRoute(formRequest(new URLSearchParams({
      expectedVersion: "1", expectedSha256: digest, reasonCode: "OUT_OF_SCOPE",
    }).toString()), context)).status, 400);
    const formResponse = await rejectProposalRoute(formRequest(form.toString()), context);
    assert.equal(formResponse.status, 303);
    assert.equal(formResponse.headers.get("location"),
      `http://localhost:3000/catalog/review/${proposalId}?result=rejected`);
    assert.equal(formResponse.headers.get("cache-control"), "no-store");
    assert.equal(calls.length, 2);
    calls.length = 0;
    rejectStatus = 409;
    const staleResponse = await rejectProposalRoute(formRequest(form.toString()), context);
    assert.equal(staleResponse.status, 303);
    assert.equal(staleResponse.headers.get("location"),
      `http://localhost:3000/catalog/review/${proposalId}?error=stale`);
    assert.equal(calls.length, 2);
    calls.length = 0;
    await authDatabase().query(`UPDATE web.sessions SET authenticated_at = CURRENT_TIMESTAMP - INTERVAL '16 minutes'
      WHERE session_hash = $1`, [opaqueHash(curatorSession)]);
    assert.equal((await rejectProposalRoute(request("http://localhost:3000", curatorSession), context)).status,
      403);
    assert.deepEqual(calls, []);
  } finally {
    await revokeSession(curatorSession);
    await revokeSession(ordinarySession);
    globalThis.fetch = previous.fetch;
    for (const [key, value] of [
      ["AUTHWEAVE_OIDC_ISSUER", previous.issuer], ["AUTHWEAVE_OIDC_CLIENT_ID", previous.clientId],
      ["AUTHWEAVE_PUBLIC_ORIGIN", previous.origin], ["AUTHWEAVE_CORE_SERVICE_TOKEN", previous.token],
      ["AUTHWEAVE_OIDC_PROJECT_ID", previous.project], ["AUTHWEAVE_OIDC_ORG_ID", previous.organization],
    ] as const) {
      if (value === undefined) delete process.env[key]; else process.env[key] = value;
    }
  }
});

test("manual fact-review route bounds JSON/native forms and uses only fresh scoped DB-session identity", async () => {
  const keys = ["AUTHWEAVE_OIDC_ISSUER", "AUTHWEAVE_OIDC_CLIENT_ID", "AUTHWEAVE_PUBLIC_ORIGIN",
    "AUTHWEAVE_CORE_SERVICE_TOKEN", "AUTHWEAVE_OIDC_PROJECT_ID", "AUTHWEAVE_OIDC_ORG_ID"];
  const previous = keys.map(key => process.env[key]); const previousFetch = globalThis.fetch;
  Object.assign(process.env, { AUTHWEAVE_OIDC_ISSUER: "http://localhost:8081", AUTHWEAVE_OIDC_CLIENT_ID: "synthetic-client",
    AUTHWEAVE_PUBLIC_ORIGIN: "http://localhost:3000", AUTHWEAVE_CORE_SERVICE_TOKEN: "synthetic-internal-token-000000000000000000000",
    AUTHWEAVE_OIDC_PROJECT_ID: "123456789012345678", AUTHWEAVE_OIDC_ORG_ID: "987654321098765432" });
  const id = "90000000-0000-4000-8000-000000000003";
  const input = { reviewId: "90000000-0000-4000-8000-000000000004", expectedVersion: 0, expectedSha256: "a".repeat(64),
    optionId: "example-managed-eu", factPath: "facts.OIDC", verdict: "SOURCE_SUPPORTS_CLAIM", confirmation: "MANUAL_SOURCE_REVIEW" };
  const identity = { workspaceId: "70000000-0000-4000-8000-000000000001", issuer: "http://localhost:8081",
    subject: "synthetic-fact-curator", email: null, displayName: null, authenticatedAt: new Date(),
    curatorScope: { projectId: "123456789012345678", organizationId: "987654321098765432" } };
  const curator = await createSession(identity, undefined);
  const ordinary = await createSession({ ...identity, curatorScope: null }, undefined);
  const context = { params: Promise.resolve({ id }) };
  const request = (session: string | null, body = JSON.stringify(input), headers: Record<string, string> = {}) =>
    new NextRequest(`http://localhost:3000/api/catalog-change-proposals/${id}/fact-reviews`, {
      method: "POST", headers: { Origin: "http://localhost:3000", "Content-Type": "application/json",
        ...(session ? { Cookie: `${sessionCookieName(false)}=${session}` } : {}), ...headers }, body });
  const form = new URLSearchParams(Object.entries(input).map(([key, value]) => [key, String(value)]));
  const formRequest = (session: string | null, body = form.toString(), headers: Record<string, string> = {}) =>
    request(session, body, { "Content-Type": "application/x-www-form-urlencoded", ...headers });
  const calls: string[] = []; let coreStatus = 201, reviewNumber = 1, forgedReceipt = false;
  globalThis.fetch = async (url, init) => {
    calls.push(`${init?.method} ${url}`);
    const headers = init?.headers as Record<string, string>;
    assert.equal(headers["X-AuthWeave-Oidc-Subject"], identity.subject);
    assert.equal(headers["X-AuthWeave-Curator-Role"], "catalog_curator"); assert.equal(headers.Cookie, undefined);
    if (init?.method === "GET") return new Response(null, { status: 204 });
    assert.deepEqual(JSON.parse(String(init?.body)), input);
    if (coreStatus !== 201 && coreStatus !== 200) return new Response(null, { status: coreStatus });
    return Response.json({ reviewId: input.reviewId, proposalId: id, proposalVersion: 0, proposalSha256: input.expectedSha256,
      reviewNumber, optionId: input.optionId, factPath: input.factPath, verdict: input.verdict, recordedAt: new Date().toISOString(),
      kind: "HUMAN_SOURCE_REVIEW_OBSERVATION", sourceVerificationPerformed: false, approvalGranted: false,
      catalogWritesPerformed: false, factTrustChanged: forgedReceipt }, { status: coreStatus });
  };
  try {
    assert.equal((await factReviewRoute(request(curator, undefined, { Origin: "https://evil.invalid" }), context)).status, 403);
    assert.equal((await factReviewRoute(request(curator, undefined, { Origin: "" }), context)).status, 403);
    assert.equal((await factReviewRoute(request(null), context)).status, 401);
    assert.equal((await factReviewRoute(request(ordinary), context)).status, 403);
    assert.equal((await factReviewRoute(request(curator, JSON.stringify({ ...input, actor: "forged" })), context)).status, 400);
    assert.equal((await factReviewRoute(request(curator, "{}"), context)).status, 400);
    assert.equal((await factReviewRoute(request(curator, "{"), context)).status, 400);
    assert.equal((await factReviewRoute(request(curator, undefined, { "Content-Type": "text/plain" }), context)).status, 415);
    assert.equal((await factReviewRoute(request(curator, undefined, { "Content-Length": "2049" }), context)).status, 413);
    assert.equal((await factReviewRoute(request(curator, " ".repeat(2049)), context)).status, 413);
    assert.deepEqual(calls, []);
    for (const status of [201, 200, 409, 404, 400, 403, 503]) {
      coreStatus = status; calls.length = 0;
      const response = await factReviewRoute(request(curator, undefined, { "X-AuthWeave-Oidc-Subject": "forged" }), context);
      assert.equal(response.status, status); assert.equal(response.headers.get("cache-control"), "no-store");
      assert.deepEqual(calls, ["GET http://127.0.0.1:8080/internal/v1/catalog-curator/authorization",
        `POST http://127.0.0.1:8080/api/v1/catalog-change-proposals/${id}/fact-reviews`]);
      if (status <= 201) assert.equal((await response.json()).factTrustChanged, false);
    }
    calls.length = 0;
    assert.equal((await factReviewRoute(formRequest(curator, undefined, { Origin: "https://evil.invalid" }), context)).status, 403);
    assert.equal((await factReviewRoute(formRequest(curator, undefined, { Origin: "" }), context)).status, 403);
    assert.equal((await factReviewRoute(formRequest(null), context)).status, 401);
    assert.equal((await factReviewRoute(formRequest(ordinary), context)).status, 403);
    for (const invalid of [form.toString() + "&reviewId=" + input.reviewId,
      form.toString().replace("confirmation=MANUAL_SOURCE_REVIEW", "confirmation=APPROVE"),
      form.toString() + "&actorSubject=forged", form.toString() + "&returnTo=https://evil.invalid"]) {
      assert.equal((await factReviewRoute(formRequest(curator, invalid), context)).status, 400);
    }
    assert.equal((await factReviewRoute(formRequest(curator, undefined, { "Content-Length": "2049" }), context)).status, 413);
    assert.equal((await factReviewRoute(formRequest(curator, "x".repeat(2049)), context)).status, 413);
    assert.equal((await factReviewRoute(formRequest(curator, undefined, { "Content-Length": "invalid" }), context)).status, 413);
    assert.deepEqual(calls, []);
    for (const status of [201, 200]) {
      coreStatus = status; reviewNumber = status === 201 ? 1 : 26; calls.length = 0;
      const response = await factReviewRoute(formRequest(curator, undefined,
        { "X-AuthWeave-Oidc-Subject": "forged" }), context);
      assert.equal(response.status, 303);
      assert.equal(response.headers.get("cache-control"), "no-store");
      assert.equal(response.headers.get("referrer-policy"), "no-referrer");
      assert.equal(response.headers.get("location"), `http://localhost:3000/catalog/review/${id}` +
        `?reviewVersion=0&reviewAfter=${Math.max(0, reviewNumber - 20)}&reviewResult=${input.reviewId}#fact-review-history`);
      assert.equal(calls.length, 2);
    }
    coreStatus = 409;
    const conflict = await factReviewRoute(formRequest(curator), context);
    assert.equal(conflict.status, 303);
    assert.equal(conflict.headers.get("location"), `http://localhost:3000/catalog/review/${id}?reviewError=conflict#candidate-evidence`);
    for (const status of [404, 400, 403]) {
      coreStatus = status;
      assert.equal((await factReviewRoute(formRequest(curator), context)).status, status);
    }
    for (const status of [503, 201]) {
      coreStatus = status; forgedReceipt = status === 201;
      const unconfirmed = await factReviewRoute(formRequest(curator), context);
      assert.equal(unconfirmed.status, 503); assert.equal(unconfirmed.headers.get("location"), null);
      assert.match(unconfirmed.headers.get("content-type")!, /text\/html/);
      const html = await unconfirmed.text();
      assert.match(html, /use the original form unchanged/);
      assert.match(html, /Retry this exact observation/);
      assert.ok(html.includes(`name="reviewId" value="${input.reviewId}"`));
      assert.ok(html.includes(`name="expectedSha256" value="${input.expectedSha256}"`));
      assert.match(html, /name="confirmation"[^>]*required/);
    }
    forgedReceipt = false;
    calls.length = 0;
    await authDatabase().query(`UPDATE web.sessions SET authenticated_at = CURRENT_TIMESTAMP - INTERVAL '16 minutes'
      WHERE session_hash = $1`, [opaqueHash(curator)]);
    assert.equal((await factReviewRoute(request(curator), context)).status, 403); assert.deepEqual(calls, []);
    const staleForm = await factReviewRoute(formRequest(curator), context);
    assert.equal(staleForm.status, 403); assert.match(await staleForm.text(), /verify this account again/);
    assert.deepEqual(calls, []);
  } finally {
    await revokeSession(curator); await revokeSession(ordinary); globalThis.fetch = previousFetch;
    keys.forEach((key, index) => { if (previous[index] === undefined) delete process.env[key]; else process.env[key] = previous[index]; });
  }
});

test("web runtime cannot inspect core data or migration history", async () => {
  const pool = authDatabase();
  for (const table of ["core.assessments", "core.personal_workspaces", "web.schema_migrations"]) {
    await assert.rejects(pool.query(`SELECT 1 FROM ${table} LIMIT 1`), (failure: unknown) =>
      typeof failure === "object" && failure !== null && "code" in failure && failure.code === "42501");
  }
  assert.equal(await touchSession(undefined, pool), null);
});

test("reauthentication route rejects cross-origin and anonymous requests before OIDC", async () => {
  const previousIssuer = process.env.AUTHWEAVE_OIDC_ISSUER;
  const previousClient = process.env.AUTHWEAVE_OIDC_CLIENT_ID;
  process.env.AUTHWEAVE_OIDC_ISSUER = "http://localhost:8081";
  process.env.AUTHWEAVE_OIDC_CLIENT_ID = "synthetic-client";
  const request = (origin: string) => new NextRequest("http://localhost:3000/api/auth/reauth", {
    method: "POST", headers: { Origin: origin },
  });
  try {
    assert.equal((await reauthenticateRoute(request("https://evil.example.test"))).status, 403);
    const anonymous = await reauthenticateRoute(request("http://localhost:3000"));
    assert.equal(anonymous.status, 401);
    assert.equal(anonymous.headers.get("cache-control"), "no-store");
  } finally {
    if (previousIssuer === undefined) delete process.env.AUTHWEAVE_OIDC_ISSUER;
    else process.env.AUTHWEAVE_OIDC_ISSUER = previousIssuer;
    if (previousClient === undefined) delete process.env.AUTHWEAVE_OIDC_CLIENT_ID;
    else process.env.AUTHWEAVE_OIDC_CLIENT_ID = previousClient;
  }
});

test("sessions created before workspace binding cannot gain workspace access", async () => {
  const pool = authDatabase();
  const id = randomOpaqueValue();
  await pool.query(
    `INSERT INTO web.sessions
       (session_hash, issuer, subject, authenticated_at, idle_expires_at, absolute_expires_at)
     VALUES ($1, 'https://synthetic.example.test', 'legacy-subject', CURRENT_TIMESTAMP,
             CURRENT_TIMESTAMP + INTERVAL '30 minutes', CURRENT_TIMESTAMP + INTERVAL '8 hours')`,
    [opaqueHash(id)],
  );
  try {
    assert.equal(await touchSession(id, pool), null);
  } finally {
    await revokeSession(id, pool);
  }
});

test("assessment route requires same-origin session and never trusts a browser workspace ID", async () => {
  const previous = {
    issuer: process.env.AUTHWEAVE_OIDC_ISSUER,
    clientId: process.env.AUTHWEAVE_OIDC_CLIENT_ID,
    origin: process.env.AUTHWEAVE_PUBLIC_ORIGIN,
    token: process.env.AUTHWEAVE_CORE_SERVICE_TOKEN,
    fetch: globalThis.fetch,
  };
  process.env.AUTHWEAVE_OIDC_ISSUER = "http://localhost:8081";
  process.env.AUTHWEAVE_OIDC_CLIENT_ID = "synthetic-client";
  process.env.AUTHWEAVE_PUBLIC_ORIGIN = "http://localhost:3000";
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-internal-token-000000000000000000000";
  const identity = {
    workspaceId: "70000000-0000-4000-8000-000000000001",
    issuer: "http://localhost:8081",
    subject: "synthetic-route-user",
    email: null,
    displayName: null,
    authenticatedAt: new Date(),
  };
  const id = await createSession(identity, undefined);
  let calls = 0;
  globalThis.fetch = async (input, init) => {
    calls++;
    assert.equal(input, `http://127.0.0.1:8080/api/v6/workspaces/${identity.workspaceId}/assessments`);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], identity.subject);
    return Response.json({
      id: "80000000-0000-4000-8000-000000000001", workspaceId: identity.workspaceId,
      status: "DRAFT", version: 0, profileSchemaVersion: 6, profile: { security: { auditability: "UNKNOWN",
        auditabilityRequirements: { selectedCriteria: [], minimumRetentionDays: null } } },
    }, { status: 201 });
  };
  const request = (origin: string, cookie?: string) => new NextRequest(
    "http://localhost:3000/api/assessments", {
      method: "POST", headers: {
        Origin: origin, ...(cookie ? { Cookie: `${sessionCookieName(false)}=${cookie}` } : {}),
      },
      body: JSON.stringify({ workspaceId: "90000000-0000-4000-8000-000000000001" }),
    },
  );
  try {
    assert.equal((await createAssessmentRoute(request("https://other.example.test", id))).status, 403);
    assert.equal((await createAssessmentRoute(request("http://localhost:3000"))).status, 401);
    assert.equal(calls, 0);
    const response = await createAssessmentRoute(request("http://localhost:3000", id));
    assert.equal(response.status, 303);
    assert.equal(response.headers.get("location"),
      "http://localhost:3000/assessments/80000000-0000-4000-8000-000000000001");
    assert.equal(response.headers.get("cache-control"), "no-store");
    assert.equal(calls, 1);
  } finally {
    await revokeSession(id);
    globalThis.fetch = previous.fetch;
    for (const [key, value] of [
      ["AUTHWEAVE_OIDC_ISSUER", previous.issuer],
      ["AUTHWEAVE_OIDC_CLIENT_ID", previous.clientId],
      ["AUTHWEAVE_PUBLIC_ORIGIN", previous.origin],
      ["AUTHWEAVE_CORE_SERVICE_TOKEN", previous.token],
    ] as const) {
      if (value === undefined) delete process.env[key]; else process.env[key] = value;
    }
  }
});

test("capability route enforces session, origin, form scope and optimistic version", async () => {
  const previous = {
    issuer: process.env.AUTHWEAVE_OIDC_ISSUER,
    clientId: process.env.AUTHWEAVE_OIDC_CLIENT_ID,
    origin: process.env.AUTHWEAVE_PUBLIC_ORIGIN,
    token: process.env.AUTHWEAVE_CORE_SERVICE_TOKEN,
    fetch: globalThis.fetch,
  };
  process.env.AUTHWEAVE_OIDC_ISSUER = "http://localhost:8081";
  process.env.AUTHWEAVE_OIDC_CLIENT_ID = "synthetic-client";
  process.env.AUTHWEAVE_PUBLIC_ORIGIN = "http://localhost:3000";
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-internal-token-000000000000000000000";
  const workspaceId = "70000000-0000-4000-8000-000000000001";
  const assessmentId = "80000000-0000-4000-8000-000000000001";
  const identity = {
    workspaceId, issuer: "http://localhost:8081", subject: "synthetic-capability-user",
    email: null, displayName: null, authenticatedAt: new Date(),
  };
  const sessionId = await createSession(identity, undefined);
  const profile = {
    application: { type: "B2B_SAAS" },
    protocols: { federation: {}, oauth2ProtectedApis: "UNKNOWN", socialLogin: "UNKNOWN",
      enterpriseSingleSignOn: "UNKNOWN" },
    provisioning: { scim: "UNKNOWN", justInTimeProvisioning: "UNKNOWN", groupSynchronization: "UNKNOWN" },
    security: { multiFactorAuthentication: "UNKNOWN", auditability: "REQUIRED",
      auditabilityRequirements: { selectedCriteria: ["AUDIT_LOG_RETENTION"], minimumRetentionDays: 90 } },
    operations: { retained: true },
  };
  const form = new URLSearchParams({ expectedVersion: "2" });
  for (const field of capabilityFields) form.set(field.capability, "UNKNOWN");
  form.set("SCIM", "PREFERRED");
  const context = { params: Promise.resolve({ id: assessmentId }) };
  const request = (origin: string | null, cookie: string | null, body = form.toString(),
    contentType = "application/x-www-form-urlencoded") => new NextRequest(
    `http://localhost:3000/api/assessments/${assessmentId}/capabilities`, {
      method: "POST", headers: {
        ...(origin ? { Origin: origin } : {}),
        ...(cookie ? { Cookie: `${sessionCookieName(false)}=${cookie}` } : {}),
        "Content-Type": contentType,
      }, body,
    },
  );
  const calls: string[] = [];
  globalThis.fetch = async (input, init) => {
    calls.push(`${init?.method} ${input}`);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], identity.subject);
    if (init?.method === "GET") return Response.json({ id: assessmentId, workspaceId,
      status: "DRAFT", version: 2, profileSchemaVersion: 6, profile });
    const update = JSON.parse(String(init?.body));
    assert.equal(update.expectedVersion, 2);
    assert.equal(update.profile.provisioning.scim, "PREFERRED");
    assert.deepEqual(update.profile.operations, profile.operations);
    return Response.json({ id: assessmentId, workspaceId, status: "DRAFT", version: 3,
      profileSchemaVersion: 6, profile: update.profile });
  };
  try {
    assert.equal((await updateCapabilitiesRoute(request("https://other.example.test", sessionId), context)).status, 403);
    assert.equal((await updateCapabilitiesRoute(request("http://localhost:3000", null), context)).status, 401);
    assert.equal((await updateCapabilitiesRoute(request("http://localhost:3000", sessionId,
      form.toString(), "application/json"), context)).status, 415);
    assert.equal((await updateCapabilitiesRoute(request("http://localhost:3000", sessionId,
      "expectedVersion=2"), context)).status, 400);
    assert.equal(calls.length, 0);
    const saved = await updateCapabilitiesRoute(request("http://localhost:3000", sessionId), context);
    assert.equal(saved.status, 303);
    assert.equal(saved.headers.get("location"), `http://localhost:3000/assessments/${assessmentId}?step=capabilities`);
    assert.equal(saved.headers.get("cache-control"), "no-store");
    assert.equal(calls.length, 2);
    const stale = new URLSearchParams(form);
    stale.set("expectedVersion", "1");
    const conflict = await updateCapabilitiesRoute(
      request("http://localhost:3000", sessionId, stale.toString()), context);
    assert.equal(conflict.status, 303);
    assert.equal(conflict.headers.get("location"),
      `http://localhost:3000/assessments/${assessmentId}?step=capabilities&editError=stale`);
    assert.equal(calls.length, 3);
  } finally {
    await revokeSession(sessionId);
    globalThis.fetch = previous.fetch;
    for (const [key, value] of [
      ["AUTHWEAVE_OIDC_ISSUER", previous.issuer],
      ["AUTHWEAVE_OIDC_CLIENT_ID", previous.clientId],
      ["AUTHWEAVE_PUBLIC_ORIGIN", previous.origin],
      ["AUTHWEAVE_CORE_SERVICE_TOKEN", previous.token],
    ] as const) {
      if (value === undefined) delete process.env[key]; else process.env[key] = value;
    }
  }
});

test("weighted preview route enforces origin and session without saving an assessment", async () => {
  const previous = {
    issuer: process.env.AUTHWEAVE_OIDC_ISSUER,
    clientId: process.env.AUTHWEAVE_OIDC_CLIENT_ID,
    origin: process.env.AUTHWEAVE_PUBLIC_ORIGIN,
    token: process.env.AUTHWEAVE_CORE_SERVICE_TOKEN,
    fetch: globalThis.fetch,
  };
  process.env.AUTHWEAVE_OIDC_ISSUER = "http://localhost:8081";
  process.env.AUTHWEAVE_OIDC_CLIENT_ID = "synthetic-client";
  process.env.AUTHWEAVE_PUBLIC_ORIGIN = "http://localhost:3000";
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-internal-token-000000000000000000000";
  const workspaceId = "70000000-0000-4000-8000-000000000001";
  const assessmentId = "80000000-0000-4000-8000-000000000001";
  const identity = {
    workspaceId, issuer: "http://localhost:8081", subject: "synthetic-weight-user",
    email: null, displayName: null, authenticatedAt: new Date(),
  };
  const sessionId = await createSession(identity, undefined);
  const profile = {
    protocols: { federation: { OIDC: "PREFERRED" }, oauth2ProtectedApis: "UNKNOWN",
      socialLogin: "UNKNOWN", enterpriseSingleSignOn: "UNKNOWN" },
    provisioning: { scim: "UNKNOWN", justInTimeProvisioning: "UNKNOWN", groupSynchronization: "UNKNOWN" },
    security: { multiFactorAuthentication: "UNKNOWN", auditability: "UNKNOWN",
      auditabilityRequirements: { selectedCriteria: [], minimumRetentionDays: null } },
  };
  const comparison = {
    workspaceId, assessmentId, assessmentVersion: 2, catalogVersion: "synthetic-test",
    catalogKind: "SYNTHETIC", policyVersion: "synthetic-comparison-1",
    hardConstraintPolicyVersion: "hard-constraint-preflight-1",
    preferencePolicyVersion: "capability-preference-1",
    evaluatedAt: "2026-09-22T12:00:00Z", scope: "SYNTHETIC_UNRANKED_COMPARISON",
    recommendationReady: false, rankingPerformed: false, deferredPaths: ["operations"],
    candidates: [{ optionId: "fictional-plan", displayName: "Fictional Plan", plan: "Demo",
      region: "Synthetic region", hardVerdict: "UNRESOLVED", exclusionReasons: [],
      informationGaps: [{ dimension: "COVERAGE", profilePath: "assessment",
        reasonCode: "NO_AFFIRMATIVE_CHECKS", explanation: "Clarify the requirements." }],
      capabilityPreferences: [{ capability: "OIDC", profilePath: "protocols.federation.OIDC",
        outcome: "UNKNOWN", reasonCode: "EVIDENCE_MISSING", explanation: "Evidence is missing.",
        evidence: null }],
    }],
  };
  const context = { params: Promise.resolve({ id: assessmentId }) };
  const request = (origin: string, cookie: string | null, body = "expectedVersion=2&OIDC=100") => new NextRequest(
    `http://localhost:3000/api/assessments/${assessmentId}/weighted-preview`, {
      method: "POST", headers: {
        Origin: origin, "Content-Type": "application/x-www-form-urlencoded",
        ...(cookie ? { Cookie: `${sessionCookieName(false)}=${cookie}` } : {}),
      }, body,
    },
  );
  const sensitivityRequest = (origin: string, cookie: string | null,
    body = "expectedVersion=2&baseline_OIDC=100&alternative_OIDC=100") => new NextRequest(
    `http://localhost:3000/api/assessments/${assessmentId}/weight-sensitivity`, {
      method: "POST", headers: {
        Origin: origin, "Content-Type": "application/x-www-form-urlencoded",
        ...(cookie ? { Cookie: `${sessionCookieName(false)}=${cookie}` } : {}),
      }, body,
    },
  );
  const calls: string[] = [];
  globalThis.fetch = async (input, init) => {
    calls.push(`${init?.method} ${input}`);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], identity.subject);
    if (init?.method === "GET") return Response.json({ id: assessmentId, workspaceId,
      status: "DRAFT", version: 2, profileSchemaVersion: 6, profile });
    if (String(input).endsWith("/weight-sensitivity-preview")) {
      assert.deepEqual(JSON.parse(String(init?.body)), {
        baselineWeights: { OIDC: 100 }, alternativeWeights: { OIDC: 100 },
      });
      const scores = [{ optionId: "fictional-plan", status: "UNRESOLVED_HARD_CONSTRAINTS",
        score: null, contributions: [] }];
      return Response.json({ comparison, scoringPolicyVersion: "explicit-capability-weights-1",
        sensitivityPolicyVersion: "explicit-weight-sensitivity-1",
        baseline: { weights: { OIDC: 100 }, scores }, alternative: { weights: { OIDC: 100 }, scores },
        deltas: [{ optionId: "fictional-plan", status: "UNRESOLVED_HARD_CONSTRAINTS",
          scoreDelta: null, capabilityDeltas: [] }], rankingPerformed: false, recommendationReady: false });
    }
    assert.deepEqual(JSON.parse(String(init?.body)), { weights: { OIDC: 100 } });
    return Response.json({ comparison, scoringPolicyVersion: "explicit-capability-weights-1",
      weights: { OIDC: 100 }, rankingPerformed: false, recommendationReady: false,
      scores: [{ optionId: "fictional-plan", status: "UNRESOLVED_HARD_CONSTRAINTS",
        score: null, contributions: [] }] });
  };
  try {
    assert.equal((await weightedPreviewRoute(request("https://other.example.test", sessionId), context)).status, 403);
    assert.equal((await weightedPreviewRoute(request("http://localhost:3000", null), context)).status, 401);
    assert.equal((await weightedPreviewRoute(request("http://localhost:3000", sessionId,
      "expectedVersion=2&OIDC=99"), context)).status, 400);
    assert.equal(calls.length, 0);
    const response = await weightedPreviewRoute(request("http://localhost:3000", sessionId), context);
    assert.equal(response.status, 200);
    assert.equal(response.headers.get("cache-control"), "no-store");
    const preview = await response.json();
    assert.equal(preview.candidates[0].score, null);
    assert.equal(preview.candidates[0].status, "UNRESOLVED_HARD_CONSTRAINTS");
    assert.equal("evidence" in preview.candidates[0], false);
    assert.deepEqual(calls.map(call => call.split(" ")[0]), ["GET", "POST"]);
    const stale = await weightedPreviewRoute(request("http://localhost:3000", sessionId,
      "expectedVersion=1&OIDC=100"), context);
    assert.equal(stale.status, 409);
    assert.equal(calls.length, 3);
    assert.equal((await weightSensitivityRoute(sensitivityRequest("https://other.example.test", sessionId), context)).status, 403);
    assert.equal((await weightSensitivityRoute(sensitivityRequest("http://localhost:3000", null), context)).status, 401);
    assert.equal((await weightSensitivityRoute(sensitivityRequest("http://localhost:3000", sessionId,
      "expectedVersion=2&baseline_OIDC=100&alternative_OIDC=99"), context)).status, 400);
    assert.equal(calls.length, 3);
    const sensitivity = await weightSensitivityRoute(sensitivityRequest("http://localhost:3000", sessionId), context);
    assert.equal(sensitivity.status, 200);
    assert.equal(sensitivity.headers.get("cache-control"), "no-store");
    const paired = await sensitivity.json();
    assert.equal(paired.candidates[0].scoreDelta, null);
    assert.deepEqual(paired.candidates[0].capabilityDeltas, []);
    assert.equal("evidence" in paired.candidates[0], false);
    assert.deepEqual(calls.map(call => call.split(" ")[0]), ["GET", "POST", "GET", "GET", "POST"]);
    assert.equal((await weightSensitivityRoute(sensitivityRequest("http://localhost:3000", sessionId,
      "expectedVersion=1&baseline_OIDC=100&alternative_OIDC=100"), context)).status, 409);
    assert.equal(calls.length, 6);
  } finally {
    await revokeSession(sessionId);
    globalThis.fetch = previous.fetch;
    for (const [key, value] of [
      ["AUTHWEAVE_OIDC_ISSUER", previous.issuer],
      ["AUTHWEAVE_OIDC_CLIENT_ID", previous.clientId],
      ["AUTHWEAVE_PUBLIC_ORIGIN", previous.origin],
      ["AUTHWEAVE_CORE_SERVICE_TOKEN", previous.token],
    ] as const) {
      if (value === undefined) delete process.env[key]; else process.env[key] = value;
    }
  }
});

test("evaluation context route accepts only a scoped form from the personal session", async () => {
  const previous = {
    issuer: process.env.AUTHWEAVE_OIDC_ISSUER,
    clientId: process.env.AUTHWEAVE_OIDC_CLIENT_ID,
    origin: process.env.AUTHWEAVE_PUBLIC_ORIGIN,
    token: process.env.AUTHWEAVE_CORE_SERVICE_TOKEN,
    fetch: globalThis.fetch,
  };
  process.env.AUTHWEAVE_OIDC_ISSUER = "http://localhost:8081";
  process.env.AUTHWEAVE_OIDC_CLIENT_ID = "synthetic-client";
  process.env.AUTHWEAVE_PUBLIC_ORIGIN = "http://localhost:3000";
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-internal-token-000000000000000000000";
  const workspaceId = "70000000-0000-4000-8000-000000000001";
  const assessmentId = "80000000-0000-4000-8000-000000000001";
  const identity = {
    workspaceId, issuer: "http://localhost:8081", subject: "synthetic-context-user",
    email: null, displayName: null, authenticatedAt: new Date(),
  };
  const sessionId = await createSession(identity, undefined);
  const profile = {
    application: { type: "UNKNOWN", clients: [] },
    audience: { populations: [], tenancy: "UNKNOWN", membership: "UNKNOWN" },
    protocols: { federation: { OIDC: "PREFERRED" } },
    security: { dataResidency: "UNKNOWN", browserTokenExposureMinimization: "UNKNOWN", auditability: "REQUIRED",
      auditabilityRequirements: { selectedCriteria: ["AUDIT_LOG_RETENTION"], minimumRetentionDays: 90 },
      dataResidencyDetails: { allowedCountries: [], dataCategories: [] },
      complianceScopeStatus: "UNKNOWN",
      complianceTargets: [],
      authenticationControls: { phishingResistance: "UNKNOWN", nonExportableKeys: "UNKNOWN",
        stepUpAuthentication: "UNKNOWN" } },
  };
  const form = new URLSearchParams({
    expectedVersion: "2", applicationType: "B2B_SAAS", tenancy: "SINGLE_ORGANIZATION",
    membership: "SINGLE_ORGANIZATION_PER_USER", dataResidency: "REQUIRED",
    allowedCountries: "US, CA",
    browserTokenExposureMinimization: "REQUIRED",
    phishingResistance: "NOT_REQUIRED", nonExportableKeys: "NOT_REQUIRED",
    stepUpAuthentication: "NOT_REQUIRED", complianceScopeStatus: "NONE_IDENTIFIED",
  });
  form.append("clients", "BROWSER");
  form.append("selectedPopulations", "EMPLOYEES");
  form.append("selectedDataCategories", "USER_PROFILES");
  form.append("selectedDataCategories", "BACKUPS");
  const context = { params: Promise.resolve({ id: assessmentId }) };
  const request = (origin: string, cookie: string | null, body = form.toString()) => new NextRequest(
    `http://localhost:3000/api/assessments/${assessmentId}/evaluation-context`, {
      method: "POST", headers: { Origin: origin, "Content-Type": "application/x-www-form-urlencoded",
        ...(cookie ? { Cookie: `${sessionCookieName(false)}=${cookie}` } : {}) }, body,
    },
  );
  const calls: string[] = [];
  globalThis.fetch = async (input, init) => {
    calls.push(`${init?.method} ${input}`);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], identity.subject);
    if (init?.method === "GET") return Response.json({ id: assessmentId, workspaceId,
      status: "DRAFT", version: 2, profileSchemaVersion: 6, profile });
    const update = JSON.parse(String(init?.body));
    assert.equal(update.expectedVersion, 2);
    assert.equal(update.profile.application.type, "B2B_SAAS");
    assert.equal(update.profile.security.browserTokenExposureMinimization, "REQUIRED");
    if (update.profile.security.dataResidencyDetails.allowedCountries.includes("ZZ")) {
      return new Response(null, { status: 422 });
    }
    assert.deepEqual(update.profile.security.dataResidencyDetails,
      { allowedCountries: ["CA", "US"], dataCategories: ["USER_PROFILES", "BACKUPS"] });
    assert.deepEqual(update.profile.protocols, profile.protocols);
    return Response.json({ id: assessmentId, workspaceId, status: "DRAFT", version: 3,
      profileSchemaVersion: 6, profile: update.profile });
  };
  try {
    assert.equal((await evaluationContextRoute(request("https://other.example.test", sessionId), context)).status, 403);
    assert.equal((await evaluationContextRoute(request("http://localhost:3000", null), context)).status, 401);
    const forged = new URLSearchParams(form);
    forged.append("workspaceId", "90000000-0000-4000-8000-000000000001");
    assert.equal((await evaluationContextRoute(request("http://localhost:3000", sessionId,
      forged.toString()), context)).status, 400);
    assert.equal(calls.length, 0);
    const malformedResidency = new URLSearchParams(form);
    malformedResidency.set("allowedCountries", "us, CA");
    assert.equal((await evaluationContextRoute(request("http://localhost:3000", sessionId,
      malformedResidency.toString()), context)).status, 400);
    assert.equal(calls.length, 0);
    const response = await evaluationContextRoute(request("http://localhost:3000", sessionId), context);
    assert.equal(response.status, 303);
    assert.equal(response.headers.get("location"), `http://localhost:3000/assessments/${assessmentId}?step=context`);
    assert.deepEqual(calls.map(call => call.split(" ")[0]), ["GET", "PUT"]);
    const invalidCountry = new URLSearchParams(form);
    invalidCountry.set("allowedCountries", "ZZ");
    const rejected = await evaluationContextRoute(request("http://localhost:3000", sessionId,
      invalidCountry.toString()), context);
    assert.equal(rejected.headers.get("location"),
      `http://localhost:3000/assessments/${assessmentId}?step=context&contextError=invalid`);
    assert.deepEqual(calls.map(call => call.split(" ")[0]), ["GET", "PUT", "GET", "PUT"]);
    const stale = new URLSearchParams(form);
    stale.set("expectedVersion", "1");
    const conflict = await evaluationContextRoute(request("http://localhost:3000", sessionId,
      stale.toString()), context);
    assert.equal(conflict.headers.get("location"),
      `http://localhost:3000/assessments/${assessmentId}?step=context&contextError=stale`);
    assert.equal(calls.length, 5);
  } finally {
    await revokeSession(sessionId);
    globalThis.fetch = previous.fetch;
    for (const [key, value] of [
      ["AUTHWEAVE_OIDC_ISSUER", previous.issuer],
      ["AUTHWEAVE_OIDC_CLIENT_ID", previous.clientId],
      ["AUTHWEAVE_PUBLIC_ORIGIN", previous.origin],
      ["AUTHWEAVE_CORE_SERVICE_TOKEN", previous.token],
    ] as const) {
      if (value === undefined) delete process.env[key]; else process.env[key] = value;
    }
  }
});

test("usage planning route preserves the personal session and writes only scoped inputs", async () => {
  const previous = {
    issuer: process.env.AUTHWEAVE_OIDC_ISSUER,
    clientId: process.env.AUTHWEAVE_OIDC_CLIENT_ID,
    origin: process.env.AUTHWEAVE_PUBLIC_ORIGIN,
    token: process.env.AUTHWEAVE_CORE_SERVICE_TOKEN,
    fetch: globalThis.fetch,
  };
  process.env.AUTHWEAVE_OIDC_ISSUER = "http://localhost:8081";
  process.env.AUTHWEAVE_OIDC_CLIENT_ID = "synthetic-client";
  process.env.AUTHWEAVE_PUBLIC_ORIGIN = "http://localhost:3000";
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-internal-token-000000000000000000000";
  const workspaceId = "70000000-0000-4000-8000-000000000001";
  const assessmentId = "80000000-0000-4000-8000-000000000001";
  const identity = {
    workspaceId, issuer: "http://localhost:8081", subject: "synthetic-usage-user",
    email: null, displayName: null, authenticatedAt: new Date(),
  };
  const sessionId = await createSession(identity, undefined);
  const profile = {
    application: { type: "B2B_SAAS" },
    security: { assurance: "UNKNOWN", auditability: "REQUIRED",
      auditabilityRequirements: { selectedCriteria: ["AUDIT_LOG_RETENTION"], minimumRetentionDays: 90 } },
    operations: { hosting: "UNKNOWN",
      usagePlanning: { scopeDescription: "", assumptions: [], volumes: {} } },
  };
  const form = new URLSearchParams({ expectedVersion: "2", scopeDescription: "First production year" });
  for (let index = 0; index < 10; index++) form.append("assumption", index === 0 ? "Launch forecast" : "");
  for (const metric of ["MONTHLY_ACTIVE_USERS", "ENTERPRISE_SSO_CONNECTIONS",
    "MONTHLY_M2M_TOKEN_ISSUANCES", "PEAK_HUMAN_LOGINS_PER_SECOND"]) {
    form.set(`basis_${metric}`, "UNKNOWN");
    form.set(`value_${metric}`, "");
  }
  form.set("basis_MONTHLY_ACTIVE_USERS", "ASSUMED");
  form.set("value_MONTHLY_ACTIVE_USERS", "500");
  const context = { params: Promise.resolve({ id: assessmentId }) };
  const request = (origin: string, cookie: string | null, body = form.toString()) => new NextRequest(
    `http://localhost:3000/api/assessments/${assessmentId}/usage-planning`, {
      method: "POST", headers: { Origin: origin, "Content-Type": "application/x-www-form-urlencoded",
        ...(cookie ? { Cookie: `${sessionCookieName(false)}=${cookie}` } : {}) }, body,
    },
  );
  const calls: string[] = [];
  globalThis.fetch = async (input, init) => {
    calls.push(`${init?.method} ${input}`);
    assert.equal((init?.headers as Record<string, string>)["X-AuthWeave-Oidc-Subject"], identity.subject);
    if (init?.method === "GET") return Response.json({ id: assessmentId, workspaceId,
      status: "DRAFT", version: 2, profileSchemaVersion: 6, profile });
    const update = JSON.parse(String(init?.body));
    assert.equal(update.expectedVersion, 2);
    assert.deepEqual(update.profile.operations.usagePlanning, {
      scopeDescription: "First production year", assumptions: ["Launch forecast"],
      volumes: { MONTHLY_ACTIVE_USERS: { basis: "ASSUMED", value: 500 } },
    });
    assert.equal(update.profile.operations.hosting, "UNKNOWN");
    assert.deepEqual(update.profile.security, profile.security);
    return Response.json({ id: assessmentId, workspaceId, status: "DRAFT", version: 3,
      profileSchemaVersion: 6, profile: update.profile });
  };
  try {
    assert.equal((await usagePlanningRoute(request("https://other.example.test", sessionId), context)).status, 403);
    assert.equal((await usagePlanningRoute(request("http://localhost:3000", null), context)).status, 401);
    const forged = new URLSearchParams(form);
    forged.set("workspaceId", workspaceId);
    assert.equal((await usagePlanningRoute(request("http://localhost:3000", sessionId,
      forged.toString()), context)).status, 400);
    const invalidNumber = new URLSearchParams(form);
    invalidNumber.set("value_MONTHLY_ACTIVE_USERS", "9007199254740992");
    assert.equal((await usagePlanningRoute(request("http://localhost:3000", sessionId,
      invalidNumber.toString()), context)).status, 400);
    // Bypassing browser feedback still fails before any Core read or write.
    const unknownWithNumber = new URLSearchParams(form);
    unknownWithNumber.set("basis_MONTHLY_ACTIVE_USERS", "UNKNOWN");
    const assumedWithoutNumber = new URLSearchParams(form);
    assumedWithoutNumber.set("value_MONTHLY_ACTIVE_USERS", "");
    const duplicateAssumptions = new URLSearchParams(form);
    duplicateAssumptions.delete("assumption");
    for (let index = 0; index < 10; index++) duplicateAssumptions.append("assumption", index < 2 ? "Launch forecast" : "");
    const whitespaceAssumption = new URLSearchParams(form);
    whitespaceAssumption.delete("assumption");
    for (let index = 0; index < 10; index++) whitespaceAssumption.append("assumption", index === 0 ? " \n " : "");
    for (const invalid of [unknownWithNumber, assumedWithoutNumber, duplicateAssumptions, whitespaceAssumption]) {
      const rejected = await usagePlanningRoute(request("http://localhost:3000", sessionId, invalid.toString()), context);
      assert.equal(rejected.status, 400); assert.equal(rejected.headers.get("cache-control"), "no-store");
      assert.equal(calls.length, 0);
    }
    assert.equal(calls.length, 0);
    const saved = await usagePlanningRoute(request("http://localhost:3000", sessionId), context);
    assert.equal(saved.status, 303);
    assert.equal(saved.headers.get("location"), `http://localhost:3000/assessments/${assessmentId}?step=usage`);
    assert.deepEqual(calls.map(call => call.split(" ")[0]), ["GET", "PUT"]);
    const stale = new URLSearchParams(form);
    stale.set("expectedVersion", "1");
    const conflict = await usagePlanningRoute(request("http://localhost:3000", sessionId,
      stale.toString()), context);
    assert.equal(conflict.headers.get("location"),
      `http://localhost:3000/assessments/${assessmentId}?step=usage&usageError=stale`);
    assert.deepEqual(calls.map(call => call.split(" ")[0]), ["GET", "PUT", "GET"]);
  } finally {
    await revokeSession(sessionId);
    globalThis.fetch = previous.fetch;
    for (const [key, value] of [
      ["AUTHWEAVE_OIDC_ISSUER", previous.issuer],
      ["AUTHWEAVE_OIDC_CLIENT_ID", previous.clientId],
      ["AUTHWEAVE_PUBLIC_ORIGIN", previous.origin],
      ["AUTHWEAVE_CORE_SERVICE_TOKEN", previous.token],
    ] as const) {
      if (value === undefined) delete process.env[key]; else process.env[key] = value;
    }
  }
});

import assert from "node:assert/strict";
import { after, test } from "node:test";
import { NextRequest } from "next/server.js";

import { POST as createRoute } from "../src/app/api/assessments/route.ts";
import { POST as contextRoute } from "../src/app/api/assessments/[id]/evaluation-context/route.ts";
import { POST as capabilityRoute } from "../src/app/api/assessments/[id]/capabilities/route.ts";
import { POST as auditRoute } from "../src/app/api/assessments/[id]/auditability/route.ts";
import { POST as operationsRoute } from "../src/app/api/assessments/[id]/operational-preferences/route.ts";
import { POST as usageRoute } from "../src/app/api/assessments/[id]/usage-planning/route.ts";
import { POST as briefRoute } from "../src/app/api/assessments/[id]/requirements-brief/route.ts";
import { POST as prerequisiteRoute } from "../src/app/api/assessments/[id]/architecture-prerequisites/route.ts";
import { POST as configurationRoute } from "../src/app/api/assessments/[id]/architecture-configuration/route.ts";
import { POST as lifecycleRoute } from "../src/app/api/assessments/[id]/provisioning-lifecycle-v2/route.ts";
import { provisionPersonalWorkspace, readPersonalAssessment, listPersonalAssessments, readComparisonEvidence,
  readPersonalArchitecturePatterns, readPersonalAuditability, readPersonalAssurancePlanning,
  readPersonalOperationsPlanning, readPersonalUsagePlanning } from "../src/lib/auth/core-client.ts";
import { authDatabase, createSession, revokeSession, touchSession, type BrowserSession } from "../src/lib/auth/store.ts";
import { sessionCookieName } from "../src/lib/auth/session-policy.ts";
import { savedRequirementGroups } from "../src/lib/assessment/saved-requirements.ts";
import { savedProfileMatches } from "../src/lib/assessment/profile-save-acknowledgement.ts";
import { evaluationContextValues, evaluationContextLabels } from "../src/lib/assessment/evaluation-context.ts";
import { auditabilityValues } from "../src/lib/assessment/auditability.ts";
import { assurancePlanningValues } from "../src/lib/assessment/assurance-compliance-planning.ts";
import { operationsPlanningValues } from "../src/lib/assessment/operations-planning.ts";
import { usagePlanningValues } from "../src/lib/assessment/usage-planning.ts";
import { capabilityFields, capabilityValues } from "../src/lib/assessment/capabilities.ts";
import { prerequisiteIds } from "../src/lib/assessment/architecture-prerequisites.ts";
import { architectureConfigurationPatterns } from "../src/lib/assessment/architecture-configuration.ts";
import { lifecycleV2Conditions } from "../src/lib/assessment/provisioning-lifecycle-v2.ts";
import { requirementsBriefFilename } from "../src/lib/assessment/requirements-brief.ts";
import { guidedScenarios, guidedScenarioForms } from "./fixtures/guided-scenarios.mts";

// Only the Java IT supplies this ephemeral server/container. Never start against local project data.
assert.equal(process.env.AUTHWEAVE_TEST_GUIDED_BFF, "synthetic-guided-real-core-v1", "Run make check-guided-bff");
const origin = new URL(process.env.AUTHWEAVE_TEST_CORE_ORIGIN!);
assert.match(origin.href, /^http:\/\/127\.0\.0\.1:[1-9][0-9]*\/$/);
assert.equal(origin.search, ""); assert.equal(origin.hash, "");
const realFetch = globalThis.fetch;
let requests = 0;
// Transport remapping only: every response comes from the real Spring HTTP server, with filters enabled.
// No mocked Responses, auth bypass, app env override or persistent change to Core's fixed local origin.
globalThis.fetch = (target, init) => {
  const url = new URL(String(target));
  assert.equal(url.origin, "http://127.0.0.1:8080");
  assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error");
  assert.ok(init?.signal);
  requests++;
  return realFetch(new URL(url.pathname + url.search, origin), init);
};
after(async () => { globalThis.fetch = realFetch; await authDatabase().end(); });

const routes = { context: contextRoute, capabilities: capabilityRoute, auditability: auditRoute,
  operations: operationsRoute, usage: usageRoute };
const routePaths = { context: "evaluation-context", capabilities: "capabilities", auditability: "auditability",
  operations: "operational-preferences", usage: "usage-planning" };

for (const scenario of guidedScenarios) test(`real Core guided ${scenario.key}: saves, saved previews and isolated ownership`, async () => {
  const identity = { issuer: "http://localhost:8081", subject: `synthetic-guided-real-core-${scenario.key}`,
    email: null, displayName: null, authenticatedAt: new Date() };
  const workspaceId = await provisionPersonalWorkspace(identity);
  assert.equal(await provisionPersonalWorkspace(identity), workspaceId);
  const owner: BrowserSession = { ...identity, workspaceId };
  const outsiderIdentity = { ...identity, subject: `${identity.subject}-outsider` };
  const outsider: BrowserSession = { ...outsiderIdentity, workspaceId: await provisionPersonalWorkspace(outsiderIdentity) };
  assert.notEqual(outsider.workspaceId, workspaceId);
  const sessions: string[] = [];
  try {
    const sessionId = await createSession(owner, undefined); sessions.push(sessionId);
    const outsiderId = await createSession(outsider, undefined); sessions.push(outsiderId);
    const request = (path: string, form?: URLSearchParams, cookie = sessionId, accept = "application/json",
      requestOrigin = "http://localhost:3000") => new NextRequest(`http://localhost:3000/api/assessments${path}`, {
      method: "POST", headers: { Origin: requestOrigin, Accept: accept, "Content-Type": "application/x-www-form-urlencoded",
        Cookie: `${sessionCookieName(false)}=${cookie}`, "X-AuthWeave-Oidc-Subject": outsider.subject,
        "X-AuthWeave-Workspace-Id": outsider.workspaceId }, body: form?.toString(),
    });
    const created = await createRoute(request(""));
    assert.equal(created.status, 303);
    assert.equal(created.headers.get("cache-control"), "no-store");
    const id = new URL(created.headers.get("location")!).pathname.split("/").at(-1)!;
    assert.match(id, /^[0-9a-f-]{36}$/);
    const context = { params: Promise.resolve({ id }) };
    const initial = await readPersonalAssessment(owner, id); assert.ok(initial);
    assert.equal(initial.version, 0); assert.equal(initial.status, "DRAFT");
    assert.equal((initial.profile.application as Record<string, unknown>).type, "UNKNOWN");
    assert.deepEqual((initial.profile.application as Record<string, unknown>).clients, []);
    assert.equal((initial.profile.security as Record<string, unknown>).assurance, "UNKNOWN");
    const forms = guidedScenarioForms(scenario);
    let version = 0;
    for (const section of ["context", "capabilities", "auditability", "operations", "usage"] as const) {
      const form = forms[section], route = routes[section], path = `/${id}/${routePaths[section]}`;
      const previous = await readPersonalAssessment(owner, id); assert.ok(previous);
      const response = await route(request(path, form), context);
      assert.equal(response.status, 200, `${section}: ${await response.clone().text()}`);
      assert.equal(response.headers.get("cache-control"), "no-store");
      assert.deepEqual(await response.json(), { assessmentId: id, expectedVersion: version, outcome: "saved" });
      const saved = await readPersonalAssessment(owner, id); assert.ok(saved);
      version++; assert.equal(saved.version, version);
      // Partial writers must preserve all other major sections, including explicit context from earlier steps.
      const untouched = { context: ["protocols", "provisioning", "operations"], capabilities: ["application", "audience", "operations"],
        auditability: ["application", "audience", "protocols", "provisioning", "operations"],
        operations: ["application", "audience", "protocols", "provisioning", "security"],
        usage: ["application", "audience", "protocols", "provisioning", "security"] };
      for (const key of untouched[section]) assert.ok(savedProfileMatches(previous.profile[key], saved.profile[key]), `${section} changed ${key}`);
      // Submit the same values with the fresh version through the native path: a genuine Core no-op.
      const noOp = new URLSearchParams(form); noOp.set("expectedVersion", String(version));
      const native = await route(request(path, noOp, sessionId, "text/html"), context);
      assert.equal(native.status, 303);
      assert.equal(native.headers.get("location"), `http://localhost:3000/assessments/${id}?step=${section === "operations" ? "usage" : section}`);
      const replay = await readPersonalAssessment(owner, id); assert.ok(replay);
      assert.equal(replay.version, version); assert.ok(savedProfileMatches(saved.profile, replay.profile));
    }
    assert.equal(version, 5);
    const saved = await readPersonalAssessment(owner, id); assert.ok(saved);
    const app = saved.profile.application as Record<string, unknown>, audience = saved.profile.audience as Record<string, unknown>;
    assert.equal(app.type, scenario.applicationType); assert.deepEqual((app.clients as string[]).toSorted(), [...scenario.clients].sort());
    assert.deepEqual((audience.populations as string[]).toSorted(), [...scenario.populations].sort());
    assert.equal(audience.tenancy, scenario.tenancy); assert.equal(audience.membership, scenario.membership);
    const security = saved.profile.security as Record<string, unknown>;
    assert.equal(security.assurance, "ELEVATED"); assert.equal(security.browserTokenExposureMinimization, scenario.tokenExposure);
    assert.equal((security.authenticationControls as Record<string, unknown>).phishingResistance, scenario.phishingResistance);
    const capabilities = capabilityValues(saved.profile); assert.ok(capabilities);
    const explicit: Readonly<Record<string, string>> = scenario.capabilities;
    for (const field of capabilityFields) assert.equal(capabilities[field.capability], explicit[field.capability] ?? "UNKNOWN");
    const groups = savedRequirementGroups(saved.profile);
    assert.equal(groups.length, 6); assert.equal(groups.flatMap(group => group.rows ?? []).length, 37);
    const row = (group: string, label: string) => groups.find(item => item.id === group)?.rows?.find(item => item.label === label)?.value;
    assert.equal(row("application", "Application type"), scenario.expected.application);
    assert.equal(row("application", "User populations"), scenario.expected.users);
    assert.equal(row("application", "Client types"), scenario.expected.clients);
    assert.equal(row("security", "Assurance expectation (planning label)"), "Elevated");
    assert.equal(row("auditability", "Minimum retention"), `${scenario.retention} days`);

    const contextValues = evaluationContextValues(saved.profile), audit = auditabilityValues(saved.profile),
      usage = usagePlanningValues(saved.profile), operations = operationsPlanningValues(saved.profile), assurance = assurancePlanningValues(saved.profile);
    assert.ok(contextValues && audit && usage && operations && assurance);
    assert.deepEqual(audit.selectedCriteria.toSorted(), [...scenario.audit].sort()); assert.equal(audit.minimumRetentionDays, scenario.retention);
    assert.deepEqual(operations.inputs, scenario.operations); assert.deepEqual(usage.assumptions, [scenario.assumption]);
    const [comparison, architecture, auditPreview, usagePreview, operationsPreview, assurancePreview] = await Promise.all([
      readComparisonEvidence(owner, id, 5, audit), readPersonalArchitecturePatterns(owner, id, 5, contextValues),
      readPersonalAuditability(owner, id, 5, audit), readPersonalUsagePlanning(owner, id, 5, usage),
      readPersonalOperationsPlanning(owner, id, 5, operations), readPersonalAssurancePlanning(owner, id, 5, assurance),
    ]);
    for (const preview of [comparison.comparison, architecture, auditPreview, usagePreview, operationsPreview, assurancePreview]) assert.equal(preview.assessmentVersion, 5);
    const options = ["fictional-complete", "fictional-no-scim", "fictional-unreviewed"];
    assert.deepEqual(comparison.comparison.candidates.map(option => option.optionId), options);
    assert.deepEqual(comparison.evidence.map(option => option.optionId), options);
    for (const option of comparison.evidence) for (const group of option.groups) for (const item of group.rows) {
      if (item.sourceUrl) assert.ok(new URL(item.sourceUrl).hostname.endsWith(".invalid"));
    }
    assert.equal(architecture.patterns.length, 5);
    assert.deepEqual(architecture.selectedClients.toSorted(), [...scenario.clients].sort());
    assert.equal(architecture.patterns.find(pattern => pattern.patternId === "M2M_CLIENT_CREDENTIALS")?.status,
      scenario.key === "workforce" ? "MATCHES_CHECKED_REQUIREMENTS" : "NOT_APPLICABLE");
    assert.deepEqual(operationsPreview.inputs, scenario.operations); assert.equal(assurancePreview.inputs.assuranceExpectation, "ELEVATED");
    assert.equal(usagePreview.quantityChecks.find(item => item.metric === "ENTERPRISE_SSO_CONNECTIONS")?.value, scenario.ssoConnections);
    assert.equal(usagePreview.quantityChecks.find(item => item.metric === "MONTHLY_M2M_TOKEN_ISSUANCES")?.value, scenario.m2mTokens);
    // Exercise actual temporary architecture endpoints too; none may write a profile or promote verification.
    for (const patternId of Object.keys(prerequisiteIds) as (keyof typeof prerequisiteIds)[]) {
      const prerequisites = new URLSearchParams({ expectedVersion: "5", patternId });
      for (const condition of prerequisiteIds[patternId]) prerequisites.set(condition, "UNKNOWN");
      const response = await prerequisiteRoute(request(`/${id}/architecture-prerequisites`, prerequisites), context);
      assert.equal(response.status, 200); const result = await response.json();
      assert.equal(result.assessmentVersion, 5); assert.equal(result.analysis.configurationVerified, false);
      assert.equal(result.analysis.recommendationReady, false);
      const configuration = new URLSearchParams({ expectedVersion: "5", patternId });
      for (const setting of architectureConfigurationPatterns[patternId].ids) configuration.set(setting, "UNKNOWN");
      const configured = await configurationRoute(request(`/${id}/architecture-configuration`, configuration), context);
      assert.equal(configured.status, 200); assert.equal((await configured.json()).assessmentVersion, 5);
    }
    for (const patternId of ["SCIM_PUSH", "JIT_LOGIN", "SCIM_AND_JIT"] as const) {
      const form = new URLSearchParams({ expectedVersion: "5", patternId, groupStrategy: "UNKNOWN" });
      for (const condition of lifecycleV2Conditions(patternId, "UNKNOWN")) form.set(condition, "UNKNOWN");
      const response = await lifecycleRoute(request(`/${id}/provisioning-lifecycle-v2`, form), context);
      assert.equal(response.status, 200); const result = await response.json();
      assert.equal(result.assessmentVersion, 5); assert.equal(result.analysis.requirements.scim, explicit.SCIM ?? "UNKNOWN");
      if (scenario.key === "b2b" && patternId === "JIT_LOGIN") assert.equal(result.analysis.status, "CONDITIONALLY_DOES_NOT_MATCH");
    }
    const exportForm = new URLSearchParams({ expectedVersion: "5" });
    const exported = await briefRoute(request(`/${id}/requirements-brief`, exportForm), context);
    assert.equal(exported.status, 200); assert.equal(exported.headers.get("cache-control"), "no-store");
    assert.equal(exported.headers.get("content-disposition"), `attachment; filename="${requirementsBriefFilename(id, 5)}"`);
    const markdown = await exported.text(), readable = markdown.replace(/\\([!-~])/g, "$1");
    for (const group of groups) for (const item of group.rows ?? []) assert.ok(readable.includes(`**${item.label}:** ${item.value}`), item.label);
    assert.ok(markdown.includes("authweave-saved-requirements-brief-v2")); assert.ok(markdown.includes("- Saved version: `5`"));
    assert.equal(markdown.includes(identity.subject), false);
    assert.equal(await (await briefRoute(request(`/${id}/requirements-brief`, exportForm), context)).text(), markdown);
    const page = await listPersonalAssessments(owner);
    assert.equal(page.items.length, 1); assert.equal(page.items[0].id, id); assert.equal(page.items[0].version, 5);
    assert.equal(page.items[0].context?.applicationType, scenario.applicationType);
    assert.equal(page.items[0].context?.userPopulations.map(value => evaluationContextLabels[value]).join(", "), scenario.expected.users);
    assert.deepEqual((await listPersonalAssessments(outsider)).items, []);
    assert.equal(await readPersonalAssessment(outsider, id), null);
    const foreign = await capabilityRoute(request(`/${id}/capabilities`, forms.capabilities, outsiderId), context);
    assert.equal(foreign.status, 404); // A valid but different session cannot write this assessment.
    assert.equal((await briefRoute(request(`/${id}/requirements-brief`, exportForm, outsiderId), context)).status, 404);
    const forgedSession = await createSession({ ...outsider, workspaceId }, undefined); sessions.push(forgedSession);
    assert.equal((await capabilityRoute(request(`/${id}/capabilities`, forms.capabilities, forgedSession), context)).status, 503);
    // A forged session is still denied by real Core's identity/workspace authorization, not only BFF projection checks.
    const corePath = `http://127.0.0.1:8080/api/v6/workspaces/${workspaceId}/assessments/${id}`;
    const directHeaders = { Authorization: `Bearer ${process.env.AUTHWEAVE_CORE_SERVICE_TOKEN}`,
      "X-AuthWeave-Oidc-Issuer": identity.issuer, "X-AuthWeave-Oidc-Subject": outsider.subject };
    const direct = (headers: Record<string, string>) => fetch(corePath, { method: "GET", headers,
      cache: "no-store", redirect: "error", signal: AbortSignal.timeout(3_000) });
    assert.equal((await direct(directHeaders)).status, 403);
    assert.equal((await direct({ ...directHeaders, Authorization: "Bearer synthetic-invalid-service-token" })).status, 401);
    assert.equal((await direct({ Authorization: directHeaders.Authorization })).status, 401);
    for (const section of ["context", "capabilities", "auditability", "operations", "usage"] as const) {
      const response = await routes[section](request(`/${id}/${routePaths[section]}`, forms[section]), context);
      assert.equal(response.status, 409); // Every stale form retains the original submitted version.
    }
    assert.equal((await briefRoute(request(`/${id}/requirements-brief`, new URLSearchParams({ expectedVersion: "4" })), context)).status, 409);
    const beforeDenied = requests;
    assert.equal((await createRoute(request("", undefined, sessionId, "application/json", "https://other.invalid"))).status, 403);
    assert.equal(requests, beforeDenied);
    await revokeSession(sessionId); assert.equal(await touchSession(sessionId), null);
    assert.equal((await usageRoute(request(`/${id}/usage-planning`, forms.usage), context)).status, 401);
    assert.equal((await briefRoute(request(`/${id}/requirements-brief`, exportForm), context)).status, 401);
    assert.equal(requests, beforeDenied);
    const final = await readPersonalAssessment(owner, id); assert.ok(final);
    assert.equal(final.version, 5); assert.ok(savedProfileMatches(saved.profile, final.profile));
  } finally {
    for (const id of sessions) await revokeSession(id);
  }
});

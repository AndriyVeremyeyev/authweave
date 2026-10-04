import assert from "node:assert/strict";
import { test } from "node:test";
import { readFile } from "node:fs/promises";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import type { AuditabilityValues } from "../src/lib/assessment/auditability.ts";
import { auditabilityPreviewByteLimit, auditabilityPreviewFromCore,
  type AuditabilityReason } from "../src/lib/assessment/auditability-preview.ts";
import { readPersonalAuditability } from "../src/lib/auth/core-client.ts";
import { assessmentUiComponents, comparisonUiFixture, savedRequirementsFixture } from "./fixtures/assessment-ui.mts";
import { auditabilityAssessmentId as id, auditabilityWorkspaceId as workspaceId, auditabilityBinding as binding,
  auditabilityFixture, auditabilityInput, replaceAuditabilityCheck } from "./fixtures/auditability-preview.mts";

test("scoped preview preserves pass/fail/unknown separately, duration and original evidence dates", () => {
  const result = auditabilityPreviewFromCore(auditabilityFixture(), binding);
  assert.deepEqual(result.candidates.map(c => c.status), ["MATCHES_CHECKED_REQUIREMENTS", "DOES_NOT_MATCH", "NEEDS_INFORMATION"]);
  assert.equal(result.candidates[1].checks[3].reasonCode, "EVIDENCE_MISSING");
  assert.equal(result.candidates[1].checks[4].reasonCode, "CAPABILITY_UNAVAILABLE");
  assert.equal(result.candidates[1].checks[5].documentedMinimumRetentionDays, 7);
  assert.equal(result.candidates[0].checks[5].documentedMinimumRetentionDays, 90);
  assert.equal(result.candidates[2].checks[5].documentedMinimumRetentionDays, null);
  assert.ok(result.candidates.flatMap(c => c.evidence).every(f => f.observedAt === "2026-09-12T12:00:00Z"));
  assert.deepEqual(Object.keys(result).sort(), ["assessmentVersion", "baseCatalogVersion", "candidates", "evaluatedAt", "evidenceVersion", "values"]);
});

for (const criticality of ["REQUIRED", "PREFERRED", "NOT_REQUIRED", "UNKNOWN", "FORBIDDEN"] as const) {
  for (const empty of [false, true]) {
    test(`${criticality} with ${empty ? "unrecorded" : "selected"} scope remains explicit and unscored`, () => {
      const values: AuditabilityValues = { criticality, selectedCriteria: empty ? [] : [...auditabilityInput.selectedCriteria],
        minimumRetentionDays: empty ? null : 30 };
      const raw = auditabilityFixture(values);
      const result = auditabilityPreviewFromCore(raw, { ...binding, values });
      if (criticality === "REQUIRED" && !empty) assert.equal(result.candidates[0].status, "MATCHES_CHECKED_REQUIREMENTS");
      else {
        const reason = criticality === "REQUIRED" ? "AUDIT_SCOPE_UNKNOWN" : criticality === "PREFERRED" ? "PREFERENCE_NOT_SCORED" :
          criticality === "NOT_REQUIRED" ? "NO_REQUIREMENT" : criticality === "UNKNOWN" ? "REQUIREMENT_UNKNOWN" : "AUDIT_INTENT_UNCLEAR";
        assert.ok(result.candidates.flatMap(c => c.checks).every(c => c.reasonCode === reason));
        assert.equal(result.candidates[0].status, ["PREFERRED", "NOT_REQUIRED"].includes(criticality) ? "NOT_APPLIED" : "NEEDS_INFORMATION");
      }
      // Evidence validity and scope isolation apply even when the criterion is not evaluated.
      raw.candidates[0].evidence.push(structuredClone(raw.candidates[0].evidence[0]));
      assert.throws(() => auditabilityPreviewFromCore(raw, { ...binding, values }));
    });
  }
}

test("selection is not inferred; unselected facts do not constrain the preview", () => {
  const values: AuditabilityValues = { criticality: "REQUIRED", selectedCriteria: ["AUTHENTICATION_SUCCESS_EVENTS"], minimumRetentionDays: null };
  const result = auditabilityPreviewFromCore(auditabilityFixture(values), { ...binding, values });
  assert.equal(result.candidates[1].status, "MATCHES_CHECKED_REQUIREMENTS");
  assert.ok(result.candidates[1].checks.slice(1).every(c => c.reasonCode === "CRITERION_NOT_SELECTED"));
});

for (const [duration, status, reason] of [[1, "MATCHES_CHECKED_REQUIREMENTS", "RETENTION_MEETS_MINIMUM"],
  [90, "MATCHES_CHECKED_REQUIREMENTS", "RETENTION_MEETS_MINIMUM"], [91, "DOES_NOT_MATCH", "RETENTION_BELOW_MINIMUM"],
  [36500, "DOES_NOT_MATCH", "RETENTION_BELOW_MINIMUM"]] as const) {
  test(`requested minimum ${duration} days is compared to documented provider minimum without clamping`, () => {
    const values: AuditabilityValues = { criticality: "REQUIRED", selectedCriteria: ["AUDIT_LOG_RETENTION"], minimumRetentionDays: duration };
    const result = auditabilityPreviewFromCore(auditabilityFixture(values), { ...binding, values });
    assert.equal(result.candidates[0].status, status); assert.equal(result.candidates[0].checks[5].reasonCode, reason);
  });
}

const firstOnly: AuditabilityValues = { criticality: "REQUIRED", selectedCriteria: ["AUTHENTICATION_SUCCESS_EVENTS"], minimumRetentionDays: null };
for (const [observedAt, evaluatedAt, reason] of [
  ["2026-07-04T12:00:00Z", "2026-10-02T12:00:00Z", "DOCUMENTED_CAPABILITY_AVAILABLE"],
  ["2026-07-04T12:00:00Z", "2026-10-02T12:00:00.000000001Z", "EVIDENCE_STALE"],
  ["2026-10-02T12:00:00.000000001Z", "2026-10-02T12:00:00Z", "EVIDENCE_FROM_FUTURE"],
  ["2026-10-02T12:00:00.123456789Z", "2026-10-02T12:00:00.123456789Z", "DOCUMENTED_CAPABILITY_AVAILABLE"],
  ["2026-07-04T12:00:00.123456790Z", "2026-10-02T12:00:00.123456789Z", "DOCUMENTED_CAPABILITY_AVAILABLE"],
] as const) {
  test(`nanosecond evidence policy ${observedAt} at ${evaluatedAt}: ${reason}`, () => {
    const raw = auditabilityFixture(firstOnly);
    raw.evaluatedAt = evaluatedAt;
    for (const candidate of raw.candidates) candidate.analysis.evaluatedAt = evaluatedAt;
    raw.candidates[0].evidence[0].observedAt = observedAt;
    replaceAuditabilityCheck(raw, 0, 0, reason, reason === "DOCUMENTED_CAPABILITY_AVAILABLE" ? "PASS" : "UNKNOWN");
    const result = auditabilityPreviewFromCore(raw, { ...binding, values: firstOnly });
    assert.equal(result.candidates[0].checks[0].reasonCode, reason);
    raw.candidates[0].analysis.checks[0].reasonCode = reason === "EVIDENCE_STALE" ? "DOCUMENTED_CAPABILITY_AVAILABLE" : "EVIDENCE_STALE";
    assert.throws(() => auditabilityPreviewFromCore(raw, { ...binding, values: firstOnly }));
  });
}

test("evidence gating precedes capability interpretation and never substitutes unsupported for missing", () => {
  for (const [reason, mutate] of [
    ["EVIDENCE_MISSING", (raw: ReturnType<typeof auditabilityFixture>) => { raw.candidates[0].evidence.shift(); }],
    ["EVIDENCE_UNREVIEWED", (raw: ReturnType<typeof auditabilityFixture>) => { raw.candidates[0].evidence[0].evidenceStatus = "UNREVIEWED"; raw.candidates[0].evidence[0].support = "UNSUPPORTED"; }],
    ["CAPABILITY_UNKNOWN", (raw: ReturnType<typeof auditabilityFixture>) => { raw.candidates[0].evidence[0].support = "UNKNOWN"; }],
    ["CAPABILITY_UNAVAILABLE", (raw: ReturnType<typeof auditabilityFixture>) => { raw.candidates[0].evidence[0].support = "UNSUPPORTED"; }],
  ] as const) {
    const raw = auditabilityFixture(firstOnly); mutate(raw);
    replaceAuditabilityCheck(raw, 0, 0, reason, reason === "CAPABILITY_UNAVAILABLE" ? "FAIL" : "UNKNOWN");
    assert.equal(auditabilityPreviewFromCore(raw, { ...binding, values: firstOnly }).candidates[0].checks[0].reasonCode, reason);
  }
  const raw = auditabilityFixture(); raw.candidates[0].evidence[5].documentedMinimumRetentionDays = null;
  replaceAuditabilityCheck(raw, 0, 5, "RETENTION_DURATION_UNKNOWN", "UNKNOWN");
  assert.equal(auditabilityPreviewFromCore(raw, binding).candidates[0].status, "NEEDS_INFORMATION");
  raw.candidates[0].evidence[5].documentedMinimumRetentionDays = 0;
  replaceAuditabilityCheck(raw, 0, 5, "RETENTION_BELOW_MINIMUM", "FAIL", 0);
  assert.equal(auditabilityPreviewFromCore(raw, binding).candidates[0].checks[5].documentedMinimumRetentionDays, 0);
});

test("rejects foreign identity/version/inputs, claims, malformed tuples and invented metadata", () => {
  const valid = auditabilityFixture();
  const mutations: ((raw: typeof valid) => void)[] = [
    raw => { raw.workspaceId = "70000000-0000-4000-8000-000000000002"; },
    raw => { raw.assessmentId = "80000000-0000-4000-8000-000000000002"; },
    raw => { raw.assessmentVersion++; }, raw => { raw.criticality = "PREFERRED"; },
    raw => { raw.requirements.minimumRetentionDays = 31; }, raw => { raw.requirements.selectedCriteria.reverse(); },
    raw => { raw.recommendationReady = true; }, raw => { raw.sourceVerificationPerformed = true; },
    raw => { raw.catalogKind = "PUBLISHED"; }, raw => { raw.policyVersion = "auditability-capability-preflight-2"; },
    raw => { raw.baseCatalogVersion = ""; }, raw => { raw.evidenceVersion = "x".repeat(101); },
    raw => { raw.checkedPaths.push("security.assurance"); }, raw => { Object.assign(raw, { winner: "fictional-complete" }); },
    raw => { raw.candidates = []; }, raw => { raw.candidates = Array.from({ length: 101 }, () => structuredClone(raw.candidates[0])); },
    raw => { raw.candidates.push(structuredClone(raw.candidates[0])); },
    raw => { raw.candidates[0].analysis.configurationVerified = true; },
    raw => { raw.candidates[0].analysis.complianceVerified = true; },
    raw => { raw.candidates[0].analysis.recommendationReady = true; },
    raw => { raw.candidates[0].analysis.analysisBasis = "VERIFIED_CONFIGURATION"; },
    raw => { raw.candidates[0].analysis.deferredBoundaries.pop(); },
    raw => { raw.candidates[0].analysis.evaluatedAt = "2026-10-02T12:00:01Z"; },
    raw => { raw.candidates[0].analysis.requirements.selectedCriteria.pop(); },
    raw => { raw.candidates[0].analysis.criticality = "PREFERRED"; },
    raw => { raw.candidates[0].analysis.checks.reverse(); }, raw => { raw.candidates[0].analysis.checks.pop(); },
    raw => { raw.candidates[0].analysis.status = "NOT_APPLIED"; },
    raw => { raw.candidates[0].analysis.checks[0].outcome = "FAIL"; },
    raw => { raw.candidates[0].analysis.checks[0].reasonCode = "CAPABILITY_UNKNOWN"; },
    raw => { raw.candidates[0].analysis.checks[5].documentedMinimumRetentionDays = 91; },
    raw => { Object.assign(raw.candidates[0].analysis.checks[0], { explanation: "Recommended" }); },
    raw => { raw.candidates[0].displayName = "x".repeat(121); }, raw => { raw.candidates[0].displayName = "\nInvalid"; },
  ];
  for (const mutate of mutations) { const raw = structuredClone(valid); mutate(raw); assert.throws(() => auditabilityPreviewFromCore(raw, binding)); }
  for (const invalidDate of ["2026-02-30T12:00:00Z", "invalid", "2026-10-02T12:00:00+00:00", "2026-10-02T12:00:00.1234567890Z"]) {
    const raw = structuredClone(valid); raw.evaluatedAt = invalidDate;
    for (const candidate of raw.candidates) candidate.analysis.evaluatedAt = invalidDate;
    assert.throws(() => auditabilityPreviewFromCore(raw, binding));
  }
});

test("rejects pooled scopes, application emitters, duplicate facts and unbound durations/sources", () => {
  const valid = auditabilityFixture();
  const mutations: ((raw: typeof valid) => void)[] = [
    raw => { raw.candidates[0].evidence[0].scope.optionId = "fictional-no-scim"; },
    raw => { raw.candidates[0].evidence[0].scope.plan = "Another plan"; },
    raw => { raw.candidates[0].evidence[0].scope.region = "Another region"; },
    raw => { raw.candidates[0].evidence[0].scope.configuration = "Another configuration"; },
    raw => { Object.assign(raw.candidates[0].evidence[0], { emitter: "APPLICATION" }); },
    raw => { raw.candidates[0].evidence.push(structuredClone(raw.candidates[0].evidence[0])); },
    raw => { raw.candidates[0].evidence[1] = structuredClone(raw.candidates[0].evidence[0]); },
    raw => { raw.candidates[0].evidence[0].documentedMinimumRetentionDays = 30; },
    raw => { raw.candidates[0].evidence[5].support = "UNKNOWN"; },
    raw => { raw.candidates[0].evidence[5].documentedMinimumRetentionDays = -1; },
    raw => { raw.candidates[0].evidence[5].documentedMinimumRetentionDays = 36501; },
    raw => { raw.candidates[0].evidence[5].documentedMinimumRetentionDays = 1.5; },
    raw => { Object.assign(raw.candidates[0].evidence[0], { evidenceStatus: "VERIFIED" }); },
    raw => { Object.assign(raw.candidates[0].evidence[0], { fetched: true }); },
    raw => { Object.assign(raw.candidates[0].analysis.optionScope, { wildcard: true }); },
    raw => { raw.candidates[0].analysis.optionScope.region = "Unbound"; },
  ];
  for (const mutate of mutations) { const raw = structuredClone(valid); mutate(raw); assert.throws(() => auditabilityPreviewFromCore(raw, binding)); }
  for (const source of ["https://example.com/logs", "http://example.invalid/logs", "https://user:secret@example.invalid/logs",
    "https://example.invalid.evil/logs", "https://example.invalid\\@evil.invalid/logs", "https://example.invalid/\nlogs", "https://example.invalid/" + "x".repeat(2048)]) {
    const raw = structuredClone(valid); raw.candidates[0].evidence[0].sourceUrl = source;
    assert.throws(() => auditabilityPreviewFromCore(raw, binding));
  }
});

test("distinct configurations remain distinct, never pooled or ranked", () => {
  const raw = auditabilityFixture();
  const other = structuredClone(raw.candidates[0]);
  other.analysis.optionScope.configuration = "Other synthetic configuration";
  for (const fact of other.evidence) fact.scope.configuration = other.analysis.optionScope.configuration;
  raw.candidates.push(other);
  assert.equal(auditabilityPreviewFromCore(raw, binding).candidates.length, 4);
});

test("BFF read is fixed-origin/session-bound, no-store/body-free and rejects unsafe input before fetch", async () => {
  const previousFetch = globalThis.fetch, previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-auditability-preview-token-000000000000000000";
  const session = { workspaceId, issuer: "http://localhost:8081", subject: "synthetic-auditability-user",
    email: null, displayName: null, authenticatedAt: new Date() };
  let calls = 0, status = 200, value = auditabilityFixture();
  globalThis.fetch = async (url, init) => {
    calls++;
    assert.equal(String(url), `http://127.0.0.1:8080/api/v6/workspaces/${workspaceId}/assessments/${id}/auditability-capability-preflight`);
    assert.equal(init?.method, "GET"); assert.equal(init?.body, undefined);
    assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error"); assert.ok(init?.signal);
    const headers = init?.headers as Record<string, string>;
    assert.equal(headers["Authorization"], `Bearer ${process.env.AUTHWEAVE_CORE_SERVICE_TOKEN}`);
    assert.equal(headers["X-AuthWeave-Oidc-Issuer"], session.issuer);
    assert.equal(headers["X-AuthWeave-Oidc-Subject"], session.subject);
    return status === 200 ? Response.json(value) : new Response(null, { status });
  };
  try {
    const result = await readPersonalAuditability(session, id, 2, auditabilityInput);
    assert.equal(result.assessmentVersion, 2);
    for (const code of [400, 401, 403, 404, 409, 500]) { status = code; await assert.rejects(readPersonalAuditability(session, id, 2, auditabilityInput)); }
    status = 200; value.assessmentVersion++;
    await assert.rejects(readPersonalAuditability(session, id, 2, auditabilityInput)); value = auditabilityFixture();
    const count = calls;
    for (const version of [-1, NaN, 2.5, Number.MAX_SAFE_INTEGER + 1]) await assert.rejects(readPersonalAuditability(session, id, version, auditabilityInput));
    await assert.rejects(readPersonalAuditability(session, "../other", 2, auditabilityInput));
    await assert.rejects(readPersonalAuditability({ ...session, workspaceId: "../other" }, id, 2, auditabilityInput));
    for (const input of [{ ...auditabilityInput, minimumRetentionDays: null }, { ...auditabilityInput, selectedCriteria: [], minimumRetentionDays: 30 },
      { ...auditabilityInput, selectedCriteria: ["AUDIT_LOG_RETENTION", "AUDIT_LOG_RETENTION"] }, { ...auditabilityInput, approvalGranted: true }]) {
      await assert.rejects(readPersonalAuditability(session, id, 2, input as AuditabilityValues));
    }
    assert.equal(calls, count);
    for (const response of [new Response("{invalid"), new Response(new Uint8Array([0xff])), new Response("small", { headers: { "Content-Length": String(auditabilityPreviewByteLimit + 1) } }),
      new Response("x".repeat(auditabilityPreviewByteLimit + 1), { headers: { "Content-Length": "1" } })]) {
      globalThis.fetch = async () => response;
      await assert.rejects(readPersonalAuditability(session, id, 2, auditabilityInput));
    }
    let cancelled = false;
    globalThis.fetch = async () => new Response(new ReadableStream({ start(controller) { controller.enqueue(new Uint8Array(auditabilityPreviewByteLimit + 1)); }, cancel() { cancelled = true; } }));
    await assert.rejects(readPersonalAuditability(session, id, 2, auditabilityInput)); assert.equal(cancelled, true);
  } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});

async function previewComponent() {
  const source = await readFile(new URL("../src/app/assessments/[id]/auditability-preflight.tsx", import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext,
    jsx: ts.JsxEmit.ReactJSX, target: ts.ScriptTarget.ES2022 } }).outputText
    .replaceAll('"react/jsx-runtime"', JSON.stringify(import.meta.resolve("react/jsx-runtime")))
    .replaceAll('"@/lib/assessment/auditability"', JSON.stringify(new URL("../src/lib/assessment/auditability.ts", import.meta.url).href))
    .replaceAll('"@/lib/assessment/auditability-preview"', JSON.stringify(new URL("../src/lib/assessment/auditability-preview.ts", import.meta.url).href));
  return import(`data:text/javascript;base64,${Buffer.from(compiled).toString("base64")}`);
}
test("SSR explains exact scopes, every criterion, dates, gaps, retention and deferred boundaries without authority", async () => {
  const component = await previewComponent();
  const raw = auditabilityFixture(); raw.candidates[0].displayName = "<script>alert('fixture')</script>";
  const html = renderToStaticMarkup(createElement(component.AuditabilityPreflight, { preview: auditabilityPreviewFromCore(raw, binding) }));
  assert.equal((html.match(/<article /g) ?? []).length, 3);
  assert.equal((html.match(/<h4 /g) ?? []).length, 18);
  assert.ok(!html.includes("<script>")); assert.ok(html.includes("&lt;script&gt;"));
  assert.ok(!html.includes("<a ")); assert.ok(!html.includes("<form"));
  for (const phrase of ["not facts about ZITADEL", "Matches selected capability checks only", "Does not match selected capability checks",
    "Needs more information", "Missing evidence does not mean unsupported", "Documented provider minimum:", "external-sink retention",
    "Configuration scope (not verified)", "2026-09-12T12:00:00Z", "not fetched", "does not imply SCIM support",
    "does not make a recommendation ready", "No source verification is performed", "Audit record integrity", "Logging failure handling",
    "Export delivery", "Compliance evidence", "plan, region and configuration", "A mismatch does not hide other information gaps"]) assert.ok(html.includes(phrase), phrase);
});
test("SSR missing preview never invents a result or exposes upstream errors", async () => {
  const component = await previewComponent();
  const html = renderToStaticMarkup(createElement(component.AuditabilityPreflightUnavailable));
  assert.ok(html.includes("preview unavailable")); assert.ok(html.includes("No result is inferred"));
  assert.ok(!html.includes("Capability match")); assert.ok(!html.includes("Bearer"));
  for (const [criticality, reason] of [["PREFERRED", "PREFERENCE_NOT_SCORED"], ["FORBIDDEN", "AUDIT_INTENT_UNCLEAR"], ["REQUIRED", "AUDIT_SCOPE_UNKNOWN"]] as const) {
    const values: AuditabilityValues = { criticality, selectedCriteria: [], minimumRetentionDays: null };
    const preview = auditabilityPreviewFromCore(auditabilityFixture(values), { ...binding, values });
    const markup = renderToStaticMarkup(createElement(component.AuditabilityPreflight, { preview }));
    const phrases: Record<string, string> = { PREFERENCE_NOT_SCORED: "does not score preferences", AUDIT_INTENT_UNCLEAR: "does not recommend disabling logs", AUDIT_SCOPE_UNKNOWN: "Empty scope is not an exemption" };
    assert.ok(markup.includes(phrases[reason as AuditabilityReason]));
  }
});

test("personal pages accept canonical UUIDs, bind current inputs, isolate preview failure and resolve session first", async () => {
  const component = await previewComponent();
  const overview = await assessmentUiComponents();
  const state = { live: true, reads: 0, listReads: 0, fail: false, comparison: null as ReturnType<typeof comparisonUiFixture> | null };
  const identity = { workspaceId, issuer: "http://localhost:8081", subject: "synthetic-page-owner",
    email: null, displayName: null, authenticatedAt: new Date() };
  const slot = "__authweaveAuditabilityPageTest";
  const globals = globalThis as unknown as Record<string, unknown>;
  const previous = globals[slot];
  const assessment = { id, status: "DRAFT", version: 2, profile: { security: {
    auditability: auditabilityInput.criticality, auditabilityRequirements: {
      selectedCriteria: auditabilityInput.selectedCriteria, minimumRetentionDays: auditabilityInput.minimumRetentionDays } } } };
  globals[slot] = {
    session: () => state.live ? identity : null, assessment,
    list: async (session: typeof identity, before: string | undefined) => {
      state.listReads++; assert.deepEqual(session, identity); assert.equal(before, id);
      if (state.fail) throw new Error("synthetic upstream credential must not appear");
      return { items: [], nextBeforeId: id };
    },
    read: async (session: typeof identity, assessmentId: string, version: number, values: AuditabilityValues) => {
      state.reads++; assert.deepEqual(session, identity); assert.equal(assessmentId, id);
      assert.equal(version, 2); assert.deepEqual(values, auditabilityInput);
      if (state.fail) throw new Error("synthetic upstream credential must not appear");
      return auditabilityPreviewFromCore(auditabilityFixture(), binding);
    }, comparison: () => state.comparison,
    ...component, SavedRequirementsOverview: overview.SavedRequirementsOverview, ComparisonSection: overview.ComparisonSection,
    SavedContextSummary: overview.SavedContextSummary,
    AssessmentList: overview.AssessmentList,
    SavedRequirementsExport: overview.SavedRequirementsExport,
    CapabilityEditor: overview.CapabilityEditor,
    EvaluationContextEditor: overview.EvaluationContextEditor,
  };
  const shimSource = `import { createElement } from ${JSON.stringify(import.meta.resolve("react"))};
    const state = globalThis[${JSON.stringify(slot)}];
    export default function Link({ children, ...props }) { return createElement('a', props, children); }
    export const cookies = async () => ({ get: () => ({ value: 'synthetic-session' }) });
    export const notFound = () => { throw new Error('not-found'); };
    export const redirect = () => { throw new Error('redirect-account'); };
    export const authConfiguration = () => ({ secureCookies: false });
    export const sessionCookieName = () => 'authweave_session';
    export const touchSession = async () => state.session();
    export const readPersonalAssessment = async () => state.assessment;
    export const listPersonalAssessments = (...args) => state.list(...args);
    export const readSyntheticComparison = async () => state.comparison();
    export const readPersonalArchitecturePatterns = async () => null;
    export const readPersonalUsagePlanning = async () => null;
    export const readPersonalAuditability = (...args) => state.read(...args);
    export const WeightedPreviewForm = () => null,
      ArchitecturePatterns = () => null, UsagePlanningEditor = () => null, UsagePlanningPreflight = () => null,
      AuditabilityEditor = () => null;
    export const AssessmentWorkflow = ({ initialStep, panels }) => createElement('section', { 'data-step': initialStep }, panels[initialStep]);
    export const SavedRequirementsOverview = state.SavedRequirementsOverview;
    export const ComparisonSection = state.ComparisonSection;
    export const SavedContextSummary = state.SavedContextSummary;
    export const AssessmentList = state.AssessmentList;
    export const SavedRequirementsExport = state.SavedRequirementsExport;
    export const CapabilityEditor = state.CapabilityEditor;
    export const EvaluationContextEditor = state.EvaluationContextEditor;
    export const AuditabilityPreflight = state.AuditabilityPreflight, AuditabilityPreflightUnavailable = state.AuditabilityPreflightUnavailable;`;
  const shim = `data:text/javascript;base64,${Buffer.from(shimSource).toString("base64")}`;
  const source = await readFile(new URL("../src/app/assessments/[id]/page.tsx", import.meta.url), "utf8");
  let compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext,
    jsx: ts.JsxEmit.ReactJSX, target: ts.ScriptTarget.ES2022 } }).outputText
    .replaceAll('"react/jsx-runtime"', JSON.stringify(import.meta.resolve("react/jsx-runtime")));
  for (const name of ["next/link", "next/headers", "next/navigation", "@/lib/auth/config", "@/lib/auth/core-client",
    "@/lib/auth/session-policy", "@/lib/auth/store", "./weighted-preview", "./evaluation-context-editor", "./architecture-patterns",
    "./usage-planning-editor", "./usage-planning-preflight", "./auditability-editor", "./auditability-preflight", "./assessment-workflow", "./saved-requirements-overview", "./comparison-section", "./saved-context-summary", "./saved-requirements-export", "./capability-editor"]) {
    compiled = compiled.replaceAll(JSON.stringify(name), JSON.stringify(shim));
  }
  for (const name of ["capabilities", "comparison-evidence", "evaluation-context", "usage-planning", "auditability", "workflow"]) {
    compiled = compiled.replaceAll(JSON.stringify(`@/lib/assessment/${name}`),
      JSON.stringify(new URL(`../src/lib/assessment/${name}.ts`, import.meta.url).href));
  }
  try {
    const page = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString("base64")}`);
    const props = { params: Promise.resolve({ id }), searchParams: Promise.resolve({ step: "auditability" }) };
    const html = renderToStaticMarkup(await page.default(props));
    assert.ok(html.includes("Identity auditability capability preview")); assert.equal(state.reads, 1);
    assert.ok(html.includes('aria-label="Saved application context"')); assert.ok(html.includes("Saved context cannot be read safely"));
    const invalidIds = ["80000000-0000-4000-000000000001", "../other", "not-a-uuid", "80000000-0000-4000-8000-00000000000G"];
    for (const invalid of invalidIds) {
      await assert.rejects(page.default({ ...props, params: Promise.resolve({ id: invalid }) }), /not-found/);
    }
    assert.equal(state.reads, 1);
    const listSource = await readFile(new URL("../src/app/assessments/page.tsx", import.meta.url), "utf8");
    let listCompiled = ts.transpileModule(listSource, { compilerOptions: { module: ts.ModuleKind.ESNext,
      jsx: ts.JsxEmit.ReactJSX, target: ts.ScriptTarget.ES2022 } }).outputText
      .replaceAll('"react/jsx-runtime"', JSON.stringify(import.meta.resolve("react/jsx-runtime")));
    for (const name of ["next/link", "next/headers", "next/navigation", "@/lib/auth/config", "@/lib/auth/core-client",
      "@/lib/auth/session-policy", "@/lib/auth/store", "./assessment-list"]) listCompiled = listCompiled.replaceAll(JSON.stringify(name), JSON.stringify(shim));
    const listPage = await import(`data:text/javascript;base64,${Buffer.from(listCompiled).toString("base64")}`);
    const listHtml = renderToStaticMarkup(await listPage.default({ searchParams: Promise.resolve({ before: id }) }));
    assert.ok(listHtml.includes(`/assessments?before=${id}`)); assert.equal(state.listReads, 1);
    for (const invalid of [...invalidIds, [id, id]]) {
      await assert.rejects(listPage.default({ searchParams: Promise.resolve({ before: invalid }) }), /not-found/);
    }
    assert.equal(state.listReads, 1);
    state.fail = true;
    const unavailableList = renderToStaticMarkup(await listPage.default({ searchParams: Promise.resolve({ before: id }) }));
    assert.ok(unavailableList.includes("Assessments unavailable"));
    assert.equal(unavailableList.includes("synthetic upstream credential"), false);
    const unavailable = renderToStaticMarkup(await page.default(props));
    assert.ok(unavailable.includes("Identity auditability preview unavailable"));
    assert.ok(unavailable.includes("Saved profile and technical details"));
    const comparison = renderToStaticMarkup(await page.default({ ...props, searchParams: Promise.resolve({ step: "comparison" }) }));
    assert.ok(comparison.includes("Synthetic comparison unavailable"));
    assert.ok(!comparison.includes("Identity auditability capability preview"));
    state.comparison = { ...comparisonUiFixture(), assessmentVersion: 2 };
    const availableComparison = renderToStaticMarkup(await page.default({ ...props, searchParams: Promise.resolve({ step: "comparison" }) }));
    assert.ok(availableComparison.includes("Saved assessment version 2"));
    assert.ok(availableComparison.includes("Fictional Limited Plan"));
    assert.ok(availableComparison.includes("Related saved inputs cannot be read safely"));
    assert.ok(!availableComparison.includes("Identity auditability capability preview"));
    assert.ok(!availableComparison.includes("synthetic upstream credential"));
    assert.ok(!unavailable.includes("synthetic upstream credential")); assert.ok(!unavailable.includes("Matches selected capability"));
    const reviewProps = { ...props, searchParams: Promise.resolve({ step: "review" }) };
    const review = renderToStaticMarkup(await page.default(reviewProps));
    assert.ok(review.includes("Saved version 2 · Read-only overview"));
    assert.ok(review.includes("Download saved brief (.md)"));
    assert.ok(review.includes("It uses saved version 2, not unsaved edits"));
    assert.ok(review.includes("30 days")); assert.ok(review.includes("cannot be read safely"));
    assert.ok(!review.includes("synthetic upstream credential"));
    const requirementsProps = { ...props, searchParams: Promise.resolve({ step: "capabilities" }) };
    const unreadableRequirements = renderToStaticMarkup(await page.default(requirementsProps));
    assert.ok(unreadableRequirements.includes("Requirement editing is unavailable"));
    assert.ok(!unreadableRequirements.includes("Save capability requirements"));
    const contextProps = { ...props, searchParams: Promise.resolve({ step: "context" }) };
    const unreadableContext = renderToStaticMarkup(await page.default(contextProps));
    assert.ok(unreadableContext.includes("Context editing is unavailable"));
    assert.ok(!unreadableContext.includes("Save application context"));
    Object.assign(assessment, { profile: savedRequirementsFixture() });
    const requirements = renderToStaticMarkup(await page.default(requirementsProps));
    assert.ok(requirements.includes("How to choose a requirement level"));
    assert.ok(requirements.includes(`action="/api/assessments/${id}/capabilities"`));
    assert.ok(requirements.includes('name="expectedVersion" value="2"'));
    const context = renderToStaticMarkup(await page.default(contextProps));
    assert.ok(context.includes("Protect sign-in and sensitive actions"));
    assert.ok(context.includes("Compliance scope to investigate"));
    assert.ok(context.includes(`action="/api/assessments/${id}/evaluation-context"`));
    assert.ok(context.includes('name="expectedVersion" value="2"'));
    Object.assign(assessment, { status: "ARCHIVED" });
    const readOnlyRequirements = renderToStaticMarkup(await page.default(requirementsProps));
    assert.ok(readOnlyRequirements.includes("This assessment is read-only"));
    assert.ok(!readOnlyRequirements.includes("Save capability requirements"));
    assert.ok(!readOnlyRequirements.includes("<select"));
    const readOnlyContext = renderToStaticMarkup(await page.default(contextProps));
    assert.ok(readOnlyContext.includes("This assessment is read-only"));
    assert.ok(!readOnlyContext.includes("Save application context"));
    assert.ok(!readOnlyContext.includes("<select"));
    state.live = false; const count = state.reads;
    await assert.rejects(page.default(props), /redirect-account/); assert.equal(state.reads, count);
    const listReads = state.listReads;
    await assert.rejects(listPage.default({ searchParams: Promise.resolve({ before: id }) }), /redirect-account/);
    assert.equal(state.listReads, listReads);
    await assert.rejects(page.default(reviewProps), /redirect-account/); assert.equal(state.reads, count);
    await assert.rejects(page.default(contextProps), /redirect-account/); assert.equal(state.reads, count);
    await assert.rejects(page.default({ ...props, searchParams: Promise.resolve({ step: "comparison" }) }), /redirect-account/);
    assert.equal(state.reads, count);
  } finally {
    if (previous === undefined) delete globals[slot]; else globals[slot] = previous;
  }
});

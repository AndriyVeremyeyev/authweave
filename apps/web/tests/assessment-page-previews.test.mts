import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import { capabilityFields, capabilityValues } from "../src/lib/assessment/capabilities.ts";
import { evaluationContextValues } from "../src/lib/assessment/evaluation-context.ts";
import { usagePlanningValues } from "../src/lib/assessment/usage-planning.ts";
import { operationsPlanningValues } from "../src/lib/assessment/operations-planning.ts";
import { assurancePlanningValues } from "../src/lib/assessment/assurance-compliance-planning.ts";
import { auditabilityValues } from "../src/lib/assessment/auditability.ts";
import { assessmentStepFromQuery } from "../src/lib/assessment/workflow.ts";
import { savedRequirementsFixture } from "./fixtures/assessment-ui.mts";
import { operationsProfile } from "./fixtures/operations-planning.mts";

const readers = ["readComparisonEvidence", "readPersonalArchitecturePatterns", "readPersonalUsagePlanning",
  "readPersonalOperationsPlanning", "readPersonalAuditability", "readPersonalAssurancePlanning"] as const;
type Reader = typeof readers[number];
type View = { type: unknown; props: Record<string, unknown> };
function nodes(value: unknown): View[] {
  if (Array.isArray(value)) return value.flatMap(nodes);
  if (!value || typeof value !== "object") return [];
  if (Object.hasOwn(value, "props")) { const view = value as View; return [view, ...Object.values(view.props).flatMap(nodes)]; }
  return Object.values(value).flatMap(nodes);
}
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
const moduleUrl = (source: string) => `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`;

async function harness() {
  const slot = `__authweave_parallel_page_${crypto.randomUUID()}`, globals = globalThis as unknown as Record<string, unknown>;
  const session = { workspaceId: "70000000-0000-4000-8000-000000000001", issuer: "http://localhost:8081", subject: "synthetic-preview-owner" };
  const assessment = { id: "80000000-0000-4000-8000-000000000001", version: 2, status: "DRAFT",
    profile: { ...savedRequirementsFixture(), ...operationsProfile() } as Record<string, unknown> };
  Object.assign(assessment.profile.security as Record<string, unknown>, { assurance: "HIGH" });
  const events: string[] = [], calls: { name: Reader; args: unknown[] }[] = [];
  const gates = Object.fromEntries(readers.map(name => [name, deferred<unknown>()])) as Record<Reader, ReturnType<typeof deferred<unknown>>>;
  const results: Record<Reader, Record<string, unknown>> = {
    readComparisonEvidence: { comparison: { assessmentVersion: 2, marker: "comparison" }, evidence: [{ marker: "evidence" }] },
    readPersonalArchitecturePatterns: { assessmentVersion: 2, marker: "architecture" },
    readPersonalUsagePlanning: { assessmentVersion: 2, marker: "usage" },
    readPersonalOperationsPlanning: { assessmentVersion: 2, marker: "operations" },
    readPersonalAuditability: { assessmentVersion: 2, marker: "auditability" },
    readPersonalAssurancePlanning: { assessmentVersion: 2, marker: "assurance" },
  };
  const sessionGate = deferred<typeof session | null>(), profileGate = deferred<typeof assessment | null>(), queryGate = deferred<{ step: string }>();
  const deps: Record<string, unknown> = { capabilityFields, capabilityValues, evaluationContextValues, usagePlanningValues,
    operationsPlanningValues, assurancePlanningValues, auditabilityValues, assessmentStepFromQuery,
    authConfiguration: () => ({ secureCookies: false }), cookies: async () => ({ get: () => ({ value: "synthetic-cookie" }) }),
    sessionCookieName: () => "session", touchSession: async () => { events.push("session"); return sessionGate.promise; },
    readPersonalAssessment: async (owner: unknown, id: unknown) => {
      assert.equal(owner, session); assert.equal(id, assessment.id); events.push("assessment"); return profileGate.promise;
    }, redirect: (path: string) => { throw new Error(`redirect:${path}`); }, notFound: () => { throw new Error("not-found"); },
  };
  for (const name of readers) deps[name] = async (...args: unknown[]) => {
    events.push(name); calls.push({ name, args }); return gates[name].promise;
  };
  globals[slot] = deps;
  const source = await readFile(new URL("../src/app/assessments/[id]/page.tsx", import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText
    .replace(/import\s+([\s\S]*?)\s+from\s+"([^"]+)";/g, (whole: string, bindings: string, specifier: string) => {
      if (specifier === "react/jsx-runtime") return whole.replace('"react/jsx-runtime"', JSON.stringify(import.meta.resolve("react/jsx-runtime")));
      const names = bindings.startsWith("{") ? bindings.slice(1, -1).split(",").map(name => name.trim().split(/\s+as\s+/)[0]) : [];
      for (const name of names.length ? names : [bindings]) if (!Object.hasOwn(deps, name)) deps[name] = () => null;
      const body = names.length ? names.map(name => `export const ${name} = globalThis[${JSON.stringify(slot)}][${JSON.stringify(name)}];`).join("\n")
        : `export default globalThis[${JSON.stringify(slot)}][${JSON.stringify(bindings)}];`;
      return whole.replace(JSON.stringify(specifier), JSON.stringify(moduleUrl(body)));
    });
  const { default: Page } = await import(moduleUrl(compiled));
  const props = { params: Promise.resolve({ id: assessment.id }), searchParams: queryGate.promise };
  let work: Promise<unknown> | undefined;
  const start = (id = assessment.id): Promise<unknown> => work = Page({ ...props, params: Promise.resolve({ id }) });
  // Drain promise continuations, not a wall-clock race or an arbitrary performance threshold.
  const drain = async () => { for (let i = 0; i < 16; i++) await Promise.resolve(); };
  const unlock = () => { sessionGate.resolve(session); profileGate.resolve(assessment); queryGate.resolve({ step: "review" }); };
  const settle = () => { for (const name of readers) gates[name].resolve(results[name]); };
  const panels = (tree: unknown) => {
    const workflow = nodes(tree).find(node => node.type === deps.AssessmentWorkflow); assert.ok(workflow);
    return workflow.props.panels as Record<string, unknown>;
  };
  const component = (panel: unknown, name: string) => nodes(panel).find(node => node.type === deps[name]);
  return { session, assessment, events, calls, gates, results, deps, sessionGate, profileGate, queryGate, start, drain, unlock, settle, panels, component,
    async cleanup() { unlock(); settle(); await work?.catch(() => {}); delete globals[slot]; } };
}

test("all six page previews start concurrently only after live session, canonical profile and saved query resolution", async () => {
  const h = await harness();
  try {
    const before = structuredClone(h.assessment), work = h.start(); await h.drain();
    assert.deepEqual(h.events, ["session"]); assert.equal(h.calls.length, 0);
    h.sessionGate.resolve(h.session); await h.drain(); assert.deepEqual(h.events, ["session", "assessment"]);
    h.profileGate.resolve(h.assessment); await h.drain(); assert.equal(h.calls.length, 0);
    h.queryGate.resolve({ step: "review" }); await h.drain(); assert.deepEqual(h.calls.map(call => call.name), [...readers]);
    const expected = [auditabilityValues(h.assessment.profile), evaluationContextValues(h.assessment.profile), usagePlanningValues(h.assessment.profile),
      operationsPlanningValues(h.assessment.profile), auditabilityValues(h.assessment.profile), assurancePlanningValues(h.assessment.profile)];
    assert.ok(expected.every(Boolean));
    for (const [index, call] of h.calls.entries()) {
      assert.equal(call.args[0], h.session); assert.equal(call.args[1], h.assessment.id); assert.equal(call.args[2], 2);
      assert.deepEqual(call.args[3], expected[index]);
    }
    let complete = false; void work.then(() => { complete = true; });
    // Complete out of order: no result/metadata may be associated with the wrong preview.
    for (const name of [...readers].reverse().slice(0, -1)) h.gates[name].resolve(h.results[name]);
    await h.drain(); assert.equal(complete, false); h.gates.readComparisonEvidence.resolve(h.results.readComparisonEvidence);
    const panels = h.panels(await work);
    assert.equal(h.component(panels.comparison, "ComparisonSection")!.props.comparison, h.results.readComparisonEvidence.comparison);
    assert.equal(h.component(panels.comparison, "ComparisonSection")!.props.evidence, h.results.readComparisonEvidence.evidence);
    for (const [step, name, reader] of [["architecture", "ArchitecturePatterns", "readPersonalArchitecturePatterns"],
      ["usage", "UsagePlanningPreflight", "readPersonalUsagePlanning"], ["usage", "OperationsPlanning", "readPersonalOperationsPlanning"],
      ["auditability", "AuditabilityPreflight", "readPersonalAuditability"], ["review", "AssuranceCompliancePlanning", "readPersonalAssurancePlanning"]] as const) {
      assert.equal(h.component(panels[step], name)!.props.preview, h.results[reader]);
    }
    assert.deepEqual(h.assessment, before); assert.equal(h.calls.length, 6);
  } finally { await h.cleanup(); }
});

test("each rejected page preview uses only its own fixed fallback while all other reads continue", async () => {
  for (const failed of readers) {
    const h = await harness();
    try {
      h.unlock(); const work = h.start(); await h.drain(); assert.equal(h.calls.length, 6);
      h.gates[failed].reject(new Error("Private upstream credential <script>must not appear</script>"));
      for (const name of readers) if (name !== failed) h.gates[name].resolve(h.results[name]);
      const panels = h.panels(await work);
      const placements = [
        ["comparison", "ComparisonSection", "readComparisonEvidence", "Synthetic comparison unavailable"],
        ["architecture", "ArchitecturePatterns", "readPersonalArchitecturePatterns", "Architecture pattern preflight unavailable"],
        ["usage", "UsagePlanningPreflight", "readPersonalUsagePlanning", "Usage input check unavailable"],
        ["usage", "OperationsPlanning", "readPersonalOperationsPlanning", "OperationsPlanningUnavailable"],
        ["auditability", "AuditabilityPreflight", "readPersonalAuditability", "AuditabilityPreflightUnavailable"],
        ["review", "AssuranceCompliancePlanning", "readPersonalAssurancePlanning", "AssuranceCompliancePlanningUnavailable"],
      ] as const;
      for (const [step, component, reader, fallback] of placements) {
        assert.equal(Boolean(h.component(panels[step], component)), reader !== failed, `${failed}: ${component}`);
        if (reader === failed) {
          if (fallback.endsWith("Unavailable")) assert.ok(h.component(panels[step], fallback));
          else assert.ok(renderToStaticMarkup(panels[step] as never).includes(fallback));
        }
        assert.equal(renderToStaticMarkup(panels[step] as never).includes("Private upstream"), false);
      }
      assert.ok(h.component(panels.architecture, "ProvisioningLifecycle"));
      assert.ok(h.component(panels.review, "SavedRequirementsOverview"));
      assert.ok(h.component(panels.usage, "UsagePlanningEditor"));
      assert.ok(h.component(panels.auditability, "AuditabilityEditor"));
    } finally { await h.cleanup(); }
  }
});

test("an early preview rejection is contained even while an unrelated response is still pending", async () => {
  const h = await harness();
  try {
    h.unlock(); const work = h.start(); await h.drain(); assert.equal(h.calls.length, 6);
    let complete = false; void work.then(() => { complete = true; });
    h.gates.readComparisonEvidence.reject(new Error("Private comparison failure"));
    for (const name of readers) if (!["readComparisonEvidence", "readPersonalAuditability"].includes(name)) h.gates[name].resolve(h.results[name]);
    await h.drain(); assert.equal(complete, false); assert.equal(h.calls.length, 6);
    h.gates.readPersonalAuditability.resolve(h.results.readPersonalAuditability);
    const panels = h.panels(await work);
    assert.ok(renderToStaticMarkup(panels.comparison as never).includes("Synthetic comparison unavailable"));
    assert.ok(h.component(panels.auditability, "AuditabilityPreflight"));
  } finally { await h.cleanup(); }
});

test("unreadable saved sections do not start their preview or infer substitute inputs", async () => {
  for (const section of ["application", "security", "operations"] as const) {
    const h = await harness();
    try {
      delete h.assessment.profile[section]; h.unlock(); const work = h.start(); await h.drain();
      const expected = section === "application" ? ["readComparisonEvidence", "readPersonalUsagePlanning", "readPersonalOperationsPlanning", "readPersonalAuditability"]
        : section === "security" ? ["readPersonalUsagePlanning", "readPersonalOperationsPlanning"]
          : ["readComparisonEvidence", "readPersonalArchitecturePatterns", "readPersonalAuditability", "readPersonalAssurancePlanning"];
      assert.deepEqual(h.calls.map(call => call.name), expected); h.settle(); const panels = h.panels(await work);
      assert.ok(h.component(panels.review, "SavedRequirementsOverview"));
      assert.equal(h.calls.length, expected.length);
    } finally { await h.cleanup(); }
  }
});

test("invalid IDs, absent sessions and unavailable or missing canonical assessments never start previews", async () => {
  for (const mode of ["id", "session", "session-failure", "missing", "assessment-failure"] as const) {
    const h = await harness();
    try {
      const work = h.start(mode === "id" ? "../other" : h.assessment.id);
      // Attach refusal handling immediately, before a controlled gate can reject.
      const outcome = work.then(tree => ({ tree, error: null }), error => ({ tree: null, error }));
      if (mode === "session-failure") h.sessionGate.reject(new Error("Private session database"));
      else h.sessionGate.resolve(mode === "session" ? null : h.session);
      if (mode === "assessment-failure") h.profileGate.reject(new Error("Private Core credential"));
      else h.profileGate.resolve(mode === "missing" ? null : h.assessment);
      h.queryGate.resolve({ step: "review" });
      const result = await outcome;
      assert.equal(h.calls.length, 0);
      assert.equal(h.events.includes("assessment"), ["missing", "assessment-failure"].includes(mode));
      if (mode === "id" || mode === "missing") assert.match(String(result.error), /not-found/);
      else if (mode === "session") assert.match(String(result.error), /redirect:\/account/);
      else { const html = renderToStaticMarkup(result.tree as never); assert.ok(html.includes("Assessment unavailable")); assert.equal(html.includes("Private"), false); }
    } finally { await h.cleanup(); }
  }
});

test("archived assessments retain read-only placement when every parallel preview is unavailable", async () => {
  const h = await harness();
  try {
    h.assessment.status = "ARCHIVED"; h.unlock(); const work = h.start(); await h.drain();
    for (const name of readers) h.gates[name].reject(new Error("Private unavailable result"));
    const tree = await work, panels = h.panels(tree);
    for (const [step, editor] of [["context", "EvaluationContextEditor"], ["capabilities", "CapabilityEditor"],
      ["auditability", "AuditabilityEditor"], ["usage", "UsagePlanningEditor"], ["usage", "OperationalPreferencesEditor"]]) {
      assert.equal(Boolean(h.component(panels[step], editor)), false);
      assert.equal(renderToStaticMarkup(panels[step] as never).includes("Private unavailable"), false);
    }
    assert.equal(h.component(panels.review, "SavedRequirementsOverview")!.props.editable, false);
    assert.ok(renderToStaticMarkup(tree as never).includes("Saved profile and technical details"));
  } finally { await h.cleanup(); }
});

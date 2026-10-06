import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import ts from "typescript";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { assurancePlanningValues } from "../src/lib/assessment/assurance-compliance-planning.ts";
import { readPersonalAssurancePlanning } from "../src/lib/auth/core-client.ts";
import { assessmentUiComponents } from "./fixtures/assessment-ui.mts";
import { assuranceAt, assuranceWorkspaceId, assuranceAssessmentId, assuranceProfile, assuranceFixture } from "./fixtures/assurance-compliance-planning.mts";

type NodeView = { type: unknown; props: Record<string, unknown> };
function nodes(value: unknown): NodeView[] {
  if (Array.isArray(value)) return value.flatMap(nodes);
  if (!value || typeof value !== "object" || !Object.hasOwn(value, "props")) return [];
  const node = value as NodeView; return [node, ...Object.values(node.props).flatMap(nodes)];
}
function moduleUrl(source: string) { return `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`; }

// Invoke the actual async page with deterministic session/other-preview boundaries.
// The new BFF reader and consumer are real; this is not a browser OIDC or live Core test.
test("Personal page gates assurance on a live session, keeps its saved-version Review placement and sanitizes failed reads", async () => {
  const components = await assessmentUiComponents(), slot = `__authweave_assurance_page_${crypto.randomUUID()}`;
  const globals = globalThis as unknown as Record<string, unknown>;
  const oldFetch = globalThis.fetch, oldToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-assurance-page-token-000000000000000000";
  const session = { workspaceId: assuranceWorkspaceId, issuer: "http://localhost:8081", subject: "synthetic-page-owner", email: null, displayName: null, authenticatedAt: new Date(assuranceAt) };
  let live = true, reads = 0, calls = 0, fixture = assuranceFixture();
  const assessment = { id: assuranceAssessmentId, status: "DRAFT", version: 7, profile: assuranceProfile() };
  const dependencies: Record<string, unknown> = { ...components,
    cookies: async () => ({ get: () => ({ value: "synthetic-session-cookie" }) }), sessionCookieName: () => "session", authConfiguration: () => ({ secureCookies: false }),
    touchSession: async () => live ? session : null,
    redirect: (path: string) => { throw new Error(`redirect:${path}`); }, notFound: () => { throw new Error("notFound"); },
    readPersonalAssessment: async () => { reads++; return assessment; }, assurancePlanningValues, readPersonalAssurancePlanning,
    capabilityFields: [], capabilityValues: () => null, evaluationContextValues: () => null, usagePlanningValues: () => null,
    operationsPlanningValues: () => null, auditabilityValues: () => null, assessmentStepFromQuery: () => "review",
  };
  globals[slot] = dependencies;
  const source = await readFile(new URL("../src/app/assessments/[id]/page.tsx", import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText
    .replace(/import\s+([\s\S]*?)\s+from\s+"([^"]+)";/g, (whole: string, bindings: string, specifier: string) => {
      if (specifier === "react/jsx-runtime") return whole.replace('"react/jsx-runtime"', JSON.stringify(import.meta.resolve("react/jsx-runtime")));
      const names = bindings.startsWith("{") ? bindings.slice(1, -1).split(",").map(name => name.trim().split(/\s+as\s+/)[0]) : [];
      for (const name of names) if (!Object.hasOwn(dependencies, name)) dependencies[name] = () => null;
      if (!names.length && !Object.hasOwn(dependencies, bindings)) dependencies[bindings] = () => null;
      const body = names.length ? names.map(name => `export const ${name} = globalThis[${JSON.stringify(slot)}][${JSON.stringify(name)}];`).join("\n")
        : `export default globalThis[${JSON.stringify(slot)}][${JSON.stringify(bindings)}];`;
      return whole.replace(JSON.stringify(specifier), JSON.stringify(moduleUrl(body)));
    });
  const { default: Page } = await import(moduleUrl(compiled));
  const render = () => Page({ params: Promise.resolve({ id: assuranceAssessmentId }), searchParams: Promise.resolve({ step: "review" }) });
  const review = (tree: unknown) => {
    const workflow = nodes(tree).find(node => node.type === components.AssessmentWorkflow); assert.ok(workflow);
    assert.equal(workflow.props.initialStep, "review"); return (workflow.props.panels as Record<string, unknown>).review;
  };
  globalThis.fetch = async (url, init) => {
    calls++; assert.equal(String(url), `http://127.0.0.1:8080/api/v1/workspaces/${assuranceWorkspaceId}/assessments/${assuranceAssessmentId}/assurance-compliance-planning-preflight`);
    assert.equal(init?.method, "GET"); assert.equal(init?.cache, "no-store"); return Response.json(fixture);
  };
  try {
    const original = structuredClone(assessment), tree = await render(), card = nodes(review(tree)).find(node => node.type === components.AssuranceCompliancePlanning);
    assert.ok(card); assert.equal(card.props.editable, true); assert.equal(calls, 1); assert.equal(reads, 1); assert.deepEqual(assessment, original);
    assert.equal((card.props.preview as { assessmentVersion: number }).assessmentVersion, 7);
    assert.equal(nodes((nodes(tree).find(node => node.type === components.AssessmentWorkflow)!.props.panels as Record<string, unknown>).context)
      .some(node => node.type === components.AssuranceCompliancePlanning), false);
    assessment.status = "ARCHIVED"; const archived = nodes(review(await render())).find(node => node.type === components.AssuranceCompliancePlanning); assert.ok(archived); assert.equal(archived.props.editable, false);
    fixture = assuranceFixture(undefined, 8); const stale = nodes(review(await render())); assert.ok(stale.some(node => node.type === components.AssuranceCompliancePlanningUnavailable));
    assert.ok(!stale.some(node => node.type === components.AssuranceCompliancePlanning));
    fixture = assuranceFixture(); fixture.assuranceItems[0].question = "Private upstream detail <script>leak</script>";
    const forged = nodes(review(await render())); const fallback = forged.find(node => node.type === components.AssuranceCompliancePlanningUnavailable); assert.ok(fallback);
    assert.equal(renderToStaticMarkup(createElement(fallback.type as typeof components.AssuranceCompliancePlanningUnavailable)).includes("Private upstream"), false);
    delete (assessment.profile.security as Record<string, unknown>).assurance; const previousCalls = calls;
    assert.ok(nodes(review(await render())).some(node => node.type === components.AssuranceCompliancePlanningUnavailable)); assert.equal(calls, previousCalls);
    live = false; const previousReads = reads; await assert.rejects(render(), /redirect:\/account/); assert.equal(calls, previousCalls); assert.equal(reads, previousReads);
  } finally { delete globals[slot]; globalThis.fetch = oldFetch; if (oldToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = oldToken; }
});

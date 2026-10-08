import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import type { PersonalAssessment } from "../src/lib/auth/core-client.ts";
import { InvalidRequirementsBriefRequest, parseRequirementsBriefForm, requirementsBriefByteLimit,
  requirementsBriefFilename, requestSavedRequirementsBrief, RequirementsBriefDownloadError,
  savedRequirementsMarkdown } from "../src/lib/assessment/requirements-brief.ts";
import { savedRequirementGroups } from "../src/lib/assessment/saved-requirements.ts";
import { assessmentUiComponents, savedRequirementsFixture } from "./fixtures/assessment-ui.mts";
import { guidedScenarios } from "./fixtures/guided-scenarios.mts";
import { assuranceExpectations, assuranceExpectationLabels } from "../src/lib/assessment/assurance-expectation.ts";

const id = "80000000-0000-4000-8000-000000000001";
const assessment = (): PersonalAssessment => ({ id, status: "DRAFT", version: 7, profile: savedRequirementsFixture() });
const unescape = (value: string) => value.replace(/\\([!-~])/g, "$1");

test("saved Markdown brief and Review share the exact assurance planning label including Unknown", () => {
  for (const assurance of assuranceExpectations) {
    const input = assessment(); Object.assign(input.profile.security as object, { assurance });
    const before = structuredClone(input), text = unescape(savedRequirementsMarkdown(input));
    const row = savedRequirementGroups(input.profile)[1].rows![9];
    assert.equal(row.value, assuranceExpectationLabels[assurance]);
    assert.ok(text.includes(`**${row.label}:** ${row.value}`));
    if (assurance === "UNKNOWN") assert.ok(text.includes("Security and compliance scope / Assurance expectation (planning label): Not recorded"));
    assert.deepEqual(input, before);
  }
});

test("saved brief has a pinned deterministic v2 snapshot, version, status and honest scope", () => {
  const input = assessment(), before = structuredClone(input), text = savedRequirementsMarkdown(input);
  assert.equal(text, savedRequirementsMarkdown(input)); assert.deepEqual(input, before);
  assert.equal(createHash("sha256").update(text).digest("hex"), "df15350f5580b18d7fd8beaa9ae84d025582a4430bca031943258d150c4e7bb2");
  assert.ok(text.includes(`- Assessment ID: \`${id}\``)); assert.ok(text.includes("- Saved version: `7`"));
  assert.ok(text.includes("- Assessment status: `DRAFT`")); assert.ok(text.endsWith("\n"));
  assert.equal((text.match(/^## /gm) ?? []).length, 8);
  for (const label of ["Not an ADR", "unsaved edits", "not completeness, applicability or readiness", "not a full-profile backup", "Review the file for private details"]) assert.ok(text.includes(label), label);
  assert.equal(text.includes("workspaceId"), false); assert.equal(text.includes("recommendedProvider"), false);
});

for (const scenario of guidedScenarios) test(`saved ${scenario.key} brief preserves the same checked input rows as Review`, () => {
  const input = assessment(), profile = input.profile;
  profile.application = { type: scenario.applicationType, clients: [...scenario.clients] };
  profile.audience = { populations: [...scenario.populations], tenancy: scenario.tenancy, membership: scenario.membership };
  const text = savedRequirementsMarkdown(input), decoded = unescape(text), before = structuredClone(profile);
  for (const group of savedRequirementGroups(profile)) {
    assert.ok(decoded.includes(`## ${group.title}`));
    for (const row of group.rows ?? []) assert.ok(decoded.includes(row.value), row.label);
  }
  for (const label of [scenario.expected.application, scenario.expected.users, scenario.expected.clients]) assert.ok(decoded.includes(label), label);
  assert.deepEqual(profile, before);
});

test("Unknown, empty, Other, explicit none and zero retain their meanings rather than becoming exemptions", () => {
  const input = assessment(), profile = input.profile;
  profile.application = { type: "OTHER", clients: [] };
  profile.audience = { populations: [], tenancy: "UNKNOWN", membership: "UNKNOWN" };
  const security = profile.security as Record<string, unknown>;
  security.complianceScopeStatus = "NONE_IDENTIFIED"; security.complianceTargets = [];
  security.auditability = "UNKNOWN"; security.auditabilityRequirements = { selectedCriteria: [], minimumRetentionDays: null };
  const text = unescape(savedRequirementsMarkdown(input));
  for (const label of ["Other (needs definition)", "Needs definition", "Nothing recorded", "Unknown / not recorded", "No compliance targets identified after review",
    "No duration recorded; retention is not selected", "0 · Observed", "not automatic exemptions"]) assert.ok(text.includes(label), label);
  assert.ok(text.includes("Application and audience / Application type: Needs definition"));
  assert.equal(text.includes("**Monthly M2M token issuances:** 0 · Assumed"), false);
});

test("unreadable input sections remain isolated without copying unknown raw fields", () => {
  const input = assessment(); input.profile.application = { type: "sensitive-invalid" };
  input.profile.privateDetails = "private-content-must-not-leak";
  const text = savedRequirementsMarkdown(input);
  assert.ok(text.includes("This saved section could not be read safely"));
  assert.ok(text.includes("Identity capabilities")); assert.ok(text.includes("SCIM provisioning"));
  assert.ok(text.includes("Application and audience: saved section unavailable"));
  assert.equal(text.includes("sensitive-invalid"), false); assert.equal(text.includes("private-content-must-not-leak"), false);
});

test("planning text cannot inject Markdown links, images, HTML, headings or invisible controls", () => {
  const input = assessment();
  const scope = "<script>alert(1)</script>\n# Fake approval\n![image](https://example.invalid/pixel)\n```\n[a](https://example.invalid)\u0000\u202e";
  input.profile.operations = { usagePlanning: { scopeDescription: scope, assumptions: ["**claimed winner**", "NOT_REQUIRED"], volumes: {} } };
  const text = savedRequirementsMarkdown(input);
  for (const raw of ["<script>", "\n# Fake approval", "![image]", "https://example.invalid", "\n```", "\u0000", "\u202e"]) assert.equal(text.includes(raw), false, raw);
  const decoded = unescape(text);
  assert.ok(decoded.includes(scope.replaceAll("\n", "\n  ").replace("\u0000", "[U+0000]").replace("\u202e", "[U+202E]")));
  assert.ok(decoded.includes("**claimed winner**, NOT_REQUIRED"));
});

test("worst-case bounded planning text stays within the 64 KiB artifact limit", () => {
  const input = assessment(); input.profile.operations = { usagePlanning: {
    scopeDescription: "!".repeat(500), assumptions: Array.from({ length: 10 }, (_, n) => String(n) + "!".repeat(499)), volumes: {},
  } };
  assert.ok(new TextEncoder().encode(savedRequirementsMarkdown(input)).byteLength < requirementsBriefByteLimit);
});

test("export identity and exact single version reject path, filename and authority injection", () => {
  assert.equal(requirementsBriefFilename(id, 0), `authweave-requirements-${id}-v0.md`);
  assert.equal(parseRequirementsBriefForm(new URLSearchParams({ expectedVersion: "7" })), 7);
  for (const raw of ["", "07", "-1", "1.0", "1e3", "9007199254740992", "Infinity"]) {
    assert.throws(() => parseRequirementsBriefForm(new URLSearchParams({ expectedVersion: raw })), InvalidRequirementsBriefRequest);
  }
  for (const raw of ["", "expectedVersion=7&expectedVersion=7", "expectedVersion=7&profile=private", "expectedVersion=7&workspaceId=other", "expectedVersion=7&approvalGranted=true"]) {
    assert.throws(() => parseRequirementsBriefForm(new URLSearchParams(raw)), InvalidRequirementsBriefRequest);
  }
  for (const invalid of ["../other", `${id}\r\nfilename=private`, "NOT_A_UUID"]) assert.throws(() => requirementsBriefFilename(invalid, 7));
  for (const version of [-1, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) assert.throws(() => requirementsBriefFilename(id, version));
});

test("download reader uses only the fixed same-origin BFF endpoint and binds the attachment to saved version", async () => {
  const previousFetch = globalThis.fetch, input = assessment(), markdown = savedRequirementsMarkdown(input);
  const filename = requirementsBriefFilename(id, 7);
  const headers = { "Content-Type": "text/markdown; charset=utf-8", "Content-Disposition": `attachment; filename="${filename}"`, "Cache-Control": "no-store" };
  let calls = 0;
  globalThis.fetch = async (url, init) => {
    calls++; assert.equal(url, `/api/assessments/${id}/requirements-brief`); assert.equal(init?.method, "POST");
    assert.equal(init?.body, "expectedVersion=7"); assert.equal(init?.credentials, "same-origin");
    assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error");
    assert.deepEqual(init?.headers, { "Content-Type": "application/x-www-form-urlencoded" });
    return new Response(markdown, { headers });
  };
  try {
    await assert.rejects(requestSavedRequirementsBrief("../other", 7)); assert.equal(calls, 0);
    assert.deepEqual(await requestSavedRequirementsBrief(id, 7), { filename, markdown }); assert.equal(calls, 1);
    for (const status of [401, 403, 404, 409, 503]) {
      globalThis.fetch = async () => new Response("private upstream credential", { status });
      await assert.rejects(requestSavedRequirementsBrief(id, 7), error => error instanceof RequirementsBriefDownloadError &&
        error.kind === (status === 401 ? "session" : status === 404 ? "not-found" : status === 409 ? "stale" : "unavailable") && !error.message.includes("private upstream"));
    }
    for (const changed of [{ ...headers, "Content-Type": "text/html" },
      { ...headers, "Content-Disposition": 'attachment; filename="other.md"' },
      { ...headers, "Cache-Control": "public" }]) {
      globalThis.fetch = async () => new Response(markdown, { headers: changed });
      await assert.rejects(requestSavedRequirementsBrief(id, 7));
    }
    for (const content of [markdown.replace("- Saved version: `7`", "- Saved version: `8`"),
      markdown.replace("authweave-saved-requirements-brief-v2", "authweave-saved-requirements-brief-v1"),
      markdown.replace(id, "80000000-0000-4000-8000-000000000002"), "<html>private</html>", "x".repeat(requirementsBriefByteLimit + 1)]) {
      globalThis.fetch = async () => new Response(content, { headers });
      await assert.rejects(requestSavedRequirementsBrief(id, 7));
    }
  } finally { globalThis.fetch = previousFetch; }
});

test("actual Review export panel explains saved-only scope without credentials or implied publication", async () => {
  const { SavedRequirementsExport, SavedRequirementsOverview } = await assessmentUiComponents();
  const panel = createElement(SavedRequirementsExport, { assessmentId: id, version: 7 });
  const html = renderToStaticMarkup(createElement(SavedRequirementsOverview, {
    profile: savedRequirementsFixture(), version: 7, editable: false, exportPanel: panel,
  }));
  for (const phrase of ["Download saved brief (.md)", "It uses saved version 7, not unsaved edits", "Not a full-profile backup, final ADR", "private details before sharing", 'aria-live="polite"']) assert.ok(html.includes(phrase), phrase);
  assert.equal(html.includes("<form"), false); assert.equal(html.includes("workspaceId"), false);
  assert.ok(html.indexOf("Take your saved requirements with you") < html.indexOf("Application and audience"));
});

test("brief reader forwards Review cancellation and does not send an already-cancelled request", async () => {
  const previousFetch = globalThis.fetch;
  let calls = 0;
  const controller = new AbortController();
  globalThis.fetch = async (_url, init) => {
    calls++; const signal = init?.signal; assert.ok(signal instanceof AbortSignal);
    assert.equal(signal.aborted, false);
    return new Promise<Response>((_resolve, reject) => signal.addEventListener("abort", () => reject(signal.reason), { once: true }));
  };
  try {
    const request = requestSavedRequirementsBrief(id, 7, controller.signal);
    controller.abort(); await assert.rejects(request, error => error instanceof DOMException && error.name === "AbortError");
    assert.equal(calls, 1);
    await assert.rejects(requestSavedRequirementsBrief(id, 7, controller.signal)); assert.equal(calls, 1);
  } finally { globalThis.fetch = previousFetch; }
});

import assert from "node:assert/strict";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { criticalities } from "../src/lib/assessment/capabilities.ts";
import { parseEvaluationContextForm, evaluationContextValues } from "../src/lib/assessment/evaluation-context.ts";
import { auditCriteria, maximumRetentionDays, parseAuditabilityForm, auditabilityValues } from "../src/lib/assessment/auditability.ts";
import { evaluationContextFormIssues, auditabilityFormIssues } from "../src/lib/assessment/section-form-validation.ts";
import { postProfileSection, profileFormIssues } from "../src/lib/assessment/profile-save.ts";
import { profileFormFixture, profileSectionAction } from "./fixtures/profile-save.mts";
import { assessmentUiComponents, savedRequirementsFixture } from "./fixtures/assessment-ui.mts";
import { guidedScenarios } from "./fixtures/guided-scenarios.mts";

const fallback = [{ fieldId: null,
  message: "Check this section's selections and values. Nothing was sent or automatically corrected." }];
const codes = Array.from({ length: 250 }, (_, index) =>
  String.fromCharCode(65 + Math.floor(index / 26), 65 + index % 26)); // Syntax fixtures, not an ISO registry.

test("assurance diagnostics use fixed field feedback and refuse malformed choices before network IO", async () => {
  for (const raw of ["", "high", "AAL3", "<script>private-assurance-input</script>", "HIGH "]) {
    const params = profileFormFixture("context"); params.set("assuranceExpectation", raw);
    const before = params.toString(), issues = evaluationContextFormIssues(params);
    assert.deepEqual(issues.map(issue => issue.fieldId), ["context-assuranceExpectation"]);
    assert.ok(issues[0].message.includes("select one planning label"));
    assert.equal(issues[0].message.includes("private-assurance-input"), false);
    let calls = 0;
    assert.equal(await postProfileSection("context", profileSectionAction("context"), params,
      async () => { calls++; throw new Error("Unexpected request"); }), "invalid");
    assert.equal(calls, 0); assert.equal(params.toString(), before);
  }
  for (const value of ["BASELINE", "ELEVATED", "HIGH", "UNKNOWN"]) {
    const params = profileFormFixture("context"); params.set("assuranceExpectation", value);
    assert.deepEqual(profileFormIssues("context", params), []);
  }
  const duplicate = profileFormFixture("context");
  duplicate.append("assuranceExpectation", "HIGH"); duplicate.append("assuranceExpectation", "HIGH");
  assert.equal(evaluationContextFormIssues(duplicate)[0].fieldId, "context-assuranceExpectation");
  assert.throws(() => parseEvaluationContextForm(duplicate));
});

test("Context keeps blank, whitespace, sorted, partial and syntax-only country semantics unchanged", () => {
  for (const text of ["", " \n ", "US", " CA , US ", "ZZ", "US".padEnd(1024, " "), codes.slice(0, 249).join(",")]) {
    const params = profileFormFixture("context"); params.set("allowedCountries", text);
    const before = params.toString();
    assert.deepEqual(evaluationContextFormIssues(params), []);
    assert.doesNotThrow(() => parseEvaluationContextForm(params));
    assert.equal(params.toString(), before);
  }
  // Country membership and cross-field policy still belong to Core, not this UI formatter.
  const params = profileFormFixture("context"); params.set("allowedCountries", "");
  params.delete("clients"); params.delete("selectedPopulations");
  assert.deepEqual(profileFormIssues("context", params), []);
});

test("Context format, duplicate, count and length refusals point to the existing country field without echo or correction", () => {
  for (const [text, hint] of [["us, CA", "uppercase"], ["USA", "two-letter"], ["US,", "empty entry"],
    ["US, US", "each code once"], [codes.join(","), "249 codes"], ["US".padEnd(1025, " "), "1,024 characters"],
    ["<script>private-country-input</script>", "two-letter"]]) {
    const params = profileFormFixture("context"); params.set("allowedCountries", text);
    const before = params.toString(); const issues = evaluationContextFormIssues(params);
    assert.equal(issues.length, 1); assert.equal(issues[0].fieldId, "context-allowedCountries");
    assert.ok(issues[0].message.includes(hint)); assert.ok(!issues[0].message.includes("private-country-input"));
    assert.throws(() => parseEvaluationContextForm(params)); assert.equal(params.toString(), before);
  }
  const multiple = profileFormFixture("context"); multiple.set("allowedCountries", "us, US, US");
  const issues = evaluationContextFormIssues(multiple);
  assert.equal(issues.length, 2);
  assert.ok(issues[0].message.includes("uppercase")); assert.ok(issues[1].message.includes("each code once"));
});

test("Audit diagnostics preserve every valid criterion subset, criticality, boundary duration and explicit clear", () => {
  for (const criticality of criticalities) for (let mask = 0; mask < 2 ** auditCriteria.length; mask++) {
    for (const duration of ["1", String(maximumRetentionDays)]) {
      const params = new URLSearchParams({ expectedVersion: "0", criticality });
      auditCriteria.forEach((criterion, index) => { if (mask & 2 ** index) params.append("selectedCriteria", criterion.key); });
      if (params.getAll("selectedCriteria").includes("AUDIT_LOG_RETENTION")) params.set("minimumRetentionDays", duration);
      else if (duration === "1") params.set("minimumRetentionDays", "");
      const before = params.toString();
      assert.deepEqual(auditabilityFormIssues(params), []);
      assert.doesNotThrow(() => parseAuditabilityForm(params)); assert.equal(params.toString(), before);
    }
  }
});

test("selected retention needs canonical whole days; guidance never rounds, fills or echoes the input", () => {
  for (const value of [undefined, "", "0", "36501", "01", "1.0", "1e2", "-1", "+1", " 30", "30 ",
    "9007199254740992", "<script>private-retention-input</script>"]) {
    const params = profileFormFixture("auditability");
    if (value === undefined) params.delete("minimumRetentionDays"); else params.set("minimumRetentionDays", value);
    const before = params.toString(); const issues = auditabilityFormIssues(params);
    assert.equal(issues.length, 1); assert.equal(issues[0].fieldId, "audit-retention-days");
    assert.ok(issues[0].message.includes("1–36,500") || issues[0].message.includes("1 to 36,500"));
    assert.ok(!issues[0].message.includes("private-retention-input"));
    assert.throws(() => parseAuditabilityForm(params)); assert.equal(params.toString(), before);
  }
});

test("orphan retention feedback links to its enabled checkbox, not the disabled duration field", () => {
  const params = profileFormFixture("auditability"); params.delete("selectedCriteria");
  const before = params.toString(); const issues = auditabilityFormIssues(params);
  assert.deepEqual(issues.map(issue => issue.fieldId), ["audit-AUDIT_LOG_RETENTION"]);
  assert.ok(issues[0].message.includes("only when you save"));
  assert.throws(() => parseAuditabilityForm(params)); assert.equal(params.toString(), before);
  params.delete("minimumRetentionDays");
  assert.deepEqual(auditabilityFormIssues(params), []); // Explicit clear remains saveable.
});

test("malformed shape, version and selections stay refused with fixed fallback and no arbitrary field links", () => {
  for (const section of ["context", "auditability"] as const) {
    const field = section === "context" ? "allowedCountries" : "minimumRetentionDays";
    for (const change of [(p: URLSearchParams) => p.append("workspaceId", "private"),
      (p: URLSearchParams) => p.set("expectedVersion", "01"), (p: URLSearchParams) => p.append("expectedVersion", "0"),
      (p: URLSearchParams) => p.append(field, "30"),
      (p: URLSearchParams) => p.set(section === "context" ? "applicationType" : "criticality", "javascript:alert(1)"),
      (p: URLSearchParams) => p.append(section === "context" ? "clients" : "selectedCriteria", "private")]) {
      const params = profileFormFixture(section); change(params); const before = params.toString();
      assert.deepEqual(profileFormIssues(section, params), fallback);
      assert.throws(() => section === "context" ? parseEvaluationContextForm(params) : parseAuditabilityForm(params));
      assert.equal(params.toString(), before);
    }
  }
});

test("all three agreed guided scenarios keep their exact Context/Audit payloads and local refusals never make a write", async () => {
  for (const scenario of guidedScenarios) {
    const context = profileFormFixture("context");
    context.set("applicationType", scenario.applicationType); context.set("tenancy", scenario.tenancy);
    context.set("membership", scenario.membership); context.set("browserTokenExposureMinimization", scenario.tokenExposure);
    context.set("phishingResistance", scenario.phishingResistance); context.set("allowedCountries", "");
    context.delete("clients"); scenario.clients.forEach(value => context.append("clients", value));
    context.delete("selectedPopulations"); scenario.populations.forEach(value => context.append("selectedPopulations", value));
    const audit = new URLSearchParams({ expectedVersion: "0", criticality: "REQUIRED", minimumRetentionDays: String(scenario.retention) });
    scenario.audit.forEach(value => audit.append("selectedCriteria", value));
    for (const [section, params] of [["context", context], ["auditability", audit]] as const) {
      const before = params.toString(); assert.deepEqual(profileFormIssues(section, params), []);
      assert.equal(params.toString(), before);
      params.set(section === "context" ? "allowedCountries" : "minimumRetentionDays", section === "context" ? "US, US" : "030");
      let writes = 0;
      assert.equal(await postProfileSection(section, profileSectionAction(section), params, async () => {
        writes++; throw new Error("Unexpected IO for invalid inputs");
      }), "invalid");
      assert.equal(writes, 0); assert.ok(profileFormIssues(section, params).length > 0);
    }
  }
});

test("feedback targets are unique labelled existing controls and native Audit constraints remain enabled", async () => {
  const components = await assessmentUiComponents(); const profile = savedRequirementsFixture();
  const context = renderToStaticMarkup(createElement(components.EvaluationContextEditor, {
    assessmentId: "4640bbac-c20f-476a-a4dc-23efad5ff14f", version: 0, values: evaluationContextValues(profile)! }));
  const audit = renderToStaticMarkup(createElement(components.AuditabilityEditor, {
    assessmentId: "4640bbac-c20f-476a-a4dc-23efad5ff14f", version: 0, values: auditabilityValues(profile)! }));
  for (const [html, id] of [[context, "context-allowedCountries"], [audit, "audit-retention-days"], [audit, "audit-AUDIT_LOG_RETENTION"]]) {
    assert.equal(html.split(`id="${id}"`).length - 1, 1); assert.ok(html.includes(`for="${id}"`));
  }
  const duration = audit.match(/<input\b[^>]*id="audit-retention-days"[^>]*>/)?.[0];
  const countries = context.match(/<input\b[^>]*id="context-allowedCountries"[^>]*>/)?.[0];
  assert.ok(countries?.includes('maxLength="1024"'));
  assert.ok(countries?.includes('pattern="[ ]*(?:[A-Z]{2}(?:[ ]*,[ ]*[A-Z]{2})*)?[ ]*"'));
  assert.equal(context.includes("novalidate"), false);
  assert.ok(duration?.includes('min="1"')); assert.ok(duration?.includes('max="36500"'));
  assert.ok(duration?.includes('step="1"')); assert.ok(duration?.includes('required=""'));
  assert.equal(audit.includes("novalidate"), false);
});

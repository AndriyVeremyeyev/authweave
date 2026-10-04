import assert from "node:assert/strict";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { criticalities } from "../src/lib/assessment/capabilities.ts";
import { contextSecurityGuidance, complianceScopeGuidance, complianceTargetGuidance, securityLevelGuidance } from "../src/lib/assessment/context-guidance.ts";
import { complianceScopeStatuses, complianceTargets, evaluationContextValues, parseEvaluationContextForm,
  type EvaluationContextValues } from "../src/lib/assessment/evaluation-context.ts";
import { assessmentUiComponents, savedRequirementsFixture } from "./fixtures/assessment-ui.mts";

const id = "4640bbac-c20f-476a-a4dc-23efad5ff14f";
const { EvaluationContextEditor } = await assessmentUiComponents();

function render(values: EvaluationContextValues, version = 5) {
  return renderToStaticMarkup(createElement(EvaluationContextEditor, { assessmentId: id, version, values }));
}

// Extract only successful native form controls from the real server-rendered editor.
function formData(html: string) {
  const params = new URLSearchParams();
  for (const [tag] of html.matchAll(/<input\b[^>]*>/g)) {
    const name = tag.match(/\bname="([^"]+)"/)![1];
    const value = tag.match(/\bvalue="([^"]*)"/)![1];
    if (!tag.includes('type="checkbox"') || tag.includes('checked=""')) params.append(name, value);
  }
  for (const [, name, options] of html.matchAll(/<select\b[^>]*name="([^"]+)"[^>]*>(.*?)<\/select>/g)) {
    const selected = [...options.matchAll(/<option\b[^>]*>/g)].filter(([tag]) => tag.includes('selected=""'));
    assert.equal(selected.length, 1);
    params.append(name, selected[0][0].match(/\bvalue="([^"]*)"/)![1]);
  }
  return params;
}

test("context guidance covers exactly the scoped security fields, levels, compliance states and targets", () => {
  assert.deepEqual(Object.keys(contextSecurityGuidance), ["dataResidency", "browserTokenExposureMinimization", "phishingResistance", "nonExportableKeys", "stepUpAuthentication"]);
  assert.deepEqual(Object.keys(securityLevelGuidance), [...criticalities]);
  assert.deepEqual(Object.keys(complianceScopeGuidance), [...complianceScopeStatuses]);
  assert.deepEqual(Object.keys(complianceTargetGuidance), [...complianceTargets]);
  const references = [...Object.values(contextSecurityGuidance), ...Object.values(complianceTargetGuidance)].flatMap(guide => guide.reference ? [guide.reference] : []);
  assert.equal(references.length, 9);
  for (const reference of references) {
    const url = new URL(reference.href);
    assert.equal(url.protocol, "https:");
    assert.ok(["datatracker.ietf.org", "pages.nist.gov", "www.aicpa-cima.com", "www.iso.org", "www.hhs.gov", "www.fedramp.gov", "commission.europa.eu"].includes(url.hostname));
    assert.equal(url.username + url.password + url.search, "");
  }
});

test("real editor round-trips the exact saved form values for all three scenarios without extra fields or mutations", () => {
  for (const applicationType of ["B2B_SAAS", "PUBLIC_SECTOR_PORTAL", "INTERNAL_WORKFORCE"] as const) {
    const profile = savedRequirementsFixture();
    Object.assign(profile.application, { type: applicationType });
    const before = structuredClone(profile);
    const values = evaluationContextValues(profile)!;
    const html = render(values);
    assert.ok(html.includes(`action="/api/assessments/${id}/evaluation-context" method="post"`));
    assert.deepEqual(parseEvaluationContextForm(formData(html)), { expectedVersion: 5, values });
    assert.equal([...html.matchAll(/<select\b/g)].length, 9);
    assert.equal([...html.matchAll(/<input\b/g)].length, 21);
    assert.equal([...html.matchAll(/<button\b/g)].length, 1);
    assert.deepEqual(profile, before);
  }
});

test("every security level and compliance scope stays explicit rather than inferred from help", () => {
  for (const criticality of criticalities) for (const scope of complianceScopeStatuses) {
    const values = evaluationContextValues(savedRequirementsFixture())!;
    for (const field of Object.keys(contextSecurityGuidance) as (keyof typeof contextSecurityGuidance)[]) values[field] = criticality;
    values.complianceScopeStatus = scope;
    values.selectedComplianceTargets = scope === "NONE_IDENTIFIED" ? [] : ["SOC_2", "OTHER"];
    const before = structuredClone(values);
    const parsed = parseEvaluationContextForm(formData(render(values, 0)));
    assert.deepEqual(parsed, { expectedVersion: 0, values });
    assert.deepEqual(values, before);
  }
});

test("empty choices and unknowns remain unrecorded; country examples are not submitted defaults", () => {
  const values: EvaluationContextValues = { applicationType: "UNKNOWN", tenancy: "UNKNOWN", membership: "UNKNOWN",
    clients: [], selectedPopulations: [], dataResidency: "UNKNOWN", allowedCountries: [], selectedDataCategories: [],
    browserTokenExposureMinimization: "UNKNOWN", phishingResistance: "UNKNOWN", nonExportableKeys: "UNKNOWN",
    stepUpAuthentication: "UNKNOWN", complianceScopeStatus: "UNKNOWN", selectedComplianceTargets: [] };
  const html = render(values);
  assert.deepEqual(parseEvaluationContextForm(formData(html)).values, values);
  assert.equal([...html.matchAll(/checked=""/g)].length, 0);
  assert.equal([...html.matchAll(/value="UNKNOWN" selected=""/g)].length, 9);
  assert.ok(html.includes('name="allowedCountries"'));
  assert.ok(html.includes('placeholder="US, CA"'));
  assert.equal(formData(html).get("allowedCountries"), "");
});

test("guided controls and grouped sections have unique accessible descriptions and closed native help", () => {
  const html = render(evaluationContextValues(savedRequirementsFixture())!);
  const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]);
  assert.equal(new Set(ids).size, ids.length);
  for (const idref of [...html.matchAll(/aria-(?:describedby|labelledby)="([^"]+)"/g)].flatMap(match => match[1].split(" "))) assert.ok(ids.includes(idref), idref);
  for (const field of Object.keys(contextSecurityGuidance)) {
    assert.ok(html.includes(`for="context-${field}"`));
    assert.ok(html.includes(`aria-describedby="context-${field}-description"`));
  }
  assert.equal([...html.matchAll(/<details\b/g)].length, 8);
  assert.equal(/<details[^>]*\bopen\b/.test(html), false);
  assert.equal([...html.matchAll(/rel="noopener noreferrer"/g)].length, 9);
  assert.equal(html.includes("<script"), false);
});

test("educational copy preserves policy boundaries rather than issuing security or legal conclusions", () => {
  const html = render(evaluationContextValues(savedRequirementsFixture())!);
  for (const text of ["not changes to AuthWeave&#x27;s own sign-in", "not an overall security verdict",
    "not a blanket token ban", "not processing locations", "MFA alone does not establish phishing resistance",
    "disabling synchronization alone, does not establish non-exportability", "before the action",
    "not interpreted as a request for weaker authentication", "The target list must be empty",
    "Record at least one target label", "not a legal exemption", "legal applicability decision",
    "A storage-country choice alone does not establish compliance", "Internet-Draft"])
    assert.ok(html.includes(text), text);
  assert.equal(html.includes("providerId"), false);
  assert.equal(html.includes("recommendationReady"), false);
});

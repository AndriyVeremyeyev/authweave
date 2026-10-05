import assert from "node:assert/strict";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { capabilityFields, capabilityValues, criticalities, type CapabilityValues } from "../src/lib/assessment/capabilities.ts";
import { capabilityGuidance, criticalityGuidance } from "../src/lib/assessment/capability-guidance.ts";
import { assessmentUiComponents, savedRequirementsFixture } from "./fixtures/assessment-ui.mts";

const assessmentId = "4640bbac-c20f-476a-a4dc-23efad5ff14f";
const { CapabilityEditor } = await assessmentUiComponents();

function render(values: CapabilityValues, version = 5) {
  return renderToStaticMarkup(createElement(CapabilityEditor, { assessmentId, version, values }));
}

test("educational guidance covers exactly the nine capabilities and five existing criticalities", () => {
  assert.deepEqual(Object.keys(capabilityGuidance).sort(), capabilityFields.map(field => field.capability).sort());
  assert.deepEqual(Object.keys(criticalityGuidance), [...criticalities]);
  for (const field of capabilityFields) {
    const guide = capabilityGuidance[field.capability];
    for (const key of ["definition", "usefulWhen", "tradeOff", "question"] as const) {
      assert.ok(guide[key].length >= 20 && guide[key].length <= 300);
    }
    const url = new URL(guide.source.href);
    assert.equal(url.protocol, "https:");
    assert.ok(["openid.net", "docs.oasis-open.org", "www.rfc-editor.org", "learn.microsoft.com", "help.okta.com", "pages.nist.gov"].includes(url.hostname));
    assert.equal(url.username + url.password + url.search, "");
  }
});

test("real editor preserves every saved selection and the exact existing form binding", () => {
  const profile = savedRequirementsFixture();
  const before = structuredClone(profile);
  const values = capabilityValues(profile)!;
  const html = render(values);
  assert.ok(html.includes(`action="/api/assessments/${assessmentId}/capabilities" method="post"`));
  assert.ok(html.includes('type="hidden" name="expectedVersion" value="5"'));
  assert.equal([...html.matchAll(/<select\b/g)].length, 9);
  assert.equal([...html.matchAll(/<option\b/g)].length, 45);
  assert.equal([...html.matchAll(/<input\b/g)].length, 1);
  assert.equal([...html.matchAll(/<button\b[^>]*type="submit"/g)].length, 1);
  assert.equal([...html.matchAll(/<button\b[^>]*type="button"/g)].length, 2);
  assert.equal([...html.matchAll(/selected=""/g)].length, 9);
  for (const field of capabilityFields) {
    const select = html.match(new RegExp(`<select[^>]*name="${field.capability}"[^>]*>(.*?)</select>`))![1];
    assert.ok(select.includes(`<option value="${values[field.capability]}" selected="">`));
  }
  assert.deepEqual(profile, before);
});

test("unknowns and all five choices remain user answers rather than educational defaults", () => {
  for (const value of criticalities) {
    const values = Object.fromEntries(capabilityFields.map(field => [field.capability, value])) as CapabilityValues;
    const before = structuredClone(values);
    const html = render(values, 0);
    assert.equal([...html.matchAll(new RegExp(`<option value="${value}" selected="">`, "g"))].length, 9);
    assert.ok(html.includes('name="expectedVersion" value="0"'));
    assert.deepEqual(values, before);
  }
});

test("each control has a unique accessible definition and a separately named native disclosure", () => {
  const html = render(capabilityValues(savedRequirementsFixture())!);
  const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]);
  assert.equal(new Set(ids).size, ids.length);
  assert.equal([...html.matchAll(/<details\b/g)].length, 10);
  assert.equal(html.includes("<details open"), false);
  for (const field of capabilityFields) {
    const control = `capability-${field.capability}`;
    assert.ok(html.includes(`for="${control}"`));
    assert.ok(html.includes(`id="${control}-description"`));
    assert.ok(html.includes(`aria-describedby="${control}-description"`));
    assert.ok(html.includes(`<span class="sr-only">: ${field.label}</span>`));
    assert.ok(html.includes(`href="${capabilityGuidance[field.capability].source.href}" target="_blank" rel="noopener noreferrer"`));
  }
  assert.equal([...html.matchAll(/Concept reference:/g)].length, 9);
  assert.equal(html.includes("<script"), false);
});

test("guide separates concept references from provider evidence and explains high-risk distinctions", () => {
  const html = render(capabilityValues(savedRequirementsFixture())!);
  for (const text of ["do not choose answers or verify provider support", "fictional options, not verified real-provider facts",
    "Preferences cannot reverse an exclusion", "this is not a prohibition", "optional and kept disabled",
    "Missing or outdated evidence leaves the check unresolved", "not a replacement for a required SCIM lifecycle",
    "Not all MFA is phishing-resistant", "SSO is not another protocol", "OAuth 2.0 alone is not a user sign-in protocol",
    "sign-in claim is not automatically continuous synchronization"]) assert.ok(html.includes(text), text);
  assert.equal(html.includes("recommendationReady"), false);
  assert.equal(html.includes("providerId"), false);
});

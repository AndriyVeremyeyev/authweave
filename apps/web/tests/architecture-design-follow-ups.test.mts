import assert from "node:assert/strict";
import { test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { architectureDesignFollowUpsModuleUrl } from "./fixtures/assessment-ui.mts";

const { ArchitectureDesignFollowUps } = await import(await architectureDesignFollowUpsModuleUrl());
const checks = [
  { fieldId: "BFF_SESSION-configuration-OAUTH_FLOW", label: "OAuth flow", outcome: "CONDITIONALLY_NOT_SATISFIED" },
  { fieldId: "BFF_SESSION-configuration-OAUTH_CLIENT_TYPE", label: "OAuth client type", outcome: "UNKNOWN" },
  { fieldId: "BFF_SESSION-configuration-TOKEN_LOCATION", label: "OAuth token location", outcome: "CONDITIONALLY_SATISFIED" },
];
const render = (props = {}) => renderToStaticMarkup(createElement(ArchitectureDesignFollowUps,
  { clientScope: "SELECTED", kind: "settings", checks, ...props }));

test("settings summary separates exact unmet and unknown proposals without defaults, mutation or additional forms", () => {
  const before = structuredClone(checks), html = render();
  assert.ok(html.includes("Does not match the reference setting (1)")); assert.ok(html.includes("Unknown proposal (1)"));
  assert.ok(html.indexOf("OAuth flow</a>") < html.indexOf("OAuth client type</a>"));
  assert.ok(html.includes('href="#BFF_SESSION-configuration-OAUTH_FLOW"'));
  assert.ok(html.includes('href="#BFF_SESSION-configuration-OAUTH_CLIENT_TYPE"'));
  assert.equal(html.includes('href="#BFF_SESSION-configuration-TOKEN_LOCATION"'), false);
  assert.equal((html.match(/<a /g) ?? []).length, 2); assert.equal(html.includes("<form"), false);
  assert.equal(html.includes("<select"), false); assert.equal(html.includes("<input"), false);
  for (const text of ["do not choose an answer", "Editing clears this result", "Nothing is saved", "unverified proposals", "full checks below"]) assert.ok(html.includes(text), text);
  assert.deepEqual(checks, before);
});

test("conditions use declaration wording and preserve check order within each exact group", () => {
  const rows = [
    { fieldId: "M2M_CLIENT_CREDENTIALS-WORKLOAD_CONFIDENTIAL_CLIENT", label: "Credential custody", outcome: "UNKNOWN" },
    { fieldId: "M2M_CLIENT_CREDENTIALS-WORKLOAD_AUTHORIZATION_CONTEXT", label: "Authorization context", outcome: "CONDITIONALLY_NOT_SATISFIED" },
    { fieldId: "M2M_CLIENT_CREDENTIALS-WORKLOAD_GRANT_API_PERMISSIONS", label: "API permissions", outcome: "UNKNOWN" },
  ];
  const html = render({ kind: "conditions", checks: rows });
  assert.ok(html.includes("Declared not met (1)")); assert.ok(html.includes("Unknown proposal (2)"));
  assert.ok(html.indexOf("Credential custody</a>") < html.indexOf("API permissions</a>"));
  assert.equal(html.includes("reference setting"), false); assert.deepEqual(rows.map(row => row.outcome), ["UNKNOWN", "CONDITIONALLY_NOT_SATISFIED", "UNKNOWN"]);
});

test("unknown saved client scope is not misrepresented as unanswered design fields", () => {
  const html = render({ clientScope: "UNKNOWN", checks: checks.map(check => ({ ...check, outcome: "UNKNOWN" })) });
  assert.ok(html.includes("Client applicability is unknown, not a missing design answer"));
  assert.ok(html.includes("Context and save")); assert.equal(html.includes("<a "), false);
  assert.equal(html.includes("Unknown proposal"), false); assert.equal(html.includes("No unmet or unknown"), false);
});

test("unselected client scope cannot become applicable through proposed design choices", () => {
  const html = render({ clientScope: "NOT_SELECTED", checks: checks.map(check => ({ ...check, outcome: "NOT_APPLICABLE" })) });
  assert.ok(html.includes("cannot make this pattern applicable")); assert.ok(html.includes("saved client types in Context"));
  assert.equal(html.includes("<a "), false); assert.equal(html.includes("No unmet or unknown"), false);
  assert.equal(html.includes("Declared not met"), false); assert.equal(html.includes("Unknown proposal"), false);
});

test("all matched declarations have no field follow-ups but never claim configuration verification or readiness", () => {
  for (const kind of ["conditions", "settings"]) {
    const html = render({ kind, checks: checks.map(check => ({ ...check, outcome: "CONDITIONALLY_SATISFIED" })) });
    assert.ok(html.includes(`No unmet or unknown ${kind} in this temporary preview`));
    assert.ok(html.includes("not verification or an architecture recommendation")); assert.ok(html.includes("not tasks, saved requirements"));
    assert.equal(html.includes("<a "), false);
  }
});

test("labels are escaped and every field link remains a same-page fragment, not a source fetch or executable URL", () => {
  const html = render({ checks: [{ fieldId: "NATIVE_CODE_PKCE-configuration-NATIVE_USER_AGENT",
    label: '<script>literal()</script>&"name"', outcome: "UNKNOWN" }] });
  assert.ok(html.includes("&lt;script&gt;literal()&lt;/script&gt;")); assert.equal(html.includes("<script>literal"), false);
  assert.ok(html.includes('href="#NATIVE_CODE_PKCE-configuration-NATIVE_USER_AGENT"')); assert.equal(html.includes("https://"), false);
  assert.equal(html.includes("javascript:"), false); assert.ok(html.includes('aria-label="Next steps for this temporary preview"'));
});

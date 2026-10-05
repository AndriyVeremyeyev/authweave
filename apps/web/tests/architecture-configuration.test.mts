import assert from "node:assert/strict";
import { test } from "node:test";
import { architectureConfigurationAnalysis, architectureConfigurationFromCore, architectureConfigurationPatterns, architectureSettingValues,
  parseArchitectureConfigurationForm, validateArchitectureConfigurationInput, type ArchitectureConfigurationInput, type ArchitectureConfigurationContext } from "../src/lib/assessment/architecture-configuration.ts";
import { criticalities } from "../src/lib/assessment/capabilities.ts";
import { configurationBinding, configurationContext, configurationFixture, configurationInput, configurationMatching, configurationPatterns } from "./fixtures/architecture-configuration.mts";
import { previewPersonalArchitectureConfiguration } from "../src/lib/auth/core-client.ts";
import { prerequisiteProfile } from "./fixtures/architecture-prerequisites.mts";

test("Concrete settings replay 800 saved-client/criticality/design combinations without overriding browser-token gaps", () => {
  let combinations = 0;
  const clients = ["BROWSER", "NATIVE_MOBILE", "MACHINE_TO_MACHINE"] as const;
  for (let mask = 0; mask < 8; mask++) for (const requirement of criticalities) for (const patternId of configurationPatterns) {
    const context: ArchitectureConfigurationContext = { clients: clients.filter((_, index) => mask & 1 << index), browserTokenExposureMinimization: requirement };
    for (const settings of [{}, Object.fromEntries(Object.keys(configurationMatching[patternId]).map(id => [id, "UNKNOWN"])), configurationMatching[patternId],
      { OAUTH_FLOW: patternId === "M2M_CLIENT_CREDENTIALS" ? "AUTHORIZATION_CODE" : "CLIENT_CREDENTIALS" }] as ArchitectureConfigurationInput["settings"][]) {
      const input = { expectedVersion: 2, patternId, settings }, raw = configurationFixture(input, context);
      const result = architectureConfigurationFromCore(raw, { ...configurationBinding, input, context }); combinations++;
      assert.deepEqual(Object.keys(result).sort(), ["analysis", "assessmentVersion"]);
      assert.deepEqual(result.analysis, raw.analysis);
      assert.equal(result.analysis.configurationObserved, false); assert.equal(result.analysis.recommendationReady, false);
      if (patternId === "SPA_CODE_PKCE" && context.clients.includes("BROWSER") && requirement === "REQUIRED")
        assert.equal(raw.preflight.patterns[2].status, "NEEDS_INFORMATION");
    }
  }
  assert.equal(combinations, 800);
});
test("Every scoped setting value is independently compatible, incompatible or unknown", () => {
  for (const patternId of configurationPatterns) for (const id of architectureConfigurationPatterns[patternId].ids) for (const value of architectureSettingValues[id]) {
    const input = { expectedVersion: 2, patternId, settings: { ...configurationMatching[patternId], [id]: value } };
    const context: ArchitectureConfigurationContext = { clients: [architectureConfigurationPatterns[patternId].client], browserTokenExposureMinimization: "REQUIRED" };
    assert.deepEqual(architectureConfigurationFromCore(configurationFixture(input, context), { ...configurationBinding, input, context }).analysis,
      configurationFixture(input, context).analysis);
  }
  const native = { ...configurationInput, patternId: "NATIVE_CODE_PKCE" as const, settings: { ...configurationMatching.NATIVE_CODE_PKCE, REDIRECT_MATCHING: "NATIVE_LOOPBACK_IP_LITERAL_PORT_EXCEPTION" as const } };
  assert.equal(architectureConfigurationFromCore(configurationFixture(native, { ...configurationContext, clients: ["NATIVE_MOBILE"] }),
    { ...configurationBinding, input: native, context: { ...configurationContext, clients: ["NATIVE_MOBILE"] } }).analysis.status, "CONDITIONALLY_MATCHES");
});
test("Sparse typed forms reject foreign values, duplicate fields, unsafe versions and caller authority", () => {
  for (const patternId of configurationPatterns) {
    const base = new URLSearchParams({ expectedVersion: "2", patternId });
    assert.deepEqual(parseArchitectureConfigurationForm(base), { expectedVersion: 2, patternId, settings: {} });
    for (const [id, values] of Object.entries(architectureSettingValues)) for (const value of [...new Set(Object.values(architectureSettingValues).flat())]) {
      const params = new URLSearchParams(base); params.append(id, value);
      if ((architectureConfigurationPatterns[patternId].ids as readonly string[]).includes(id) && (values as readonly string[]).includes(value)) validateArchitectureConfigurationInput(parseArchitectureConfigurationForm(params));
      else assert.throws(() => parseArchitectureConfigurationForm(params));
    }
    for (const suffix of ["&expectedVersion=2", "&patternId=BFF_SESSION", "&OAUTH_FLOW=UNKNOWN&OAUTH_FLOW=AUTHORIZATION_CODE", "&clientScope=SELECTED", "&configurationObserved=true", "&clientSecret=private", "&issuer=private", "&__proto__=private", "&constructor=private"]) assert.throws(() => parseArchitectureConfigurationForm(new URLSearchParams(base.toString() + suffix)));
    for (const version of ["02", "-1", "0.5", "9007199254740992", "", "true"]) { const params = new URLSearchParams(base); params.set("expectedVersion", version); assert.throws(() => parseArchitectureConfigurationForm(params)); }
  }
  for (const input of [{ ...configurationInput, expectedVersion: "2" }, { ...configurationInput, expectedVersion: true }, { ...configurationInput, settings: [] },
    { ...configurationInput, settings: { OAUTH_FLOW: 1 } }, { ...configurationInput, clientScope: "SELECTED" }, { ...configurationInput, patternId: "toString" }]) assert.throws(() => validateArchitectureConfigurationInput(input as never));
});
test("Consumer refuses shape-valid binding, inventory, metadata, preflight and readiness substitutions", () => {
  const mutations: ((raw: ReturnType<typeof configurationFixture>) => void)[] = [
    raw => { raw.preflight.workspaceId = configurationBinding.assessmentId; }, raw => { raw.preflight.assessmentId = configurationBinding.workspaceId; },
    raw => { raw.preflight.assessmentVersion++; }, raw => { raw.preflight.evaluatedAt = "2026-02-30T12:00:00Z"; }, raw => { raw.preflight.evaluatedAt = "private"; },
    raw => { raw.preflight.selectedClients = []; }, raw => { raw.preflight.browserTokenExposureRequirement = "PREFERRED"; },
    raw => { raw.preflight.patterns.reverse(); }, raw => { raw.preflight.patterns[2].checks[1].outcome = "PASS"; raw.preflight.patterns[2].status = "MATCHES_CHECKED_REQUIREMENTS"; },
    raw => { raw.preflight.patterns[0].checks.reverse(); }, raw => { raw.preflight.checkedPaths.pop(); }, raw => { raw.preflight.deferredPaths.pop(); },
    raw => { raw.analysis.clientScope = "UNKNOWN"; }, raw => { raw.analysis.settings.OAUTH_FLOW = "AUTHORIZATION_CODE"; }, raw => { raw.analysis.status = "CONDITIONALLY_MATCHES"; },
    raw => { raw.analysis.checks.pop(); }, raw => { raw.analysis.checks.reverse(); }, raw => { raw.analysis.checks[1] = raw.analysis.checks[0]; },
    raw => { raw.analysis.checks[0].outcome = "CONDITIONALLY_SATISFIED"; raw.analysis.checks[0].reasonCode = "EXPECTED_SETTING_DECLARED"; },
    raw => { raw.settingDefinitions[0].description = "Private upstream guidance"; }, raw => { raw.settingDefinitions[0].references = ["https://evil.example.invalid"]; },
    raw => { raw.settingDefinitions.pop(); }, raw => { raw.settingDefinitions.reverse(); }, raw => { raw.deferredBoundaries.pop(); },
    ...["configurationObserved", "configurationVerified", "providerCompatibilityVerified", "runtimeFlowVerified", "recommendationReady", "publicationReady", "writesPerformed"].map(flag =>
      (raw: ReturnType<typeof configurationFixture>) => { (raw.analysis as unknown as Record<string, unknown>)[flag] = true; }),
    raw => { (raw as unknown as Record<string, unknown>).private = "upstream"; },
  ];
  for (const mutate of mutations) { const raw = configurationFixture(); mutate(raw); assert.throws(() => architectureConfigurationFromCore(raw, configurationBinding)); }
  assert.throws(() => architectureConfigurationAnalysis(configurationFixture().analysis, configurationInput, "OTHER" as never));
  const later = configurationFixture(); later.preflight.evaluatedAt = "2026-10-05T12:00:00.123456789Z";
  assert.equal(architectureConfigurationFromCore(later, configurationBinding).analysis.status, "NEEDS_INFORMATION"); // Core owns evaluation time, not an earlier GET.
});
test("Core client reads canonical v6 first, keeps server-only identity and refuses invalid or oversized replies", async () => {
  const previousFetch = globalThis.fetch, previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-configuration-token-000000000000000";
  const session = { workspaceId: configurationBinding.workspaceId, issuer: "http://localhost:8081", subject: "synthetic-owner", email: null, displayName: null, authenticatedAt: new Date() };
  let calls = 0, version = 2, status = 200, override: Response | undefined;
  globalThis.fetch = async (url, init) => {
    calls++; assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error"); assert.ok(init?.signal instanceof AbortSignal);
    const headers = init?.headers as Record<string, string>;
    assert.equal(headers.Authorization, `Bearer ${process.env.AUTHWEAVE_CORE_SERVICE_TOKEN}`); assert.equal(headers["X-AuthWeave-Oidc-Subject"], session.subject); assert.equal(headers["X-AuthWeave-Oidc-Issuer"], session.issuer);
    if (init?.method === "GET") {
      assert.equal(url, `http://127.0.0.1:8080/api/v6/workspaces/${session.workspaceId}/assessments/${configurationBinding.assessmentId}`);
      return Response.json({ id: configurationBinding.assessmentId, workspaceId: session.workspaceId, status: "DRAFT", version, profileSchemaVersion: 6, profile: prerequisiteProfile });
    }
    assert.equal(init?.method, "POST"); assert.equal(url, `http://127.0.0.1:8080/api/v1/workspaces/${session.workspaceId}/assessments/${configurationBinding.assessmentId}/architecture-configuration-preview`);
    assert.deepEqual(JSON.parse(String(init?.body)), configurationInput);
    return override ?? (status === 200 ? Response.json(configurationFixture()) : new Response("Private upstream details", { status }));
  };
  try {
    assert.equal((await previewPersonalArchitectureConfiguration(session, configurationBinding.assessmentId, configurationInput)).kind, "preview"); assert.equal(calls, 2);
    for (const [code, kind] of [[400, "invalid"], [404, "not-found"], [409, "conflict"]] as const) { status = code; assert.equal((await previewPersonalArchitectureConfiguration(session, configurationBinding.assessmentId, configurationInput)).kind, kind); }
    status = 503; await assert.rejects(previewPersonalArchitectureConfiguration(session, configurationBinding.assessmentId, configurationInput));
    for (const response of [new Response("private".repeat(5000)), new Response("{}", { headers: { "content-length": "40000" } }), new Response("private invalid json")]) { override = response; await assert.rejects(previewPersonalArchitectureConfiguration(session, configurationBinding.assessmentId, configurationInput)); }
    version = 3; const count = calls; assert.equal((await previewPersonalArchitectureConfiguration(session, configurationBinding.assessmentId, configurationInput)).kind, "conflict"); assert.equal(calls, count + 1);
    await assert.rejects(previewPersonalArchitectureConfiguration(session, configurationBinding.assessmentId, { ...configurationInput, settings: { PKCE_METHOD: "IMPLICIT" } } as never)); assert.equal(calls, count + 1);
  } finally { globalThis.fetch = previousFetch; if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken; }
});

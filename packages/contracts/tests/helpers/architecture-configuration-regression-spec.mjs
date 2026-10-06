import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { patterns, expectedAnalysis, expectedDefinitions } from "./architecture-configuration-spec.mjs";

// Independent wire expectation at the HTTP test's fixed clock, never imported from Core output.
export function configurationRegressionFixture() {
  const sources = JSON.parse(readFileSync(new URL("../../../../services/core-api/src/main/resources/catalog/scoped-impact-scenarios.v1.json", import.meta.url), "utf8"));
  assert.equal(sources.length, 4);
  const outcomes = { conditionallySatisfied: 0, conditionallyNotSatisfied: 0, unknown: 0, notApplicable: 0 };
  const results = { conditionallyMatches: 0, conditionallyDoesNotMatch: 0, needsInformation: 0, notApplicable: 0 };
  const reasonNames = ["EXPECTED_SETTING_DECLARED", "INCOMPATIBLE_SETTING_DECLARED", "SETTING_UNKNOWN", "CLIENT_SCOPE_UNKNOWN", "PATTERN_NOT_APPLICABLE"];
  const reasons = reasonNames.map(reasonCode => ({ reasonCode, checks: 0 }));
  let selectedCases = 0, unknownClientCases = 0, unselectedClientCases = 0, savedInputNeedsInformation = 0, checkedCases = 0, checkedSettings = 0;
  for (const source of sources) for (const context of ["BASE_PROFILE", "UNKNOWN_CLIENTS", "EXCLUDED_PATTERN_CLIENT"]) for (const [patternId, pattern] of Object.entries(patterns)) {
    const clients = context === "BASE_PROFILE" ? source.profile.application.clients : context === "UNKNOWN_CLIENTS" ? [] : source.profile.application.clients.filter(c => c !== pattern.client);
    assert.ok(context !== "EXCLUDED_PATTERN_CLIENT" || clients.length > 0);
    const scope = clients.length === 0 ? "UNKNOWN" : clients.includes(pattern.client) ? "SELECTED" : "NOT_SELECTED";
    for (const design of ["EMPTY", "EXPLICIT_UNKNOWN", "REFERENCE_DESIGN", "MISMATCH_WITH_GAP", ...(patternId === "NATIVE_CODE_PKCE" ? ["NATIVE_LOOPBACK"] : [])]) {
      const settings = design === "EMPTY" ? {} : Object.fromEntries(pattern.ids.map((id, index) => [id, design === "EXPLICIT_UNKNOWN" ? "UNKNOWN" : [pattern.values[index]].flat()[0]]));
      if (design === "MISMATCH_WITH_GAP") { settings.OAUTH_FLOW = "UNKNOWN"; settings.CLIENT_AUTHENTICATION = "DISTRIBUTED_SHARED_SECRET"; }
      if (design === "NATIVE_LOOPBACK") settings.REDIRECT_MATCHING = "NATIVE_LOOPBACK_IP_LITERAL_PORT_EXCEPTION";
      const a = expectedAnalysis(patternId, scope, settings); checkedCases++; checkedSettings += a.checks.length;
      if (scope === "SELECTED") selectedCases++; else if (scope === "UNKNOWN") unknownClientCases++; else unselectedClientCases++;
      const token = source.profile.security.browserTokenExposureMinimization;
      if (scope === "UNKNOWN" || scope === "SELECTED" && pattern.client === "BROWSER" && (["UNKNOWN", "FORBIDDEN"].includes(token) || token === "REQUIRED" && patternId === "SPA_CODE_PKCE")) savedInputNeedsInformation++;
      results[{ CONDITIONALLY_MATCHES: "conditionallyMatches", CONDITIONALLY_DOES_NOT_MATCH: "conditionallyDoesNotMatch", NEEDS_INFORMATION: "needsInformation", NOT_APPLICABLE: "notApplicable" }[a.status]]++;
      for (const c of a.checks) {
        outcomes[{ CONDITIONALLY_SATISFIED: "conditionallySatisfied", CONDITIONALLY_NOT_SATISFIED: "conditionallyNotSatisfied", UNKNOWN: "unknown", NOT_APPLICABLE: "notApplicable" }[c.outcome]]++;
        reasons.find(r => r.reasonCode === c.reasonCode).checks++;
      }
    }
  }
  return { evaluatedAt: "2026-09-12T12:00:00Z", scenarioSetSha256: "404fa56ca63260e017beb842cfd53e3ad9328920c06bfa02dfaec065793465ec",
    analysisSha256: "5f833ddf0efd9b1a7148ddd103b4d6f9a0cd6180b5acd2234c3d41d1a664923b", outcomes, results, reasons, selectedCases, unknownClientCases, unselectedClientCases, savedInputNeedsInformation,
    scope: "SYNTHETIC_PROPOSED_ARCHITECTURE_CONFIGURATION_REGRESSION", policyVersion: "catalog-architecture-configuration-regression-1", configurationPolicyVersion: "architecture-configuration-design-1", preflightPolicyVersion: "architecture-pattern-preflight-1",
    definitionsSha256: "c3ada467dbf3d915d20ceb5a2bddb60c3edabc3ac6ac5a70857562e26640a9f9", scenarioSetVersion: "catalog-architecture-configuration-scenarios-1", baseScenarioSetVersion: "catalog-scoped-profile-scenarios-1",
    baseScenarioSetSha256: "ce70ce85cbb2e5b1537f36e2120a5c4add5ef0ce5d64be46397436bbc1993ca1", profileSchemaVersion: 5, profileSchemaSha256: "c995122fdd206e90bdf8145ee76e0e85f657933bb4b91dfae39a6ef715e30561",
    analysisBasis: "UNVERIFIED_SYNTHETIC_PROPOSED_CONFIGURATION", declaredProfiles: 4, declaredPatterns: 5, checkedCases, checkedSettings,
    exercisedSettings: [...new Set(Object.keys(patterns).flatMap(id => expectedDefinitions(id).map(d => d.settingId)))], checkedPaths: ["application.clients", "security.browserTokenExposureMinimization"],
    deferredBoundaries: ["Observed client registration and actual issuer/redirect values", "Runtime protocol validation, token storage and session defenses", "API authorization, scopes, audience and grant permissions", "Provider interoperability, browser CORS and native redirect ownership", "Provisioning, offboarding, assurance, compliance and operations", "Independent assessment of additional resource-access paths"],
    candidateChangesEvaluated: false, coverageComplete: false, configurationObserved: false, configurationVerified: false, providerCompatibilityVerified: false, runtimeFlowVerified: false, sourceVerificationPerformed: false,
    storedReportVerified: false, baselineVerified: false, approvalGranted: false, publicationReady: false, evaluationReady: false, recommendationReady: false, writesPerformed: false };
}
export function validateConfigurationRegression(payload) {
  assert.deepEqual(payload, configurationRegressionFixture(), "Actual fixed-clock Core summary must match independent inputs, counts, policy and pinned digests.");
}

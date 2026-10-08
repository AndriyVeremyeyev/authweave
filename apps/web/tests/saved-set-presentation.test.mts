import assert from "node:assert/strict";
import { test } from "node:test";

import { applicationTypes, clientTypes, populations, complianceTargets, dataCategories, evaluationContextValues } from "../src/lib/assessment/evaluation-context.ts";
import { auditCriteria } from "../src/lib/assessment/auditability.ts";
import { savedRequirementGroups } from "../src/lib/assessment/saved-requirements.ts";
import { savedRequirementsMarkdown } from "../src/lib/assessment/requirements-brief.ts";
import { listPersonalAssessments } from "../src/lib/auth/core-client.ts";
import { savedRequirementsFixture } from "./fixtures/assessment-ui.mts";

function permutations(values: readonly string[]): string[][] {
  return values.length === 0 ? [[]] : values.flatMap((value, index) =>
    permutations(values.filter((_, other) => index !== other)).map(tail => [value, ...tail]));
}

test("all six domain sets have stable saved Review/brief presentation without sorting ordered assumptions or mutating source", () => {
  const profile = savedRequirementsFixture();
  profile.application.clients = [...clientTypes]; profile.audience.populations = [...populations];
  profile.security.complianceTargets = [...complianceTargets];
  profile.security.dataResidencyDetails.allowedCountries = ["CA", "FR", "US"];
  profile.security.dataResidencyDetails.dataCategories = [...dataCategories];
  profile.security.auditabilityRequirements.selectedCriteria = auditCriteria.map(item => item.key);
  profile.operations.usagePlanning.assumptions = ["First declared assumption", "Second declared assumption"];
  const input = { id: "80000000-0000-4000-8000-000000000001", version: 7, status: "DRAFT" as const, profile };
  const groups = savedRequirementGroups(profile), brief = savedRequirementsMarkdown(input);
  const sets = [{ get: (p: typeof profile) => p.application.clients, set: (p: typeof profile, v: string[]) => { p.application.clients = v; } },
    { get: (p: typeof profile) => p.audience.populations, set: (p: typeof profile, v: string[]) => { p.audience.populations = v; } },
    { get: (p: typeof profile) => p.security.complianceTargets, set: (p: typeof profile, v: string[]) => { p.security.complianceTargets = v; } },
    { get: (p: typeof profile) => p.security.dataResidencyDetails.allowedCountries, set: (p: typeof profile, v: string[]) => { p.security.dataResidencyDetails.allowedCountries = v; } },
    { get: (p: typeof profile) => p.security.dataResidencyDetails.dataCategories, set: (p: typeof profile, v: string[]) => { p.security.dataResidencyDetails.dataCategories = v; } },
    { get: (p: typeof profile) => p.security.auditabilityRequirements.selectedCriteria, set: (p: typeof profile, v: string[]) => { p.security.auditabilityRequirements.selectedCriteria = v; } }];
  let checked = 0;
  for (const field of sets) for (const order of permutations(field.get(profile))) {
    const candidate = structuredClone(profile); field.set(candidate, order);
    const before = structuredClone(candidate);
    assert.deepEqual(savedRequirementGroups(candidate), groups);
    assert.equal(savedRequirementsMarkdown({ ...input, profile: candidate }), brief);
    assert.deepEqual(candidate, before); checked++;
  }
  assert.equal(checked, 2196);
  const projection = evaluationContextValues(profile)!;
  projection.clients.reverse(); projection.selectedPopulations.reverse();
  assert.deepEqual(profile.application.clients, [...clientTypes]); assert.deepEqual(profile.audience.populations, [...populations]);
  const ordered = structuredClone(profile); ordered.operations.usagePlanning.assumptions.reverse();
  assert.notEqual(savedRequirementsMarkdown({ ...input, profile: ordered }), brief);
});

test("assessment index shows the same stable client/population order as the saved Context projection", async () => {
  const originalFetch = globalThis.fetch, previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-stable-list-order-service-token-000000000000";
  const session = { workspaceId: "70000000-0000-4000-8000-000000000001", issuer: "http://localhost:8081",
    subject: "synthetic-stable-list-owner", email: null, displayName: null, authenticatedAt: new Date() };
  let clients: readonly string[] = clientTypes, users: readonly string[] = populations;
  globalThis.fetch = async () => Response.json({ items: [{ id: "80000000-0000-4000-8000-000000000001", version: 7,
    status: "DRAFT", createdAt: "2026-10-07T12:00:00Z", updatedAt: "2026-10-07T12:00:00Z",
    context: { applicationType: applicationTypes[1], clients, userPopulations: users } }], nextBeforeId: null });
  try {
    for (const order of permutations(clientTypes)) {
      clients = order; assert.deepEqual((await listPersonalAssessments(session)).items[0].context?.clients, [...clientTypes]);
    }
    for (const order of permutations(populations)) {
      users = order; assert.deepEqual((await listPersonalAssessments(session)).items[0].context?.userPopulations, [...populations]);
    }
  } finally {
    globalThis.fetch = originalFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});

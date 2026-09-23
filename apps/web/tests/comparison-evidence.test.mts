import assert from "node:assert/strict";
import { test } from "node:test";

import { hasStaleSyntheticEvidence } from "../src/lib/assessment/comparison-evidence.ts";

test("only a stale checked fact triggers the synthetic evidence warning", () => {
  assert.equal(hasStaleSyntheticEvidence([]), false);
  assert.equal(hasStaleSyntheticEvidence([{
    informationGaps: [{ reasonCode: "EVIDENCE_MISSING" }, { reasonCode: "COMPLIANCE_SCOPE_UNKNOWN" }],
    capabilityPreferences: [{ reasonCode: "PREFERRED_CAPABILITY_AVAILABLE" }],
  }]), false);
  assert.equal(hasStaleSyntheticEvidence([{
    informationGaps: [{ reasonCode: "EVIDENCE_STALE" }], capabilityPreferences: [],
  }]), true);
  assert.equal(hasStaleSyntheticEvidence([{
    informationGaps: [], capabilityPreferences: [{ reasonCode: "EVIDENCE_STALE" }],
  }]), true);
});

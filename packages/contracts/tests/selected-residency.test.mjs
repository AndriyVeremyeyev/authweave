import assert from "node:assert/strict";
import test from "node:test";
import { inspectScopedBaselineDraft } from "../scripts/inspect-provider-baselines.mjs";
import { prepareDecisionCandidate, readSelectedDrafts } from "../scripts/prepare-decision-candidate.mjs";

const drafts = await readSelectedDrafts();
const paths = ["AUDIT_LOGS", "BACKUPS", "CREDENTIALS", "USER_PROFILES"];
const observed = "2026-10-10T15:31:08Z";
const selected = drafts.filter(({ draft }) => ["workos", "entra-external-id"].includes(draft.options[0].providerId)
  && !draft.options[0].plan.includes("M2M Premium"));

for (const { file, draft } of selected) {
  test(`${file}: all four residency categories remain explicit, dated UNKNOWN proposals`, () => {
    const option = draft.options[0];
    assert.deepEqual(Object.keys(option.residency).sort(), paths);
    const report = inspectScopedBaselineDraft(draft, new Date(observed));
    const records = report.options[0].facts.filter(fact => fact.path.startsWith("residency."));
    assert.equal(records.length, 4);
    for (const fact of records) {
      assert.equal(fact.coverage, "UNKNOWN");
      assert.deepEqual(fact.storageCountries, []);
      assert.equal(fact.evidenceStatus, "UNREVIEWED");
      assert.equal(fact.evidence.observedAt, observed);
      assert.equal(fact.freshness, "CURRENT");
      assert(fact.conditions.length > 0);
      assert.equal(new URL(fact.evidence.sourceUrl).hostname, option.providerId === "workos" ? "workos.com" : "learn.microsoft.com");
    }
    for (const flag of ["approvalGranted", "sourceVerificationPerformed", "writesPerformed", "evaluationReady"]) {
      assert.equal(report[flag], false);
    }
    const earlierFacts = report.options[0].facts.filter(fact => !fact.path.startsWith("residency."));
    assert(earlierFacts.every(fact => Date.parse(fact.evidence.observedAt) < Date.parse(observed)));
  });

  test(`${file}: inspection cannot turn regional hints or documentation into complete country coverage`, () => {
    assert.equal(Object.keys(draft.options[0].residency).length, 4);
    for (const mutate of [
      value => { value.options[0].residency.USER_PROFILES.coverage = "COMPLETE"; },
      value => { value.options[0].residency.CREDENTIALS.storageCountries = ["US"]; },
      value => { value.options[0].residency.BACKUPS.evidence.sourceUrl = "https://example.invalid/backup"; },
      value => { delete value.options[0].residency.AUDIT_LOGS; },
    ]) {
      const changed = structuredClone(draft); mutate(changed);
      assert.throws(() => inspectScopedBaselineDraft(changed, new Date(observed)));
    }
  });
}

test("residency additions keep eight options, all claims pending and separate product/add-on boundaries", async () => {
  const assembly = await prepareDecisionCandidate();
  assert.equal(assembly.candidate.options.length, 8);
  assert.equal(assembly.reviewTasks.length, 48);
  assert.equal(assembly.reviewTasks.filter(task => task.factPath.startsWith("residency.")).length, 16);
  assert(assembly.reviewTasks.every(task => task.verdict === null));
  const addon = assembly.candidate.options.find(option => option.plan.includes("M2M Premium"));
  assert.deepEqual(addon.residency, {});
  const workos = assembly.candidate.options.filter(option => option.providerId === "workos");
  assert.deepEqual(workos.map(option => option.product), ["WorkOS AuthKit", "WorkOS AuthKit Connect", "WorkOS Directory Sync"]);
  assert.equal(workos[0].facts.SCIM, undefined);
  assert.equal(workos[1].facts.SCIM, undefined);
  assert.equal(workos[2].facts.OIDC, undefined);
  assert.equal(assembly.focusedCase.profile.provisioning.scim, "REQUIRED");
  assert.equal(assembly.approvalGranted, false);
  assert.equal(assembly.publicationReady, false);
});

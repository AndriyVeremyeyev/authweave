import assert from "node:assert/strict";
import { test } from "node:test";
import { validateProfileSaveAcknowledgements } from "./helpers/profile-save-acknowledgement-spec.mjs";

function pair(name = "v6-saved") {
  return [{ name: `${name}-request`, valid: true, schema: "update-assessment-profile-request.v6", payload: {
    expectedVersion: 7, profile: { application: { clients: ["BROWSER", "MACHINE_TO_MACHINE"] }, operations: { usagePlanning: { assumptions: ["First", "Second"] } } },
  } }, { name, valid: true, schema: "assessment-response.v6", payload: {
    version: 8, profile: { operations: { usagePlanning: { assumptions: ["First", "Second"] } }, application: { clients: ["MACHINE_TO_MACHINE", "BROWSER"] } },
  } }];
}

test("actual-write sample replay accepts set serialization order and no-op versions, with explicit operations sample bindings", () => {
  assert.deepEqual(validateProfileSaveAcknowledgements(pair()), { count: 1, noOps: 0 });
  const noOp = pair("v6-recorded-no-op"); noOp[1].payload.version = 7;
  assert.deepEqual(validateProfileSaveAcknowledgements(noOp), { count: 1, noOps: 1 });
  const operations = pair("operations-saved-1"); operations[0].name = "operations-save-request-1";
  assert.deepEqual(validateProfileSaveAcknowledgements(operations), { count: 1, noOps: 0 });
  assert.deepEqual(validateProfileSaveAcknowledgements([{ valid: false, schema: "update-assessment-profile-request.v6" }]), { count: 0, noOps: 0 });
});

test("actual-write sample replay rejects missing or unbound replies, value loss, duplicates, ordered-list changes and oversized bodies", () => {
  for (const mutate of [rows => rows.pop(), rows => rows[1].name = "foreign-write",
    rows => rows[1].valid = false, rows => rows[1].schema = "assessment-response.v5",
    rows => rows[1].payload.version = 9, rows => rows[1].payload.version = 6,
    rows => rows[1].payload.profile.application.clients.pop(),
    rows => rows[1].payload.profile.application.clients = ["BROWSER", "BROWSER"],
    rows => rows[1].payload.profile.operations.usagePlanning.assumptions.reverse(),
    rows => rows[1].payload.privateReply = "😀".repeat(17_000)]) {
    const rows = pair(); mutate(rows); assert.throws(() => validateProfileSaveAcknowledgements(rows));
  }
});

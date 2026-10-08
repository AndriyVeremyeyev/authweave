import assert from "node:assert/strict";
import { savedProfileMatches, profileSaveAcknowledgementByteLimit } from "../../../../apps/web/src/lib/assessment/profile-save-acknowledgement.ts";

/** Replay adjacent actual v6 write request/response samples, not invented save receipts. */
export function validateProfileSaveAcknowledgements(samples) {
  let count = 0, noOps = 0;
  for (let index = 0; index < samples.length; index++) {
    const request = samples[index];
    if (!request.valid || request.schema !== "update-assessment-profile-request.v6") continue;
    const response = samples[index + 1];
    const responseName = /^operations-save-request-[0-9]+$/.test(request.name)
      ? request.name.replace("operations-save-request-", "operations-saved-") : request.name.replace(/-request$/, "");
    assert.ok(response?.valid && response.schema === "assessment-response.v6" && response.name === responseName,
      `${request.name}: the exact adjacent successful Core write response is required`);
    assert.ok(savedProfileMatches(request.payload.profile, response.payload.profile),
      `${request.name}: all actual Core saved values must pass the common BFF acknowledgement guard`);
    assert.ok(response.payload.version === request.payload.expectedVersion || response.payload.version === request.payload.expectedVersion + 1,
      `${request.name}: the write response cannot skip or lose versions`);
    assert.ok(Buffer.byteLength(JSON.stringify(response.payload), "utf8") <= profileSaveAcknowledgementByteLimit,
      `${request.name}: the actual Core write response must fit the bounded BFF reader`);
    if (response.payload.version === request.payload.expectedVersion) noOps++;
    count++;
  }
  return { count, noOps };
}

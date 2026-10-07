import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { publicationBinding, publicationFixture, publicationAt } from "../../../apps/web/tests/fixtures/publication-preflight.mts";
import { publicationReviewFromCore } from "../../../apps/web/src/lib/catalog/publication-preflight.ts";

const ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv);
const schema = JSON.parse(await readFile(new URL("../schemas/catalog-publication-preflight-review.v1.schema.json", import.meta.url), "utf8"));
const validate = ajv.compile(schema);

test("publication review contract is body-free, exact-mode, always blocked and preserves mandatory gates", () => {
  for (const bootstrap of [false, true]) {
    const raw = publicationFixture(bootstrap); assert.equal(validate(raw), true, ajv.errorsText(validate.errors));
    for (const [flag, value] of Object.entries(raw)) if (value === false) assert.equal(validate({ ...raw, [flag]: true }), false);
    for (const privateField of ["sourceUrl", "candidate", "actorSubject", "profile", "readiness"]) assert.equal(validate({ ...raw, [privateField]: "private" }), false);
    for (const mutation of [r => r.blockers.pop(), r => r.blockers.push(r.blockers[0]), r => r.blockers.push("APPROVED"),
      r => r.planning.regressions.reverse(), r => r.planning.regressions.pop(), r => r.planning.checkedDimensions++,
      r => r.planning.structuralVerificationGaps--, r => r.planning.planningVerificationGaps--,
      r => r.planning.regressions[0].checkedCases++, r => r.inputVersion = bootstrap ? 0 : null]) {
      const bad = structuredClone(raw); mutation(bad); assert.equal(validate(bad), false);
    }
    const absent = structuredClone(raw); absent.planning = null; assert.equal(validate(absent), false);
    absent.blockers.unshift(bootstrap ? "BOOTSTRAP_REVIEW_UNAVAILABLE" : "PROPOSAL_NOT_FOUND"); assert.equal(validate(absent), true);
    absent.planning = raw.planning; assert.equal(validate(absent), false);
  }
});
test("valid-shaped revision/hash/clock/count substitutions still fail the separate bounded BFF guard", () => {
  for (const mutate of [r => r.inputSha256 = "c".repeat(64), r => r.inputVersion++, r => r.evaluatedAt = "2026-10-07T11:00:00Z",
    r => r.planning.evaluatedAt = "2026-10-07T12:00:00.123456788Z", r => r.facts.supporting--, r => r.reviewThroughNumber = -1]) {
    const raw = publicationFixture(); mutate(raw);
    if (raw.reviewThroughNumber >= 0) assert.equal(validate(raw), true, ajv.errorsText(validate.errors));
    assert.throws(() => publicationReviewFromCore(raw, publicationBinding(), new Date(publicationAt)));
  }
});

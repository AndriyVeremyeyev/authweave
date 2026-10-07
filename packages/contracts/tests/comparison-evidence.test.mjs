import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { provenanceFixture, provenanceBinding, fixtureCatalogDigest } from "../../../apps/web/tests/fixtures/comparison-provenance.mts";
import { comparisonEvidenceFromCore } from "../../../apps/web/src/lib/assessment/comparison-provenance.ts";

const root = new URL("../schemas/", import.meta.url);
const ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv);
for (const file of await readdir(root)) if (file.endsWith(".schema.json"))
  ajv.addSchema(JSON.parse(await readFile(new URL(file, root), "utf8")));
const validate = ajv.getSchema("https://authweave.dev/contracts/comparison-evidence-preview.v1.schema.json");

test("comparison evidence contract retains partial fictional maps and rejects authority, disclosure and malformed envelopes", () => {
  const raw = provenanceFixture(); assert.equal(validate(raw), true, ajv.errorsText(validate.errors));
  assert.equal(comparisonEvidenceFromCore(raw, provenanceBinding).evidence[0].groups.flatMap(g => g.rows).length, 74);
  for (const flag of ["sourceVerificationPerformed", "publicationReady", "recommendationReady", "writesPerformed"])
    assert.equal(validate({ ...raw, [flag]: true }), false);
  for (const privateField of ["actorSubject", "sourceBody", "profile", "token"])
    assert.equal(validate({ ...raw, [privateField]: "private" }), false);
  for (const mutate of [r => r.schemaVersion = 2, r => r.scope = "PUBLISHED_COMPARISON", r => r.catalog.kind = "PUBLISHED",
    r => r.catalogSha256 = "invalid", r => delete r.catalog.options[0].compatibility,
    r => r.catalog.options[0].facts.SCIM.availability = "SUPPORTED",
    r => r.catalog.options[0].facts.SCIM.sourceUrl = "https://real-provider.example/docs"]) {
    const bad = structuredClone(raw); mutate(bad); assert.equal(validate(bad), false);
  }
});
test("shape-valid comparison/catalog substitutions still fail the independent personal BFF binding", () => {
  for (const mutate of [r => r.comparison.assessmentVersion++, r => r.catalog.catalogVersion = "synthetic-other-catalog",
    r => r.catalog.options[0].plan = "Foreign plan", r => r.catalog.options[0].facts.OIDC.sourceUrl = "https://catalog.invalid/substitution",
    r => r.catalogSha256 = "0".repeat(64)]) {
    const raw = provenanceFixture(); mutate(raw);
    if (raw.catalogSha256 !== "0".repeat(64)) raw.catalogSha256 = fixtureCatalogDigest(raw.catalog);
    assert.equal(validate(raw), true, ajv.errorsText(validate.errors));
    assert.throws(() => comparisonEvidenceFromCore(raw, provenanceBinding));
  }
});

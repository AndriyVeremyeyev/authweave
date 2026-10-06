import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { lifecycleRegressionFixture, lifecycleRegressionInputs, lifecycleRegressionHash, lifecycleRegressionFlags, validateLifecycleRegression } from "./helpers/lifecycle-regression-spec.mjs";
import { lifecycleV2Expectation, lifecycleV2Scope } from "./helpers/provisioning-lifecycle-v2-spec.mjs";

const schemas = new URL("../schemas/", import.meta.url), ajv = new Ajv2020({strict:true,allErrors:true}); addFormats(ajv);
for (const file of await readdir(schemas)) if (file.endsWith(".schema.json")) ajv.addSchema(JSON.parse(await readFile(new URL(file,schemas),"utf8")));
const validate = ajv.getSchema("https://authweave.dev/contracts/catalog-provisioning-lifecycle-regression-check.v1.schema.json");
const sourceRoot = new URL("../../../services/core-api/src/main/resources/catalog/", import.meta.url);
const sources = JSON.parse(await readFile(new URL("scoped-impact-scenarios.v1.json",sourceRoot),"utf8"));
const supplement = JSON.parse(await readFile(new URL("scoped-auditability-scenarios.v1.json",sourceRoot),"utf8"));
test("bounded lifecycle overlays preserve exact frozen v6 sources and allow only scoped declarations",()=>{
  const {definitions} = lifecycleRegressionInputs(); assert.equal(definitions.length,2016);
  assert.equal(new Set(definitions.map(d=>[d.scenarioId,d.requirementVariant,d.patternId,d.groupStrategy,d.declarationVariant].join("/"))).size,2016);
  const profileSchema = ajv.getSchema("https://authweave.dev/contracts/application-identity-profile.v6.schema.json");
  const request = ajv.getSchema("https://authweave.dev/contracts/provisioning-lifecycle-request.v2.schema.json");
  for (const d of definitions) {
    const source = sources.find(s=>s.id===d.scenarioId), input = supplement.scenarios.find(s=>s.scenarioId===d.scenarioId), profile = structuredClone(source.profile);
    profile.security.auditabilityRequirements = {selectedCriteria:input.selectedCriteria,minimumRetentionDays:input.minimumRetentionDays};
    assert.equal(lifecycleRegressionHash(profile),d.sourceProfileSha256); profile.provisioning = d.requirements;
    assert.equal(lifecycleRegressionHash(profile),d.profileSha256); assert.equal(profileSchema(profile),true,ajv.errorsText(profileSchema.errors));
    for (const key of Object.keys(source.profile).filter(key=>!["provisioning","security"].includes(key))) assert.deepEqual(profile[key],source.profile[key]);
    const {auditabilityRequirements,...security} = profile.security; assert.deepEqual(security,source.profile.security); assert.deepEqual(auditabilityRequirements.selectedCriteria,input.selectedCriteria);
    assert.equal(request({expectedVersion:0,patternId:d.patternId,groupStrategy:d.groupStrategy,declarations:d.declarations}),true,ajv.errorsText(request.errors));
    assert.ok(Object.keys(d.declarations).every(id=>lifecycleV2Scope(d.patternId,d.groupStrategy).includes(id)));
  }
});
test("hard failures keep independent offboarding and group gaps rather than implying observed revocation",()=>{
  const rows = lifecycleRegressionInputs().definitions.map(d=>({d,a:lifecycleV2Expectation(d.requirements,d.patternId,d.groupStrategy,d.declarations)}));
  for (const {d,a} of rows) {
    if (d.declarationVariant==="OFFBOARDING_FAILURE_WITH_GAP") { assert.equal(a.status,"CONDITIONALLY_DOES_NOT_MATCH"); assert.equal(a.conditionChecks.find(c=>c.conditionId==="TOKEN_REVOCATION_OR_BOUNDED_EXPIRY").outcome,"UNKNOWN"); }
    if (d.declarationVariant==="GROUP_REMOVAL_FAILURE_WITH_GAP") { assert.equal(a.status,"CONDITIONALLY_DOES_NOT_MATCH"); assert.equal(a.conditionChecks.find(c=>c.conditionId==="GROUP_TO_ROLE_MAPPING_AND_ENFORCEMENT").outcome,"UNKNOWN"); }
    if (d.patternId==="JIT_LOGIN"&&d.groupStrategy==="SCIM_GROUPS") assert.equal(a.designChecks[0].reasonCode,"SCIM_GROUPS_REQUIRE_SCIM_PATTERN");
    if (d.requirements.scim==="REQUIRED"&&d.patternId==="JIT_LOGIN") assert.equal(a.requirementChecks[0].reasonCode,"REQUIRED_MECHANISM_ABSENT");
  }
});
test("body-free lifecycle summary has exact golden bindings, counters and false authority",()=>{
  const f=lifecycleRegressionFixture(); assert.equal(validate(f),true,ajv.errorsText(validate.errors)); validateLifecycleRegression(f);
  assert.equal(f.scenarioSetSha256,"bb56c1fb90eca12be7e84b535b0fe70eb3c96386fb09020f04d8d0d4d140e608");
  assert.equal(f.definitionsSha256,"c81c99b5da95215ab04e06489dec50afa7954bccd065ae68a9179951259b5e1c");
  assert.equal(f.analysisSha256,"f4e0b12d77a7021b46a74a1db09b3d718e748801318418f9c4410312643c5b2c");
  assert.deepEqual(f.results,{conditionallyMatches:78,conditionallyDoesNotMatch:1253,needsInformation:685}); assert.equal(f.checkedConditionChecks,22560);
  assert.deepEqual(f.offboardingOutcomes,{conditionallySatisfied:2304,conditionallyNotSatisfied:1152,unknown:2592,notApplied:0});
  assert.deepEqual(f.groupRemovalOutcomes,{conditionallySatisfied:432,conditionallyNotSatisfied:288,unknown:432,notApplied:0});
  for(const flag of lifecycleRegressionFlags) assert.equal(validate({...f,[flag]:true}),false,flag);
  for(const field of ["profile","requirements","declarations","rows","actor","workspaceId","sourceUrl","winner"]) assert.equal(validate({...f,[field]:"private"}),false,field);
  for(const field of ["requirementVariants","declarationVariants","patternIds","groupStrategies","conditionIds","checkedPaths","deferredBoundaries","reasons"]) {const invalid=structuredClone(f); invalid[field].pop();assert.equal(validate(invalid),false,field);}
});
test("shape-valid counter, digest and clock substitutions fail independent source replay",()=>{
  const f=lifecycleRegressionFixture();
  for(const mutate of [r=>{r.results.conditionallyMatches--;r.results.needsInformation++;},
    ...["requirementOutcomes","designOutcomes","conditionOutcomes","offboardingOutcomes","groupRemovalOutcomes"].map(key=>r=>{r[key].conditionallySatisfied--;r[key].unknown++;}),
    r=>r.checkedConditionChecks--,r=>{r.reasons[0].checks--;r.reasons[1].checks++;},
    ...["scenarioSetSha256","auditabilityScenarioSetSha256","analysisSha256","definitionsSha256"].map(key=>r=>r[key]="0".repeat(64)),r=>r.evaluatedAt="2026-09-12T12:00:01Z"]){
    const invalid=structuredClone(f); mutate(invalid); assert.equal(validate(invalid),true,ajv.errorsText(validate.errors));assert.throws(()=>validateLifecycleRegression(invalid));
  }
  const later=lifecycleRegressionFixture("2026-09-12T12:00:01Z");assert.equal(f.scenarioSetSha256,later.scenarioSetSha256);assert.equal(f.definitionsSha256,later.definitionsSha256);assert.notEqual(f.analysisSha256,later.analysisSha256);
});

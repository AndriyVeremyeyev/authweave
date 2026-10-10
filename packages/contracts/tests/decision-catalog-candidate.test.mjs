import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, readFile, readdir, rmdir } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import {
  assembleDecisionCandidate, assertFocusedCaseCoverage, bootstrapDigest, decisionDigest, prepareDecisionCandidate,
  readDecisionSelection, readSelectedDrafts,
} from "../scripts/prepare-decision-candidate.mjs";

const selection = await readDecisionSelection();
const drafts = await readSelectedDrafts();
const assembly = assembleDecisionCandidate(selection, drafts);
const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);
for (let version = 1; version <= 6; version++) ajv.addSchema(JSON.parse(await readFile(new URL(`../schemas/application-identity-profile.v${version}.schema.json`, import.meta.url))));
ajv.addSchema(JSON.parse(await readFile(new URL("../schemas/provider-catalog-draft.v1.schema.json", import.meta.url))));
const validate = ajv.compile(JSON.parse(await readFile(new URL("../schemas/decision-catalog-candidate.v1.schema.json", import.meta.url))));

test("pinned selection produces one usable draft payload, eight separate scopes and five families", async () => {
  assert.equal(validate(assembly), true, ajv.errorsText(validate.errors));
  assert.equal(assembly.candidate.options.length, 8);
  assert.deepEqual([...new Set(assembly.candidate.options.map(o => o.providerId))].sort(), ["auth0", "entra-external-id", "keycloak", "workos", "zitadel"]);
  assert.deepEqual(assembly.candidate.options.map(o => o.id), selection.selections.map(s => s.optionId));
  assert.equal(assembly.candidate.catalogVersion, selection.catalogVersion);
  assert.equal(assembly.bindings.selectionSha256, decisionDigest(selection));
  assert.equal(assembly.bindings.decisionCatalogSha256, decisionDigest(assembly.candidate));
  assert.equal(assembly.bindings.bootstrapCandidateSha256, bootstrapDigest(assembly.candidate));
  assert.equal(assembly.bindings.selectionSha256, "5cd50f1a63472d14f9cd55f7ed1599dee4ab01d208385cd3036981c39d219cda");
  assert.equal(assembly.bindings.decisionCatalogSha256, "870ba66ec81b3dfb45d2ea591e9ba31678e6da3be8281c5eaee0ab431eb1687c");
  assert.equal(assembly.bindings.bootstrapCandidateSha256, "a951b581550c0330c0404a49cce8f8fe4bfdf51181eda211c24bd69fab300159");
  assert.equal(assembly.bindings.focusedCaseSha256, decisionDigest(assembly.focusedCase));
  assert.notEqual(assembly.bindings.decisionCatalogSha256, assembly.bindings.bootstrapCandidateSha256);
  assert.deepEqual(await prepareDecisionCandidate(), assembly);
});

test("assembly preserves facts, conditions, observation dates and exact scopes without modifying inputs", () => {
  const before = JSON.stringify({ selection, drafts });
  const result = assembleDecisionCandidate(selection, drafts.toReversed());
  assert.deepEqual(result, assembly);
  for (const [index, chosen] of selection.selections.entries()) {
    const original = drafts.find(source => source.file === chosen.file).draft;
    assert.deepEqual(result.candidate.options[index], original.options[0]);
  }
  result.candidate.options[0].facts.SCIM.conditions.push("Local caller mutation");
  result.selection.selections[0].scope = "changed";
  assert.equal(JSON.stringify({ selection, drafts }), before);
});

test("every recorded claim has one pending exact-scope review task, including unknown assertions", () => {
  assert.equal(assembly.approvalGranted, false);
  assert.equal(assembly.sourceVerificationPerformed, false);
  assert.equal(assembly.publicationReady, false);
  const seen = new Set();
  let unknown = 0;
  for (const task of assembly.reviewTasks) {
    const key = `${task.optionId}/${task.factPath}`;
    assert.ok(!seen.has(key)); seen.add(key);
    assert.equal(task.verdict, null);
    const option = assembly.candidate.options.find(o => o.id === task.optionId);
    const fact = task.factPath.split(".").reduce((value, name) => value[name], option);
    assert.equal(task.claimSha256, decisionDigest({ optionId: option.id, optionSha256: decisionDigest(option), factPath: task.factPath, fact }));
    if (Object.values(fact).includes("UNKNOWN")) unknown++;
  }
  assert.ok(unknown > 0);
  assert.match(assembly.limitations.join(" "), /do not yet establish a positive end-to-end acceptance case/);
});

test("other drafts never donate residency, organization, client or machine facts", () => {
  const option = id => assembly.candidate.options.find(o => o.id === id);
  assert.deepEqual(Object.keys(option("keycloak-26.8.0-native-self-hosted").compatibility.clients), ["BROWSER"]);
  assert.equal(option("keycloak-26.8.0-native-self-hosted").compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS, undefined);
  assert.deepEqual(option("auth0-b2b-free-oidc-scim").residency, {});
  assert.deepEqual(option("zitadel-cloud-free-native").compatibility.applications, {});
  assert.equal(Object.keys(option("entra-external-id-basic-standard-native").residency).length, 4);
  assert.equal(option("entra-external-id-basic-standard-native").facts.OAUTH2_APIS, undefined);
  assert.equal(option("entra-external-id-m2m-addon-machine-clients").facts.SCIM, undefined);
  const workos = assembly.candidate.options.filter(o => o.providerId === "workos");
  assert.deepEqual(workos.map(o => o.product), ["WorkOS AuthKit", "WorkOS AuthKit Connect", "WorkOS Directory Sync"]);
  assert.equal(workos[0].facts.SCIM, undefined);
  assert.equal(workos[1].facts.SCIM, undefined);
  assert.equal(workos[2].facts.OIDC, undefined);
});

test("review claim binding includes whole option scope, not merely a copied fact", () => {
  const task = assembly.reviewTasks.find(t => t.factPath === "facts.SCIM");
  const option = structuredClone(assembly.candidate.options.find(o => o.id === task.optionId));
  const fact = option.facts.SCIM;
  option.configuration = "Different application-side provisioning bridge";
  assert.notEqual(task.claimSha256, decisionDigest({ optionId: option.id, optionSha256: decisionDigest(option), factPath: task.factPath, fact }));
});

test("the additional case retains hard SCIM, seven exact required claims and explicit preference weights", async () => {
  const c = assembly.focusedCase;
  assert.equal(c.profile.provisioning.scim, "REQUIRED");
  assert.deepEqual(c.profile.application.clients, ["BROWSER"]);
  assert.equal(c.profile.audience.tenancy, "SINGLE_ORGANIZATION");
  assert.equal(c.requiredFactPaths.length, 7);
  assert.deepEqual(c.preferredFactPaths, ["facts.SAML"]);
  assert.deepEqual(c.weights.values, [{ capability: "SAML", weight: 100 }]);
  assert.equal(c.pendingSourceReview, true);
  assert.equal(assembly.reviewTasks.length, 48);
  assert.equal(assembly.sourceVerificationPerformed, false);
  assertFocusedCaseCoverage(c, assembly.candidate);
  for (const factPath of [...c.requiredFactPaths, ...c.preferredFactPaths]) {
    assert(assembly.reviewTasks.some(task => task.optionId === c.targetOptionId && task.factPath === factPath && task.verdict === null));
  }
  const primary = JSON.parse(await readFile(new URL("../../../services/core-api/src/main/resources/catalog/scoped-impact-scenarios.v1.json", import.meta.url)));
  const original = primary.find(c => c.id === "b2b-saas-scoped").profile;
  assert.equal(original.audience.tenancy, "MULTI_TENANT_ORGANIZATIONS");
  assert.equal(original.provisioning.scim, "REQUIRED");
  assert.equal(original.security.dataResidency, "REQUIRED");
  assert.equal(original.application.clients.length, 3);
  assert.equal(JSON.parse(await readFile(new URL("../decision-core/cases.v1.json", import.meta.url))).cases.length, 18);
});

for (const [name, mutate] of [
  ["relaxed SCIM", c => { c.profile.provisioning.scim = "NOT_REQUIRED"; c.requiredFactPaths = c.requiredFactPaths.filter(p => p !== "facts.SCIM"); }],
  ["missing context dependency", c => { c.requiredFactPaths.shift(); }],
  ["multi-organization borrowing", c => { c.profile.audience.tenancy = "MULTI_TENANT_ORGANIZATIONS"; c.requiredFactPaths[c.requiredFactPaths.indexOf("compatibility.tenancy.SINGLE_ORGANIZATION")] = "compatibility.tenancy.MULTI_TENANT_ORGANIZATIONS"; }],
  ["required residency", c => { c.profile.security.dataResidency = "REQUIRED"; }],
  ["required auditability", c => { c.profile.security.auditability = "REQUIRED"; }],
  ["elevated assurance", c => { c.profile.security.assurance = "ELEVATED"; }],
  ["identified compliance", c => { c.profile.security.complianceScopeStatus = "IDENTIFIED"; }],
  ["hidden weight", c => { c.weights.values[0].weight = 99; }],
  ["invented review", c => { c.pendingSourceReview = false; }],
  ["forbidden available OIDC", c => { c.profile.protocols.federation.OIDC = "FORBIDDEN"; }],
]) test(`focused preparation rejects ${name}`, () => {
  const input = structuredClone(assembly.focusedCase); mutate(input);
  assert.throws(() => assertFocusedCaseCoverage(input, assembly.candidate));
});

test("focused preparation cannot borrow missing or unknown claims from another option", () => {
  for (const mutate of [
    candidate => { delete candidate.options[0].compatibility.clients.BROWSER; },
    candidate => { candidate.options[0].facts.SCIM.availability = "UNKNOWN"; },
    candidate => { candidate.options[0].compatibility.tenancy.SINGLE_ORGANIZATION.support = "UNKNOWN"; },
  ]) {
    const input = structuredClone(assembly.candidate); mutate(input);
    assert.throws(() => assertFocusedCaseCoverage(assembly.focusedCase, input));
  }
});

test("the optional focused-case extension keeps earlier v1 envelopes valid but requires a paired binding", () => {
  const legacy = structuredClone(assembly);
  delete legacy.focusedCase; delete legacy.bindings.focusedCaseSha256;
  assert.equal(validate(legacy), true, ajv.errorsText(validate.errors));
  const noBinding = structuredClone(assembly); delete noBinding.bindings.focusedCaseSha256;
  assert.equal(validate(noBinding), false);
  const noCase = structuredClone(assembly); delete noCase.focusedCase;
  assert.equal(validate(noCase), false);
});

test("dual digest policies keep object ordering stable but do not reinterpret array ordering", () => {
  const first = { b: 2, a: ["First", "Second"] };
  const reordered = { a: ["Second", "First"], b: 2 };
  assert.equal(decisionDigest(first), decisionDigest({ a: ["First", "Second"], b: 2 }));
  assert.notEqual(decisionDigest(first), decisionDigest(reordered));
  assert.equal(bootstrapDigest(first), bootstrapDigest(reordered));
  const input = structuredClone(assembly.candidate);
  input.options.reverse();
  assert.notEqual(decisionDigest(input), assembly.bindings.decisionCatalogSha256);
  assert.equal(bootstrapDigest(input), assembly.bindings.bootstrapCandidateSha256);
});

const invalidSelections = [
  ["missing scope", m => m.selections.pop()],
  ["extra scope", m => m.selections.push(structuredClone(m.selections[0]))],
  ["duplicate scope", m => { m.selections[1] = structuredClone(m.selections[0]); }],
  ["changed source digest", m => { m.selections[0].sourceSha256 = "0".repeat(64); }],
  ["changed option identity", m => { m.selections[0].optionId = "another-option"; }],
  ["changed source version", m => { m.selections[0].catalogVersion = "another-source-version"; }],
  ["changed origin file", m => { m.selections[0].file = "another-source.v1.json"; }],
  ["wrong provider scope", m => { m.selections[0].providerId = "auth0"; }],
  ["WorkOS cross-product scope", m => { [m.selections[3].scope, m.selections[4].scope] = [m.selections[4].scope, m.selections[3].scope]; }],
  ["Entra add-on scope borrowing", m => { [m.selections[6].scope, m.selections[7].scope] = [m.selections[7].scope, m.selections[6].scope]; }],
  ["path traversal", m => { m.selections[0].file = "../../infra/.env"; }],
  ["URL input", m => { m.selections[0].file = "https://example.invalid/file.v1.json"; }],
  ["caller approval", m => { m.approvalGranted = true; }],
  ["policy drift", m => { m.policyVersion = "new-policy"; }],
];
for (const [name, mutate] of invalidSelections) test(`selection rejects ${name}`, () => {
  const input = structuredClone(selection); mutate(input);
  assert.throws(() => assembleDecisionCandidate(input, drafts));
});

for (const [name, mutate] of [
  ["availability", d => { d.options[0].facts.SCIM.availability = "MANDATORY"; }],
  ["observation refresh", d => { d.options[0].facts.SCIM.evidence.observedAt = "2026-10-09T00:00:00Z"; }],
  ["source URL", d => { d.options[0].facts.SCIM.evidence.sourceUrl += "?approved=true"; }],
  ["conditions order", d => { d.options[0].facts.SCIM.conditions.reverse(); }],
  ["configuration", d => { d.options[0].configuration = "A combined context"; }],
]) test(`pinned assembly rejects silent ${name} change`, () => {
  const input = structuredClone(drafts); mutate(input[0].draft);
  assert.throws(() => assembleDecisionCandidate(selection, input), /Pinned source content changed/);
});

test("missing, duplicate and extra source inputs cannot widen the selected catalog", () => {
  assert.throws(() => assembleDecisionCandidate(selection, drafts.slice(1)), /Incomplete/);
  assert.throws(() => assembleDecisionCandidate(selection, [...drafts, drafts[0]]), /Incomplete/);
  const input = structuredClone(drafts); input[1] = structuredClone(input[0]);
  assert.throws(() => assembleDecisionCandidate(selection, input), /Duplicate/);
});

test("assembly schema cannot carry a verdict, authority flag or active runtime kind", () => {
  for (const mutate of [
    r => { r.approvalGranted = true; }, r => { r.sourceVerificationPerformed = true; }, r => { r.publicationReady = true; },
    r => { r.reviewTasks[0].verdict = "SOURCE_SUPPORTS_CLAIM"; }, r => { r.candidate.kind = "SYNTHETIC"; },
    r => { r.confirmation = "MANUAL_BOOTSTRAP_SOURCE_REVIEW"; },
  ]) {
    const input = structuredClone(assembly); mutate(input);
    assert.equal(validate(input), false);
  }
});

test("offline command emits only the deterministic assembly, rejects options and writes no file", async () => {
  const command = new URL("../scripts/prepare-decision-candidate.mjs", import.meta.url);
  const directory = await mkdtemp(path.join(tmpdir(), "authweave-candidate-command-"));
  try {
    const valid = spawnSync(process.execPath, [command.pathname], { cwd: directory, encoding: "utf8" });
    assert.equal(valid.status, 0, valid.stderr);
    assert.deepEqual(JSON.parse(valid.stdout), assembly);
    const invalid = spawnSync(process.execPath, [command.pathname, "--approve"], { cwd: directory, encoding: "utf8" });
    assert.equal(invalid.status, 1);
    assert.equal(invalid.stdout, "");
    assert.match(invalid.stderr, /accepts no paths, URLs, verdicts or options/);
    assert.deepEqual(await readdir(directory), []);
  } finally {
    await rmdir(directory);
  }
});

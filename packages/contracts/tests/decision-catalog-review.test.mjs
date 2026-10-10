import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, readFile, readdir, rmdir } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { assembleDecisionCandidate, decisionDigest, prepareDecisionCandidate, readDecisionSelection, readSelectedDrafts } from "../scripts/prepare-decision-candidate.mjs";
import { renderDecisionReview } from "../scripts/prepare-decision-review.mjs";

const assembly = await prepareDecisionCandidate();
const clock = new Date("2026-10-10T15:31:08Z");
const report = renderDecisionReview(assembly, clock);
const get = (value, address) => address.split(".").reduce((item, key) => item[key], value);

test("human summary contains all 48 pending exact-scope claims, sources and both distinct binding digests", () => {
  assert.match(report, /Selected options: 8; pending claims: 48; distinct source URLs: 30/);
  assert.equal(report.match(/^### /gm).length, 8);
  assert.equal(report.match(/^#### /gm).length, 48);
  assert.equal(report.match(/human verdict: PENDING/g).length, 48);
  for (const task of assembly.reviewTasks) {
    assert.equal(report.split(`Claim SHA-256: ${task.claimSha256}.`).length - 1, 1);
    const option = assembly.candidate.options.find(option => option.id === task.optionId);
    const evidence = get(option, task.factPath).evidence;
    assert(report.includes(`<${new URL(evidence.sourceUrl).href}>`));
    assert(report.includes(`Observed at: ${evidence.observedAt};`));
  }
  for (const key of ["bootstrapCandidateSha256", "decisionCatalogSha256", "selectionSha256", "focusedCaseSha256"]) {
    assert(report.includes(`${key}: ${assembly.bindings[key]}`));
  }
  for (const origin of assembly.selection.selections) assert(report.includes(origin.sourceSha256));
  assert.match(report, /they are not interchangeable/);
});

test("review priority preserves hard SCIM and explicit SAML weight without relaxing primary requirements", () => {
  assert.equal(report.match(/^- REQUIRED_FOR_FOCUSED_CASE:/gm).length, 7);
  assert.equal(report.match(/^- PREFERENCE_FOR_FOCUSED_CASE:/gm).length, 1);
  assert.equal(report.match(/Focused-case role: REQUIRED_FOR_FOCUSED_CASE/g).length, 7);
  assert.equal(report.match(/Focused-case role: PREFERENCE_FOR_FOCUSED_CASE/g).length, 1);
  assert.match(report, /REQUIRED_FOR_FOCUSED_CASE: facts.SCIM; verdict PENDING/);
  assert.match(report, /Explicit preference weight: SAML=100/);
  assert.match(report, /not a replacement for the primary multi-tenant B2B profile or the 18 frozen synthetic acceptance cases/);
  assert.match(report, /Native Organizations, shared multi-tenant membership/);
  assert.match(report, /application-side lifecycle/);
});

test("missing paths and recorded UNKNOWN proposals do not become unavailable, supported or evaluated", () => {
  assert.match(report, /Missing paths remain UNKNOWN, not unavailable/);
  assert.match(report, /supported UNKNOWN claim remains UNKNOWN/);
  assert.match(report, /support=UNKNOWN|availability=UNKNOWN|enforcement=UNKNOWN/);
  assert.match(report, /No eligibility or score is calculated/);
  const directoryId = assembly.selection.selections.find(selection => selection.scope === "directory-sync-staging").optionId;
  const start = report.indexOf(`### ${directoryId}`);
  assert(start >= 0);
  const workosDirectory = report.slice(start, report.indexOf("\n### ", start + 1));
  assert.match(workosDirectory, /Missing focused-case paths \(this fixture only\):[^\n]*facts.OIDC/);
  assert.match(report, /All recorded claims, including the other selected options and UNKNOWN proposals/);
});

test("freshness is a clock-bound display classification, not a source verdict or observation refresh", () => {
  const before = JSON.stringify(assembly);
  assert.match(report, /CURRENT=48, STALE=0, FUTURE=0/);
  assert.match(renderDecisionReview(assembly, new Date("2026-10-01T00:00:00Z")), /CURRENT=0, STALE=0, FUTURE=48/);
  assert.match(renderDecisionReview(assembly, new Date("2026-10-09T12:00:00Z")), /CURRENT=27, STALE=0, FUTURE=21/);
  assert.match(renderDecisionReview(assembly, new Date("2027-01-20T00:00:00Z")), /CURRENT=0, STALE=48, FUTURE=0/);
  assert.equal(renderDecisionReview(assembly, clock), report);
  assert.equal(JSON.stringify(assembly), before);
  assert.match(report, /CURRENT is not source approval/);
  assert.match(report, /Recheck freshness at the actual review\/calculation clock/);
  const observed = Date.parse("2026-10-02T21:20:39Z"), boundary = observed + 90 * 86400000;
  for (const [instant, classification] of [[observed - 1, "FUTURE"], [observed, "CURRENT"], [boundary, "CURRENT"], [boundary + 1, "STALE"]]) {
    const output = renderDecisionReview(assembly, new Date(instant));
    assert(output.includes(`Observed at: 2026-10-02T21:20:39Z; freshness: ${classification}; review: UNREVIEWED.`));
  }
});

test("summary uses the existing explicit Core review vocabulary, supplies no confirmation and does not claim approval", async () => {
  const schema = JSON.parse(await readFile(new URL("../schemas/catalog-bootstrap-review-request.v1.schema.json", import.meta.url)));
  for (const verdict of schema.properties.observations.items.properties.verdict.enum) assert(report.includes(verdict));
  assert.match(report, /only as an authorized curator/);
  assert.match(report, /candidate field from make prepare-decision-candidate, not the entire assembly envelope/);
  assert.match(report, /does not submit a review or grant a role/);
  assert(!report.includes("MANUAL_BOOTSTRAP_SOURCE_REVIEW"));
  assert.match(report, /no human verdict, approval, trusted catalog or evaluated recommendation/);
});

for (const [name, mutate] of [
  ["catalog digest substitution", r => { r.bindings.decisionCatalogSha256 = "0".repeat(64); }],
  ["bootstrap digest substitution", r => { r.bindings.bootstrapCandidateSha256 = r.bindings.decisionCatalogSha256; }],
  ["selection digest substitution", r => { r.bindings.selectionSha256 = "0".repeat(64); }],
  ["case digest substitution", r => { r.bindings.focusedCaseSha256 = "0".repeat(64); }],
  ["claim digest substitution", r => { r.reviewTasks[0].claimSha256 = "0".repeat(64); }],
  ["missing claim", r => { r.reviewTasks.pop(); }],
  ["duplicate claim", r => { r.reviewTasks[1] = structuredClone(r.reviewTasks[0]); }],
  ["source refresh", r => { r.candidate.options[0].facts.SCIM.evidence.observedAt = "2026-10-09T17:00:00Z"; }],
  ["scope borrowing", r => { r.candidate.options[3].facts.SCIM = structuredClone(r.candidate.options[5].facts.SCIM); }],
  ["source URL substitution", r => { r.candidate.options[0].facts.SCIM.evidence.sourceUrl = "https://attacker.invalid/source"; }],
  ["caller verdict", r => { r.reviewTasks[0].verdict = "SOURCE_SUPPORTS_CLAIM"; }],
  ["approval", r => { r.approvalGranted = true; }],
  ["source verification", r => { r.sourceVerificationPerformed = true; }],
  ["publication", r => { r.publicationReady = true; }],
  ["hidden confirmation", r => { r.confirmation = "MANUAL_BOOTSTRAP_SOURCE_REVIEW"; }],
  ["case drift", r => { r.focusedCase.profile.provisioning.scim = "NOT_REQUIRED"; }],
]) test(`review summary rejects ${name} rather than presenting an unbound payload`, () => {
  const input = structuredClone(assembly); mutate(input);
  assert.throws(() => renderDecisionReview(input, clock));
});

test("inspection clocks must be valid Date objects", () => {
  for (const value of [new Date("invalid"), "2026-10-09T17:00:00Z", null, 0]) {
    assert.throws(() => renderDecisionReview(assembly, value), /Invalid inspection clock/);
  }
});

test("stored summaries and conditions remain inert Markdown/HTML data after an explicit test-only repin", async () => {
  const selection = await readDecisionSelection(), drafts = await readSelectedDrafts();
  const fact = drafts[0].draft.options[0].facts.SCIM;
  fact.evidence.summary = "<script>alert('x')</script> [approve](https://attacker.invalid) & `execute`\n# approved";
  fact.conditions.push("<img src=x onerror=alert(1)>\n## approved");
  selection.selections[0].sourceSha256 = decisionDigest(drafts[0].draft);
  const safe = renderDecisionReview(assembleDecisionCandidate(selection, drafts), clock);
  assert(!safe.includes("<script>")); assert(!safe.includes("<img"));
  assert(!safe.includes("[approve](https://attacker.invalid)"));
  assert(!safe.includes("\n# approved")); assert(!safe.includes("\n## approved"));
  assert(safe.includes("&lt;script&gt;")); assert(safe.includes("&amp;"));
  assert(safe.includes("\\[approve\\]")); assert(safe.includes("\\`execute\\`"));
  assert.equal(safe.match(/human verdict: PENDING/g).length, 48);
});

test("CLI reads fixed local inputs, prints only Markdown and writes nothing in an empty working directory", async () => {
  const command = new URL("../scripts/prepare-decision-review.mjs", import.meta.url);
  const directory = await mkdtemp(path.join(tmpdir(), "authweave-review-command-"));
  try {
    const run = args => spawnSync(process.execPath, [command.pathname, ...args], { cwd: directory, encoding: "utf8" });
    const valid = run([]);
    assert.equal(valid.status, 0, valid.stderr);
    assert(valid.stdout.startsWith("# AuthWeave decision candidate:"));
    assert.equal(valid.stderr, "");
    for (const args of [["--approve"], ["https://attacker.invalid/source"], ["../../infra/.env"], ["--at", clock.toISOString()]]) {
      const invalid = run(args);
      assert.equal(invalid.status, 1); assert.equal(invalid.stdout, "");
      assert.match(invalid.stderr, /accepts no paths, URLs, verdicts or options/);
    }
    assert.deepEqual(await readdir(directory), []);
  } finally { await rmdir(directory); }
});

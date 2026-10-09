import path from "node:path";
import { fileURLToPath } from "node:url";
import { assembleDecisionCandidate, decisionDigest, prepareDecisionCandidate } from "./prepare-decision-candidate.mjs";

const order = (a, b) => a < b ? -1 : a > b ? 1 : 0;
const get = (value, address) => address.split(".").reduce((item, key) => item?.[key], value);
const text = value => String(value).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
  .replace(/[\\`*_{}\[\]()#!|]/g, "\\$&").replace(/[\r\n\u2028\u2029]/g, " ");

/** Reuse the pinned assembler rather than accepting a merely shape-valid display payload. */
function boundAssembly(input) {
  const drafts = input.selection.selections.map((selection, index) => ({ file: selection.file, draft: {
    schemaVersion: 1, kind: "PROVIDER_CATALOG_DRAFT", catalogVersion: selection.catalogVersion,
    options: [input.candidate.options[index]],
  } }));
  const expected = assembleDecisionCandidate(input.selection, drafts);
  if (decisionDigest(input) !== decisionDigest(expected)) throw new Error("Review summary requires the exact pinned assembly and pending tasks");
  return expected;
}

function freshness(observedAt, at) {
  const observed = Date.parse(observedAt);
  return at < observed ? "FUTURE" : at > observed + 90 * 86400000 ? "STALE" : "CURRENT";
}

function proposed(fact) {
  const fields = ["availability", "support", "coverage", "enforcement"]
    .filter(key => Object.hasOwn(fact, key)).map(key => `${key}=${fact[key]}`);
  if (Object.hasOwn(fact, "storageCountries")) fields.push(`storageCountries=${JSON.stringify(fact.storageCountries)}`);
  return fields.join("; ");
}

/** Read-only human review aid. The clock classifies observations, never renews them or supplies verdicts. */
export function renderDecisionReview(input, inspectedAt = new Date()) {
  if (!(inspectedAt instanceof Date) || !Number.isFinite(inspectedAt.getTime())) throw new Error("Invalid inspection clock");
  const assembly = boundAssembly(input);
  const { candidate, selection, bindings, focusedCase, reviewTasks } = assembly;
  const required = new Set(focusedCase.requiredFactPaths), preferred = new Set(focusedCase.preferredFactPaths);
  const dependency = (optionId, factPath) => optionId !== focusedCase.targetOptionId ? "OTHER_SELECTED_CLAIM"
    : required.has(factPath) ? "REQUIRED_FOR_FOCUSED_CASE" : preferred.has(factPath) ? "PREFERENCE_FOR_FOCUSED_CASE" : "OTHER_SELECTED_CLAIM";
  const counts = { CURRENT: 0, STALE: 0, FUTURE: 0 };
  const sources = new Set();
  for (const task of reviewTasks) {
    const fact = get(candidate.options.find(option => option.id === task.optionId), task.factPath);
    counts[freshness(fact.evidence.observedAt, inspectedAt.getTime())]++;
    sources.add(fact.evidence.sourceUrl);
  }
  const lines = [
    "# AuthWeave decision candidate: pending source review", "",
    "Status: UNREVIEWED. This report supplies no human verdict, approval, trusted catalog or evaluated recommendation.", "",
    `Inspection clock: ${inspectedAt.toISOString()}. Freshness window: 90 days, inclusive; original observedAt values are unchanged.`,
    `Catalog: ${text(candidate.catalogVersion)}; policy: ${text(selection.policyVersion)}.`,
    `Selected options: ${candidate.options.length}; pending claims: ${reviewTasks.length}; distinct source URLs: ${sources.size}.`,
    `Observation freshness: CURRENT=${counts.CURRENT}, STALE=${counts.STALE}, FUTURE=${counts.FUTURE}. CURRENT is not source approval.`, "",
    "## Exact bindings", "",
    ...Object.entries(bindings).map(([key, value]) => `- ${key}: ${text(value)}`), "",
    "The bootstrap candidate digest is for the existing Core review workflow. The decision catalog digest preserves array order; they are not interchangeable.", "",
    "## Focused case and review priority", "",
    `Case: ${text(focusedCase.caseId)}; target option: ${text(focusedCase.targetOptionId)}.`,
    text(focusedCase.description), "",
    ...focusedCase.requiredFactPaths.map(address => `- REQUIRED_FOR_FOCUSED_CASE: ${text(address)}; verdict PENDING.`),
    ...focusedCase.preferredFactPaths.map(address => `- PREFERENCE_FOR_FOCUSED_CASE: ${text(address)}; verdict PENDING.`),
    ...focusedCase.weights.values.map(weight => `- Explicit preference weight: ${weight.capability}=${weight.weight}.`), "",
    ...focusedCase.limitations.map(limit => `- ${text(limit)}`), "",
    "All recorded claims, including the other selected options and UNKNOWN proposals, still need separate explicit verdicts for a whole-candidate bootstrap review.", "",
    "## Review procedure", "",
    "1. Open each official source and compare its exact product, plan, release, scope and conditions with each proposed claim. Stored URLs and summaries are review inputs, not proof that a source was fetched or checked by this command.",
    "2. Choose independently for every claim: SOURCE_SUPPORTS_CLAIM, SOURCE_DOES_NOT_SUPPORT_CLAIM or INSUFFICIENT_EVIDENCE. No default verdict is supplied; a supported UNKNOWN claim remains UNKNOWN.",
    "3. Use the existing /catalog/bootstrap workflow only as an authorized curator. Its input is the candidate field from make prepare-decision-candidate, not the entire assembly envelope. Read the actual sources before manually confirming the exact payload; this command does not submit a review or grant a role.",
    "4. Recheck freshness at the actual review/calculation clock. A source verdict is not deployment verification, provider integration testing, publication approval or a finished decision result.", "",
    "## Selected scopes and pending claims", "",
  ];
  for (const [index, option] of candidate.options.entries()) {
    const origin = selection.selections[index];
    const tasks = reviewTasks.filter(task => task.optionId === option.id);
    lines.push(`### ${text(option.id)}`, "",
      ...["providerId", "product", "plan", "deployment", "region", "configuration"].map(key => `- ${key}: ${text(option[key])}`),
      `- Selected scope: ${text(origin.scope)}.`,
      `- Source draft: ${text(origin.file)}; version: ${text(origin.catalogVersion)}.`,
      `- Source payload SHA-256: ${origin.sourceSha256}.`, "");
    const missing = [...required, ...preferred].filter(address => !get(option, address));
    lines.push(`Missing focused-case paths (this fixture only): ${missing.length ? missing.map(text).join(", ") : "none"}.`,
      "Recorded does not mean supported or reviewed. Missing paths remain UNKNOWN, not unavailable. No eligibility or score is calculated.", "");
    const groups = new Map();
    for (const task of tasks) {
      const fact = get(option, task.factPath);
      const key = `${fact.evidence.sourceUrl}\0${fact.evidence.observedAt}`;
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push({ task, fact });
    }
    for (const key of [...groups.keys()].sort(order)) {
      const entries = groups.get(key), evidence = entries[0].fact.evidence;
      const source = new URL(evidence.sourceUrl);
      if (source.protocol !== "https:" || source.username || source.password || /[<>\r\n]/.test(evidence.sourceUrl)) throw new Error("Unsafe source URL");
      lines.push(`Official source: [open source](<${source.href}>).`,
        `Observed at: ${evidence.observedAt}; freshness: ${freshness(evidence.observedAt, inspectedAt.getTime())}; review: UNREVIEWED.`, "");
      for (const { task, fact } of entries) {
        lines.push(`#### ${text(task.factPath)}`, "",
          `Proposed claim: ${text(proposed(fact))}.`,
          `Focused-case role: ${dependency(option.id, task.factPath)}; human verdict: PENDING.`,
          `Claim SHA-256: ${task.claimSha256}.`, "",
          `Stored summary: ${text(fact.evidence.summary)}`, "", "Conditions:", "",
          ...(fact.conditions.length ? fact.conditions.map(condition => `- ${text(condition)}`) : ["- No condition is recorded; this is not proof of unconditional support."]), "");
      }
    }
  }
  lines.push("## Remaining boundaries", "", ...assembly.limitations.map(limit => `- ${text(limit)}`), "");
  return lines.join("\n");
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    if (process.argv.length !== 2) throw new Error("This command accepts no paths, URLs, verdicts or options");
    process.stdout.write(renderDecisionReview(await prepareDecisionCandidate()));
  } catch (error) {
    console.error(`Decision source-review summary rejected: ${error.message}`);
    process.exitCode = 1;
  }
}

import { boundedPrerequisiteText } from "./architecture-prerequisites.ts";
import { resultAdviceFromCore, type AdviceChoice, type ResultAdvice } from "./decision-advice.ts";
import { resultReference, resultUuid, type ResultReference } from "./decision-results.ts";
import { savedBlockerGroups } from "./result-blockers.ts";
import { reviewedAdviceText } from "./reviewed-advice.ts";
import { literalBriefText as literal } from "./requirements-brief.ts";

export const decisionBriefFormat = "authweave-saved-decision-brief-v1";
export const decisionBriefByteLimit = 131_072;
export class InvalidDecisionBriefRequest extends Error { }
export class DecisionBriefDownloadError extends Error {
  readonly kind: "session" | "not-found" | "unavailable";
  constructor(kind: DecisionBriefDownloadError["kind"]) {
    super("Saved calculation download is unavailable"); this.kind = kind;
  }
}

export function decisionBriefFilename(id: string, value: ResultReference): string {
  if (!resultUuid.test(id)) throw new InvalidDecisionBriefRequest();
  const reference = resultReference(value);
  return `authweave-decision-${id}-${reference.resultId}-v${reference.version}.md`;
}
export function decisionBriefForm(params: URLSearchParams, resultId: string): ResultReference {
  const entries = [...params];
  if (entries.length !== 2 || !params.has("version") || !params.has("resultSha256") ||
      !/^[1-9][0-9]*$/.test(params.get("version")!)) throw new InvalidDecisionBriefRequest();
  try { return resultReference({ resultId, version: Number(params.get("version")), resultSha256: params.get("resultSha256") }); }
  catch { throw new InvalidDecisionBriefRequest(); }
}

const list = (lines: string[], title: string, values: string[]) => {
  lines.push(`- ${title}:`, ... (values.length ? values.map(v => `  - ${literal(v)}`) : ["  - None recorded."]));
};
function choice(lines: string[], value: AdviceChoice) {
  lines.push(`### ${literal(value.id)}`, "", `- Disposition: ${literal(value.disposition)} (conditional design advice only).`,
    `- Reason: ${literal(value.reasonCode)}.`, "");
  list(lines, "Conditional scoped options", value.conditionalOptionIds);
  list(lines, "Pros", value.pros); list(lines, "Trade-offs", value.cons);
  list(lines, "Conditions to verify", value.conditions); lines.push("");
}

/** Compact allowlisted presentation after Core replay. Never reads a current profile or evaluates facts. */
export function savedDecisionMarkdown(input: ResultAdvice, workspace: string, id: string, reference: ResultReference): string {
  decisionBriefFilename(id, reference);
  const advice = resultAdviceFromCore(input, workspace, id, reference), s = advice.summary, item = s.item;
  const reviewed = reviewedAdviceText(advice);
  const lines = ["# AuthWeave saved calculation brief", "",
    "> Historical conditional advice, not a final ADR, approved decision, deployment recommendation or compliance verification.", "",
    `- Export format: \`${decisionBriefFormat}\``, `- Assessment ID: \`${id}\``,
    `- Result ID: \`${reference.resultId}\``, `- Result version: \`${reference.version}\``, `- Result SHA-256: \`${reference.resultSha256}\``,
    `- Assessment version used: \`${item.assessmentVersion}\``, `- Stored profile schema: \`${s.profileSchemaVersion}\``,
    `- Profile SHA-256: \`${s.profileSha256}\``, `- Catalog snapshot ID: \`${item.catalog.snapshotId}\``,
    `- Catalog version: \`${item.catalog.catalogVersion}\``, `- Catalog SHA-256: \`${item.catalog.snapshotSha256}\``,
    `- Policy SHA-256: \`${s.policySha256}\``, `- Original evaluation clock: \`${s.evaluatedAt}\``, `- Recorded at: \`${item.recordedAt}\``, "",
    "Core verified historical replay using the original profile, catalog, policy, weights and clock before this download. No latest version was substituted. Evidence dates were not refreshed. This file is not a replay proof or a full-profile backup.", "",
    "## Saved outcome", "", `- Status: ${literal(s.status)}.`, `- Original weight mode: ${s.weights.mode}.`,
    ...s.weights.values.map(w => `- ${w.capability} weight: ${w.weight} of 100.`), "",
    "Hard failures exclude an option. UNKNOWN keeps an option unresolved, not supported. Preference points apply only to eligible options; they cannot override hard failures. Points and unknown intervals are not confidence or readiness.", ""];
  list(lines, "Shortlist (scoped option IDs, not provider-wide endorsements)", s.shortlist);
  if (advice.rankGroups.length) for (const group of advice.rankGroups)
    lines.push(`- Rank ${group.rank}: ${group.optionIds.map(literal).join(", ")}${group.optionIds.length > 1 ? " — tied; no automatic winner" : ""}.`);
  else lines.push("- No ranks assigned in this saved calculation.");
  lines.push("", "## Scoped options", "");
  for (const candidate of advice.candidates) {
    const c = candidate.hardChecks;
    lines.push(`### ${literal(c.optionId)}`, "", `- Product: ${literal(c.product)}.`, `- Plan: ${literal(c.plan)}.`,
      `- Region: ${literal(c.region)}.`, `- Deployment: ${c.deployment}.`, `- Configuration: ${literal(c.configuration)}.`,
      `- Saved hard verdict: ${c.hardVerdict}.`, candidate.score
        ? `- Saved preference points: ${candidate.score.lowerBound}–${candidate.score.upperBound}; unknown weight: ${candidate.score.unknownWeight}.`
        : "- No preference score calculated.", "");
  }
  lines.push("## Blocking requirements", "",
    "The same field/criticality/reason groups as the saved UI: excluded FAIL checks first, then unresolved UNKNOWN checks. Not priority or ranking. Counts distinguish scoped options from checks. Grouping never shares evidence between checks.", "");
  const groups = savedBlockerGroups(advice);
  for (const group of groups) {
    lines.push(`- ${literal(group.profilePath)} / ${group.criticality} / ${group.outcome} / ${group.reasonCode}: ${group.options.length} scoped options, ${group.checks} checks.`,
      ...group.options.map(o => `  - ${literal(advice.candidates[o.index].hardChecks.optionId)}: ${o.checks} checks.`));
  }
  if (!groups.length) lines.push("No hard-check blockers recorded. Architecture prerequisites and deployment verification may still be unresolved.");
  lines.push("", "## Conditional architecture", "", `Saved architecture status: ${advice.architecture.status}. RECOMMENDED means conditional fit, not configured or approved.`, "");
  for (const pattern of advice.architecture.patterns) {
    choice(lines, pattern.choice);
    list(lines, "Unverified design prerequisites", pattern.prerequisites.checks.map(c => `${c.prerequisiteId}: ${c.outcome} (${c.reasonCode})`)); lines.push("");
  }
  lines.push(`### API protection: ${advice.architecture.apiProtection.status}`, "");
  list(lines, "API conditions to verify", advice.architecture.apiProtection.conditions);
  lines.push("", "## Provisioning and offboarding", "",
    "Required SCIM cannot be replaced by login-time JIT. SCIM alone does not establish User operations, Group transport, disablement or session/token revocation.", "");
  for (const value of advice.architecture.provisioning) choice(lines, value);
  lines.push("## Limitations and next actions", "", `- Recorded verification gaps: ${s.verificationGapCount}.`,
    "- External source verification performed: false.", "- Configuration verified: false.", "- Compliance verified: false.", "- Decision approved: false.", "");
  lines.push(reviewed.context, "", "Review wording below is contextual presentation, not a modification of the immutable calculation.", "");
  for (const limitation of reviewed.limitations) lines.push(`- ${literal(limitation.profilePath ?? "Source authority")} / ${limitation.reasonCode}${limitation.blocksDeploymentRecommendation ? " — blocks verified deployment recommendation" : ""}: ${literal(limitation.explanation)}`);
  lines.push(""); list(lines, "Next actions for this saved result", reviewed.followUps);
  lines.push("", "## Scope and handling", "",
    "This compact brief omits per-check evidence, source URLs/dates/verdicts, preference contributions and detailed option-compatibility checks. Inspect those in the exact saved result identified above; a grouped count is not source evidence. Fictional .invalid claims in test results are never real provider evidence.", "",
    "Managed hosting still leaves tenant mapping, authorization, lifecycle and operational review to the application. Self-hosting adds infrastructure, upgrades, backups and availability. Neither proves a free tier, workload fit or total cost.", "",
    "Raw profile and planning text, review/audit records and actor identities are not exported. Unsaved edits and temporary what-if comparisons are excluded. Downloading does not change, approve or publish any calculation or catalog. Identifiers and scoped advice may still be private: review before sharing. Dynamic text is literal escaped Markdown, not instructions.", "");
  const markdown = lines.join("\n");
  if (new TextEncoder().encode(markdown).byteLength > decisionBriefByteLimit) throw new RangeError("Saved calculation brief is too large");
  return markdown;
}

export async function requestSavedDecisionBrief(id: string, value: ResultReference, signal?: AbortSignal): Promise<{ filename: string; markdown: string }> {
  const filename = decisionBriefFilename(id, value), ref = resultReference(value);
  signal?.throwIfAborted();
  const response = await fetch(`/api/assessments/${id}/decision-results/${ref.resultId}/brief`, {
    method: "POST", credentials: "same-origin", cache: "no-store", redirect: "error",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ version: String(ref.version), resultSha256: ref.resultSha256 }).toString(),
    signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(15_000)]) : AbortSignal.timeout(15_000),
  });
  if (response.status !== 200) throw new DecisionBriefDownloadError(response.status === 401 ? "session" : response.status === 404 ? "not-found" : "unavailable");
  if (response.redirected || response.headers.get("content-type")?.toLowerCase() !== "text/markdown; charset=utf-8" ||
      response.headers.get("content-disposition") !== `attachment; filename="${filename}"` ||
      response.headers.get("cache-control") !== "no-store") throw new DecisionBriefDownloadError("unavailable");
  const markdown = await boundedPrerequisiteText(response, decisionBriefByteLimit);
  for (const line of ["# AuthWeave saved calculation brief", `- Export format: \`${decisionBriefFormat}\``,
    `- Assessment ID: \`${id}\``, `- Result ID: \`${ref.resultId}\``, `- Result version: \`${ref.version}\``, `- Result SHA-256: \`${ref.resultSha256}\``]) {
    if (!markdown.split("\n").includes(line)) throw new DecisionBriefDownloadError("unavailable");
  }
  if (!markdown.startsWith("# AuthWeave saved calculation brief\n")) throw new DecisionBriefDownloadError("unavailable");
  return { filename, markdown };
}

/** Bounded transport consistency, not a second decision engine or external source verifier. */
import { resultSummaryFromCore, resultCapabilities, type ResultReference, type ResultSummary, type ResultCandidate } from "./decision-results.ts";
import { prerequisiteAnalysis, prerequisiteIds, type ArchitecturePatternId, type PrerequisiteAnalysis } from "./architecture-prerequisites.ts";

export const resultAdviceByteLimit = 1_048_576;
export type AdviceEvidence = { claimSha256: string; sourceAssertion: "SOURCE_SUPPORTS_CLAIM" | "SOURCE_DOES_NOT_SUPPORT_CLAIM" | "INSUFFICIENT_EVIDENCE" | null;
  sourceUrl: string; observedAt: string; conditions: string[]; documentedMinimumRetentionDays: number | null };
export type AdviceFinding = { checkId: string; profilePath: string; factPath: string | null; criticality: string;
  outcome: "PASS" | "FAIL" | "UNKNOWN" | "NOT_APPLIED"; reasonCode: string; evidence: AdviceEvidence | null };
export type AdviceContribution = { capability: string; profilePath: string; weight: number;
  outcome: "AVAILABLE" | "UNAVAILABLE" | "UNKNOWN"; earnedPoints: number; reasonCode: string; evidence: AdviceEvidence | null };
export type AdviceCandidate = { hardChecks: Omit<ResultCandidate, "score"> & { providerId: string; configuration: string; findings: AdviceFinding[] };
  score: (NonNullable<ResultCandidate["score"]> & { contributions: AdviceContribution[] }) | null };
export type AdviceOptionCheck = { optionId: string; match: "CONDITIONAL_MATCH" | "UNRESOLVED" | "EXCLUDED";
  capabilities: { capability: string; usable: boolean; reasonCode: string; evidence: AdviceEvidence | null }[] };
export type AdviceChoice = { id: string; disposition: "RECOMMENDED" | "ALTERNATIVE" | "UNRESOLVED" | "NOT_APPLICABLE"; reasonCode: string;
  conditionalOptionIds: string[]; optionChecks: AdviceOptionCheck[]; pros: string[]; cons: string[]; conditions: string[]; references: string[] };
export type ResultAdvice = { scope: "VERIFIED_ASSESSMENT_DECISION_ADVICE"; summary: ResultSummary; candidates: AdviceCandidate[];
  rankGroups: { rank: number; optionIds: string[] }[];
  architecture: { status: "CONDITIONAL_ADVICE" | "NEEDS_INFORMATION" | "NOT_APPLICABLE";
    basis: "CONDITIONAL_DESIGN_ADVICE_WITH_UNAUTHENTICATED_SOURCE_HYPOTHESES";
    patterns: { choice: AdviceChoice; prerequisites: PrerequisiteAnalysis }[];
    apiProtection: { status: "REQUIRED_CONDITIONAL" | "OPTIONAL_CONDITIONAL" | "NEEDS_INFORMATION" | "NOT_REQUIRED" | "FORBIDDEN";
      optionChecks: AdviceOptionCheck[]; conditions: string[] }; provisioning: AdviceChoice[] };
  limitations: { profilePath: string | null; reasonCode: string; blocksDeploymentRecommendation: boolean; explanation: string }[]; followUps: string[] };

const fail = (): never => { throw new Error("Invalid owned decision advice transport"); };
const object = (value: unknown, keys: string[]): Record<string, unknown> => {
  if (!value || typeof value !== "object" || Array.isArray(value) || Object.keys(value).length !== keys.length || keys.some(k => !Object.hasOwn(value, k))) fail();
  return value as Record<string, unknown>;
};
const text = (value: unknown, max: number): string => {
  if (typeof value !== "string" || !value.length || value.length > max) fail(); return value as string;
};
const code = (value: unknown): string => { const s = text(value, 150); if (!/^[A-Z][A-Z0-9_]*$/.test(s)) fail(); return s; };
function array<T>(value: unknown, max: number, parse: (item: unknown) => T): T[] {
  if (!Array.isArray(value) || value.length > max) fail(); return (value as unknown[]).map(parse);
}
const texts = (value: unknown) => array(value, 200, v => text(v, 4000));
const unique = (ids: string[]) => { if (new Set(ids).size !== ids.length) fail(); return ids; };
function enumeration<const T extends string>(value: unknown, values: readonly T[]): T {
  if (!values.includes(value as T)) fail(); return value as T;
}
function integer(value: unknown, min: number, max: number): number {
  if (!Number.isSafeInteger(value) || Number(value) < min || Number(value) > max) fail(); return value as number;
}
const bool = (value: unknown): boolean => { if (typeof value !== "boolean") fail(); return value as boolean; };
function url(value: unknown): string {
  const s = text(value, 2048); let u: URL; try { u = new URL(s); } catch { return fail(); }
  if (!["http:", "https:"].includes(u.protocol) || !u.hostname || u.username || u.password) fail(); return s;
}
function evidence(value: unknown): AdviceEvidence | null {
  if (value === null) return null;
  const e = object(value, ["claimSha256", "sourceAssertion", "sourceUrl", "observedAt", "conditions", "documentedMinimumRetentionDays"]);
  const hash = text(e.claimSha256, 64), date = text(e.observedAt, 100);
  if (!/^[a-f0-9]{64}$/.test(hash) || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/.test(date) || Number.isNaN(Date.parse(date))) fail();
  return { claimSha256: hash, sourceAssertion: e.sourceAssertion === null ? null : enumeration(e.sourceAssertion,
    ["SOURCE_SUPPORTS_CLAIM", "SOURCE_DOES_NOT_SUPPORT_CLAIM", "INSUFFICIENT_EVIDENCE"]), sourceUrl: url(e.sourceUrl), observedAt: date,
    conditions: texts(e.conditions), documentedMinimumRetentionDays: e.documentedMinimumRetentionDays === null ? null : integer(e.documentedMinimumRetentionDays, 1, 3650) };
}
function finding(value: unknown): AdviceFinding {
  const f = object(value, ["checkId", "profilePath", "factPath", "criticality", "outcome", "reasonCode", "evidence"]);
  return { checkId: text(f.checkId, 600), profilePath: text(f.profilePath, 200), factPath: f.factPath === null ? null : text(f.factPath, 200),
    criticality: enumeration(f.criticality, ["CONTEXT", "REQUIRED", "FORBIDDEN", "PREFERRED", "NOT_REQUIRED", "UNKNOWN"]),
    outcome: enumeration(f.outcome, ["PASS", "FAIL", "UNKNOWN", "NOT_APPLIED"]), reasonCode: code(f.reasonCode), evidence: evidence(f.evidence) };
}
function optionChecks(value: unknown, summary: ResultSummary): AdviceOptionCheck[] {
  const checks = array(value, 100, v => {
    const o = object(v, ["optionId", "match", "capabilities"]), id = text(o.optionId, 100);
    const candidate = summary.candidates.find(c => c.optionId === id); if (!candidate) fail();
    const capabilities = array(o.capabilities, 9, value => {
      const c = object(value, ["capability", "usable", "reasonCode", "evidence"]);
      return { capability: enumeration(c.capability, resultCapabilities), usable: bool(c.usable), reasonCode: code(c.reasonCode), evidence: evidence(c.evidence) };
    }); unique(capabilities.map(c => c.capability));
    const match = enumeration(o.match, ["CONDITIONAL_MATCH", "UNRESOLVED", "EXCLUDED"]);
    if ((candidate!.hardVerdict === "EXCLUDED") !== (match === "EXCLUDED") ||
        (match === "CONDITIONAL_MATCH" && (candidate!.hardVerdict !== "ELIGIBLE" || capabilities.some(c => !c.usable)))) fail();
    return { optionId: id, match, capabilities };
  }); unique(checks.map(c => c.optionId)); return checks;
}
function choice(value: unknown, summary: ResultSummary): AdviceChoice {
  const c = object(value, ["id", "disposition", "reasonCode", "conditionalOptionIds", "optionChecks", "pros", "cons", "conditions", "references"]);
  const checks = optionChecks(c.optionChecks, summary), ids = unique(array(c.conditionalOptionIds, 100, v => text(v, 100)));
  const disposition = enumeration(c.disposition, ["RECOMMENDED", "ALTERNATIVE", "UNRESOLVED", "NOT_APPLICABLE"]);
  const selected = disposition === "RECOMMENDED" || disposition === "ALTERNATIVE";
  if (JSON.stringify(ids) !== JSON.stringify(selected ? checks.filter(c => c.match === "CONDITIONAL_MATCH").map(c => c.optionId) : [])) fail();
  if ((disposition === "RECOMMENDED" || disposition === "ALTERNATIVE") && !ids.length) fail();
  return { id: text(c.id, 100), disposition, reasonCode: code(c.reasonCode), conditionalOptionIds: ids, optionChecks: checks,
    pros: texts(c.pros), cons: texts(c.cons), conditions: texts(c.conditions), references: array(c.references, 30, url) };
}
export function resultAdviceFromCore(value: unknown, workspace: string, assessment: string, reference: ResultReference): ResultAdvice {
  const r = object(value, ["scope", "summary", "candidates", "rankGroups", "architecture", "limitations", "followUps"]);
  if (r.scope !== "VERIFIED_ASSESSMENT_DECISION_ADVICE") fail();
  const summary = resultSummaryFromCore(r.summary, workspace, assessment, reference);
  const candidates = array(r.candidates, 100, (v): AdviceCandidate => {
    const c = object(v, ["hardChecks", "score"]), h = object(c.hardChecks, ["optionId", "providerId", "product", "plan", "region", "deployment", "configuration", "hardVerdict", "findings"]);
    const s = summary.candidates.find(s => s.optionId === h.optionId); if (!s) fail();
    for (const key of ["optionId", "product", "plan", "region", "deployment", "hardVerdict"] as const) if (h[key] !== s![key]) fail();
    const findings = array(h.findings, 200, finding); unique(findings.map(f => f.checkId)); if (!findings.length) fail();
    const verdict = findings.some(f => f.outcome === "FAIL") ? "EXCLUDED" : findings.some(f => f.outcome === "UNKNOWN") ? "UNRESOLVED" : "ELIGIBLE";
    if (h.hardVerdict !== verdict || (c.score === null) !== (s!.score === null)) fail();
    let score: AdviceCandidate["score"] = null;
    if (c.score !== null) {
      const raw = object(c.score, ["lowerBound", "upperBound", "unknownWeight", "contributions"]);
      for (const k of ["lowerBound", "upperBound", "unknownWeight"] as const) if (raw[k] !== s!.score![k]) fail();
      const contributions = array(raw.contributions, 9, (v): AdviceContribution => {
        const x = object(v, ["capability", "profilePath", "weight", "outcome", "earnedPoints", "reasonCode", "evidence"]);
        const capability = enumeration(x.capability, resultCapabilities), weight = integer(x.weight, 1, 100), outcome = enumeration(x.outcome, ["AVAILABLE", "UNAVAILABLE", "UNKNOWN"]);
        if (summary.weights.values.find(w => w.capability === capability)?.weight !== weight || x.earnedPoints !== (outcome === "AVAILABLE" ? weight : 0)) fail();
        return { capability, weight, outcome, earnedPoints: x.earnedPoints as number, profilePath: text(x.profilePath, 200), reasonCode: code(x.reasonCode), evidence: evidence(x.evidence) };
      }); unique(contributions.map(x => x.capability));
      if (contributions.length !== summary.weights.values.length || contributions.reduce((n, x) => n + x.earnedPoints, 0) !== raw.lowerBound ||
          contributions.filter(x => x.outcome === "UNKNOWN").reduce((n, x) => n + x.weight, 0) !== raw.unknownWeight) fail();
      score = { ...s!.score!, contributions };
    }
    const { score: ignored, ...identity } = s!; void ignored;
    return { hardChecks: { ...identity, providerId: text(h.providerId, 100), configuration: text(h.configuration, 1000), findings }, score };
  });
  if (JSON.stringify(candidates.map(c => c.hardChecks.optionId)) !== JSON.stringify(summary.candidates.map(c => c.optionId))) fail();
  const rankGroups = array(r.rankGroups, 100, v => { const g = object(v, ["rank", "optionIds"]);
    return { rank: integer(g.rank, 1, 100), optionIds: unique(array(g.optionIds, 100, v => text(v, 100))) }; });
  // Compare only transported exact score grouping; no source facts or current weights are recalculated.
  const scores = [...new Set(summary.candidates.flatMap(c => c.score ? [c.score.lowerBound] : []))].sort((a, b) => b - a);
  const expectedGroups = summary.status === "RANKED_SHORTLIST" ? scores.map((s, i) => ({ rank: i + 1,
    optionIds: summary.candidates.filter(c => c.score?.lowerBound === s).map(c => c.optionId) })) : [];
  if (JSON.stringify(rankGroups) !== JSON.stringify(expectedGroups)) fail();
  const a = object(r.architecture, ["status", "basis", "patterns", "apiProtection", "provisioning"]);
  if (a.basis !== "CONDITIONAL_DESIGN_ADVICE_WITH_UNAUTHENTICATED_SOURCE_HYPOTHESES") fail();
  const patterns = array(a.patterns, 5, v => {
    const p = object(v, ["choice", "prerequisites"]), c = choice(p.choice, summary);
    if (!Object.hasOwn(prerequisiteIds, c.id)) fail();
    const scope = enumeration((p.prerequisites as PrerequisiteAnalysis)?.clientScope, ["SELECTED", "NOT_SELECTED", "UNKNOWN"]);
    return { choice: c, prerequisites: prerequisiteAnalysis(p.prerequisites,
      { expectedVersion: summary.item.assessmentVersion, patternId: c.id as ArchitecturePatternId, declarations: {} }, scope) };
  });
  if (unique(patterns.map(p => p.choice.id)).length !== 5) fail();
  const api = object(a.apiProtection, ["status", "optionChecks", "conditions"]), provisioning = array(a.provisioning, 3, v => choice(v, summary));
  if (unique(provisioning.map(p => p.id)).length !== 3 || provisioning.some(p => !["SCIM_PUSH", "JIT_LOGIN", "SCIM_AND_JIT"].includes(p.id))) fail();
  const limitations = array(r.limitations, 200, v => { const l = object(v, ["profilePath", "reasonCode", "blocksDeploymentRecommendation", "explanation"]);
    return { profilePath: l.profilePath === null ? null : text(l.profilePath, 200), reasonCode: code(l.reasonCode),
      blocksDeploymentRecommendation: bool(l.blocksDeploymentRecommendation), explanation: text(l.explanation, 4000) }; });
  return { scope: "VERIFIED_ASSESSMENT_DECISION_ADVICE", summary, candidates, rankGroups,
    architecture: { status: enumeration(a.status, ["CONDITIONAL_ADVICE", "NEEDS_INFORMATION", "NOT_APPLICABLE"]),
      basis: "CONDITIONAL_DESIGN_ADVICE_WITH_UNAUTHENTICATED_SOURCE_HYPOTHESES", patterns, provisioning,
      apiProtection: { status: enumeration(api.status, ["REQUIRED_CONDITIONAL", "OPTIONAL_CONDITIONAL", "NEEDS_INFORMATION", "NOT_REQUIRED", "FORBIDDEN"]),
        optionChecks: optionChecks(api.optionChecks, summary), conditions: texts(api.conditions) } }, limitations, followUps: texts(r.followUps) };
}

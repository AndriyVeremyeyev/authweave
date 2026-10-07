import { createHash } from "node:crypto";
import { comparisonFromCore, type SyntheticComparisonSummary } from "./comparison.ts";
import { auditabilityPreviewFromCore, type AuditabilityPreviewBinding } from "./auditability-preview.ts";
import { auditCriteria } from "./auditability.ts";
import { applicationTypes, clientTypes, populations, tenancyModels, membershipModels, dataCategories,
  evaluationContextLabels } from "./evaluation-context.ts";
import { capabilityFields } from "./capabilities.ts";

export const comparisonEvidenceByteLimit = 1_048_576;
export type EvidenceGate = "MISSING" | "UNREVIEWED" | "FUTURE" | "STALE" | "CURRENT";
export type EvidenceRow = {
  path: string; label: string; claim: string | null; sourceUrl: string | null; observedAt: string | null;
  evidenceStatus: "REVIEWED" | "UNREVIEWED" | null; gate: EvidenceGate; configuration: string | null;
};
export type EvidenceGroup = { family: "CAPABILITY" | "CONTEXT" | "RESIDENCY" | "AUTHENTICATION_CONTROL" | "AUDITABILITY"; rows: EvidenceRow[] };
export type ComparisonProvenance = { optionId: string; groups: EvidenceGroup[] };
export type ComparisonEvidenceSummary = { comparison: SyntheticComparisonSummary; catalogSha256: string; evidence: ComparisonProvenance[] };

const support = ["SUPPORTED", "UNSUPPORTED", "UNKNOWN"] as const;
const availability = ["OPTIONAL", "MANDATORY", "UNAVAILABLE", "UNKNOWN"] as const;
const controls = ["PHISHING_RESISTANCE", "NON_EXPORTABLE_KEYS", "STEP_UP_AUTHENTICATION"] as const;
const controlLabels = { PHISHING_RESISTANCE: "Phishing resistance", NON_EXPORTABLE_KEYS: "Non-exportable keys", STEP_UP_AUTHENTICATION: "Step-up authentication" };
const compatibility = {
  applications: applicationTypes.filter(t => !["UNKNOWN", "OTHER"].includes(t)), clients: clientTypes,
  populations, tenancy: tenancyModels.filter(t => t !== "UNKNOWN"), membership: membershipModels.filter(t => t !== "UNKNOWN"),
};
const nanosPerDay = BigInt(86_400_000_000_000);
function invalid(): never { throw new Error("Invalid comparison evidence preview"); }
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value) || Object.getPrototypeOf(value) !== Object.prototype) return invalid();
  return value as Record<string, unknown>;
}
function exact(value: unknown, keys: readonly string[]): Record<string, unknown> {
  const row = object(value);
  if (Object.keys(row).length !== keys.length || keys.some(k => !Object.hasOwn(row, k))) return invalid();
  return row;
}
function entries(value: unknown, keys: readonly string[]): Record<string, unknown> {
  const row = object(value);
  if (Object.keys(row).some(key => !keys.includes(key))) return invalid();
  return row;
}
function text(value: unknown, max: number): string {
  if (typeof value !== "string" || !value || value.length > max || value !== value.trim() || /[\u0000-\u001f\u007f-\u009f]/u.test(value)) return invalid();
  return value;
}
function choice(value: unknown, values: readonly string[]): string {
  if (typeof value !== "string" || !values.includes(value)) return invalid();
  return value;
}
function instant(value: unknown): { text: string; nanos: bigint } {
  const date = text(value, 30), parts = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(date);
  if (!parts) return invalid();
  const millis = Date.parse(parts[1] + "Z");
  if (!Number.isFinite(millis) || new Date(millis).toISOString() !== parts[1] + ".000Z") return invalid();
  return { text: date, nanos: BigInt(millis) * BigInt(1_000_000) + BigInt((parts[2] ?? "").padEnd(9, "0")) };
}
// Match Core's application-specific unordered-array canonicalization, not a signature or source verification.
function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical).sort((a, b) => JSON.stringify(a) < JSON.stringify(b) ? -1 : JSON.stringify(a) > JSON.stringify(b) ? 1 : 0);
  if (value !== null && typeof value === "object") return Object.fromEntries(Object.entries(value).sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([k, v]) => [k, canonical(v)]));
  return value;
}
function same(a: unknown, b: unknown): boolean { return JSON.stringify(canonical(a)) === JSON.stringify(canonical(b)); }
function row(path: string, label: string, raw: unknown, fields: readonly string[], claim: (fact: Record<string, unknown>) => string,
  at: bigint, configuration: string | null = null): EvidenceRow {
  if (raw === undefined) return { path, label, claim: null, sourceUrl: null, observedAt: null, evidenceStatus: null, gate: "MISSING", configuration };
  const fact = exact(raw, [...fields, "evidenceStatus", "sourceUrl", "observedAt"]);
  const source = text(fact.sourceUrl, 2048); let url: URL;
  try { url = new URL(source); } catch { return invalid(); }
  if (url.protocol !== "https:" || !url.hostname.endsWith(".invalid") || url.username || url.password || /\s/u.test(source)) return invalid();
  const observed = instant(fact.observedAt), status = choice(fact.evidenceStatus, ["REVIEWED", "UNREVIEWED"]) as "REVIEWED" | "UNREVIEWED";
  const gate: EvidenceGate = status === "UNREVIEWED" ? "UNREVIEWED" : observed.nanos > at ? "FUTURE" : observed.nanos < at - BigInt(90) * nanosPerDay ? "STALE" : "CURRENT";
  return { path, label, claim: claim(fact), sourceUrl: source, observedAt: observed.text, evidenceStatus: status, gate, configuration };
}

/** Display recorded claims only; this cannot promote evidence, change a verdict or establish an active real baseline. */
export function comparisonEvidenceFromCore(value: unknown, binding: AuditabilityPreviewBinding): ComparisonEvidenceSummary {
  const body = exact(value, ["schemaVersion", "scope", "policyVersion", "catalogSha256", "comparison", "catalog",
    "sourceVerificationPerformed", "publicationReady", "recommendationReady", "writesPerformed"]);
  if (body.schemaVersion !== 1 || body.scope !== "SYNTHETIC_COMPARISON_EVIDENCE" || body.policyVersion !== "comparison-evidence-preview-1"
      || ["sourceVerificationPerformed", "publicationReady", "recommendationReady", "writesPerformed"].some(k => body[k] !== false)
      || typeof body.catalogSha256 !== "string" || !/^[a-f0-9]{64}$/.test(body.catalogSha256)) return invalid();
  const comparison = comparisonFromCore(body.comparison, binding), rawComparison = object(body.comparison);
  const catalog = exact(body.catalog, ["schemaVersion", "catalogVersion", "kind", "options"]);
  if (catalog.schemaVersion !== 4 || catalog.kind !== "SYNTHETIC" || catalog.catalogVersion !== comparison.catalogVersion
      || !/^[a-z0-9][a-z0-9.-]{0,99}$/.test(comparison.catalogVersion) || !Array.isArray(catalog.options)
      || catalog.options.length !== comparison.candidates.length
      || createHash("sha256").update(JSON.stringify(canonical(catalog)), "utf8").digest("hex") !== body.catalogSha256) return invalid();
  const at = instant(comparison.evaluatedAt).nanos;
  const audits = auditabilityPreviewFromCore(rawComparison.auditability, binding);
  const seen = new Set<string>();
  const options = catalog.options.map(value => {
    const option = exact(value, ["id", "displayName", "plan", "region", "facts", "compatibility", "residency", "authenticationControls"]);
    const id = text(option.id, 100);
    if (!/^[a-z0-9][a-z0-9.-]{0,99}$/.test(id) || seen.has(id)) return invalid(); seen.add(id);
    const candidate = comparison.candidates.find(c => c.optionId === id);
    if (!candidate || candidate.displayName !== text(option.displayName, 120) || candidate.plan !== text(option.plan, 120) || candidate.region !== text(option.region, 120)) return invalid();
    const facts = entries(option.facts, capabilityFields.map(f => f.capability));
    const groups: EvidenceGroup[] = [{ family: "CAPABILITY", rows: capabilityFields.map(f => row("facts." + f.capability, f.label,
      facts[f.capability], ["availability"], r => choice(r.availability, availability), at)) }];
    // A preference cannot point at different source metadata than the attached exact option.
    const rawCandidate = (rawComparison.candidates as Record<string, unknown>[]).find(c => c.optionId === id)!;
    for (const pref of rawCandidate.capabilityPreferences as Record<string, unknown>[])
      if (!same(pref.evidence, facts[String(pref.capability)] ?? null)) return invalid();
    const context = exact(option.compatibility, Object.keys(compatibility)), contextRows: EvidenceRow[] = [];
    for (const [scope, values] of Object.entries(compatibility)) {
      const facts = entries(context[scope], values);
      for (const key of values) contextRows.push(row(`compatibility.${scope}.${key}`, evaluationContextLabels[key], facts[key], ["support"], r => choice(r.support, support), at));
    }
    groups.push({ family: "CONTEXT", rows: contextRows });
    const residency = entries(option.residency, dataCategories);
    groups.push({ family: "RESIDENCY", rows: dataCategories.map(category => row("residency." + category, evaluationContextLabels[category], residency[category],
      ["coverage", "storageCountries"], r => {
        const coverage = choice(r.coverage, ["COMPLETE", "PARTIAL", "UNKNOWN"]), countries = r.storageCountries;
        if (!Array.isArray(countries) || countries.length > 249 || countries.some(c => typeof c !== "string" || !/^[A-Z]{2}$/.test(c))
            || new Set(countries).size !== countries.length || (coverage === "UNKNOWN") !== (countries.length === 0)) return invalid();
        return coverage + (countries.length ? " · " + [...countries].sort().join(", ") : " · No destinations established");
      }, at)) });
    const authentication = entries(option.authenticationControls, ["BROWSER", "NATIVE_MOBILE"]), authenticationRows: EvidenceRow[] = [];
    for (const client of ["BROWSER", "NATIVE_MOBILE"]) {
      const scopes = authentication[client] === undefined ? {} : entries(authentication[client], populations);
      for (const population of populations) {
        const facts = scopes[population] === undefined ? {} : entries(scopes[population], controls);
        for (const control of controls) authenticationRows.push(row(`authenticationControls.${client}.${population}.${control}`,
          `${controlLabels[control]} · ${evaluationContextLabels[client]} · ${evaluationContextLabels[population]}`, facts[control], ["availability", "enforcement"], r => {
            const available = choice(r.availability, support), enforceable = choice(r.enforcement, support);
            if (enforceable === "SUPPORTED" && available !== "SUPPORTED") return invalid();
            return `Availability: ${available} · Enforcement capability: ${enforceable}`;
          }, at));
      }
    }
    groups.push({ family: "AUTHENTICATION_CONTROL", rows: authenticationRows });
    const audit = audits.candidates.find(c => c.scope.optionId === id)!;
    groups.push({ family: "AUDITABILITY", rows: auditCriteria.map(criterion => {
      const fact = audit.evidence.find(f => f.criterion === criterion.key);
      // The separate audit guard already binds emitter, exact plan/region/configuration, criteria and duration.
      return row("auditability." + criterion.key, criterion.label, fact && {
        support: fact.support, documentedMinimumRetentionDays: fact.documentedMinimumRetentionDays,
        evidenceStatus: fact.evidenceStatus, sourceUrl: fact.sourceUrl, observedAt: fact.observedAt,
      }, ["support", "documentedMinimumRetentionDays"], r => choice(r.support, support) + (r.documentedMinimumRetentionDays === null ? "" : ` · Documented minimum: ${r.documentedMinimumRetentionDays} days`), at, audit.scope.configuration);
    }) });
    return { optionId: id, groups };
  });
  return { comparison, catalogSha256: body.catalogSha256, evidence: comparison.candidates.map(c => options.find(o => o.optionId === c.optionId)!) };
}

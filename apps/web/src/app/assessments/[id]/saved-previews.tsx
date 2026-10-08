import type { ComponentProps } from "react";
import type { readComparisonEvidence, readPersonalArchitecturePatterns, readPersonalUsagePlanning,
  readPersonalOperationsPlanning, readPersonalAuditability, readPersonalAssurancePlanning } from "@/lib/auth/core-client";
import { ComparisonSection } from "./comparison-section";
import { WeightedPreviewForm } from "./weighted-preview";
import { ArchitecturePatterns } from "./architecture-patterns";
import { UsagePlanningPreflight } from "./usage-planning-preflight";
import { OperationsPlanning, OperationsPlanningUnavailable } from "./operations-planning";
import { AuditabilityPreflight, AuditabilityPreflightUnavailable } from "./auditability-preflight";
import { AssuranceCompliancePlanning, AssuranceCompliancePlanningUnavailable } from "./assurance-compliance-planning";

type GuardedResult<T> = Promise<Awaited<T> | null>;

// These Server Components consume already-started, guarded reads. They never fetch or save.
export async function ComparisonPreview({ result, assessmentId, profile, editable, preferred }: {
  result: GuardedResult<ReturnType<typeof readComparisonEvidence>>; assessmentId: string;
  profile: Record<string, unknown>; editable: boolean;
  preferred: ComponentProps<typeof WeightedPreviewForm>["preferred"];
}) {
  const preview = await result;
  if (!preview) return <PreviewUnavailable name="Synthetic comparison" />;
  const comparison = preview.comparison;
  return <ComparisonSection comparison={comparison} evidence={preview.evidence} profile={profile} editable={editable}
    preferencePreview={preferred.length > 0 ? <WeightedPreviewForm key={`${assessmentId}-${comparison.assessmentVersion}`}
      assessmentId={assessmentId} version={comparison.assessmentVersion} preferred={preferred} /> : null} />;
}

export async function ArchitecturePreview({ result, assessmentId }: {
  result: GuardedResult<ReturnType<typeof readPersonalArchitecturePatterns>>; assessmentId: string;
}) {
  const preview = await result;
  return preview ? <ArchitecturePatterns preview={preview} assessmentId={assessmentId} />
    : <PreviewUnavailable name="Architecture pattern preflight" />;
}

export async function UsagePreview({ result }: { result: GuardedResult<ReturnType<typeof readPersonalUsagePlanning>> }) {
  const preview = await result;
  return preview ? <UsagePlanningPreflight preview={preview} /> : <PreviewUnavailable name="Usage input check" />;
}

export async function OperationsPreview({ result }: { result: GuardedResult<ReturnType<typeof readPersonalOperationsPlanning>> }) {
  const preview = await result;
  return preview ? <OperationsPlanning preview={preview} /> : <OperationsPlanningUnavailable />;
}

export async function AuditPreview({ result }: { result: GuardedResult<ReturnType<typeof readPersonalAuditability>> }) {
  const preview = await result;
  return preview ? <AuditabilityPreflight preview={preview} /> : <AuditabilityPreflightUnavailable />;
}

export async function AssurancePreview({ result, editable }: {
  result: GuardedResult<ReturnType<typeof readPersonalAssurancePlanning>>; editable: boolean;
}) {
  const preview = await result;
  return preview ? <AssuranceCompliancePlanning preview={preview} editable={editable} /> : <AssuranceCompliancePlanningUnavailable />;
}

export function PreviewPending({ name, version }: { name: string; version: number }) {
  return <section role="status" aria-live="polite" aria-busy="true" className="mt-6 rounded-xl border border-slate-700 bg-slate-900/50 p-5">
    <h3 className="font-semibold">{name} loading</h3>
    <p className="mt-2 text-sm text-slate-300">Checking saved version {version}. Your saved inputs remain available while this preview loads.</p>
  </section>;
}

export function PreviewUnavailable({ name }: { name: string }) {
  return <section className="mt-6 rounded-xl border border-amber-700 p-5"><h3 className="font-semibold">{name} unavailable</h3>
    <p className="mt-2 text-sm text-slate-300">Your saved assessment is still available. Try reloading this page later.</p></section>;
}

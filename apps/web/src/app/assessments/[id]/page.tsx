import Link from "next/link";
import { cookies } from "next/headers";
import { notFound, redirect } from "next/navigation";

import { capabilityFields, capabilityValues } from "@/lib/assessment/capabilities";
import { evaluationContextValues } from "@/lib/assessment/evaluation-context";
import { usagePlanningValues } from "@/lib/assessment/usage-planning";
import { auditabilityValues } from "@/lib/assessment/auditability";
import type { AuditabilityPreview } from "@/lib/assessment/auditability-preview";
import { authConfiguration } from "@/lib/auth/config";
import { readPersonalArchitecturePatterns, readPersonalAssessment, readPersonalUsagePlanning, readPersonalAuditability,
  readSyntheticComparison,
  type ArchitecturePatternPreflightSummary,
  type PersonalAssessment, type SyntheticComparisonSummary,
  type UsagePlanningPreflightSummary } from "@/lib/auth/core-client";
import { sessionCookieName } from "@/lib/auth/session-policy";
import { touchSession, type BrowserSession } from "@/lib/auth/store";
import { WeightedPreviewForm } from "./weighted-preview";
import { EvaluationContextEditor } from "./evaluation-context-editor";
import { ArchitecturePatterns } from "./architecture-patterns";
import { UsagePlanningEditor } from "./usage-planning-editor";
import { UsagePlanningPreflight } from "./usage-planning-preflight";
import { AuditabilityEditor } from "./auditability-editor";
import { AuditabilityPreflight, AuditabilityPreflightUnavailable } from "./auditability-preflight";
import { assessmentStepFromQuery } from "@/lib/assessment/workflow";
import { AssessmentWorkflow } from "./assessment-workflow";
import { SavedRequirementsOverview } from "./saved-requirements-overview";
import { ComparisonSection } from "./comparison-section";
import { SavedContextSummary } from "./saved-context-summary";
import { SavedRequirementsExport } from "./saved-requirements-export";
import { CapabilityEditor } from "./capability-editor";

export const runtime = "nodejs";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const editErrors: Record<string, string> = {
  stale: "This draft changed since you opened it. Review the current values and save again.",
  invalid: "Core rejected this combination of requirements. Review the current profile before trying again.",
  locked: "Only drafts can be edited here.",
};
const contextErrors: Record<string, string> = {
  stale: "This draft changed since you opened it. Review the current context and save again.",
  invalid: "Core rejected this combination of context and security requirements. Review the profile before trying again.",
  locked: "Only drafts can be edited here.",
};
const usageErrors: Record<string, string> = {
  stale: "This draft changed since you opened it. Review the current usage inputs and save again.",
  invalid: "Core rejected these usage inputs. Review the current profile before trying again.",
  locked: "Only drafts can be edited here.",
};
const auditErrors: Record<string, string> = {
  stale: "This draft changed since you opened it. Review the current auditability inputs and save again.",
  invalid: "Core rejected these auditability inputs. Review the current profile before trying again.",
  locked: "Only drafts can be edited here.",
};

export default async function AssessmentPage({ params, searchParams }: PageProps<"/assessments/[id]">) {
  const { id } = await params;
  if (!UUID.test(id)) notFound();

  let session: BrowserSession | null;
  try {
    const config = authConfiguration();
    const sessionId = (await cookies()).get(sessionCookieName(config.secureCookies))?.value;
    session = await touchSession(sessionId);
  } catch {
    return <Unavailable />;
  }
  if (!session) redirect("/account");

  let assessment: PersonalAssessment | null;
  try {
    assessment = await readPersonalAssessment(session, id);
  } catch {
    return <Unavailable />;
  }
  if (!assessment) notFound();

  const query = await searchParams;
  const editError = query.editError;
  const contextError = query.contextError;
  const usageError = query.usageError;
  const auditError = query.auditError;
  const values = capabilityValues(assessment.profile);
  const contextValues = evaluationContextValues(assessment.profile);
  const usageValues = usagePlanningValues(assessment.profile);
  const auditValues = auditabilityValues(assessment.profile);
  const preferred = values ? capabilityFields.filter(field => values[field.capability] === "PREFERRED")
    .map(field => ({ capability: field.capability, label: field.label })) : [];

  let comparison: SyntheticComparisonSummary | null = null;
  try {
    if (auditValues) comparison = await readSyntheticComparison(session, id, assessment.version, auditValues);
  } catch {
    // Keep the private assessment readable if the diagnostic comparison is unavailable.
  }

  let patterns: ArchitecturePatternPreflightSummary | null = null;
  if (contextValues) {
    try {
      patterns = await readPersonalArchitecturePatterns(session, id, assessment.version, contextValues);
    } catch {
      // Keep the assessment and provider comparison readable if this separate preflight is unavailable.
    }
  }

  let usagePreview: UsagePlanningPreflightSummary | null = null;
  if (usageValues) {
    try {
      usagePreview = await readPersonalUsagePlanning(session, id, assessment.version, usageValues);
    } catch {
      // Keep the assessment and other independent previews readable if this input check is unavailable.
    }
  }

  let auditPreview: AuditabilityPreview | null = null;
  if (auditValues) {
    try {
      auditPreview = await readPersonalAuditability(session, id, assessment.version, auditValues);
    } catch {
      // A stale, malformed or unavailable preview must not block the assessment or infer a result.
    }
  }

  return (
    <main className="mx-auto max-w-6xl px-5 py-10 text-slate-100 sm:px-8 sm:py-14">
      <div className="flex flex-wrap items-start justify-between gap-5">
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.2em] text-cyan-200">AuthWeave · Personal workspace</p>
          <h1 className="mt-3 text-4xl font-semibold tracking-tight">Your identity decision</h1>
          <p className="mt-4 max-w-2xl text-sm leading-6 text-slate-300">Build your requirements one section at a time, then explore the available checks and trade-offs.</p>
        </div>
        <div className="flex items-center gap-3 rounded-xl border border-white/10 bg-white/5 px-4 py-3 text-sm">
          <span className="rounded-md bg-cyan-300/10 px-2 py-1 text-xs font-semibold text-cyan-200">{assessment.status}</span>
          <span className="text-slate-300">Saved version {assessment.version}</span>
        </div>
      </div>
      <SavedContextSummary values={contextValues} />
      <AssessmentWorkflow key={`${assessment.id}-${assessment.version}`} initialStep={assessmentStepFromQuery(query)}
        editable={assessment.status === "DRAFT"} panels={{
          context: <>
            <StepError error={contextError} messages={contextErrors} />
            {assessment.status !== "DRAFT" ? <ReadOnlyStep /> : contextValues
              ? <EvaluationContextEditor assessmentId={assessment.id} version={assessment.version} values={contextValues} />
              : <UnreadableStep name="Context" />}
          </>,
          capabilities: <>
            <StepError error={editError} messages={editErrors} />
            {assessment.status !== "DRAFT" ? <ReadOnlyStep /> : values
              ? <CapabilityEditor assessmentId={assessment.id} version={assessment.version} values={values} /> : <UnreadableStep name="Requirement" />}
          </>,
          auditability: <>
            <StepError error={auditError} messages={auditErrors} />
            {assessment.status !== "DRAFT" ? <ReadOnlyStep /> : auditValues
              ? <AuditabilityEditor assessmentId={assessment.id} version={assessment.version} values={auditValues} />
              : <UnreadableStep name="Audit" />}
            {auditPreview ? <AuditabilityPreflight preview={auditPreview} /> : <AuditabilityPreflightUnavailable />}
          </>,
          usage: <>
            <StepError error={usageError} messages={usageErrors} />
            {assessment.status !== "DRAFT" ? <ReadOnlyStep /> : usageValues
              ? <UsagePlanningEditor assessmentId={assessment.id} version={assessment.version} values={usageValues} />
              : <UnreadableStep name="Usage" />}
            {usagePreview ? <UsagePlanningPreflight preview={usagePreview} /> : <PreviewUnavailable name="Usage input check" />}
          </>,
          review: <SavedRequirementsOverview profile={assessment.profile} version={assessment.version} editable={assessment.status === "DRAFT"}
            exportPanel={<SavedRequirementsExport assessmentId={assessment.id} version={assessment.version} />} />,
          comparison: comparison ? <ComparisonSection comparison={comparison} profile={assessment.profile} editable={assessment.status === "DRAFT"}
            preferencePreview={preferred.length > 0 ? <WeightedPreviewForm key={`${assessment.id}-${comparison.assessmentVersion}`}
              assessmentId={assessment.id} version={comparison.assessmentVersion} preferred={preferred} /> : null} /> : <PreviewUnavailable name="Synthetic comparison" />,
          architecture: patterns ? <ArchitecturePatterns preview={patterns} assessmentId={assessment.id} />
            : <PreviewUnavailable name="Architecture pattern preflight" />,
        }} />
      <details className="mt-8 rounded-xl border border-white/10 p-5 text-sm text-slate-400">
        <summary className="cursor-pointer font-medium text-slate-300">Saved profile and technical details</summary>
        <p className="mt-4 break-all">Assessment ID: {assessment.id} · Saved version {assessment.version}</p>
        <p className="mt-2">Some profile fields remain read-only. The comparison uses fictional catalog data, not approved real-provider facts.</p>
        <pre className="mt-4 overflow-x-auto whitespace-pre-wrap break-words">{JSON.stringify(assessment.profile, null, 2)}</pre>
      </details>
    </main>
  );
}

function StepError({ error, messages }: { error: string | string[] | undefined; messages: Record<string, string> }) {
  return typeof error === "string" && Object.hasOwn(messages, error)
    ? <p role="alert" className="mt-6 rounded-lg border border-amber-700 p-4 text-amber-100">{messages[error]}</p> : null;
}

function ReadOnlyStep() {
  return <p className="mt-6 rounded-xl border border-slate-700 p-5 text-sm text-slate-300">This assessment is read-only. View its saved profile in the technical details below.</p>;
}

function UnreadableStep({ name }: { name: string }) {
  return <p role="alert" className="mt-6 rounded-xl border border-amber-700 p-5 text-sm text-amber-100">{name} editing is unavailable because this profile cannot be read safely. Other steps remain available.</p>;
}

function PreviewUnavailable({ name }: { name: string }) {
  return <section className="mt-6 rounded-xl border border-amber-700 p-5"><h3 className="font-semibold">{name} unavailable</h3>
    <p className="mt-2 text-sm text-slate-300">Your saved assessment is still available. Try reloading this page later.</p></section>;
}

function Unavailable() {
  return (
    <main className="mx-auto max-w-2xl px-6 py-20 text-slate-100">
      <Link href="/account" className="text-sm text-cyan-200 hover:underline">← Account</Link>
      <h1 className="mt-8 text-3xl font-semibold">Assessment unavailable</h1>
      <p className="mt-4 text-slate-300">Please try again later.</p>
    </main>
  );
}

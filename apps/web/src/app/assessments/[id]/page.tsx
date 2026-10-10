import Link from "next/link";
import { Suspense } from "react";
import { cookies } from "next/headers";
import { notFound, redirect } from "next/navigation";

import { capabilityFields, capabilityValues } from "@/lib/assessment/capabilities";
import { evaluationContextValues } from "@/lib/assessment/evaluation-context";
import { usagePlanningValues } from "@/lib/assessment/usage-planning";
import { operationsPlanningValues } from "@/lib/assessment/operations-planning";
import { assurancePlanningValues } from "@/lib/assessment/assurance-compliance-planning";
import { auditabilityValues } from "@/lib/assessment/auditability";
import { authConfiguration } from "@/lib/auth/config";
import { readPersonalArchitecturePatterns, readPersonalAssessment, readPersonalUsagePlanning, readPersonalAuditability, readPersonalOperationsPlanning, readPersonalAssurancePlanning,
  readComparisonEvidence,
  type PersonalAssessment } from "@/lib/auth/core-client";
import { sessionCookieName } from "@/lib/auth/session-policy";
import { touchSession, type BrowserSession } from "@/lib/auth/store";
import { EvaluationContextEditor } from "./evaluation-context-editor";
import { ProvisioningLifecycle } from "./provisioning-lifecycle";
import { UsagePlanningEditor } from "./usage-planning-editor";
import { OperationsPlanningUnavailable } from "./operations-planning";
import { AssuranceCompliancePlanningUnavailable } from "./assurance-compliance-planning";
import { OperationalPreferencesEditor } from "./operational-preferences-editor";
import { AuditabilityEditor } from "./auditability-editor";
import { AuditabilityPreflightUnavailable } from "./auditability-preflight";
import { ComparisonPreview, ArchitecturePreview, UsagePreview, OperationsPreview, AuditPreview, AssurancePreview,
  PreviewPending, PreviewUnavailable } from "./saved-previews";
import { assessmentStepFromQuery } from "@/lib/assessment/workflow";
import { AssessmentWorkflow } from "./assessment-workflow";
import { SavedRequirementsOverview } from "./saved-requirements-overview";
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
const operationsErrors: Record<string, string> = {
  stale: "This draft changed since you opened it. Review the current operational preferences and save again.",
  invalid: "Core rejected these operational preferences. Review the current profile before trying again.",
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
  const operationsValues = operationsPlanningValues(assessment.profile);
  const auditValues = auditabilityValues(assessment.profile);
  const assuranceValues = assurancePlanningValues(assessment.profile);
  const preferred = values ? capabilityFields.filter(field => values[field.capability] === "PREFERRED")
    .map(field => ({ capability: field.capability, label: field.label })) : [];

  // Independent, version-bound reads start together only after the live session and saved profile are resolved.
  // Catch each read separately: unavailable or rejected previews never erase other sections or infer a result.
  const comparisonPreview = auditValues ? readComparisonEvidence(session, id, assessment.version, auditValues).catch(() => null) : null;
  const patterns = contextValues ? readPersonalArchitecturePatterns(session, id, assessment.version, contextValues).catch(() => null) : null;
  const usagePreview = usageValues ? readPersonalUsagePlanning(session, id, assessment.version, usageValues).catch(() => null) : null;
  const operationsPreview = operationsValues ? readPersonalOperationsPlanning(session, id, assessment.version, operationsValues).catch(() => null) : null;
  const auditPreview = auditValues ? readPersonalAuditability(session, id, assessment.version, auditValues).catch(() => null) : null;
  const assurancePreview = assuranceValues ? readPersonalAssurancePlanning(session, id, assessment.version, assuranceValues).catch(() => null) : null;

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
            {auditPreview ? <Suspense fallback={<PreviewPending name="Auditability preview" version={assessment.version} />}>
              <AuditPreview result={auditPreview} /></Suspense> : <AuditabilityPreflightUnavailable />}
          </>,
          usage: <>
            <StepError error={query.operationsError} messages={operationsErrors} />
            {assessment.status === "DRAFT" && (operationsValues
              ? <OperationalPreferencesEditor assessmentId={assessment.id} version={assessment.version} values={operationsValues.inputs} />
              : <UnreadableStep name="Operational preferences" />)}
            <StepError error={usageError} messages={usageErrors} />
            {assessment.status !== "DRAFT" ? <ReadOnlyStep /> : usageValues
              ? <UsagePlanningEditor assessmentId={assessment.id} version={assessment.version} values={usageValues} />
              : <UnreadableStep name="Usage" />}
            {usagePreview ? <Suspense fallback={<PreviewPending name="Usage input check" version={assessment.version} />}>
              <UsagePreview result={usagePreview} /></Suspense> : <PreviewUnavailable name="Usage input check" />}
            {operationsPreview ? <Suspense fallback={<PreviewPending name="Operations planning preview" version={assessment.version} />}>
              <OperationsPreview result={operationsPreview} /></Suspense> : <OperationsPlanningUnavailable />}
          </>,
          review: <>
            <section aria-labelledby="saved-results-entry" className="mt-6 rounded-xl border border-white/10 p-5">
              <h3 id="saved-results-entry" className="font-medium">Saved decision calculations</h3>
              <p className="mt-2 text-sm leading-6 text-slate-300">Browse immutable calculation versions separately from these synthetic previews. Opening history does not record, recalculate or approve a decision.</p>
              <Link prefetch={false} href={`/assessments/${assessment.id}/results`} className="mt-3 inline-block text-sm text-cyan-200 hover:underline">Open saved calculation history →</Link>
            </section>
            <SavedRequirementsOverview profile={assessment.profile} version={assessment.version} editable={assessment.status === "DRAFT"}
              exportPanel={<SavedRequirementsExport assessmentId={assessment.id} version={assessment.version} />} />
            {assurancePreview ? <Suspense fallback={<PreviewPending name="Assurance and compliance preview" version={assessment.version} />}>
              <AssurancePreview result={assurancePreview} editable={assessment.status === "DRAFT"} /></Suspense> : <AssuranceCompliancePlanningUnavailable />}
          </>,
          comparison: comparisonPreview ? <Suspense fallback={<PreviewPending name="Synthetic comparison" version={assessment.version} />}>
            <ComparisonPreview result={comparisonPreview} assessmentId={assessment.id} profile={assessment.profile}
              editable={assessment.status === "DRAFT"} preferred={preferred} /></Suspense> : <PreviewUnavailable name="Synthetic comparison" />,
          architecture: <>
            {patterns ? <Suspense fallback={<PreviewPending name="Architecture pattern preflight" version={assessment.version} />}>
              <ArchitecturePreview result={patterns} assessmentId={assessment.id} /></Suspense> : <PreviewUnavailable name="Architecture pattern preflight" />}
            {values ? <ProvisioningLifecycle key={`${assessment.id}-${assessment.version}`} assessmentId={assessment.id} version={assessment.version}
              requirements={{ scim: values.SCIM, justInTimeProvisioning: values.JIT, groupSynchronization: values.GROUP_SYNC }} />
              : <PreviewUnavailable name="Provisioning design inputs" />}
          </>,
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

function Unavailable() {
  return (
    <main className="mx-auto max-w-2xl px-6 py-20 text-slate-100">
      <Link href="/account" className="text-sm text-cyan-200 hover:underline">← Account</Link>
      <h1 className="mt-8 text-3xl font-semibold">Assessment unavailable</h1>
      <p className="mt-4 text-slate-300">Please try again later.</p>
    </main>
  );
}

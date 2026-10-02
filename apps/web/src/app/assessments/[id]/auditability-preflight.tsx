import { auditCriteria } from "@/lib/assessment/auditability";
import { auditabilityReasons, type AuditabilityPreview } from "@/lib/assessment/auditability-preview";

const statuses = {
  MATCHES_CHECKED_REQUIREMENTS: "Matches selected capability checks only",
  DOES_NOT_MATCH: "Does not match selected capability checks",
  NEEDS_INFORMATION: "Needs more information",
  NOT_APPLIED: "No capability checks applied",
};
const outcomes = { PASS: "Capability match", FAIL: "Capability mismatch", UNKNOWN: "Unknown", NOT_APPLIED: "Not applied" };
const criticalities = { REQUIRED: "Required", PREFERRED: "Preferred", NOT_REQUIRED: "Not required",
  UNKNOWN: "Unknown", FORBIDDEN: "Forbidden — intent needs clarification" };
const support = { SUPPORTED: "Supported", UNSUPPORTED: "Unsupported", UNKNOWN: "Unknown support" };

export function AuditabilityPreflight({ preview }: { preview: AuditabilityPreview }) {
  const selected = auditCriteria.filter(c => preview.values.selectedCriteria.includes(c.key));
  return (
    <section className="mt-10 rounded-xl border border-slate-700 p-6" aria-labelledby="auditability-preflight-heading">
      <h2 id="auditability-preflight-heading" className="text-2xl font-semibold">Identity auditability capability preview</h2>
      <p className="mt-3 text-slate-300">Synthetic teaching examples, not facts about ZITADEL or any real vendor.
        Each result belongs to one option, plan, region and configuration. Evidence from different scopes is never pooled.</p>
      <p className="mt-3 rounded-lg border border-amber-700 p-4 text-amber-100">This is a partial capability check,
        not verified logging, compliance, merged provider eligibility, ranking or a recommendation.
        A mismatch does not hide other information gaps.</p>
      <dl className="mt-5 grid gap-3 text-sm sm:grid-cols-2">
        <div><dt className="text-slate-400">Saved requirement</dt><dd>{criticalities[preview.values.criticality]}</dd></div>
        <div><dt className="text-slate-400">Assessment version</dt><dd>{preview.assessmentVersion}</dd></div>
        <div><dt className="text-slate-400">Selected criteria</dt><dd>{selected.length ? selected.map(c => c.label).join(", ") : "None recorded — scope unresolved"}</dd></div>
        <div><dt className="text-slate-400">Requested provider retention minimum</dt><dd>{preview.values.minimumRetentionDays === null ? "Not selected" : `${preview.values.minimumRetentionDays} days`}</dd></div>
        <div><dt className="text-slate-400">Base catalog / evidence versions</dt><dd className="break-words">{preview.baseCatalogVersion} / {preview.evidenceVersion}</dd></div>
        <div><dt className="text-slate-400">Evaluation time (UTC)</dt><dd>{preview.evaluatedAt}</dd></div>
      </dl>
      <p className="mt-4 text-sm text-slate-400">Only selected required criteria use evidence. Preferred is not scored;
        unknown and forbidden intent need clarification. No selection is an unresolved scope, not an audit exemption.
        Usable facts must be reviewed, not future-dated and no more than 90 days old at evaluation time.
        Reloading does not refresh observation dates.</p>
      <div className="mt-6 space-y-6">
        {preview.candidates.map(candidate => (
          <article key={JSON.stringify(candidate.scope)} className="rounded-xl border border-slate-600 p-5">
            <h3 className="text-xl font-semibold">{candidate.displayName}</h3>
            <p className="mt-2 font-medium text-cyan-200">{statuses[candidate.status]}</p>
            <dl className="mt-4 grid gap-2 text-sm sm:grid-cols-2">
              <div><dt className="text-slate-400">Option</dt><dd className="break-words">{candidate.scope.optionId}</dd></div>
              <div><dt className="text-slate-400">Plan</dt><dd className="break-words">{candidate.scope.plan}</dd></div>
              <div><dt className="text-slate-400">Region</dt><dd className="break-words">{candidate.scope.region}</dd></div>
              <div><dt className="text-slate-400">Configuration scope (not verified)</dt><dd className="break-words">{candidate.scope.configuration}</dd></div>
            </dl>
            <ul className="mt-5 space-y-4">
              {candidate.checks.map((check, index) => {
                const criterion = auditCriteria[index];
                const fact = candidate.evidence.find(f => f.criterion === check.criterion);
                return <li key={check.criterion} className="border-t border-slate-700 pt-4">
                  <h4 className="font-medium">{criterion.label}: {outcomes[check.outcome]}</h4>
                  <p className="mt-1 text-sm text-slate-300">{auditabilityReasons[check.reasonCode]}</p>
                  <p className="mt-1 text-sm text-slate-400">{criterion.help}</p>
                  {check.documentedMinimumRetentionDays !== null && <p className="mt-2 text-sm">
                    Documented provider minimum: {check.documentedMinimumRetentionDays} days;
                    requested minimum: {preview.values.minimumRetentionDays} days. This is not external-sink retention.
                  </p>}
                  <details className="mt-2 text-sm">
                    <summary className="cursor-pointer text-cyan-200">Recorded synthetic evidence</summary>
                    {fact ? <div className="mt-2 space-y-1 text-slate-400">
                      <p>Identity-provider claim: {support[fact.support]}. Synthetic review status: {fact.evidenceStatus}.</p>
                      <p>Observed at (UTC): {fact.observedAt}. This is not an observed log event.</p>
                      {fact.criterion === "AUDIT_LOG_RETENTION" && <p>Recorded minimum duration: {fact.documentedMinimumRetentionDays === null ? "Unknown" : `${fact.documentedMinimumRetentionDays} days`}.</p>}
                      <p className="break-all">Fictional source (not fetched): {fact.sourceUrl}</p>
                    </div> : <p className="mt-2 text-slate-400">No fact recorded for this criterion and exact option scope.</p>}
                  </details>
                </li>;
              })}
            </ul>
          </article>
        ))}
      </div>
      <details className="mt-6 text-sm text-slate-300">
        <summary className="cursor-pointer font-medium">What this preview does not verify</summary>
        <ul className="mt-3 list-disc space-y-1 pl-5">
          <li>Event record content and scope, including application business events.</li>
          <li>Audit record integrity and access controls.</li>
          <li>Logging failure handling.</li>
          <li>Export delivery and retrieval from an external sink.</li>
          <li>Deployed logging configuration and actual retention.</li>
          <li>Compliance evidence or certification.</li>
        </ul>
        <p className="mt-3">No source verification is performed. Provisioning-event evidence is not proof of SCIM support
          or executed lifecycle changes. Passing every selected check still does not make a recommendation ready.</p>
      </details>
    </section>
  );
}

export function AuditabilityPreflightUnavailable() {
  return <section className="mt-10 rounded-xl border border-amber-700 p-6" aria-labelledby="auditability-preflight-heading">
    <h2 id="auditability-preflight-heading" className="text-xl font-semibold">Identity auditability preview unavailable</h2>
    <p className="mt-2 text-slate-300">Your assessment and other independent previews are still available.
      Reload to use the current saved requirements. No result is inferred from an unavailable preview.</p>
  </section>;
}

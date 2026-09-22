import Link from "next/link";
import { cookies } from "next/headers";
import { notFound, redirect } from "next/navigation";

import { authConfiguration } from "@/lib/auth/config";
import { readPersonalAssessment, readSyntheticComparison, type ComparisonCandidate,
  type ComparisonFinding, type PersonalAssessment, type SyntheticComparisonSummary } from "@/lib/auth/core-client";
import { sessionCookieName } from "@/lib/auth/session-policy";
import { touchSession, type BrowserSession } from "@/lib/auth/store";

export const runtime = "nodejs";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export default async function AssessmentPage({ params }: PageProps<"/assessments/[id]">) {
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

  let comparison: SyntheticComparisonSummary | null = null;
  try {
    comparison = await readSyntheticComparison(session, id, assessment.version);
  } catch {
    // Keep the private assessment readable if the diagnostic comparison is unavailable.
  }

  return (
    <main className="mx-auto max-w-4xl px-6 py-20 text-slate-100">
      <Link href="/assessments" className="text-sm text-cyan-200 hover:underline">← Your assessments</Link>
      <h1 className="mt-8 text-4xl font-semibold">Assessment draft</h1>
      <dl className="mt-8 grid gap-4 rounded-xl border border-slate-700 p-6 sm:grid-cols-3">
        <div><dt className="text-sm text-slate-400">Status</dt><dd>{assessment.status}</dd></div>
        <div><dt className="text-sm text-slate-400">Version</dt><dd>{assessment.version}</dd></div>
        <div><dt className="text-sm text-slate-400">ID</dt><dd className="break-all text-sm">{assessment.id}</dd></div>
      </dl>
      <p className="mt-6 text-slate-300">This is your private, read-only requirements draft. Profile editing is coming next.</p>
      {comparison ? <ComparisonSection comparison={comparison} /> : (
        <section className="mt-10 rounded-xl border border-amber-700 p-6" aria-labelledby="comparison-heading">
          <h2 id="comparison-heading" className="text-xl font-semibold">Synthetic comparison unavailable</h2>
          <p className="mt-2 text-slate-300">Your assessment is still available. Try reloading this page later.</p>
        </section>
      )}
      <details className="mt-6 rounded-xl border border-slate-700 p-6">
        <summary className="cursor-pointer font-medium">Current profile JSON</summary>
        <pre className="mt-4 overflow-x-auto whitespace-pre-wrap break-words text-sm text-slate-300">
          {JSON.stringify(assessment.profile, null, 2)}
        </pre>
      </details>
    </main>
  );
}

function ComparisonSection({ comparison }: { comparison: SyntheticComparisonSummary }) {
  const preferences = comparison.candidates[0]?.capabilityPreferences.length ?? 0;
  return (
    <section className="mt-10" aria-labelledby="comparison-heading">
      <h2 id="comparison-heading" className="text-2xl font-semibold">Synthetic option comparison</h2>
      <p className="mt-3 text-slate-300">These are fictional plans for learning and testing the decision rules, not real provider recommendations. The checks below do not cover every requirement or establish a winner.</p>
      <p className="mt-2 text-sm text-slate-400">Assessment version {comparison.assessmentVersion} · Catalog {comparison.catalogVersion} · Checked {new Date(comparison.evaluatedAt).toLocaleString("en-US", { timeZone: "UTC" })} UTC</p>
      {preferences === 0 && <p className="mt-5 rounded-lg border border-slate-700 p-4 text-slate-300">No capability preferences are recorded in this draft. Weighted scoring is unavailable until preferences can be saved. Profile editing is not enabled yet.</p>}
      <ul className="mt-6 space-y-5">
        {comparison.candidates.map(candidate => <ComparisonCard key={candidate.optionId} candidate={candidate} />)}
      </ul>
      <details className="mt-6 rounded-xl border border-slate-700 p-5 text-sm text-slate-300">
        <summary className="cursor-pointer font-medium">Checks not included in this comparison</summary>
        <p className="mt-3">A passing result applies only to checked constraints. Cost, operations and other listed topics still need review.</p>
        <ul className="mt-2 list-disc space-y-1 pl-5">
          {comparison.deferredPaths.map(path => <li key={path}>{path}</li>)}
        </ul>
      </details>
    </section>
  );
}

function ComparisonCard({ candidate }: { candidate: ComparisonCandidate }) {
  const verdict = {
    EXCLUDED: "Excluded by a checked requirement",
    UNRESOLVED: "Needs more information",
    PASSES_CHECKED_REQUIREMENTS: "Passes checked requirements only",
  }[candidate.hardVerdict];
  return (
    <li className="rounded-xl border border-slate-700 p-6">
      <h3 className="text-xl font-semibold">{candidate.displayName}</h3>
      <p className="mt-1 text-sm text-slate-400">{candidate.plan} · {candidate.region}</p>
      <p className="mt-4 font-medium text-cyan-200">{verdict}</p>
      <FindingList title="Reasons for exclusion" findings={candidate.exclusionReasons} />
      <FindingList title="Information still needed" findings={candidate.informationGaps} />
      {candidate.capabilityPreferences.length > 0 && (
        <div className="mt-5">
          <h4 className="font-medium">Recorded capability preferences</h4>
          <ul className="mt-2 space-y-2 text-sm text-slate-300">
            {candidate.capabilityPreferences.map(preference => (
              <li key={preference.capability} className="rounded-lg bg-slate-800/60 p-3">
                <span className="font-medium">{preference.capability.replaceAll("_", " ")}</span>
                <span className="text-slate-400"> · {preference.outcome.toLowerCase()}</span>
                <p className="mt-1">{preference.explanation}</p>
              </li>
            ))}
          </ul>
          <p className="mt-2 text-sm text-slate-400">Preferences do not override exclusions. No weights or scores are inferred.</p>
        </div>
      )}
    </li>
  );
}

function FindingList({ title, findings }: { title: string; findings: ComparisonFinding[] }) {
  if (findings.length === 0) return null;
  return (
    <div className="mt-5">
      <h4 className="font-medium">{title}</h4>
      <ul className="mt-2 list-disc space-y-2 pl-5 text-sm text-slate-300">
        {findings.map((finding, index) => (
          <li key={`${finding.dimension}-${finding.profilePath}-${finding.reasonCode}-${index}`}>
            {finding.explanation}
            <span className="ml-1 text-slate-400">({finding.reasonCode})</span>
          </li>
        ))}
      </ul>
    </div>
  );
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

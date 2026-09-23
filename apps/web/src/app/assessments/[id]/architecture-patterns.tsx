import type { ArchitecturePatternPreflightSummary, ArchitecturePatternSummary } from "@/lib/auth/core-client";

const statusText: Record<ArchitecturePatternSummary["status"], string> = {
  MATCHES_CHECKED_REQUIREMENTS: "Matches the two checked criteria only",
  NEEDS_INFORMATION: "Needs more information",
  NOT_APPLICABLE: "Client type not selected",
};

export function ArchitecturePatterns({ preview }: { preview: ArchitecturePatternPreflightSummary }) {
  return (
    <section className="mt-10" aria-labelledby="patterns-heading">
      <h2 id="patterns-heading" className="text-2xl font-semibold">Architecture pattern preflight</h2>
      <p className="mt-3 text-slate-300">Compare how five common client patterns handle sign-in and tokens. This check uses only your selected client types and browser-token minimization requirement. It does not verify prerequisites, score patterns or recommend a winner.</p>
      <p className="mt-2 text-sm text-slate-400">Assessment version {preview.assessmentVersion} · Browser token minimization: {preview.browserTokenExposureRequirement.toLowerCase().replaceAll("_", " ")}</p>
      {preview.selectedClients.length === 0 && <p className="mt-4 rounded-lg border border-amber-700 p-4 text-amber-100">
        Select at least one client type in the application context above to assess applicability.
      </p>}
      <ul className="mt-6 space-y-4">
        {preview.patterns.map(pattern => <li key={pattern.patternId} className="rounded-xl border border-slate-700 p-5">
          <h3 className="text-lg font-semibold">{pattern.displayName}</h3>
          <p className="mt-1 text-sm text-slate-400">{pattern.clientType.replaceAll("_", " ").toLowerCase()} · Tokens: {pattern.tokenHandling.replaceAll("_", " ").toLowerCase()}</p>
          <p className="mt-3 font-medium text-cyan-200">{statusText[pattern.status]}</p>
          <ul className="mt-3 space-y-1 text-sm text-slate-300">
            {pattern.checks.map(check => <li key={check.profilePath}>
              {check.explanation} <span className="text-slate-400">({check.reasonCode})</span>
            </li>)}
          </ul>
          <details className="mt-4 text-sm text-slate-300">
            <summary className="cursor-pointer font-medium">Pros, trade-offs and prerequisites</summary>
            <PatternList title="Advantages" items={pattern.advantages} />
            <PatternList title="Trade-offs" items={pattern.tradeoffs} />
            <PatternList title="Prerequisites to verify" items={pattern.prerequisites} />
            <p className="mt-3">Protocol references: {pattern.references.map((reference, index) => <span key={reference}>
              {index > 0 && ", "}<a className="text-cyan-200 underline" href={reference} target="_blank" rel="noopener noreferrer">
                {index + 1}
              </a>
            </span>)}</p>
          </details>
        </li>)}
      </ul>
      <details className="mt-5 rounded-xl border border-slate-700 p-5 text-sm text-slate-300">
        <summary className="cursor-pointer font-medium">What this preflight does not check</summary>
        <p className="mt-3">The pattern’s technical prerequisites and these assessment areas remain unevaluated here:</p>
        <ul className="mt-2 list-disc space-y-1 pl-5">
          {preview.deferredPaths.map(path => <li key={path}>{path}</li>)}
        </ul>
      </details>
    </section>
  );
}

function PatternList({ title, items }: { title: string; items: string[] }) {
  return <div className="mt-3">
    <h4 className="font-medium">{title}</h4>
    <ul className="mt-1 list-disc space-y-1 pl-5">{items.map(item => <li key={item}>{item}</li>)}</ul>
  </div>;
}

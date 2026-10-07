type DesignCheck = {
  fieldId: string;
  label: string;
  outcome: "CONDITIONALLY_SATISFIED" | "CONDITIONALLY_NOT_SATISFIED" | "UNKNOWN" | "NOT_APPLICABLE";
};

/** Presentation of already-checked replies only; never infer a declaration from client scope. */
export function ArchitectureDesignFollowUps({ clientScope, kind, checks }: {
  clientScope: "SELECTED" | "NOT_SELECTED" | "UNKNOWN";
  kind: "conditions" | "settings";
  checks: DesignCheck[];
}) {
  const unmet = checks.filter(check => check.outcome === "CONDITIONALLY_NOT_SATISFIED");
  const unknown = checks.filter(check => check.outcome === "UNKNOWN");
  return <section aria-label="Next steps for this temporary preview" className="mt-4 rounded-lg border border-white/10 p-3 text-sm">
    <h4 className="font-medium">Next steps for this temporary preview</h4>
    {clientScope !== "SELECTED" ? <p className="mt-2 leading-6 text-amber-100">{clientScope === "UNKNOWN"
      ? "Client applicability is unknown, not a missing design answer. Review the saved client types in Context and save before assessing these proposals."
      : "This client type is not selected. Changing proposed conditions or settings cannot make this pattern applicable; review the saved client types in Context."}</p>
      : <>
        {unmet.length === 0 && unknown.length === 0 ? <p className="mt-2 leading-6 text-slate-300">No unmet or unknown {kind} in this temporary preview. This is not verification or an architecture recommendation.</p>
          : <>
            <p className="mt-2 text-xs leading-5 text-slate-400">Revisit only these proposed {kind}. Links return to the exact field; they do not choose an answer. Editing clears this result, so preview again after changing a proposal. Nothing is saved.</p>
            {[{ label: kind === "conditions" ? "Declared not met" : "Does not match the reference setting", rows: unmet },
              { label: "Unknown proposal", rows: unknown }].filter(group => group.rows.length > 0).map(group => <div key={group.label} className="mt-3">
                <p className="font-medium text-amber-100">{group.label} ({group.rows.length})</p>
                <ul className="mt-2 list-disc space-y-2 pl-5">{group.rows.map(check => <li key={check.fieldId} className="break-words">
                  <a href={`#${check.fieldId}`} className="text-cyan-100 underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-200">{check.label}</a>
                </li>)}</ul>
              </div>)}
          </>}
      </>}
    <p className="mt-3 text-xs leading-5 text-slate-400">These are temporary, unverified proposals for this pattern only, not tasks, saved requirements, provider compatibility or deployment evidence. The full checks below remain authoritative for this preview.</p>
  </section>;
}

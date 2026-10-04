"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { usagePlanningFormIssues, type UsageFormIssue } from "@/lib/assessment/usage-form-validation";

export function UsagePlanningForm({ action, children }: { action: string; children: ReactNode }) {
  const [issues, setIssues] = useState<UsageFormIssue[]>([]);
  const summary = useRef<HTMLDivElement>(null);
  useEffect(() => { if (issues.length > 0) summary.current?.focus(); }, [issues]);

  return <form action={action} method="post" className="mt-6 space-y-6"
    onChange={() => setIssues([])} onSubmit={event => {
      const params = new URLSearchParams();
      for (const [name, value] of new FormData(event.currentTarget)) {
        if (typeof value === "string") params.append(name, value);
      }
      const found = usagePlanningFormIssues(params);
      setIssues(found);
      if (found.length > 0) event.preventDefault();
    }}>
    {issues.length > 0 && <div ref={summary} role="alert" tabIndex={-1}
      aria-labelledby="usage-form-errors-heading"
      className="rounded-xl border border-amber-700 p-4 text-sm leading-6 text-amber-100 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-amber-200">
      <h3 id="usage-form-errors-heading" className="font-semibold">Check these inputs before saving</h3>
      <p className="mt-2">Nothing was sent. Your edits are still in this form; correcting them does not save automatically.</p>
      <ul className="mt-3 list-disc space-y-2 pl-5">{issues.map((issue, index) => <li key={index}>
        {issue.fieldId ? <a href={`#${issue.fieldId}`} className="block rounded underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-200"
          onClick={event => {
            event.preventDefault();
            const field = event.currentTarget.closest("form")?.querySelector<HTMLElement>(`#${issue.fieldId}`);
            const details = field?.closest("details");
            if (details) details.open = true;
            field?.focus();
          }}>{issue.message}</a> : issue.message}
      </li>)}</ul>
    </div>}
    {children}
  </form>;
}

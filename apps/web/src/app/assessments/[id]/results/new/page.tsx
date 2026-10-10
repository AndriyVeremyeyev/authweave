import { randomUUID } from "node:crypto";
import Link from "next/link";
import { cookies } from "next/headers";
import { notFound, redirect } from "next/navigation";
import { resultUuid } from "@/lib/assessment/decision-results";
import { preferredCapabilities } from "@/lib/assessment/weights";
import { authConfiguration } from "@/lib/auth/config";
import { readPersonalAssessment, readPersonalDecisionHistory } from "@/lib/auth/core-client";
import { sessionCookieName } from "@/lib/auth/session-policy";
import { touchSession, type BrowserSession } from "@/lib/auth/store";
import { ResultUnavailable } from "../result-history";
import { DecisionRecordingForm } from "./recording-form";

export const runtime = "nodejs";
export default async function NewResultPage({ params, searchParams }: PageProps<"/assessments/[id]/results/new">) {
  const { id } = await params; if (!resultUuid.test(id)) notFound();
  let session: BrowserSession | null;
  try {
    const config = authConfiguration();
    session = await touchSession((await cookies()).get(sessionCookieName(config.secureCookies))?.value);
  } catch { return <ResultUnavailable assessmentId={id} />; }
  if (!session) redirect("/account");
  if (Object.keys(await searchParams).length) return <ResultUnavailable assessmentId={id} invalid />;
  let assessment, history;
  try { [assessment, history] = await Promise.all([readPersonalAssessment(session, id), readPersonalDecisionHistory(session, id)]); }
  catch { return <ResultUnavailable assessmentId={id} />; }
  if (!assessment || !history) notFound();
  const preferred = preferredCapabilities(assessment.profile);
  if (preferred === null) return <ResultUnavailable assessmentId={id} />;
  return <main className="mx-auto max-w-3xl px-5 py-10 text-slate-100 sm:px-8">
    <Link prefetch={false} href={`/assessments/${id}/results`} className="text-sm text-cyan-200 hover:underline">← Calculation history</Link>
    <h1 className="mt-6 text-3xl font-semibold">{history.items.length ? "Record an explicit re-evaluation" : "Record a decision calculation"}</h1>
    {assessment.status === "ARCHIVED" ? <p role="alert" className="mt-6 text-sm text-slate-300">This assessment is archived. Its history remains readable; no new calculation can be recorded.</p> :
      <DecisionRecordingForm assessmentId={id} assessmentVersion={assessment.version} resultId={randomUUID()} previous={history.items[0] ?? null} preferred={preferred} />}
  </main>;
}

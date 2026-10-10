import Link from "next/link";
import { cookies } from "next/headers";
import { notFound, redirect } from "next/navigation";
import { resultQueryReference, resultUuid } from "@/lib/assessment/decision-results";
import type { ResultAdvice } from "@/lib/assessment/decision-advice";
import { authConfiguration } from "@/lib/auth/config";
import { readPersonalDecisionAdvice } from "@/lib/auth/core-client";
import { sessionCookieName } from "@/lib/auth/session-policy";
import { touchSession, type BrowserSession } from "@/lib/auth/store";
import { ResultSummaryView, ResultUnavailable } from "../result-history";
import { ResultAdviceView } from "../result-advice";

export const runtime = "nodejs";
export default async function SavedResultPage({ params, searchParams }: PageProps<"/assessments/[id]/results/[resultId]">) {
  const { id, resultId } = await params;
  if (!resultUuid.test(id) || !resultUuid.test(resultId)) notFound();
  let session: BrowserSession | null;
  try {
    const config = authConfiguration();
    session = await touchSession((await cookies()).get(sessionCookieName(config.secureCookies))?.value);
  } catch { return <ResultUnavailable assessmentId={id} />; }
  if (!session) redirect("/account");
  let reference;
  try { reference = resultQueryReference(await searchParams, false, resultId); }
  catch { return <ResultUnavailable assessmentId={id} invalid />; }
  let advice: ResultAdvice | null;
  try { advice = await readPersonalDecisionAdvice(session, id, reference!); }
  catch { return <ResultUnavailable assessmentId={id} />; }
  if (!advice) notFound();
  return <main className="mx-auto max-w-5xl px-5 py-10 text-slate-100 sm:px-8">
    <Link prefetch={false} href={`/assessments/${id}/results`} className="text-sm text-cyan-200 hover:underline">← Saved calculation history</Link>
    <h1 className="mt-6 text-3xl font-semibold">Historical decision advice</h1>
    <ResultSummaryView summary={advice.summary} />
    <ResultAdviceView advice={advice} />
  </main>;
}

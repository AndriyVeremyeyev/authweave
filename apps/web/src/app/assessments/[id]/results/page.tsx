import Link from "next/link";
import { cookies } from "next/headers";
import { notFound, redirect } from "next/navigation";
import { resultQueryReference, resultUuid, type ResultPage } from "@/lib/assessment/decision-results";
import { authConfiguration } from "@/lib/auth/config";
import { readPersonalDecisionHistory } from "@/lib/auth/core-client";
import { sessionCookieName } from "@/lib/auth/session-policy";
import { touchSession, type BrowserSession } from "@/lib/auth/store";
import { ResultHistory, ResultUnavailable } from "./result-history";

export const runtime = "nodejs";
export default async function ResultHistoryPage({ params, searchParams }: PageProps<"/assessments/[id]/results">) {
  const { id } = await params;
  if (!resultUuid.test(id)) notFound();
  let session: BrowserSession | null;
  try {
    const config = authConfiguration();
    session = await touchSession((await cookies()).get(sessionCookieName(config.secureCookies))?.value);
  } catch { return <ResultUnavailable assessmentId={id} />; }
  if (!session) redirect("/account");
  let before;
  try { before = resultQueryReference(await searchParams, true); }
  catch { return <ResultUnavailable assessmentId={id} invalid />; }
  let page: ResultPage | null;
  try { page = await readPersonalDecisionHistory(session, id, before); }
  catch { return <ResultUnavailable assessmentId={id} />; }
  if (!page) notFound();
  return <main className="mx-auto max-w-5xl px-5 py-10 text-slate-100 sm:px-8">
    <Link href={`/assessments/${id}?step=review`} className="text-sm text-cyan-200 hover:underline">← Review saved requirements</Link>
    <h1 className="mt-6 text-3xl font-semibold">Decision calculation history</h1>
    <Link prefetch={false} href={`/assessments/${id}/results/new`} className="mt-5 inline-block rounded-lg border border-cyan-300/30 px-4 py-2 text-sm text-cyan-200 hover:bg-cyan-300/10">Prepare an explicit calculation</Link>
    <ResultHistory page={page} paginated={before !== null} />
  </main>;
}

import Link from "next/link";
import { cookies } from "next/headers";
import { notFound, redirect } from "next/navigation";

import { authConfiguration } from "@/lib/auth/config";
import { readPersonalAssessment, type PersonalAssessment } from "@/lib/auth/core-client";
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
      <details className="mt-6 rounded-xl border border-slate-700 p-6">
        <summary className="cursor-pointer font-medium">Current profile JSON</summary>
        <pre className="mt-4 overflow-x-auto whitespace-pre-wrap break-words text-sm text-slate-300">
          {JSON.stringify(assessment.profile, null, 2)}
        </pre>
      </details>
    </main>
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

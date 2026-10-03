import Link from "next/link";
import { cookies } from "next/headers";
import { notFound, redirect } from "next/navigation";

import { authConfiguration } from "@/lib/auth/config";
import { listPersonalAssessments, type PersonalAssessmentListPage } from "@/lib/auth/core-client";
import { sessionCookieName } from "@/lib/auth/session-policy";
import { touchSession, type BrowserSession } from "@/lib/auth/store";
import { AssessmentList } from "./assessment-list";

export const runtime = "nodejs";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export default async function AssessmentsPage({ searchParams }: PageProps<"/assessments">) {
  const query = await searchParams;
  const before = query.before;
  if (before !== undefined && (typeof before !== "string" || !UUID.test(before))) notFound();

  let session: BrowserSession | null;
  try {
    const config = authConfiguration();
    const id = (await cookies()).get(sessionCookieName(config.secureCookies))?.value;
    session = await touchSession(id);
  } catch {
    return <Unavailable />;
  }
  if (!session) redirect("/account");

  let page: PersonalAssessmentListPage;
  try {
    page = await listPersonalAssessments(session, before);
  } catch {
    return <Unavailable />;
  }

  return <AssessmentList page={page} paginated={before !== undefined} />;
}

function Unavailable() {
  return (
    <main className="mx-auto max-w-2xl px-6 py-20 text-slate-100">
      <Link href="/account" className="text-sm text-cyan-200 hover:underline">← Account</Link>
      <h1 className="mt-8 text-3xl font-semibold">Assessments unavailable</h1>
      <p className="mt-4 text-slate-300">Please try again later.</p>
    </main>
  );
}

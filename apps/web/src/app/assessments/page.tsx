import Link from "next/link";
import { cookies } from "next/headers";
import { notFound, redirect } from "next/navigation";

import { authConfiguration } from "@/lib/auth/config";
import { listPersonalAssessments, type PersonalAssessmentListPage } from "@/lib/auth/core-client";
import { sessionCookieName } from "@/lib/auth/session-policy";
import { touchSession, type BrowserSession } from "@/lib/auth/store";

export const runtime = "nodejs";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

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

  return (
    <main className="mx-auto max-w-3xl px-6 py-20 text-slate-100">
      <Link href="/account" className="text-sm text-cyan-200 hover:underline">← Account</Link>
      <h1 className="mt-8 text-4xl font-semibold">Your assessments</h1>
      <p className="mt-4 text-slate-300">Only assessments in your personal workspace appear here.</p>
      {page.items.length === 0 ? (
        <p className="mt-8 rounded-xl border border-slate-700 p-6 text-slate-300">No assessments on this page.</p>
      ) : (
        <ul className="mt-8 space-y-3">
          {page.items.map((item) => (
            <li key={item.id}>
              <Link href={`/assessments/${item.id}`}
                className="block rounded-xl border border-slate-700 p-5 hover:border-cyan-300">
                <span className="font-medium">{item.status}</span>
                <span className="ml-3 text-sm text-slate-400">Version {item.version}</span>
                <span className="mt-2 block break-all text-sm text-slate-400">{item.id}</span>
                <span className="mt-2 block text-sm text-slate-400">Created {new Date(item.createdAt).toLocaleString("en-US", { timeZone: "UTC" })} UTC</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
      {page.nextBeforeId && (
        <Link href={`/assessments?before=${page.nextBeforeId}`}
          className="mt-8 inline-block text-cyan-200 hover:underline">Older assessments →</Link>
      )}
    </main>
  );
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

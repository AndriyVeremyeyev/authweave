import Link from "next/link";
import { cookies } from "next/headers";

import { authConfiguration } from "@/lib/auth/config";
import { touchSession } from "@/lib/auth/store";
import { sessionCookieName } from "@/lib/auth/session-policy";

export const runtime = "nodejs";

export default async function AccountPage() {
  let unavailable = false;
  let session = null;
  try {
    const config = authConfiguration();
    const id = (await cookies()).get(sessionCookieName(config.secureCookies))?.value;
    session = await touchSession(id);
  } catch {
    unavailable = true;
  }

  return (
    <main className="mx-auto max-w-2xl px-6 py-20 text-slate-100">
      <Link href="/" className="text-sm text-cyan-200 hover:underline">← AuthWeave</Link>
      <h1 className="mt-8 text-4xl font-semibold">Account</h1>
      {unavailable ? (
        <p className="mt-6 text-amber-200">Authentication is temporarily unavailable.</p>
      ) : session ? (
        <div className="mt-6 space-y-5">
          <p>Signed in as {session.displayName ?? session.email ?? session.subject}.</p>
          <p className="text-sm text-slate-400">Your personal workspace is ready. Create and edit a private assessment draft to begin. Catalog curator permissions are not enabled yet.</p>
          <p className="text-sm text-slate-400">Last identity verification: <time dateTime={session.authenticatedAt.toISOString()}>{session.authenticatedAt.toISOString()}</time></p>
          <form method="post" action="/api/auth/reauth">
            <button className="rounded-lg border border-slate-500 px-4 py-2 font-medium text-slate-100">Verify this account again</button>
          </form>
          <p className="text-xs text-slate-400">This checks the same account again and rotates your session. It does not grant catalog access.</p>
          <form method="post" action="/api/assessments">
            <button className="rounded-lg bg-cyan-300 px-4 py-2 font-medium text-slate-950">Create assessment draft</button>
          </form>
          <Link href="/assessments" className="inline-block text-cyan-200 hover:underline">View your assessments →</Link>
          <form method="post" action="/api/auth/logout">
            <button className="rounded-lg bg-white px-4 py-2 font-medium text-slate-950">Sign out</button>
          </form>
        </div>
      ) : (
        <div className="mt-6 space-y-5">
          <p>You are not signed in. The public requirements preview remains available.</p>
          <form method="post" action="/api/auth/start">
            <button className="rounded-lg bg-white px-4 py-2 font-medium text-slate-950">Sign in with ZITADEL</button>
          </form>
        </div>
      )}
    </main>
  );
}

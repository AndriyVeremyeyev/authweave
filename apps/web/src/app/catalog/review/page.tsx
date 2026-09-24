import Link from "next/link";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";

import { authConfiguration } from "@/lib/auth/config";
import { readCuratorAuthorization, type CuratorProbeStatus } from "@/lib/auth/core-client";
import { sessionCookieName } from "@/lib/auth/session-policy";
import { touchSession } from "@/lib/auth/store";

export const runtime = "nodejs";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const unavailable: Record<Exclude<CuratorProbeStatus, "ready">, string> = {
  "not-configured": "Catalog curator scope is not configured for this local environment.",
  "not-granted": "This account does not have the scoped AuthWeave catalog curator role.",
  "reauth-required": "This sensitive action requires a new verification of the same account.",
  "core-rejected": "Core rejected the curator assertion. No proposal data was read.",
  "core-unavailable": "Core curator verification is temporarily unavailable.",
};

export default async function CatalogReviewIndex({ searchParams }: PageProps<"/catalog/review">) {
  let status: CuratorProbeStatus = "core-unavailable";
  let anonymous = false;
  try {
    const config = authConfiguration();
    const sessionId = (await cookies()).get(sessionCookieName(config.secureCookies))?.value;
    const session = await touchSession(sessionId);
    if (!session) anonymous = true;
    else status = await readCuratorAuthorization(session, config);
  } catch { /* Fail closed without treating service errors as anonymous. */ }
  if (anonymous) redirect("/account");
  const query = await searchParams;
  const requestedId = typeof query.proposalId === "string" ? query.proposalId.trim().toLowerCase() : "";
  return (
    <main className="mx-auto max-w-3xl px-6 py-20 text-slate-100">
      <Link href="/account" className="text-sm text-cyan-200 hover:underline">← Account</Link>
      <h1 className="mt-8 text-4xl font-semibold">Catalog proposal review</h1>
      <p className="mt-5 text-slate-300">Review a stored proposal by its UUID. Proposals are still created by an explicit local command; there is no shared queue or publication workflow yet.</p>
      {status !== "ready" ? (
        <section className="mt-8 rounded-xl border border-amber-700 p-6" aria-label="Curator access unavailable">
          <p className="text-amber-100">{unavailable[status]}</p>
          {status === "reauth-required" && <form action="/api/auth/reauth" method="post" className="mt-5">
            <button className="rounded-lg bg-cyan-300 px-4 py-2 font-semibold text-slate-950">Verify this account again</button>
          </form>}
        </section>
      ) : (
        <section className="mt-8 rounded-xl border border-slate-700 p-6">
          <h2 className="text-xl font-semibold">Open a proposal</h2>
          <form action="/catalog/review" method="get" className="mt-4 flex flex-col gap-3 sm:flex-row">
            <label htmlFor="proposalId" className="sr-only">Proposal UUID</label>
            <input id="proposalId" name="proposalId" required maxLength={36} placeholder="Proposal UUID"
              defaultValue={requestedId} className="min-w-0 flex-1 rounded-lg border border-slate-500 bg-slate-900 px-3 py-2" />
            <button className="rounded-lg bg-cyan-300 px-4 py-2 font-semibold text-slate-950">Find</button>
          </form>
          {requestedId && !UUID.test(requestedId) &&
            <p role="alert" className="mt-4 text-amber-200">Enter a valid proposal UUID.</p>}
          {UUID.test(requestedId) && <Link href={`/catalog/review/${requestedId}`}
            className="mt-4 inline-block text-cyan-200 hover:underline">Open proposal {requestedId} →</Link>}
        </section>
      )}
      <p className="mt-8 text-sm text-slate-400">All displayed provider evidence remains unreviewed. This screen can record a rejection only; it cannot approve or publish a catalog.</p>
    </main>
  );
}

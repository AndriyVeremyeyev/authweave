import Link from "next/link";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { authConfiguration } from "@/lib/auth/config";
import { readCuratorAuthorization, readCatalogBootstrapReview, readCatalogPublicationPreflight,
  type BootstrapReadResult, type PublicationReviewResult } from "@/lib/auth/core-client";
import { sessionCookieName } from "@/lib/auth/session-policy";
import { touchSession } from "@/lib/auth/store";
import BootstrapReviewForm from "./review-form";
import PublicationPreflight from "../publication-preflight";

export const runtime = "nodejs";
const messages: Record<string, string> = {
  "not-configured": "Catalog curator scope is not configured.",
  "not-granted": "This account does not have the scoped AuthWeave catalog curator role.",
  "reauth-required": "Verify the same account again before this sensitive review.",
  "core-rejected": "Core rejected the curator assertion.",
  "core-unavailable": "Core curator verification or receipt reading is unavailable.",
  invalid: "Use one exact review UUID and its complete review SHA-256, not the candidate digest.",
  "not-found": "This exact review was not found.",
  conflict: "The review digest or stored integrity check does not match. No receipt was released.",
};
export default async function BootstrapReviewPage({ searchParams }: PageProps<"/catalog/bootstrap">) {
  const query = await searchParams;
  const requested = query.reviewId !== undefined || query.expectedSha256 !== undefined;
  let anonymous = false;
  let status = "core-unavailable";
  let result: BootstrapReadResult | null = null;
  let publication: PublicationReviewResult | null = null;
  try {
    const config = authConfiguration();
    const values = (await cookies()).getAll(sessionCookieName(config.secureCookies));
    const session = values.length === 1 ? await touchSession(values[0].value) : null;
    if (!session) anonymous = true;
    else if (requested) {
      result = await readCatalogBootstrapReview(session, config, query.reviewId, query.expectedSha256);
      status = result.kind;
      if (result.kind === "ready") publication = await readCatalogPublicationPreflight(session, config, {
        mode: "CURATED_BOOTSTRAP", inputId: result.receipt.reviewId, inputVersion: null, inputSha256: result.receipt.reviewSha256,
      });
    } else status = await readCuratorAuthorization(session, config);
  } catch { /* Service errors are not anonymous access or permission to review. */ }
  if (anonymous) redirect("/account");
  return <main className="mx-auto max-w-4xl px-6 py-20 text-slate-100">
    <Link href="/catalog/review" className="text-sm text-cyan-200 hover:underline">← Catalog review</Link>
    <h1 className="mt-8 text-4xl font-semibold">Bootstrap source review</h1>
    <p className="mt-5 text-slate-300">Manually review the first catalog candidate while the publication registry is empty. This separate workflow records human conclusions only; it does not establish a trusted baseline or enable publication.</p>
    {status !== "ready" ? <section className="mt-8 rounded-xl border border-amber-700 p-6" aria-label="Bootstrap review unavailable">
      <p className="text-amber-100">{messages[status] ?? messages["core-unavailable"]}</p>
      {status === "reauth-required" && <form action="/api/auth/reauth" method="post" className="mt-5">
        <button className="rounded-lg bg-cyan-300 px-4 py-2 font-semibold text-slate-950">Verify this account again</button>
      </form>}
    </section> : result?.kind === "ready" ? <section className="mt-8 rounded-xl border border-slate-700 p-6" aria-label="Exact stored bootstrap receipt">
      <h2 className="text-xl font-semibold">Exact stored manual review</h2>
      <p className="mt-4">Catalog: {result.receipt.catalogVersion} · {result.receipt.factCount} facts · Recorded {result.receipt.recordedAt}</p>
      <p className="mt-3">Supporting: {result.receipt.counts.supporting} · Contradicting: {result.receipt.counts.contradicting} · Insufficient: {result.receipt.counts.insufficient}</p>
      <p className="mt-4 break-all text-sm text-slate-400">Review UUID: {result.receipt.reviewId}<br />Candidate draft SHA-256: {result.receipt.candidateSha256}<br />Complete review SHA-256: {result.receipt.reviewSha256}</p>
      <p className="mt-4 text-slate-300">This receipt contains no actor, source bodies or candidate. It does not prove current source freshness, full impact coverage, source truth or permission to publish. No source verification, trust promotion, approval or catalog writes were performed.</p>
    </section> : <BootstrapReviewForm />}
    {result?.kind === "ready" && <PublicationPreflight result={publication} />}
    <p className="mt-8 text-sm text-slate-400">Publication remains blocked pending complete impact coverage and an authenticated atomic publication workflow.</p>
  </main>;
}

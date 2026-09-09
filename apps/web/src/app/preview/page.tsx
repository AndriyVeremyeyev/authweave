import type { Metadata } from "next";
import Link from "next/link";
import Workspace from "./workspace";

export const metadata: Metadata = {
  title: "Requirements preview · AuthWeave",
  description: "Explore a B2B SaaS example, capture identity requirements and download a draft brief. An early AuthWeave preview.",
};

export default function PreviewPage() {
  return (
    <div className="min-h-screen bg-[var(--background)] text-[var(--foreground)]">
      <header className="border-b border-white/10">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-6 py-5 lg:px-8">
          <Link className="flex items-center gap-3 rounded-lg focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-cyan-200" href="/" aria-label="AuthWeave home">
            <span aria-hidden="true" className="grid size-9 place-items-center rounded-xl bg-[var(--accent)] text-sm font-black text-slate-950 shadow-[0_0_32px_var(--accent-glow)]">A</span>
            <span className="text-lg font-semibold tracking-tight">AuthWeave</span>
          </Link>
          <span className="rounded-full border border-emerald-300/20 bg-emerald-300/10 px-3 py-1 text-xs font-medium text-emerald-200">Early preview</span>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-6 py-10 lg:px-8 lg:py-14">
        <div className="mb-8 max-w-3xl">
          <p className="text-xs font-semibold uppercase tracking-[0.18em] text-[var(--accent)]">From requirements to a useful starting point</p>
          <h1 className="mt-3 text-3xl font-semibold tracking-tight sm:text-4xl">Your identity requirements, made explicit.</h1>
          <p className="mt-4 text-base leading-7 text-[var(--muted)]">Explore a fictional B2B SaaS example, adapt the requirements and download a draft to discuss with your team. Provider recommendations and architecture decisions are still in development.</p>
        </div>
        <noscript><p className="mb-6 rounded-xl border border-amber-200/30 p-4 text-amber-100">This browser preview needs JavaScript to edit and download requirements.</p></noscript>
        <Workspace />
      </main>
      <footer className="mx-auto flex max-w-6xl flex-wrap justify-between gap-3 px-6 py-8 text-sm text-[var(--muted)] lg:px-8">
        <Link className="hover:text-white" href="/">← About AuthWeave</Link>
        <a className="hover:text-white" href="https://github.com/AndriyVeremyeyev/authweave">Source & development on GitHub ↗</a>
      </footer>
    </div>
  );
}

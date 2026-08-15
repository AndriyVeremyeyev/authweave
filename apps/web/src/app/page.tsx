export default function Home() {
  return (
    <div className="min-h-screen bg-[var(--background)] text-[var(--foreground)]">
      <header className="border-b border-white/10">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-6 py-5 lg:px-8">
          <a className="flex items-center gap-3" href="#top" aria-label="AuthWeave home">
            <span
              aria-hidden="true"
              className="grid size-9 place-items-center rounded-xl bg-[var(--accent)] text-sm font-black text-slate-950 shadow-[0_0_32px_var(--accent-glow)]"
            >
              A
            </span>
            <span className="text-lg font-semibold tracking-tight">AuthWeave</span>
          </a>
          <span className="rounded-full border border-emerald-300/20 bg-emerald-300/10 px-3 py-1 text-xs font-medium text-emerald-200">
            Foundation in progress
          </span>
        </div>
      </header>

      <main id="top">
        <section className="mx-auto grid max-w-6xl gap-14 px-6 py-20 lg:grid-cols-[1.15fr_0.85fr] lg:px-8 lg:py-28">
          <div className="flex flex-col justify-center">
            <p className="mb-5 text-sm font-semibold uppercase tracking-[0.2em] text-[var(--accent)]">
              Identity architecture decision workspace
            </p>
            <h1 className="max-w-3xl text-5xl font-semibold leading-[1.05] tracking-[-0.04em] sm:text-6xl">
              Make authentication decisions with evidence, not guesswork.
            </h1>
            <p className="mt-7 max-w-2xl text-lg leading-8 text-[var(--muted)]">
              AuthWeave will turn application requirements into reviewable identity architecture
              options, deterministic trade-offs, and source-backed recommendations.
            </p>
            <div className="mt-10 flex flex-wrap items-center gap-4">
              <span className="rounded-xl bg-white px-5 py-3 text-sm font-semibold text-slate-950">
                Decision workspace coming next
              </span>
              <a
                className="text-sm font-semibold text-slate-300 transition hover:text-white"
                href="#principles"
              >
                Explore the foundation <span aria-hidden="true">↓</span>
              </a>
            </div>
          </div>

          <aside className="relative overflow-hidden rounded-3xl border border-white/10 bg-white/[0.045] p-7 shadow-2xl shadow-black/30">
            <div className="absolute -right-20 -top-20 size-56 rounded-full bg-cyan-300/10 blur-3xl" />
            <div className="relative">
              <div className="flex items-center justify-between border-b border-white/10 pb-5">
                <div>
                  <p className="text-xs uppercase tracking-[0.18em] text-[var(--muted)]">
                    Local foundation
                  </p>
                  <h2 className="mt-2 text-xl font-semibold">System status</h2>
                </div>
                <span className="size-2.5 rounded-full bg-emerald-300 shadow-[0_0_16px_#6ee7b7]" />
              </div>
              <dl className="mt-6 space-y-4 text-sm">
                <StatusRow label="Web and BFF shell" value="Implemented" />
                <StatusRow label="Deterministic core API" value="Implemented" />
                <StatusRow label="AI operation worker" value="Planned" muted />
                <StatusRow label="Provider catalog" value="Planned" muted />
              </dl>
              <p className="mt-7 rounded-2xl border border-amber-200/10 bg-amber-100/[0.04] p-4 text-sm leading-6 text-amber-50/75">
                Current status is explicit: this is a development foundation, not a production
                release or a completed recommendation engine.
              </p>
            </div>
          </aside>
        </section>

        <section id="principles" className="border-y border-white/10 bg-black/10">
          <div className="mx-auto max-w-6xl px-6 py-16 lg:px-8">
            <div className="mb-10 max-w-2xl">
              <p className="text-sm font-semibold uppercase tracking-[0.2em] text-[var(--accent)]">
                Decision principles
              </p>
              <h2 className="mt-3 text-3xl font-semibold tracking-tight">
                Explainable by construction
              </h2>
            </div>
            <div className="grid gap-5 md:grid-cols-3">
              <Principle
                number="01"
                title="Deterministic first"
                description="Hard constraints and scoring remain testable code, not model opinion."
              />
              <Principle
                number="02"
                title="Evidence attached"
                description="Provider facts carry sources, observation dates, and review status."
              />
              <Principle
                number="03"
                title="Human owned"
                description="AI proposes and explains; a person accepts every consequential decision."
              />
            </div>
          </div>
        </section>
      </main>

      <footer className="mx-auto flex max-w-6xl flex-col gap-2 px-6 py-8 text-sm text-[var(--muted)] sm:flex-row sm:items-center sm:justify-between lg:px-8">
        <span>AuthWeave · Local-first development</span>
        <span>No production release available</span>
      </footer>
    </div>
  );
}

function StatusRow({
  label,
  value,
  muted = false,
}: {
  label: string;
  value: string;
  muted?: boolean;
}) {
  return (
    <div className="flex items-center justify-between gap-6">
      <dt className="text-slate-300">{label}</dt>
      <dd className={muted ? "text-slate-500" : "font-medium text-emerald-200"}>{value}</dd>
    </div>
  );
}

function Principle({
  number,
  title,
  description,
}: {
  number: string;
  title: string;
  description: string;
}) {
  return (
    <article className="rounded-2xl border border-white/10 bg-white/[0.035] p-6">
      <span className="text-xs font-semibold tracking-[0.18em] text-[var(--accent)]">{number}</span>
      <h3 className="mt-5 text-lg font-semibold">{title}</h3>
      <p className="mt-3 text-sm leading-6 text-[var(--muted)]">{description}</p>
    </article>
  );
}

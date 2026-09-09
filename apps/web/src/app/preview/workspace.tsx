"use client";

import { useRef, useState } from "react";
import { allFields, blankProfile, displayValue, exampleProfile, getValue, jsonBrief, markdownBrief, openFields, sections, setValue, structuralIssues, valueLabel, type Field, type Profile } from "@/lib/preview/model";
import styles from "./workspace.module.css";

export default function Workspace() {
  const [profile, setProfile] = useState<Profile>(exampleProfile);
  const [step, setStep] = useState(0);
  const [review, setReview] = useState(false);
  const [pending, setPending] = useState<"example" | "clear" | null>(null);
  const [edited, setEdited] = useState(false);
  const [origin, setOrigin] = useState<"example" | "clear">("example");
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const heading = useRef<HTMLHeadingElement>(null);
  const confirmation = useRef<HTMLDivElement>(null);
  const sourceAction = useRef<HTMLButtonElement | null>(null);
  const section = sections[step];
  const open = openFields(profile);
  const issues = structuralIssues(profile);

  function focusHeading() {
    requestAnimationFrame(() => heading.current?.focus());
  }
  function navigate(index: number, toReview = false) {
    setStep(index);
    setReview(toReview);
    setMessage("");
    setError("");
    focusHeading();
  }
  function update(field: Field, value: string | string[]) {
    setProfile(current => setValue(current, field.path, value));
    setEdited(true);
    setMessage("");
    setError("");
  }
  function replace(kind: "example" | "clear") {
    setProfile(kind === "example" ? exampleProfile() : blankProfile());
    setEdited(false);
    setOrigin(kind);
    setPending(null);
    setError("");
    setMessage(kind === "example" ? "Fictional B2B SaaS example loaded." : "Answers cleared. Start with what you know.");
    setStep(0);
    setReview(false);
    focusHeading();
  }
  function requestReplace(kind: "example" | "clear", button: HTMLButtonElement) {
    if (!edited && kind === "example") { replace(kind); return; }
    sourceAction.current = button;
    setPending(kind);
    requestAnimationFrame(() => confirmation.current?.focus());
  }
  function download(format: "json" | "markdown") {
    try {
      const content = format === "json" ? jsonBrief(profile) : markdownBrief(profile);
      const blob = new Blob([content], { type: format === "json" ? "application/json;charset=utf-8" : "text/markdown;charset=utf-8" });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = format === "json" ? "authweave-profile-draft.json" : "authweave-requirements-draft.md";
      document.body.append(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 30_000);
      setMessage(`${format === "json" ? "JSON profile" : "Markdown brief"} download requested. Check your browser's downloads.`);
      setError("");
    } catch {
      setError("The download could not be created. Please try again in a browser that supports file downloads.");
    }
  }

  return (
    <div className={styles.workspace}>
      <div className={styles.notice}>
        <strong>Private to this tab.</strong> Answers are held in memory and are not sent to AuthWeave or saved for later. Download your brief before leaving or refreshing this workspace.
      </div>
      <div className={styles.toolbar}>
        <span className={styles.muted}>{origin === "example" ? "Starting point: fictional B2B SaaS" : "Starting point: blank draft"} · All answers are editable</span>
        <div className={styles.actions}>
          <button className={styles.secondary} type="button" onClick={event => requestReplace("example", event.currentTarget)}>Load example</button>
          <button className={styles.secondary} type="button" onClick={event => requestReplace("clear", event.currentTarget)}>Clear answers</button>
        </div>
      </div>
      {pending && (
        <div className={styles.confirmation} ref={confirmation} tabIndex={-1} role="group" aria-labelledby="replace-title">
          <h2 id="replace-title">{pending === "clear" ? "Clear all answers?" : "Replace answers with the example?"}</h2>
          <p>Your current answers will be replaced. Download a brief first if you want to keep them.</p>
          <div className={styles.actions}>
            <button type="button" className={styles.secondary} onClick={() => { setPending(null); sourceAction.current?.focus(); }}>Keep editing</button>
            <button type="button" className={styles.primary} onClick={() => replace(pending)}>{pending === "clear" ? "Clear all answers" : "Load B2B SaaS example"}</button>
          </div>
        </div>
      )}
      <div className={styles.layout}>
        <nav className={styles.navigation} aria-label="Requirements sections">
          <ol>
            {sections.map((item, index) => (
              <li key={item.id}>
                <button type="button" className={styles.step} aria-current={!review && index === step ? "step" : undefined} onClick={() => navigate(index)}>
                  <span className={styles.number}>{String(index + 1).padStart(2, "0")}</span>{item.title}
                </button>
              </li>
            ))}
          </ol>
          <button type="button" className={styles.reviewLink} aria-current={review ? "step" : undefined} onClick={() => navigate(step, true)}>Review & export →</button>
        </nav>
        <section className={styles.panel} aria-labelledby="workspace-heading">
          <div className={styles.panelHeader}>
            <p className={styles.eyebrow}>{review ? "Requirements brief · Draft" : `Section ${step + 1} of ${sections.length}`}</p>
            <h2 id="workspace-heading" ref={heading} tabIndex={-1}>{review ? "Review your starting point" : section.title}</h2>
            <p>{review ? "Review the recorded inputs and open questions before sharing a draft with your team." : section.description}</p>
          </div>
          {issues.length > 0 && <p className={styles.error} role="alert">The profile structure is invalid. Reload the example or clear answers before exporting.</p>}
          {review ? (
            <>
              <div className={styles.reviewNotice}>
                <strong>Requirements only.</strong> This preview checks structure. It does not check contradictions between answers, compare providers or verify compliance. A brief with no open fields can still need substantial review.
              </div>
              <div className={styles.exportButtons}>
                <button type="button" className={styles.primary} disabled={issues.length > 0} onClick={() => download("markdown")}>Download Markdown</button>
                <button type="button" className={styles.secondary} disabled={issues.length > 0} onClick={() => download("json")}>Download JSON</button>
              </div>
              <p className={styles.exportHelp}>Markdown includes the draft context and open questions. JSON contains the profile in AuthWeave&apos;s shared format.</p>
              <div className={styles.openQuestions}>
                <h3>{open.length ? `${open.length} open or unrecorded fields` : "No unknown or unrecorded fields"}</h3>
                {open.length > 0 ? (
                  <ul>{open.map(field => (
                    <li key={field.path}>
                      <button type="button" onClick={() => navigate(sections.findIndex(item => item.fields.some(entry => entry.path === field.path)))}>{field.label}</button>
                      <span> · {displayValue(profile, field)}</span>
                    </li>
                  ))}</ul>
                ) : <p>This is a record of your inputs, not confirmation that the requirements are complete or consistent.</p>}
                <p>Empty selections mean no choice was recorded. They do not mean a topic is unnecessary.</p>
              </div>
              {sections.map((item, index) => (
                <section className={styles.summarySection} key={item.id} aria-labelledby={`summary-${item.id}`}>
                  <div className={styles.summaryTitle}><h3 id={`summary-${item.id}`}>{item.title}</h3><button type="button" onClick={() => navigate(index)} aria-label={`Edit ${item.title.toLowerCase()}`}>Edit</button></div>
                  <dl>{item.fields.map(field => <div key={field.path}><dt>{field.label}</dt><dd>{displayValue(profile, field)}</dd></div>)}</dl>
                </section>
              ))}
            </>
          ) : (
            <>
              {section.fields.some(field => field.criticality) && <p className={styles.legend}><strong>Required</strong> is mandatory; <strong>preferred</strong> is desirable. <strong>Not required</strong> leaves the choice open; <strong>forbidden</strong> excludes it. Choose <strong>unknown</strong> when it needs clarification.</p>}
              <div className={styles.fields}>
                {section.fields.map(field => <ProfileField key={field.path} field={field} profile={profile} onChange={value => update(field, value)} />)}
              </div>
              <div className={styles.stepActions}>
                <button type="button" className={styles.secondary} disabled={step === 0} onClick={() => navigate(step - 1)}>← Previous</button>
                <button type="button" className={styles.primary} onClick={() => step === sections.length - 1 ? navigate(step, true) : navigate(step + 1)}>{step === sections.length - 1 ? "Review & export" : "Next section →"}</button>
              </div>
            </>
          )}
          {error && <p className={styles.error} role="alert">{error}</p>}
          <p className={styles.feedback} role="status">{message}</p>
        </section>
        <aside className={styles.progress} aria-label="Draft progress">
          <p className={styles.eyebrow}>Your draft</p>
          <h2>Capture what you know.</h2>
          <p className={styles.count}>{allFields.length - open.length}<span> / {allFields.length} fields recorded</span></p>
          <p>{open.length} open or unrecorded. Unknown answers are welcome at this stage.</p>
          <div className={styles.progressRule} />
          <p><strong>Available now</strong><br />Requirements, open questions and draft downloads.</p>
          <p><strong>Coming next</strong><br />Validated assessments, provider comparisons and evidence-backed decisions.</p>
          {!review && <button type="button" className={styles.secondary} onClick={() => navigate(step, true)}>Review & export</button>}
        </aside>
      </div>
    </div>
  );
}

function ProfileField({ field, profile, onChange }: { field: Field; profile: Profile; onChange: (value: string | string[]) => void }) {
  const id = `field-${field.path.replaceAll(".", "-")}`;
  const value = getValue(profile, field.path);
  if (field.multiple) {
    const selected = Array.isArray(value) ? value : [];
    return (
      <fieldset className={styles.field} aria-describedby={`${id}-help`}>
        <legend>{field.label}</legend>
        <p id={`${id}-help`} className={styles.fieldHelp}>{field.help}</p>
        <div className={styles.choices}>
          {field.options.map(option => <label key={option}><input type="checkbox" checked={selected.includes(option)} onChange={event => onChange(event.target.checked ? [...selected, option] : selected.filter(item => item !== option))} />{valueLabel(option)}</label>)}
        </div>
        <p className={styles.selectionNote}>{selected.length === 0 ? "No selection recorded yet." : `${selected.length} selected`}</p>
      </fieldset>
    );
  }
  return (
    <div className={styles.field}>
      <label htmlFor={id}>{field.label}</label>
      <p id={`${id}-help`} className={styles.fieldHelp}>{field.help}</p>
      <select id={id} value={typeof value === "string" ? value : "UNKNOWN"} aria-describedby={`${id}-help`} onChange={event => onChange(event.target.value)}>
        {field.options.map(option => <option key={option} value={option}>{valueLabel(option)}</option>)}
      </select>
    </div>
  );
}

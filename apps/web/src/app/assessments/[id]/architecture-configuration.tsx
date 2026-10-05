"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { architectureConfigurationAnalysis, architectureConfigurationDeferred, architectureSettingDefinitions, parseArchitectureConfigurationForm,
  type ArchitectureClientScope, type ArchitectureConfigurationAnalysis, type ArchitectureConfigurationInput, type ArchitectureConfigurationPreview,
  type ArchitectureSettingId, type ArchitectureSettingValue } from "@/lib/assessment/architecture-configuration";
import type { ArchitecturePatternId } from "@/lib/assessment/architecture-prerequisites";

const labels: Record<ArchitectureSettingId, string> = {
  OAUTH_FLOW: "OAuth flow", OAUTH_CLIENT_TYPE: "OAuth client type", CLIENT_AUTHENTICATION: "Client credential custody",
  TOKEN_LOCATION: "OAuth token location", PKCE_METHOD: "PKCE method", REDIRECT_MATCHING: "Redirect matching",
  SESSION_COOKIE_SECURE: "Session cookie: Secure", SESSION_COOKIE_HTTP_ONLY: "Session cookie: HttpOnly", SESSION_CSRF_DEFENSE: "Session CSRF defense",
  RESOURCE_ACCESS: "Primary resource-access path", BROWSER_TOKEN_ENDPOINT_ACCESS: "Browser access to the token endpoint",
  NATIVE_USER_AGENT: "Native authorization user agent", WORKLOAD_AUTHORIZATION: "Workload authorization context",
};
const valueLabels: Record<ArchitectureSettingValue, string> = {
  UNKNOWN: "Unknown / not yet chosen", AUTHORIZATION_CODE: "Authorization code", CLIENT_CREDENTIALS: "Client credentials", IMPLICIT: "Implicit",
  CONFIDENTIAL: "Confidential — can protect credentials", PUBLIC: "Public — cannot protect a shared secret",
  SERVER_HELD_CREDENTIAL: "Credential held by application server", WORKLOAD_HELD_CREDENTIAL: "Credential protected by workload",
  NONE: "None", DISTRIBUTED_SHARED_SECRET: "Shared secret distributed with the app", APPLICATION_SERVER: "Application server",
  BROWSER: "Browser code", NATIVE_APP: "Native app", WORKLOAD: "Workload", S256: "S256 — hashed challenge", PLAIN: "Plain — unhashed challenge",
  EXACT_REGISTERED: "Exact registered redirect", NATIVE_LOOPBACK_IP_LITERAL_PORT_EXCEPTION: "Native loopback IP-literal port exception",
  WILDCARD: "Wildcard redirect matching", ENABLED: "Enabled in proposed design", DISABLED: "Disabled in proposed design",
  DEFENSE_PLANNED: "CSRF defense planned", ABSENT: "No CSRF defense planned", BFF_PROXY: "Browser → BFF → API",
  SESSION_BACKEND: "Browser → session backend", DIRECT_BROWSER: "Browser → API directly", REQUIRED_ORIGINS_PLANNED: "Required browser origins planned",
  BLOCKED: "Browser endpoint access blocked", EXTERNAL_BROWSER: "External browser", EMBEDDED_WEBVIEW: "Embedded webview",
  WORKLOAD_OWN_OR_PREARRANGED: "Workload's own or prearranged resources", USER_DELEGATION: "User-delegated access",
};
function choice(id: ArchitectureSettingId, value: ArchitectureSettingValue) {
  return value === "NONE" ? id === "PKCE_METHOD" ? "No PKCE" : "No client authentication" : valueLabels[value];
}
const statusText: Record<ArchitectureConfigurationAnalysis["status"], string> = {
  CONDITIONALLY_MATCHES: "Proposed settings match this reference design only",
  CONDITIONALLY_DOES_NOT_MATCH: "At least one proposed setting does not match this reference design",
  NEEDS_INFORMATION: "More information is needed about the proposed settings",
  NOT_APPLICABLE: "This client type is not selected in the assessment",
};
const outcomeText: Record<ArchitectureConfigurationAnalysis["checks"][number]["outcome"], string> = {
  CONDITIONALLY_SATISFIED: "Matches this reference setting — unverified", CONDITIONALLY_NOT_SATISFIED: "Does not match this reference setting — unverified",
  UNKNOWN: "Unknown — clarify the setting or saved client scope", NOT_APPLICABLE: "Not applicable to the selected clients",
};

export function ArchitectureConfiguration({ assessmentId, version, patternId, clientScope }: {
  assessmentId: string; version: number; patternId: ArchitecturePatternId; clientScope: ArchitectureClientScope;
}) {
  const [settings, setSettings] = useState<ArchitectureConfigurationInput["settings"]>({});
  const [preview, setPreview] = useState<ArchitectureConfigurationPreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const active = useRef<AbortController | null>(null);
  useEffect(() => () => { active.current?.abort(); active.current = null; }, []);
  const definitions = architectureSettingDefinitions(patternId);
  function invalidate() { active.current?.abort(); active.current = null; setPending(false); setPreview(null); setError(null); }
  function cancel() {
    if (!active.current) return;
    active.current.abort(); active.current = null; setPending(false);
    setError("Preview canceled. Your proposed settings are still here; preview again when ready.");
  }
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (active.current) return;
    setPreview(null); setError(null);
    const params = new URLSearchParams(); let input: ArchitectureConfigurationInput;
    try {
      for (const [key, value] of new FormData(event.currentTarget)) { if (typeof value !== "string") throw new Error(); params.append(key, value); }
      input = parseArchitectureConfigurationForm(params);
      if (input.expectedVersion !== version || input.patternId !== patternId) throw new Error();
    } catch { setError("Choose valid proposed settings for this pattern."); return; }
    const controller = new AbortController(), signal = AbortSignal.any([controller.signal, AbortSignal.timeout(10_000)]);
    active.current = controller; setPending(true);
    let message = "The settings preview is temporarily unavailable. Try again.";
    try {
      const response = await fetch(`/api/assessments/${assessmentId}/architecture-configuration`, {
        method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: params.toString(),
        credentials: "same-origin", cache: "no-store", redirect: "error", signal,
      });
      if (controller.signal.aborted) return; signal.throwIfAborted();
      if (response.status !== 200) {
        const messages: Record<number, string> = { 400: "Choose valid proposed settings for this pattern.", 401: "Your session expired. Sign in again.",
          403: "This request is not allowed.", 404: "This assessment is unavailable.", 409: "The assessment changed. Reload before previewing again." };
        setError(messages[response.status] ?? message); return;
      }
      message = "The settings preview could not be read safely. Try again.";
      const body = await response.json();
      if (controller.signal.aborted) return; signal.throwIfAborted();
      if (!body || Object.keys(body).length !== 2 || body.assessmentVersion !== version) throw new Error();
      setPreview({ assessmentVersion: version, analysis: architectureConfigurationAnalysis(body.analysis, input, clientScope) });
    } catch { if (!controller.signal.aborted) setError(signal.aborted ? "The settings preview took too long. Your proposed settings are still here; try again." : message); }
    finally { if (active.current === controller) { active.current = null; setPending(false); } }
  }
  return <details className="mt-4 rounded-lg border border-cyan-300/20 p-4 text-sm text-slate-300">
    <summary className="cursor-pointer font-medium text-cyan-100">Try concrete settings — temporary what-if</summary>
    <p className="mt-3 leading-6">Explore how your proposed OAuth and session settings fit this pattern. This describes the assessed application, not AuthWeave’s login. Every setting starts Unknown; nothing is saved, and no IdP configuration is read or changed.</p>
    <p className="mt-2 text-xs leading-5 text-slate-400">The separate design-condition form covers broader prerequisites. These concrete settings do not replace those conditions or the saved-input result above.</p>
    {clientScope !== "SELECTED" && <p className="mt-3 rounded-lg border border-amber-300/20 p-3 text-amber-100">{clientScope === "UNKNOWN"
      ? "Applicability is unknown. Record and save client types in Context first; proposed settings cannot establish a match."
      : "This pattern’s client is not selected. Proposed settings cannot make it applicable; review saved Context first."}</p>}
    <form onSubmit={submit} onChange={invalidate} aria-busy={pending} className="mt-4 space-y-4">
      <input type="hidden" name="expectedVersion" value={version} /><input type="hidden" name="patternId" value={patternId} />
      <fieldset disabled={pending} className="space-y-4">
        <legend className="mb-3 font-medium">Proposed settings, not observed configuration</legend>
        {definitions.map(definition => {
          const id = `${patternId}-configuration-${definition.settingId}`, selected = settings[definition.settingId] ?? "UNKNOWN";
          return <div key={definition.settingId} className="min-w-0">
            <label htmlFor={id} className="block font-medium">{labels[definition.settingId]}</label>
            <select id={id} name={definition.settingId} aria-describedby={`${id}-hint`} value={selected}
              onChange={event => setSettings({ ...settings, [definition.settingId]: event.currentTarget.value as ArchitectureSettingValue })}
              className="mt-2 w-full min-w-0 rounded-lg border border-slate-600 bg-slate-900 p-2">
              {definition.allowedValues.map(value => <option key={value} value={value}>{choice(definition.settingId, value)}</option>)}
            </select>
            <p className="mt-2 break-words text-xs text-cyan-100">Selected proposal: {choice(definition.settingId, selected)}</p>
            <p id={`${id}-hint`} className="mt-2 text-xs leading-5 text-slate-400">{definition.description}</p>
          </div>;
        })}
        <button type="submit" className="rounded-lg border border-cyan-700 px-4 py-2 text-cyan-100 disabled:opacity-50">{pending ? "Previewing…" : "Preview settings"}</button>
      </fieldset>
      {pending && <div className="flex flex-wrap items-center gap-3"><p role="status">Previewing proposed settings. Inputs are locked until this finishes or you cancel.</p>
        <button type="button" onClick={cancel} className="rounded-lg border border-slate-500 px-4 py-2 hover:bg-slate-800">Cancel preview</button></div>}
    </form>
    <div aria-live="polite" aria-atomic="true" className="mt-4">
      {!preview && !error && !pending && <p className="text-xs text-slate-400">No current settings result. Choose settings and preview them; changing a choice clears the result. Leaving this step or reloading resets these temporary choices.</p>}
      {error && <p role="alert" className="text-amber-100">{error}</p>}
      {preview && <div className="rounded-lg border border-slate-600 p-4">
        <p className="font-medium text-cyan-200">{statusText[preview.analysis.status]}</p>
        <ul className="mt-3 space-y-3">{preview.analysis.checks.map((check, index) => <li key={check.settingId}>
          <p className="font-medium">{labels[check.settingId]}</p><p className="mt-1">Proposed: {choice(check.settingId, preview.analysis.settings[check.settingId] ?? "UNKNOWN")}</p>
          <p className={check.outcome === "CONDITIONALLY_NOT_SATISFIED" || check.outcome === "UNKNOWN" ? "mt-1 text-amber-100" : "mt-1 text-slate-400"}>{outcomeText[check.outcome]}</p>
          <p className="mt-1 text-xs text-slate-400">Reference design expects: {definitions[index].compatibleValues.map(value => choice(check.settingId, value)).join(" or ")}</p>
        </li>)}</ul>
        <p className="mt-4 text-amber-100">This is an unverified proposed configuration, not observed IdP settings. It does not override the saved-input preflight or verify provider compatibility, runtime behavior, deployment or recommendation readiness.</p>
        <details className="mt-3"><summary className="cursor-pointer">Deferred checks and technical reasons</summary>
          <ul className="mt-2 list-disc space-y-1 pl-5">{architectureConfigurationDeferred.map(boundary => <li key={boundary}>{boundary}</li>)}</ul>
          <ul className="mt-3 space-y-1 break-words text-xs text-slate-400">{preview.analysis.checks.map(check => <li key={check.settingId}>{check.settingId} · {check.reasonCode}</li>)}</ul>
        </details>
      </div>}
    </div>
  </details>;
}

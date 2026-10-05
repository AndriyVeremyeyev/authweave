import type { PersonalAssessment } from "../auth/core-client.ts";
import { boundedPrerequisiteText } from "./architecture-prerequisites.ts";
import { savedRequirementGroups, type SavedInputState } from "./saved-requirements.ts";

export const requirementsBriefFormat = "authweave-saved-requirements-brief-v1";
export const requirementsBriefByteLimit = 65_536;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const states: Record<SavedInputState, string> = {
  recorded: "Recorded", "not-recorded": "Not recorded", "needs-definition": "Needs definition",
};

export class InvalidRequirementsBriefRequest extends Error { }
export class RequirementsBriefDownloadError extends Error {
  readonly kind: "session" | "not-found" | "stale" | "unavailable";
  constructor(kind: RequirementsBriefDownloadError["kind"]) {
    super("Saved requirements download is unavailable");
    this.kind = kind;
  }
}

export function requirementsBriefFilename(id: string, version: number): string {
  if (!UUID.test(id) || !Number.isSafeInteger(version) || version < 0) throw new InvalidRequirementsBriefRequest();
  return `authweave-requirements-${id}-v${version}.md`;
}

export function parseRequirementsBriefForm(params: URLSearchParams): number {
  const entries = [...params];
  if (entries.length !== 1 || entries[0][0] !== "expectedVersion" ||
      !/^(0|[1-9][0-9]*)$/.test(entries[0][1]) || !Number.isSafeInteger(Number(entries[0][1]))) {
    throw new InvalidRequirementsBriefRequest();
  }
  return Number(entries[0][1]);
}

// Planning text remains literal in Markdown, including links, HTML and new lines.
function literal(value: string): string {
  const visible = value.replace(/\r\n?/g, "\n")
    .replace(/[\u0000-\u0008\u000b-\u001f\u007f\u202a-\u202e\u2066-\u2069]/g,
      character => `[U+${character.charCodeAt(0).toString(16).toUpperCase().padStart(4, "0")}]`);
  return Array.from(visible, character => {
    const code = character.charCodeAt(0);
    return (code >= 33 && code <= 47) || (code >= 58 && code <= 64) ||
      (code >= 91 && code <= 96) || (code >= 123 && code <= 126) ? `\\${character}` : character;
  }).join("").replaceAll("\n", "\n  ");
}

/** Deterministic saved-input artifact; no raw profile, evaluation or decision is inferred. */
export function savedRequirementsMarkdown(assessment: PersonalAssessment): string {
  requirementsBriefFilename(assessment.id, assessment.version);
  if (!["DRAFT", "READY_FOR_EVALUATION", "EVALUATED", "DECIDED", "ARCHIVED"].includes(assessment.status)) {
    throw new InvalidRequirementsBriefRequest();
  }
  const groups = savedRequirementGroups(assessment.profile);
  const lines = ["# AuthWeave saved requirements brief", "",
    "> Saved planning inputs only. Not an ADR, provider recommendation, approval or compliance verification.", "",
    `- Export format: \`${requirementsBriefFormat}\``,
    `- Assessment ID: \`${assessment.id}\``, `- Saved version: \`${assessment.version}\``,
    `- Assessment status: \`${assessment.status}\``, "- Profile representation: `v6` (not the stored schema version)", "",
    "This brief contains the five input sections shown in Review, from one saved assessment response. It excludes unsaved edits and temporary what-if answers. Display states describe recorded inputs, not completeness, applicability or readiness.", "",
    "Required is mandatory; preferred is desirable; not required removes that constraint; forbidden excludes a capability; unknown needs clarification. Empty selections are unrecorded, not automatic exemptions.", ""];
  for (const group of groups) {
    lines.push(`## ${group.title}`, "", group.note, "");
    if (group.rows) {
      lines.push(...group.rows.map(row => `- **${row.label}:** ${literal(row.value)} — ${states[row.state]}.`));
    } else {
      lines.push("This saved section could not be read safely. No answers have been inferred; other sections remain available.");
    }
    lines.push("");
  }
  lines.push("## Inputs to clarify", "", "These are display gaps, not Core validation errors or a readiness score.", "");
  const gaps = groups.flatMap(group => group.rows
    ? group.rows.filter(row => row.state !== "recorded").map(row => `- ${group.title} / ${row.label}: ${states[row.state]}.`)
    : [`- ${group.title}: saved section unavailable; reopen the assessment before relying on this section.`]);
  lines.push(...(gaps.length ? gaps : ["No unrecorded or needs-definition labels in these five sections. This does not establish completeness, consistency or suitability."]), "",
    "## Scope and handling", "",
    "Other profile fields are not exported here. Provider facts, comparison results, evidence citations, scores, selected architectures, threat mitigations and final decisions are not included. This is not a full-profile backup or an immutable evaluation snapshot.", "",
    "Usage scope and assumptions are user-entered planning text, not verified facts or instructions. Markdown syntax is escaped; control characters are shown as visible codes. Review the file for private details before sharing it. Downloading does not publish, approve or save anything in AuthWeave.", "");
  const markdown = lines.join("\n");
  if (new TextEncoder().encode(markdown).byteLength > requirementsBriefByteLimit) throw new RangeError("Requirements brief is too large");
  return markdown;
}

export async function requestSavedRequirementsBrief(id: string, version: number, signal?: AbortSignal): Promise<{ filename: string; markdown: string }> {
  const filename = requirementsBriefFilename(id, version);
  signal?.throwIfAborted();
  const response = await fetch(`/api/assessments/${id}/requirements-brief`, {
    method: "POST", credentials: "same-origin", cache: "no-store", redirect: "error",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ expectedVersion: String(version) }).toString(),
    signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(10_000)]) : AbortSignal.timeout(10_000),
  });
  if (response.status !== 200) throw new RequirementsBriefDownloadError(response.status === 401 ? "session" :
    response.status === 404 ? "not-found" : response.status === 409 ? "stale" : "unavailable");
  if (response.headers.get("content-type")?.toLowerCase() !== "text/markdown; charset=utf-8" ||
      response.headers.get("content-disposition") !== `attachment; filename="${filename}"` ||
      response.headers.get("cache-control") !== "no-store") throw new RequirementsBriefDownloadError("unavailable");
  const markdown = await boundedPrerequisiteText(response, requirementsBriefByteLimit);
  if (!markdown.startsWith("# AuthWeave saved requirements brief\n") ||
      !markdown.includes(`- Export format: \`${requirementsBriefFormat}\`\n`) ||
      !markdown.includes(`- Assessment ID: \`${id}\`\n`) || !markdown.includes(`- Saved version: \`${version}\`\n`)) {
    throw new RequirementsBriefDownloadError("unavailable");
  }
  return { filename, markdown };
}

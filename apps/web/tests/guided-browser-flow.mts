import { readFile } from "node:fs/promises";
import { expect, type Page, type Locator } from "@playwright/test";
import { guidedScenarios, guidedScenarioForms } from "./fixtures/guided-scenarios.mts";

// Shared UI assertions only: no credentials, login, reporter or failure artifacts.
// The real-provider runner catches failures without exporting Playwright error objects.
export async function createGuidedAssessment(page: Page) {
  await page.getByRole("button", { name: "Create assessment draft", exact: true }).click();
  await expect(page).toHaveURL(/\/assessments\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("heading", { name: "Your identity decision", exact: true })).toBeVisible();
  return new URL(page.url()).pathname;
}

export async function guidedStep(page: Page, name: string, id: string, diagnostic: (value: string) => void = () => {}) {
  const button = page.getByRole("navigation", { name: "Assessment steps" }).getByRole("button", { name, exact: true });
  diagnostic("navigation click"); await button.click();
  diagnostic("navigation current step");
  try { await expect(button).toHaveAttribute("aria-current", "step"); }
  catch (error) {
    const current = await button.getAttribute("aria-current");
    const dirty = await page.getByRole("dialog", { name: "Keep your unsaved changes?" }).isVisible();
    const saving = await page.locator('form[aria-busy="true"]').count();
    diagnostic(`navigation current=${current === "step" ? "step" : "inactive"}; dirty dialog=${dirty}; busy forms=${saving}`);
    throw error;
  }
  diagnostic("navigation URL step");
  await expect(page).toHaveURL(new RegExp(`[?&]step=${id}(?:&|$)`));
}

async function inputs(form: Locator, values: URLSearchParams, diagnostic: (value: string) => void) {
  // Open explanations using actual controls so hidden input groups become browser-editable.
  const closed = form.locator("details:not([open]) > summary");
  while (await closed.count()) await closed.first().click();
  const names = [...new Set([...values.keys()].filter(name => name !== "expectedVersion"))];
  // Checkbox choices can enable dependent controls (for example retention days).
  const checkboxes: string[] = [], other: string[] = [];
  for (const name of names) {
    ((await form.locator(`[name="${name}"]`).first().getAttribute("type")) === "checkbox" ? checkboxes : other).push(name);
  }
  for (const name of [...checkboxes, ...other]) {
    diagnostic(`form field ${name}`);
    const fields = form.locator(`[name="${name}"]`), entries = values.getAll(name);
    const tag = await fields.first().evaluate(field => field.tagName);
    if (tag === "SELECT") await fields.selectOption(entries[0]);
    else if (await fields.first().getAttribute("type") === "checkbox") {
      for (const field of await fields.all()) await field.setChecked(entries.includes((await field.getAttribute("value"))!));
    } else {
      expect(await fields.count()).toBe(entries.length);
      for (let index = 0; index < entries.length; index++) await fields.nth(index).fill(entries[index]);
    }
  }
}

export async function saveGuidedForm(page: Page, assessment: string, path: string, values: URLSearchParams, version: number,
  diagnostic: (value: string) => void = () => {}) {
  diagnostic("expected form version before save");
  const form = page.locator(`form[action="/api${assessment}/${path}"]`);
  await expect(form.locator('[name="expectedVersion"]')).toHaveValue(String(version - 1));
  await inputs(form, values, diagnostic);
  const response = page.waitForResponse(response => new URL(response.url()).pathname === `/api${assessment}/${path}`);
  void response.catch(() => {}); // Never leave an unhandled waiter after a failed UI action.
  diagnostic("submit form");
  await form.getByRole("button", { name: /^Save / }).click();
  const status = (await response).status(); diagnostic(`save response status ${status}`); expect(status).toBe(200);
  diagnostic("saved-version feedback");
  await expect(page.getByText(`Saved version ${version}`, { exact: true }).first()).toBeVisible();
  diagnostic("expected form version after save");
  await expect(page.locator(`form[action="/api${assessment}/${path}"] [name="expectedVersion"]`)).toHaveValue(String(version));
  diagnostic("horizontal layout after save");
  await noOverflow(page);
}

async function noOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
}

function literal(value: string) {
  return Array.from(value, character => {
    const code = character.charCodeAt(0);
    return (code >= 33 && code <= 47) || (code >= 58 && code <= 64) || (code >= 91 && code <= 96) ||
      (code >= 123 && code <= 126) ? `\\${character}` : character;
  }).join("").replaceAll("\n", "\n  ");
}

async function review(page: Page, scenario: typeof guidedScenarios[number]) {
  await guidedStep(page, "Review", "review");
  const rows = page.locator('section[aria-labelledby^="saved-"] dl > div');
  await expect(rows).toHaveCount(37);
  for (const [label, expected] of [["Application type", scenario.expected.application], ["User populations", scenario.expected.users],
    ["Client types", scenario.expected.clients], ["Minimum retention", `${scenario.retention} days`],
    ["SCIM provisioning", scenario.expected.scim], ["Enterprise single sign-on", scenario.expected.sso]]) {
    await expect(rows.filter({ has: page.locator("dt", { hasText: new RegExp(`^${label}$`) }) }).locator("dd > span").first()).toHaveText(expected);
  }
  await expect(page.getByText("Saved version 5", { exact: true }).first()).toBeVisible();
  await noOverflow(page);
  return rows;
}

export async function runGuidedScenario(page: Page, scenario: typeof guidedScenarios[number],
  checkpoint: (value: string) => void = () => {}, diagnostic: (value: string) => void = () => {}) {
  checkpoint("create draft");
  const assessment = await createGuidedAssessment(page), forms = guidedScenarioForms(scenario);
  checkpoint("context save"); await saveGuidedForm(page, assessment, "evaluation-context", forms.context, 1, diagnostic);
  checkpoint("requirements save"); await guidedStep(page, "Requirements", "capabilities", diagnostic);
  await saveGuidedForm(page, assessment, "capabilities", forms.capabilities, 2, diagnostic);
  checkpoint("audit save"); await guidedStep(page, "Audit", "auditability", diagnostic);
  await saveGuidedForm(page, assessment, "auditability", forms.auditability, 3, diagnostic);
  checkpoint("operational preferences save"); await guidedStep(page, "Usage", "usage", diagnostic);
  await saveGuidedForm(page, assessment, "operational-preferences", forms.operations, 4, diagnostic);
  checkpoint("usage planning save"); await saveGuidedForm(page, assessment, "usage-planning", forms.usage, 5, diagnostic);
  checkpoint("Review and saved brief");
  const rows = await review(page, scenario);
  const displayed = await rows.evaluateAll(rows => rows.map(row => ({ label: row.querySelector("dt")!.textContent!,
    value: row.querySelector("dd > span")!.textContent!, state: row.querySelector("dd > span:last-child")!.textContent! })));
  const downloaded = page.waitForEvent("download"); void downloaded.catch(() => {});
  await page.getByRole("button", { name: "Download saved brief (.md)", exact: true }).click();
  const download = await downloaded;
  try {
    expect(download.suggestedFilename()).toBe(`authweave-requirements-${assessment.split("/").at(-1)}-v5.md`);
    const markdown = await readFile((await download.path())!, "utf8");
    expect(markdown).toContain("- Saved version: `5`");
    for (const row of displayed) expect(markdown).toContain(`- **${row.label}:** ${literal(row.value)} — ${row.state}.`);
  } finally { await download.delete(); }
  checkpoint("fictional Comparison"); await guidedStep(page, "Comparison", "comparison");
  await expect(page.getByRole("heading", { name: "Understand each option", exact: true })).toBeVisible();
  await expect(page.locator('[id^="comparison-option-"]')).toHaveCount(3); await noOverflow(page);
  checkpoint("Architecture preview"); await guidedStep(page, "Architecture", "architecture");
  await expect(page.getByRole("heading", { name: "Understand the patterns before choosing", exact: true })).toBeVisible();
  await expect(page.locator('[id^="architecture-pattern-"]')).toHaveCount(5); await noOverflow(page);
  checkpoint("saved list and resume"); await page.goto("/assessments");
  const saved = page.locator(`a[href="${assessment}"]`);
  await expect(saved).toContainText(scenario.expected.application); await expect(saved).toContainText("Saved version 5");
  await noOverflow(page); await saved.click(); await expect(page).toHaveURL(new RegExp(`${assessment}$`));
  await review(page, scenario);
  return assessment;
}

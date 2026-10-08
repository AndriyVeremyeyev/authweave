import { readFile } from "node:fs/promises";
import { test, expect, type Page, type Locator } from "@playwright/test";
import { guidedScenarios, guidedScenarioForms } from "../fixtures/guided-scenarios.mts";

test.beforeAll(() => {
  expect(process.env.AUTHWEAVE_TEST_BROWSER).toBe("synthetic-browser-core-v1");
});

async function identity(page: Page, subject: string, button = "Sign in synthetic user") {
  await expect(page.getByRole("heading", { name: "Synthetic OIDC provider" })).toBeVisible();
  await page.getByLabel("Test subject").fill(subject);
  const callback = page.waitForResponse(response => new URL(response.url()).pathname === "/api/auth/callback");
  await page.getByRole("button", { name: button, exact: true }).click();
  return callback;
}

async function login(page: Page, subject: string) {
  await page.goto("/account");
  await page.getByRole("button", { name: "Sign in with ZITADEL", exact: true }).click();
  expect((await identity(page, subject)).status()).toBe(303);
  await expect(page).toHaveURL("http://localhost:3000/account");
  await expect(page.getByText(`Signed in as ${subject}.`, { exact: true })).toBeVisible();
  const cookie = (await page.context().cookies()).find(cookie => cookie.name === "authweave-session-local")!;
  expect(cookie.value).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(cookie.httpOnly).toBe(true); expect(cookie.sameSite).toBe("Lax"); expect(cookie.path).toBe("/");
  expect(await page.evaluate(() => document.cookie)).not.toContain("authweave-session");
  return cookie.value;
}

async function create(page: Page) {
  await page.getByRole("button", { name: "Create assessment draft", exact: true }).click();
  await expect(page).toHaveURL(/\/assessments\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("heading", { name: "Your identity decision", exact: true })).toBeVisible();
  return new URL(page.url()).pathname;
}

async function step(page: Page, name: string, id: string) {
  const button = page.getByRole("navigation", { name: "Assessment steps" }).getByRole("button", { name, exact: true });
  await button.click(); await expect(button).toHaveAttribute("aria-current", "step");
  await expect(page).toHaveURL(new RegExp(`[?&]step=${id}(?:&|$)`));
}

async function inputs(form: Locator, values: URLSearchParams) {
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

async function save(page: Page, assessment: string, path: string, values: URLSearchParams, version: number) {
  const form = page.locator(`form[action="/api${assessment}/${path}"]`);
  await expect(form.locator('[name="expectedVersion"]')).toHaveValue(String(version - 1));
  await inputs(form, values);
  const response = page.waitForResponse(response => new URL(response.url()).pathname === `/api${assessment}/${path}`);
  await form.getByRole("button", { name: /^Save / }).click();
  expect((await response).status()).toBe(200);
  await expect(page.getByText(`Saved version ${version}`, { exact: true }).first()).toBeVisible();
  await expect(page.locator(`form[action="/api${assessment}/${path}"] [name="expectedVersion"]`)).toHaveValue(String(version));
}

async function logout(page: Page) {
  await page.goto("/account"); await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(page).toHaveURL("http://localhost:3000/");
  expect((await page.context().cookies()).some(cookie => cookie.name === "authweave-session-local")).toBe(false);
}

function literal(value: string) {
  return Array.from(value, character => {
    const code = character.charCodeAt(0);
    return (code >= 33 && code <= 47) || (code >= 58 && code <= 64) || (code >= 91 && code <= 96) ||
      (code >= 123 && code <= 126) ? `\\${character}` : character;
  }).join("").replaceAll("\n", "\n  ");
}

for (const scenario of guidedScenarios) test(`guided ${scenario.key}: five browser saves, Review, brief and saved previews`, async ({ page }, info) => {
  await login(page, `synthetic-browser-${info.project.name}-${scenario.key}`);
  const assessment = await create(page), forms = guidedScenarioForms(scenario);
  await save(page, assessment, "evaluation-context", forms.context, 1);
  await step(page, "Requirements", "capabilities"); await save(page, assessment, "capabilities", forms.capabilities, 2);
  await step(page, "Audit", "auditability"); await save(page, assessment, "auditability", forms.auditability, 3);
  await step(page, "Usage", "usage"); await save(page, assessment, "operational-preferences", forms.operations, 4);
  await save(page, assessment, "usage-planning", forms.usage, 5);
  await step(page, "Review", "review");
  const rows = page.locator('section[aria-labelledby^="saved-"] dl > div');
  await expect(rows).toHaveCount(37);
  for (const [label, expected] of [["Application type", scenario.expected.application], ["User populations", scenario.expected.users],
    ["Client types", scenario.expected.clients], ["Minimum retention", `${scenario.retention} days`]]) {
    await expect(rows.filter({ has: page.locator("dt", { hasText: new RegExp(`^${label}$`) }) }).locator("dd > span").first()).toHaveText(expected);
  }
  const displayed = await rows.evaluateAll(rows => rows.map(row => ({ label: row.querySelector("dt")!.textContent!,
    value: row.querySelector("dd > span")!.textContent!, state: row.querySelector("dd > span:last-child")!.textContent! })));
  const downloaded = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download saved brief (.md)", exact: true }).click();
  const download = await downloaded;
  expect(download.suggestedFilename()).toBe(`authweave-requirements-${assessment.split("/").at(-1)}-v5.md`);
  const markdown = await readFile((await download.path())!, "utf8");
  expect(markdown).toContain("- Saved version: `5`");
  for (const row of displayed) expect(markdown).toContain(`- **${row.label}:** ${literal(row.value)} — ${row.state}.`);
  await step(page, "Comparison", "comparison");
  await expect(page.getByRole("heading", { name: "Understand each option", exact: true })).toBeVisible();
  await expect(page.locator('[id^="comparison-option-"]')).toHaveCount(3);
  await step(page, "Architecture", "architecture");
  await expect(page.getByRole("heading", { name: "Understand the patterns before choosing", exact: true })).toBeVisible();
  await expect(page.locator('[id^="architecture-pattern-"]')).toHaveCount(5);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.goto("/assessments");
  await expect(page.getByRole("link", { name: /Open assessment/ }).first()).toBeVisible();
  await logout(page);
});

test("dirty guard, stale tab, ownership, reauthentication, logout and invalid nonce fail closed", async ({ page, browser }, info) => {
  const subject = `synthetic-browser-${info.project.name}-security-owner`;
  const originalCookie = await login(page, subject), assessment = await create(page);
  const stale = await page.context().newPage(); await stale.goto(assessment);
  await page.locator('select[name="applicationType"]').selectOption("B2B_SAAS");
  await page.getByRole("navigation", { name: "Assessment steps" }).getByRole("button", { name: "Review", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "Keep your unsaved changes?" });
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "Stay and review" }).click();
  await expect(page.locator('select[name="applicationType"]')).toHaveValue("B2B_SAAS");
  await page.getByRole("navigation", { name: "Assessment steps" }).getByRole("button", { name: "Review", exact: true }).click();
  await dialog.getByRole("button", { name: "Discard and continue" }).click();
  await step(page, "Context", "context");
  await expect(page.locator('select[name="applicationType"]')).toHaveValue("UNKNOWN");
  await save(page, assessment, "evaluation-context", guidedScenarioForms(guidedScenarios[0]).context, 1);
  await stale.locator('select[name="applicationType"]').selectOption("INTERNAL_WORKFORCE");
  const conflict = stale.waitForResponse(response => new URL(response.url()).pathname === `/api${assessment}/evaluation-context`);
  await stale.getByRole("button", { name: "Save application context", exact: true }).click();
  expect((await conflict).status()).toBe(409);
  await expect(stale.getByRole("heading", { name: "This assessment changed in another tab or process" })).toBeVisible();
  await expect(stale.locator('select[name="applicationType"]')).toHaveValue("INTERNAL_WORKFORCE");
  await stale.close({ runBeforeUnload: false });
  await page.goto("/account");
  await page.getByRole("button", { name: "Verify this account again" }).click();
  expect((await identity(page, `${subject}-different`)).status()).toBe(403);
  await page.goto("/account"); await expect(page.getByText(`Signed in as ${subject}.`, { exact: true })).toBeVisible();
  expect((await page.context().cookies()).find(cookie => cookie.name === "authweave-session-local")!.value).toBe(originalCookie);
  await page.getByRole("button", { name: "Verify this account again" }).click();
  expect((await identity(page, subject)).status()).toBe(303);
  await expect(page).toHaveURL("http://localhost:3000/account");
  expect((await page.context().cookies()).find(cookie => cookie.name === "authweave-session-local")!.value).not.toBe(originalCookie);
  const outsiderContext = await browser.newContext({ baseURL: "http://localhost:3000" });
  try {
    const outsider = await outsiderContext.newPage(); await login(outsider, `${subject}-outsider`);
    const denied = await outsider.goto(assessment); expect(denied!.status()).toBe(404);
    const deniedSave = await outsiderContext.request.post(`/api${assessment}/evaluation-context`, {
      headers: { Origin: "http://localhost:3000", Accept: "application/json" },
      form: { ...Object.fromEntries(guidedScenarioForms(guidedScenarios[0]).context), expectedVersion: "1" },
    });
    expect(deniedSave.status()).toBe(404);
    await logout(outsider);
  } finally { await outsiderContext.close(); }
  const csrf = await page.context().request.post("/api/assessments", { headers: { Origin: "https://other.invalid" } });
  expect(csrf.status()).toBe(403);
  await logout(page); await page.goto(assessment); await expect(page).toHaveURL("http://localhost:3000/account");
  await page.getByRole("button", { name: "Sign in with ZITADEL" }).click();
  const invalid = await identity(page, `${subject}-invalid`, "Return an invalid nonce");
  expect(invalid.status()).toBe(400);
  const replay = await page.context().request.get(invalid.url()); expect(replay.status()).toBe(400);
  expect((await page.context().cookies()).some(cookie => cookie.name === "authweave-session-local")).toBe(false);
});

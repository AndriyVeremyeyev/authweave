import { test, expect, type Page } from "@playwright/test";
import { guidedScenarios, guidedScenarioForms } from "../fixtures/guided-scenarios.mts";
import { createGuidedAssessment as create, guidedStep as step, saveGuidedForm as save, runGuidedScenario } from "../guided-browser-flow.mts";
import { resultReferenceQuery } from "../../src/lib/assessment/decision-results.ts";

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

async function logout(page: Page) {
  await page.goto("/account"); await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(page).toHaveURL("http://localhost:3000/");
  expect((await page.context().cookies()).some(cookie => cookie.name === "authweave-session-local")).toBe(false);
}

async function delayedHydration(page: Page, assessment: string) {
  let release!: () => void;
  const held = new Promise<void>(resolve => { release = resolve; });
  let scripts = 0;
  const chunks = /\/_next\/static\/.*\.js(?:\?.*)?$/;
  await page.route(chunks, async route => { scripts++; await held; await route.continue(); });
  try {
    await page.goto(assessment, { waitUntil: "commit" });
    const buttons = page.getByRole("navigation", { name: "Assessment steps" }).getByRole("button");
    await expect(buttons).toHaveCount(7);
    for (const button of await buttons.all()) await expect(button).toBeDisabled();
    await expect(page.getByRole("button", { name: "← Your assessments", exact: true })).toBeDisabled();
    // Native progressive form submission must remain available before hydration.
    await expect(page.getByRole("button", { name: "Save application context", exact: true })).toBeEnabled();
    await expect.poll(() => scripts).toBeGreaterThan(0);
  } finally { release(); }
  await expect(page.getByRole("navigation", { name: "Assessment steps" }).getByRole("button", { name: "Requirements", exact: true })).toBeEnabled();
  await page.unroute(chunks);
}

for (const scenario of guidedScenarios) test(`guided ${scenario.key}: five browser saves, Review, brief and saved previews`, async ({ page }, info) => {
  await login(page, `synthetic-browser-${info.project.name}-${scenario.key}`);
  await runGuidedScenario(page, scenario);
  await logout(page);
});

test("owned saved result history opens exact original-clock advice without writes or cross-owner access", async ({ page, browser }, info) => {
  const fixtures = JSON.parse(process.env.AUTHWEAVE_TEST_RESULT_FIXTURES!);
  const fixture = fixtures.find((value: { device: string }) => value.device === info.project.name);
  expect(fixture).toBeTruthy();
  const subject = `synthetic-browser-${info.project.name}-history`, assessment = `/assessments/${fixture.assessmentId}`;
  await login(page, subject);
  await page.goto(`${assessment}?step=review`);
  await page.getByRole("link", { name: "Open saved calculation history →", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Decision calculation history", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Result version 2", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Result version 1", exact: true })).toBeVisible();
  await page.getByRole("link", { name: "Verify and open result version 1", exact: true }).click();
  await expect(page).toHaveURL(`http://localhost:3000${assessment}/results/${fixture.first.resultId}?${resultReferenceQuery(fixture.first)}`);
  await expect(page.getByRole("status")).toContainText("Core verified the whole historical replay");
  await expect(page.getByText("Version 1 · Schema 6", { exact: true })).toBeVisible();
  await expect(page.getByText(/No decision is approved/)).toBeVisible();
  await expect(page.getByRole("button", { name: /recalculate|approve|record/i })).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
  await page.screenshot({ path: `../../.internal/result-history-${info.project.name}.png`, fullPage: true });
  await page.reload(); await expect(page.getByRole("heading", { name: "Result version 1", exact: true })).toBeVisible();
  const invalid = await page.goto(`${assessment}/results/${fixture.first.resultId}?version=1`);
  expect(invalid!.status()).toBe(200); await expect(page.getByRole("main").getByRole("alert")).toContainText("complete exact result reference");
  await expect(page.getByText("Core verified the whole historical replay", { exact: false })).toHaveCount(0);
  const outsiderContext = await browser.newContext({ baseURL: "http://localhost:3000" });
  try {
    const outsider = await outsiderContext.newPage(); await login(outsider, `${subject}-outsider`);
    const deniedHistory = await outsider.goto(`${assessment}/results`); expect(deniedHistory!.status()).toBe(404);
    const deniedResult = await outsider.goto(`${assessment}/results/${fixture.first.resultId}?${resultReferenceQuery(fixture.first)}`); expect(deniedResult!.status()).toBe(404);
    await logout(outsider);
  } finally { await outsiderContext.close(); }
  await logout(page); await page.goto(`${assessment}/results`); await expect(page).toHaveURL("http://localhost:3000/account");
});

test("dirty guard, stale tab, ownership, reauthentication, logout and invalid nonce fail closed", async ({ page, browser }, info) => {
  const subject = `synthetic-browser-${info.project.name}-security-owner`;
  const originalCookie = await login(page, subject), assessment = await create(page);
  await page.goto(`${assessment}?step=review`);
  await page.getByRole("link", { name: "Open saved calculation history →", exact: true }).click();
  await expect(page.getByRole("heading", { name: "No saved decision calculations yet", exact: true })).toBeVisible();
  await page.goto(assessment);
  const stale = await page.context().newPage(); await delayedHydration(stale, assessment);
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

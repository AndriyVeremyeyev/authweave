import assert from "node:assert/strict";
import { chromium, type BrowserContext, type Page } from "@playwright/test";
import { Pool } from "pg";
import { opaqueHash } from "../src/lib/auth/session-policy.ts";
import { BrowserRuntime, browserApp } from "./browser-runtime.mts";
import { localIssuer, localZitadelCuratorLogin, readLocalZitadelConfiguration } from "./local-zitadel-config.mts";
import { prepareDecisionCandidate } from "../../../packages/contracts/scripts/prepare-decision-candidate.mjs";
import { guidedScenarioForms, guidedScenarios } from "./fixtures/guided-scenarios.mts";
import { runGuidedScenario } from "./guided-browser-flow.mts";

// No Playwright reporter/trace/screenshot: assertion errors and call logs can contain secrets.
let stage = "explicit opt-in";
let runtime: BrowserRuntime | undefined;
let browser: Awaited<ReturnType<typeof chromium.launch>> | undefined;
let database: Pool | undefined;
let success = false;
const contexts: BrowserContext[] = [];
let authorizations = 0, callbacks = 0;
let detail = "", callbackStatus: number | undefined;
let databaseInterrupted = false;

function progress(value: string) {
  stage = value; detail = ""; callbackStatus = undefined;
  console.log(`Local ZITADEL checkpoint: ${stage}`);
}

async function appSession(context: BrowserContext) {
  const cookie = (await context.cookies(browserApp)).find(cookie => cookie.name === "authweave-session-local");
  assert.ok(cookie && /^[A-Za-z0-9_-]{43}$/.test(cookie.value));
  assert.ok(cookie.httpOnly && cookie.sameSite === "Lax" && cookie.path === "/");
  return cookie.value;
}

async function authenticated(page: Page, name: string, curator: boolean) {
  detail = "account redirect";
  await page.waitForURL(`${browserApp}/account`);
  detail = "account display name";
  await page.getByText(`Signed in as ${name}.`, { exact: true }).waitFor();
  detail = curator ? "scoped curator readiness" : "curator denial";
  if (curator) await page.getByRole("link", { name: "Review a catalog proposal →", exact: true }).waitFor();
  else await page.getByText("This account has no AuthWeave catalog curator role.", { exact: true }).waitFor();
  assert.ok(!(await page.evaluate(() => document.cookie)).includes("authweave-session"));
}

async function signIn(page: Page, user: { login: string; password: string }, registration: Record<string, string>, reauth = false) {
  detail = "authorization request";
  callbackStatus = undefined;
  const authorization = page.waitForRequest(request => {
    const url = new URL(request.url());
    return url.origin === localIssuer && url.searchParams.get("response_type") === "code";
  }, { timeout: 30_000 });
  const callback = page.waitForResponse(response => {
    const url = new URL(response.url()); return url.origin === browserApp && url.pathname === "/api/auth/callback";
  }, { timeout: 60_000 });
  // A failed UI step must not leave a waiter producing an unhandled diagnostic later.
  void authorization.catch(() => {}); void callback.catch(() => {});
  await page.getByRole("button", { name: reauth ? "Verify this account again" : "Sign in with ZITADEL", exact: true }).click();
  const url = new URL((await authorization).url());
  detail = "authorization parameters";
  assert.equal(url.searchParams.get("client_id"), registration.AUTHWEAVE_OIDC_CLIENT_ID);
  assert.equal(url.searchParams.get("scope"), `openid profile email urn:zitadel:iam:org:project:id:${registration.AUTHWEAVE_OIDC_PROJECT_ID}:aud urn:zitadel:iam:org:projects:roles`);
  assert.equal(url.searchParams.get("redirect_uri"), `${browserApp}/api/auth/callback`);
  assert.equal(url.searchParams.get("code_challenge_method"), "S256");
  for (const key of ["state", "nonce", "code_challenge"]) assert.match(url.searchParams.get(key)!, /^[A-Za-z0-9_-]{43}$/);
  assert.equal(url.searchParams.get("max_age"), reauth ? "0" : "28800");
  assert.equal(url.searchParams.get("prompt"), reauth ? "login" : null);
  authorizations++;
  detail = "Login V2 redirect";
  await page.waitForURL(target => target.origin === localIssuer && target.pathname.startsWith("/ui/v2/login/"));
  // The pinned Login V2 UI owns the password flow; no Session API shortcut or injected cookies.
  detail = "username form";
  await page.locator('input[type="text"][autocomplete="username"]').fill(user.login);
  await page.getByRole("button", { name: "Continue", exact: true }).click();
  detail = "password form";
  await page.locator('input[type="password"]').waitFor();
  await page.locator('input[type="password"]').fill(user.password);
  await page.getByRole("button", { name: "Continue", exact: true }).click();
  detail = "callback response";
  callbackStatus = (await callback).status(); assert.equal(callbackStatus, 303); callbacks++;
}

async function signOut(page: Page) {
  detail = "application session revocation";
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await page.waitForURL(url => url.href === `${browserApp}/` ||
    (url.origin === localIssuer && url.pathname.startsWith("/ui/v2/login/")));
  if (new URL(page.url()).origin === localIssuer) {
    detail = "provider logout confirmation";
    await page.getByRole("heading", { name: "Logout", exact: true }).waitFor();
    await page.locator("button").filter({ hasText: "End Session" }).click();
  }
  detail = "post-logout redirect";
  await page.waitForURL(`${browserApp}/`);
}

try {
  assert.equal(process.env.AUTHWEAVE_TEST_BROWSER, "local-zitadel-browser-v1");
  const expectedCurator = localZitadelCuratorLogin(process.env.AUTHWEAVE_TEST_LOCAL_CURATOR_USER);
  assert.ok(!Object.keys(process.env).some(key => key.startsWith("DEBUG") ||
    ["PWDEBUG", "NODE_DEBUG", "NODE_DEBUG_NATIVE", "NODE_OPTIONS"].includes(key)), "Disable browser and Node debug output");
  progress("read existing private local registration");
  const { web, users } = await readLocalZitadelConfiguration(process.cwd());
  runtime = new BrowserRuntime();
  await runtime.reservePorts([3000, 8080]); // Port 8081 belongs to the existing lab, never stopped by this harness.
  progress("start isolated production app");
  await runtime.startWeb(web, true);
  browser = await chromium.launch();
  const assessments: string[][] = [];
  let blockedRequests = 0, guidedRuns = 0;
  for (const [index, user] of users.entries()) {
    const curator = user.login === expectedCurator;
    const device = index === 0 ? "desktop" : "mobile";
    const context = await browser.newContext({ baseURL: browserApp, serviceWorkers: "block", acceptDownloads: true,
      viewport: index === 0 ? { width: 1440, height: 1000 } : { width: 390, height: 844 },
      isMobile: index === 1, hasTouch: index === 1 });
    contexts.push(context);
    await context.route("**/*", route => {
      const url = new URL(route.request().url());
      if ([browserApp, localIssuer].includes(url.origin)) return route.continue();
      blockedRequests++; return route.abort();
    });
    context.setDefaultTimeout(15_000); context.setDefaultNavigationTimeout(30_000);
    const page = await context.newPage();
    progress(index === 0 ? "Alice password login" : "Bob password login");
    await page.goto("/account"); await signIn(page, user, web); await authenticated(page, user.name, curator);
    const original = await appSession(context);
    if (!database) {
      database = new Pool({ host: "127.0.0.1", port: Number(process.env.AUTHWEAVE_POSTGRES_PORT),
        database: process.env.AUTHWEAVE_POSTGRES_DB, user: "authweave_web_runtime", password: "web-test-password",
        max: 1, connectionTimeoutMillis: 3000 });
      database.on("error", () => { databaseInterrupted = true; });
    }
    const identity = await database.query(`SELECT issuer, subject, workspace_id, email, display_name, curator_project_id, curator_org_id
      FROM web.sessions WHERE session_hash = $1`, [opaqueHash(original)]);
    assert.equal(identity.rowCount, 1);
    assert.equal(identity.rows[0].issuer, localIssuer); assert.equal(identity.rows[0].email, user.login);
    assert.equal(identity.rows[0].display_name, user.name);
    assert.equal(identity.rows[0].curator_project_id, curator ? web.AUTHWEAVE_OIDC_PROJECT_ID : null);
    assert.equal(identity.rows[0].curator_org_id, curator ? web.AUTHWEAVE_OIDC_ORG_ID : null);
    const personal: string[] = []; assessments.push(personal);
    for (const scenario of guidedScenarios) {
      await page.goto("/account");
      personal.push(await runGuidedScenario(page, scenario, value => progress(`${device} ${scenario.key}: ${value}`),
        value => { detail = value; }));
      guidedRuns++;
    }
    progress(`${index === 0 ? "Alice" : "Bob"} ${curator ? "read-only bootstrap preparation" : "curator denial"}`);
    await page.goto("/catalog/review");
    if (curator) {
      await page.getByRole("link", { name: "Review the first bootstrap candidate →", exact: true }).click();
      const assembly = await prepareDecisionCandidate();
      await page.getByLabel("Import the first candidate", { exact: true }).fill(JSON.stringify(assembly.candidate));
      await page.getByRole("button", { name: "Prepare manual review", exact: true }).click();
      await page.getByRole("heading", { name: "Review every recorded fact", exact: true }).waitFor();
      assert.equal(await page.locator('input[type="radio"]').count(), assembly.reviewTasks.length * 3);
      assert.equal(await page.locator('input[type="radio"]:checked').count(), 0);
      assert.equal(await page.locator('input[type="checkbox"]:checked').count(), 0);
      assert.ok(await page.getByRole("button", { name: "Record manual bootstrap review", exact: true }).isDisabled());
    } else {
      await page.getByRole("region", { name: "Curator access unavailable" }).waitFor();
      await page.getByText("This account does not have the scoped AuthWeave catalog curator role.", { exact: true }).waitFor();
      const denied = await context.request.post("/api/catalog-change-proposals/00000000-0000-0000-0000-000000000001/rejection", {
        headers: { Origin: browserApp }, data: { expectedVersion: 0, expectedSha256: "0".repeat(64), reasonCode: "OUT_OF_SCOPE" },
      });
      assert.equal(denied.status(), 403);
      const preparationDenied = await context.request.post("/api/catalog-bootstrap-reviews/prepare", {
        headers: { Origin: browserApp }, data: {},
      });
      assert.equal(preparationDenied.status(), 403);
    }
    progress(index === 0 ? "Alice same-account reauthentication" : "Bob same-account reauthentication");
    await page.goto("/account"); await signIn(page, user, web, true); await authenticated(page, user.name, curator);
    const rotated = await appSession(context); assert.notEqual(rotated, original);
    const current = await database.query("SELECT issuer, subject, workspace_id FROM web.sessions WHERE session_hash = $1", [opaqueHash(rotated)]);
    assert.equal(current.rowCount, 1);
    assert.equal(current.rows[0].issuer, identity.rows[0].issuer); assert.equal(current.rows[0].subject, identity.rows[0].subject);
    assert.equal(current.rows[0].workspace_id, identity.rows[0].workspace_id);
    const revoked = await fetch(`${browserApp}/account`, { headers: { Cookie: `authweave-session-local=${original}` },
      signal: AbortSignal.timeout(5000) });
    assert.ok((await revoked.text()).includes("You are not signed in."));
    for (const assessment of personal) {
      await page.goto(assessment); await page.getByRole("heading", { name: "Your identity decision", exact: true }).waitFor();
      assert.equal(await page.locator('input[name="expectedVersion"]').first().inputValue(), "5");
    }
  }
  progress("bidirectional cross-user read and write denial");
  for (let index = 0; index < 2; index++) {
    const context = contexts[index], page = context.pages()[0];
    for (const other of assessments[1 - index]) {
      assert.equal((await page.goto(other))!.status(), 404);
      const denied = await context.request.post(`/api${other}/evaluation-context`, { headers: { Origin: browserApp, Accept: "application/json" },
        form: { ...Object.fromEntries(guidedScenarioForms(guidedScenarios[0]).context), expectedVersion: "5" } });
      assert.equal(denied.status(), 404);
    }
    await page.goto("/assessments");
    const listed = await page.locator('ul[aria-label="Saved assessments"] a').evaluateAll(links => links.map(link => link.getAttribute("href")));
    assert.deepEqual([...listed].sort(), [...assessments[index]].sort());
  }
  progress("local logout and revoked access");
  for (const [index, context] of contexts.entries()) {
    const page = context.pages()[0]; await page.goto("/account");
    const value = await appSession(context);
    await signOut(page);
    assert.ok(!(await context.cookies(browserApp)).some(cookie => cookie.name === "authweave-session-local"));
    await page.goto(assessments[index][0]); await page.waitForURL(`${browserApp}/account`);
    const revoked = await fetch(`${browserApp}/account`, { headers: { Cookie: `authweave-session-local=${value}` },
      signal: AbortSignal.timeout(5000) });
    assert.ok((await revoked.text()).includes("You are not signed in."));
  }
  assert.equal(guidedRuns, 6); assert.equal(new Set(assessments.flat()).size, 6);
  assert.equal(authorizations, 4); assert.equal(callbacks, 4); assert.equal(blockedRequests, 0); assert.ok(!databaseInterrupted);
  success = true;
} catch {
  // Do not print error objects, API responses, request URLs, cookies, passwords or provider tokens.
  console.error(`Real ZITADEL browser check failed at: ${stage} (${detail}; callback status ${callbackStatus ?? "pending"}). No sensitive diagnostics were exported.`);
  process.exitCode = 1;
} finally {
  let cleanupFailed = false;
  for (const context of contexts.toReversed()) {
    try {
      if ((await context.cookies(browserApp)).some(cookie => cookie.name === "authweave-session-local")) {
        const page = context.pages()[0]; await page.goto(`${browserApp}/account`);
        await signOut(page);
      }
    } catch { cleanupFailed = true; }
    try { await context.close(); } catch { cleanupFailed = true; }
  }
  try { await browser?.close(); } catch { cleanupFailed = true; }
  try { await database?.end(); } catch { cleanupFailed = true; }
  try { await runtime?.close(); } catch { cleanupFailed = true; }
  if (cleanupFailed) {
    success = false; process.exitCode = 1;
    console.error("Real ZITADEL browser cleanup was not confirmed; inspect owned test processes locally.");
  }
}
if (success) console.log("Real ZITADEL browser: 2 users passed; 6 guided desktop/mobile flows, 30 form saves, 37 Review/brief rows per flow; 4 PKCE logins/callbacks, 2 same-account rotations, bidirectional isolation, explicit curator expectations, ordinary-user denial and logout verified. No source verdict or publication was recorded.");

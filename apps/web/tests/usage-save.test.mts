import assert from "node:assert/strict";
import { test } from "node:test";
import { readFile } from "node:fs/promises";
import { postUsagePlanning, usageReloadPath, usageWriteResponse, usageSaveFeedback,
  type UsageWriteOutcome } from "../src/lib/assessment/usage-save.ts";
import { usageMetrics } from "../src/lib/assessment/usage-planning.ts";

const id = "4640bbac-c20f-476a-a4dc-23efad5ff14f";
const action = `/api/assessments/${id}/usage-planning`;
function form() {
  const params = new URLSearchParams({ expectedVersion: "5", scopeDescription: "Fictional pilot <team>" });
  for (let i = 0; i < 10; i++) params.append("assumption", i === 0 ? "Pilot only" : "");
  for (const metric of usageMetrics) {
    params.set(`basis_${metric.key}`, "UNKNOWN"); params.set(`value_${metric.key}`, "");
  }
  params.set("basis_MONTHLY_M2M_TOKEN_ISSUANCES", "OBSERVED"); params.set("value_MONTHLY_M2M_TOKEN_ISSUANCES", "0");
  return params;
}

test("opt-in acknowledgements contain only target, submitted version and checked outcome", async () => {
  for (const [outcome, status] of [["saved", 200], ["conflict", 409], ["invalid", 422], ["locked", 423]] as const) {
    const response = usageWriteResponse(id, 5, outcome);
    assert.equal(response.status, status); assert.equal(response.headers.get("cache-control"), "no-store");
    assert.equal(response.headers.get("referrer-policy"), "no-referrer"); assert.equal(response.headers.get("vary"), "Accept");
    assert.deepEqual(await response.json(), { assessmentId: id, expectedVersion: 5, outcome });
    assert.equal(response.headers.get("location"), null);
  }
});

test("one same-origin form request preserves the exact body, zero and version without retries", async () => {
  for (const outcome of ["saved", "conflict", "invalid", "locked"] as const) {
    const params = form(); const before = params.toString(); let calls = 0;
    const fetcher: typeof fetch = async (input, init) => {
      calls++; assert.equal(input, action); assert.equal(init?.method, "POST"); assert.equal(init?.body, before);
      assert.deepEqual(init?.headers, { Accept: "application/json", "Content-Type": "application/x-www-form-urlencoded" });
      assert.equal(init?.credentials, "same-origin"); assert.equal(init?.mode, "same-origin");
      assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error"); assert.ok(init?.signal instanceof AbortSignal);
      return usageWriteResponse(id, 5, outcome);
    };
    assert.equal(await postUsagePlanning(action, params, fetcher), outcome);
    assert.equal(calls, 1); assert.equal(params.toString(), before);
  }
});

test("foreign or noncanonical actions never send form text or become reload destinations", async () => {
  let calls = 0; const fetcher: typeof fetch = async()=>{calls++; throw new Error("Unexpected request");};
  for (const target of ["https://private.example.test" + action, "//private.example.test" + action, action + "?next=/account",
    action + "#scope", action + "/", action.replace(id, id.toUpperCase()), "/api/assessments/../usage-planning", "/account"]) {
    assert.equal(await postUsagePlanning(target, form(), fetcher), "invalid"); assert.equal(usageReloadPath(target), null);
  }
  assert.equal(calls, 0); assert.equal(usageReloadPath(action), `/assessments/${id}?step=usage`);
});

test("invalid or forged form parameters cannot reach the mutation helper", async () => {
  let calls = 0; const fetcher: typeof fetch = async()=>{calls++; return usageWriteResponse(id, 5, "saved");};
  for (const mutate of [(p: URLSearchParams)=>p.set("value_MONTHLY_M2M_TOKEN_ISSUANCES", ""),
    (p: URLSearchParams)=>p.append("workspaceId", "foreign"), (p: URLSearchParams)=>p.append("expectedVersion", "6")]) {
    const params = form(); mutate(params); assert.equal(await postUsagePlanning(action, params, fetcher), "invalid");
  }
  assert.equal(calls, 0);
});

test("foreign targets, versions, outcomes or extra receipt fields cannot acknowledge a save", async () => {
  const receipt = { assessmentId: id, expectedVersion: 5, outcome: "saved" };
  const bad = [null, [], {}, { ...receipt, assessmentId: "0656d287-2f70-4f5d-9a14-d25f958ad988" },
    { ...receipt, expectedVersion: 6 }, { ...receipt, expectedVersion: "5" }, { ...receipt, outcome: "toString" },
    { ...receipt, outcome: "conflict" }, { ...receipt, profile: { private: "not displayed" } },
    { ...receipt, redirect: "https://other.example.test" }];
  for (const value of bad) assert.equal(await postUsagePlanning(action, form(), async()=>Response.json(value)), "uncertain");
});

test("all four status/outcome pairs are bound and cannot be interchanged", async () => {
  const pairs: Array<[UsageWriteOutcome, number]> = [["saved", 200], ["conflict", 409], ["invalid", 422], ["locked", 423]];
  for (const [outcome, intended] of pairs) for (const [, status] of pairs) {
    const value = await postUsagePlanning(action, form(), async()=>Response.json({ assessmentId: id, expectedVersion: 5, outcome }, { status }));
    assert.equal(value, status === intended ? outcome : "uncertain");
  }
});

test("redirects and successful-looking HTML never replace the form or supply a receipt", async () => {
  const redirected = usageWriteResponse(id, 5, "saved"); Object.defineProperty(redirected, "redirected", { value: true });
  for (const response of [redirected, Response.redirect("https://other.example.test", 303),
    new Response("<html>Private error details</html>", { headers: { "Content-Type": "text/html" } })]) {
    assert.equal(await postUsagePlanning(action, form(), async()=>response), "uncertain");
  }
});

test("missing, oversized or malformed receipt bodies remain uncertain without exposing raw text", async () => {
  const headers = { "Content-Type": "application/json" };
  const responses = [new Response(null, { headers }), new Response("not JSON: private detail", { headers }),
    new Response("x".repeat(1025), { headers }),
    new Response("{}", { headers: { ...headers, "Content-Length": "1025" } }),
    new Response("{}", { headers: { ...headers, "Content-Length": "invalid" } }),
    new Response(new Uint8Array([0xc3, 0x28]), { headers })];
  for (const response of responses) assert.equal(await postUsagePlanning(action, form(), async()=>response), "uncertain");
});

test("known access/input refusals are bounded outcomes, not upstream messages or permission grants", async () => {
  for (const [status, expected] of [[401, "signed-out"], [403, "forbidden"], [404, "not-found"],
    [400, "invalid"], [413, "invalid"], [415, "invalid"]] as const) {
    assert.equal(await postUsagePlanning(action, form(), async()=>new Response("Private upstream details", { status })), expected);
  }
  for (const status of [202, 429, 500, 503]) {
    assert.equal(await postUsagePlanning(action, form(), async()=>new Response("Private upstream details", { status })), "uncertain");
  }
});

test("timeouts and lost replies never declare failure, clear edits or trigger a second write", async () => {
  for (const error of [new TypeError("Private transport error"), new DOMException("Private timeout", "TimeoutError")]) {
    let calls = 0;
    assert.equal(await postUsagePlanning(action, form(), async()=>{calls++; throw error;}), "uncertain");
    assert.equal(calls, 1);
  }
  assert.ok(usageSaveFeedback.uncertain.text.includes("may have saved"));
  assert.ok(usageSaveFeedback.conflict.text.includes("was not saved"));
  assert.ok(usageSaveFeedback.conflict.text.includes("no automatic merge or overwrite"));
});

test("the UI freezes a pending submission and requires explicit discard before loading a fresh version", async () => {
  const source = await readFile(new URL("../src/app/assessments/[id]/usage-planning-form.tsx", import.meta.url), "utf8");
  assert.ok(source.includes("if (inFlight.current || reloadRequired.current || mustReload)"));
  assert.ok(source.includes("fieldset disabled={pending}")); assert.ok(source.includes("disabled={pending || mustReload}"));
  assert.ok(source.includes("reloadDialog.current.showModal()"));
  assert.ok(source.includes("!reloadDialog.current?.open"));
  assert.ok(source.includes("onCancel={event => { event.preventDefault(); cancelReload(); }}"));
  assert.ok(source.includes("if (!submittedForm.isConnected) return"));
  assert.ok(source.includes('if (outcome === "saved" && reloadPath)'));
  assert.ok(source.includes("originally loaded in this tab, not the current server version"));
  for (const unsupported of ["localStorage", "sessionStorage", "router.refresh", "setInterval", ".requestSubmit("]) assert.equal(source.includes(unsupported), false);
  const workflow = await readFile(new URL("../src/app/assessments/[id]/assessment-workflow.tsx", import.meta.url), "utf8");
  assert.ok(workflow.includes("if (saving.current) return;")); assert.ok(workflow.includes("!dirty.current && !saving.current"));
});

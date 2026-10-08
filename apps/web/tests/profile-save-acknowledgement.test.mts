import assert from "node:assert/strict";
import { test } from "node:test";
import { savedProfileMatches, profileSaveAcknowledgementByteLimit as byteLimit } from "../src/lib/assessment/profile-save-acknowledgement.ts";
import { acknowledgementProfile, acknowledgementSections, acknowledgementWriters, reverseProfileObjectsAndSets } from "./fixtures/profile-acknowledgement.mts";
import { profileSaveFixtureId as id } from "./fixtures/profile-save.mts";

const session = { workspaceId: "70000000-0000-4000-8000-000000000001", issuer: "http://localhost:8081",
  subject: "synthetic-acknowledgement-owner", email: null, displayName: null, authenticatedAt: new Date() };
const envelope = (profile: unknown, version = 8) => ({ id, workspaceId: session.workspaceId,
  status: "DRAFT", version, profileSchemaVersion: 6, profile });

function leaves(value: unknown, path: string[] = []): string[][] {
  if (!value || typeof value !== "object" || Array.isArray(value) || !Object.keys(value).length) return [path];
  return Object.entries(value).flatMap(([key, item]) => leaves(item, [...path, key]));
}
function replaceAt(profile: Record<string, unknown>, path: string[], remove: boolean) {
  let parent = profile;
  for (const key of path.slice(0, -1)) parent = parent[key] as Record<string, unknown>;
  if (remove) delete parent[path.at(-1)!];
  else parent[path.at(-1)!] = typeof parent[path.at(-1)!] === "string" ? "synthetic-altered-value" : "synthetic-wrong-type";
}

test("profile matching tolerates only the six declared set orders, never missing, duplicate, coerced or ordered-list changes", () => {
  const profile = acknowledgementProfile(), before = structuredClone(profile);
  assert.equal(savedProfileMatches(profile, reverseProfileObjectsAndSets(profile)), true);
  for (const path of [["application", "clients"], ["audience", "populations"], ["security", "complianceTargets"],
    ["security", "dataResidencyDetails", "allowedCountries"], ["security", "dataResidencyDetails", "dataCategories"],
    ["security", "auditabilityRequirements", "selectedCriteria"]]) {
    let parent: Record<string, unknown> = profile;
    for (const key of path.slice(0, -1)) parent = parent[key] as Record<string, unknown>;
    const values = parent[path.at(-1)!] as string[];
    assert.ok(values.length > 1);
    for (const replacement of [values.slice(1), [...values, values[0]], [values[0], values[0]], [...values.slice(1), {}]]) {
      const changed = structuredClone(profile); let target: Record<string, unknown> = changed;
      for (const key of path.slice(0, -1)) target = target[key] as Record<string, unknown>;
      target[path.at(-1)!] = replacement;
      assert.equal(savedProfileMatches(profile, changed), false);
    }
  }
  const reordered = structuredClone(profile); reordered.operations.usagePlanning.assumptions.reverse();
  assert.equal(savedProfileMatches(profile, reordered), false);
  assert.equal(savedProfileMatches({ "application.clients": ["BROWSER", "NATIVE_MOBILE"] }, { "application.clients": ["NATIVE_MOBILE", "BROWSER"] }), false);
  for (const [left, right] of [[0, "0"], [null, undefined], [[], {}], [{ a: null }, {}], [{ a: 0 }, { a: 0, b: false }], [NaN, NaN], [undefined, undefined]]) {
    assert.equal(savedProfileMatches(left, right), false);
  }
  let nested: unknown = "value";
  for (let index = 0; index < 16; index++) nested = { child: nested };
  assert.equal(savedProfileMatches(nested, structuredClone(nested)), true);
  assert.equal(savedProfileMatches({ child: nested }, { child: structuredClone(nested) }), false);
  assert.deepEqual(profile, before);
});

for (const section of acknowledgementSections) {
  const writer = acknowledgementWriters[section];
  test(`${section}: every submitted and preserved leaf must be acknowledged, independently of object and set order`, async () => {
    const previousFetch = globalThis.fetch, previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-acknowledgement-token-000000000000000000";
    let current: Record<string, unknown> = acknowledgementProfile(), version = 8;
    let mutate = (_profile: Record<string, unknown>) => {}, reads = 0, writes = 0;
    globalThis.fetch = async (target, init) => {
      assert.equal(String(target), `http://127.0.0.1:8080/api/v6/workspaces/${session.workspaceId}/assessments/${id}${init?.method === "PUT" ? "/profile" : ""}`);
      assert.equal(init?.cache, "no-store"); assert.equal(init?.redirect, "error"); assert.ok(init?.signal instanceof AbortSignal);
      if (init?.method === "GET") { reads++; return Response.json(envelope(current, 7)); }
      writes++; assert.equal(init?.method, "PUT");
      const payload = JSON.parse(String(init?.body)); assert.equal(payload.expectedVersion, 7);
      assert.deepEqual(payload.profile, writer.patch(current));
      mutate(payload.profile);
      return Response.json(envelope(reverseProfileObjectsAndSets(payload.profile), version));
    };
    try {
      const before = structuredClone(current);
      assert.equal(await writer.save(session, id, 7), "saved");
      const expected = writer.patch(current);
      for (const path of leaves(expected)) for (const remove of [false, true]) {
        mutate = profile => replaceAt(profile, path, remove);
        await assert.rejects(writer.save(session, id, 7));
      }
      mutate = profile => { (profile.operations as Record<string, unknown>).privateReply = "synthetic-sensitive-reply"; };
      await assert.rejects(writer.save(session, id, 7));
      mutate = () => {}; version = 7;
      await assert.rejects(writer.save(session, id, 7), /response is invalid/); // A changed profile cannot keep its old version.
      assert.deepEqual(current, before);
      current = expected; assert.equal(await writer.save(session, id, 7), "saved"); // Genuine no-op.
      for (const invalidVersion of [6, 9]) { version = invalidVersion; await assert.rejects(writer.save(session, id, 7)); }
      version = 8; assert.equal(await writer.save(session, id, 7), "saved");
      const beforeConflict = writes; assert.equal(await writer.save(session, id, 6), "conflict"); assert.equal(writes, beforeConflict);
      assert.equal(reads, writes + 1); assert.deepEqual(before, acknowledgementProfile());
    } finally {
      globalThis.fetch = previousFetch;
      if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
    }
  });

  test(`${section}: save replies require JSON and bounded actual UTF-8 bytes with stream cancellation`, async () => {
    const previousFetch = globalThis.fetch, previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
    process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-acknowledgement-token-000000000000000000";
    const current = acknowledgementProfile(), expected = writer.patch(current);
    const json = JSON.stringify(envelope(expected)), bytes = new TextEncoder().encode(json).length;
    let reply = () => Response.json(envelope(expected)), writes = 0;
    globalThis.fetch = async (_target, init) => {
      if (init?.method === "GET") return Response.json(envelope(current, 7));
      writes++; return reply();
    };
    try {
      reply = () => new Response(json + " ".repeat(byteLimit - bytes), { headers: { "Content-Type": "application/json; charset=utf-8" } });
      assert.equal(await writer.save(session, id, 7), "saved");
      for (const failure of [() => new Response(json, { headers: { "Content-Type": "text/html" } }),
        () => { const response = Response.json(envelope(expected)); Object.defineProperty(response, "redirected", { value: true }); return response; },
        () => new Response("{synthetic-sensitive-reply", { headers: { "Content-Type": "application/json" } }),
        () => new Response(json, { headers: { "Content-Type": "application/json", "Content-Length": String(byteLimit + 1) } }),
        () => Response.json({ ...envelope(expected), workspaceId: "70000000-0000-4000-8000-000000000002" }),
        () => Response.json({ ...envelope(expected), status: "ARCHIVED" })]) {
        reply = failure; await assert.rejects(writer.save(session, id, 7));
      }
      let cancelled = false;
      reply = () => new Response(new ReadableStream({ start(controller) {
        controller.enqueue(new TextEncoder().encode(json + " ".repeat(byteLimit - bytes + 1)));
      }, cancel() { cancelled = true; } }), { headers: { "Content-Type": "application/json", "Content-Length": "1" } });
      await assert.rejects(writer.save(session, id, 7)); assert.equal(cancelled, true);
      reply = () => new Response(new Uint8Array([0xff]), { headers: { "Content-Type": "application/json" } });
      await assert.rejects(writer.save(session, id, 7)); assert.equal(writes, 9);
    } finally {
      globalThis.fetch = previousFetch;
      if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
    }
  });
}

test("the Core write deadline also interrupts a held acknowledgement body and releases its reader", async t => {
  const previousFetch = globalThis.fetch, previousToken = process.env.AUTHWEAVE_CORE_SERVICE_TOKEN;
  process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = "synthetic-acknowledgement-token-000000000000000000";
  const controller = new AbortController();
  t.mock.method(AbortSignal, "timeout", (duration: number) => { assert.equal(duration, 3_000); return controller.signal; });
  let cancelled = false, reply: Response | undefined;
  globalThis.fetch = async (_target, init) => {
    if (init?.method === "GET") return Response.json(envelope(acknowledgementProfile(), 7));
    reply = new Response(new ReadableStream({ start() { setTimeout(() => controller.abort(), 0); }, cancel() { cancelled = true; } }),
      { headers: { "Content-Type": "application/json" } });
    return reply;
  };
  try {
    await assert.rejects(acknowledgementWriters.usage.save(session, id, 7));
    assert.equal(cancelled, true); assert.equal(reply?.body?.locked, false);
  } finally {
    globalThis.fetch = previousFetch;
    if (previousToken === undefined) delete process.env.AUTHWEAVE_CORE_SERVICE_TOKEN; else process.env.AUTHWEAVE_CORE_SERVICE_TOKEN = previousToken;
  }
});

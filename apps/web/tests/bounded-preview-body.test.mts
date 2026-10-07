import assert from "node:assert/strict";
import { test } from "node:test";
import { boundedPrerequisiteText, InvalidPrerequisiteForm } from "../src/lib/assessment/architecture-prerequisites.ts";
import { chunkedPreviewResponse } from "./fixtures/preview-stream.mts";

function observeListeners(signal: AbortSignal) {
  const added: unknown[][] = [], removed: unknown[][] = [];
  const add = signal.addEventListener.bind(signal), remove = signal.removeEventListener.bind(signal);
  Object.defineProperty(signal, "addEventListener", { value: (...args: Parameters<typeof add>) => { added.push(args); add(...args); } });
  Object.defineProperty(signal, "removeEventListener", { value: (...args: Parameters<typeof remove>) => { removed.push(args); remove(...args); } });
  return { added, removed };
}

test("the byte boundary accepts exact-size UTF-8 even when multi-byte characters cross chunks", async () => {
  const text = "aé😀z", bytes = new TextEncoder().encode(text);
  assert.equal(bytes.length, 8);
  const headerCases: HeadersInit[] = [{}, { "Content-Length": "8" }];
  for (const headers of headerCases) {
    const streamed = chunkedPreviewResponse([bytes.slice(0, 2), bytes.slice(2, 5), bytes.slice(5)], headers);
    assert.equal(await boundedPrerequisiteText(streamed.response, 8), text);
    assert.equal(streamed.cancellations(), 0); assert.equal(streamed.response.body!.locked, false);
  }
});

test("actual bytes override absent or understated Content-Length and overflow stops before unread chunks", async () => {
  const headerCases: HeadersInit[] = [{}, { "Content-Length": "1" }, { "Content-Length": "0" }];
  for (const headers of headerCases) {
    const streamed = chunkedPreviewResponse([new Uint8Array(4), new Uint8Array(5), new Uint8Array(20)], headers);
    await assert.rejects(boundedPrerequisiteText(streamed.response, 8), RangeError);
    assert.equal(streamed.pulls(), 2); assert.equal(streamed.cancellations(), 1);
    assert.equal(streamed.response.body!.locked, false);
  }
});

test("invalid or oversized declared length cancels the body without reading any chunk", async () => {
  for (const length of ["9", "-1", "1.5", "not-a-number"]) {
    const streamed = chunkedPreviewResponse([new Uint8Array(8)], { "Content-Length": length });
    await assert.rejects(boundedPrerequisiteText(streamed.response, 8), RangeError);
    assert.equal(streamed.pulls(), 0); assert.equal(streamed.cancellations(), 1);
    assert.equal(streamed.response.body!.locked, false);
  }
});

test("fatal UTF-8 rejects malformed and truncated encodings and always releases the reader", async () => {
  const malformed = chunkedPreviewResponse([new Uint8Array([0xff]), new Uint8Array(1)]);
  await assert.rejects(boundedPrerequisiteText(malformed.response, 8), TypeError);
  assert.equal(malformed.pulls(), 1); assert.equal(malformed.cancellations(), 1);
  const truncated = chunkedPreviewResponse([new Uint8Array([0xe2])]);
  await assert.rejects(boundedPrerequisiteText(truncated.response, 8), TypeError);
  assert.equal(malformed.response.body!.locked, false); assert.equal(truncated.response.body!.locked, false);
});

test("an already-aborted signal cancels the unread body and preserves the original abort reason", async () => {
  const controller = new AbortController(), reason = new Error("synthetic abort"); controller.abort(reason);
  const streamed = chunkedPreviewResponse([new Uint8Array(1)]);
  await assert.rejects(boundedPrerequisiteText(streamed.response, 8, controller.signal), error => error === reason);
  assert.equal(streamed.pulls(), 0); assert.equal(streamed.cancellations(), 1);
});

test("abort settles a pending read even when the source never resolves read or cancel", { timeout: 1000 }, async () => {
  let began: () => void = () => {}, cancellations = 0;
  const reading = new Promise<void>(resolve => { began = resolve; });
  const response = new Response(new ReadableStream<Uint8Array>({
    pull() { began(); return new Promise<void>(() => {}); },
    cancel() { cancellations++; return new Promise<void>(() => {}); },
  }, { highWaterMark: 0 }));
  const controller = new AbortController(), reason = new DOMException("synthetic deadline", "TimeoutError");
  const pending = boundedPrerequisiteText(response, 8, controller.signal);
  await reading; controller.abort(reason);
  await assert.rejects(pending, error => error === reason);
  assert.equal(cancellations, 1); assert.equal(response.body!.locked, false);
});

test("a rejected cancellation cannot hide an overflow RangeError or retain the reader lock", async () => {
  const response = new Response(new ReadableStream<Uint8Array>({
    pull(controller) { controller.enqueue(new Uint8Array(9)); },
    cancel() { return Promise.reject(new Error("synthetic cancel failure")); },
  }, { highWaterMark: 0 }));
  await assert.rejects(boundedPrerequisiteText(response, 8), RangeError);
  assert.equal(response.body!.locked, false);
});

test("successful reading removes its exact abort listener before a later abort", async () => {
  const controller = new AbortController(), listeners = observeListeners(controller.signal);
  const streamed = chunkedPreviewResponse([new TextEncoder().encode("valid")]);
  assert.equal(await boundedPrerequisiteText(streamed.response, 8, controller.signal), "valid");
  assert.equal(listeners.added.length, 1); assert.equal(listeners.removed.length, 1);
  assert.equal(listeners.added[0][1], listeners.removed[0][1]); controller.abort();
  assert.equal(streamed.cancellations(), 0); assert.equal(streamed.response.body!.locked, false);
});

test("stream failure removes the listener, releases the lock and empty bodies remain invalid", async () => {
  const reason = new Error("synthetic broken stream"), controller = new AbortController();
  const listeners = observeListeners(controller.signal);
  const response = new Response(new ReadableStream<Uint8Array>({ pull(controller) { controller.error(reason); } }, { highWaterMark: 0 }));
  await assert.rejects(boundedPrerequisiteText(response, 8, controller.signal), error => error === reason);
  assert.equal(listeners.removed.length, 1); assert.equal(listeners.added[0][1], listeners.removed[0][1]);
  assert.equal(response.body!.locked, false);
  await assert.rejects(boundedPrerequisiteText(new Response(null), 8), InvalidPrerequisiteForm);
});

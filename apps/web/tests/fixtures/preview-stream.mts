// Actual Response streams for transport regressions; no network, account or Core evaluation.
export function deferredJsonResponse(value: Promise<unknown>) {
  let markReading: () => void = () => {};
  const reading = new Promise<void>(resolve => { markReading = resolve; });
  let canceled = false;
  const body = new ReadableStream<Uint8Array>({
    async pull(controller) {
      markReading();
      const result = await value;
      if (canceled) return;
      controller.enqueue(new TextEncoder().encode(JSON.stringify(result))); controller.close();
    },
    cancel() { canceled = true; },
  }, { highWaterMark: 0 });
  return { response: new Response(body), reading, canceled: () => canceled };
}

export function chunkedPreviewResponse(chunks: Uint8Array[], headers: HeadersInit = {}) {
  let pulls = 0, cancellations = 0;
  const body = new ReadableStream<Uint8Array>({
    pull(controller) {
      const chunk = chunks[pulls++];
      if (chunk) controller.enqueue(chunk); else controller.close();
    },
    cancel() { cancellations++; },
  }, { highWaterMark: 0 });
  return { response: new Response(body, { headers }), pulls: () => pulls, cancellations: () => cancellations };
}

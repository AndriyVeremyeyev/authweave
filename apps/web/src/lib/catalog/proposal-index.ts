const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const UTC_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/;

export type ProposalReviewCursor = { createdAt: string; id: string };
export type ProposalReviewIndexItem = {
  proposalId: string;
  version: number;
  proposalSha256: string;
  createdAt: string;
  updatedAt: string;
  rejectionRecorded: boolean;
};
export type ProposalReviewIndexPage = {
  items: ProposalReviewIndexItem[];
  nextBefore: ProposalReviewCursor | null;
};

function object(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown> : null;
}
function instant(value: unknown): value is string {
  return typeof value === "string" && UTC_INSTANT.test(value) && Number.isFinite(Date.parse(value));
}

/** Preserve sub-millisecond PostgreSQL cursor precision instead of normalizing with Date. */
export function parseProposalReviewCursor(value: unknown): ProposalReviewCursor | null {
  if (value === null) return null;
  const cursor = object(value);
  if (!cursor || !instant(cursor.createdAt) || typeof cursor.id !== "string" || !UUID.test(cursor.id)) {
    throw new Error("Invalid proposal index cursor");
  }
  return { createdAt: cursor.createdAt, id: cursor.id };
}

/** Accept only bounded, body-free summaries from Core after curator authorization. */
export function proposalIndexFromCore(value: unknown): ProposalReviewIndexPage {
  const page = object(value);
  if (!page || Object.keys(page).some(key => !["items", "nextBefore"].includes(key)) ||
      !Array.isArray(page.items) || page.items.length > 20) {
    throw new Error("Invalid proposal index page");
  }
  const items = page.items.map((value: unknown) => {
    const row = object(value);
    if (!row || typeof row.proposalId !== "string" || !UUID.test(row.proposalId) ||
        !Number.isSafeInteger(row.version) || Number(row.version) < 0 ||
        typeof row.proposalSha256 !== "string" || !SHA256.test(row.proposalSha256) ||
        !instant(row.createdAt) || !instant(row.updatedAt) ||
        typeof row.rejectionRecorded !== "boolean" ||
        Object.keys(row).some(key => !["proposalId", "version", "proposalSha256",
          "createdAt", "updatedAt", "rejectionRecorded"].includes(key))) {
      throw new Error("Invalid proposal index item");
    }
    return row as ProposalReviewIndexItem;
  });
  if (new Set(items.map(row => row.proposalId)).size !== items.length) {
    throw new Error("Duplicate proposal index item");
  }
  const nextBefore = parseProposalReviewCursor(page.nextBefore);
  const last = items.at(-1);
  if (nextBefore && (items.length !== 20 || nextBefore.createdAt !== last?.createdAt ||
      nextBefore.id !== last.proposalId)) {
    throw new Error("Invalid proposal index page cursor");
  }
  return { items, nextBefore };
}

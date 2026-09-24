const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const SHA256 = /^[0-9a-f]{64}$/;

export type ReviewOptionChange = {
  optionId: string;
  changeType: "ADDED" | "REMOVED" | "MODIFIED";
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
};
export type ReviewFactChange = {
  optionId: string;
  path: string;
  factKind: string;
  changeType: "ADDED" | "REMOVED" | "MODIFIED";
  aspects: string[];
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
};
export type CatalogProposalReview = {
  proposalId: string;
  version: number;
  proposalSha256: string;
  recordedAt: string;
  rationale: string;
  baseCatalogVersion: string;
  candidateCatalogVersion: string;
  affectedOptionIds: string[];
  optionChanges: ReviewOptionChange[];
  factChanges: ReviewFactChange[];
};

function object(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown> : null;
}

function string(value: unknown): value is string {
  return typeof value === "string" && value.length > 0;
}

function fact(value: unknown): Record<string, unknown> | null | undefined {
  if (value === null) return null;
  return object(value) ?? undefined;
}

function changes(value: unknown, max: number, kind: "fact" | "option") {
  if (!Array.isArray(value) || value.length > max) throw new Error("Core proposal review is invalid");
  return value.map((entry: unknown) => {
    const row = object(entry);
    const before = fact(row?.before);
    const after = fact(row?.after);
    if (!row || !string(row.optionId) ||
        !["ADDED", "REMOVED", "MODIFIED"].includes(String(row.changeType)) ||
        before === undefined || after === undefined ||
        (row.changeType === "ADDED" && (before !== null || after === null)) ||
        (row.changeType === "REMOVED" && (before === null || after !== null)) ||
        (row.changeType === "MODIFIED" && (before === null || after === null))) {
      throw new Error("Core proposal review is invalid");
    }
    if (kind === "option") return {
      optionId: row.optionId,
      changeType: row.changeType as ReviewOptionChange["changeType"], before, after,
    };
    if (!string(row.path) || !string(row.factKind) || !Array.isArray(row.aspects) ||
        row.aspects.length === 0 || !row.aspects.every(string)) {
      throw new Error("Core proposal review is invalid");
    }
    return {
      optionId: row.optionId, path: row.path, factKind: row.factKind,
      changeType: row.changeType as ReviewFactChange["changeType"], aspects: row.aspects as string[],
      before, after,
    };
  });
}

/** Project only an exact, unapproved stored preview into a safe, read-only UI model. */
export function proposalReviewFromCore(value: unknown, id: string): CatalogProposalReview {
  const snapshot = object(value);
  const request = object(snapshot?.request);
  const preview = object(snapshot?.preview);
  const base = object(request?.base);
  const candidate = object(request?.candidate);
  if (!snapshot || snapshot.proposalId !== id || !UUID.test(id) ||
      !Number.isSafeInteger(snapshot.version) || Number(snapshot.version) < 0 ||
      typeof snapshot.proposalSha256 !== "string" || !SHA256.test(snapshot.proposalSha256) ||
      snapshot.state !== "PROPOSED" || snapshot.requestSchemaVersion !== 1 ||
      !string(snapshot.recordedAt) || !Number.isFinite(Date.parse(snapshot.recordedAt)) ||
      !request || request.proposalId !== id || request.schemaVersion !== 1 ||
      !string(request.rationale) || !base || !candidate ||
      !string(base.catalogVersion) || !string(candidate.catalogVersion) ||
      !preview || preview.proposalId !== id || preview.proposalSha256 !== snapshot.proposalSha256 ||
      preview.rationale !== request.rationale || preview.proposalState !== "PROPOSED" ||
      preview.status !== "REVIEW_REQUIRED" || preview.diffComputed !== true ||
      preview.baselineVerified !== false || preview.sourceVerificationPerformed !== false ||
      preview.approvalGranted !== false || preview.writesPerformed !== false ||
      preview.evaluationReady !== false || preview.impactAnalysisPerformed !== false ||
      !Array.isArray(preview.blockers) || preview.blockers.length !== 0 ||
      !Array.isArray(preview.affectedOptionIds) || preview.affectedOptionIds.length > 200 ||
      !preview.affectedOptionIds.every(string)) {
    throw new Error("Core proposal review is invalid");
  }
  return {
    proposalId: id, version: snapshot.version as number,
    proposalSha256: snapshot.proposalSha256,
    recordedAt: snapshot.recordedAt,
    rationale: request.rationale,
    baseCatalogVersion: base.catalogVersion,
    candidateCatalogVersion: candidate.catalogVersion,
    affectedOptionIds: preview.affectedOptionIds as string[],
    optionChanges: changes(preview.optionChanges, 200, "option") as ReviewOptionChange[],
    factChanges: changes(preview.factChanges, 13600, "fact") as ReviewFactChange[],
  };
}

/** Provenance is displayed as inert text, never fetched or treated as verified. */
export function sourceDetails(value: Record<string, unknown> | null):
    { sourceUrl: string; observedAt: string; summary: string } | null {
  const evidence = object(value?.evidence);
  return evidence && string(evidence.sourceUrl) && string(evidence.observedAt) &&
      string(evidence.summary)
    ? { sourceUrl: evidence.sourceUrl, observedAt: evidence.observedAt, summary: evidence.summary }
    : null;
}

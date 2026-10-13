import { resultUuid, type ResultItem } from "./decision-results.ts";

export type CatalogReference = ResultItem["catalog"];
export type CatalogChoice = { reference: CatalogReference; publishedAt: string };
export type CatalogChoices = { scope: "OWNED_ASSESSMENT_CATALOG_CHOICES"; workspaceId: string; assessmentId: string;
  items: CatalogChoice[]; nextBefore: CatalogReference | null; historicalPublicationWorkflowVerified: false };
export const catalogChoicesByteLimit = 16_384;
const invalid = (): never => { throw new Error("Invalid published catalog choices"); };
function object(value: unknown, keys: string[]): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value) || Object.keys(value).length !== keys.length || keys.some(k => !Object.hasOwn(value, k))) invalid();
  return value as Record<string, unknown>;
}
export function catalogReference(value: unknown): CatalogReference {
  const r = object(value, ["snapshotId", "catalogVersion", "snapshotSha256"]);
  if (typeof r.snapshotId !== "string" || !resultUuid.test(r.snapshotId) || typeof r.catalogVersion !== "string" ||
      !/^[a-z0-9][a-z0-9.-]{0,99}$/.test(r.catalogVersion) || typeof r.snapshotSha256 !== "string" || !/^[a-f0-9]{64}$/.test(r.snapshotSha256)) invalid();
  return { snapshotId: r.snapshotId as string, catalogVersion: r.catalogVersion as string, snapshotSha256: r.snapshotSha256 as string };
}
export function sameCatalog(a: CatalogReference, b: CatalogReference): boolean {
  return a.snapshotId === b.snapshotId && a.catalogVersion === b.catalogVersion && a.snapshotSha256 === b.snapshotSha256;
}
const cursorKeys = ["beforeSnapshotId", "beforeCatalogVersion", "beforeSnapshotSha256"];
export function catalogCursor(query: Record<string, string | string[] | undefined>): CatalogReference | null {
  if (!Object.keys(query).length) return null;
  if (Object.keys(query).length !== 3 || Object.keys(query).some(k => !cursorKeys.includes(k)) || cursorKeys.some(k => typeof query[k] !== "string")) invalid();
  return catalogReference({ snapshotId: query.beforeSnapshotId, catalogVersion: query.beforeCatalogVersion, snapshotSha256: query.beforeSnapshotSha256 });
}
export function catalogCursorQuery(value: CatalogReference): string {
  const r = catalogReference(value);
  return new URLSearchParams({ beforeSnapshotId: r.snapshotId, beforeCatalogVersion: r.catalogVersion, beforeSnapshotSha256: r.snapshotSha256 }).toString();
}
export function catalogChoicesFromCore(value: unknown, workspaceId: string, assessmentId: string, before: CatalogReference | null): CatalogChoices {
  const r = object(value, ["scope", "workspaceId", "assessmentId", "items", "nextBefore", "historicalPublicationWorkflowVerified"]);
  if (r.scope !== "OWNED_ASSESSMENT_CATALOG_CHOICES" || r.workspaceId !== workspaceId || r.assessmentId !== assessmentId ||
      r.historicalPublicationWorkflowVerified !== false || !Array.isArray(r.items) || r.items.length > 20) invalid();
  const items = (r.items as unknown[]).map(v => {
    const i = object(v, ["reference", "publishedAt"]);
    if (typeof i.publishedAt !== "string" || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/.test(i.publishedAt) || Number.isNaN(Date.parse(i.publishedAt))) invalid();
    return { reference: catalogReference(i.reference), publishedAt: i.publishedAt as string };
  });
  const nextBefore = r.nextBefore === null ? null : catalogReference(r.nextBefore);
  if (new Set(items.map(i => i.reference.snapshotId)).size !== items.length ||
      new Set(items.map(i => i.reference.catalogVersion)).size !== items.length ||
      (before && items.some(i => i.reference.snapshotId === before.snapshotId)) ||
      (nextBefore && (items.length !== 20 || !sameCatalog(nextBefore, items.at(-1)!.reference)))) invalid();
  return { scope: "OWNED_ASSESSMENT_CATALOG_CHOICES", workspaceId, assessmentId, items, nextBefore, historicalPublicationWorkflowVerified: false };
}

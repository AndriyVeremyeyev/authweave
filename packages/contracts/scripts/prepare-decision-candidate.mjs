import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { inspectScopedBaselineDraft } from "./inspect-provider-baselines.mjs";

const readJson = async url => JSON.parse(await readFile(url, "utf8"));
const schema = await readJson(new URL("../schemas/decision-catalog-candidate.v1.schema.json", import.meta.url));
const draftSchema = await readJson(new URL("../schemas/provider-catalog-draft.v1.schema.json", import.meta.url));
const policy = await readJson(new URL("../decision-core/policy.v1.json", import.meta.url));
const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);
ajv.addSchema(draftSchema);
ajv.addSchema(schema);
const validateManifest = ajv.getSchema(`${schema.$id}#/$defs/selectionManifest`);
const validateAssembly = ajv.getSchema(schema.$id);
const baselineRoot = new URL("../../../services/core-api/src/main/resources/catalog/baselines/scoped/", import.meta.url);

function requireCondition(condition, message) {
  if (!condition) throw new Error(message);
}

// Decision bindings preserve array order. The existing bootstrap review digest does not.
function canonical(value, unorderedArrays = false) {
  if (Array.isArray(value)) {
    const items = value.map(item => canonical(item, unorderedArrays));
    return unorderedArrays ? items.sort((left, right) => {
      const a = JSON.stringify(left), b = JSON.stringify(right);
      return a < b ? -1 : a > b ? 1 : 0;
    }) : items;
  }
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key], unorderedArrays)]));
  }
  return value;
}

export const decisionDigest = value => createHash("sha256").update(JSON.stringify(canonical(value)), "utf8").digest("hex");
export const bootstrapDigest = value => createHash("sha256").update(JSON.stringify(canonical(value, true)), "utf8").digest("hex");

export async function readDecisionSelection() {
  return readJson(new URL("../decision-core/catalog-selection.v1.json", import.meta.url));
}

/** Fixed repository selection only. No caller-selected paths, URL fetches, writes or authority. */
export async function readSelectedDrafts() {
  const manifest = await readDecisionSelection();
  requireCondition(validateManifest(manifest), `Invalid selection: ${ajv.errorsText(validateManifest.errors)}`);
  return Promise.all(manifest.selections.map(async selection => ({
    file: selection.file, draft: await readJson(new URL(selection.file, baselineRoot)),
  })));
}

function checkScope(selection, option) {
  const products = {
    "authkit-staging": "WorkOS AuthKit",
    "connect-staging": "WorkOS AuthKit Connect",
    "directory-sync-staging": "WorkOS Directory Sync",
  };
  if (selection.providerId === "workos") requireCondition(option.product === products[selection.scope], "WorkOS products must remain separate");
  if (selection.providerId === "entra-external-id") {
    requireCondition(option.plan.includes("M2M Premium") === (selection.scope === "separate-m2m-premium-addon"),
      "Entra M2M Premium cannot be inherited by Basic");
  }
}

/** Assembly is a build step, not a provider evaluator, source review, approval or publisher. */
export function assembleDecisionCandidate(manifest, drafts) {
  requireCondition(validateManifest(manifest), `Invalid selection: ${ajv.errorsText(validateManifest.errors)}`);
  requireCondition(manifest.policyVersion === policy.policyVersion, "Selection policy drift");
  const expectedScopes = new Set(policy.providerInventory.flatMap(provider => provider.scopes.map(scope => `${provider.providerId}/${scope}`)));
  requireCondition(Array.isArray(drafts) && drafts.length === manifest.selections.length, "Incomplete selected draft inputs");
  const byFile = new Map(drafts.map(source => [source.file, source.draft]));
  requireCondition(byFile.size === drafts.length && new Set(drafts.map(source => source.draft.catalogVersion)).size === drafts.length,
    "Duplicate source file or draft version");
  const seenIds = new Set(), seenFiles = new Set(), tasks = [];
  const options = manifest.selections.map(selection => {
    requireCondition(expectedScopes.delete(`${selection.providerId}/${selection.scope}`), "Duplicate or unplanned provider scope");
    requireCondition(!seenIds.has(selection.optionId) && !seenFiles.has(selection.file), "Duplicate selection identity or file");
    seenIds.add(selection.optionId); seenFiles.add(selection.file);
    const draft = byFile.get(selection.file);
    requireCondition(draft && draft.catalogVersion === selection.catalogVersion && decisionDigest(draft) === selection.sourceSha256,
      "Pinned source content changed; explicitly revise selection and review the new payload");
    const option = draft.options[0];
    requireCondition(draft.options.length === 1 && option.id === selection.optionId && option.providerId === selection.providerId,
      "Source option identity mismatch");
    checkScope(selection, option);
    // This fixed inspection instant validates the existing registry, not evidence freshness for a future calculation.
    const inspected = inspectScopedBaselineDraft(draft, new Date("2026-10-09T00:00:00Z")).options[0];
    for (const entry of inspected.facts) {
      const fact = entry.path.split(".").reduce((value, key) => value[key], option);
      tasks.push({ optionId: option.id, factPath: entry.path,
        claimSha256: decisionDigest({ optionId: option.id, optionSha256: decisionDigest(option), factPath: entry.path, fact }), verdict: null });
    }
    return structuredClone(option);
  });
  requireCondition(expectedScopes.size === 0, "Missing planned provider scopes");
  const candidate = { schemaVersion: 1, kind: "PROVIDER_CATALOG_DRAFT", catalogVersion: manifest.catalogVersion, options };
  const result = {
    schemaVersion: 1, scope: "UNREVIEWED_DECISION_CATALOG_ASSEMBLY", selection: structuredClone(manifest),
    bindings: { selectionSha256: decisionDigest(manifest), decisionCanonicalization: policy.bindingDigest,
      decisionCatalogSha256: decisionDigest(candidate), bootstrapCanonicalization: "catalog-draft-canonical-json-1",
      bootstrapCandidateSha256: bootstrapDigest(candidate) },
    candidate, reviewTasks: tasks.sort((a, b) => a.optionId < b.optionId ? -1 : a.optionId > b.optionId ? 1 : a.factPath < b.factPath ? -1 : a.factPath > b.factPath ? 1 : 0),
    approvalGranted: false, sourceVerificationPerformed: false, publicationReady: false,
    limitations: [
      "Each option retains its exact product, plan, region and configuration. Facts from other drafts are not inherited or merged.",
      "All recorded claims require an explicit authorized human source-review verdict. This assembly supplies no verdict or confirmation.",
      "Omitted capability, context, residency, authentication and auditability facts remain unknown, not supported or unavailable.",
      "The selected options do not yet establish a positive end-to-end acceptance case. Required missing or unreviewed evidence blocks eligibility.",
      "Freshness must be checked at calculation/review time using original observedAt instants. Assembly does not refresh evidence.",
      "Staging/free labels do not establish production entitlement or zero cost. No vendor integration, deployment or compliance has been verified.",
      "Use bootstrapCandidateSha256 only for the existing bootstrap review canonicalization; decisionCatalogSha256 is a different, array-order-preserving binding.",
    ],
  };
  requireCondition(validateAssembly(result), `Invalid assembly: ${ajv.errorsText(validateAssembly.errors)}`);
  return result;
}

export async function prepareDecisionCandidate() {
  return assembleDecisionCandidate(await readDecisionSelection(), await readSelectedDrafts());
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    requireCondition(process.argv.length === 2, "This command accepts no paths, URLs, verdicts or options");
    console.log(JSON.stringify(await prepareDecisionCandidate(), null, 2));
  } catch (error) {
    console.error(`Decision candidate assembly rejected: ${error.message}`);
    process.exitCode = 1;
  }
}

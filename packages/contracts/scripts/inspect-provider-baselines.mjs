import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const baselineRoot = fileURLToPath(new URL(
  "../../../services/core-api/src/main/resources/catalog/baselines/", import.meta.url,
));
const schema = JSON.parse(await readFile(new URL(
  "../schemas/provider-catalog-draft.v1.schema.json", import.meta.url,
), "utf8"));
const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);
const validate = ajv.compile(schema);
const providers = Object.freeze([
  { id: "entra-external-id", host: "learn.microsoft.com", deployment: "MANAGED" },
  { id: "auth0", host: "auth0.com", deployment: "MANAGED" },
  { id: "workos", host: "workos.com", deployment: "MANAGED" },
  { id: "zitadel", host: "zitadel.com", deployment: "MANAGED" },
  { id: "keycloak", host: "www.keycloak.org", deployment: "SELF_HOSTED" },
]);
const capabilities = Object.keys(schema.$defs.option.properties.facts.properties).sort();
const windowMs = 90 * 24 * 60 * 60 * 1000;

function requireCondition(condition, message) {
  if (!condition) throw new Error(message);
}

/** Fixed repository inputs only; no URL fetches, environment access or file writes. */
export async function readBaselineDrafts() {
  const files = providers.map(({ id }) => `${id}.v1.json`).sort();
  const actual = (await readdir(baselineRoot)).filter((file) => file.endsWith(".json")).sort();
  requireCondition(JSON.stringify(actual) === JSON.stringify(files), "Unexpected baseline research file inventory");
  return Promise.all(providers.map(async ({ id }) => JSON.parse(
    await readFile(path.join(baselineRoot, `${id}.v1.json`), "utf8"),
  )));
}

/** This checks the deliberately incomplete research pack, not arbitrary catalog approval. */
export function inspectBaselineDrafts(drafts, evaluatedAt = new Date()) {
  requireCondition(evaluatedAt instanceof Date && Number.isFinite(evaluatedAt.getTime()), "Invalid inspection time");
  requireCondition(Array.isArray(drafts) && drafts.length === providers.length, "Expected five provider research drafts");
  const remaining = new Map(providers.map((provider) => [provider.id, provider]));
  const options = [];
  for (const draft of drafts) {
    requireCondition(validate(draft), `Invalid baseline draft: ${ajv.errorsText(validate.errors)}`);
    requireCondition(draft.options.length === 1, "Each research draft must contain one unresolved scope");
    const option = draft.options[0];
    const expected = remaining.get(option.providerId);
    requireCondition(expected, "Unexpected or duplicate baseline provider");
    remaining.delete(option.providerId);
    requireCondition(option.deployment === expected.deployment, "Unexpected research deployment scope");
    requireCondition(option.id === `${option.providerId}-${expected.deployment === "MANAGED" ? "managed" : "self-hosted"}-research`,
      "Unexpected research option identity");
    requireCondition(draft.catalogVersion === `${option.providerId}-research-2026.10.02`, "Unexpected research pack version");
    requireCondition(Object.keys(option.facts).sort().join(",") === "GROUP_SYNC,OIDC,SCIM", "Expected three initial capability research entries");
    requireCondition(Object.values(option.compatibility).every((group) => Object.keys(group).length === 0)
      && Object.keys(option.residency).length === 0 && Object.keys(option.authenticationControls).length === 0,
    "This research pack must not infer context, residency or authentication controls");
    const facts = Object.entries(option.facts).sort(([left], [right]) => left.localeCompare(right)).map(([capability, fact]) => {
      requireCondition(fact.availability === "UNKNOWN", "Unresolved research scope cannot assert capability availability");
      requireCondition(fact.conditions.length > 0, "Research entries must expose unresolved conditions");
      requireCondition(new Set(fact.conditions).size === fact.conditions.length, "Duplicate research conditions");
      const source = new URL(fact.evidence.sourceUrl);
      requireCondition(source.protocol === "https:" && source.hostname === expected.host
        && source.port === "" && source.username === "" && source.password === "",
      "Expected a credential-free official documentation URL; this is not source verification");
      // This version records second-resolution observation instants. Never refresh them on inspection.
      requireCondition(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(fact.evidence.observedAt), "Expected a UTC research observation instant");
      const observedAt = Date.parse(fact.evidence.observedAt);
      requireCondition(new Date(observedAt).toISOString().replace(".000Z", "Z") === fact.evidence.observedAt,
        "Invalid research observation calendar date");
      const age = evaluatedAt.getTime() - observedAt;
      return {
        path: `facts.${capability}`,
        availability: fact.availability,
        evidenceStatus: "UNREVIEWED",
        freshness: age < 0 ? "FUTURE" : age > windowMs ? "STALE" : "CURRENT",
        conditions: [...fact.conditions].sort(),
        evidence: { ...fact.evidence },
      };
    });
    options.push({
      catalogVersion: draft.catalogVersion,
      optionId: option.id,
      providerId: option.providerId,
      product: option.product,
      plan: option.plan,
      deployment: option.deployment,
      region: option.region,
      configuration: option.configuration,
      facts,
      omittedCapabilities: capabilities.filter((capability) => !Object.hasOwn(option.facts, capability)),
      deferredDimensions: ["COMMERCIAL_SCOPE", "COMPATIBILITY", "RESIDENCY", "AUTHENTICATION_CONTROLS", "AUDITABILITY", "COST"],
    });
  }
  return {
    scope: "PROVIDER_BASELINE_RESEARCH_INSPECTION",
    evaluatedAt: evaluatedAt.toISOString(),
    sourceVerificationPerformed: false,
    approvalGranted: false,
    writesPerformed: false,
    fullCoverageEstablished: false,
    evaluationReady: false,
    optionCount: options.length,
    factCount: options.reduce((count, option) => count + option.facts.length, 0),
    options: options.sort((left, right) => left.providerId.localeCompare(right.providerId)),
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    requireCondition(process.argv.length === 2, "This command accepts no file paths, URLs or options");
    console.log(JSON.stringify(inspectBaselineDrafts(await readBaselineDrafts()), null, 2));
  } catch (error) {
    console.error(`Baseline research inspection rejected: ${error.message}`);
    process.exitCode = 1;
  }
}

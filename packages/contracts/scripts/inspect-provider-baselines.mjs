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
const keycloakRelease = Object.freeze({
  version: "26.8.0",
  commit: "4246609cf2024c85016d3fb1254c3d2533367c31",
  file: "keycloak-26.8.0.v1.json",
  sourcePaths: Object.freeze({
    OIDC: "clients/oidc/con-basic-settings.adoc",
    SAML: "clients/saml/proc-creating-saml-client.adoc",
    SCIM: "assembly-managing-scim.adoc",
    GROUP_SYNC: "scim/managing-groups.adoc",
  }),
});

function requireCondition(condition, message) {
  if (!condition) throw new Error(message);
}

function requireInspectionTime(evaluatedAt) {
  requireCondition(evaluatedAt instanceof Date && Number.isFinite(evaluatedAt.getTime()), "Invalid inspection time");
}

function requireDeferredDimensions(option) {
  requireCondition(Object.values(option.compatibility).every((group) => Object.keys(group).length === 0)
    && Object.keys(option.residency).length === 0 && Object.keys(option.authenticationControls).length === 0,
  "This baseline pack must not infer context, residency or authentication controls");
}

function inspectOption(draft, option, evaluatedAt, basis) {
  const facts = Object.entries(option.facts).sort(([left], [right]) => left.localeCompare(right)).map(([capability, fact]) => {
    requireCondition(fact.conditions.length > 0, "Baseline entries must expose unresolved conditions");
    requireCondition(new Set(fact.conditions).size === fact.conditions.length, "Duplicate baseline conditions");
    // Observation instants are inputs, not refreshed by this inspection command.
    requireCondition(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(fact.evidence.observedAt), "Expected a UTC baseline observation instant");
    const observedAt = Date.parse(fact.evidence.observedAt);
    requireCondition(new Date(observedAt).toISOString().replace(".000Z", "Z") === fact.evidence.observedAt,
      "Invalid baseline observation calendar date");
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
  return {
    catalogVersion: draft.catalogVersion,
    optionId: option.id,
    providerId: option.providerId,
    product: option.product,
    plan: option.plan,
    deployment: option.deployment,
    region: option.region,
    configuration: option.configuration,
    basis,
    facts,
    omittedCapabilities: capabilities.filter((capability) => !Object.hasOwn(option.facts, capability)),
    deferredDimensions: ["COMMERCIAL_SCOPE", "COMPATIBILITY", "RESIDENCY", "AUTHENTICATION_CONTROLS", "AUDITABILITY", "COST"],
  };
}

function report(scope, options, evaluatedAt) {
  return {
    scope,
    evaluatedAt: evaluatedAt.toISOString(),
    sourceVerificationPerformed: false,
    approvalGranted: false,
    writesPerformed: false,
    fullCoverageEstablished: false,
    evaluationReady: false,
    optionCount: options.length,
    factCount: options.reduce((count, option) => count + option.facts.length, 0),
    options: options.sort((left, right) => left.optionId.localeCompare(right.optionId)),
  };
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
  requireInspectionTime(evaluatedAt);
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
    requireDeferredDimensions(option);
    for (const fact of Object.values(option.facts)) {
      requireCondition(fact.availability === "UNKNOWN", "Unresolved research scope cannot assert capability availability");
      const source = new URL(fact.evidence.sourceUrl);
      requireCondition(source.protocol === "https:" && source.hostname === expected.host
        && source.port === "" && source.username === "" && source.password === "",
      "Expected a credential-free official documentation URL; this is not source verification");
    }
    options.push(inspectOption(draft, option, evaluatedAt, "UNRESOLVED_RESEARCH_SCOPE"));
  }
  return report("PROVIDER_BASELINE_RESEARCH_INSPECTION", options, evaluatedAt);
}

export async function readScopedBaselineDraft() {
  const directory = path.join(baselineRoot, "scoped");
  const files = (await readdir(directory)).filter((file) => file.endsWith(".json")).sort();
  requireCondition(JSON.stringify(files) === JSON.stringify([keycloakRelease.file]), "Unexpected scoped baseline file inventory");
  return JSON.parse(await readFile(path.join(directory, keycloakRelease.file), "utf8"));
}

/** Release-scoped documentation assertions are still proposals, never reviewed evidence. */
export function inspectScopedBaselineDraft(draft, evaluatedAt = new Date()) {
  requireInspectionTime(evaluatedAt);
  requireCondition(validate(draft), `Invalid scoped draft: ${ajv.errorsText(validate.errors)}`);
  requireCondition(draft.catalogVersion === "keycloak-26.8.0-native-draft-2026.10.02" && draft.options.length === 1,
    "Unexpected scoped draft version or option count");
  const option = draft.options[0];
  requireCondition(option.id === "keycloak-26.8.0-native-self-hosted" && option.providerId === "keycloak"
    && option.product === `Keycloak upstream ${keycloakRelease.version}` && option.deployment === "SELF_HOSTED"
    && option.plan === "Upstream release 26.8.0; commercial support not assessed"
    && option.region === "Operator-selected hosting; storage destinations not verified"
    && option.configuration === "Native OIDC/SAML clients and configurable inbound realm SCIM; no third-party extensions or outbound bridge",
  "Unexpected release, distribution, deployment or native integration scope");
  requireCondition(Object.keys(option.facts).sort().join(",") === "GROUP_SYNC,OIDC,SAML,SCIM", "Expected four scoped documentation assertions");
  requireDeferredDimensions(option);
  for (const [capability, fact] of Object.entries(option.facts)) {
    requireCondition(fact.availability === "OPTIONAL", "Expected proposed OPTIONAL availability for the pinned documentation candidate");
    const expected = `https://github.com/keycloak/keycloak/blob/${keycloakRelease.commit}/docs/documentation/server_admin/topics/${keycloakRelease.sourcePaths[capability]}`;
    requireCondition(fact.evidence.sourceUrl === expected, "Expected the exact release-resolved official source commit and documentation path");
  }
  const inspected = inspectOption(draft, option, evaluatedAt, "RELEASE_SCOPED_DOCUMENTATION_DRAFT");
  inspected.sourceRelease = keycloakRelease.version;
  inspected.sourceCommit = keycloakRelease.commit;
  return report("PROVIDER_SCOPED_BASELINE_INSPECTION", [inspected], evaluatedAt);
}

export async function inspectBaselinePack(evaluatedAt = new Date()) {
  const research = inspectBaselineDrafts(await readBaselineDrafts(), evaluatedAt);
  const scoped = inspectScopedBaselineDraft(await readScopedBaselineDraft(), evaluatedAt);
  return {
    ...report("PROVIDER_BASELINE_PACK_INSPECTION", [...research.options, ...scoped.options], evaluatedAt),
    researchOptionCount: research.optionCount,
    scopedDraftOptionCount: scoped.optionCount,
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    requireCondition(process.argv.length === 2, "This command accepts no file paths, URLs or options");
    console.log(JSON.stringify(await inspectBaselinePack(), null, 2));
  } catch (error) {
    console.error(`Baseline research inspection rejected: ${error.message}`);
    process.exitCode = 1;
  }
}

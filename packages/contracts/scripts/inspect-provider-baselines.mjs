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
const scopedBaselines = Object.freeze([
  {
    file: keycloakRelease.file,
    catalogVersion: "keycloak-26.8.0-native-draft-2026.10.02",
    scope: {
      id: "keycloak-26.8.0-native-self-hosted", providerId: "keycloak",
      product: `Keycloak upstream ${keycloakRelease.version}`, deployment: "SELF_HOSTED",
      plan: "Upstream release 26.8.0; commercial support not assessed",
      region: "Operator-selected hosting; storage destinations not verified",
      configuration: "Native OIDC/SAML clients and configurable inbound realm SCIM; no third-party extensions or outbound bridge",
    },
    facts: Object.fromEntries(Object.entries(keycloakRelease.sourcePaths).map(([capability, sourcePath]) => [
      capability, { availability: "OPTIONAL", sourceUrl: `https://github.com/keycloak/keycloak/blob/${keycloakRelease.commit}/docs/documentation/server_admin/topics/${sourcePath}` },
    ])),
    metadata: { basis: "RELEASE_SCOPED_DOCUMENTATION_DRAFT", sourceRelease: keycloakRelease.version, sourceCommit: keycloakRelease.commit },
  },
  {
    file: "zitadel-cloud-free.v1.json",
    catalogVersion: "zitadel-cloud-free-native-draft-2026.10.02",
    scope: {
      id: "zitadel-cloud-free-native", providerId: "zitadel", product: "ZITADEL Cloud", deployment: "MANAGED",
      plan: "Free; documented offer, no account entitlement verified",
      region: "No Cloud region selected; storage destinations not verified",
      configuration: "Native OIDC/SAML applications and inbound SCIM User interface only; no group bridge or third-party extensions",
    },
    facts: {
      OIDC: { availability: "OPTIONAL", sourceUrl: "https://zitadel.com/docs/guides/manage/console/applications-overview" },
      SAML: { availability: "OPTIONAL", sourceUrl: "https://zitadel.com/docs/guides/manage/console/applications-overview" },
      SCIM: { availability: "UNKNOWN", sourceUrl: "https://zitadel.com/docs/apis/scim2" },
      GROUP_SYNC: { availability: "UNAVAILABLE", sourceUrl: "https://zitadel.com/docs/guides/manage/user/scim2" },
    },
    metadata: { basis: "PLAN_SCOPED_DOCUMENTATION_DRAFT", sourcePlan: "Free" },
  },
  {
    file: "auth0-b2b-free.v1.json",
    catalogVersion: "auth0-b2b-free-oidc-scim-draft-2026.10.02",
    scope: {
      id: "auth0-b2b-free-oidc-scim", providerId: "auth0", product: "Auth0 Public Cloud", deployment: "MANAGED",
      plan: "B2B Free; one Enterprise Connection, no account entitlement verified",
      region: "No tenant region selected; storage destinations not verified",
      configuration: "Downstream OIDC login; one generic OIDC Enterprise Connection with inbound SCIM; no outbound bridge or paid add-ons",
    },
    facts: {
      OIDC: { availability: "OPTIONAL", sourceUrl: "https://auth0.com/docs/get-started/applications/configure-applications-with-oidc-discovery" },
      ENTERPRISE_SSO: { availability: "OPTIONAL", sourceUrl: "https://auth0.com/docs/authenticate/identity-providers/enterprise-identity-providers/oidc" },
      SCIM: { availability: "OPTIONAL", sourceUrl: "https://auth0.com/docs/authenticate/protocols/scim/configure-inbound-scim" },
      GROUP_SYNC: { availability: "UNKNOWN", sourceUrl: "https://auth0.com/docs/authenticate/protocols/scim/configure-inbound-scim" },
    },
    metadata: { basis: "PLAN_SCOPED_DOCUMENTATION_DRAFT", sourcePlan: "B2B Free" },
  },
]);

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

export async function readScopedBaselineDrafts() {
  const directory = path.join(baselineRoot, "scoped");
  const files = (await readdir(directory)).filter((file) => file.endsWith(".json")).sort();
  requireCondition(JSON.stringify(files) === JSON.stringify(scopedBaselines.map(({ file }) => file).sort()),
    "Unexpected scoped baseline file inventory");
  return Promise.all(scopedBaselines.map(async ({ file }) => JSON.parse(await readFile(path.join(directory, file), "utf8"))));
}

/** Retained for consumers of the original single Keycloak candidate. */
export async function readScopedBaselineDraft() {
  return (await readScopedBaselineDrafts())[0];
}

/** Release/plan-scoped documentation assertions are proposals, never reviewed evidence. */
export function inspectScopedBaselineDraft(draft, evaluatedAt = new Date()) {
  requireInspectionTime(evaluatedAt);
  requireCondition(validate(draft), `Invalid scoped draft: ${ajv.errorsText(validate.errors)}`);
  const expected = scopedBaselines.find((candidate) => candidate.catalogVersion === draft.catalogVersion);
  requireCondition(expected && draft.options.length === 1,
    "Unexpected scoped draft version or option count");
  const option = draft.options[0];
  requireCondition(Object.entries(expected.scope).every(([field, value]) => option[field] === value),
    "Unexpected release, plan, distribution, deployment or native integration scope");
  requireCondition(Object.keys(option.facts).sort().join(",") === Object.keys(expected.facts).sort().join(","),
    "Unexpected scoped capability inventory");
  requireDeferredDimensions(option);
  for (const [capability, fact] of Object.entries(option.facts)) {
    requireCondition(fact.availability === expected.facts[capability].availability,
      "Unexpected proposed availability for the scoped documentation candidate");
    requireCondition(fact.evidence.sourceUrl === expected.facts[capability].sourceUrl,
      "Expected the exact scoped official documentation URL");
  }
  const inspected = { ...inspectOption(draft, option, evaluatedAt, expected.metadata.basis), ...expected.metadata };
  return report("PROVIDER_SCOPED_BASELINE_INSPECTION", [inspected], evaluatedAt);
}

export async function inspectBaselinePack(evaluatedAt = new Date()) {
  const research = inspectBaselineDrafts(await readBaselineDrafts(), evaluatedAt);
  const scoped = (await readScopedBaselineDrafts()).map((draft) => inspectScopedBaselineDraft(draft, evaluatedAt));
  return {
    ...report("PROVIDER_BASELINE_PACK_INSPECTION", [...research.options, ...scoped.flatMap((entry) => entry.options)], evaluatedAt),
    researchOptionCount: research.optionCount,
    scopedDraftOptionCount: scoped.reduce((count, entry) => count + entry.optionCount, 0),
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

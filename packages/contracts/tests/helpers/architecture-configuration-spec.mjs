import assert from "node:assert/strict";

// Independent contract inventory: deliberately not imported from the Java evaluator.
export const values = {
  "OAUTH_FLOW": [
    "AUTHORIZATION_CODE",
    "CLIENT_CREDENTIALS",
    "IMPLICIT",
    "UNKNOWN"
  ],
  "OAUTH_CLIENT_TYPE": [
    "CONFIDENTIAL",
    "PUBLIC",
    "UNKNOWN"
  ],
  "CLIENT_AUTHENTICATION": [
    "SERVER_HELD_CREDENTIAL",
    "WORKLOAD_HELD_CREDENTIAL",
    "NONE",
    "DISTRIBUTED_SHARED_SECRET",
    "UNKNOWN"
  ],
  "TOKEN_LOCATION": [
    "APPLICATION_SERVER",
    "BROWSER",
    "NATIVE_APP",
    "WORKLOAD",
    "UNKNOWN"
  ],
  "PKCE_METHOD": [
    "S256",
    "PLAIN",
    "NONE",
    "UNKNOWN"
  ],
  "REDIRECT_MATCHING": [
    "EXACT_REGISTERED",
    "NATIVE_LOOPBACK_IP_LITERAL_PORT_EXCEPTION",
    "WILDCARD",
    "UNKNOWN"
  ],
  "SESSION_COOKIE_SECURE": [
    "ENABLED",
    "DISABLED",
    "UNKNOWN"
  ],
  "SESSION_COOKIE_HTTP_ONLY": [
    "ENABLED",
    "DISABLED",
    "UNKNOWN"
  ],
  "SESSION_CSRF_DEFENSE": [
    "DEFENSE_PLANNED",
    "ABSENT",
    "UNKNOWN"
  ],
  "RESOURCE_ACCESS": [
    "BFF_PROXY",
    "SESSION_BACKEND",
    "DIRECT_BROWSER",
    "UNKNOWN"
  ],
  "BROWSER_TOKEN_ENDPOINT_ACCESS": [
    "REQUIRED_ORIGINS_PLANNED",
    "BLOCKED",
    "UNKNOWN"
  ],
  "NATIVE_USER_AGENT": [
    "EXTERNAL_BROWSER",
    "EMBEDDED_WEBVIEW",
    "UNKNOWN"
  ],
  "WORKLOAD_AUTHORIZATION": [
    "WORKLOAD_OWN_OR_PREARRANGED",
    "USER_DELEGATION",
    "UNKNOWN"
  ]
};
export const patterns = {
  "BFF_SESSION": {
    "client": "BROWSER",
    "ids": [
      "OAUTH_FLOW",
      "OAUTH_CLIENT_TYPE",
      "CLIENT_AUTHENTICATION",
      "TOKEN_LOCATION",
      "PKCE_METHOD",
      "REDIRECT_MATCHING",
      "SESSION_COOKIE_SECURE",
      "SESSION_COOKIE_HTTP_ONLY",
      "SESSION_CSRF_DEFENSE",
      "RESOURCE_ACCESS"
    ],
    "values": [
      "AUTHORIZATION_CODE",
      "CONFIDENTIAL",
      "SERVER_HELD_CREDENTIAL",
      "APPLICATION_SERVER",
      "S256",
      "EXACT_REGISTERED",
      "ENABLED",
      "ENABLED",
      "DEFENSE_PLANNED",
      "BFF_PROXY"
    ]
  },
  "SERVER_SIDE_SESSION": {
    "client": "BROWSER",
    "ids": [
      "OAUTH_FLOW",
      "OAUTH_CLIENT_TYPE",
      "CLIENT_AUTHENTICATION",
      "TOKEN_LOCATION",
      "PKCE_METHOD",
      "REDIRECT_MATCHING",
      "SESSION_COOKIE_SECURE",
      "SESSION_COOKIE_HTTP_ONLY",
      "SESSION_CSRF_DEFENSE",
      "RESOURCE_ACCESS"
    ],
    "values": [
      "AUTHORIZATION_CODE",
      "CONFIDENTIAL",
      "SERVER_HELD_CREDENTIAL",
      "APPLICATION_SERVER",
      "S256",
      "EXACT_REGISTERED",
      "ENABLED",
      "ENABLED",
      "DEFENSE_PLANNED",
      "SESSION_BACKEND"
    ]
  },
  "SPA_CODE_PKCE": {
    "client": "BROWSER",
    "ids": [
      "OAUTH_FLOW",
      "OAUTH_CLIENT_TYPE",
      "CLIENT_AUTHENTICATION",
      "TOKEN_LOCATION",
      "PKCE_METHOD",
      "REDIRECT_MATCHING",
      "RESOURCE_ACCESS",
      "BROWSER_TOKEN_ENDPOINT_ACCESS"
    ],
    "values": [
      "AUTHORIZATION_CODE",
      "PUBLIC",
      "NONE",
      "BROWSER",
      "S256",
      "EXACT_REGISTERED",
      "DIRECT_BROWSER",
      "REQUIRED_ORIGINS_PLANNED"
    ]
  },
  "NATIVE_CODE_PKCE": {
    "client": "NATIVE_MOBILE",
    "ids": [
      "OAUTH_FLOW",
      "OAUTH_CLIENT_TYPE",
      "CLIENT_AUTHENTICATION",
      "TOKEN_LOCATION",
      "PKCE_METHOD",
      "REDIRECT_MATCHING",
      "NATIVE_USER_AGENT"
    ],
    "values": [
      "AUTHORIZATION_CODE",
      "PUBLIC",
      "NONE",
      "NATIVE_APP",
      "S256",
      [
        "EXACT_REGISTERED",
        "NATIVE_LOOPBACK_IP_LITERAL_PORT_EXCEPTION"
      ],
      "EXTERNAL_BROWSER"
    ]
  },
  "M2M_CLIENT_CREDENTIALS": {
    "client": "MACHINE_TO_MACHINE",
    "ids": [
      "OAUTH_FLOW",
      "OAUTH_CLIENT_TYPE",
      "CLIENT_AUTHENTICATION",
      "TOKEN_LOCATION",
      "WORKLOAD_AUTHORIZATION"
    ],
    "values": [
      "CLIENT_CREDENTIALS",
      "CONFIDENTIAL",
      "WORKLOAD_HELD_CREDENTIAL",
      "WORKLOAD",
      "WORKLOAD_OWN_OR_PREARRANGED"
    ]
  }
};
export const deferredBoundaries = [
  "Observed client registration and actual issuer/redirect values",
  "Runtime protocol validation, token storage and session defenses",
  "API authorization, scopes, audience and grant permissions",
  "Provider interoperability, browser CORS and native redirect ownership",
  "Provisioning, offboarding, assurance, compliance and operations",
  "Independent assessment of additional resource-access paths"
];
export const flags = ["configurationObserved", "configurationVerified", "providerCompatibilityVerified", "runtimeFlowVerified", "recommendationReady", "publicationReady", "writesPerformed"];
export const descriptions = {
  "OAUTH_FLOW": "Grant of the primary reference flow; not proof of runtime protocol validation.",
  "OAUTH_CLIENT_TYPE": "Whether this client can protect its authentication credentials.",
  "CLIENT_AUTHENTICATION": "Credential custody category only; never submit a secret. A distributed shared secret does not make a public client confidential.",
  "TOKEN_LOCATION": "Custody of OAuth tokens in the primary reference design; storage security remains unverified.",
  "PKCE_METHOD": "AuthWeave conservatively requires S256 in all human reference designs. This project policy is not a universal normative MUST for every confidential OIDC client.",
  "REDIRECT_MATCHING": "Exact registered redirect matching; the port exception is limited to native loopback IP-literal redirects, not wildcard hosts or paths. Actual URI registration is deferred.",
  "SESSION_COOKIE_SECURE": "Secure cookie planned for the application session; no cookie was inspected.",
  "SESSION_COOKIE_HTTP_ONLY": "HttpOnly cookie planned for the application session; no cookie was inspected.",
  "SESSION_CSRF_DEFENSE": "A session CSRF defense is planned; effectiveness, SameSite policy and other defenses remain deferred.",
  "RESOURCE_ACCESS": "Primary resource path of this reference pattern only; additional direct browser APIs require independent assessment.",
  "BROWSER_TOKEN_ENDPOINT_ACCESS": "Required browser origins are planned at the token endpoint; actual CORS and interoperability are unverified.",
  "NATIVE_USER_AGENT": "Authorization uses an external browser rather than an embedded webview.",
  "WORKLOAD_AUTHORIZATION": "Client credentials cover the workload's own or prearranged resources, not user-delegated authorization."
};
const security = "https://www.rfc-editor.org/rfc/rfc9700.html#section-2.1";
const native = "https://www.rfc-editor.org/rfc/rfc8252.html#section-8";
const browser = "https://www.rfc-editor.org/rfc/rfc10017.html#section-6.1";
const workload = "https://www.rfc-editor.org/rfc/rfc6749.html#section-4.4";
export function expectedDefinitions(patternId) {
  const pattern = patterns[patternId];
  return pattern.ids.map((settingId, index) => {
    let references;
    if (["OAUTH_FLOW", "OAUTH_CLIENT_TYPE", "CLIENT_AUTHENTICATION", "TOKEN_LOCATION"].includes(settingId))
      references = [patternId === "M2M_CLIENT_CREDENTIALS" ? workload : patternId === "NATIVE_CODE_PKCE" ? native : security];
    else if (settingId === "REDIRECT_MATCHING") references = patternId === "NATIVE_CODE_PKCE" ? [security, native] : [security];
    else if (settingId === "PKCE_METHOD") references = [security];
    else if (settingId === "NATIVE_USER_AGENT") references = [native];
    else if (settingId === "WORKLOAD_AUTHORIZATION") references = [workload];
    else if (settingId === "BROWSER_TOKEN_ENDPOINT_ACCESS") references = ["https://www.rfc-editor.org/rfc/rfc10017.html#section-6.3"];
    else references = [browser];
    return { settingId, description: descriptions[settingId], allowedValues: values[settingId], compatibleValues: [pattern.values[index]].flat(), references };
  });
}
export function expectedAnalysis(patternId, clientScope, settings) {
  const pattern = patterns[patternId];
  const checks = pattern.ids.map((settingId, index) => {
    const value = settings[settingId] ?? "UNKNOWN", compatible = [pattern.values[index]].flat();
    const reasonCode = clientScope === "NOT_SELECTED" ? "PATTERN_NOT_APPLICABLE" : clientScope === "UNKNOWN" ? "CLIENT_SCOPE_UNKNOWN"
      : value === "UNKNOWN" ? "SETTING_UNKNOWN" : compatible.includes(value) ? "EXPECTED_SETTING_DECLARED" : "INCOMPATIBLE_SETTING_DECLARED";
    const outcome = reasonCode === "PATTERN_NOT_APPLICABLE" ? "NOT_APPLICABLE" : ["CLIENT_SCOPE_UNKNOWN", "SETTING_UNKNOWN"].includes(reasonCode) ? "UNKNOWN"
      : reasonCode === "EXPECTED_SETTING_DECLARED" ? "CONDITIONALLY_SATISFIED" : "CONDITIONALLY_NOT_SATISFIED";
    return { settingId, outcome, reasonCode };
  });
  return { patternId, clientScope, settings, checks,
    status: clientScope === "NOT_SELECTED" ? "NOT_APPLICABLE" : checks.some(c => c.outcome === "CONDITIONALLY_NOT_SATISFIED") ? "CONDITIONALLY_DOES_NOT_MATCH"
      : checks.some(c => c.outcome === "UNKNOWN") ? "NEEDS_INFORMATION" : "CONDITIONALLY_MATCHES",
    policyVersion: "architecture-configuration-design-1", analysisBasis: "UNVERIFIED_PROPOSED_CONFIGURATION", ...Object.fromEntries(flags.map(key => [key, false])) };
}

export function validateArchitectureConfiguration(payload, input, saved, originalPreflight) {
  assert.ok(input && saved && originalPreflight, "Exact original request, saved assessment and independent preflight are required");
  assert.equal(payload.preflight.workspaceId, saved.workspaceId);
  assert.equal(payload.preflight.assessmentId, saved.id);
  assert.equal(payload.preflight.assessmentVersion, saved.version);
  assert.equal(input.expectedVersion, saved.version);
  assert.deepEqual(payload.preflight, originalPreflight, "Settings cannot override the independent saved-input preflight");
  assert.deepEqual(payload.preflight.selectedClients, [...saved.profile.application.clients].sort());
  assert.equal(payload.preflight.browserTokenExposureRequirement, saved.profile.security.browserTokenExposureMinimization);
  const clients = saved.profile.application.clients;
  const scope = clients.length === 0 ? "UNKNOWN" : clients.includes(patterns[input.patternId].client) ? "SELECTED" : "NOT_SELECTED";
  assert.deepEqual(payload.analysis, expectedAnalysis(input.patternId, scope, input.settings));
  assert.deepEqual(payload.settingDefinitions, expectedDefinitions(input.patternId));
  assert.deepEqual(payload.deferredBoundaries, deferredBoundaries);
}

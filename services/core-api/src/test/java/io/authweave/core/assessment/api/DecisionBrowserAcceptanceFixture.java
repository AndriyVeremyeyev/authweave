package io.authweave.core.assessment.api;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.catalog.impact.DecisionPublicationCoveragePolicy;

/** Explicit fictional hypotheses for three end-to-end result flows, not vendor facts or owner defaults. */
final class DecisionBrowserAcceptanceFixture {
    static final List<String> OPTIONS = List.of("fictional-matrix-alpha", "fictional-matrix-beta");
    record Scenario(String key, JsonNode profile, JsonNode weights, String failurePath) { }

    static List<Scenario> scenarios(ObjectMapper mapper, DecisionPublicationCoveragePolicy policy) {
        return List.of(scenario(mapper, policy, "b2b", "b2b-saas-scoped", "provisioning.scim"),
                scenario(mapper, policy, "citizen", "public-sector-scoped", "security.authenticationControls.phishingResistance"),
                scenario(mapper, policy, "workforce", "internal-workforce-scoped", "provisioning.groupSynchronization"));
    }

    private static Scenario scenario(ObjectMapper mapper, DecisionPublicationCoveragePolicy policy, String key, String id, String path) {
        var profile = (ObjectNode) policy.scenarios().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow().profile();
        // These explicit test choices remove only the documented assurance/compliance/unknown-input blockers.
        // Residency, scoped strong controls, API protection, audit criteria and lifecycle requirements stay required.
        var security = (ObjectNode) profile.get("security");
        security.put("assurance", "BASELINE"); security.put("complianceScopeStatus", "NONE_IDENTIFIED");
        security.set("complianceTargets", mapper.createArrayNode());
        ((ObjectNode) profile.get("provisioning")).put("justInTimeProvisioning", "NOT_REQUIRED");
        boolean preferences = !key.equals("citizen");
        ((ObjectNode) profile.at("/protocols/federation")).put("SAML", preferences ? "PREFERRED" : "NOT_REQUIRED");
        if (preferences) security.put("multiFactorAuthentication", "PREFERRED");
        if (key.equals("b2b")) ((ObjectNode) profile.get("protocols")).put("socialLogin", "NOT_REQUIRED");
        var weights = mapper.createObjectNode().put("mode", preferences ? "EXPLICIT" : "NONE");
        var values = weights.putArray("values");
        if (preferences) {
            values.addObject().put("capability", "SAML").put("weight", 70);
            values.addObject().put("capability", "MFA").put("weight", 30);
        }
        return new Scenario(key, profile, weights, path);
    }

    static void base(ObjectMapper mapper, ObjectNode base) {
        base.put("catalogVersion", "fictional-browser-matrix-root");
        var template = (ObjectNode) base.at("/options/0").deepCopy();
        var options = mapper.createArrayNode(); base.set("options", options);
        for (int index = 0; index < OPTIONS.size(); index++) {
            var option = template.deepCopy(); options.add(option); option.put("id", OPTIONS.get(index));
            option.put("product", "Fictional Matrix " + (index == 0 ? "Alpha" : "Beta"));
            var facts = (ObjectNode) option.get("facts"); var fact = (ObjectNode) facts.get("OIDC").deepCopy();
            for (String capability : List.of("OIDC", "OAUTH2_APIS", "ENTERPRISE_SSO", "SCIM", "GROUP_SYNC", "JIT", "SAML", "MFA", "SOCIAL_LOGIN")) {
                var value = fact.deepCopy();
                value.put("availability", capability.equals("SOCIAL_LOGIN") || capability.equals(index == 0 ? "MFA" : "SAML") ? "UNAVAILABLE" : "MANDATORY");
                facts.set(capability, value);
            }
            var compatibility = mapper.createObjectNode(); option.set("compatibility", compatibility);
            var supported = template.at("/compatibility/applications/B2B_SAAS");
            var groups = List.of(List.of("applications", "B2B_SAAS", "PUBLIC_SECTOR_PORTAL", "INTERNAL_WORKFORCE"),
                    List.of("clients", "BROWSER", "NATIVE_MOBILE", "MACHINE_TO_MACHINE"),
                    List.of("populations", "EXTERNAL_CUSTOMERS", "PARTNERS", "CITIZENS", "INTERNAL_OPERATORS", "EMPLOYEES", "CONTRACTORS"),
                    List.of("tenancy", "MULTI_TENANT_ORGANIZATIONS", "NO_ORGANIZATION_BOUNDARY", "SINGLE_ORGANIZATION"),
                    List.of("membership", "MULTIPLE_ORGANIZATIONS_PER_USER", "NOT_APPLICABLE", "SINGLE_ORGANIZATION_PER_USER"));
            for (var group : groups) {
                var target = compatibility.putObject(group.getFirst());
                group.stream().skip(1).forEach(key -> target.set(key, supported.deepCopy()));
            }
            var residency = mapper.createObjectNode(); option.set("residency", residency);
            var residencyFact = (ObjectNode) template.at("/residency/USER_PROFILES").deepCopy();
            residencyFact.set("storageCountries", mapper.createArrayNode().add("DE"));
            for (String category : List.of("USER_PROFILES", "CREDENTIALS", "AUDIT_LOGS", "BACKUPS")) residency.set(category, residencyFact.deepCopy());
            var controls = mapper.createObjectNode(); option.set("authenticationControls", controls);
            var control = template.at("/authenticationControls/BROWSER/PARTNERS/PHISHING_RESISTANCE");
            for (String client : List.of("BROWSER", "NATIVE_MOBILE")) {
                var populations = controls.putObject(client);
                for (String population : groups.get(2).subList(1, groups.get(2).size())) {
                    var scoped = populations.putObject(population);
                    for (String name : List.of("PHISHING_RESISTANCE", "NON_EXPORTABLE_KEYS", "STEP_UP_AUTHENTICATION")) scoped.set(name, control.deepCopy());
                }
            }
        }
        // Dates are set before the fictional source review, never renewed when a result is opened.
        freshEvidence(base, Instant.now().minusSeconds(86400).toString());
    }

    static void audit(ObjectMapper mapper, ObjectNode supplement) {
        var template = supplement.at("/options/0").deepCopy();
        var options = mapper.createArrayNode(); supplement.set("options", options);
        for (String id : OPTIONS) {
            var option = (ObjectNode) template.deepCopy(); options.add(option);
            ((ObjectNode) option.get("scope")).put("optionId", id);
            var facts = option.putArray("facts");
            for (String criterion : List.of("AUTHENTICATION_SUCCESS_EVENTS", "AUTHENTICATION_FAILURE_EVENTS", "ADMINISTRATIVE_CHANGE_EVENTS",
                    "PROVISIONING_CHANGE_EVENTS", "AUDIT_LOG_EXPORT", "AUDIT_LOG_RETENTION")) {
                var fact = (ObjectNode) template.at("/facts/0").deepCopy(); facts.add(fact); fact.put("criterion", criterion);
                if (criterion.equals("AUDIT_LOG_RETENTION")) fact.put("documentedMinimumRetentionDays", 365);
            }
        }
    }

    static void successor(ObjectNode candidate) {
        candidate.put("catalogVersion", "fictional-browser-matrix-successor");
        for (var option : candidate.get("options")) {
            for (String capability : List.of("SCIM", "GROUP_SYNC")) ((ObjectNode) option.path("facts").get(capability)).put("availability", "UNAVAILABLE");
            for (var client : option.get("authenticationControls")) for (var population : client) {
                var fact = (ObjectNode) population.get("PHISHING_RESISTANCE");
                fact.put("availability", "UNSUPPORTED"); fact.put("enforcement", "UNSUPPORTED");
            }
        }
    }

    private static void freshEvidence(JsonNode node, String at) {
        if (node.isObject() && node.has("observedAt") && node.has("sourceUrl")) ((ObjectNode) node).put("observedAt", at);
        if (node.isObject() || node.isArray()) node.forEach(child -> freshEvidence(child, at));
    }
}

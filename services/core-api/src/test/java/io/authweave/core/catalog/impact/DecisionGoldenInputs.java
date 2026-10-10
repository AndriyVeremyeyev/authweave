package io.authweave.core.catalog.impact;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.draft.CatalogDraftFacts;
import io.authweave.core.catalog.draft.ProviderCatalogDraft;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static io.authweave.core.catalog.impact.CandidateHardConstraintEvaluator.*;

/** Inputs are assembled independently of expected verdicts/checks/scores. All sources are fictional. */
final class DecisionGoldenInputs {
    static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final Path ROOT = Path.of(System.getProperty("basedir", "."), "../..").toAbsolutePath().normalize();
    static final JsonNode SUITE = read("packages/contracts/decision-core/cases.v1.json");
    static final JsonNode INPUTS = read(SUITE.path("runtimeInputSource").asText());
    static final JsonNode POLICY = read("packages/contracts/decision-core/policy.v1.json");
    static final Instant AT = Instant.parse(SUITE.path("evaluatedAt").asText());

    record Input(JsonNode profile, JsonNode catalog, SourceAssertions assertions,
            CandidateAuditabilityInput auditability, JsonNode weights) { }

    static Input assemble(JsonNode definition) {
        var input = find(INPUTS.get("cases"), definition.path("id").asText());
        var profile = (ObjectNode) find(read(SUITE.path("profileSource").asText()), definition.path("profileId").asText()).get("profile").deepCopy();
        for (var overrides : List.of(SUITE.get("baselineProfileOverrides"), SUITE.path("profileSpecificOverrides").path(definition.path("profileId").asText()), definition.get("profileOverrides")))
            overrides.properties().forEach(e -> set(profile, e.getKey(), e.getValue()));
        var catalog = catalog(profile);
        for (var change : input.path("changes")) set(option(catalog, change.get(0).asText()), change.get(1).asText(), change.get(2));
        for (var removal : input.path("removeFacts")) remove(option(catalog, removal.get(0).asText()), removal.get(1).asText());
        var supplement = audit(catalog);
        for (var change : input.path("auditChanges")) {
            var scoped = java.util.stream.StreamSupport.stream(supplement.get("options").spliterator(), false)
                    .filter(o -> o.at("/scope/optionId").asText().equals(change.get(0).asText())).findFirst().orElseThrow();
            set((ObjectNode) java.util.stream.StreamSupport.stream(scoped.get("facts").spliterator(), false)
                    .filter(f -> f.path("criterion").asText().equals(change.get(1).asText())).findFirst().orElseThrow(), change.get(2).asText(), change.get(3));
        }
        var assertions = new ArrayList<FactAssertion>();
        for (var raw : catalog.get("options")) {
            var typed = MAPPER.treeToValue(raw, ProviderCatalogDraft.Option.class);
            for (String path : CatalogDraftFacts.entries(typed).keySet().stream().sorted().toList()) {
                boolean omitted = java.util.stream.StreamSupport.stream(input.path("omitAssertions").spliterator(), false)
                        .anyMatch(a -> a.get(0).asText().equals(typed.id()) && a.get(1).asText().equals(path));
                if (!omitted) assertions.add(new FactAssertion(typed.id(), path, claimSha256(raw, path), Assertion.SOURCE_SUPPORTS_CLAIM));
            }
        }
        var digest = DecisionCanonicalizer.sha256(catalog);
        var audit = new CandidateAuditabilityInput(digest, supplement, CandidateAuditabilityInput.claimDigests(supplement).entrySet().stream()
                .sorted(java.util.Comparator.comparing(e -> e.getKey().optionId() + ":" + e.getKey().criterion()))
                .map(e -> new CandidateAuditabilityInput.FactAssertion(e.getKey().optionId(), e.getKey().criterion(), e.getValue(), Assertion.SOURCE_SUPPORTS_CLAIM)).toList());
        return new Input(profile, catalog, new SourceAssertions(digest, assertions), audit, weights(definition.get("weights")));
    }

    static JsonNode weights(JsonNode values) {
        var result = MAPPER.createObjectNode().put("mode", values.isEmpty() ? "NONE" : "EXPLICIT");
        var weights = result.putArray("values");
        for (var capability : POLICY.at("/scoring/dimensions")) if (values.has(capability.asText()))
            weights.addObject().put("capability", capability.asText()).set("weight", values.get(capability.asText()));
        return result;
    }

    static CandidateDecisionEvaluator.Result evaluate(Input input) {
        return CandidateDecisionEvaluator.evaluate(input.profile(), 6, input.catalog(), input.assertions(), input.auditability(), input.weights(), AT);
    }

    private static ObjectNode catalog(JsonNode profile) {
        var catalog = (ObjectNode) read("packages/contracts/tests/fixtures/provider-catalog-draft.valid.json");
        catalog.put("catalogVersion", SUITE.path("catalogVersion").asText());
        var template = (ObjectNode) catalog.at("/options/0").deepCopy();
        var options = MAPPER.createArrayNode(); catalog.set("options", options);
        var groups = List.of(List.of("applications", "B2B_SAAS", "PUBLIC_SECTOR_PORTAL", "INTERNAL_WORKFORCE"),
                List.of("clients", "BROWSER", "NATIVE_MOBILE", "MACHINE_TO_MACHINE"),
                List.of("populations", "EXTERNAL_CUSTOMERS", "PARTNERS", "CITIZENS", "INTERNAL_OPERATORS", "EMPLOYEES", "CONTRACTORS"),
                List.of("tenancy", "MULTI_TENANT_ORGANIZATIONS", "NO_ORGANIZATION_BOUNDARY", "SINGLE_ORGANIZATION"),
                List.of("membership", "MULTIPLE_ORGANIZATIONS_PER_USER", "NOT_APPLICABLE", "SINGLE_ORGANIZATION_PER_USER"));
        for (String id : List.of("alpha", "beta")) {
            var option = template.deepCopy(); options.add(option); option.put("id", id); option.put("providerId", "fictional-" + id);
            option.put("product", "Fictional Golden " + id); option.put("configuration", "Acceptance-only " + id);
            var facts = option.putObject("facts");
            for (var capability : POLICY.at("/scoring/dimensions")) {
                var fact = (ObjectNode) template.at("/facts/OIDC").deepCopy();
                fact.put("availability", capability.asText().equals("SOCIAL_LOGIN") && profile.at("/protocols/socialLogin").asText().equals("FORBIDDEN") ? "UNAVAILABLE" : "MANDATORY");
                facts.set(capability.asText(), fact);
            }
            var compatibility = option.putObject("compatibility");
            for (var group : groups) {
                var target = compatibility.putObject(group.getFirst());
                for (var key : group.subList(1, group.size())) target.set(key, template.at("/compatibility/applications/B2B_SAAS").deepCopy());
            }
            var residency = option.putObject("residency");
            for (String category : List.of("USER_PROFILES", "CREDENTIALS", "AUDIT_LOGS", "BACKUPS")) {
                var fact = (ObjectNode) template.at("/residency/USER_PROFILES").deepCopy();
                fact.set("storageCountries", MAPPER.createArrayNode().add("DE")); residency.set(category, fact);
            }
            var controls = option.putObject("authenticationControls");
            for (String client : List.of("BROWSER", "NATIVE_MOBILE")) {
                var populations = controls.putObject(client);
                for (String population : groups.get(2).subList(1, groups.get(2).size())) {
                    var target = populations.putObject(population);
                    for (String control : List.of("PHISHING_RESISTANCE", "NON_EXPORTABLE_KEYS", "STEP_UP_AUTHENTICATION"))
                        target.set(control, template.at("/authenticationControls/BROWSER/PARTNERS/PHISHING_RESISTANCE").deepCopy());
                }
            }
            fictionalEvidence(option, id);
        }
        return catalog;
    }

    private static ObjectNode audit(JsonNode catalog) {
        var supplement = (ObjectNode) read("packages/contracts/tests/fixtures/catalog-auditability-draft.valid.json");
        supplement.put("evidenceVersion", "fictional-golden-audit-1");
        supplement.put("baseCatalogVersion", catalog.path("catalogVersion").asText());
        supplement.put("baseContentSha256", CatalogDraftCanonicalizer.sha256(MAPPER.treeToValue(catalog, ProviderCatalogDraft.class)));
        var template = (ObjectNode) supplement.at("/options/0/facts/0").deepCopy();
        var options = supplement.putArray("options");
        for (var option : catalog.get("options")) {
            var target = options.addObject(); var scope = target.putObject("scope");
            scope.put("optionId", option.path("id").asText());
            for (String field : List.of("plan", "region", "configuration")) scope.set(field, option.get(field));
            var facts = target.putArray("facts");
            for (var criterion : io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion.values()) {
                var fact = template.deepCopy(); fact.put("criterion", criterion.name());
                if (criterion.name().equals("AUDIT_LOG_RETENTION")) fact.put("documentedMinimumRetentionDays", 365);
                facts.add(fact);
            }
            fictionalEvidence(target, option.path("id").asText());
        }
        return supplement;
    }

    private static void fictionalEvidence(JsonNode node, String id) {
        if (node.has("sourceUrl") && node.has("observedAt")) {
            var evidence = (ObjectNode) node;
            evidence.put("sourceUrl", "https://golden-" + id + ".invalid/identity/declared-scope");
            evidence.set("observedAt", INPUTS.get("observedAt"));
        }
        if (node.isObject() || node.isArray()) node.forEach(child -> fictionalEvidence(child, id));
    }

    static ObjectNode option(JsonNode catalog, String id) { return (ObjectNode) find(catalog.get("options"), id); }
    static JsonNode find(JsonNode array, String id) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false).filter(n -> n.path("id").asText().equals(id)).findFirst().orElseThrow();
    }
    private static void set(ObjectNode node, String path, JsonNode value) {
        var keys = path.split("\\."); var target = node;
        for (int i = 0; i < keys.length - 1; i++) target = (ObjectNode) target.get(keys[i]);
        target.set(keys[keys.length - 1], value.deepCopy());
    }
    private static void remove(ObjectNode node, String path) {
        var keys = path.split("\\."); var target = node;
        for (int i = 0; i < keys.length - 1; i++) target = (ObjectNode) target.get(keys[i]);
        target.remove(keys[keys.length - 1]);
    }
    private static JsonNode read(String path) {
        try { return MAPPER.readTree(ROOT.resolve(path).toFile()); }
        catch (Exception e) { throw new IllegalStateException("Cannot read golden input " + path, e); }
    }
}

package io.authweave.core.catalog.impact;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfileValidator;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.evaluation.ArchitecturePatternEvaluator;
import io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator;
import io.authweave.core.evaluation.ProvisioningLifecycleV2Evaluator;
import io.authweave.core.evaluation.ClaimRules;
import io.authweave.core.evaluation.CapabilityEvaluator;
import io.authweave.core.evaluation.EligibilityEvaluator;
import io.authweave.core.evaluation.ResidencyEvaluator;
import io.authweave.core.evaluation.AuthenticationControlEvaluator;
import io.authweave.core.evaluation.ComplianceScopeEvaluator;
import io.authweave.core.evaluation.AuditabilityEvaluator;
import io.authweave.core.evaluation.EvidencePolicy;

/** New bounded decision-calculation scope. Historical incomplete policies and live verification remain unchanged. */
@Component
public final class DecisionPublicationCoveragePolicy {
    public static final String VERSION = "publication-decision-coverage-1";
    public static final String SCOPE = "DECLARED_DECISION_RULES_ONLY";
    public enum Handling { CANDIDATE_FINDINGS, AUDITABILITY_FINDINGS, CONDITIONAL_ARCHITECTURE, EXPLICIT_LIMITATION }
    public record Route(String profilePath, List<String> routes, String factDependency, Handling handling, String outputProfilePath) {
        public Route { routes = List.copyOf(routes); }
    }
    public record Scenario(String id, JsonNode profile, JsonNode weights) {
        public Scenario { Objects.requireNonNull(id); profile = profile.deepCopy(); weights = weights.deepCopy(); }
        @Override public JsonNode profile() { return profile.deepCopy(); }
        @Override public JsonNode weights() { return weights.deepCopy(); }
    }
    public static final Map<String, String> COMPONENT_VERSIONS = Map.ofEntries(
            Map.entry("hardChecks", CandidateHardConstraintEvaluator.VERSION),
            Map.entry("scoring", CandidatePreferenceScorer.VERSION),
            Map.entry("composition", CandidateDecisionEvaluator.VERSION),
            Map.entry("architecture", CandidateDecisionEvaluator.ARCHITECTURE_VERSION),
            Map.entry("impact", CandidateDecisionImpactEvaluator.VERSION),
            Map.entry("auditability", CandidateAuditabilityInput.VERSION),
            Map.entry("patterns", ArchitecturePatternEvaluator.POLICY_VERSION),
            Map.entry("prerequisites", ArchitecturePrerequisiteEvaluator.POLICY_VERSION),
            Map.entry("provisioning", ProvisioningLifecycleEvaluator.POLICY_VERSION),
            Map.entry("provisioningConditions", ProvisioningLifecycleV2Evaluator.POLICY_VERSION),
            Map.entry("claimRules", ClaimRules.VERSION),
            Map.entry("capabilityRules", CapabilityEvaluator.POLICY_VERSION),
            Map.entry("contextRules", EligibilityEvaluator.POLICY_VERSION),
            Map.entry("residencyRules", ResidencyEvaluator.POLICY_VERSION),
            Map.entry("authenticationRules", AuthenticationControlEvaluator.POLICY_VERSION),
            Map.entry("complianceRules", ComplianceScopeEvaluator.POLICY_VERSION),
            Map.entry("auditabilityRules", AuditabilityEvaluator.POLICY_VERSION),
            Map.entry("evidenceMaxAgeDays", Long.toString(EvidencePolicy.MAX_AGE.toDays())),
            Map.entry("storedReviewLoading", StoredCandidateDecisionService.VERSION));
    private static final Set<String> LIMITATION_ONLY = Set.of("security.assurance", "security.complianceTargets",
            "operations.hosting", "operations.deploymentTarget", "operations.identityExpertise", "operations.budgetSensitivity",
            "operations.usagePlanning.scopeDescription", "operations.usagePlanning.assumptions", "operations.usagePlanning.volumes");
    private final List<Route> routes;
    private final List<Scenario> scenarios;
    private final String decisionPolicySha256, scenarioSetSha256, manifestSha256;

    public DecisionPublicationCoveragePolicy(ObjectMapper mapper, CatalogScopedProfileCases base, CatalogAuditabilityRegressionCases audit) throws IOException {
        JsonNode policy;
        try (var input = new ClassPathResource("decision-core/policy.v1.json").getInputStream()) { policy = mapper.readTree(input); }
        if (!CandidateHardConstraintEvaluator.POLICY_VERSION.equals(policy.path("policyVersion").asText())
                || policy.path("profileSchemaVersion").asInt() != 6 || !policy.path("defaultWeights").isNull()
                || !policy.path("inputRoutes").isArray() || policy.get("inputRoutes").size() != 34)
            throw new IllegalStateException("Review declared decision policy binding");
        decisionPolicySha256 = DecisionCanonicalizer.sha256(policy);
        var compiled = new java.util.ArrayList<Route>();
        for (var input : policy.get("inputRoutes")) {
            String path = input.path("profilePath").asText();
            String output = path.startsWith("security.dataResidencyDetails.") ? "security.dataResidency"
                    : path.startsWith("security.auditabilityRequirements.") ? "security.auditability" : path;
            var handling = output.equals("security.auditability") ? Handling.AUDITABILITY_FINDINGS
                    : path.equals("security.browserTokenExposureMinimization") ? Handling.CONDITIONAL_ARCHITECTURE
                    : LIMITATION_ONLY.contains(path) ? Handling.EXPLICIT_LIMITATION : Handling.CANDIDATE_FINDINGS;
            compiled.add(new Route(path, mapper.convertValue(input.get("routes"), mapper.getTypeFactory().constructCollectionType(List.class, String.class)),
                    input.path("factDependency").isNull() ? null : input.path("factDependency").asText(), handling, output));
        }
        routes = List.copyOf(compiled);
        if (!routes.stream().map(Route::profilePath).collect(java.util.stream.Collectors.toSet()).equals(
                CatalogProfilePlanningCoverageService.INPUT_ROUTES.stream().map(CatalogProfilePlanningCoverageService.InputRoute::profilePath)
                    .collect(java.util.stream.Collectors.toSet()))) throw new IllegalStateException("Review all 34 decision input routes");
        scenarios = base.definitions().stream().sorted(java.util.Comparator.comparing(CatalogScenarioCases.Definition::id)).map(definition -> {
            var requirements = audit.definitions().stream().filter(a -> a.scenarioId().equals(definition.id())).findFirst().orElseThrow();
            var profile = (ObjectNode) definition.profile();
            ((ObjectNode) profile.get("security")).set("auditabilityRequirements", mapper.valueToTree(requirements.requirements()));
            if (!CatalogDraftCanonicalizer.sha256(profile).equals(requirements.profileSha256())
                    || !ApplicationIdentityProfileValidator.validate(mapper.treeToValue(profile, ApplicationIdentityProfile.class)).issues().isEmpty())
                throw new IllegalStateException("Review exact supplemental regression profile");
            // Explicit regression weights, NOT inferred assessor defaults or pricing dimensions.
            var weights = mapper.createObjectNode(); var values = weights.putArray("values");
            if (definition.id().equals("partner-portal-scoped")) values.addObject().put("capability", "JIT").put("weight", 100);
            if (definition.id().equals("internal-workforce-scoped")) values.addObject().put("capability", "SAML").put("weight", 100);
            weights.put("mode", values.isEmpty() ? "NONE" : "EXPLICIT");
            return new Scenario(definition.id(), profile, weights);
        }).toList();
        scenarioSetSha256 = DecisionCanonicalizer.sha256(mapper.valueToTree(scenarios));
        manifestSha256 = DecisionCanonicalizer.sha256(mapper.valueToTree(List.of(VERSION, SCOPE, decisionPolicySha256,
                CatalogScopedProfileCases.VERSION, base.sha256(), CatalogAuditabilityRegressionCases.VERSION, audit.sha256(),
                CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_SHA256, routes, scenarios, COMPONENT_VERSIONS,
                CatalogProfilePlanningCoverageService.VERIFICATION_GAPS)));
    }
    public List<Route> routes() { return routes; }
    public List<Scenario> scenarios() { return scenarios; }
    public String decisionPolicySha256() { return decisionPolicySha256; }
    public String scenarioSetSha256() { return scenarioSetSha256; }
    public String manifestSha256() { return manifestSha256; }
}

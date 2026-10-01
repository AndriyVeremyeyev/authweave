package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.evaluation.ArchitecturePatternEvaluator;

/** Structural regression coverage, not provider support, source truth or permission to publish. */
@Service
public final class CatalogProfileImpactCoverageService {
    public static final String POLICY_VERSION = "catalog-profile-impact-coverage-4";
    public static final int PROFILE_SCHEMA_VERSION = 5;
    public static final String PROFILE_SCHEMA_SHA256 = "c995122fdd206e90bdf8145ee76e0e85f657933bb4b91dfae39a6ef715e30561";
    public enum Boundary { CATALOG_CLAIM_RULE, REQUIREMENTS_SCOPE, ARCHITECTURE_PATTERN, ARCHITECTURE_CONFIGURATION, AUDITABILITY,
        ASSURANCE, COMPLIANCE_EVIDENCE, OPERATIONS, COST_MODEL, PROVISIONING_LIFECYCLE, CONFIGURED_AUTHENTICATION_FLOW }
    public enum State { CONDITIONAL_RULE_PRESENT, PATTERN_RULE_PRESENT, SCOPE_GUARD_ONLY, DEFERRED_DIMENSION, MISSING_RULE }
    public enum Status { NOT_CHECKED, INCOMPLETE, COMPLETE }
    public record Dimension(String profilePath, String ruleProfilePath, Boundary boundary, List<String> permittedFactPaths) {
        public Dimension { permittedFactPaths = List.copyOf(permittedFactPaths); }
    }
    public record AdditionalBoundary(String profilePath, Boundary boundary) { }
    // Every declared v5 input, including currently unrecorded fields. Maps/arrays are one bounded semantic input.
    public static final List<Dimension> DIMENSIONS = dimensions();
    public static final List<AdditionalBoundary> ADDITIONAL_BOUNDARIES = List.of(
            new AdditionalBoundary("security.browserTokenExposureMinimization", Boundary.ARCHITECTURE_CONFIGURATION),
            new AdditionalBoundary("provisioning", Boundary.PROVISIONING_LIFECYCLE),
            new AdditionalBoundary("security.authenticationControls", Boundary.CONFIGURED_AUTHENTICATION_FLOW));
    public static final String MANIFEST_SHA256 = CatalogDraftCanonicalizer.sha256(List.of(PROFILE_SCHEMA_VERSION, PROFILE_SCHEMA_SHA256, DIMENSIONS, ADDITIONAL_BOUNDARIES,
            ArchitecturePatternEvaluator.POLICY_VERSION, CatalogArchitectureImpactService.DEFINITIONS_SHA256, CatalogArchitectureImpactService.PREREQUISITES_SHA256));
    private final CatalogScopedProfileCases cases;
    private final CatalogArchitectureImpactService architecture;
    public CatalogProfileImpactCoverageService(CatalogScopedProfileCases cases, CatalogArchitectureImpactService architecture) { this.cases = cases; this.architecture = architecture; }

    public record DimensionCheck(String scenarioId, String profilePath, Boundary boundary, State state,
            int ruleCount, int activeFactRuleCount, List<String> factPaths) {
        public DimensionCheck {
            Objects.requireNonNull(scenarioId); Objects.requireNonNull(profilePath); Objects.requireNonNull(boundary); Objects.requireNonNull(state);
            factPaths = List.copyOf(factPaths);
            var boundFactPaths = factPaths;
            if (ruleCount < 0 || ruleCount > 100 || activeFactRuleCount < 0 || activeFactRuleCount > ruleCount
                    || new HashSet<>(factPaths).size() != factPaths.size() || factPaths.size() > activeFactRuleCount
                    || activeFactRuleCount > 0 && factPaths.isEmpty()
                    || (state == State.CONDITIONAL_RULE_PRESENT) != (activeFactRuleCount > 0)
                    || (state == State.SCOPE_GUARD_ONLY) != (ruleCount > 0 && activeFactRuleCount == 0 && boundary != Boundary.ARCHITECTURE_PATTERN)
                    || (state == State.PATTERN_RULE_PRESENT) != (boundary == Boundary.ARCHITECTURE_PATTERN)
                    || state == State.PATTERN_RULE_PRESENT && ruleCount != CatalogArchitectureImpactService.PATTERN_COUNT
                    || (state == State.DEFERRED_DIMENSION || state == State.MISSING_RULE) && ruleCount != 0
                    || DIMENSIONS.stream().noneMatch(d -> d.profilePath().equals(profilePath) && d.boundary() == boundary
                        && d.permittedFactPaths().containsAll(boundFactPaths)
                        && (d.ruleProfilePath() == null) == (state == State.DEFERRED_DIMENSION)))
                throw new IllegalArgumentException("Inconsistent profile coverage dimension");
        }
    }
    public record BoundaryGap(String scenarioId, String profilePath, Boundary boundary) { }
    /** No profile values, source bodies, actor identities, request bodies or successful outcome claims. */
    public record Check(Status status, Instant evaluatedAt, String scenarioSetSha256, CatalogArchitectureImpactService.Check architectureImpact, List<DimensionCheck> dimensions,
            List<BoundaryGap> additionalGaps, List<String> unexercisedFactPaths) {
        public Check {
            Objects.requireNonNull(status); Objects.requireNonNull(architectureImpact); dimensions = List.copyOf(dimensions); additionalGaps = List.copyOf(additionalGaps);
            unexercisedFactPaths = List.copyOf(unexercisedFactPaths);
            var boundDimensions = dimensions;
            var exercised = dimensions.stream().flatMap(d -> d.factPaths().stream()).collect(java.util.stream.Collectors.toSet());
            var expectedUnexercised = CatalogFactPathRegressionCases.PROBES.stream().map(CatalogImpactCases.Probe::factPath)
                    .filter(p -> !exercised.contains(p)).collect(java.util.stream.Collectors.toSet());
            if (status == Status.NOT_CHECKED ? evaluatedAt != null || scenarioSetSha256 != null || !dimensions.isEmpty()
                    || architectureImpact.status() != CatalogArchitectureImpactService.CheckStatus.NOT_CHECKED
                    || !additionalGaps.isEmpty() || !unexercisedFactPaths.isEmpty()
                    : evaluatedAt == null || scenarioSetSha256 == null || !scenarioSetSha256.matches("[a-f0-9]{64}")
                        || architectureImpact.status() != CatalogArchitectureImpactService.CheckStatus.ANALYZED
                        || !evaluatedAt.equals(architectureImpact.evaluatedAt()) || !scenarioSetSha256.equals(architectureImpact.scenarioSetSha256())
                        || dimensions.stream().filter(d -> d.state() == State.PATTERN_RULE_PRESENT).mapToInt(DimensionCheck::ruleCount).sum() != architectureImpact.checkedPatterns()
                        || dimensions.size() != CatalogScopedProfileCases.COUNT * DIMENSIONS.size() || additionalGaps.size() > CatalogScopedProfileCases.COUNT * ADDITIONAL_BOUNDARIES.size()
                        || new HashSet<>(dimensions.stream().map(d -> d.scenarioId() + "|" + d.profilePath()).toList()).size() != dimensions.size()
                        || new HashSet<>(additionalGaps).size() != additionalGaps.size()
                        || new HashSet<>(unexercisedFactPaths).size() != unexercisedFactPaths.size()
                        || unexercisedFactPaths.size() > CatalogFactPathRegressionCases.FACT_PATH_COUNT
                        || dimensions.stream().map(DimensionCheck::scenarioId).distinct().count() != CatalogScopedProfileCases.COUNT
                        || dimensions.stream().anyMatch(d -> !CatalogScopedProfileCases.IDS.contains(d.scenarioId()))
                        || dimensions.stream().anyMatch(d -> boundDimensions.stream().filter(c -> c.scenarioId().equals(d.scenarioId())).count() != DIMENSIONS.size())
                        || !new HashSet<>(additionalGaps).equals(dimensions.stream().map(DimensionCheck::scenarioId).distinct()
                            .flatMap(id -> ADDITIONAL_BOUNDARIES.stream().map(b -> new BoundaryGap(id, b.profilePath(), b.boundary())))
                            .collect(java.util.stream.Collectors.toSet()))
                        || unexercisedFactPaths.stream().anyMatch(p -> CatalogFactPathRegressionCases.PROBES.stream().noneMatch(d -> d.factPath().equals(p)))
                        || !new HashSet<>(unexercisedFactPaths).equals(expectedUnexercised)
                        || (status == Status.COMPLETE) != (dimensions.stream().allMatch(CatalogProfileImpactCoverageService::rulePresent)
                            && additionalGaps.isEmpty() && unexercisedFactPaths.isEmpty()))
                throw new IllegalArgumentException("Inconsistent profile impact coverage");
        }
        public static Check notChecked() { return new Check(Status.NOT_CHECKED, null, null, CatalogArchitectureImpactService.Check.notChecked(), List.of(), List.of(), List.of()); }
        @JsonProperty public String scope() { return "CATALOG_PROFILE_IMPACT_COVERAGE"; }
        @JsonProperty public String analysisBasis() { return "STRUCTURAL_COVERAGE_OF_SCOPED_REGRESSION_SCENARIOS"; }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public int profileSchemaVersion() { return PROFILE_SCHEMA_VERSION; }
        @JsonProperty public String profileSchemaSha256() { return PROFILE_SCHEMA_SHA256; }
        @JsonProperty public String manifestSha256() { return MANIFEST_SHA256; }
        @JsonProperty public String scenarioSetVersion() { return CatalogScopedProfileCases.VERSION; }
        @JsonProperty public int declaredScenarios() { return CatalogScopedProfileCases.COUNT; }
        @JsonProperty public String ruleVersion() { return io.authweave.core.evaluation.ClaimRules.VERSION; }
        @JsonProperty public String profilePolicyVersion() { return io.authweave.core.evaluation.EligibilityEvaluator.COMPLIANCE_SCOPE_POLICY_VERSION; }
        @JsonProperty public String factPathSetVersion() { return CatalogFactPathRegressionCases.VERSION; }
        @JsonProperty public String factPathSetSha256() { return CatalogFactPathRegressionCases.SHA256; }
        @JsonProperty public int declaredProfileInputs() { return DIMENSIONS.size(); }
        @JsonProperty public long conditionalDimensions() { return dimensions.stream().filter(d -> d.state() == State.CONDITIONAL_RULE_PRESENT).count(); }
        @JsonProperty public long patternDimensions() { return dimensions.stream().filter(d -> d.state() == State.PATTERN_RULE_PRESENT).count(); }
        @JsonProperty public long scopeOnlyDimensions() { return dimensions.stream().filter(d -> d.state() == State.SCOPE_GUARD_ONLY).count(); }
        @JsonProperty public long deferredDimensions() { return dimensions.stream().filter(d -> d.state() == State.DEFERRED_DIMENSION).count(); }
        @JsonProperty public long missingRules() { return dimensions.stream().filter(d -> d.state() == State.MISSING_RULE).count(); }
        @JsonProperty public boolean coverageComplete() { return status == Status.COMPLETE; }
        @JsonProperty public boolean storedReportVerified() { return false; }
        @JsonProperty public boolean sourceVerificationPerformed() { return false; }
        @JsonProperty public boolean baselineVerified() { return false; }
        @JsonProperty public boolean approvalGranted() { return false; }
        @JsonProperty public boolean writesPerformed() { return false; }
        @JsonProperty public boolean publicationReady() { return false; }
        @JsonProperty public boolean evaluationReady() { return false; }
    }

    public Check inspectAt(Instant at) { return inspect(cases.definitions(), cases.plans(), cases.sha256(), at); }

    // Testable source-controlled boundary. No caller-supplied scenario or completeness flag enters preflight.
    Check inspect(List<CatalogScenarioCases.Definition> definitions, List<List<ScenarioRulePlan.Rule>> plans, String digest, Instant at) {
        Objects.requireNonNull(at);
        var knownPaths = new TreeSet<String>(); DIMENSIONS.forEach(d -> knownPaths.add(d.profilePath()));
        var factPaths = new TreeSet<String>(); CatalogFactPathRegressionCases.PROBES.forEach(p -> factPaths.add(p.factPath()));
        if (definitions.size() != CatalogScopedProfileCases.COUNT || plans.size() != CatalogScopedProfileCases.COUNT
                || definitions.stream().map(CatalogScenarioCases.Definition::id).distinct().count() != CatalogScopedProfileCases.COUNT
                || !CatalogDraftCanonicalizer.sha256(definitions).equals(digest)) throw new IllegalStateException("Review the frozen scenario coverage binding");
        for (int i = 0; i < definitions.size(); i++) {
            var definition = definitions.get(i); var rules = plans.get(i);
            var inputs = new TreeSet<String>(); inputs(definition.profile(), "", inputs);
            if (definition.profileSchemaVersion() != PROFILE_SCHEMA_VERSION || !inputs.equals(knownPaths)
                    || rules.stream().map(ScenarioRulePlan.Rule::checkId).distinct().count() != rules.size()
                    || rules.stream().anyMatch(r -> DIMENSIONS.stream().noneMatch(d -> d.boundary() != Boundary.ARCHITECTURE_PATTERN && r.profilePath().equals(d.ruleProfilePath())))
                    || rules.stream().anyMatch(r -> r.factPath() != null && (DIMENSIONS.stream().noneMatch(d -> r.profilePath().equals(d.ruleProfilePath())
                            && d.permittedFactPaths().contains(r.factPath()))
                        || CatalogFactPathRegressionCases.PROBES.stream().noneMatch(p -> p.factPath().equals(r.factPath()) && p.factKind() == r.kind())))
                    || rules.stream().anyMatch(r -> r.usesFact() && !factPaths.contains(r.factPath())))
                throw new IllegalStateException("Review and version profile impact coverage for schema/rule drift");
        }
        var report = architecture.analyze(definitions, digest, at);
        var checks = new ArrayList<DimensionCheck>(); var gaps = new ArrayList<BoundaryGap>(); var exercised = new TreeSet<String>();
        for (int i = 0; i < definitions.size(); i++) {
            var definition = definitions.get(i); var rules = plans.get(i);
            for (var dimension : DIMENSIONS) {
                if (dimension.boundary() == Boundary.ARCHITECTURE_PATTERN) {
                    var count = report.scenarios().stream().filter(s -> s.scenarioId().equals(definition.id())).findFirst().orElseThrow().patterns().size();
                    checks.add(new DimensionCheck(definition.id(), dimension.profilePath(), dimension.boundary(), State.PATTERN_RULE_PRESENT, count, 0, List.of()));
                    continue;
                }
                var selected = rules.stream().filter(r -> r.profilePath().equals(dimension.ruleProfilePath())).toList();
                var active = selected.stream().filter(ScenarioRulePlan.Rule::usesFact).toList();
                var paths = active.stream().map(ScenarioRulePlan.Rule::factPath).distinct().sorted().toList(); exercised.addAll(paths);
                var state = !active.isEmpty() ? State.CONDITIONAL_RULE_PRESENT : !selected.isEmpty() ? State.SCOPE_GUARD_ONLY
                        : dimension.ruleProfilePath() == null ? State.DEFERRED_DIMENSION : State.MISSING_RULE;
                checks.add(new DimensionCheck(definition.id(), dimension.profilePath(), dimension.boundary(), state, selected.size(), active.size(), paths));
            }
            ADDITIONAL_BOUNDARIES.forEach(b -> gaps.add(new BoundaryGap(definition.id(), b.profilePath(), b.boundary())));
        }
        var unexercised = factPaths.stream().filter(p -> !exercised.contains(p)).toList();
        boolean complete = checks.stream().allMatch(CatalogProfileImpactCoverageService::rulePresent) && gaps.isEmpty() && unexercised.isEmpty();
        return new Check(complete ? Status.COMPLETE : Status.INCOMPLETE, at, digest, architecture.summarize(report), checks, gaps, unexercised);
    }
    private static boolean rulePresent(DimensionCheck check) { return check.state() == State.CONDITIONAL_RULE_PRESENT || check.state() == State.PATTERN_RULE_PRESENT; }
    private static void inputs(JsonNode value, String path, Set<String> result) {
        if (value.isObject() && !path.equals("operations.usagePlanning.volumes")) {
            value.properties().forEach(e -> inputs(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey(), result));
        } else result.add(path);
    }
    private static List<Dimension> dimensions() {
        var result = new ArrayList<Dimension>();
        for (var path : List.of("application.type", "application.clients", "audience.populations", "audience.tenancy", "audience.membership",
                "protocols.federation.OIDC", "protocols.federation.SAML", "protocols.oauth2ProtectedApis", "protocols.socialLogin",
                "protocols.enterpriseSingleSignOn", "provisioning.scim", "provisioning.justInTimeProvisioning", "provisioning.groupSynchronization",
                "security.multiFactorAuthentication", "security.dataResidency", "security.authenticationControls.phishingResistance",
                "security.authenticationControls.nonExportableKeys", "security.authenticationControls.stepUpAuthentication"))
            result.add(new Dimension(path, path, Boundary.CATALOG_CLAIM_RULE, permitted(path)));
        for (var path : List.of("security.dataResidencyDetails.allowedCountries", "security.dataResidencyDetails.dataCategories"))
            result.add(new Dimension(path, "security.dataResidency", Boundary.CATALOG_CLAIM_RULE, permitted("security.dataResidency")));
        result.add(new Dimension("security.complianceScopeStatus", "security.complianceScopeStatus", Boundary.REQUIREMENTS_SCOPE, List.of()));
        result.add(new Dimension("security.browserTokenExposureMinimization", "security.browserTokenExposureMinimization", Boundary.ARCHITECTURE_PATTERN, List.of()));
        result.add(new Dimension("security.auditability", null, Boundary.AUDITABILITY, List.of()));
        result.add(new Dimension("security.assurance", null, Boundary.ASSURANCE, List.of()));
        result.add(new Dimension("security.complianceTargets", null, Boundary.COMPLIANCE_EVIDENCE, List.of()));
        for (var path : List.of("operations.hosting", "operations.deploymentTarget", "operations.identityExpertise"))
            result.add(new Dimension(path, null, Boundary.OPERATIONS, List.of()));
        for (var path : List.of("operations.budgetSensitivity", "operations.usagePlanning.scopeDescription", "operations.usagePlanning.assumptions", "operations.usagePlanning.volumes"))
            result.add(new Dimension(path, null, Boundary.COST_MODEL, List.of()));
        if (result.size() != 32 || result.stream().map(Dimension::profilePath).distinct().count() != 32)
            throw new IllegalStateException("Review profile coverage manifest");
        return result.stream().sorted(Comparator.comparing(Dimension::profilePath)).toList();
    }
    private static List<String> permitted(String path) {
        String address = switch (path) {
            case "application.type" -> "compatibility.applications.";
            case "application.clients" -> "compatibility.clients.";
            case "audience.populations" -> "compatibility.populations.";
            case "audience.tenancy" -> "compatibility.tenancy.";
            case "audience.membership" -> "compatibility.membership.";
            case "protocols.federation.OIDC" -> "facts.OIDC";
            case "protocols.federation.SAML" -> "facts.SAML";
            case "protocols.oauth2ProtectedApis" -> "facts.OAUTH2_APIS";
            case "protocols.socialLogin" -> "facts.SOCIAL_LOGIN";
            case "protocols.enterpriseSingleSignOn" -> "facts.ENTERPRISE_SSO";
            case "provisioning.scim" -> "facts.SCIM";
            case "provisioning.justInTimeProvisioning" -> "facts.JIT";
            case "provisioning.groupSynchronization" -> "facts.GROUP_SYNC";
            case "security.multiFactorAuthentication" -> "facts.MFA";
            case "security.dataResidency" -> "residency.";
            case "security.authenticationControls.phishingResistance" -> ".PHISHING_RESISTANCE";
            case "security.authenticationControls.nonExportableKeys" -> ".NON_EXPORTABLE_KEYS";
            case "security.authenticationControls.stepUpAuthentication" -> ".STEP_UP_AUTHENTICATION";
            default -> throw new IllegalStateException("Unknown profile-to-fact dependency");
        };
        return CatalogFactPathRegressionCases.PROBES.stream().map(CatalogImpactCases.Probe::factPath)
                .filter(p -> address.startsWith(".") ? p.startsWith("authenticationControls.") && p.endsWith(address)
                        : address.endsWith(".") ? p.startsWith(address) : p.equals(address)).sorted().toList();
    }
}

package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.evaluation.ArchitecturePatternEvaluator;
import io.authweave.core.evaluation.ArchitectureConfigurationEvaluator;
import io.authweave.core.evaluation.AssuranceCompliancePlanningEvaluator;
import io.authweave.core.evaluation.OperationsPlanningEvaluator;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator;

/** Planning coverage is separate from candidate impact, observed verification and publication authority. */
@Service
public final class CatalogProfilePlanningCoverageService {
    public static final String POLICY_VERSION = "catalog-profile-planning-coverage-1";
    public enum Family { ARCHITECTURE_CONFIGURATION, PROVISIONING_LIFECYCLE, OPERATIONS_PLANNING, ASSURANCE_COMPLIANCE }
    public record InputRoute(String profilePath, List<Family> planningRegressions) {
        public InputRoute { planningRegressions = List.copyOf(planningRegressions); }
    }
    public record VerificationGap(Family family, String boundary) { }
    public static final List<InputRoute> INPUT_ROUTES = routes();
    public static final List<VerificationGap> VERIFICATION_GAPS = Stream.of(Family.values())
            .flatMap(f -> deferred(f).stream().map(b -> new VerificationGap(f, b))).toList();
    public static final String MANIFEST_SHA256 = CatalogDraftCanonicalizer.sha256(List.of(POLICY_VERSION,
            CatalogProfileImpactCoverageV6Service.MANIFEST_SHA256, INPUT_ROUTES, VERIFICATION_GAPS,
            Stream.of(Family.values()).map(f -> List.of(f, policy(f), definitions(f))).toList()));
    private final CatalogProfileImpactCoverageV6Service structural;
    private final CatalogArchitectureConfigurationRegressionService architecture;
    private final CatalogLifecycleRegressionService lifecycle;
    private final CatalogOperationsPlanningRegressionService operations;
    private final CatalogAssuranceComplianceRegressionService assurance;

    public CatalogProfilePlanningCoverageService(CatalogProfileImpactCoverageV6Service structural,
            CatalogArchitectureConfigurationRegressionService architecture, CatalogLifecycleRegressionService lifecycle,
            CatalogOperationsPlanningRegressionService operations, CatalogAssuranceComplianceRegressionService assurance) {
        this.structural = structural; this.architecture = architecture; this.lifecycle = lifecycle;
        this.operations = operations; this.assurance = assurance;
    }

    /** Core-owned fresh inputs. This is not an HTTP request or a durable receipt. */
    public record Snapshot(CatalogProfileImpactCoverageV6Service.Check structural,
            CatalogArchitectureConfigurationRegressionService.Check architecture, CatalogLifecycleRegressionService.Check lifecycle,
            CatalogOperationsPlanningRegressionService.Check operations, CatalogAssuranceComplianceRegressionService.Check assurance) {
        public Snapshot { Objects.requireNonNull(structural); Objects.requireNonNull(architecture); Objects.requireNonNull(lifecycle); Objects.requireNonNull(operations); Objects.requireNonNull(assurance); }
    }
    public record RegressionBinding(Family family, String policyVersion, String definitionsSha256, String scenarioSetVersion,
            String scenarioSetSha256, int profileSchemaVersion, String profileSchemaSha256, String analysisSha256,
            String checkSha256, int checkedCases) {
        public RegressionBinding {
            Objects.requireNonNull(family);
            int schema = family == Family.ARCHITECTURE_CONFIGURATION ? 5 : 6;
            String schemaHash = schema == 5 ? CatalogProfileImpactCoverageService.PROFILE_SCHEMA_SHA256 : CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_SHA256;
            String version = switch (family) {
                case ARCHITECTURE_CONFIGURATION -> CatalogArchitectureConfigurationCases.VERSION;
                case PROVISIONING_LIFECYCLE -> CatalogLifecycleRegressionCases.VERSION;
                case OPERATIONS_PLANNING -> CatalogOperationsPlanningCases.VERSION;
                case ASSURANCE_COMPLIANCE -> CatalogAssuranceComplianceCases.VERSION;
            };
            int count = switch (family) {
                case ARCHITECTURE_CONFIGURATION -> CatalogArchitectureConfigurationCases.COUNT;
                case PROVISIONING_LIFECYCLE -> CatalogLifecycleRegressionCases.COUNT;
                case OPERATIONS_PLANNING -> CatalogOperationsPlanningCases.COUNT;
                case ASSURANCE_COMPLIANCE -> CatalogAssuranceComplianceCases.COUNT;
            };
            if (!policy(family).equals(policyVersion) || !definitions(family).equals(definitionsSha256)
                    || !version.equals(scenarioSetVersion) || checkedCases != count || profileSchemaVersion != schema
                    || !schemaHash.equals(profileSchemaSha256) || !hash(scenarioSetSha256) || !hash(analysisSha256) || !hash(checkSha256))
                throw new IllegalArgumentException("Unversioned planning regression binding");
        }
    }
    public record Dimension(String scenarioId, String profilePath, CatalogProfileImpactCoverageV6Service.State structuralState,
            List<Family> planningRegressions) {
        public Dimension { Objects.requireNonNull(structuralState); planningRegressions = List.copyOf(planningRegressions); }
    }
    public record Check(Instant evaluatedAt, CatalogProfileImpactCoverageV6Service.Check structuralCoverage,
            List<RegressionBinding> regressions, List<Dimension> dimensions, List<VerificationGap> verificationGaps) {
        public Check {
            Objects.requireNonNull(evaluatedAt); Objects.requireNonNull(structuralCoverage);
            regressions = List.copyOf(regressions); dimensions = List.copyOf(dimensions); verificationGaps = List.copyOf(verificationGaps);
            if (structuralCoverage.status() != CatalogProfileImpactCoverageV6Service.Status.INCOMPLETE
                    || !evaluatedAt.equals(structuralCoverage.evaluatedAt())
                    || !CatalogAssuranceComplianceCases.BASE_SHA256.equals(structuralCoverage.baseScenarioSetSha256())
                    || !regressions.stream().map(RegressionBinding::family).toList().equals(List.of(Family.values()))
                    || !dimensions.equals(matrix(structuralCoverage)) || !verificationGaps.equals(VERIFICATION_GAPS))
                throw new IllegalArgumentException("Incomplete planning coverage composition");
        }
        @JsonProperty public String scope() { return "CATALOG_PROFILE_PLANNING_COVERAGE"; }
        @JsonProperty public String status() { return "INCOMPLETE"; }
        @JsonProperty public String analysisBasis() { return "STRUCTURAL_CATALOG_RULES_AND_SEPARATE_SYNTHETIC_PLANNING_REGRESSIONS"; }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public String manifestSha256() { return MANIFEST_SHA256; }
        @JsonProperty public int profileSchemaVersion() { return CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_VERSION; }
        @JsonProperty public String profileSchemaSha256() { return CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_SHA256; }
        @JsonProperty public String baseScenarioSetSha256() { return structuralCoverage.baseScenarioSetSha256(); }
        @JsonProperty public String auditabilityScenarioSetSha256() { return structuralCoverage.scenarioSetSha256(); }
        @JsonProperty public String structuralCoverageSha256() { return CatalogDraftCanonicalizer.sha256(structuralCoverage); }
        @JsonProperty public int declaredProfileInputs() { return INPUT_ROUTES.size(); }
        @JsonProperty public int declaredScenarios() { return CatalogScopedProfileCases.COUNT; }
        @JsonProperty public int checkedDimensions() { return dimensions.size(); }
        @JsonProperty public int checkedRegressionFamilies() { return regressions.size(); }
        @JsonProperty public long planningAddedToDeferredDimensions() { return dimensions.stream().filter(d -> d.structuralState() == CatalogProfileImpactCoverageV6Service.State.DEFERRED_DIMENSION && !d.planningRegressions().isEmpty()).count(); }
        @JsonProperty public long unroutedDeferredDimensions() { return dimensions.stream().filter(d -> d.structuralState() == CatalogProfileImpactCoverageV6Service.State.DEFERRED_DIMENSION && d.planningRegressions().isEmpty()).count(); }
        @JsonProperty public String analysisSha256() { return CatalogDraftCanonicalizer.sha256(List.of(POLICY_VERSION, evaluatedAt,
                MANIFEST_SHA256, structuralCoverageSha256(), regressions, dimensions, verificationGaps)); }
        @JsonProperty public boolean candidateChangesEvaluated() { return false; }
        @JsonProperty public boolean coverageComplete() { return false; }
        @JsonProperty public boolean configurationVerified() { return false; }
        @JsonProperty public boolean lifecycleVerified() { return false; }
        @JsonProperty public boolean operationalReadinessVerified() { return false; }
        @JsonProperty public boolean costModelEvaluated() { return false; }
        @JsonProperty public boolean assuranceVerified() { return false; }
        @JsonProperty public boolean complianceVerified() { return false; }
        @JsonProperty public boolean sourceVerificationPerformed() { return false; }
        @JsonProperty public boolean storedReportVerified() { return false; }
        @JsonProperty public boolean baselineVerified() { return false; }
        @JsonProperty public boolean approvalGranted() { return false; }
        @JsonProperty public boolean publicationReady() { return false; }
        @JsonProperty public boolean evaluationReady() { return false; }
        @JsonProperty public boolean recommendationReady() { return false; }
        @JsonProperty public boolean writesPerformed() { return false; }
    }

    public Check inspectAt(Instant at) { return compose(readAt(at), at); }
    /** Fresh preflight consumers supply the exact structural result, never an HTTP completeness assertion. */
    public Check inspectUsing(CatalogProfileImpactCoverageV6Service.Check coverage, Instant at) {
        Objects.requireNonNull(coverage); Objects.requireNonNull(at);
        if (coverage.status() != CatalogProfileImpactCoverageV6Service.Status.INCOMPLETE || !at.equals(coverage.evaluatedAt()))
            throw new IllegalArgumentException("Use current checked structural coverage");
        var current = readAt(at);
        if (!coverage.equals(current.structural())) throw new IllegalArgumentException("Use the complete exact-bound structural result");
        return compose(current, at);
    }
    Snapshot readAt(Instant at) {
        Objects.requireNonNull(at);
        return new Snapshot(structural.inspectAt(at), architecture.inspectAt(at), lifecycle.inspectAt(at), operations.inspectAt(at), assurance.inspectAt(at));
    }
    /** Exact full replay prevents stale clocks, mixed source sets, omitted rows and fabricated aggregate counts. */
    public Check compose(Snapshot input, Instant at) {
        if (!input.equals(readAt(at))) throw new IllegalArgumentException("Use the complete current Core-owned regression snapshot");
        var base = input.structural().baseScenarioSetSha256(); var audit = input.structural().scenarioSetSha256();
        if (!at.equals(input.structural().evaluatedAt()) || !at.equals(input.architecture().evaluatedAt()) || !at.equals(input.lifecycle().evaluatedAt())
                || !at.equals(input.operations().evaluatedAt()) || !at.equals(input.assurance().evaluatedAt())
                || !base.equals(input.architecture().baseScenarioSetSha256()) || !base.equals(input.lifecycle().baseScenarioSetSha256())
                || !base.equals(input.operations().baseScenarioSetSha256()) || !base.equals(input.assurance().baseScenarioSetSha256())
                || !audit.equals(input.lifecycle().auditabilityScenarioSetSha256()) || !audit.equals(input.operations().auditabilityScenarioSetSha256())
                || !audit.equals(input.assurance().auditabilityScenarioSetSha256()))
            throw new IllegalStateException("Planning regression clock or frozen-source binding drift");
        var a = input.architecture(); var l = input.lifecycle(); var o = input.operations(); var s = input.assurance();
        var bindings = List.of(
            new RegressionBinding(Family.ARCHITECTURE_CONFIGURATION, a.policyVersion(), a.definitionsSha256(), a.scenarioSetVersion(), a.scenarioSetSha256(), a.profileSchemaVersion(), a.profileSchemaSha256(), a.analysisSha256(), CatalogDraftCanonicalizer.sha256(a), a.checkedCases()),
            new RegressionBinding(Family.PROVISIONING_LIFECYCLE, l.policyVersion(), l.definitionsSha256(), l.scenarioSetVersion(), l.scenarioSetSha256(), l.profileSchemaVersion(), l.profileSchemaSha256(), l.analysisSha256(), CatalogDraftCanonicalizer.sha256(l), l.checkedCases()),
            new RegressionBinding(Family.OPERATIONS_PLANNING, o.policyVersion(), o.definitionsSha256(), o.scenarioSetVersion(), o.scenarioSetSha256(), o.profileSchemaVersion(), o.profileSchemaSha256(), o.analysisSha256(), CatalogDraftCanonicalizer.sha256(o), o.checkedCases()),
            new RegressionBinding(Family.ASSURANCE_COMPLIANCE, s.policyVersion(), s.definitionsSha256(), s.scenarioSetVersion(), s.scenarioSetSha256(), s.profileSchemaVersion(), s.profileSchemaSha256(), s.analysisSha256(), CatalogDraftCanonicalizer.sha256(s), s.checkedCases()));
        return new Check(at, input.structural(), bindings, matrix(input.structural()), VERIFICATION_GAPS);
    }
    private static List<Dimension> matrix(CatalogProfileImpactCoverageV6Service.Check check) {
        return check.dimensions().stream().map(d -> new Dimension(d.scenarioId(), d.profilePath(), d.state(), INPUT_ROUTES.stream()
                .filter(r -> r.profilePath().equals(d.profilePath())).findFirst().orElseThrow().planningRegressions())).toList();
    }
    private static List<InputRoute> routes() {
        var declared = CatalogProfileImpactCoverageV6Service.DIMENSIONS.stream().map(d -> d.profilePath()).toList();
        if (declared.size() != 34 || !declared.containsAll(Stream.of(Family.values()).flatMap(f -> paths(f).stream()).toList()))
            throw new IllegalStateException("Review the versioned planning input manifest");
        return declared.stream().map(path -> new InputRoute(path, Stream.of(Family.values()).filter(f -> paths(f).contains(path)).toList())).toList();
    }
    private static List<String> paths(Family family) { return switch (family) {
        case ARCHITECTURE_CONFIGURATION -> ArchitecturePatternEvaluator.CHECKED_PATHS;
        case PROVISIONING_LIFECYCLE -> ProvisioningLifecycleEvaluator.CHECKED_PATHS;
        case OPERATIONS_PLANNING -> OperationsPlanningEvaluator.CHECKED_PATHS;
        case ASSURANCE_COMPLIANCE -> AssuranceCompliancePlanningEvaluator.CHECKED_PATHS;
    }; }
    private static List<String> deferred(Family family) { return switch (family) {
        case ARCHITECTURE_CONFIGURATION -> ArchitectureConfigurationEvaluator.DEFERRED_BOUNDARIES;
        case PROVISIONING_LIFECYCLE -> ProvisioningLifecycleEvaluator.DEFERRED_BOUNDARIES;
        case OPERATIONS_PLANNING -> OperationsPlanningEvaluator.DEFERRED_BOUNDARIES;
        case ASSURANCE_COMPLIANCE -> AssuranceCompliancePlanningEvaluator.DEFERRED_BOUNDARIES;
    }; }
    private static String policy(Family family) { return switch (family) {
        case ARCHITECTURE_CONFIGURATION -> CatalogArchitectureConfigurationRegressionService.POLICY_VERSION;
        case PROVISIONING_LIFECYCLE -> CatalogLifecycleRegressionService.POLICY_VERSION;
        case OPERATIONS_PLANNING -> CatalogOperationsPlanningRegressionService.POLICY_VERSION;
        case ASSURANCE_COMPLIANCE -> CatalogAssuranceComplianceRegressionService.POLICY_VERSION;
    }; }
    private static String definitions(Family family) { return switch (family) {
        case ARCHITECTURE_CONFIGURATION -> CatalogArchitectureConfigurationRegressionService.DEFINITIONS_SHA256;
        case PROVISIONING_LIFECYCLE -> CatalogLifecycleRegressionService.DEFINITIONS_SHA256;
        case OPERATIONS_PLANNING -> CatalogOperationsPlanningRegressionService.DEFINITIONS_SHA256;
        case ASSURANCE_COMPLIANCE -> CatalogAssuranceComplianceRegressionService.DEFINITIONS_SHA256;
    }; }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
}

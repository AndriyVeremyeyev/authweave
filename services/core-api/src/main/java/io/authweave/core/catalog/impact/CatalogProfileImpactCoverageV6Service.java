package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.impact.CatalogProfileImpactCoverageService.Boundary;
import io.authweave.core.evaluation.AuditabilityEvaluator;

/** Versioned structural composition; synthetic sidecar regression is never proposed-catalog impact or verified logging. */
@Service
public final class CatalogProfileImpactCoverageV6Service {
    public static final String POLICY_VERSION = "catalog-profile-impact-coverage-5";
    public enum Status { NOT_CHECKED, INCOMPLETE }
    public enum State { CONDITIONAL_RULE_PRESENT, PATTERN_RULE_PRESENT, SYNTHETIC_AUDITABILITY_RULE_PRESENT,
        SCOPE_GUARD_ONLY, DEFERRED_DIMENSION, MISSING_RULE }
    public record Dimension(String profilePath, String ruleProfilePath, Boundary boundary,
            List<String> permittedFactPaths, List<Criterion> permittedEvidenceCriteria) {
        public Dimension { permittedFactPaths = List.copyOf(permittedFactPaths); permittedEvidenceCriteria = List.copyOf(permittedEvidenceCriteria); }
    }
    public record BoundaryGap(String scenarioId, String profilePath, String boundary) { }
    public static final List<Dimension> DIMENSIONS = dimensions();
    public static final List<BoundaryGap> VERIFICATION_GAPS = CatalogScopedProfileCases.IDS.stream().sorted().flatMap(id -> {
        var gaps = new ArrayList<BoundaryGap>();
        CatalogProfileImpactCoverageService.ADDITIONAL_BOUNDARIES.forEach(b -> gaps.add(new BoundaryGap(id, b.profilePath(), b.boundary().name())));
        AuditabilityEvaluator.DEFERRED_BOUNDARIES.forEach(b -> gaps.add(new BoundaryGap(id, "security.auditability", b)));
        return gaps.stream();
    }).toList();
    public static final String MANIFEST_SHA256 = CatalogDraftCanonicalizer.sha256(List.of(POLICY_VERSION,
            CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_VERSION, CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_SHA256,
            CatalogProfileImpactCoverageService.MANIFEST_SHA256, CatalogAuditabilityRegressionService.POLICY_VERSION,
            CatalogAuditabilityRegressionService.DEFINITIONS_SHA256, DIMENSIONS, VERIFICATION_GAPS));
    private final CatalogProfileImpactCoverageService legacy;
    private final CatalogAuditabilityRegressionCases cases;
    private final CatalogAuditabilityRegressionService auditability;
    public CatalogProfileImpactCoverageV6Service(CatalogProfileImpactCoverageService legacy,
            CatalogAuditabilityRegressionCases cases, CatalogAuditabilityRegressionService auditability) {
        this.legacy = legacy; this.cases = cases; this.auditability = auditability;
    }
    public record DimensionCheck(String scenarioId, String profilePath, Boundary boundary, State state,
            int ruleCount, int activeFactRuleCount, int activeAuditabilityRuleCount,
            List<String> factPaths, List<Criterion> evidenceCriteria) {
        public DimensionCheck {
            Objects.requireNonNull(boundary); Objects.requireNonNull(state);
            factPaths = List.copyOf(factPaths); evidenceCriteria = List.copyOf(evidenceCriteria);
            var definition = DIMENSIONS.stream().filter(d -> d.profilePath().equals(profilePath) && d.boundary() == boundary).findFirst().orElseThrow(
                    () -> new IllegalArgumentException("Unknown v6 coverage dimension"));
            if (!CatalogScopedProfileCases.IDS.contains(scenarioId) || ruleCount < 0 || ruleCount > 100
                    || activeFactRuleCount < 0 || activeFactRuleCount > ruleCount || activeAuditabilityRuleCount < 0
                    || activeAuditabilityRuleCount > ruleCount || activeAuditabilityRuleCount != evidenceCriteria.size()
                    || new HashSet<>(factPaths).size() != factPaths.size() || new HashSet<>(evidenceCriteria).size() != evidenceCriteria.size()
                    || !definition.permittedFactPaths().containsAll(factPaths) || !definition.permittedEvidenceCriteria().containsAll(evidenceCriteria)
                    || factPaths.size() > activeFactRuleCount || activeFactRuleCount > 0 && factPaths.isEmpty()
                    || (state == State.CONDITIONAL_RULE_PRESENT) != (activeFactRuleCount > 0)
                    || (state == State.SYNTHETIC_AUDITABILITY_RULE_PRESENT) != (activeAuditabilityRuleCount > 0)
                    || (state == State.PATTERN_RULE_PRESENT) != (boundary == Boundary.ARCHITECTURE_PATTERN)
                    || state == State.PATTERN_RULE_PRESENT && (ruleCount != CatalogArchitectureImpactService.PATTERN_COUNT || !factPaths.isEmpty())
                    || (state == State.SCOPE_GUARD_ONLY) != (ruleCount > 0 && activeFactRuleCount == 0
                        && activeAuditabilityRuleCount == 0 && boundary != Boundary.ARCHITECTURE_PATTERN)
                    || (state == State.DEFERRED_DIMENSION || state == State.MISSING_RULE) && ruleCount != 0
                    || (definition.ruleProfilePath() == null) != (state == State.DEFERRED_DIMENSION)
                    || boundary == Boundary.AUDITABILITY && ruleCount != definition.permittedEvidenceCriteria().size())
                throw new IllegalArgumentException("Inconsistent v6 coverage dimension");
        }
    }
    /** Output-only, body-free coverage. Rule presence and fixture outcomes cannot authorize publication. */
    public record Check(Status status, Instant evaluatedAt, String baseScenarioSetSha256, String scenarioSetSha256,
            String catalogCoverageSha256, CatalogAuditabilityRegressionService.Check auditabilityRegression,
            List<DimensionCheck> dimensions, List<BoundaryGap> verificationGaps, List<String> unexercisedFactPaths) {
        public Check {
            Objects.requireNonNull(status); dimensions = List.copyOf(dimensions); verificationGaps = List.copyOf(verificationGaps);
            unexercisedFactPaths = List.copyOf(unexercisedFactPaths);
            if (status == Status.NOT_CHECKED ? evaluatedAt != null || baseScenarioSetSha256 != null || scenarioSetSha256 != null
                    || catalogCoverageSha256 != null || auditabilityRegression != null || !dimensions.isEmpty()
                    || !verificationGaps.isEmpty() || !unexercisedFactPaths.isEmpty()
                    : evaluatedAt == null || !hash(baseScenarioSetSha256) || !hash(scenarioSetSha256) || !hash(catalogCoverageSha256)
                        || auditabilityRegression == null || !evaluatedAt.equals(auditabilityRegression.evaluatedAt())
                        || !scenarioSetSha256.equals(auditabilityRegression.scenarioSetSha256())
                        || !auditabilityRegression.allDeclaredCriteriaExercised()
                        || dimensions.size() != CatalogScopedProfileCases.COUNT * DIMENSIONS.size()
                        || new HashSet<>(dimensions.stream().map(d -> d.scenarioId() + "|" + d.profilePath()).toList()).size() != dimensions.size()
                        || new HashSet<>(verificationGaps).size() != verificationGaps.size()
                        || !new HashSet<>(verificationGaps).equals(new HashSet<>(VERIFICATION_GAPS))
                        || new HashSet<>(unexercisedFactPaths).size() != unexercisedFactPaths.size()
                        || !new HashSet<>(unexercisedFactPaths).equals(unexercised(dimensions)))
                throw new IllegalArgumentException("Incomplete or unbound v6 coverage inventory");
            if (status != Status.NOT_CHECKED) {
                for (var id : CatalogScopedProfileCases.IDS) {
                    var criticality = dimension(dimensions, id, "security.auditability");
                    var selected = dimension(dimensions, id, "security.auditabilityRequirements.selectedCriteria");
                    var retention = dimension(dimensions, id, "security.auditabilityRequirements.minimumRetentionDays");
                    if (criticality.evidenceCriteria().isEmpty() || !criticality.evidenceCriteria().equals(selected.evidenceCriteria())
                            || !retention.evidenceCriteria().equals(selected.evidenceCriteria().stream().filter(c -> c == Criterion.AUDIT_LOG_RETENTION).toList()))
                        throw new IllegalArgumentException("Auditability requirement dependencies disagree");
                }
                var exercised = dimensions.stream().flatMap(d -> d.evidenceCriteria().stream()).distinct().sorted().toList();
                int selected = dimensions.stream().filter(d -> d.profilePath().equals("security.auditabilityRequirements.selectedCriteria"))
                        .mapToInt(DimensionCheck::activeAuditabilityRuleCount).sum();
                if (!exercised.equals(auditabilityRegression.exercisedRequiredCriteria())
                        || selected * auditabilityRegression.checkedScopes() != auditabilityRegression.checkedCriteria() - auditabilityRegression.outcomes().notApplied())
                    throw new IllegalArgumentException("Auditability coverage does not match exercised criteria");
            }
        }
        public static Check notChecked() { return new Check(Status.NOT_CHECKED, null, null, null, null, null, List.of(), List.of(), List.of()); }
        @JsonProperty public String scope() { return "CATALOG_PROFILE_V6_COVERAGE"; }
        @JsonProperty public String analysisBasis() { return "STRUCTURAL_RULES_AND_SEPARATE_SYNTHETIC_AUDITABILITY_REGRESSION"; }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public int profileSchemaVersion() { return CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_VERSION; }
        @JsonProperty public String profileSchemaSha256() { return CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_SHA256; }
        @JsonProperty public String manifestSha256() { return MANIFEST_SHA256; }
        @JsonProperty public String catalogCoveragePolicyVersion() { return CatalogProfileImpactCoverageService.POLICY_VERSION; }
        @JsonProperty public String catalogCoverageManifestSha256() { return CatalogProfileImpactCoverageService.MANIFEST_SHA256; }
        @JsonProperty public String baseScenarioSetVersion() { return CatalogScopedProfileCases.VERSION; }
        @JsonProperty public String scenarioSetVersion() { return CatalogAuditabilityRegressionCases.VERSION; }
        @JsonProperty public int declaredScenarios() { return CatalogScopedProfileCases.COUNT; }
        @JsonProperty public int declaredProfileInputs() { return DIMENSIONS.size(); }
        @JsonProperty public int checkedDimensions() { return dimensions.size(); }
        @JsonProperty public long conditionalDimensions() { return count(State.CONDITIONAL_RULE_PRESENT); }
        @JsonProperty public long patternDimensions() { return count(State.PATTERN_RULE_PRESENT); }
        @JsonProperty public long auditabilityDimensions() { return count(State.SYNTHETIC_AUDITABILITY_RULE_PRESENT); }
        @JsonProperty public long scopeOnlyDimensions() { return count(State.SCOPE_GUARD_ONLY); }
        @JsonProperty public long deferredDimensions() { return count(State.DEFERRED_DIMENSION); }
        @JsonProperty public long missingRules() { return count(State.MISSING_RULE); }
        @JsonProperty public boolean candidateAuditabilityChangesEvaluated() { return false; }
        @JsonProperty public boolean coverageComplete() { return false; }
        @JsonProperty public boolean configurationVerified() { return false; }
        @JsonProperty public boolean complianceVerified() { return false; }
        @JsonProperty public boolean storedReportVerified() { return false; }
        @JsonProperty public boolean sourceVerificationPerformed() { return false; }
        @JsonProperty public boolean baselineVerified() { return false; }
        @JsonProperty public boolean approvalGranted() { return false; }
        @JsonProperty public boolean writesPerformed() { return false; }
        @JsonProperty public boolean publicationReady() { return false; }
        @JsonProperty public boolean evaluationReady() { return false; }
        @JsonProperty public boolean recommendationReady() { return false; }
        private long count(State state) { return dimensions.stream().filter(d -> d.state() == state).count(); }
    }
    public Check inspectAt(Instant at) { return inspectUsing(legacy.inspectAt(at), at); }
    /** Core-owned legacy input, never an accepted HTTP body or caller completeness assertion. */
    public Check inspectUsing(CatalogProfileImpactCoverageService.Check base, Instant at) {
        if (!base.equals(legacy.inspectAt(at)) || base.status() == CatalogProfileImpactCoverageService.Status.NOT_CHECKED
                || !cases.baseScenarioSetSha256().equals(base.scenarioSetSha256()))
            throw new IllegalArgumentException("Use the current bound legacy structural coverage");
        var audit = auditability.summarize(auditability.analyzeAt(at));
        if (!audit.scenarioSetSha256().equals(cases.sha256())) throw new IllegalStateException("Auditability scenario binding drifted");
        var rows = new ArrayList<DimensionCheck>();
        base.dimensions().stream().filter(d -> !d.profilePath().equals("security.auditability")).forEach(d -> rows.add(
                new DimensionCheck(d.scenarioId(), d.profilePath(), d.boundary(), State.valueOf(d.state().name()),
                        d.ruleCount(), d.activeFactRuleCount(), 0, d.factPaths(), List.of())));
        for (var input : cases.definitions()) for (var definition : DIMENSIONS) if (definition.boundary() == Boundary.AUDITABILITY) {
            var active = definition.permittedEvidenceCriteria().stream().filter(input.requirements().selectedCriteria()::contains).toList();
            rows.add(new DimensionCheck(input.scenarioId(), definition.profilePath(), Boundary.AUDITABILITY,
                    active.isEmpty() ? State.SCOPE_GUARD_ONLY : State.SYNTHETIC_AUDITABILITY_RULE_PRESENT,
                    definition.permittedEvidenceCriteria().size(), 0, active.size(), List.of(), active));
        }
        var ordered = rows.stream().sorted(Comparator.comparing(DimensionCheck::scenarioId).thenComparing(DimensionCheck::profilePath)).toList();
        return new Check(Status.INCOMPLETE, at, base.scenarioSetSha256(), cases.sha256(), CatalogDraftCanonicalizer.sha256(base),
                audit, ordered, VERIFICATION_GAPS, base.unexercisedFactPaths());
    }
    private static DimensionCheck dimension(List<DimensionCheck> rows, String id, String path) {
        return rows.stream().filter(d -> d.scenarioId().equals(id) && d.profilePath().equals(path)).findFirst().orElseThrow();
    }
    private static java.util.Set<String> unexercised(List<DimensionCheck> rows) {
        var exercised = rows.stream().flatMap(d -> d.factPaths().stream()).collect(java.util.stream.Collectors.toSet());
        return CatalogFactPathRegressionCases.PROBES.stream().map(CatalogImpactCases.Probe::factPath).filter(p -> !exercised.contains(p)).collect(java.util.stream.Collectors.toSet());
    }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static List<Dimension> dimensions() {
        var rows = new ArrayList<Dimension>();
        CatalogProfileImpactCoverageService.DIMENSIONS.stream().filter(d -> !d.profilePath().equals("security.auditability")).forEach(d -> rows.add(
                new Dimension(d.profilePath(), d.ruleProfilePath(), d.boundary(), d.permittedFactPaths(), List.of())));
        for (var path : CatalogAuditabilityRegressionService.CHECKED_PATHS) rows.add(new Dimension(path, path, Boundary.AUDITABILITY, List.of(),
                path.endsWith("minimumRetentionDays") ? List.of(Criterion.AUDIT_LOG_RETENTION) : List.of(Criterion.values())));
        if (rows.size() != 34 || rows.stream().map(Dimension::profilePath).distinct().count() != 34)
            throw new IllegalStateException("Review profile v6 coverage manifest");
        return rows.stream().sorted(Comparator.comparing(Dimension::profilePath)).toList();
    }
}

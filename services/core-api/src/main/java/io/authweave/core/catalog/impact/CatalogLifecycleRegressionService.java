package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.Outcome;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.PatternId;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.Status;
import io.authweave.core.evaluation.ProvisioningLifecycleV2Evaluator;
import io.authweave.core.evaluation.ProvisioningLifecycleV2Evaluator.*;

/** Fresh design regression, not candidate impact, provider operations or a durable verification receipt. */
@Service
public final class CatalogLifecycleRegressionService {
    public static final String POLICY_VERSION = "catalog-provisioning-lifecycle-regression-1";
    public record Scope(PatternId patternId, GroupStrategy groupStrategy, List<ConditionId> conditions) { public Scope { conditions = List.copyOf(conditions); } }
    public static final List<Scope> SCOPES = Stream.of(PatternId.values()).flatMap(p -> Stream.of(GroupStrategy.values())
            .map(g -> new Scope(p, g, ProvisioningLifecycleV2Evaluator.conditions(p, g)))).toList();
    public static final String DEFINITIONS_SHA256 = CatalogDraftCanonicalizer.sha256(List.of(ProvisioningLifecycleV2Evaluator.POLICY_VERSION,
            List.of(PatternId.values()), SCOPES, List.of(ConditionId.values()), ProvisioningLifecycleV2Evaluator.OFFBOARDING_REFERENCES,
            ProvisioningLifecycleEvaluator.CHECKED_PATHS, ProvisioningLifecycleEvaluator.DEFERRED_BOUNDARIES));
    private final CatalogLifecycleRegressionCases cases;
    public CatalogLifecycleRegressionService(CatalogLifecycleRegressionCases cases) { this.cases = cases; }
    public record Row(CatalogLifecycleRegressionCases.Definition input, Instant evaluatedAt, Analysis analysis) { }
    public record Report(Instant evaluatedAt, String scenarioSetSha256, List<Row> rows) {
        public Report { Objects.requireNonNull(evaluatedAt); rows = List.copyOf(rows); }
    }
    public record Results(int conditionallyMatches, int conditionallyDoesNotMatch, int needsInformation) {
        public Results { total(CatalogLifecycleRegressionCases.COUNT, conditionallyMatches, conditionallyDoesNotMatch, needsInformation); }
    }
    public record Counts(int conditionallySatisfied, int conditionallyNotSatisfied, int unknown, int notApplied) {
        public Counts { if (Stream.of(conditionallySatisfied, conditionallyNotSatisfied, unknown, notApplied).anyMatch(v -> v < 0 || v > 38304)) throw new IllegalArgumentException("Invalid lifecycle counts"); }
        public long total() { return (long) conditionallySatisfied + conditionallyNotSatisfied + unknown + notApplied; }
    }
    public record ReasonCount(Reason reasonCode, int checks) {
        public ReasonCount { Objects.requireNonNull(reasonCode); if (checks < 0 || checks > 38304) throw new IllegalArgumentException("Invalid lifecycle reason count"); }
    }
    public record Check(Instant evaluatedAt, String scenarioSetSha256, String auditabilityScenarioSetSha256, String analysisSha256,
            Results results, Counts requirementOutcomes, Counts designOutcomes, Counts conditionOutcomes,
            Counts offboardingOutcomes, Counts groupRemovalOutcomes, int checkedConditionChecks, List<ReasonCount> reasons) {
        public Check {
            Objects.requireNonNull(evaluatedAt); Objects.requireNonNull(results); Objects.requireNonNull(requirementOutcomes); Objects.requireNonNull(designOutcomes);
            Objects.requireNonNull(conditionOutcomes); Objects.requireNonNull(offboardingOutcomes); Objects.requireNonNull(groupRemovalOutcomes); reasons = List.copyOf(reasons);
            if (!hash(scenarioSetSha256) || !hash(auditabilityScenarioSetSha256) || !hash(analysisSha256)
                    || checkedConditionChecks < 14112 || checkedConditionChecks > 30240 || requirementOutcomes.total() != 6048 || designOutcomes.total() != 2016
                    || conditionOutcomes.total() != checkedConditionChecks || offboardingOutcomes.total() != 6048 || groupRemovalOutcomes.total() != 1152
                    || !reasons.stream().map(ReasonCount::reasonCode).toList().equals(List.of(Reason.values()))
                    || reasons.stream().mapToLong(ReasonCount::checks).sum() != 8064L + checkedConditionChecks)
                throw new IllegalArgumentException("Incomplete lifecycle regression summary");
        }
        @JsonProperty public String scope() { return "SYNTHETIC_PROVISIONING_LIFECYCLE_V2_REGRESSION"; }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public String lifecyclePolicyVersion() { return ProvisioningLifecycleV2Evaluator.POLICY_VERSION; }
        @JsonProperty public String definitionsSha256() { return DEFINITIONS_SHA256; }
        @JsonProperty public String scenarioSetVersion() { return CatalogLifecycleRegressionCases.VERSION; }
        @JsonProperty public String baseScenarioSetVersion() { return CatalogScopedProfileCases.VERSION; }
        @JsonProperty public String baseScenarioSetSha256() { return CatalogLifecycleRegressionCases.BASE_SHA256; }
        @JsonProperty public String auditabilityScenarioSetVersion() { return CatalogAuditabilityRegressionCases.VERSION; }
        @JsonProperty public int profileSchemaVersion() { return CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_VERSION; }
        @JsonProperty public String profileSchemaSha256() { return CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_SHA256; }
        @JsonProperty public String analysisBasis() { return "SYNTHETIC_REQUIREMENTS_AND_UNVERIFIED_DESIGN_DECLARATIONS"; }
        @JsonProperty public int declaredProfiles() { return 4; }
        @JsonProperty public int checkedCases() { return CatalogLifecycleRegressionCases.COUNT; }
        @JsonProperty public int checkedRequirementChecks() { return 6048; }
        @JsonProperty public int checkedDesignChecks() { return 2016; }
        @JsonProperty public int checkedOffboardingChecks() { return 6048; }
        @JsonProperty public int checkedGroupRemovalChecks() { return 1152; }
        @JsonProperty public List<CatalogLifecycleRegressionCases.RequirementVariant> requirementVariants() { return List.of(CatalogLifecycleRegressionCases.RequirementVariant.values()); }
        @JsonProperty public List<CatalogLifecycleRegressionCases.DeclarationVariant> declarationVariants() { return List.of(CatalogLifecycleRegressionCases.DeclarationVariant.values()); }
        @JsonProperty public List<PatternId> patternIds() { return List.of(PatternId.values()); }
        @JsonProperty public List<GroupStrategy> groupStrategies() { return List.of(GroupStrategy.values()); }
        @JsonProperty public List<ConditionId> conditionIds() { return List.of(ConditionId.values()); }
        @JsonProperty public List<String> checkedPaths() { return ProvisioningLifecycleEvaluator.CHECKED_PATHS; }
        @JsonProperty public List<String> deferredBoundaries() { return ProvisioningLifecycleEvaluator.DEFERRED_BOUNDARIES; }
        @JsonProperty public boolean candidateChangesEvaluated() { return false; }
        @JsonProperty public boolean coverageComplete() { return false; }
        @JsonProperty public boolean configurationVerified() { return false; }
        @JsonProperty public boolean providerCompatibilityVerified() { return false; }
        @JsonProperty public boolean lifecycleVerified() { return false; }
        @JsonProperty public boolean groupSynchronizationVerified() { return false; }
        @JsonProperty public boolean accessRevocationVerified() { return false; }
        @JsonProperty public boolean sourceVerificationPerformed() { return false; }
        @JsonProperty public boolean storedReportVerified() { return false; }
        @JsonProperty public boolean baselineVerified() { return false; }
        @JsonProperty public boolean approvalGranted() { return false; }
        @JsonProperty public boolean publicationReady() { return false; }
        @JsonProperty public boolean evaluationReady() { return false; }
        @JsonProperty public boolean recommendationReady() { return false; }
        @JsonProperty public boolean writesPerformed() { return false; }
    }
    public Report analyzeAt(Instant at) {
        Objects.requireNonNull(at);
        var rows = cases.definitions().stream().map(input -> {
            var actual = ProvisioningLifecycleV2Evaluator.evaluate(input.requirements(), input.patternId(), input.groupStrategy(), input.declarations());
            var expected = CatalogLifecycleRegressionCases.expected(input);
            if (!actual.requirementChecks().equals(expected.requirementChecks()) || !actual.designChecks().equals(expected.designChecks())
                    || !actual.conditionChecks().equals(expected.conditionChecks()) || actual.status() != expected.status())
                throw new IllegalStateException("Lifecycle design policy drift: review explicit expectations");
            return new Row(input, at, actual);
        }).toList();
        return new Report(at, cases.sha256(), rows);
    }
    public Check inspectAt(Instant at) { return summarize(analyzeAt(at)); }
    public Check summarize(Report report) {
        if (!report.equals(analyzeAt(report.evaluatedAt()))) throw new IllegalArgumentException("Use the complete exact-bound lifecycle regression");
        var analyses = report.rows().stream().map(Row::analysis).toList();
        var requirements = analyses.stream().flatMap(a -> a.requirementChecks().stream()).toList();
        var designs = analyses.stream().flatMap(a -> a.designChecks().stream()).toList();
        var conditions = analyses.stream().flatMap(a -> a.conditionChecks().stream()).toList();
        var reasons = Stream.of(Reason.values()).map(reason -> new ReasonCount(reason, (int) (requirements.stream().filter(c -> c.reasonCode() == reason).count()
                + designs.stream().filter(c -> c.reasonCode() == reason).count() + conditions.stream().filter(c -> c.reasonCode() == reason).count()))).toList();
        return new Check(report.evaluatedAt(), report.scenarioSetSha256(), cases.auditabilityScenarioSetSha256(), CatalogDraftCanonicalizer.sha256(report),
                new Results((int) analyses.stream().filter(a -> a.status() == Status.CONDITIONALLY_MATCHES).count(), (int) analyses.stream().filter(a -> a.status() == Status.CONDITIONALLY_DOES_NOT_MATCH).count(), (int) analyses.stream().filter(a -> a.status() == Status.NEEDS_INFORMATION).count()),
                counts(requirements.stream().map(RequirementCheck::outcome).toList()), counts(designs.stream().map(DesignCheck::outcome).toList()), counts(conditions.stream().map(ConditionCheck::outcome).toList()),
                counts(conditions.stream().filter(c -> CatalogLifecycleRegressionCases.OFFBOARDING.contains(c.conditionId())).map(ConditionCheck::outcome).toList()),
                counts(conditions.stream().filter(c -> c.conditionId() == ConditionId.GROUP_REMOVAL_AND_ACCESS_RECHECK).map(ConditionCheck::outcome).toList()), conditions.size(), reasons);
    }
    private static Counts counts(List<Outcome> outcomes) { return new Counts(count(outcomes, Outcome.CONDITIONALLY_SATISFIED), count(outcomes, Outcome.CONDITIONALLY_NOT_SATISFIED), count(outcomes, Outcome.UNKNOWN), count(outcomes, Outcome.NOT_APPLIED)); }
    private static int count(List<Outcome> outcomes, Outcome outcome) { return (int) outcomes.stream().filter(value -> value == outcome).count(); }
    private static void total(int expected, int... values) { if (java.util.Arrays.stream(values).anyMatch(v -> v < 0) || java.util.Arrays.stream(values).mapToLong(v -> v).sum() != expected) throw new IllegalArgumentException("Invalid bounded lifecycle totals"); }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
}

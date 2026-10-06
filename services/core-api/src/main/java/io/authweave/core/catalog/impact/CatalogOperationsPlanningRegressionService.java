package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import io.authweave.core.assessment.domain.profile.OperationalConstraints;
import io.authweave.core.assessment.domain.profile.UsagePlanning;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.evaluation.OperationsPlanningEvaluator;
import io.authweave.core.evaluation.OperationsPlanningPreflight;
import io.authweave.core.evaluation.UsagePlanningPreflight.Status;
import static io.authweave.core.evaluation.OperationsPlanningEvaluator.*;

/** Fresh synthetic input regression, not candidate impact, prices, verified operations or publication coverage. */
@Service
public final class CatalogOperationsPlanningRegressionService {
    public static final String POLICY_VERSION = "catalog-operations-planning-regression-1";
    public static final UUID WORKSPACE = UUID.fromString("60000000-0000-4000-8000-000000000001");
    public static UUID assessmentId(int index) { return new UUID(0x8000000000004000L, 0x8000000000000000L + index + 1); }
    public static final String DEFINITIONS_SHA256 = CatalogDraftCanonicalizer.sha256(OperationsPlanningEvaluator.evaluate(WORKSPACE,
            assessmentId(0), 0, OperationalConstraints.unknown(), Instant.EPOCH));
    private final CatalogOperationsPlanningCases cases;
    public CatalogOperationsPlanningRegressionService(CatalogOperationsPlanningCases cases) { this.cases = cases; }
    public record Row(CatalogOperationsPlanningCases.Definition input, OperationsPlanningPreflight analysis) { }
    public record Analysis(Instant evaluatedAt, String scenarioSetSha256, List<Row> rows) {
        public Analysis { Objects.requireNonNull(evaluatedAt); rows = List.copyOf(rows); }
    }
    public record InputCounts(int inputsRecorded, int needsInformation) {
        public InputCounts { total(CatalogOperationsPlanningCases.COUNT, inputsRecorded, needsInformation); }
    }
    public record HostingCounts(int preferenceAligned, int preferenceDiffers, int noPreference, int preferenceUnknown) {
        public HostingCounts { total(CatalogOperationsPlanningCases.COUNT * 2, preferenceAligned, preferenceDiffers, noPreference, preferenceUnknown); }
    }
    public record SupportCounts(int integrationSupportPlanNeeded, int operatorSupportPlanNeeded, int responsibilityPlanNeeded, int supportCapacityUndefined) {
        public SupportCounts { total(CatalogOperationsPlanningCases.COUNT * 2, integrationSupportPlanNeeded, operatorSupportPlanNeeded, responsibilityPlanNeeded, supportCapacityUndefined); }
    }
    public record BudgetCounts(int costModelNeeded, int budgetScopeUndefined) {
        public BudgetCounts { total(CatalogOperationsPlanningCases.COUNT * 2, costModelNeeded, budgetScopeUndefined); }
    }
    public record Check(Instant evaluatedAt, String scenarioSetSha256, String auditabilityScenarioSetSha256, String analysisSha256,
            InputCounts inputResults, InputCounts usageResults, HostingCounts hosting, SupportCounts support, BudgetCounts budget,
            int recordedMetricChecks, int missingInputChecks) {
        public Check {
            Objects.requireNonNull(evaluatedAt); Objects.requireNonNull(inputResults); Objects.requireNonNull(usageResults);
            Objects.requireNonNull(hosting); Objects.requireNonNull(support); Objects.requireNonNull(budget);
            if (!hash(scenarioSetSha256) || !hash(auditabilityScenarioSetSha256) || !hash(analysisSha256) || recordedMetricChecks < 0 || recordedMetricChecks > 560
                    || missingInputChecks < 0 || missingInputChecks > 1400 || inputResults.inputsRecorded() > usageResults.inputsRecorded())
                throw new IllegalArgumentException("Invalid operations regression summary");
        }
        @JsonProperty public String scope() { return "SYNTHETIC_OPERATIONS_PLANNING_REGRESSION"; }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public String operationsPolicyVersion() { return OperationsPlanningEvaluator.POLICY_VERSION; }
        @JsonProperty public String definitionsSha256() { return DEFINITIONS_SHA256; }
        @JsonProperty public String scenarioSetVersion() { return CatalogOperationsPlanningCases.VERSION; }
        @JsonProperty public String baseScenarioSetVersion() { return CatalogScopedProfileCases.VERSION; }
        @JsonProperty public String baseScenarioSetSha256() { return CatalogOperationsPlanningCases.BASE_SHA256; }
        @JsonProperty public String auditabilityScenarioSetVersion() { return CatalogAuditabilityRegressionCases.VERSION; }
        @JsonProperty public int profileSchemaVersion() { return CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_VERSION; }
        @JsonProperty public String profileSchemaSha256() { return CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_SHA256; }
        @JsonProperty public String analysisBasis() { return "UNVERIFIED_SYNTHETIC_INPUTS_AND_GENERIC_RESPONSIBILITIES"; }
        @JsonProperty public int declaredProfiles() { return CatalogScopedProfileCases.COUNT; }
        @JsonProperty public int checkedCases() { return CatalogOperationsPlanningCases.COUNT; }
        @JsonProperty public int checkedOptions() { return CatalogOperationsPlanningCases.COUNT * 2; }
        @JsonProperty public List<CatalogOperationsPlanningCases.PreferenceVariant> preferenceVariants() { return List.of(CatalogOperationsPlanningCases.PreferenceVariant.values()); }
        @JsonProperty public List<CatalogOperationsPlanningCases.UsageVariant> usageVariants() { return List.of(CatalogOperationsPlanningCases.UsageVariant.values()); }
        @JsonProperty public List<OptionId> optionIds() { return List.of(OptionId.values()); }
        @JsonProperty public List<UsagePlanning.Metric> usageMetrics() { return List.of(UsagePlanning.Metric.values()); }
        @JsonProperty public List<String> checkedPaths() { return OperationsPlanningEvaluator.CHECKED_PATHS; }
        @JsonProperty public List<String> deferredBoundaries() { return OperationsPlanningEvaluator.DEFERRED_BOUNDARIES; }
        @JsonProperty public boolean candidateChangesEvaluated() { return false; }
        @JsonProperty public boolean coverageComplete() { return false; }
        @JsonProperty public boolean providerEligibilityEvaluated() { return false; }
        @JsonProperty public boolean deploymentCompatibilityVerified() { return false; }
        @JsonProperty public boolean operationalReadinessVerified() { return false; }
        @JsonProperty public boolean pricingEvaluated() { return false; }
        @JsonProperty public boolean costModelEvaluated() { return false; }
        @JsonProperty public boolean budgetFitVerified() { return false; }
        @JsonProperty public boolean configurationVerified() { return false; }
        @JsonProperty public boolean sourceVerificationPerformed() { return false; }
        @JsonProperty public boolean storedReportVerified() { return false; }
        @JsonProperty public boolean baselineVerified() { return false; }
        @JsonProperty public boolean approvalGranted() { return false; }
        @JsonProperty public boolean publicationReady() { return false; }
        @JsonProperty public boolean evaluationReady() { return false; }
        @JsonProperty public boolean recommendationReady() { return false; }
        @JsonProperty public boolean writesPerformed() { return false; }
    }
    public Analysis analyzeAt(Instant at) {
        Objects.requireNonNull(at);
        var rows = IntStream.range(0, cases.definitions().size()).mapToObj(index -> {
            var input = cases.definitions().get(index);
            var actual = OperationsPlanningEvaluator.evaluate(WORKSPACE, assessmentId(index), 0, input.operations(), at);
            var expected = CatalogOperationsPlanningCases.expected(input);
            if (!actual.inputs().equals(input.inputs()) || actual.status() != expected.status() || !actual.missingPaths().equals(expected.missingPaths())
                    || !actual.usageInputs().equals(expected.usageInputs()) || !actual.options().stream().map(OperationsPlanningPreflight.Option::optionId).toList().equals(List.of(OptionId.values()))
                    || !actual.options().stream().map(OperationsPlanningPreflight.Option::hostingAlignment).toList().equals(expected.alignments())
                    || !actual.options().stream().map(OperationsPlanningPreflight.Option::supportPlanning).toList().equals(expected.support())
                    || !actual.options().stream().map(OperationsPlanningPreflight.Option::budgetPlanning).toList().equals(expected.budget()))
                throw new IllegalStateException("Operations planning policy drift: review explicit scenario expectations");
            return new Row(input, actual);
        }).toList();
        return new Analysis(at, cases.sha256(), rows);
    }
    public Check inspectAt(Instant at) { return summarize(analyzeAt(at)); }
    public Check summarize(Analysis report) {
        if (!report.equals(analyzeAt(report.evaluatedAt()))) throw new IllegalArgumentException("Use the complete exact-bound operations regression");
        var analyses = report.rows().stream().map(Row::analysis).toList();
        var options = analyses.stream().flatMap(a -> a.options().stream()).toList();
        return new Check(report.evaluatedAt(), report.scenarioSetSha256(), cases.auditabilityScenarioSetSha256(), CatalogDraftCanonicalizer.sha256(report),
            new InputCounts((int) analyses.stream().filter(a -> a.status() == Status.INPUTS_RECORDED).count(), (int) analyses.stream().filter(a -> a.status() == Status.NEEDS_INFORMATION).count()),
            new InputCounts((int) analyses.stream().filter(a -> a.usageInputs().status() == Status.INPUTS_RECORDED).count(), (int) analyses.stream().filter(a -> a.usageInputs().status() == Status.NEEDS_INFORMATION).count()),
            new HostingCounts(alignment(options, Alignment.PREFERENCE_ALIGNED), alignment(options, Alignment.PREFERENCE_DIFFERS), alignment(options, Alignment.NO_PREFERENCE), alignment(options, Alignment.PREFERENCE_UNKNOWN)),
            new SupportCounts(support(options, SupportPlanning.INTEGRATION_SUPPORT_PLAN_NEEDED), support(options, SupportPlanning.OPERATOR_SUPPORT_PLAN_NEEDED), support(options, SupportPlanning.RESPONSIBILITY_PLAN_NEEDED), support(options, SupportPlanning.SUPPORT_CAPACITY_UNDEFINED)),
            new BudgetCounts((int) options.stream().filter(o -> o.budgetPlanning() == BudgetPlanning.COST_MODEL_NEEDED).count(), (int) options.stream().filter(o -> o.budgetPlanning() == BudgetPlanning.BUDGET_SCOPE_UNDEFINED).count()),
            analyses.stream().mapToInt(a -> a.usageInputs().recordedMetrics().size()).sum(), analyses.stream().mapToInt(a -> a.missingPaths().size()).sum());
    }
    private static int alignment(List<OperationsPlanningPreflight.Option> options, Alignment value) { return (int) options.stream().filter(o -> o.hostingAlignment() == value).count(); }
    private static int support(List<OperationsPlanningPreflight.Option> options, SupportPlanning value) { return (int) options.stream().filter(o -> o.supportPlanning() == value).count(); }
    private static void total(int expected, int... values) { if (java.util.Arrays.stream(values).anyMatch(v -> v < 0) || java.util.Arrays.stream(values).mapToLong(v -> v).sum() != expected) throw new IllegalArgumentException("Incomplete bounded regression counts"); }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
}

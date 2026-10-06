package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import io.authweave.core.assessment.domain.profile.OperationalConstraints;
import io.authweave.core.assessment.domain.profile.OperationalConstraints.*;
import io.authweave.core.assessment.domain.profile.UsagePlanning;
import io.authweave.core.evaluation.OperationsPlanningEvaluator.*;
import static org.junit.jupiter.api.Assertions.*;

class OperationsPlanningEvaluatorTests {
    private static final UUID WORKSPACE = UUID.fromString("60000000-0000-4000-8000-000000000001"), ASSESSMENT = UUID.fromString("80000000-0000-4000-8000-000000000001");
    private static final Instant AT = Instant.parse("2026-09-12T12:00:00Z");
    private OperationsPlanningPreflight evaluate(OperationalConstraints ops) { return OperationsPlanningEvaluator.evaluate(WORKSPACE, ASSESSMENT, 7, ops, AT); }
    private UsagePlanning complete(UsagePlanning.Basis basis, long value) {
        var values = new EnumMap<UsagePlanning.Metric, UsagePlanning.Quantity>(UsagePlanning.Metric.class);
        for (var metric : UsagePlanning.Metric.values()) values.put(metric, new UsagePlanning.Quantity(basis, value));
        return new UsagePlanning("Synthetic pilot month", basis == UsagePlanning.Basis.ASSUMED ? List.of("Synthetic planning assumption") : List.of(), values);
    }
    @Test void all384SavedEnumCombinationsKeepBothAlternativesAndNeverTreatPreferenceAsExclusion() {
        int count = 0;
        for (var hosting : HostingPreference.values()) for (var target : DeploymentTarget.values()) for (var expertise : IdentityExpertise.values()) for (var budget : BudgetSensitivity.values()) {
            var result = evaluate(new OperationalConstraints(hosting, target, expertise, budget, complete(UsagePlanning.Basis.OBSERVED, 0)));
            assertEquals(List.of(OptionId.values()), result.options().stream().map(OperationsPlanningPreflight.Option::optionId).toList());
            for (var option : result.options()) {
                boolean managed = option.optionId() == OptionId.MANAGED_IDENTITY_SERVICE;
                var alignment = hosting == HostingPreference.UNKNOWN ? Alignment.PREFERENCE_UNKNOWN : hosting == HostingPreference.NO_PREFERENCE ? Alignment.NO_PREFERENCE
                        : (hosting == HostingPreference.MANAGED) == managed ? Alignment.PREFERENCE_ALIGNED : Alignment.PREFERENCE_DIFFERS;
                assertEquals(alignment, option.hostingAlignment());
                assertEquals(expertise == IdentityExpertise.UNKNOWN ? SupportPlanning.SUPPORT_CAPACITY_UNDEFINED : expertise != IdentityExpertise.LIMITED ? SupportPlanning.RESPONSIBILITY_PLAN_NEEDED
                        : managed ? SupportPlanning.INTEGRATION_SUPPORT_PLAN_NEEDED : SupportPlanning.OPERATOR_SUPPORT_PLAN_NEEDED, option.supportPlanning());
                assertEquals(budget == BudgetSensitivity.UNKNOWN ? BudgetPlanning.BUDGET_SCOPE_UNDEFINED : BudgetPlanning.COST_MODEL_NEEDED, option.budgetPlanning());
            }
            assertEquals(hosting == HostingPreference.UNKNOWN || target == DeploymentTarget.UNDECIDED || expertise == IdentityExpertise.UNKNOWN || budget == BudgetSensitivity.UNKNOWN
                    ? UsagePlanningPreflight.Status.NEEDS_INFORMATION : UsagePlanningPreflight.Status.INPUTS_RECORDED, result.status());
            assertEquals(List.of(UsagePlanning.Metric.values()), result.usageInputs().recordedMetrics()); count++;
        }
        assertEquals(384, count);
    }
    @Test void unknownInputsStayUnknownAndDefaultUsageIsNotZero() {
        var result = evaluate(OperationalConstraints.unknown());
        assertEquals(UsagePlanningPreflight.Status.NEEDS_INFORMATION, result.status());
        assertEquals(9, result.missingPaths().size()); assertTrue(result.usageInputs().recordedMetrics().isEmpty());
        assertEquals(5, result.usageInputs().missingPaths().size()); assertEquals(2, result.options().size());
        assertTrue(result.options().stream().allMatch(o -> o.hostingAlignment() == Alignment.PREFERENCE_UNKNOWN));
    }
    @Test void recordedZerosHugeVolumesAndObservedAssertionsCannotBecomePricingOrVerifiedReadiness() {
        for (var basis : UsagePlanning.Basis.values()) for (long value : List.of(0L, 9007199254740991L)) {
            var ops = new OperationalConstraints(HostingPreference.SELF_HOSTED, DeploymentTarget.ON_PREMISES, IdentityExpertise.ADVANCED, BudgetSensitivity.HIGH, complete(basis, value));
            var result = evaluate(ops); assertEquals(UsagePlanningPreflight.Status.INPUTS_RECORDED, result.status());
            var json = JsonMapper.builder().build().valueToTree(result);
            for (String flag : List.of("providerEligibilityEvaluated", "deploymentCompatibilityVerified", "operationalReadinessVerified", "pricingEvaluated", "costModelEvaluated", "budgetFitVerified",
                    "configurationVerified", "recommendationReady", "publicationReady", "writesPerformed")) assertFalse(json.get(flag).asBoolean(), flag);
            for (String field : List.of("winner", "score", "estimatedCost", "budgetLimit", "providerId", "eligible")) assertFalse(json.has(field), field);
            assertFalse(json.toString().contains("Synthetic pilot month")); assertFalse(json.toString().contains("Synthetic planning assumption"));
            assertEquals(BudgetPlanning.COST_MODEL_NEEDED, result.options().get(1).budgetPlanning());
        }
    }
    @Test void assumedQuantitiesNeedAnAssumptionButObservedDoesNotMeanVerified() {
        var planning = new UsagePlanning("Month", List.of(), Map.of(UsagePlanning.Metric.MONTHLY_ACTIVE_USERS, new UsagePlanning.Quantity(UsagePlanning.Basis.ASSUMED, 0L)));
        var result = evaluate(new OperationalConstraints(HostingPreference.MANAGED, DeploymentTarget.AZURE, IdentityExpertise.LIMITED, BudgetSensitivity.LOW, planning));
        assertEquals(List.of(UsagePlanning.Metric.MONTHLY_ACTIVE_USERS), result.usageInputs().recordedMetrics());
        assertEquals("operations.usagePlanning.assumptions", result.usageInputs().missingPaths().getLast());
        assertFalse(result.usageInputs().missingPaths().contains("operations.usagePlanning.volumes.MONTHLY_ACTIVE_USERS"));
        assertEquals(SupportPlanning.INTEGRATION_SUPPORT_PLAN_NEEDED, result.options().getFirst().supportPlanning());
        assertEquals(SupportPlanning.OPERATOR_SUPPORT_PLAN_NEEDED, result.options().getLast().supportPlanning());
    }
    @Test void deploymentTargetDoesNotDeclareIdentityLocationOrChangeGenericOptionRules() {
        var original = evaluate(new OperationalConstraints(HostingPreference.NO_PREFERENCE, DeploymentTarget.AZURE, IdentityExpertise.MODERATE, BudgetSensitivity.MODERATE));
        for (var target : DeploymentTarget.values()) {
            var next = evaluate(new OperationalConstraints(HostingPreference.NO_PREFERENCE, target, IdentityExpertise.MODERATE, BudgetSensitivity.MODERATE));
            assertEquals(original.options(), next.options()); assertFalse(next.deploymentCompatibilityVerified());
        }
    }
    @Test void usageScopePresenceMatchesTheExistingJavaWhitespacePolicyWithoutChangingIt() {
        for (String scope : List.of("", "\u2003", "\u00a0", "\ufeff")) {
            var full = complete(UsagePlanning.Basis.OBSERVED, 0);
            var result = evaluate(new OperationalConstraints(HostingPreference.MANAGED, DeploymentTarget.AZURE, IdentityExpertise.MODERATE, BudgetSensitivity.MODERATE,
                    new UsagePlanning(scope, full.assumptions(), full.volumes())));
            assertEquals(scope.isBlank(), result.usageInputs().missingPaths().contains("operations.usagePlanning.scopeDescription"));
        }
    }
    @Test void immutableOutputRejectsMissingReorderedSubstitutedOptionsUnsafeVersionsAndUsageInventory() {
        var result = evaluate(OperationalConstraints.unknown());
        assertThrows(UnsupportedOperationException.class, () -> result.options().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.options().getFirst().tradeoffs().clear());
        for (var options : List.of(List.<OperationsPlanningPreflight.Option>of(), List.of(result.options().getLast(), result.options().getFirst()), List.of(result.options().getFirst(), result.options().getFirst())))
            assertThrows(IllegalArgumentException.class, () -> new OperationsPlanningPreflight(WORKSPACE, ASSESSMENT, 7, AT, result.inputs(), result.usageInputs(), options));
        for (long version : List.of(-1L, Long.MAX_VALUE)) assertThrows(IllegalArgumentException.class,
                () -> OperationsPlanningEvaluator.evaluate(WORKSPACE, ASSESSMENT, version, OperationalConstraints.unknown(), AT));
        assertThrows(IllegalArgumentException.class, () -> new OperationsPlanningPreflight.UsageInputs(UsagePlanningPreflight.Status.INPUTS_RECORDED, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new OperationsPlanningPreflight.UsageInputs(UsagePlanningPreflight.Status.NEEDS_INFORMATION, List.of(UsagePlanning.Metric.MONTHLY_ACTIVE_USERS), result.usageInputs().missingPaths()));
        assertThrows(IllegalArgumentException.class, () -> new OperationsPlanningPreflight.UsageInputs(UsagePlanningPreflight.Status.NEEDS_INFORMATION, List.of(), result.usageInputs().missingPaths().reversed()));
        assertThrows(NullPointerException.class, () -> OperationsPlanningEvaluator.evaluate(WORKSPACE, ASSESSMENT, 0, null, AT));
    }
}

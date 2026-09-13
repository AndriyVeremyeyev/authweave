package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import io.authweave.core.assessment.domain.profile.UsagePlanning;

public final class UsagePlanningEvaluator {
    public static final String POLICY_VERSION = "usage-planning-preflight-1";
    private UsagePlanningEvaluator() { }

    public static UsagePlanningPreflight evaluate(UUID workspaceId, UUID assessmentId, long version,
            UsagePlanning planning, Instant at) {
        var missing = new ArrayList<String>();
        if (planning.scopeDescription().isBlank()) missing.add("operations.usagePlanning.scopeDescription");
        var checks = Arrays.stream(UsagePlanning.Metric.values()).map(metric -> {
            var value = planning.volumes().get(metric);
            if (value == null) missing.add("operations.usagePlanning.volumes." + metric.name());
            return new UsagePlanningPreflight.QuantityCheck(metric, metric.unit(), metric.definition(),
                    value == null ? UsagePlanningPreflight.QuantityStatus.UNKNOWN
                            : UsagePlanningPreflight.QuantityStatus.valueOf(value.basis().name()), value);
        }).toList();
        if (planning.assumptions().isEmpty() && planning.volumes().values().stream()
                .anyMatch(value -> value.basis() == UsagePlanning.Basis.ASSUMED)) {
            missing.add("operations.usagePlanning.assumptions");
        }
        return new UsagePlanningPreflight(workspaceId, assessmentId, version, POLICY_VERSION, at,
                "USAGE_PLANNING_PREFLIGHT", false, false, missing.isEmpty()
                        ? UsagePlanningPreflight.Status.INPUTS_RECORDED : UsagePlanningPreflight.Status.NEEDS_INFORMATION,
                planning.scopeDescription(), planning.assumptions(), missing, checks,
                "These are owner-supplied inputs, not verified measurements or a cost estimate. Provider billing units, dated prices, paid features, additional environments and operational costs still need a separate model. No budget limit or free tier is inferred.");
    }
}

package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.authweave.core.assessment.domain.profile.UsagePlanning;

/** Input inventory only: no provider price lookup, billable-unit mapping, scoring or cost estimate. */
public record UsagePlanningPreflight(UUID workspaceId, UUID assessmentId, long assessmentVersion, String policyVersion,
        Instant evaluatedAt, String scope, boolean pricingEvaluated, boolean recommendationReady, Status status,
        String scopeDescription, List<String> assumptions, List<String> missingPaths,
        List<QuantityCheck> quantityChecks, String explanation) {
    public UsagePlanningPreflight {
        assumptions = List.copyOf(assumptions);
        missingPaths = List.copyOf(missingPaths);
        quantityChecks = List.copyOf(quantityChecks);
    }
    public enum Status { NEEDS_INFORMATION, INPUTS_RECORDED }
    public enum QuantityStatus { UNKNOWN, ASSUMED, OBSERVED }
    public record QuantityCheck(UsagePlanning.Metric metric, UsagePlanning.Unit unit, String definition,
            QuantityStatus status, UsagePlanning.Quantity input) { }
}

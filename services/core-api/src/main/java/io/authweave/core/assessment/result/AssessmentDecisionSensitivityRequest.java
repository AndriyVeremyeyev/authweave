package io.authweave.core.assessment.result;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** A transient comparison: no caller profile, catalog, policy, clock, result or write key. */
public record AssessmentDecisionSensitivityRequest(int schemaVersion, AssessmentDecisionResultRequest.Reference reference, JsonNode weights) {
    public AssessmentDecisionSensitivityRequest {
        if (schemaVersion != 1) throw new IllegalArgumentException("Invalid sensitivity version");
        Objects.requireNonNull(reference); weights = Objects.requireNonNull(weights).deepCopy();
        if (!weights.isObject() || weights.toString().length() > 4096) throw new IllegalArgumentException("Use bounded explicit weights");
    }
    @Override public JsonNode weights() { return weights.deepCopy(); }
}

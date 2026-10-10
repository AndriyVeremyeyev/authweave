package io.authweave.core.assessment.result;

import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.authweave.core.catalog.publication.PublishedCatalogSnapshot;
import tools.jackson.databind.JsonNode;

/** No transported profile, result, source verdicts, policy or caller clock. */
public record AssessmentDecisionResultRequest(int schemaVersion, UUID resultId, long expectedAssessmentVersion,
        PublishedCatalogSnapshot.Reference catalog, @JsonProperty(required = true) Reference previousResult,
        JsonNode weights, Confirmation confirmation) {
    public static final long MAX_VERSION = 9007199254740991L;
    public enum Confirmation { RECORD_DECISION_RESULT, REEVALUATE_DECISION_RESULT }
    public record Reference(UUID resultId, long version, String resultSha256) {
        public Reference {
            Objects.requireNonNull(resultId); digest(resultSha256);
            if (version < 1 || version > MAX_VERSION) throw new IllegalArgumentException("Invalid result version");
        }
    }
    public AssessmentDecisionResultRequest {
        if (schemaVersion != 1 || expectedAssessmentVersion < 0 || expectedAssessmentVersion > MAX_VERSION)
            throw new IllegalArgumentException("Invalid request version");
        Objects.requireNonNull(resultId); Objects.requireNonNull(catalog); Objects.requireNonNull(confirmation);
        weights = Objects.requireNonNull(weights).deepCopy();
        if (!weights.isObject() || weights.toString().length() > 4096
                || confirmation != (previousResult == null ? Confirmation.RECORD_DECISION_RESULT : Confirmation.REEVALUATE_DECISION_RESULT))
            throw new IllegalArgumentException("Use explicit weights and the matching result confirmation");
    }
    @Override public JsonNode weights() { return weights.deepCopy(); }
    public static void digest(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Use an exact SHA-256");
    }
}

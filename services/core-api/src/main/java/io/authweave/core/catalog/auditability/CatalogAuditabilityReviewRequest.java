package io.authweave.core.catalog.auditability;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion;
import io.authweave.core.catalog.draft.CatalogAuditabilityDraftValidator.Request;
import io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict;

/** Full exact untrusted candidate plus explicit manual observations, not an approval or a trusted sidecar. */
public record CatalogAuditabilityReviewRequest(int schemaVersion, UUID reviewId,
        String expectedBaseContentSha256, String expectedAuditabilityContentSha256, String expectedTargetSetSha256,
        Request candidate, List<Observation> observations, Confirmation confirmation) {
    public enum Confirmation { MANUAL_AUDITABILITY_SOURCE_REVIEW }
    public CatalogAuditabilityReviewRequest {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported auditability source-review version");
        Objects.requireNonNull(reviewId); Objects.requireNonNull(candidate); Objects.requireNonNull(confirmation);
        digest(expectedBaseContentSha256); digest(expectedAuditabilityContentSha256); digest(expectedTargetSetSha256);
        observations = List.copyOf(observations);
        if (observations.isEmpty() || observations.size() > 600) throw new IllegalArgumentException("Use one manual observation per recorded auditability fact");
    }
    public record Observation(String optionId, Criterion criterion, String expectedTargetSha256, Verdict verdict) {
        public Observation {
            identifier(optionId); Objects.requireNonNull(criterion); digest(expectedTargetSha256); Objects.requireNonNull(verdict);
        }
    }
    static void digest(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Use an exact SHA-256 digest");
    }
    static void identifier(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9.-]{0,99}")) throw new IllegalArgumentException("Invalid review version or option identifier");
    }
}

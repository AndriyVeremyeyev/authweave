package io.authweave.core.catalog.publication;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Small historical receipt; no source body, actor identity or mutable authority flag. */
public record CatalogBootstrapReview(UUID reviewId, String candidateSha256, String reviewSha256,
        String catalogVersion, int factCount, Counts counts, Instant recordedAt) {
    public record Counts(int supporting, int contradicting, int insufficient) { }
    @JsonProperty public String policyVersion() { return CatalogBootstrapReviewService.POLICY_VERSION; }
    @JsonProperty public String kind() { return "HUMAN_BOOTSTRAP_SOURCE_REVIEW"; }
    @JsonProperty public boolean sourceVerificationPerformed() { return false; }
    @JsonProperty public boolean approvalGranted() { return false; }
    @JsonProperty public boolean catalogWritesPerformed() { return false; }
    @JsonProperty public boolean factTrustChanged() { return false; }
}

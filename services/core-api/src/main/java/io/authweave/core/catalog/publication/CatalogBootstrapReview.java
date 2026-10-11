package io.authweave.core.catalog.publication;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Small historical receipt; no source body, actor identity or mutable authority flag. */
public record CatalogBootstrapReview(UUID reviewId, String candidateSha256, String reviewSha256,
        String catalogVersion, int factCount, Counts counts, Instant recordedAt, String policyVersion, String kind) {
    public CatalogBootstrapReview(UUID reviewId, String candidateSha256, String reviewSha256,
            String catalogVersion, int factCount, Counts counts, Instant recordedAt) {
        this(reviewId, candidateSha256, reviewSha256, catalogVersion, factCount, counts, recordedAt,
                CatalogBootstrapReviewService.POLICY_VERSION, "HUMAN_BOOTSTRAP_SOURCE_REVIEW");
    }
    public CatalogBootstrapReview {
        if (!(CatalogBootstrapReviewService.POLICY_VERSION.equals(policyVersion) && "HUMAN_BOOTSTRAP_SOURCE_REVIEW".equals(kind))
                && !(CatalogBootstrapReviewService.ASSISTANT_POLICY_VERSION.equals(policyVersion)
                    && "OWNER_APPROVED_ASSISTANT_SOURCE_ASSESSMENT".equals(kind)))
            throw new IllegalArgumentException("Invalid source-review provenance");
    }
    public record Counts(int supporting, int contradicting, int insufficient) { }
    @JsonProperty public boolean sourceVerificationPerformed() { return false; }
    @JsonProperty public boolean approvalGranted() { return false; }
    @JsonProperty public boolean catalogWritesPerformed() { return false; }
    @JsonProperty public boolean factTrustChanged() { return false; }
}

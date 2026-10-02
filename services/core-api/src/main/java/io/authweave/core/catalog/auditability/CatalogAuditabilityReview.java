package io.authweave.core.catalog.auditability;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Body-free historical human assertion. No actor, source body or derived provider authority. */
public record CatalogAuditabilityReview(UUID reviewId, String baseContentSha256, String auditabilityContentSha256,
        String targetSetSha256, String reviewSha256, String catalogVersion, String evidenceVersion,
        int optionCount, int factCount, Counts counts, Instant recordedAt) {
    public CatalogAuditabilityReview {
        Objects.requireNonNull(reviewId); Objects.requireNonNull(recordedAt); Objects.requireNonNull(counts);
        CatalogAuditabilityReviewRequest.digest(baseContentSha256); CatalogAuditabilityReviewRequest.digest(auditabilityContentSha256);
        CatalogAuditabilityReviewRequest.digest(targetSetSha256); CatalogAuditabilityReviewRequest.digest(reviewSha256);
        CatalogAuditabilityReviewRequest.identifier(catalogVersion); CatalogAuditabilityReviewRequest.identifier(evidenceVersion);
        if (optionCount < 1 || optionCount > 100 || factCount < 1 || factCount > optionCount * 6
                || factCount != counts.supporting() + counts.contradicting() + counts.insufficient())
            throw new IllegalArgumentException("Inconsistent auditability review receipt");
    }
    public record Counts(int supporting, int contradicting, int insufficient) {
        public Counts {
            if (supporting < 0 || contradicting < 0 || insufficient < 0
                    || supporting > 600 || contradicting > 600 || insufficient > 600
                    || supporting + contradicting + insufficient < 1 || supporting + contradicting + insufficient > 600)
                throw new IllegalArgumentException("Invalid manual auditability observation counts");
        }
    }
    @JsonProperty public String policyVersion() { return CatalogAuditabilityReviewService.POLICY_VERSION; }
    @JsonProperty public String kind() { return "HUMAN_AUDITABILITY_SOURCE_REVIEW"; }
    @JsonProperty public int requestSchemaVersion() { return 1; }
    @JsonProperty public boolean sourceReviewRecorded() { return true; }
    @JsonProperty public boolean sourceVerificationPerformed() { return false; }
    @JsonProperty public boolean factTrustChanged() { return false; }
    @JsonProperty public boolean candidateImpactPerformed() { return false; }
    @JsonProperty public boolean approvalGranted() { return false; }
    @JsonProperty public boolean catalogWritesPerformed() { return false; }
    @JsonProperty public boolean publicationReady() { return false; }
    @JsonProperty public boolean evaluationReady() { return false; }
    @JsonProperty public boolean recommendationReady() { return false; }
}

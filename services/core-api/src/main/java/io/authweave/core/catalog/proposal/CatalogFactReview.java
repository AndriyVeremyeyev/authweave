package io.authweave.core.catalog.proposal;

import java.time.Instant;
import java.util.UUID;

/** Immutable ledger receipt. Recording an assertion does not independently verify its source. */
public record CatalogFactReview(UUID reviewId, UUID proposalId, long proposalVersion,
        String proposalSha256, long reviewNumber, String optionId, String factPath,
        CatalogFactReviewRequest.Verdict verdict, Instant recordedAt,
        String kind, boolean sourceVerificationPerformed, boolean approvalGranted,
        boolean catalogWritesPerformed, boolean factTrustChanged) {
    static CatalogFactReview from(io.authweave.core.generated.jooq.tables.records.CatalogFactReviewsRecord row) {
        return new CatalogFactReview(row.getId(), row.getProposalId(), row.getProposalVersion(), row.getProposalSha256(),
                row.getReviewNumber(), row.getOptionId(), row.getFactPath(),
                CatalogFactReviewRequest.Verdict.valueOf(row.getVerdict()), row.getRecordedAt().toInstant(),
                "HUMAN_SOURCE_REVIEW_OBSERVATION", false, false, false, false);
    }
}

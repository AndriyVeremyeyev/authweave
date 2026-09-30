package io.authweave.core.catalog.proposal;

import java.time.Instant;
import java.util.UUID;

/** Immutable ledger receipt. Recording an assertion does not independently verify its source. */
public record CatalogFactReview(UUID reviewId, UUID proposalId, long proposalVersion,
        String proposalSha256, long reviewNumber, String optionId, String factPath,
        CatalogFactReviewRequest.Verdict verdict, Instant recordedAt,
        String kind, boolean sourceVerificationPerformed, boolean approvalGranted,
        boolean catalogWritesPerformed, boolean factTrustChanged) { }

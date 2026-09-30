package io.authweave.core.catalog.proposal;

import java.util.List;
import java.util.UUID;

/** Latest reported observation per recorded candidate fact, never an evidence or approval status. */
public record CatalogFactReviewSummaryPage(UUID proposalId, long proposalVersion, String proposalSha256,
        String policyVersion, long reviewThroughNumber, int factCount, Counts counts, int offset,
        List<Item> items, Integer nextOffset, boolean sourceVerificationPerformed, boolean approvalGranted,
        boolean catalogWritesPerformed, boolean factTrustChanged) {
    public CatalogFactReviewSummaryPage { items = List.copyOf(items); }
    public record Counts(int noObservation, int sourceSupportsClaim, int sourceDoesNotSupportClaim, int insufficientEvidence) { }
    public record Item(String optionId, String factPath, CatalogFactReview latestObservation) { }
}

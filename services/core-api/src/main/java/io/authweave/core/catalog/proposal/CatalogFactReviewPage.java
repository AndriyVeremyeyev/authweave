package io.authweave.core.catalog.proposal;

import java.util.List;
import java.util.UUID;

/** Ordered observations for one immutable revision; no actor identity or trust promotion. */
public record CatalogFactReviewPage(UUID proposalId, long proposalVersion, String proposalSha256,
        long afterReviewNumber, List<CatalogFactReview> items, Long nextAfterReviewNumber) {
    public CatalogFactReviewPage { items = List.copyOf(items); }
}

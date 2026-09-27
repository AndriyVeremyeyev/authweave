package io.authweave.core.catalog.proposal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Small curator index; neither the proposal body nor evidence leaves this read. */
public record CatalogProposalReviewPage(List<Item> items, Cursor nextBefore) {
    public CatalogProposalReviewPage { items = List.copyOf(items); }

    public record Item(UUID proposalId, long version, String proposalSha256,
            Instant createdAt, Instant updatedAt, boolean rejectionRecorded) { }

    public record Cursor(Instant createdAt, UUID id) { }
}

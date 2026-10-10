package io.authweave.core.catalog.publication;

import java.util.Objects;
import java.util.UUID;
import io.authweave.core.catalog.impact.StoredProposalDecisionService;

/** Exact stored inputs, never caller-supplied manifests, verdicts, calculation clocks or approval tokens. */
public record CatalogProposalPublicationRequest(int schemaVersion, UUID publicationId,
        PublishedCatalogSnapshot.Reference before, StoredProposalDecisionService.Reference proposal, Confirmation confirmation) {
    public CatalogProposalPublicationRequest {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported proposal publication request");
        Objects.requireNonNull(publicationId); Objects.requireNonNull(before); Objects.requireNonNull(proposal); Objects.requireNonNull(confirmation);
    }
    public enum Confirmation { PUBLISH_REVIEWED_PROPOSAL }
}

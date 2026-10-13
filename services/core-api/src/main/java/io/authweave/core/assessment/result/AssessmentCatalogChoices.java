package io.authweave.core.assessment.result;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.authweave.core.catalog.publication.PublishedCatalogSnapshot;

/** Discovery only. The unchanged recording boundary verifies the exact chosen publication. */
public record AssessmentCatalogChoices(String scope, UUID workspaceId, UUID assessmentId,
        List<Item> items, PublishedCatalogSnapshot.Reference nextBefore, boolean historicalPublicationWorkflowVerified) {
    public static final int PAGE_SIZE = 20;
    public AssessmentCatalogChoices { items = List.copyOf(items); }
    public record Item(PublishedCatalogSnapshot.Reference reference, Instant publishedAt) { }
}

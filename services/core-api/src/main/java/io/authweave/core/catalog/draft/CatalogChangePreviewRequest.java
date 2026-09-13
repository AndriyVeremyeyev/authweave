package io.authweave.core.catalog.draft;

import java.util.Objects;
import java.util.UUID;

/** Caller-supplied comparison inputs, not an authenticated proposal or a trusted published baseline. */
public record CatalogChangePreviewRequest(int schemaVersion, UUID proposalId, String rationale,
        String expectedBaseSha256, ProviderCatalogDraft base, ProviderCatalogDraft candidate) {
    public CatalogChangePreviewRequest {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported proposal schema version");
        Objects.requireNonNull(proposalId); Objects.requireNonNull(base); Objects.requireNonNull(candidate);
        ProviderCatalogDraft.text(rationale, 1000);
        if (expectedBaseSha256 == null || !expectedBaseSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Use a lowercase SHA-256 digest for the supplied baseline");
        }
    }
}

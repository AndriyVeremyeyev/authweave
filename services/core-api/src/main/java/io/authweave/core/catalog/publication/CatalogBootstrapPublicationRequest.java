package io.authweave.core.catalog.publication;

import java.util.Objects;
import java.util.UUID;
import io.authweave.core.catalog.impact.StoredCandidateDecisionService;

/** Only exact stored input pins and an explicit human action. No manifest, verdicts, clock or transported impact report. */
public record CatalogBootstrapPublicationRequest(int schemaVersion, UUID publicationId,
        StoredCandidateDecisionService.Reference source, Confirmation confirmation) {
    public CatalogBootstrapPublicationRequest {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported bootstrap publication request");
        Objects.requireNonNull(publicationId); Objects.requireNonNull(source); Objects.requireNonNull(confirmation);
    }
    public enum Confirmation { PUBLISH_REVIEWED_BOOTSTRAP }
}

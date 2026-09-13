package io.authweave.core.catalog.proposal;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Historical request and preview, not a fresh evaluation or a published catalog. */
public record CatalogProposalSnapshot(UUID proposalId, long version, String state, int requestSchemaVersion,
        String proposalSha256, Instant recordedAt, JsonNode request, JsonNode preview) {
    public CatalogProposalSnapshot { request = request.deepCopy(); preview = preview.deepCopy(); }
    @Override public JsonNode request() { return request.deepCopy(); }
    @Override public JsonNode preview() { return preview.deepCopy(); }
}

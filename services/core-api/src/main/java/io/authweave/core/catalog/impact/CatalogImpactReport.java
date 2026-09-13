package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Exact historical wire report, never deserialized into today's scenario rules on a read. */
public record CatalogImpactReport(UUID reportId, long reportNumber, UUID proposalId, long proposalVersion,
        int reportSchemaVersion, String canonicalizationVersion, String proposalSha256, String reportSha256,
        Instant recordedAt, JsonNode report) {
    public CatalogImpactReport { report = report.deepCopy(); }
    @Override public JsonNode report() { return report.deepCopy(); }
}

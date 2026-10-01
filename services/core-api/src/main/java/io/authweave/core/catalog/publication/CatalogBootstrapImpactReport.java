package io.authweave.core.catalog.publication;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Historical raw snapshot, not a proposal envelope or a reinterpretation under current rules. */
public record CatalogBootstrapImpactReport(UUID reportId, long reportNumber, UUID reviewId, String candidateSha256,
        String reviewSha256, int reportSchemaVersion, String canonicalizationVersion, String reportSha256,
        Instant recordedAt, JsonNode report) {
    public CatalogBootstrapImpactReport { report = report.deepCopy(); }
    @Override public JsonNode report() { return report.deepCopy(); }
}

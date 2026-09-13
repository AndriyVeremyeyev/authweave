package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.UUID;

public record CatalogImpactReportEvent(UUID id, UUID reportId, UUID proposalId, long proposalVersion,
        String reportSha256, String action, String actorType, String actorId, UUID correlationId,
        String outcome, Instant occurredAt) { }

package io.authweave.core.catalog.publication;

import java.time.Instant;
import java.util.UUID;

/** Internal storage assertions only. Never expose audit actor data as a lookup response. */
record CatalogPublicationRecord(Snapshot snapshot, Decision decision, Event event, Proposal proposal) {
    record Snapshot(UUID id, UUID decisionId, String decisionKind, String catalogVersion, String contentSha256,
            String snapshotSha256, Instant publishedAt, UUID previousId, String previousVersion, String previousSha256,
            String manifest, long manifestBytes) { }
    record Decision(UUID id, UUID snapshotId, String kind, UUID proposalId, Long proposalVersion, String proposalSha256,
            String catalogVersion, String contentSha256, String snapshotSha256, Instant publishedAt, Instant recordedAt) { }
    record Event(UUID id, UUID decisionId, UUID snapshotId, String snapshotSha256, String decisionKind, String action,
            String actorType, String issuer, String subject, String projectId, String orgId, Instant authenticatedAt,
            UUID correlationId, String outcome, Instant occurredAt) { }
    record Proposal(UUID id, Long version, String state, Short schemaVersion, String sha256, String request,
            long requestBytes, boolean rejected) { }
}

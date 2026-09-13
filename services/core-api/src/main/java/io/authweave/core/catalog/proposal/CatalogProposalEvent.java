package io.authweave.core.catalog.proposal;

import java.time.Instant;
import java.util.UUID;

/** No rationale, source URLs, profile data or claimed human identity in the event payload. */
public record CatalogProposalEvent(UUID id, UUID proposalId, long version, Long previousVersion,
        String action, String actorType, String actorId, UUID correlationId, String outcome,
        String proposalSha256, Instant occurredAt) { }

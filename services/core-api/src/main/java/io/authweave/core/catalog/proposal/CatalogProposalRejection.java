package io.authweave.core.catalog.proposal;

import java.time.Instant;
import java.util.UUID;

public record CatalogProposalRejection(UUID decisionId, UUID proposalId, long proposalVersion,
        String proposalSha256, String decision, CatalogProposalRejectionRequest.ReasonCode reasonCode,
        Instant recordedAt) { }

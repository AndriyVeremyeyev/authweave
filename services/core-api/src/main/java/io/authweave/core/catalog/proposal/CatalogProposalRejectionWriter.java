package io.authweave.core.catalog.proposal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static io.authweave.core.catalog.proposal.CatalogProposalException.Reason.NOT_FOUND;
import static io.authweave.core.generated.audit.tables.CatalogProposalDecisionEvents.CATALOG_PROPOSAL_DECISION_EVENTS;
import static io.authweave.core.generated.jooq.tables.CatalogProposalDecisions.CATALOG_PROPOSAL_DECISIONS;
import static io.authweave.core.generated.jooq.tables.CatalogProposalRevisions.CATALOG_PROPOSAL_REVISIONS;
import static io.authweave.core.generated.jooq.tables.CatalogProposals.CATALOG_PROPOSALS;

/** Only a rejection is supported; no approval, provider-fact write or catalog activation. */
@Service
public class CatalogProposalRejectionWriter {
    private final DSLContext dsl;

    public CatalogProposalRejectionWriter(DSLContext dsl) { this.dsl = dsl; }

    @Transactional
    public CatalogProposalRejection reject(UUID proposalId, CatalogProposalRejectionRequest request,
            CuratorActor actor) {
        var p = CATALOG_PROPOSALS;
        var r = CATALOG_PROPOSAL_REVISIONS;
        var d = CATALOG_PROPOSAL_DECISIONS;
        var e = CATALOG_PROPOSAL_DECISION_EVENTS;
        // Serialize a decision with proposal revision changes and concurrent decisions.
        var head = dsl.selectFrom(p).where(p.ID.eq(proposalId)).forUpdate().fetchOne();
        if (head == null) throw new CatalogProposalException(NOT_FOUND);
        if (!request.expectedVersion().equals(head.getVersion())) {
            throw new CatalogProposalDecisionConflictException();
        }
        var revision = dsl.selectFrom(r).where(r.PROPOSAL_ID.eq(proposalId))
                .and(r.VERSION.eq(head.getVersion())).fetchOne();
        if (revision == null || !"PROPOSED".equals(revision.getState())
                || !request.expectedSha256().equals(revision.getProposalSha256())
                || dsl.fetchExists(d, d.PROPOSAL_ID.eq(proposalId)
                        .and(d.PROPOSAL_VERSION.eq(head.getVersion())))) {
            throw new CatalogProposalDecisionConflictException();
        }
        UUID decisionId = UUID.randomUUID();
        var saved = dsl.insertInto(d).set(d.ID, decisionId).set(d.PROPOSAL_ID, proposalId)
                .set(d.PROPOSAL_VERSION, head.getVersion()).set(d.PROPOSAL_SHA256, revision.getProposalSha256())
                .set(d.DECISION, "REJECTED").set(d.REASON_CODE, request.reasonCode().name())
                .returning(d.RECORDED_AT).fetchOne();
        dsl.insertInto(e).set(e.ID, UUID.randomUUID()).set(e.DECISION_ID, decisionId)
                .set(e.PROPOSAL_ID, proposalId).set(e.PROPOSAL_VERSION, head.getVersion())
                .set(e.PROPOSAL_SHA256, revision.getProposalSha256()).set(e.DECISION, "REJECTED")
                .set(e.ACTION, "catalog-proposal.rejected").set(e.ACTOR_TYPE, "CURATOR")
                .set(e.ACTOR_ISSUER, actor.issuer()).set(e.ACTOR_SUBJECT, actor.subject())
                .set(e.ACTOR_PROJECT_ID, actor.projectId()).set(e.ACTOR_ORG_ID, actor.organizationId())
                .set(e.AUTHENTICATED_AT, OffsetDateTime.ofInstant(actor.authenticatedAt(), ZoneOffset.UTC))
                .set(e.CORRELATION_ID, UUID.randomUUID()).set(e.OUTCOME, "SUCCEEDED").execute();
        return new CatalogProposalRejection(decisionId, proposalId, head.getVersion(),
                revision.getProposalSha256(), "REJECTED", request.reasonCode(), saved.getRecordedAt().toInstant());
    }

    public record CuratorActor(String issuer, String subject, String projectId,
            String organizationId, Instant authenticatedAt) { }
}

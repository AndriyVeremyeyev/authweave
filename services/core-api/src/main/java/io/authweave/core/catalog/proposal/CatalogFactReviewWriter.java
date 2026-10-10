package io.authweave.core.catalog.proposal;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.draft.CatalogDraftFacts;
import io.authweave.core.catalog.draft.CatalogDraftValidation;
import io.authweave.core.catalog.draft.CatalogDraftValidator;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;
import static io.authweave.core.generated.jooq.tables.CatalogFactReviews.CATALOG_FACT_REVIEWS;
import static io.authweave.core.generated.audit.tables.CatalogFactReviewEvents.CATALOG_FACT_REVIEW_EVENTS;
import static io.authweave.core.generated.jooq.tables.CatalogProposals.CATALOG_PROPOSALS;
import static io.authweave.core.generated.jooq.tables.CatalogProposalDecisions.CATALOG_PROPOSAL_DECISIONS;
import static io.authweave.core.generated.jooq.tables.CatalogPublicationDecisions.CATALOG_PUBLICATION_DECISIONS;

@Service
public class CatalogFactReviewWriter {
    private final DSLContext dsl;
    private final CatalogProposalRepository repository;
    private final CatalogDraftValidator validator;
    private final ObjectMapper mapper;

    public CatalogFactReviewWriter(DSLContext dsl, CatalogProposalRepository repository,
            CatalogDraftValidator validator, ObjectMapper mapper) {
        this.dsl = dsl; this.repository = repository; this.validator = validator; this.mapper = mapper;
    }

    @Transactional
    public Result record(UUID proposalId, CatalogFactReviewRequest request, CuratorActor actor) {
        var p = CATALOG_PROPOSALS;
        var r = CATALOG_FACT_REVIEWS;
        var e = CATALOG_FACT_REVIEW_EVENTS;
        // Serialize even a key reused across different proposals. Hash collisions only add contention.
        dsl.fetch("select pg_advisory_xact_lock(hashtextextended(?, 0))", request.reviewId().toString());
        var head = dsl.selectFrom(p).where(p.ID.eq(proposalId)).forUpdate().fetchOne();
        if (head == null) throw new CatalogProposalException(CatalogProposalException.Reason.NOT_FOUND);
        var existing = dsl.selectFrom(r).where(r.ID.eq(request.reviewId())).fetchOne();
        if (existing != null) {
            var audit = dsl.selectFrom(e).where(e.REVIEW_ID.eq(request.reviewId())).fetchOne();
            if (!proposalId.equals(existing.getProposalId())
                    || !request.expectedVersion().equals(existing.getProposalVersion())
                    || !request.expectedSha256().equals(existing.getProposalSha256())
                    || !request.optionId().equals(existing.getOptionId())
                    || !request.factPath().equals(existing.getFactPath())
                    || !request.verdict().name().equals(existing.getVerdict())
                    || audit == null || !actor.issuer().equals(audit.getActorIssuer())
                    || !actor.subject().equals(audit.getActorSubject())
                    || !actor.projectId().equals(audit.getActorProjectId())
                    || !actor.organizationId().equals(audit.getActorOrgId())) {
                throw new CatalogFactReviewConflictException();
            }
            // A fresh authorization is still required by the HTTP boundary, even for historical retries.
            return new Result(CatalogFactReview.from(existing), false);
        }
        if (!request.expectedVersion().equals(head.getVersion())
                || dsl.fetchExists(CATALOG_PUBLICATION_DECISIONS, CATALOG_PUBLICATION_DECISIONS.PROPOSAL_ID.eq(proposalId)
                        .and(CATALOG_PUBLICATION_DECISIONS.PROPOSAL_VERSION.eq(head.getVersion())))
                || dsl.fetchExists(CATALOG_PROPOSAL_DECISIONS,
                        CATALOG_PROPOSAL_DECISIONS.PROPOSAL_ID.eq(proposalId)
                                .and(CATALOG_PROPOSAL_DECISIONS.PROPOSAL_VERSION.eq(head.getVersion())))) {
            throw new CatalogFactReviewConflictException();
        }
        var snapshot = repository.revision(proposalId, head.getVersion());
        if (!request.expectedSha256().equals(snapshot.proposalSha256())) {
            throw new CatalogFactReviewConflictException();
        }
        validateFact(snapshot, request);
        var saved = dsl.insertInto(r).set(r.ID, request.reviewId()).set(r.PROPOSAL_ID, proposalId)
                .set(r.PROPOSAL_VERSION, request.expectedVersion()).set(r.PROPOSAL_SHA256, request.expectedSha256())
                .set(r.OPTION_ID, request.optionId()).set(r.FACT_PATH, request.factPath())
                .set(r.VERDICT, request.verdict().name()).returning().fetchOne();
        dsl.insertInto(e).set(e.ID, UUID.randomUUID()).set(e.REVIEW_ID, request.reviewId())
                .set(e.PROPOSAL_ID, proposalId).set(e.PROPOSAL_VERSION, request.expectedVersion())
                .set(e.PROPOSAL_SHA256, request.expectedSha256()).set(e.OPTION_ID, request.optionId())
                .set(e.FACT_PATH, request.factPath()).set(e.VERDICT, request.verdict().name())
                .set(e.ACTION, "catalog-fact.review-recorded").set(e.ACTOR_TYPE, "CURATOR")
                .set(e.ACTOR_ISSUER, actor.issuer()).set(e.ACTOR_SUBJECT, actor.subject())
                .set(e.ACTOR_PROJECT_ID, actor.projectId()).set(e.ACTOR_ORG_ID, actor.organizationId())
                .set(e.AUTHENTICATED_AT, OffsetDateTime.ofInstant(actor.authenticatedAt(), ZoneOffset.UTC))
                .set(e.CORRELATION_ID, UUID.randomUUID()).set(e.OUTCOME, "SUCCEEDED").execute();
        return new Result(CatalogFactReview.from(saved), true);
    }

    private void validateFact(CatalogProposalSnapshot snapshot, CatalogFactReviewRequest review) {
        try {
            if (snapshot.requestSchemaVersion() != 1 || !"PROPOSED".equals(snapshot.state())) {
                throw new IllegalArgumentException("Unsupported snapshot");
            }
            var request = mapper.treeToValue(snapshot.request(), CatalogChangePreviewRequest.class);
            if (request == null || !snapshot.proposalId().equals(request.proposalId())
                    || !snapshot.proposalSha256().equals(CatalogDraftCanonicalizer.sha256(request))
                    || validator.validate(request.candidate()).status() != CatalogDraftValidation.Status.VALID_DRAFT) {
                throw new IllegalArgumentException("Unreviewable snapshot");
            }
            boolean present = request.candidate().options().stream()
                    .filter(option -> option.id().equals(review.optionId()))
                    .anyMatch(option -> CatalogDraftFacts.entries(option).containsKey(review.factPath()));
            if (!present) throw new CatalogFactReviewConflictException();
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException invalidSnapshot) {
            throw new CatalogProposalException(CatalogProposalException.Reason.REPLAY_UNAVAILABLE);
        }
    }

    public record Result(CatalogFactReview review, boolean created) { }
}

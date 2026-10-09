package io.authweave.core.catalog.auditability;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;
import static io.authweave.core.catalog.auditability.CatalogAuditabilityReviewException.Reason.*;
import static io.authweave.core.generated.jooq.tables.CatalogAuditabilityReviews.CATALOG_AUDITABILITY_REVIEWS;
import static io.authweave.core.generated.audit.tables.CatalogAuditabilityReviewEvents.CATALOG_AUDITABILITY_REVIEW_EVENTS;

/** Atomic append-only human assertion. Current scoped curator authority is enforced at HTTP, not inferred from rows. */
@Service
public class CatalogAuditabilityReviewService {
    public static final String POLICY_VERSION = "catalog-auditability-source-review-1";
    private final DSLContext dsl;
    private final CatalogAuditabilityReviewRepository repository;
    private final CatalogAuditabilityReviewValidator validator;
    private final ObjectMapper mapper;
    CatalogAuditabilityReviewService(DSLContext dsl, CatalogAuditabilityReviewRepository repository,
            CatalogAuditabilityReviewValidator validator, ObjectMapper mapper) {
        this.dsl = dsl; this.repository = repository; this.validator = validator; this.mapper = mapper;
    }
    public record Result(CatalogAuditabilityReview review, boolean created) { }
    record ReviewedCandidate(CatalogAuditabilityReviewRequest request, CatalogAuditabilityReview review) { }
    /** Internal engine input, never a body-bearing HTTP receipt or an approval. */
    public record DecisionSource(CatalogAuditabilityReview review, String candidateJson,
            List<CatalogAuditabilityReviewRequest.Observation> observations) {
        public DecisionSource { observations = List.copyOf(observations); }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, noRollbackFor = CatalogAuditabilityReviewException.class)
    public DecisionSource loadForDecision(UUID id, String expectedSha256) {
        var row = expectedRow(id, expectedSha256);
        var saved = validateStored(row);
        return new DecisionSource(saved.review(), mapper.readTree(row.request()).get("candidate").toString(), saved.request().observations());
    }

    @Transactional
    public Result record(CatalogAuditabilityReviewRequest request, CuratorActor actor) {
        Objects.requireNonNull(request); Objects.requireNonNull(actor);
        if (!text(actor.issuer(), 2048) || !text(actor.subject(), 256) || !digits(actor.projectId())
                || !digits(actor.organizationId()) || actor.authenticatedAt() == null) throw new CatalogAuditabilityReviewException(INVALID_REQUEST);
        repository.lock(request.reviewId());
        String digest = CatalogDraftCanonicalizer.sha256(request);
        var existing = repository.find(request.reviewId());
        if (existing != null) {
            var saved = validateStored(existing);
            if (!digest.equals(existing.reviewSha256()) || !existing.audit().sameActor(actor)) throw new CatalogAuditabilityReviewException(CONFLICT);
            return new Result(saved.review(), false);
        }
        validator.validate(request);
        String json = mapper.writeValueAsString(request);
        if (json.getBytes(StandardCharsets.UTF_8).length > CatalogAuditabilityReviewRepository.MAX_JSON_BYTES
                || repository.jsonBytes(json) > CatalogAuditabilityReviewRepository.MAX_JSON_BYTES) throw new CatalogAuditabilityReviewException(INVALID_REQUEST);
        var r = CATALOG_AUDITABILITY_REVIEWS; var e = CATALOG_AUDITABILITY_REVIEW_EVENTS;
        dsl.insertInto(r).set(r.ID, request.reviewId()).set(r.BASE_CONTENT_SHA256, request.expectedBaseContentSha256())
                .set(r.AUDITABILITY_CONTENT_SHA256, request.expectedAuditabilityContentSha256()).set(r.TARGET_SET_SHA256, request.expectedTargetSetSha256())
                .set(r.REVIEW_SHA256, digest).set(r.REQUEST_SCHEMA_VERSION, (short) 1).set(r.POLICY_VERSION, POLICY_VERSION)
                .set(r.CATALOG_VERSION, request.candidate().baseDraft().catalogVersion()).set(r.EVIDENCE_VERSION, request.candidate().auditabilityDraft().evidenceVersion())
                .set(r.OPTION_COUNT, request.candidate().auditabilityDraft().options().size()).set(r.FACT_COUNT, request.observations().size())
                .set(r.REQUEST, JSONB.jsonb(json)).execute();
        dsl.insertInto(e).set(e.ID, UUID.randomUUID()).set(e.REVIEW_ID, request.reviewId()).set(e.BASE_CONTENT_SHA256, request.expectedBaseContentSha256())
                .set(e.AUDITABILITY_CONTENT_SHA256, request.expectedAuditabilityContentSha256()).set(e.TARGET_SET_SHA256, request.expectedTargetSetSha256())
                .set(e.REVIEW_SHA256, digest).set(e.ACTION, "catalog-auditability.source-reviewed").set(e.ACTOR_TYPE, "CURATOR")
                .set(e.ACTOR_ISSUER, actor.issuer()).set(e.ACTOR_SUBJECT, actor.subject()).set(e.ACTOR_PROJECT_ID, actor.projectId())
                .set(e.ACTOR_ORG_ID, actor.organizationId()).set(e.AUTHENTICATED_AT, OffsetDateTime.ofInstant(actor.authenticatedAt(), ZoneOffset.UTC))
                .set(e.CORRELATION_ID, UUID.randomUUID()).set(e.OUTCOME, "SUCCEEDED").execute();
        return new Result(validateStored(repository.find(request.reviewId())).review(), true);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, noRollbackFor = CatalogAuditabilityReviewException.class)
    public CatalogAuditabilityReview get(UUID id, String expectedSha256) { return reviewed(id, expectedSha256).review(); }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, noRollbackFor = CatalogAuditabilityReviewException.class)
    ReviewedCandidate reviewed(UUID id, String expectedSha256) {
        return validateStored(expectedRow(id, expectedSha256));
    }

    private CatalogAuditabilityReviewRepository.Stored expectedRow(UUID id, String expectedSha256) {
        Objects.requireNonNull(id);
        if (expectedSha256 == null || !expectedSha256.matches("[a-f0-9]{64}")) throw new CatalogAuditabilityReviewException(INVALID_REQUEST);
        var row = repository.find(id);
        if (row == null) throw new CatalogAuditabilityReviewException(NOT_FOUND);
        if (!expectedSha256.equals(row.reviewSha256())) throw new CatalogAuditabilityReviewException(CONFLICT);
        return row;
    }

    private ReviewedCandidate validateStored(CatalogAuditabilityReviewRepository.Stored row) {
        try {
            if (row == null || row.request() == null || row.requestBytes() <= 0 || row.requestBytes() > CatalogAuditabilityReviewRepository.MAX_JSON_BYTES
                    || row.schemaVersion() != 1 || !POLICY_VERSION.equals(row.policyVersion())) throw new IllegalArgumentException();
            var request = mapper.readValue(row.request(), CatalogAuditabilityReviewRequest.class);
            if (request == null || !row.id().equals(request.reviewId()) || !row.baseSha256().equals(request.expectedBaseContentSha256())
                    || !row.auditabilitySha256().equals(request.expectedAuditabilityContentSha256()) || !row.targetSetSha256().equals(request.expectedTargetSetSha256())
                    || !row.reviewSha256().equals(CatalogDraftCanonicalizer.sha256(request))
                    || !row.catalogVersion().equals(request.candidate().baseDraft().catalogVersion())
                    || !row.evidenceVersion().equals(request.candidate().auditabilityDraft().evidenceVersion())
                    || row.optionCount() != request.candidate().auditabilityDraft().options().size() || row.factCount() != request.observations().size()) throw new IllegalArgumentException();
            var counts = validator.validate(request); var audit = row.audit();
            if (audit == null || audit.id() == null || !row.id().equals(audit.reviewId()) || !row.baseSha256().equals(audit.baseSha256())
                    || !row.auditabilitySha256().equals(audit.auditabilitySha256()) || !row.targetSetSha256().equals(audit.targetSetSha256())
                    || !row.reviewSha256().equals(audit.reviewSha256()) || !"catalog-auditability.source-reviewed".equals(audit.action())
                    || !"CURATOR".equals(audit.actorType()) || !text(audit.issuer(), 2048) || !text(audit.subject(), 256)
                    || !digits(audit.projectId()) || !digits(audit.organizationId()) || audit.correlationId() == null
                    || !"SUCCEEDED".equals(audit.outcome()) || audit.authenticatedAt() == null || audit.occurredAt() == null
                    || row.recordedAt() == null || row.recordedAt().isAfter(audit.occurredAt().plusSeconds(30))
                    || row.recordedAt().isBefore(audit.occurredAt().minusSeconds(30))
                    || audit.authenticatedAt().isBefore(audit.occurredAt().minusSeconds(900))
                    || audit.authenticatedAt().isAfter(audit.occurredAt().plusSeconds(30))) throw new IllegalArgumentException();
            return new ReviewedCandidate(request, new CatalogAuditabilityReview(row.id(), row.baseSha256(), row.auditabilitySha256(),
                    row.targetSetSha256(), row.reviewSha256(), row.catalogVersion(), row.evidenceVersion(), row.optionCount(), row.factCount(), counts, row.recordedAt()));
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException | CatalogAuditabilityReviewException invalid) {
            throw new CatalogAuditabilityReviewException(READ_UNAVAILABLE);
        }
        // DB failures propagate; missing/corrupt reviews never become fresh successful calculations.
    }
    private static boolean text(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    private static boolean digits(String value) { return value != null && value.matches("[0-9]{1,40}"); }
}

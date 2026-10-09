package io.authweave.core.catalog.publication;

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
import static io.authweave.core.catalog.publication.CatalogBootstrapReviewException.Reason.*;
import static io.authweave.core.generated.jooq.tables.CatalogBootstrapReviews.CATALOG_BOOTSTRAP_REVIEWS;
import static io.authweave.core.generated.audit.tables.CatalogBootstrapReviewEvents.CATALOG_BOOTSTRAP_REVIEW_EVENTS;

/** Atomic manual assertion + body-free audit. The HTTP boundary, not database rows, validates current curator assertions. */
@Service
public class CatalogBootstrapReviewService {
    public static final String POLICY_VERSION = "catalog-bootstrap-source-review-1";
    private final DSLContext dsl;
    private final CatalogBootstrapReviewRepository repository;
    private final CatalogPublicationPreflightRepository registry;
    private final CatalogBootstrapReviewValidator validator;
    private final ObjectMapper mapper;
    CatalogBootstrapReviewService(DSLContext dsl, CatalogBootstrapReviewRepository repository,
            CatalogPublicationPreflightRepository registry, CatalogBootstrapReviewValidator validator, ObjectMapper mapper) {
        this.dsl = dsl; this.repository = repository; this.registry = registry; this.validator = validator; this.mapper = mapper;
    }
    public record Result(CatalogBootstrapReview review, boolean created) { }
    record ReviewedCandidate(CatalogBootstrapReviewRequest request, CatalogBootstrapReview review) { }
    /** Internal engine input, not an HTTP response or a current curator-authority grant. */
    public record DecisionSource(CatalogBootstrapReview review, String candidateJson,
            List<CatalogBootstrapReviewRequest.Observation> observations) {
        public DecisionSource { observations = List.copyOf(observations); }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, noRollbackFor = CatalogBootstrapReviewException.class)
    public DecisionSource loadForDecision(UUID id, String expectedSha256) {
        var row = expectedRow(id, expectedSha256);
        var saved = validateStored(row);
        // Retain the actual persisted array order and field presence, not a typed reserialization.
        return new DecisionSource(saved.review(), mapper.readTree(row.request()).get("candidate").toString(), saved.request().observations());
    }

    @Transactional
    public Result record(CatalogBootstrapReviewRequest request, CuratorActor actor) {
        Objects.requireNonNull(request); Objects.requireNonNull(actor);
        // Also held by the DB insert trigger and reserved publication decisions. All bootstrap keys serialize here.
        repository.lockBoundary();
        String digest = CatalogDraftCanonicalizer.sha256(request);
        var existing = repository.find(request.reviewId());
        if (existing != null) {
            var saved = validateStored(existing);
            if (!digest.equals(existing.reviewSha256()) || !existing.audit().sameActor(actor)) throw new CatalogBootstrapReviewException(CONFLICT);
            // Auth freshness is rechecked by HTTP even for retries after publication. Do not rerun freshness on historical evidence.
            return new Result(saved.review(), false);
        }
        if (!registry.registryEmpty()) throw new CatalogBootstrapReviewException(CONFLICT);
        validator.validate(request);
        String json = mapper.writeValueAsString(request);
        if (json.getBytes(StandardCharsets.UTF_8).length > CatalogBootstrapReviewRepository.MAX_JSON_BYTES
                || repository.jsonBytes(json) > CatalogBootstrapReviewRepository.MAX_JSON_BYTES) throw new CatalogBootstrapReviewException(INVALID_REQUEST);
        var r = CATALOG_BOOTSTRAP_REVIEWS; var e = CATALOG_BOOTSTRAP_REVIEW_EVENTS;
        dsl.insertInto(r).set(r.ID, request.reviewId()).set(r.CANDIDATE_SHA256, request.expectedCandidateSha256())
                .set(r.REVIEW_SHA256, digest).set(r.REQUEST_SCHEMA_VERSION, (short) 1).set(r.POLICY_VERSION, POLICY_VERSION)
                .set(r.CATALOG_VERSION, request.candidate().catalogVersion()).set(r.FACT_COUNT, request.observations().size())
                .set(r.REQUEST, JSONB.jsonb(json)).execute();
        dsl.insertInto(e).set(e.ID, UUID.randomUUID()).set(e.REVIEW_ID, request.reviewId())
                .set(e.CANDIDATE_SHA256, request.expectedCandidateSha256()).set(e.REVIEW_SHA256, digest)
                .set(e.ACTION, "catalog-bootstrap.source-reviewed").set(e.ACTOR_TYPE, "CURATOR")
                .set(e.ACTOR_ISSUER, actor.issuer()).set(e.ACTOR_SUBJECT, actor.subject())
                .set(e.ACTOR_PROJECT_ID, actor.projectId()).set(e.ACTOR_ORG_ID, actor.organizationId())
                .set(e.AUTHENTICATED_AT, OffsetDateTime.ofInstant(actor.authenticatedAt(), ZoneOffset.UTC))
                .set(e.CORRELATION_ID, UUID.randomUUID()).set(e.OUTCOME, "SUCCEEDED").execute();
        return new Result(validateStored(repository.find(request.reviewId())).review(), true);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, noRollbackFor = CatalogBootstrapReviewException.class)
    public CatalogBootstrapReview get(UUID id, String expectedSha256) { return reviewed(id, expectedSha256).review(); }

    // Expected read denials must not poison an outer preflight transaction that reports BLOCKED.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, noRollbackFor = CatalogBootstrapReviewException.class)
    ReviewedCandidate reviewed(UUID id, String expectedSha256) {
        return validateStored(expectedRow(id, expectedSha256));
    }

    private CatalogBootstrapReviewRepository.Stored expectedRow(UUID id, String expectedSha256) {
        Objects.requireNonNull(id);
        if (expectedSha256 == null || !expectedSha256.matches("[a-f0-9]{64}")) throw new CatalogBootstrapReviewException(INVALID_REQUEST);
        var row = repository.find(id);
        if (row == null) throw new CatalogBootstrapReviewException(NOT_FOUND);
        if (!expectedSha256.equals(row.reviewSha256())) throw new CatalogBootstrapReviewException(CONFLICT);
        return row;
    }

    private ReviewedCandidate validateStored(CatalogBootstrapReviewRepository.Stored row) {
        try {
            if (row.request() == null || row.requestBytes() <= 0 || row.requestBytes() > CatalogBootstrapReviewRepository.MAX_JSON_BYTES
                    || row.schemaVersion() != 1 || !POLICY_VERSION.equals(row.policyVersion())) throw new IllegalArgumentException();
            var request = mapper.readValue(row.request(), CatalogBootstrapReviewRequest.class);
            if (request == null || !row.id().equals(request.reviewId()) || !row.candidateSha256().equals(request.expectedCandidateSha256())
                    || !row.reviewSha256().equals(CatalogDraftCanonicalizer.sha256(request))
                    || !row.catalogVersion().equals(request.candidate().catalogVersion()) || row.factCount() != request.observations().size()) {
                throw new IllegalArgumentException();
            }
            var counts = validator.validate(request); var audit = row.audit();
            if (audit == null || audit.id() == null || !row.id().equals(audit.reviewId()) || !row.candidateSha256().equals(audit.candidateSha256())
                    || !row.reviewSha256().equals(audit.reviewSha256()) || !"catalog-bootstrap.source-reviewed".equals(audit.action())
                    || !"CURATOR".equals(audit.actorType()) || !text(audit.issuer(), 2048) || !text(audit.subject(), 256)
                    || !digits(audit.projectId()) || !digits(audit.organizationId()) || audit.correlationId() == null
                    || !"SUCCEEDED".equals(audit.outcome()) || audit.authenticatedAt() == null || audit.occurredAt() == null
                    || row.recordedAt() == null || row.recordedAt().isAfter(audit.occurredAt().plusSeconds(30))
                    || row.recordedAt().isBefore(audit.occurredAt().minusSeconds(30))
                    || audit.authenticatedAt().isBefore(audit.occurredAt().minusSeconds(900))
                    || audit.authenticatedAt().isAfter(audit.occurredAt().plusSeconds(30))) throw new IllegalArgumentException();
            return new ReviewedCandidate(request, new CatalogBootstrapReview(row.id(), row.candidateSha256(), row.reviewSha256(),
                    row.catalogVersion(), row.factCount(), counts, row.recordedAt()));
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException | CatalogBootstrapReviewException invalid) {
            throw new CatalogBootstrapReviewException(READ_UNAVAILABLE);
        }
        // Database failures are not caught or converted to missing reviews.
    }
    private static boolean text(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    private static boolean digits(String value) { return value != null && value.matches("[0-9]{1,40}"); }
}

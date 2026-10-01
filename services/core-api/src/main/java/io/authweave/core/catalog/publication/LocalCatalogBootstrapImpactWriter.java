package io.authweave.core.catalog.publication;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.impact.CatalogBootstrapImpactService;
import static io.authweave.core.generated.jooq.tables.CatalogBootstrapImpactReports.CATALOG_BOOTSTRAP_IMPACT_REPORTS;
import static io.authweave.core.generated.audit.tables.CatalogBootstrapImpactReportEvents.CATALOG_BOOTSTRAP_IMPACT_REPORT_EVENTS;
import static io.authweave.core.catalog.publication.CatalogBootstrapImpactReportException.Reason.*;

/** Explicit local service write. Caller supplies exact identities/hash, never JSON, time, source truth or authorization. */
@Service
@Profile("local-catalog-bootstrap-impact-write")
public class LocalCatalogBootstrapImpactWriter {
    private final DSLContext dsl;
    private final ObjectMapper mapper;
    private final CatalogBootstrapImpactService impacts;
    private final CatalogBootstrapImpactReportRepository reports;
    private final CatalogBootstrapReviewRepository boundary;
    private final CatalogBootstrapReviewService reviews;
    private final CatalogPublicationPreflightRepository registry;
    public LocalCatalogBootstrapImpactWriter(DSLContext dsl, ObjectMapper mapper, CatalogBootstrapImpactService impacts,
            CatalogBootstrapImpactReportRepository reports, CatalogBootstrapReviewRepository boundary,
            CatalogBootstrapReviewService reviews, CatalogPublicationPreflightRepository registry) {
        this.dsl = dsl; this.mapper = mapper; this.impacts = impacts; this.reports = reports;
        this.boundary = boundary; this.reviews = reviews; this.registry = registry;
    }
    @Transactional
    public SaveResult save(UUID reportId, UUID reviewId, String expectedReviewSha256) {
        Objects.requireNonNull(reportId); Objects.requireNonNull(reviewId);
        if (expectedReviewSha256 == null || !expectedReviewSha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Use an exact review SHA-256");
        boundary.lockBoundary();
        var existing = reports.find(reportId);
        if (existing != null) {
            if (!reviewId.equals(existing.reviewId()) || !expectedReviewSha256.equals(existing.reviewSha256()))
                throw new CatalogBootstrapImpactReportException(ID_CONFLICT);
            // Immutable retries survive later publication and rule changes, without a new analysis/audit.
            return new SaveResult(false, reports.snapshot(existing));
        }
        if (!registry.registryEmpty()) throw new CatalogBootstrapImpactReportException(REGISTRY_NOT_EMPTY);
        var reviewed = reviews.reviewed(reviewId, expectedReviewSha256);
        var at = dsl.select(org.jooq.impl.DSL.field("clock_timestamp()", OffsetDateTime.class)).fetchOne(0, OffsetDateTime.class).toInstant();
        if (reviewed.review().recordedAt().isAfter(at.plusSeconds(30))) throw new CatalogBootstrapImpactReportException(REVIEW_UNAVAILABLE);
        var report = impacts.analyzeAt(reviewed.request(), at);
        var hash = CatalogDraftCanonicalizer.sha256(report); var body = mapper.writeValueAsString(report);
        if (body.getBytes(StandardCharsets.UTF_8).length > CatalogBootstrapImpactReportRepository.MAX_JSON_BYTES
                || boundary.jsonBytes(body) > CatalogBootstrapImpactReportRepository.MAX_JSON_BYTES)
            throw new CatalogBootstrapImpactReportException(REPORT_TOO_LARGE);
        var r = CATALOG_BOOTSTRAP_IMPACT_REPORTS;
        dsl.insertInto(r).set(r.ID, reportId).set(r.REVIEW_ID, reviewId).set(r.CANDIDATE_SHA256, reviewed.review().candidateSha256())
                .set(r.REVIEW_SHA256, expectedReviewSha256).set(r.REPORT_SCHEMA_VERSION, (short) 1)
                .set(r.CANONICALIZATION_VERSION, CatalogDraftCanonicalizer.VERSION).set(r.REPORT_SHA256, hash).set(r.REPORT, JSONB.valueOf(body)).execute();
        var e = CATALOG_BOOTSTRAP_IMPACT_REPORT_EVENTS;
        dsl.insertInto(e).set(e.ID, UUID.randomUUID()).set(e.REPORT_ID, reportId).set(e.REVIEW_ID, reviewId)
                .set(e.CANDIDATE_SHA256, reviewed.review().candidateSha256()).set(e.REVIEW_SHA256, expectedReviewSha256).set(e.REPORT_SHA256, hash)
                .set(e.ACTION, "catalog-bootstrap-impact.recorded").set(e.ACTOR_TYPE, "SERVICE").set(e.ACTOR_ID, "core-api-local-catalog")
                .set(e.CORRELATION_ID, UUID.randomUUID()).set(e.OUTCOME, "SUCCEEDED").execute();
        return new SaveResult(true, reports.get(reviewId, expectedReviewSha256, reportId));
    }
    public record SaveResult(boolean changed, CatalogBootstrapImpactReport report) { }
}

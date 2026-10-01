package io.authweave.core.catalog.publication;

import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;
import static io.authweave.core.generated.jooq.tables.CatalogBootstrapReviews.CATALOG_BOOTSTRAP_REVIEWS;
import static io.authweave.core.generated.audit.tables.CatalogBootstrapReviewEvents.CATALOG_BOOTSTRAP_REVIEW_EVENTS;

@Repository
class CatalogBootstrapReviewRepository {
    static final long MAX_JSON_BYTES = 32L * 1024 * 1024;
    private final DSLContext dsl;
    CatalogBootstrapReviewRepository(DSLContext dsl) { this.dsl = dsl; }
    record Audit(UUID id, UUID reviewId, String candidateSha256, String reviewSha256, String action, String actorType,
            String issuer, String subject, String projectId, String organizationId, Instant authenticatedAt,
            UUID correlationId, String outcome, Instant occurredAt) {
        boolean sameActor(CuratorActor actor) {
            return issuer.equals(actor.issuer()) && subject.equals(actor.subject()) && projectId.equals(actor.projectId())
                    && organizationId.equals(actor.organizationId());
        }
    }
    record Stored(UUID id, String candidateSha256, String reviewSha256, int schemaVersion, String policyVersion,
            String catalogVersion, int factCount, Instant recordedAt, String request, long requestBytes, Audit audit) { }

    void lockBoundary() { dsl.fetch("select pg_advisory_xact_lock(hashtextextended('authweave:catalog-bootstrap-boundary', 0))"); }
    int jsonBytes(String json) { return dsl.fetchOne("select octet_length(?::jsonb::text)", json).get(0, Integer.class); }
    Stored find(UUID id) { return find(id, MAX_JSON_BYTES); }
    Stored find(UUID id, long maxBytes) {
        if (maxBytes < 0 || maxBytes > MAX_JSON_BYTES) throw new IllegalArgumentException("Invalid read budget");
        var r = CATALOG_BOOTSTRAP_REVIEWS; var e = CATALOG_BOOTSTRAP_REVIEW_EVENTS;
        var length = DSL.octetLength(r.REQUEST.cast(String.class)).cast(Long.class);
        var bytes = length.as("request_bytes");
        var body = DSL.when(length.le(maxBytes), r.REQUEST).otherwise((JSONB) null).as("bounded_request");
        var row = dsl.select(r.ID, r.CANDIDATE_SHA256, r.REVIEW_SHA256, r.REQUEST_SCHEMA_VERSION, r.POLICY_VERSION,
                        r.CATALOG_VERSION, r.FACT_COUNT, r.RECORDED_AT, body, bytes).select(e.fields())
                .from(r).leftJoin(e).on(e.REVIEW_ID.eq(r.ID)).where(r.ID.eq(id)).fetchOne();
        if (row == null) return null;
        var json = row.get(body);
        var audit = row.get(e.ID) == null ? null : new Audit(row.get(e.ID), row.get(e.REVIEW_ID), row.get(e.CANDIDATE_SHA256),
                row.get(e.REVIEW_SHA256), row.get(e.ACTION), row.get(e.ACTOR_TYPE), row.get(e.ACTOR_ISSUER), row.get(e.ACTOR_SUBJECT),
                row.get(e.ACTOR_PROJECT_ID), row.get(e.ACTOR_ORG_ID), row.get(e.AUTHENTICATED_AT).toInstant(),
                row.get(e.CORRELATION_ID), row.get(e.OUTCOME), row.get(e.OCCURRED_AT).toInstant());
        return new Stored(row.get(r.ID), row.get(r.CANDIDATE_SHA256), row.get(r.REVIEW_SHA256), row.get(r.REQUEST_SCHEMA_VERSION),
                row.get(r.POLICY_VERSION), row.get(r.CATALOG_VERSION), row.get(r.FACT_COUNT), row.get(r.RECORDED_AT).toInstant(),
                json == null ? null : json.data(), row.get(bytes), audit);
    }
}

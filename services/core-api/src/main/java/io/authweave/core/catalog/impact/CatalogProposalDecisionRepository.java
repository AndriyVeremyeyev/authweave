package io.authweave.core.catalog.impact;

import java.util.List;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import static io.authweave.core.generated.jooq.tables.CatalogProposalRevisions.CATALOG_PROPOSAL_REVISIONS;
import static io.authweave.core.generated.audit.tables.CatalogProposalEvents.CATALOG_PROPOSAL_EVENTS;
import static io.authweave.core.generated.jooq.tables.CatalogFactReviews.CATALOG_FACT_REVIEWS;
import static io.authweave.core.generated.audit.tables.CatalogFactReviewEvents.CATALOG_FACT_REVIEW_EVENTS;
import io.authweave.core.generated.jooq.tables.records.CatalogProposalRevisionsRecord;
import io.authweave.core.generated.jooq.tables.records.CatalogFactReviewsRecord;
import io.authweave.core.generated.audit.tables.records.CatalogProposalEventsRecord;
import io.authweave.core.generated.audit.tables.records.CatalogFactReviewEventsRecord;

/** Bounded original request and latest-per-address observations at an explicit immutable cutoff.
 * Existence alone grants no trust. Call only within the loader's consistent read transaction. */
@Repository
public class CatalogProposalDecisionRepository {
    static final long MAX_JSON_BYTES = 32L * 1024 * 1024;
    static final int MAX_FACTS = 6800;
    private final DSLContext dsl;
    public CatalogProposalDecisionRepository(DSLContext dsl) { this.dsl = dsl; }
    record Proposal(CatalogProposalRevisionsRecord revision, CatalogProposalEventsRecord audit, long requestBytes) { }
    record Observation(CatalogFactReviewsRecord review, CatalogFactReviewEventsRecord audit) { }

    Proposal proposal(StoredProposalDecisionService.Revision reference) {
        var r = CATALOG_PROPOSAL_REVISIONS; var e = CATALOG_PROPOSAL_EVENTS;
        var bytes = DSL.octetLength(r.REQUEST.cast(String.class)).cast(Long.class);
        var body = DSL.when(bytes.le(MAX_JSON_BYTES), r.REQUEST).otherwise((JSONB) null).as("bounded_request");
        // Do not select the unbounded request or the obsolete conditional preview.
        var row = dsl.select(r.PROPOSAL_ID, r.VERSION, r.STATE, r.REQUEST_SCHEMA_VERSION, r.PROPOSAL_SHA256, r.RECORDED_AT, body, bytes)
                .select(e.fields()).from(r).leftJoin(e).on(e.PROPOSAL_ID.eq(r.PROPOSAL_ID).and(e.VERSION.eq(r.VERSION)))
                .where(r.PROPOSAL_ID.eq(reference.proposalId()).and(r.VERSION.eq(reference.version()))).fetchOne();
        if (row == null) return null;
        var revision = row.into(r); revision.setRequest(row.get(body));
        return new Proposal(revision, row.get(e.ID) == null ? null : row.into(e), row.get(bytes));
    }
    long latestNumber(StoredProposalDecisionService.Revision reference) {
        var r = CATALOG_FACT_REVIEWS;
        return dsl.select(DSL.coalesce(DSL.max(r.REVIEW_NUMBER), 0L)).from(r)
                .where(r.PROPOSAL_ID.eq(reference.proposalId()).and(r.PROPOSAL_VERSION.eq(reference.version())))
                .fetchSingle().value1();
    }
    List<Observation> observations(StoredProposalDecisionService.Revision reference, long through) {
        var r = CATALOG_FACT_REVIEWS; var e = CATALOG_FACT_REVIEW_EVENTS;
        return dsl.select(r.fields()).select(e.fields()).distinctOn(r.OPTION_ID, r.FACT_PATH)
                .from(r).leftJoin(e).on(e.REVIEW_ID.eq(r.ID))
                .where(r.PROPOSAL_ID.eq(reference.proposalId()).and(r.PROPOSAL_VERSION.eq(reference.version())).and(r.REVIEW_NUMBER.le(through)))
                .orderBy(r.OPTION_ID, r.FACT_PATH, r.REVIEW_NUMBER.desc()).limit(MAX_FACTS + 1).fetch()
                .map(row -> new Observation(row.into(r), row.get(e.ID) == null ? null : row.into(e)));
    }
}

package io.authweave.core.catalog.proposal;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.generated.jooq.tables.records.CatalogProposalRevisionsRecord;
import static io.authweave.core.generated.jooq.tables.CatalogProposals.CATALOG_PROPOSALS;
import static io.authweave.core.generated.jooq.tables.CatalogProposalRevisions.CATALOG_PROPOSAL_REVISIONS;
import static io.authweave.core.generated.jooq.tables.CatalogProposalDecisions.CATALOG_PROPOSAL_DECISIONS;
import static io.authweave.core.generated.audit.tables.CatalogProposalEvents.CATALOG_PROPOSAL_EVENTS;

/** Read boundary available to the loopback API; writing is an explicit local command only. */
@Repository
@Transactional(readOnly = true)
public class CatalogProposalRepository {
    private static final int REVIEW_PAGE_SIZE = 20;
    private final DSLContext dsl;
    private final ObjectMapper mapper;
    public CatalogProposalRepository(DSLContext dsl, ObjectMapper mapper) { this.dsl = dsl; this.mapper = mapper; }

    public CatalogProposalSnapshot current(UUID id) {
        var r = CATALOG_PROPOSAL_REVISIONS; var p = CATALOG_PROPOSALS;
        var row = dsl.select(r.fields()).from(r).join(p).on(r.PROPOSAL_ID.eq(p.ID).and(r.VERSION.eq(p.VERSION)))
                .where(p.ID.eq(id)).fetchOneInto(r);
        if (row == null) throw new CatalogProposalException(CatalogProposalException.Reason.NOT_FOUND);
        return snapshot(row);
    }

    /** Stable keyset over creation time and ID; current revision and decision only. */
    public CatalogProposalReviewPage reviewPage(CatalogProposalReviewPage.Cursor before) {
        var p = CATALOG_PROPOSALS; var r = CATALOG_PROPOSAL_REVISIONS; var d = CATALOG_PROPOSAL_DECISIONS;
        Condition older = DSL.trueCondition();
        if (before != null) {
            var at = OffsetDateTime.ofInstant(before.createdAt(), ZoneOffset.UTC);
            older = p.CREATED_AT.lt(at).or(p.CREATED_AT.eq(at).and(p.ID.lt(before.id())));
        }
        var rows = dsl.select(p.ID, p.VERSION, p.CREATED_AT, p.UPDATED_AT,
                        r.PROPOSAL_SHA256, d.ID)
                .from(p).join(r).on(r.PROPOSAL_ID.eq(p.ID).and(r.VERSION.eq(p.VERSION)))
                .leftJoin(d).on(d.PROPOSAL_ID.eq(p.ID).and(d.PROPOSAL_VERSION.eq(p.VERSION)))
                .where(older).orderBy(p.CREATED_AT.desc(), p.ID.desc())
                .limit(REVIEW_PAGE_SIZE + 1)
                .fetch(row -> new CatalogProposalReviewPage.Item(row.get(p.ID), row.get(p.VERSION),
                        row.get(r.PROPOSAL_SHA256), row.get(p.CREATED_AT).toInstant(),
                        row.get(p.UPDATED_AT).toInstant(), row.get(d.ID) != null));
        boolean more = rows.size() > REVIEW_PAGE_SIZE;
        var items = more ? rows.subList(0, REVIEW_PAGE_SIZE) : rows;
        var last = more ? items.getLast() : null;
        return new CatalogProposalReviewPage(items, last == null ? null :
                new CatalogProposalReviewPage.Cursor(last.createdAt(), last.proposalId()));
    }

    /** A decision is separate from the immutable PROPOSED snapshot; null means not yet rejected. */
    public CatalogProposalRejection currentRejection(UUID id) {
        var p = CATALOG_PROPOSALS; var d = CATALOG_PROPOSAL_DECISIONS;
        var row = dsl.select(p.VERSION, d.ID, d.PROPOSAL_VERSION, d.PROPOSAL_SHA256,
                        d.DECISION, d.REASON_CODE, d.RECORDED_AT)
                .from(p).leftJoin(d).on(d.PROPOSAL_ID.eq(p.ID).and(d.PROPOSAL_VERSION.eq(p.VERSION)))
                .where(p.ID.eq(id)).fetchOne();
        if (row == null) throw new CatalogProposalException(CatalogProposalException.Reason.NOT_FOUND);
        if (row.get(d.ID) == null) return null;
        return new CatalogProposalRejection(row.get(d.ID), id, row.get(d.PROPOSAL_VERSION),
                row.get(d.PROPOSAL_SHA256), row.get(d.DECISION),
                CatalogProposalRejectionRequest.ReasonCode.valueOf(row.get(d.REASON_CODE)),
                row.get(d.RECORDED_AT).toInstant());
    }

    public CatalogProposalPage<CatalogProposalSnapshot> revisions(UUID id, Long afterVersion, int limit) {
        bounds(afterVersion, limit); requireExists(id);
        var r = CATALOG_PROPOSAL_REVISIONS;
        return CatalogProposalPage.from(dsl.selectFrom(r).where(r.PROPOSAL_ID.eq(id))
                .and(r.VERSION.gt(afterVersion == null ? -1L : afterVersion)).orderBy(r.VERSION.asc())
                .limit(limit + 1).fetch(this::snapshot), limit, CatalogProposalSnapshot::version);
    }

    public CatalogProposalSnapshot revision(UUID id, long version) {
        if (version < 0 || version > 9007199254740991L) throw new IllegalArgumentException("Invalid proposal version");
        var r = CATALOG_PROPOSAL_REVISIONS;
        var row = dsl.selectFrom(r).where(r.PROPOSAL_ID.eq(id)).and(r.VERSION.eq(version)).fetchOne();
        if (row == null) throw new CatalogProposalException(CatalogProposalException.Reason.NOT_FOUND);
        return snapshot(row);
    }

    public CatalogProposalPage<CatalogProposalEvent> events(UUID id, Long afterVersion, int limit) {
        bounds(afterVersion, limit); requireExists(id);
        var e = CATALOG_PROPOSAL_EVENTS;
        return CatalogProposalPage.from(dsl.selectFrom(e).where(e.PROPOSAL_ID.eq(id))
                .and(e.VERSION.gt(afterVersion == null ? -1L : afterVersion)).orderBy(e.VERSION.asc())
                .limit(limit + 1).fetch(row -> new CatalogProposalEvent(row.getId(), row.getProposalId(), row.getVersion(),
                        row.getPreviousVersion(), row.getAction(), row.getActorType(), row.getActorId(), row.getCorrelationId(),
                        row.getOutcome(), row.getProposalSha256(), row.getOccurredAt().toInstant())), limit, CatalogProposalEvent::version);
    }

    private CatalogProposalSnapshot snapshot(CatalogProposalRevisionsRecord row) {
        // Read the stored wire snapshots without revalidating them against today's domain rules or clock.
        return new CatalogProposalSnapshot(row.getProposalId(), row.getVersion(), row.getState(), row.getRequestSchemaVersion(),
                row.getProposalSha256(), row.getRecordedAt().toInstant(), mapper.readTree(row.getRequest().data()), mapper.readTree(row.getPreview().data()));
    }
    private void requireExists(UUID id) {
        if (!dsl.fetchExists(CATALOG_PROPOSALS, CATALOG_PROPOSALS.ID.eq(id))) {
            throw new CatalogProposalException(CatalogProposalException.Reason.NOT_FOUND);
        }
    }
    private static void bounds(Long after, int limit) {
        if (limit < 1 || limit > 100 || (after != null && (after < 0 || after > 9007199254740991L))) {
            throw new IllegalArgumentException("Invalid history page bounds");
        }
    }
}

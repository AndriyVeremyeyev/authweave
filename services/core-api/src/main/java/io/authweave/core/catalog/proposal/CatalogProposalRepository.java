package io.authweave.core.catalog.proposal;

import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.generated.jooq.tables.records.CatalogProposalRevisionsRecord;
import static io.authweave.core.generated.jooq.tables.CatalogProposals.CATALOG_PROPOSALS;
import static io.authweave.core.generated.jooq.tables.CatalogProposalRevisions.CATALOG_PROPOSAL_REVISIONS;
import static io.authweave.core.generated.audit.tables.CatalogProposalEvents.CATALOG_PROPOSAL_EVENTS;

/** Read boundary available to the loopback API; writing is an explicit local command only. */
@Repository
@Transactional(readOnly = true)
public class CatalogProposalRepository {
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

    public CatalogProposalPage<CatalogProposalSnapshot> revisions(UUID id, Long afterVersion, int limit) {
        bounds(afterVersion, limit); requireExists(id);
        var r = CATALOG_PROPOSAL_REVISIONS;
        return CatalogProposalPage.from(dsl.selectFrom(r).where(r.PROPOSAL_ID.eq(id))
                .and(r.VERSION.gt(afterVersion == null ? -1L : afterVersion)).orderBy(r.VERSION.asc())
                .limit(limit + 1).fetch(this::snapshot), limit, CatalogProposalSnapshot::version);
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

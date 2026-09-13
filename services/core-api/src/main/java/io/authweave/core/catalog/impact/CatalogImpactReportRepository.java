package io.authweave.core.catalog.impact;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.catalog.proposal.CatalogProposalException;
import io.authweave.core.generated.jooq.tables.records.CatalogImpactReportsRecord;
import static io.authweave.core.generated.jooq.tables.CatalogImpactReports.CATALOG_IMPACT_REPORTS;
import static io.authweave.core.generated.jooq.tables.CatalogProposalRevisions.CATALOG_PROPOSAL_REVISIONS;
import static io.authweave.core.generated.audit.tables.CatalogImpactReportEvents.CATALOG_IMPACT_REPORT_EVENTS;

@Repository
@Transactional(readOnly = true)
public class CatalogImpactReportRepository {
    private final DSLContext dsl;
    private final ObjectMapper mapper;
    public CatalogImpactReportRepository(DSLContext dsl, ObjectMapper mapper) { this.dsl = dsl; this.mapper = mapper; }
    Optional<CatalogImpactReport> find(UUID id) {
        return dsl.selectFrom(CATALOG_IMPACT_REPORTS).where(CATALOG_IMPACT_REPORTS.ID.eq(id)).fetchOptional(this::snapshot);
    }
    public CatalogImpactReport get(UUID proposalId, long version, UUID reportId) {
        version(version);
        return find(reportId).filter(r -> r.proposalId().equals(proposalId) && r.proposalVersion() == version)
                .orElseThrow(() -> new CatalogImpactReportException(CatalogImpactReportException.Reason.NOT_FOUND));
    }
    public Page list(UUID proposalId, long version, Long after, int limit) {
        version(version);
        if (limit < 1 || limit > 100 || (after != null && (after < 0 || after > 9007199254740991L))) throw new IllegalArgumentException("Invalid impact history page bounds");
        var revisions = CATALOG_PROPOSAL_REVISIONS;
        if (!dsl.fetchExists(revisions, revisions.PROPOSAL_ID.eq(proposalId).and(revisions.VERSION.eq(version)))) {
            throw new CatalogProposalException(CatalogProposalException.Reason.NOT_FOUND);
        }
        var r = CATALOG_IMPACT_REPORTS;
        var rows = dsl.selectFrom(r).where(r.PROPOSAL_ID.eq(proposalId).and(r.PROPOSAL_VERSION.eq(version)))
                .and(r.REPORT_NUMBER.gt(after == null ? 0 : after)).orderBy(r.REPORT_NUMBER.asc()).limit(limit + 1).fetch(this::snapshot);
        boolean more = rows.size() > limit; var items = more ? rows.subList(0, limit) : rows;
        return new Page(items, more ? items.getLast().reportNumber() : null);
    }
    public CatalogImpactReportEvent event(UUID proposalId, long version, UUID reportId) {
        version(version); var e = CATALOG_IMPACT_REPORT_EVENTS;
        var row = dsl.selectFrom(e).where(e.REPORT_ID.eq(reportId).and(e.PROPOSAL_ID.eq(proposalId)).and(e.PROPOSAL_VERSION.eq(version))).fetchOne();
        if (row == null) throw new CatalogImpactReportException(CatalogImpactReportException.Reason.NOT_FOUND);
        return new CatalogImpactReportEvent(row.getId(), row.getReportId(), row.getProposalId(), row.getProposalVersion(), row.getReportSha256(),
                row.getAction(), row.getActorType(), row.getActorId(), row.getCorrelationId(), row.getOutcome(), row.getOccurredAt().toInstant());
    }
    private CatalogImpactReport snapshot(CatalogImpactReportsRecord row) {
        return new CatalogImpactReport(row.getId(), row.getReportNumber(), row.getProposalId(), row.getProposalVersion(), row.getReportSchemaVersion(),
                row.getCanonicalizationVersion(), row.getProposalSha256(), row.getReportSha256(), row.getRecordedAt().toInstant(), mapper.readTree(row.getReport().data()));
    }
    static void version(long value) { if (value < 0 || value > 9007199254740991L) throw new IllegalArgumentException("Use a non-negative safe integer version"); }
    public record Page(List<CatalogImpactReport> items, Long nextAfterReportNumber) { public Page { items = List.copyOf(items); } }
}

package io.authweave.core.catalog.impact;

import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.proposal.CatalogProposalException;
import static io.authweave.core.generated.jooq.tables.CatalogProposals.CATALOG_PROPOSALS;
import static io.authweave.core.generated.jooq.tables.CatalogImpactReports.CATALOG_IMPACT_REPORTS;
import static io.authweave.core.generated.audit.tables.CatalogImpactReportEvents.CATALOG_IMPACT_REPORT_EVENTS;

/** Local-only immutable scenario report write. The caller selects a revision and an idempotency UUID, not a report body. */
@Service
@Profile("local-catalog-impact-write")
public class LocalCatalogImpactWriter {
    private final DSLContext dsl;
    private final ObjectMapper mapper;
    private final CatalogScenarioReplay replay;
    private final CatalogImpactReportRepository reports;
    public LocalCatalogImpactWriter(DSLContext dsl, ObjectMapper mapper, CatalogScenarioReplay replay, CatalogImpactReportRepository reports) {
        this.dsl = dsl; this.mapper = mapper; this.replay = replay; this.reports = reports;
    }
    @Transactional
    public SaveResult save(UUID reportId, UUID proposalId, long version) {
        Objects.requireNonNull(reportId); Objects.requireNonNull(proposalId); CatalogImpactReportRepository.version(version);
        var existing = reports.find(reportId);
        if (existing.isPresent()) return unchanged(existing.get(), proposalId, version);
        // Serialize all report writes for this proposal BEFORE allocating report numbers. This also
        // prevents a committed page cursor from skipping a lower-numbered in-flight report in this scope.
        var p = CATALOG_PROPOSALS;
        if (dsl.select(p.ID).from(p).where(p.ID.eq(proposalId)).forUpdate().fetchOne() == null) {
            throw new CatalogProposalException(CatalogProposalException.Reason.NOT_FOUND);
        }
        existing = reports.find(reportId);
        if (existing.isPresent()) return unchanged(existing.get(), proposalId, version);
        var report = replay.analyze(proposalId, version);
        var hash = CatalogDraftCanonicalizer.sha256(report); var r = CATALOG_IMPACT_REPORTS;
        boolean inserted = dsl.insertInto(r).set(r.ID, reportId).set(r.PROPOSAL_ID, proposalId).set(r.PROPOSAL_VERSION, version)
                .set(r.PROPOSAL_SHA256, report.proposalSha256()).set(r.REPORT_SCHEMA_VERSION, (short) 1)
                .set(r.CANONICALIZATION_VERSION, CatalogDraftCanonicalizer.VERSION).set(r.REPORT_SHA256, hash)
                .set(r.REPORT, JSONB.valueOf(mapper.writeValueAsString(report))).onConflict(r.ID).doNothing().execute() == 1;
        // The same UUID may have raced from another proposal. Never silently rebind it.
        if (!inserted) return unchanged(reports.find(reportId).orElseThrow(), proposalId, version);
        var e = CATALOG_IMPACT_REPORT_EVENTS;
        dsl.insertInto(e).set(e.ID, UUID.randomUUID()).set(e.REPORT_ID, reportId).set(e.PROPOSAL_ID, proposalId).set(e.PROPOSAL_VERSION, version)
                .set(e.REPORT_SHA256, hash).set(e.ACTION, "catalog-impact.recorded").set(e.ACTOR_TYPE, "SERVICE")
                .set(e.ACTOR_ID, "core-api-local-catalog").set(e.CORRELATION_ID, UUID.randomUUID()).set(e.OUTCOME, "SUCCEEDED").execute();
        return new SaveResult(true, reports.get(proposalId, version, reportId));
    }
    private SaveResult unchanged(CatalogImpactReport report, UUID proposalId, long version) {
        if (!report.proposalId().equals(proposalId) || report.proposalVersion() != version) {
            throw new CatalogImpactReportException(CatalogImpactReportException.Reason.ID_CONFLICT);
        }
        return new SaveResult(false, report);
    }
    public record SaveResult(boolean changed, CatalogImpactReport report) { }
}

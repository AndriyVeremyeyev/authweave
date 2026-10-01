package io.authweave.core.catalog.impact;

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
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import static io.authweave.core.generated.jooq.tables.CatalogProposals.CATALOG_PROPOSALS;
import static io.authweave.core.generated.jooq.tables.CatalogFactPathReports.CATALOG_FACT_PATH_REPORTS;
import static io.authweave.core.generated.audit.tables.CatalogFactPathReportEvents.CATALOG_FACT_PATH_REPORT_EVENTS;
import static io.authweave.core.catalog.impact.CatalogFactPathReportException.Reason.*;

/** Explicit local service write only. Caller chooses identity/revision, never JSON, trust flags or receipt time. */
@Service
@Profile("local-catalog-regression-write")
public class LocalCatalogFactPathReportWriter {
    private final DSLContext dsl;
    private final ObjectMapper mapper;
    private final CatalogImpactService impacts;
    private final CatalogFactPathReportRepository reports;
    public LocalCatalogFactPathReportWriter(DSLContext dsl, ObjectMapper mapper, CatalogImpactService impacts, CatalogFactPathReportRepository reports) {
        this.dsl = dsl; this.mapper = mapper; this.impacts = impacts; this.reports = reports;
    }
    @Transactional
    public SaveResult save(UUID reportId, UUID proposalId, long version) {
        Objects.requireNonNull(reportId); Objects.requireNonNull(proposalId); CatalogImpactReportRepository.version(version);
        var existing = reports.find(reportId); if (existing != null) return unchanged(existing, proposalId, version);
        // Same proposal-head lock as revision/scenario writers, before allocating global report numbers.
        var p = CATALOG_PROPOSALS;
        if (dsl.select(p.ID).from(p).where(p.ID.eq(proposalId)).forUpdate().fetchOne() == null) throw new CatalogFactPathReportException(NOT_FOUND);
        existing = reports.find(reportId); if (existing != null) return unchanged(existing, proposalId, version);
        var revision = reports.request(proposalId, version); if (revision == null) throw new CatalogFactPathReportException(NOT_FOUND);
        if (revision.bytes() <= 0 || revision.bytes() > CatalogFactPathReportRepository.MAX_JSON_BYTES || revision.body() == null)
            throw new CatalogFactPathReportException(READ_BUDGET_EXCEEDED);
        var at = dsl.select(org.jooq.impl.DSL.field("clock_timestamp()", OffsetDateTime.class))
                .fetchOne(0, OffsetDateTime.class).toInstant();
        CatalogChangePreviewRequest request;
        try {
            if (revision.schemaVersion() != 1 || !"PROPOSED".equals(revision.state()) || revision.recordedAt().isAfter(at.plusSeconds(30)))
                throw new IllegalArgumentException("Invalid stored revision");
            request = mapper.readValue(revision.body(), CatalogChangePreviewRequest.class);
            if (request == null || !proposalId.equals(request.proposalId()) || !revision.sha256().equals(CatalogDraftCanonicalizer.sha256(request)))
                throw new IllegalArgumentException("Stored request mismatch");
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException invalid) { throw new CatalogFactPathReportException(REPLAY_UNAVAILABLE); }
        var report = impacts.analyzeFactPathsAt(request, version, revision.sha256(), at);
        var hash = CatalogDraftCanonicalizer.sha256(report); var body = mapper.writeValueAsString(report);
        if (body.getBytes(StandardCharsets.UTF_8).length > CatalogFactPathReportRepository.MAX_JSON_BYTES) throw new CatalogFactPathReportException(REPORT_TOO_LARGE);
        var r = CATALOG_FACT_PATH_REPORTS;
        boolean inserted = dsl.insertInto(r).set(r.ID, reportId).set(r.PROPOSAL_ID, proposalId).set(r.PROPOSAL_VERSION, version)
                .set(r.PROPOSAL_SHA256, revision.sha256()).set(r.REPORT_SCHEMA_VERSION, (short) 1)
                .set(r.CANONICALIZATION_VERSION, CatalogDraftCanonicalizer.VERSION).set(r.REPORT_SHA256, hash)
                .set(r.REPORT, JSONB.valueOf(body)).onConflict(r.ID).doNothing().execute() == 1;
        if (!inserted) return unchanged(Objects.requireNonNull(reports.find(reportId)), proposalId, version);
        var e = CATALOG_FACT_PATH_REPORT_EVENTS;
        dsl.insertInto(e).set(e.ID, UUID.randomUUID()).set(e.REPORT_ID, reportId).set(e.PROPOSAL_ID, proposalId).set(e.PROPOSAL_VERSION, version)
                .set(e.REPORT_SHA256, hash).set(e.ACTION, "catalog-fact-path.recorded").set(e.ACTOR_TYPE, "SERVICE")
                .set(e.ACTOR_ID, "core-api-local-catalog").set(e.CORRELATION_ID, UUID.randomUUID()).set(e.OUTCOME, "SUCCEEDED").execute();
        return new SaveResult(true, reports.get(proposalId, version, reportId));
    }
    private SaveResult unchanged(CatalogFactPathReportRepository.Row row, UUID proposalId, long version) {
        if (!proposalId.equals(row.proposalId()) || version != row.version()) throw new CatalogFactPathReportException(ID_CONFLICT);
        return new SaveResult(false, reports.snapshot(row));
    }
    public record SaveResult(boolean changed, CatalogImpactReport report) { }
}

package io.authweave.core.catalog.proposal;

import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.catalog.draft.CatalogChangePreview;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import io.authweave.core.catalog.draft.CatalogChangePreviewService;
import static io.authweave.core.catalog.proposal.CatalogProposalException.Reason.*;
import static io.authweave.core.generated.jooq.tables.CatalogProposals.CATALOG_PROPOSALS;
import static io.authweave.core.generated.jooq.tables.CatalogProposalRevisions.CATALOG_PROPOSAL_REVISIONS;
import static io.authweave.core.generated.audit.tables.CatalogProposalEvents.CATALOG_PROPOSAL_EVENTS;
import static org.jooq.impl.DSL.currentOffsetDateTime;

/** Explicit local development write boundary. No HTTP mapping, curator decision or catalog activation. */
@Service
@Profile("local-catalog-write")
public class LocalCatalogProposalWriter {
    private final DSLContext dsl;
    private final ObjectMapper mapper;
    private final CatalogChangePreviewService previews;
    private final CatalogProposalRepository repository;
    public LocalCatalogProposalWriter(DSLContext dsl, ObjectMapper mapper,
            CatalogChangePreviewService previews, CatalogProposalRepository repository) {
        this.dsl = dsl; this.mapper = mapper; this.previews = previews; this.repository = repository;
    }

    @Transactional
    public SaveResult save(CatalogChangePreviewRequest request, Long expectedVersion) {
        if (expectedVersion != null && (expectedVersion < 0 || expectedVersion > 9007199254740991L)) {
            throw new IllegalArgumentException("Use a non-negative safe integer version");
        }
        var p = CATALOG_PROPOSALS; var r = CATALOG_PROPOSAL_REVISIONS; var e = CATALOG_PROPOSAL_EVENTS;
        boolean created = expectedVersion == null && dsl.insertInto(p).set(p.ID, request.proposalId()).set(p.VERSION, 0L)
                .onConflict(p.ID).doNothing().execute() == 1;
        // Serialize updates and creation retries for this ID, including concurrent commands.
        var head = dsl.selectFrom(p).where(p.ID.eq(request.proposalId())).forUpdate().fetchOne();
        if (head == null) throw new CatalogProposalException(NOT_FOUND);
        long version = head.getVersion();
        if (!created && ((expectedVersion == null && version != 0)
                || (expectedVersion != null && expectedVersion != version))) throw new CatalogProposalException(VERSION_CONFLICT);

        var preview = previews.preview(request);
        if (!created) {
            var current = repository.current(request.proposalId());
            if (current.proposalSha256().equals(preview.proposalSha256())) return new SaveResult(false, current);
            // Missing expectedVersion means create/retry, never implicit overwrite.
            if (expectedVersion == null) throw new CatalogProposalException(VERSION_CONFLICT);
        }
        if (preview.status() != CatalogChangePreview.Status.REVIEW_REQUIRED) throw new CatalogProposalException(NOT_REVIEWABLE);
        if (!created && version == 9007199254740991L) throw new CatalogProposalException(VERSION_EXHAUSTED);
        long next = created ? 0 : version + 1;
        if (!created) dsl.update(p).set(p.VERSION, next).set(p.UPDATED_AT, currentOffsetDateTime()).where(p.ID.eq(request.proposalId())).execute();

        dsl.insertInto(r).set(r.PROPOSAL_ID, request.proposalId()).set(r.VERSION, next).set(r.STATE, "PROPOSED")
                .set(r.REQUEST_SCHEMA_VERSION, (short) request.schemaVersion()).set(r.PROPOSAL_SHA256, preview.proposalSha256())
                .set(r.REQUEST, JSONB.valueOf(mapper.writeValueAsString(request)))
                .set(r.PREVIEW, JSONB.valueOf(mapper.writeValueAsString(preview))).execute();
        dsl.insertInto(e).set(e.ID, UUID.randomUUID()).set(e.PROPOSAL_ID, request.proposalId()).set(e.VERSION, next)
                .set(e.PREVIOUS_VERSION, created ? null : version).set(e.ACTION, created ? "catalog-proposal.created" : "catalog-proposal.revised")
                .set(e.ACTOR_TYPE, "SERVICE").set(e.ACTOR_ID, "core-api-local-catalog").set(e.CORRELATION_ID, UUID.randomUUID())
                .set(e.OUTCOME, "SUCCEEDED").set(e.PROPOSAL_SHA256, preview.proposalSha256()).execute();
        return new SaveResult(true, repository.current(request.proposalId()));
    }
    public record SaveResult(boolean changed, CatalogProposalSnapshot proposal) { }
}

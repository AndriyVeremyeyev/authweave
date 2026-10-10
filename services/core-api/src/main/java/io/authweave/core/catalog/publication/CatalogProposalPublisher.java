package io.authweave.core.catalog.publication;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.impact.*;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;
import static io.authweave.core.catalog.publication.CatalogProposalPublicationException.Reason.*;
import static io.authweave.core.generated.jooq.tables.CatalogProposals.CATALOG_PROPOSALS;
import io.authweave.core.catalog.publication.CatalogProposalPublicationReader.Proof;

/** Opt-in first successor only. Serialized current-head/latest-review checks precede fresh Core analysis. */
@Service
@org.springframework.context.annotation.Profile("catalog-proposal-publication")
public class CatalogProposalPublisher {
    public static final String POLICY_VERSION = CatalogProposalPublicationReader.PUBLICATION_POLICY_VERSION;
    private final DSLContext dsl;
    private final CatalogBootstrapReviewRepository boundary;
    private final CatalogPublicationPreflightRepository registry;
    private final CatalogPublicationRepository publications;
    private final CatalogBootstrapPublicationReader roots;
    private final CatalogProposalPublicationReader reader;
    private final StoredProposalDecisionService proposals;
    private final CatalogDraftValidator drafts;
    private final CatalogSnapshotInspector inspector;
    private final PublishedProposalDecisionCoverageService coverage;
    private final ObjectMapper mapper;
    public CatalogProposalPublisher(DSLContext dsl, CatalogBootstrapReviewRepository boundary, CatalogPublicationPreflightRepository registry,
            CatalogPublicationRepository publications, CatalogBootstrapPublicationReader roots, CatalogProposalPublicationReader reader,
            StoredProposalDecisionService proposals, CatalogDraftValidator drafts, CatalogSnapshotInspector inspector,
            PublishedProposalDecisionCoverageService coverage, ObjectMapper mapper) {
        this.dsl = dsl; this.boundary = boundary; this.registry = registry; this.publications = publications; this.roots = roots;
        this.reader = reader; this.proposals = proposals; this.drafts = drafts; this.inspector = inspector; this.coverage = coverage; this.mapper = mapper;
    }
    public record Receipt(PublishedCatalogSnapshot.Reference snapshot, UUID decisionId, Instant publishedAt,
            PublishedCatalogSnapshot.Reference before, StoredProposalDecisionService.Reference proposal,
            String policyVersion, String proofSha256, String coverageManifestSha256, String coverageScope,
            List<CatalogProfilePlanningCoverageService.VerificationGap> verificationGaps,
            boolean publicationRecorded, boolean evaluationReady, boolean externalSourceVerificationPerformed) {
        public Receipt { verificationGaps = List.copyOf(verificationGaps); }
    }
    public record Result(Receipt receipt, boolean created) { }
    @Transactional
    public Result publish(CatalogProposalPublicationRequest request, CuratorActor actor) {
        Objects.requireNonNull(request); Objects.requireNonNull(actor);
        boundary.lockBoundary(); actor(actor, now());
        var stored = reader.stored(request.publicationId());
        if (stored != null) {
            var proof = verified(stored);
            var event = publications.find(request.publicationId(), CatalogPublicationLookup.MAX_READ_BYTES).event();
            if (!proof.request().equals(request) || !sameActor(event, actor)) fail(CONFLICT);
            actor(actor, now()); return new Result(receipt(proof, stored.getProofSha256()), false);
        }
        if (publications.find(request.publicationId(), CatalogPublicationLookup.MAX_READ_BYTES) != null) fail(CONFLICT);
        var pin = request.proposal().revision(); var p = CATALOG_PROPOSALS;
        var head = dsl.select(p.VERSION).from(p).where(p.ID.eq(pin.proposalId())).forUpdate().fetchOne();
        if (head == null || head.value1() != pin.version()) fail(CONFLICT);
        var revision = registry.proposal(pin.proposalId(), pin.version());
        if (revision == null || revision.rejected() || revision.published() || !pin.proposalSha256().equals(revision.sha256())
                || publications.successors(request.before().snapshotId()) != 0) fail(CONFLICT);
        try {
            roots.load(request.before()); // Policy 1 cannot publish a successor of a successor.
            if (!proposals.pin(pin, request.proposal().auditability()).equals(request.proposal())) fail(CONFLICT);
            var inputs = proposals.load(request.proposal()); var at = now();
            var draft = mapper.treeToValue(inputs.snapshot().catalog(), ProviderCatalogDraft.class);
            if (registry.labelUsed(draft.catalogVersion())) fail(CONFLICT);
            var validation = drafts.validateAt(draft, at);
            if (validation.status() != CatalogDraftValidation.Status.VALID_DRAFT) fail(SOURCE_NOT_ELIGIBLE);
            var checked = coverage.replay(request.before(), request.proposal(), at);
            if (!checked.decisionScopeCoverageComplete() || !checked.storedSourceReviewsVerified()) fail(COVERAGE_INCOMPLETE);
            if (!checked.candidateClaims().allRecordedClaimsSupportedAndCurrent()) fail(SOURCE_NOT_ELIGIBLE);
            var content = new PublishedCatalogSnapshot.Content(1, draft.catalogVersion(), draft.options());
            var statuses = validation.facts().stream().map(f -> new PublishedCatalogSnapshot.FactStatus(f.optionId(), f.path(),
                    PublishedCatalogSnapshot.DeclaredEvidenceStatus.REVIEWED)).toList();
            var unsigned = new PublishedCatalogSnapshot(1, PublishedCatalogSnapshot.Kind.PUBLISHED_PROVIDER_CATALOG_SNAPSHOT,
                    request.publicationId(), CatalogDraftCanonicalizer.VERSION, content, CatalogDraftCanonicalizer.sha256(content), "0".repeat(64),
                    request.before(), new PublishedCatalogSnapshot.Publication(UUID.randomUUID(), at), statuses);
            var snapshot = new PublishedCatalogSnapshot(1, unsigned.kind(), unsigned.snapshotId(), unsigned.canonicalizationVersion(), content,
                    unsigned.contentSha256(), unsigned.computedSnapshotSha256(), request.before(), unsigned.publication(), statuses);
            if (inspector.inspect(snapshot).status() != CatalogSnapshotInspector.Status.VALID_SNAPSHOT_FORMAT) fail(SOURCE_NOT_ELIGIBLE);
            var ref = new PublishedCatalogSnapshot.Reference(snapshot.snapshotId(), content.catalogVersion(), snapshot.snapshotSha256());
            var proof = new Proof(POLICY_VERSION, request, hash(request), ref, snapshot.publication().decisionId(), at, checked);
            String digest = hash(proof), manifest = mapper.writeValueAsString(snapshot), proofJson = mapper.writeValueAsString(proof);
            if (boundary.jsonBytes(manifest) > CatalogPublicationRepository.MAX_JSON_BYTES || boundary.jsonBytes(proofJson) > CatalogPublicationRepository.MAX_JSON_BYTES)
                fail(COVERAGE_INCOMPLETE);
            actor(actor, now());
            dsl.fetch("select core.publish_catalog_proposal(?::jsonb, ?::jsonb, ?, ?::jsonb)", manifest, proofJson, digest, mapper.writeValueAsString(actor));
            var saved = reader.stored(request.publicationId());
            return new Result(receipt(verified(saved), saved.getProofSha256()), true);
        } catch (CatalogPublishedLoadingException | CatalogProposalDecisionLoadingException | CatalogBootstrapReviewException
                | CatalogBootstrapPublicationException | io.authweave.core.catalog.auditability.CatalogAuditabilityReviewException
                | tools.jackson.core.JacksonException | IllegalArgumentException invalid) {
            throw new CatalogProposalPublicationException(SOURCE_NOT_ELIGIBLE);
        }
    }
    private Proof verified(io.authweave.core.generated.jooq.tables.records.CatalogProposalPublicationsRecord row) {
        try { return reader.verified(row).proof(); }
        catch (CatalogPublishedLoadingException invalid) { throw new CatalogProposalPublicationException(STORED_PUBLICATION_INVALID); }
    }
    private Instant now() { return dsl.fetchOne("select clock_timestamp()").get(0, OffsetDateTime.class).toInstant(); }
    private String hash(Object value) { return DecisionCanonicalizer.sha256(mapper.valueToTree(value)); }
    private static Receipt receipt(Proof p, String digest) {
        return new Receipt(p.snapshot(), p.decisionId(), p.publishedAt(), p.request().before(), p.request().proposal(), POLICY_VERSION, digest,
                p.coverage().manifestSha256(), p.coverage().scope(), p.coverage().verificationGaps(), true, false, false);
    }
    private static boolean sameActor(CatalogPublicationRecord.Event e, CuratorActor a) {
        return e != null && e.issuer().equals(a.issuer()) && e.subject().equals(a.subject()) && e.projectId().equals(a.projectId()) && e.orgId().equals(a.organizationId());
    }
    private static void actor(CuratorActor a, Instant at) {
        if (a.issuer() == null || a.issuer().isBlank() || a.issuer().length() > 2048 || a.subject() == null || a.subject().isBlank() || a.subject().length() > 256
                || a.projectId() == null || !a.projectId().matches("[0-9]{1,40}") || a.organizationId() == null || !a.organizationId().matches("[0-9]{1,40}")
                || a.authenticatedAt() == null || a.authenticatedAt().isBefore(at.minusSeconds(900)) || a.authenticatedAt().isAfter(at.plusSeconds(30))) fail(AUTHENTICATION_EXPIRED);
    }
    private static void fail(CatalogProposalPublicationException.Reason r) { throw new CatalogProposalPublicationException(r); }
}

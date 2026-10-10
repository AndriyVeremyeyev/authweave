package io.authweave.core.catalog.publication;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.impact.*;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;
import static io.authweave.core.catalog.publication.CatalogBootstrapPublicationException.Reason.*;
import io.authweave.core.catalog.publication.CatalogBootstrapPublicationReader.Proof;

/** Opt-in first publication only. HTTP establishes current authority; Core reloads/replays inputs inside the serialized transaction. */
@Service
@Profile("catalog-bootstrap-publication")
public class CatalogBootstrapPublisher {
    public static final String POLICY_VERSION = CatalogBootstrapPublicationReader.PUBLICATION_POLICY_VERSION;
    private final DSLContext dsl;
    private final CatalogBootstrapReviewRepository boundary;
    private final CatalogBootstrapReviewService reviews;
    private final CatalogPublicationPreflightRepository registry;
    private final CatalogPublicationRepository publications;
    private final CatalogBootstrapPublicationReader reader;
    private final CatalogSnapshotInspector inspector;
    private final DecisionPublicationCoverageService coverage;
    private final ObjectMapper mapper;

    public CatalogBootstrapPublisher(DSLContext dsl, CatalogBootstrapReviewRepository boundary, CatalogBootstrapReviewService reviews,
            CatalogPublicationPreflightRepository registry, CatalogPublicationRepository publications,
            CatalogBootstrapPublicationReader reader, CatalogSnapshotInspector inspector,
            DecisionPublicationCoverageService coverage, ObjectMapper mapper) {
        this.dsl = dsl; this.boundary = boundary; this.reviews = reviews; this.registry = registry;
        this.publications = publications; this.reader = reader; this.inspector = inspector; this.coverage = coverage; this.mapper = mapper;
    }
    public record Receipt(PublishedCatalogSnapshot.Reference snapshot, UUID decisionId, Instant publishedAt,
            StoredCandidateDecisionService.Reference source, String policyVersion, String proofSha256, String coverageManifestSha256,
            String coverageScope, List<CatalogProfilePlanningCoverageService.VerificationGap> verificationGaps,
            boolean publicationRecorded, boolean evaluationReady, boolean externalSourceVerificationPerformed) {
        public Receipt { verificationGaps = List.copyOf(verificationGaps); }
    }
    public record Result(Receipt receipt, boolean created) { }

    @Transactional
    public Result publish(CatalogBootstrapPublicationRequest request, CuratorActor actor) {
        Objects.requireNonNull(request); Objects.requireNonNull(actor);
        // READ_COMMITTED: take the shared boundary before any reads so a waiter sees the committed winner.
        boundary.lockBoundary();
        actor(actor, now());
        var stored = reader.stored(request.publicationId());
        if (stored != null) {
            var proof = verified(stored);
            var event = publications.find(request.publicationId(), CatalogPublicationLookup.MAX_READ_BYTES).event();
            if (!proof.request().equals(request) || !sameActor(event, actor)) fail(CONFLICT);
            // Retry validates historical evidence at the original clock, not today's freshness, but current reauth remains required.
            actor(actor, now());
            return new Result(receipt(proof, stored.getProofSha256()), false);
        }
        if (!registry.registryEmpty()) fail(CONFLICT);
        var source = reviews.loadForDecision(request.source().reviewId(), request.source().reviewSha256());
        var at = now(); var raw = mapper.readTree(source.candidateJson());
        if (!request.source().decisionCatalogSha256().equals(DecisionCanonicalizer.sha256(raw))) fail(CONFLICT);
        var draft = mapper.treeToValue(raw, ProviderCatalogDraft.class);
        var validation = reader.eligible(request.source(), at);
        var checked = coverage.replay(request.source(), request.source(), at);
        if (!checked.decisionScopeCoverageComplete() || !checked.storedSourceReviewsVerified()) fail(COVERAGE_INCOMPLETE);
        var content = new PublishedCatalogSnapshot.Content(1, draft.catalogVersion(), draft.options());
        var facts = validation.facts().stream().map(f -> new PublishedCatalogSnapshot.FactStatus(f.optionId(), f.path(),
                PublishedCatalogSnapshot.DeclaredEvidenceStatus.REVIEWED)).toList();
        var unsigned = new PublishedCatalogSnapshot(1, PublishedCatalogSnapshot.Kind.PUBLISHED_PROVIDER_CATALOG_SNAPSHOT,
                request.publicationId(), CatalogDraftCanonicalizer.VERSION, content, CatalogDraftCanonicalizer.sha256(content), "0".repeat(64), null,
                new PublishedCatalogSnapshot.Publication(UUID.randomUUID(), at), facts);
        var snapshot = new PublishedCatalogSnapshot(1, unsigned.kind(), unsigned.snapshotId(), unsigned.canonicalizationVersion(), content,
                unsigned.contentSha256(), unsigned.computedSnapshotSha256(), null, unsigned.publication(), facts);
        if (inspector.inspect(snapshot).status() != CatalogSnapshotInspector.Status.VALID_SNAPSHOT_FORMAT) fail(SOURCE_NOT_ELIGIBLE);
        var reference = new PublishedCatalogSnapshot.Reference(snapshot.snapshotId(), content.catalogVersion(), snapshot.snapshotSha256());
        var proof = new Proof(POLICY_VERSION, request, hash(request), reference, snapshot.publication().decisionId(), at, checked);
        String digest = hash(proof), manifest = mapper.writeValueAsString(snapshot), proofJson = mapper.writeValueAsString(proof);
        if (boundary.jsonBytes(manifest) > CatalogPublicationRepository.MAX_JSON_BYTES || boundary.jsonBytes(proofJson) > CatalogPublicationRepository.MAX_JSON_BYTES)
            fail(COVERAGE_INCOMPLETE);
        // Recheck after potentially expensive analysis. SQL checks freshness once more immediately before inserts.
        actor(actor, now());
        dsl.fetch("select core.publish_catalog_bootstrap(?::jsonb, ?::jsonb, ?, ?::jsonb)",
                manifest, proofJson, digest, mapper.writeValueAsString(actor));
        var saved = reader.stored(request.publicationId());
        return new Result(receipt(verified(saved), saved.getProofSha256()), true);
    }

    private Proof verified(io.authweave.core.generated.jooq.tables.records.CatalogBootstrapPublicationsRecord row) {
        try { return reader.verified(row).proof(); }
        catch (CatalogPublishedLoadingException invalid) {
            throw new CatalogBootstrapPublicationException(STORED_PUBLICATION_INVALID);
        }
    }
    private Instant now() { return dsl.fetchOne("select clock_timestamp()").get(0, OffsetDateTime.class).toInstant(); }
    private String hash(Object value) { return DecisionCanonicalizer.sha256(mapper.valueToTree(value)); }
    private static Receipt receipt(Proof p, String digest) {
        return new Receipt(p.snapshot(), p.decisionId(), p.publishedAt(), p.request().source(), POLICY_VERSION, digest,
                p.coverage().manifestSha256(), p.coverage().scope(), p.coverage().verificationGaps(), true, false, false);
    }
    private static boolean sameActor(CatalogPublicationRecord.Event e, CuratorActor actor) {
        return e != null && e.issuer().equals(actor.issuer()) && e.subject().equals(actor.subject())
                && e.projectId().equals(actor.projectId()) && e.orgId().equals(actor.organizationId());
    }
    private static void actor(CuratorActor actor, Instant at) {
        if (actor.issuer() == null || actor.issuer().isBlank() || actor.issuer().length() > 2048
                || actor.subject() == null || actor.subject().isBlank() || actor.subject().length() > 256
                || actor.projectId() == null || !actor.projectId().matches("[0-9]{1,40}")
                || actor.organizationId() == null || !actor.organizationId().matches("[0-9]{1,40}")
                || actor.authenticatedAt() == null || actor.authenticatedAt().isBefore(at.minusSeconds(900))
                || actor.authenticatedAt().isAfter(at.plusSeconds(30))) fail(AUTHENTICATION_EXPIRED);
    }
    private static void fail(CatalogBootstrapPublicationException.Reason reason) { throw new CatalogBootstrapPublicationException(reason); }
}

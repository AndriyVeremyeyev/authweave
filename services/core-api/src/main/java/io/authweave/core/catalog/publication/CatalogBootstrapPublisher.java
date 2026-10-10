package io.authweave.core.catalog.publication;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.catalog.auditability.CatalogAuditabilityReviewService;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.impact.*;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;
import static io.authweave.core.catalog.publication.CatalogBootstrapPublicationException.Reason.*;
import static io.authweave.core.generated.jooq.tables.CatalogBootstrapPublications.CATALOG_BOOTSTRAP_PUBLICATIONS;

/** Opt-in first publication only. HTTP establishes current authority; Core reloads/replays inputs inside the serialized transaction. */
@Service
@Profile("catalog-bootstrap-publication")
public class CatalogBootstrapPublisher {
    public static final String POLICY_VERSION = "catalog-bootstrap-publication-1";
    private final DSLContext dsl;
    private final CatalogBootstrapReviewRepository boundary;
    private final CatalogBootstrapReviewService reviews;
    private final CatalogAuditabilityReviewService audits;
    private final CatalogPublicationPreflightRepository registry;
    private final CatalogPublicationRepository publications;
    private final CatalogPublicationLookup lookup;
    private final CatalogDraftValidator drafts;
    private final CatalogSnapshotInspector inspector;
    private final DecisionPublicationCoverageService coverage;
    private final ObjectMapper mapper;

    public CatalogBootstrapPublisher(DSLContext dsl, CatalogBootstrapReviewRepository boundary, CatalogBootstrapReviewService reviews,
            CatalogAuditabilityReviewService audits, CatalogPublicationPreflightRepository registry, CatalogPublicationRepository publications,
            CatalogPublicationLookup lookup, CatalogDraftValidator drafts, CatalogSnapshotInspector inspector,
            DecisionPublicationCoverageService coverage, ObjectMapper mapper) {
        this.dsl = dsl; this.boundary = boundary; this.reviews = reviews; this.audits = audits; this.registry = registry;
        this.publications = publications; this.lookup = lookup; this.drafts = drafts; this.inspector = inspector; this.coverage = coverage; this.mapper = mapper;
    }
    public record Receipt(PublishedCatalogSnapshot.Reference snapshot, UUID decisionId, Instant publishedAt,
            StoredCandidateDecisionService.Reference source, String policyVersion, String proofSha256, String coverageManifestSha256,
            String coverageScope, List<CatalogProfilePlanningCoverageService.VerificationGap> verificationGaps,
            boolean publicationRecorded, boolean evaluationReady, boolean externalSourceVerificationPerformed) {
        public Receipt { verificationGaps = List.copyOf(verificationGaps); }
    }
    public record Result(Receipt receipt, boolean created) { }
    record Proof(String policyVersion, CatalogBootstrapPublicationRequest request, String requestSha256,
            PublishedCatalogSnapshot.Reference snapshot, UUID decisionId, Instant publishedAt,
            DecisionPublicationCoverageService.Check coverage) { }

    @Transactional
    public Result publish(CatalogBootstrapPublicationRequest request, CuratorActor actor) {
        Objects.requireNonNull(request); Objects.requireNonNull(actor);
        // READ_COMMITTED: take the shared boundary before any reads so a waiter sees the committed winner.
        boundary.lockBoundary();
        actor(actor, now());
        var stored = stored(request.publicationId());
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
        var validation = eligible(request.source(), at);
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
        var saved = stored(request.publicationId());
        return new Result(receipt(verified(saved), saved.getProofSha256()), true);
    }

    private io.authweave.core.generated.jooq.tables.records.CatalogBootstrapPublicationsRecord stored(UUID id) {
        var p = CATALOG_BOOTSTRAP_PUBLICATIONS;
        // No unbounded JSON read even when storage has been modified by an administrative actor.
        return dsl.select(p.ID, p.DECISION_ID, p.REQUEST_SHA256, p.REVIEW_ID, p.CANDIDATE_SHA256, p.REVIEW_SHA256, p.PROOF_SHA256, p.RECORDED_AT)
                .select(DSL.when(DSL.octetLength(p.PROOF.cast(String.class)).le((int) CatalogPublicationRepository.MAX_JSON_BYTES), p.PROOF)
                    .otherwise((JSONB) null).as(p.PROOF)).from(p).where(p.ID.eq(id)).fetchOneInto(p);
    }
    private Proof verified(io.authweave.core.generated.jooq.tables.records.CatalogBootstrapPublicationsRecord row) {
        try {
            if (row == null || row.getProof() == null) throw new IllegalArgumentException();
            var proof = mapper.readValue(row.getProof().data(), Proof.class);
            var result = lookup.lookup(proof.snapshot());
            if (!POLICY_VERSION.equals(proof.policyVersion()) || !row.getId().equals(proof.request().publicationId())
                    || !row.getId().equals(proof.snapshot().snapshotId()) || !row.getDecisionId().equals(proof.decisionId())
                    || !row.getRequestSha256().equals(hash(proof.request())) || !row.getRequestSha256().equals(proof.requestSha256())
                    || !row.getProofSha256().equals(hash(proof)) || !row.getReviewId().equals(proof.request().source().reviewId())
                    || !row.getReviewSha256().equals(proof.request().source().reviewSha256())
                    || !result.storedIntegrityValidated() || result.snapshot().previousSnapshot() != null
                    || !proof.decisionId().equals(result.snapshot().publication().decisionId())
                    || !proof.publishedAt().equals(result.snapshot().publication().publishedAt()) || row.getRecordedAt() == null
                    || row.getRecordedAt().toInstant().isBefore(proof.publishedAt().minusSeconds(30))
                    || row.getRecordedAt().toInstant().isAfter(proof.publishedAt().plusSeconds(30))) throw new IllegalArgumentException();
            var source = reviews.loadForDecision(row.getReviewId(), row.getReviewSha256());
            if (!row.getCandidateSha256().equals(source.review().candidateSha256())
                    || !hash(mapper.treeToValue(mapper.readTree(source.candidateJson()), ProviderCatalogDraft.class))
                        .equals(hash(result.snapshot().catalog().asDraft()))) throw new IllegalArgumentException();
            eligible(proof.request().source(), proof.publishedAt());
            var replayed = coverage.replay(proof.request().source(), proof.request().source(), proof.publishedAt());
            if (!replayed.decisionScopeCoverageComplete() || !replayed.equals(proof.coverage())) throw new IllegalArgumentException();
            return proof;
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException | CatalogBootstrapReviewException
                | io.authweave.core.catalog.auditability.CatalogAuditabilityReviewException | CatalogBootstrapPublicationException invalid) {
            throw new CatalogBootstrapPublicationException(STORED_PUBLICATION_INVALID);
        }
    }
    private CatalogDraftValidation eligible(StoredCandidateDecisionService.Reference pin, Instant at) {
        var base = reviews.loadForDecision(pin.reviewId(), pin.reviewSha256());
        var raw = mapper.readTree(base.candidateJson());
        var validation = drafts.validateAt(mapper.treeToValue(raw, ProviderCatalogDraft.class), at);
        if (!pin.decisionCatalogSha256().equals(DecisionCanonicalizer.sha256(raw)) || base.review().recordedAt().isAfter(at.plusSeconds(30))
                || base.review().counts().supporting() != base.review().factCount() || validation.status() != CatalogDraftValidation.Status.VALID_DRAFT
                || validation.facts().stream().anyMatch(f -> f.freshness() != CatalogDraftValidation.Freshness.CURRENT)) fail(SOURCE_NOT_ELIGIBLE);
        if (pin.auditability() != null) {
            var auditPin = pin.auditability(); var audit = audits.loadForDecision(auditPin.reviewId(), auditPin.reviewSha256());
            var candidate = mapper.readTree(audit.candidateJson()); var supplement = candidate.get("auditabilityDraft");
            if (!pin.decisionCatalogSha256().equals(DecisionCanonicalizer.sha256(candidate.get("baseDraft")))
                    || !auditPin.decisionSupplementSha256().equals(DecisionCanonicalizer.sha256(supplement))
                    || audit.review().recordedAt().isAfter(at.plusSeconds(30)) || audit.review().counts().supporting() != audit.review().factCount()) fail(SOURCE_NOT_ELIGIBLE);
            for (var option : supplement.get("options")) for (var fact : option.get("facts")) {
                var observed = Instant.parse(fact.get("evidence").get("observedAt").asText());
                if (observed.isAfter(at) || observed.isBefore(at.minus(io.authweave.core.evaluation.EvidencePolicy.MAX_AGE))) fail(SOURCE_NOT_ELIGIBLE);
            }
        }
        return validation;
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

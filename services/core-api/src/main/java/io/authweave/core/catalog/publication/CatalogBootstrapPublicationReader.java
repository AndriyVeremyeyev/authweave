package io.authweave.core.catalog.publication;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.catalog.auditability.CatalogAuditabilityReviewService;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.impact.*;
import io.authweave.core.generated.jooq.tables.records.CatalogBootstrapPublicationsRecord;
import static io.authweave.core.catalog.publication.CatalogPublishedLoadingException.Reason.*;
import static io.authweave.core.generated.jooq.tables.CatalogBootstrapPublications.CATALOG_BOOTSTRAP_PUBLICATIONS;

/** Historical workflow verification, independent of the opt-in writer. Exact references only;
 * never an active-head selector, a source fetch, a permission grant or an assessment result. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CatalogBootstrapPublicationReader {
    public static final String VERSION = "published-bootstrap-loading-1";
    public static final String PUBLICATION_POLICY_VERSION = "catalog-bootstrap-publication-1";
    private final DSLContext dsl;
    private final CatalogBootstrapReviewService reviews;
    private final CatalogAuditabilityReviewService audits;
    private final CatalogPublicationLookup lookup;
    private final CatalogPublicationRepository publications;
    private final CatalogDraftValidator drafts;
    private final DecisionPublicationCoverageService coverage;
    private final ObjectMapper mapper;

    public CatalogBootstrapPublicationReader(DSLContext dsl, CatalogBootstrapReviewService reviews,
            CatalogAuditabilityReviewService audits, CatalogPublicationLookup lookup, CatalogPublicationRepository publications,
            CatalogDraftValidator drafts, DecisionPublicationCoverageService coverage, ObjectMapper mapper) {
        this.dsl = dsl; this.reviews = reviews; this.audits = audits; this.lookup = lookup;
        this.publications = publications; this.drafts = drafts; this.coverage = coverage; this.mapper = mapper;
    }
    // Preserve the existing proof bytes/digest policy. Unknown or incomplete fields are not a new policy version.
    record Proof(String policyVersion, CatalogBootstrapPublicationRequest request, String requestSha256,
            PublishedCatalogSnapshot.Reference snapshot, UUID decisionId, Instant publishedAt,
            DecisionPublicationCoverageService.Check coverage) {
        Proof {
            if (policyVersion == null || request == null || requestSha256 == null || snapshot == null
                    || decisionId == null || publishedAt == null || coverage == null) throw new IllegalArgumentException("Incomplete publication proof");
        }
    }
    record Verified(Proof proof, PublishedCatalogSnapshot snapshot, String proofSha256) { }

    /** Internal immutable material; only this verifier constructs it. Not a response DTO or reusable approval token. */
    public static final class Loaded {
        private final Verified verified;
        private Loaded(Verified verified) { this.verified = verified; }
        public PublishedCatalogSnapshot snapshot() { return verified.snapshot(); }
        public PublishedCatalogSnapshot.Reference reference() { return verified.proof().snapshot(); }
        public StoredCandidateDecisionService.Reference source() { return verified.proof().request().source(); }
        public String proofSha256() { return verified.proofSha256(); }
        public String publicationPolicyVersion() { return verified.proof().policyVersion(); }
        public String coverageManifestSha256() { return verified.proof().coverage().manifestSha256(); }
        public List<CatalogProfilePlanningCoverageService.VerificationGap> verificationGaps() { return verified.proof().coverage().verificationGaps(); }
        public String loaderVersion() { return VERSION; }
        public boolean historicalPublicationWorkflowVerified() { return true; }
        public boolean externalSourceVerificationPerformed() { return false; }
        public boolean assessmentResultPinned() { return false; }
    }

    public Loaded load(PublishedCatalogSnapshot.Reference reference) {
        Objects.requireNonNull(reference);
        var row = stored(reference.snapshotId());
        if (row == null) throw new CatalogPublishedLoadingException(PUBLICATION_PROOF_NOT_FOUND);
        var verified = verified(row);
        if (!reference.equals(verified.proof().snapshot())) throw new CatalogPublishedLoadingException(REFERENCE_MISMATCH);
        return new Loaded(verified);
    }

    // Writer shares precisely the same bounded read and verification, inside its serialized transaction.
    CatalogBootstrapPublicationsRecord stored(UUID id) {
        var p = CATALOG_BOOTSTRAP_PUBLICATIONS;
        return dsl.select(p.ID, p.DECISION_ID, p.REQUEST_SHA256, p.REVIEW_ID, p.CANDIDATE_SHA256, p.REVIEW_SHA256, p.PROOF_SHA256, p.RECORDED_AT)
                .select(DSL.when(DSL.octetLength(p.PROOF.cast(String.class)).le((int) CatalogPublicationRepository.MAX_JSON_BYTES), p.PROOF)
                    .otherwise((JSONB) null).as(p.PROOF)).from(p).where(p.ID.eq(id)).fetchOneInto(p);
    }
    Verified verified(CatalogBootstrapPublicationsRecord row) {
        try {
            if (row == null || row.getProof() == null) throw new IllegalArgumentException();
            var proof = mapper.readValue(row.getProof().data(), Proof.class);
            if (proof == null) throw new IllegalArgumentException();
            var result = lookup.lookup(proof.snapshot());
            if (!PUBLICATION_POLICY_VERSION.equals(proof.policyVersion()) || !row.getId().equals(proof.request().publicationId())
                    || !row.getId().equals(proof.snapshot().snapshotId()) || !row.getDecisionId().equals(proof.decisionId())
                    || !row.getRequestSha256().equals(hash(proof.request())) || !row.getRequestSha256().equals(proof.requestSha256())
                    || !row.getProofSha256().equals(hash(proof)) || !row.getReviewId().equals(proof.request().source().reviewId())
                    || !row.getReviewSha256().equals(proof.request().source().reviewSha256())
                    || !result.storedIntegrityValidated() || result.snapshot().previousSnapshot() != null
                    || !proof.decisionId().equals(result.snapshot().publication().decisionId())
                    || !proof.publishedAt().equals(result.snapshot().publication().publishedAt()) || row.getRecordedAt() == null
                    || row.getRecordedAt().toInstant().isBefore(proof.publishedAt().minusSeconds(30))
                    || row.getRecordedAt().toInstant().isAfter(proof.publishedAt().plusSeconds(30))) throw new IllegalArgumentException();
            var event = publications.find(row.getId(), CatalogPublicationLookup.MAX_READ_BYTES).event();
            if (event == null || event.occurredAt().isBefore(proof.publishedAt().minusSeconds(30))
                    || event.occurredAt().isAfter(proof.publishedAt().plusSeconds(30))) throw new IllegalArgumentException();
            var source = reviews.loadForDecision(row.getReviewId(), row.getReviewSha256());
            if (!row.getCandidateSha256().equals(source.review().candidateSha256())
                    || !hash(mapper.treeToValue(mapper.readTree(source.candidateJson()), ProviderCatalogDraft.class))
                        .equals(hash(result.snapshot().catalog().asDraft()))) throw new IllegalArgumentException();
            eligible(proof.request().source(), proof.publishedAt());
            var replayed = coverage.replay(proof.request().source(), proof.request().source(), proof.publishedAt());
            if (!replayed.decisionScopeCoverageComplete() || !replayed.equals(proof.coverage())) throw new IllegalArgumentException();
            return new Verified(proof, result.snapshot(), row.getProofSha256());
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException | CatalogBootstrapReviewException
                | io.authweave.core.catalog.auditability.CatalogAuditabilityReviewException | CatalogBootstrapPublicationException invalid) {
            throw new CatalogPublishedLoadingException(STORED_PUBLICATION_INVALID);
        }
        // Database outages propagate. They are not missing proofs and must never select a synthetic fallback.
    }
    CatalogDraftValidation eligible(StoredCandidateDecisionService.Reference pin, Instant at) {
        var base = reviews.loadForDecision(pin.reviewId(), pin.reviewSha256());
        var raw = mapper.readTree(base.candidateJson());
        var validation = drafts.validateAt(mapper.treeToValue(raw, ProviderCatalogDraft.class), at);
        if (!pin.decisionCatalogSha256().equals(DecisionCanonicalizer.sha256(raw)) || base.review().recordedAt().isAfter(at.plusSeconds(30))
                || base.review().counts().supporting() != base.review().factCount() || validation.status() != CatalogDraftValidation.Status.VALID_DRAFT
                || validation.facts().stream().anyMatch(f -> f.freshness() != CatalogDraftValidation.Freshness.CURRENT)) ineligible();
        if (pin.auditability() != null) {
            var auditPin = pin.auditability(); var audit = audits.loadForDecision(auditPin.reviewId(), auditPin.reviewSha256());
            var candidate = mapper.readTree(audit.candidateJson()); var supplement = candidate.get("auditabilityDraft");
            if (!pin.decisionCatalogSha256().equals(DecisionCanonicalizer.sha256(candidate.get("baseDraft")))
                    || !auditPin.decisionSupplementSha256().equals(DecisionCanonicalizer.sha256(supplement))
                    || audit.review().recordedAt().isAfter(at.plusSeconds(30)) || audit.review().counts().supporting() != audit.review().factCount()) ineligible();
            for (var option : supplement.get("options")) for (var fact : option.get("facts")) {
                var observed = Instant.parse(fact.get("evidence").get("observedAt").asText());
                if (observed.isAfter(at) || observed.isBefore(at.minus(io.authweave.core.evaluation.EvidencePolicy.MAX_AGE))) ineligible();
            }
        }
        return validation;
    }
    private String hash(Object value) { return DecisionCanonicalizer.sha256(mapper.valueToTree(value)); }
    private static void ineligible() { throw new CatalogBootstrapPublicationException(CatalogBootstrapPublicationException.Reason.SOURCE_NOT_ELIGIBLE); }
}

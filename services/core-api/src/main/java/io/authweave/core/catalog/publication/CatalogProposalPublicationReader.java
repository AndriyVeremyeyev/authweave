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
import io.authweave.core.catalog.impact.*;
import io.authweave.core.generated.jooq.tables.records.CatalogProposalPublicationsRecord;
import static io.authweave.core.generated.jooq.tables.CatalogProposalPublications.CATALOG_PROPOSAL_PUBLICATIONS;
import static io.authweave.core.catalog.publication.CatalogPublishedLoadingException.Reason.*;

/** Policy 1 verifies a first successor of a trusted bootstrap. Historical pins do not select a live head. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CatalogProposalPublicationReader {
    public static final String VERSION = "published-proposal-loading-1";
    public static final String PUBLICATION_POLICY_VERSION = "catalog-proposal-publication-1";
    private final DSLContext dsl;
    private final CatalogPublicationLookup lookup;
    private final CatalogPublicationRepository publications;
    private final StoredProposalDecisionService proposals;
    private final PublishedProposalDecisionCoverageService coverage;
    private final ObjectMapper mapper;
    public CatalogProposalPublicationReader(DSLContext dsl, CatalogPublicationLookup lookup, CatalogPublicationRepository publications,
            StoredProposalDecisionService proposals, PublishedProposalDecisionCoverageService coverage, ObjectMapper mapper) {
        this.dsl = dsl; this.lookup = lookup; this.publications = publications; this.proposals = proposals; this.coverage = coverage; this.mapper = mapper;
    }
    record Proof(String policyVersion, CatalogProposalPublicationRequest request, String requestSha256,
            PublishedCatalogSnapshot.Reference snapshot, UUID decisionId, Instant publishedAt, PublishedProposalDecisionCoverageService.Check coverage) {
        Proof {
            if (policyVersion == null || request == null || requestSha256 == null || snapshot == null || decisionId == null || publishedAt == null || coverage == null)
                throw new IllegalArgumentException("Incomplete proposal publication proof");
        }
    }
    record Verified(Proof proof, PublishedCatalogSnapshot snapshot, String proofSha256, CandidateDecisionImpactEvaluator.Snapshot decisionInputs) { }
    public static final class Loaded implements CatalogVerifiedPublication {
        private final Verified verified;
        private Loaded(Verified verified) { this.verified = verified; }
        public PublishedCatalogSnapshot snapshot() { return verified.snapshot(); }
        public PublishedCatalogSnapshot.Reference reference() { return verified.proof().snapshot(); }
        public StoredCandidateDecisionService.Reference source() { return null; }
        public StoredProposalDecisionService.Reference proposalSource() { return verified.proof().request().proposal(); }
        public CandidateDecisionImpactEvaluator.Snapshot decisionInputs() { return verified.decisionInputs(); }
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
    CatalogProposalPublicationsRecord stored(UUID id) {
        var p = CATALOG_PROPOSAL_PUBLICATIONS;
        return dsl.select(p.ID, p.DECISION_ID, p.REQUEST_SHA256, p.PROPOSAL_ID, p.PROPOSAL_VERSION, p.PROPOSAL_SHA256, p.PROOF_SHA256, p.RECORDED_AT)
                .select(DSL.when(DSL.octetLength(p.PROOF.cast(String.class)).le((int) CatalogPublicationRepository.MAX_JSON_BYTES), p.PROOF)
                    .otherwise((JSONB) null).as(p.PROOF)).from(p).where(p.ID.eq(id)).fetchOneInto(p);
    }
    Verified verified(CatalogProposalPublicationsRecord row) {
        try {
            if (row == null || row.getProof() == null) throw new IllegalArgumentException();
            var proof = mapper.readValue(row.getProof().data(), Proof.class);
            if (proof == null) throw new IllegalArgumentException();
            var pin = proof.request().proposal().revision(); var result = lookup.lookup(proof.snapshot());
            if (!PUBLICATION_POLICY_VERSION.equals(proof.policyVersion()) || !row.getId().equals(proof.request().publicationId())
                    || !row.getId().equals(proof.snapshot().snapshotId()) || !row.getDecisionId().equals(proof.decisionId())
                    || !row.getRequestSha256().equals(hash(proof.request())) || !row.getRequestSha256().equals(proof.requestSha256())
                    || !row.getProofSha256().equals(hash(proof)) || !row.getProposalId().equals(pin.proposalId())
                    || row.getProposalVersion() != pin.version() || !row.getProposalSha256().equals(pin.proposalSha256())
                    || !result.storedIntegrityValidated() || result.lineage().size() != 2
                    || !proof.request().before().equals(result.snapshot().previousSnapshot())
                    || !proof.decisionId().equals(result.snapshot().publication().decisionId())
                    || !proof.publishedAt().equals(result.snapshot().publication().publishedAt()) || row.getRecordedAt() == null
                    || !near(row.getRecordedAt().toInstant(), proof.publishedAt())) throw new IllegalArgumentException();
            var event = publications.find(row.getId(), CatalogPublicationLookup.MAX_READ_BYTES).event();
            if (event == null || !near(event.occurredAt(), proof.publishedAt())) throw new IllegalArgumentException();
            var replayed = coverage.replay(proof.request().before(), proof.request().proposal(), proof.publishedAt());
            if (!replayed.decisionScopeCoverageComplete() || !replayed.candidateClaims().allRecordedClaimsSupportedAndCurrent()
                    || !replayed.equals(proof.coverage())) throw new IllegalArgumentException();
            var inputs = proposals.load(proof.request().proposal()).snapshot();
            if (!hash(inputs.catalog()).equals(hash(result.snapshot().catalog().asDraft()))) throw new IllegalArgumentException();
            return new Verified(proof, result.snapshot(), row.getProofSha256(), inputs);
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException | CatalogProposalDecisionLoadingException
                | CatalogBootstrapReviewException | io.authweave.core.catalog.auditability.CatalogAuditabilityReviewException
                | CatalogBootstrapPublicationException | CatalogPublishedLoadingException invalid) {
            throw new CatalogPublishedLoadingException(STORED_PUBLICATION_INVALID);
        }
        // Database failures propagate; no synthetic or earlier-proof fallback.
    }
    private String hash(Object value) { return DecisionCanonicalizer.sha256(mapper.valueToTree(value)); }
    private static boolean near(Instant a, Instant b) { return !a.isBefore(b.minusSeconds(30)) && !a.isAfter(b.plusSeconds(30)); }
}

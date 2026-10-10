package io.authweave.core.catalog.impact;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.catalog.auditability.CatalogAuditabilityReview;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.draft.CatalogDraftFacts;
import io.authweave.core.catalog.draft.CatalogDraftValidation;
import io.authweave.core.catalog.draft.CatalogDraftValidator;
import io.authweave.core.catalog.proposal.CatalogFactReview;
import io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict;
import static io.authweave.core.catalog.impact.CandidateHardConstraintEvaluator.*;
import static io.authweave.core.catalog.impact.CatalogProposalDecisionLoadingException.Reason.*;

/** Internal read-only adapter for proposal decisions. Verifies historical provenance, not current
 * curator authority or source truth. Never fetches URLs, approves, publishes or updates assessments. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class StoredProposalDecisionService {
    public static final String VERSION = "decision-proposal-review-loading-1";
    private static final long MAX_INTEGER = 9007199254740991L;
    private final CatalogProposalDecisionRepository repository;
    private final CatalogDraftValidator validator;
    private final StoredCandidateDecisionService reviews;
    private final ObjectMapper mapper;
    private final Clock clock;
    public StoredProposalDecisionService(CatalogProposalDecisionRepository repository, CatalogDraftValidator validator,
            StoredCandidateDecisionService reviews, ObjectMapper mapper, Clock clock) {
        this.repository = repository; this.validator = validator; this.reviews = reviews; this.mapper = mapper; this.clock = clock;
    }
    public record Revision(UUID proposalId, long version, String proposalSha256, String requestSha256) {
        public Revision { Objects.requireNonNull(proposalId); number(version); digest(proposalSha256); digest(requestSha256); }
    }
    public record Reference(Revision revision, long reviewThroughNumber, String reviewSetSha256,
            @com.fasterxml.jackson.annotation.JsonProperty(required = true) StoredCandidateDecisionService.AuditReference auditability) {
        public Reference { Objects.requireNonNull(revision); number(reviewThroughNumber); digest(reviewSetSha256); }
    }
    public record Inputs(Reference reference, JsonNode base, String expectedBaseSha256,
            CandidateDecisionImpactEvaluator.Snapshot snapshot, List<CatalogFactReview> observations,
            CatalogAuditabilityReview auditabilityReview) {
        public Inputs { base = base.deepCopy(); observations = List.copyOf(observations); }
        @Override public JsonNode base() { return base.deepCopy(); }
    }

    /** Core computes a pin from authenticated stored observations; caller cannot supply verdicts.
     * The immutable cutoff is for calculation replay, not permission to publish a stale revision. */
    public Reference pin(Revision revision, StoredCandidateDecisionService.AuditReference auditability) {
        Objects.requireNonNull(revision);
        return material(revision, repository.latestNumber(revision), auditability, null).reference();
    }
    public Inputs load(Reference reference) {
        Objects.requireNonNull(reference);
        return material(reference.revision(), reference.reviewThroughNumber(), reference.auditability(), reference.reviewSetSha256());
    }

    private Inputs material(Revision pin, long through, StoredCandidateDecisionService.AuditReference auditability, String expectedReviews) {
        var stored = repository.proposal(pin);
        if (stored == null) throw new CatalogProposalDecisionLoadingException(PROPOSAL_NOT_FOUND);
        var row = stored.revision();
        if (!pin.proposalSha256().equals(row.getProposalSha256())) throw new CatalogProposalDecisionLoadingException(REFERENCE_MISMATCH);
        JsonNode raw; CatalogChangePreviewRequest request;
        try {
            if (stored.requestBytes() <= 0 || stored.requestBytes() > CatalogProposalDecisionRepository.MAX_JSON_BYTES || row.getRequest() == null
                    || row.getRequestSchemaVersion() != 1 || !"PROPOSED".equals(row.getState())) throw new IllegalArgumentException();
            raw = mapper.readTree(row.getRequest().data()); request = mapper.treeToValue(raw, CatalogChangePreviewRequest.class);
            if (request == null || !pin.proposalId().equals(request.proposalId())
                    || !row.getProposalSha256().equals(CatalogDraftCanonicalizer.sha256(request))
                    || !request.expectedBaseSha256().equals(CatalogDraftCanonicalizer.sha256(request.base()))
                    || validator.validateAt(request.base(), clock.instant()).status() != CatalogDraftValidation.Status.VALID_DRAFT
                    || validator.validateAt(request.candidate(), clock.instant()).status() != CatalogDraftValidation.Status.VALID_DRAFT) throw new IllegalArgumentException();
            var e = stored.audit(); var at = row.getRecordedAt();
            if (e == null || e.getId() == null || !pin.proposalId().equals(e.getProposalId()) || e.getVersion() != pin.version()
                    || !pin.proposalSha256().equals(e.getProposalSha256()) || !"SERVICE".equals(e.getActorType())
                    || !"core-api-local-catalog".equals(e.getActorId()) || e.getCorrelationId() == null || !"SUCCEEDED".equals(e.getOutcome())
                    || (pin.version() == 0 ? e.getPreviousVersion() != null || !"catalog-proposal.created".equals(e.getAction())
                        : !Long.valueOf(pin.version() - 1).equals(e.getPreviousVersion()) || !"catalog-proposal.revised".equals(e.getAction()))
                    || at == null || e.getOccurredAt() == null || !near(at.toInstant(), e.getOccurredAt().toInstant())
                    || at.toInstant().isAfter(clock.instant().plusSeconds(30))) throw new IllegalArgumentException();
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException invalid) {
            throw new CatalogProposalDecisionLoadingException(STORED_PROPOSAL_INVALID);
        }
        if (!pin.requestSha256().equals(DecisionCanonicalizer.sha256(raw))) throw new CatalogProposalDecisionLoadingException(REFERENCE_MISMATCH);
        var catalog = raw.get("candidate"); var catalogSha = DecisionCanonicalizer.sha256(catalog);
        var options = new HashMap<String, JsonNode>(); catalog.get("options").forEach(o -> options.put(o.get("id").asText(), o));
        var targets = new HashSet<String>(); request.candidate().options().forEach(o -> CatalogDraftFacts.entries(o).keySet().forEach(p -> targets.add(o.id() + "/" + p)));
        var rows = repository.observations(pin, through);
        if (rows.size() > CatalogProposalDecisionRepository.MAX_FACTS) throw new CatalogProposalDecisionLoadingException(STORED_PROPOSAL_INVALID);
        var receipts = new java.util.ArrayList<CatalogFactReview>(); var addresses = new HashSet<String>(); var numbers = new HashSet<Long>();
        // Bind full historical audit metadata by digest without returning curator identity.
        var auditDigests = new java.util.ArrayList<String>();
        auditDigests.add(DecisionCanonicalizer.sha256(mapper.valueToTree(stored.audit().intoMap())));
        for (var observation : rows) {
            var r = observation.review(); var e = observation.audit();
            try {
                var at = r.getRecordedAt(); var occurred = e == null ? null : e.getOccurredAt();
                if (r.getId() == null || !pin.proposalId().equals(r.getProposalId()) || r.getProposalVersion() != pin.version()
                        || !pin.proposalSha256().equals(r.getProposalSha256()) || r.getReviewNumber() < 1 || r.getReviewNumber() > through
                        || !numbers.add(r.getReviewNumber()) || !targets.contains(r.getOptionId() + "/" + r.getFactPath())
                        || !addresses.add(r.getOptionId() + "/" + r.getFactPath()) || at == null
                        || at.toInstant().isBefore(row.getRecordedAt().toInstant().minusSeconds(30)) || at.toInstant().isAfter(clock.instant().plusSeconds(30))
                        || e == null || e.getId() == null || !r.getId().equals(e.getReviewId()) || !pin.proposalId().equals(e.getProposalId())
                        || e.getProposalVersion() != pin.version() || !pin.proposalSha256().equals(e.getProposalSha256())
                        || !r.getOptionId().equals(e.getOptionId()) || !r.getFactPath().equals(e.getFactPath()) || !r.getVerdict().equals(e.getVerdict())
                        || !"catalog-fact.review-recorded".equals(e.getAction()) || !"CURATOR".equals(e.getActorType())
                        || !text(e.getActorIssuer(), 2048) || !text(e.getActorSubject(), 256) || !digits(e.getActorProjectId()) || !digits(e.getActorOrgId())
                        || e.getCorrelationId() == null || !"SUCCEEDED".equals(e.getOutcome()) || occurred == null || e.getAuthenticatedAt() == null
                        || !near(at.toInstant(), occurred.toInstant()) || e.getAuthenticatedAt().isBefore(occurred.minusSeconds(900))
                        || e.getAuthenticatedAt().isAfter(occurred.plusSeconds(30))) throw new IllegalArgumentException();
                receipts.add(new CatalogFactReview(r.getId(), pin.proposalId(), pin.version(), pin.proposalSha256(), r.getReviewNumber(),
                        r.getOptionId(), r.getFactPath(), Verdict.valueOf(r.getVerdict()), at.toInstant(), "HUMAN_SOURCE_REVIEW_OBSERVATION", false, false, false, false));
                auditDigests.add(DecisionCanonicalizer.sha256(mapper.valueToTree(e.intoMap())));
            } catch (IllegalArgumentException invalid) { throw new CatalogProposalDecisionLoadingException(STORED_PROPOSAL_INVALID); }
        }
        if (receipts.stream().mapToLong(CatalogFactReview::reviewNumber).max().orElse(0) != through)
            throw new CatalogProposalDecisionLoadingException(REVIEW_SET_MISMATCH);
        var reviewSha = DecisionCanonicalizer.sha256(mapper.valueToTree(new ReviewSet(receipts, auditDigests)));
        if (expectedReviews != null && !expectedReviews.equals(reviewSha)) throw new CatalogProposalDecisionLoadingException(REVIEW_SET_MISMATCH);
        var assertions = new SourceAssertions(catalogSha, receipts.stream().map(r -> new FactAssertion(r.optionId(), r.factPath(),
                claimSha256(options.get(r.optionId()), r.factPath()), Assertion.valueOf(r.verdict().name()))).toList());
        var audit = reviews.loadAuditability(catalogSha, auditability);
        return new Inputs(new Reference(pin, through, reviewSha, auditability), raw.get("base"), request.expectedBaseSha256(),
                new CandidateDecisionImpactEvaluator.Snapshot(catalog, assertions, audit.supplement()), receipts, audit.receipt());
    }
    private record ReviewSet(List<CatalogFactReview> observations, List<String> auditSha256) { }
    private static boolean near(Instant a, Instant b) { return !a.isBefore(b.minusSeconds(30)) && !a.isAfter(b.plusSeconds(30)); }
    private static boolean text(String s, int max) { return s != null && !s.isBlank() && s.length() <= max; }
    private static boolean digits(String s) { return s != null && s.matches("[0-9]{1,40}"); }
    private static void number(long n) { if (n < 0 || n > MAX_INTEGER) throw new IllegalArgumentException("Use a non-negative safe integer"); }
    private static void digest(String s) { if (s == null || !s.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Use an exact decision digest"); }
}

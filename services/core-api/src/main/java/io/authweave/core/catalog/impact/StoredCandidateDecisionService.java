package io.authweave.core.catalog.impact;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import io.authweave.core.catalog.auditability.CatalogAuditabilityReview;
import io.authweave.core.catalog.auditability.CatalogAuditabilityReviewException;
import io.authweave.core.catalog.auditability.CatalogAuditabilityReviewService;
import io.authweave.core.catalog.publication.CatalogBootstrapReview;
import io.authweave.core.catalog.publication.CatalogBootstrapReviewException;
import io.authweave.core.catalog.publication.CatalogBootstrapReviewService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static io.authweave.core.catalog.impact.CandidateHardConstraintEvaluator.*;

/** Read-only DB adapter, not an HTTP endpoint or permission grant. Loads validated historical
 * review+audit rows; their verdicts remain human assertions, not independent source verification. */
@Service
public class StoredCandidateDecisionService {
    public static final String VERSION = "decision-stored-review-loading-1";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final CatalogBootstrapReviewService baseReviews;
    private final CatalogAuditabilityReviewService auditReviews;
    private final Clock clock;
    public StoredCandidateDecisionService(CatalogBootstrapReviewService baseReviews, CatalogAuditabilityReviewService auditReviews, Clock clock) {
        this.baseReviews = baseReviews; this.auditReviews = auditReviews; this.clock = clock;
    }
    public record AuditReference(UUID reviewId, String reviewSha256, String decisionSupplementSha256) {
        public AuditReference { Objects.requireNonNull(reviewId); digest(reviewSha256); digest(decisionSupplementSha256); }
    }
    public record Reference(UUID reviewId, String reviewSha256, String decisionCatalogSha256,
            @com.fasterxml.jackson.annotation.JsonProperty(required = true) AuditReference auditability) {
        public Reference { Objects.requireNonNull(reviewId); digest(reviewSha256); digest(decisionCatalogSha256); }
    }
    public record Result(String scope, String loaderVersion, CatalogBootstrapReview baseReview, CatalogAuditabilityReview auditabilityReview,
            CandidateDecisionEvaluator.Result decision, List<String> deferredBoundaries, boolean storedReviewsVerified,
            boolean currentCuratorAuthorityVerified, boolean sourceVerificationPerformed, boolean approvalGranted,
            boolean publicationReady, boolean writesPerformed) {
        public Result { deferredBoundaries = List.copyOf(deferredBoundaries); }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result evaluate(JsonNode profileDocument, int profileSchemaVersion, Reference reference, JsonNode weightsDocument) {
        // Snapshot caller documents before DB reads; caller cannot replace eligibility or the clock.
        var profile = Objects.requireNonNull(profileDocument).deepCopy(); var weights = Objects.requireNonNull(weightsDocument).deepCopy();
        return calculate(profile, profileSchemaVersion, load(reference), weights, clock.instant());
    }

    /** Package-internal material for impact/replay, under the caller's transaction. No actor disclosure. */
    record Inputs(CandidateDecisionImpactEvaluator.Snapshot snapshot, CatalogBootstrapReview baseReview,
            CatalogAuditabilityReview auditabilityReview) { }
    Inputs load(Reference reference) {
        Objects.requireNonNull(reference);
        var base = baseReviews.loadForDecision(reference.reviewId(), reference.reviewSha256());
        var catalog = MAPPER.readTree(base.candidateJson());
        if (!reference.decisionCatalogSha256().equals(DecisionCanonicalizer.sha256(catalog)))
            throw new CatalogBootstrapReviewException(CatalogBootstrapReviewException.Reason.CONFLICT);
        var options = new HashMap<String, JsonNode>(); catalog.get("options").forEach(o -> options.put(o.get("id").asText(), o));
        var assertions = new SourceAssertions(reference.decisionCatalogSha256(), base.observations().stream().map(o ->
                new FactAssertion(o.optionId(), o.factPath(), claimSha256(options.get(o.optionId()), o.factPath()), Assertion.valueOf(o.verdict().name()))).toList());
        var audit = loadAuditability(reference.decisionCatalogSha256(), reference.auditability());
        return new Inputs(new CandidateDecisionImpactEvaluator.Snapshot(catalog, assertions, audit.supplement()), base.review(), audit.receipt());
    }
    record AuditInputs(CandidateAuditabilityInput supplement, CatalogAuditabilityReview receipt) { }
    /** Reusable exact-candidate loader; never inherit a previous catalog's supplemental review. */
    AuditInputs loadAuditability(String catalogSha256, AuditReference pin) {
        CandidateAuditabilityInput supplement = null; CatalogAuditabilityReview receipt = null;
        if (pin != null) {
            var audit = auditReviews.loadForDecision(pin.reviewId(), pin.reviewSha256());
            var candidate = MAPPER.readTree(audit.candidateJson()); var draft = candidate.get("auditabilityDraft");
            if (!catalogSha256.equals(DecisionCanonicalizer.sha256(candidate.get("baseDraft")))
                    || !pin.decisionSupplementSha256().equals(DecisionCanonicalizer.sha256(draft)))
                throw new CatalogAuditabilityReviewException(CatalogAuditabilityReviewException.Reason.CONFLICT);
            var claimDigests = CandidateAuditabilityInput.claimDigests(draft);
            supplement = new CandidateAuditabilityInput(catalogSha256, draft, audit.observations().stream().map(o ->
                    new CandidateAuditabilityInput.FactAssertion(o.optionId(), o.criterion(),
                            claimDigests.get(new CandidateAuditabilityInput.Address(o.optionId(), o.criterion())), Assertion.valueOf(o.verdict().name()))).toList());
            receipt = audit.review();
        }
        return new AuditInputs(supplement, receipt);
    }
    static Result calculate(JsonNode profile, int profileSchemaVersion, Inputs inputs, JsonNode weights, Instant at) {
        var snapshot = inputs.snapshot();
        var decision = CandidateDecisionEvaluator.evaluate(profile, profileSchemaVersion, snapshot.catalog(), snapshot.assertions(), snapshot.auditability(), weights, at);
        boolean auditLoaded = snapshot.auditability() != null;
        var deferred = decision.deferredBoundaries().stream().filter(path -> !path.equals("reviewReceiptAuthenticationAndLoading")
                && !(auditLoaded && path.equals("realAuditabilitySupplementLoading"))).toList();
        return new Result("STORED_REVIEW_CANDIDATE_DECISION_CALCULATION", VERSION, inputs.baseReview(), inputs.auditabilityReview(), decision, deferred,
                true, false, false, false, false, false);
    }
    private static void digest(String value) { if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Use an exact decision digest"); }
}

package io.authweave.core.catalog.impact;

import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.publication.PublishedCatalogSnapshot;

/** Recomputes both whole decisions from verified DB material in one consistent snapshot.
 * No caller manifest, verdict, result, clock, implicit current head, or inherited supplement. */
@Service
public class StoredProposalDecisionImpactService {
    public static final String VERSION = "decision-published-proposal-impact-1";
    private final TrustedPublishedCatalogService published;
    private final StoredProposalDecisionService proposals;
    private final Clock clock;
    public StoredProposalDecisionImpactService(TrustedPublishedCatalogService published, StoredProposalDecisionService proposals, Clock clock) {
        this.published = published; this.proposals = proposals; this.clock = clock;
    }
    public record Result(String scope, String adapterVersion, PublishedCatalogSnapshot.Reference baseline,
            String baselineProofSha256, StoredProposalDecisionService.Reference proposal, CandidateDecisionImpactEvaluator.Result impact,
            boolean historicalPublicationWorkflowVerified, boolean storedProposalReviewsVerified, boolean currentCuratorAuthorityVerified,
            boolean sourceVerificationPerformed, boolean assessmentResultPinned, boolean approvalGranted, boolean publicationReady, boolean writesPerformed) { }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result evaluate(JsonNode profileDocument, int profileSchemaVersion, JsonNode weightsDocument,
            PublishedCatalogSnapshot.Reference baseline, StoredProposalDecisionService.Reference proposal) {
        var profile = Objects.requireNonNull(profileDocument).deepCopy(); var weights = Objects.requireNonNull(weightsDocument).deepCopy();
        var before = published.load(Objects.requireNonNull(baseline)); var after = proposals.load(Objects.requireNonNull(proposal));
        // The proposal's claimed base must be this published source, including original array order.
        if (!after.expectedBaseSha256().equals(CatalogDraftCanonicalizer.sha256(before.publication().snapshot().catalog().asDraft()))
                || !DecisionCanonicalizer.sha256(after.base()).equals(DecisionCanonicalizer.sha256(before.decisionInputs().catalog())))
            throw new CatalogProposalDecisionLoadingException(CatalogProposalDecisionLoadingException.Reason.BASELINE_MISMATCH);
        var impact = CandidateDecisionImpactEvaluator.evaluate(profile, profileSchemaVersion, weights, before.decisionInputs(), after.snapshot(), clock.instant());
        return new Result("PUBLISHED_BASELINE_REVIEWED_PROPOSAL_DECISION_IMPACT", VERSION, baseline, before.publication().proofSha256(), proposal, impact,
                true, true, false, false, false, false, false, false);
    }
}

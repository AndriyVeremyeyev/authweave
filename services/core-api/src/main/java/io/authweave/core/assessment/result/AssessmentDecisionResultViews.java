package io.authweave.core.assessment.result;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.authweave.core.catalog.publication.PublishedCatalogSnapshot;
import tools.jackson.databind.JsonNode;

/** Discovery metadata is deliberately not a replay-verified result. Summary is projected only after full replay. */
public final class AssessmentDecisionResultViews {
    private AssessmentDecisionResultViews() { }
    public static final int PAGE_SIZE = 20;
    public record Item(AssessmentDecisionResultRequest.Reference reference, long assessmentVersion,
            PublishedCatalogSnapshot.Reference catalog, AssessmentDecisionResultRequest.Reference previousResult, Instant recordedAt) { }
    public record Page(String scope, UUID workspaceId, UUID assessmentId, List<Item> items,
            AssessmentDecisionResultRequest.Reference nextBefore, boolean historicalReplayVerified) {
        public Page { items = List.copyOf(items); }
    }
    public record Bounds(int lowerBound, int upperBound, int unknownWeight) { }
    public record Candidate(String optionId, String product, String plan, String region, String deployment,
            String hardVerdict, Bounds score) { }
    public record Summary(String scope, UUID workspaceId, UUID assessmentId, Item item, Instant evaluatedAt,
            int profileSchemaVersion, String profileSha256, String policySha256, JsonNode weights, String status,
            List<String> shortlist, List<Candidate> candidates, int verificationGapCount, boolean historicalReplayVerified,
            boolean externalSourceVerificationPerformed, boolean configurationVerified, boolean complianceVerified, boolean decisionApproved) {
        public Summary { weights = weights.deepCopy(); shortlist = List.copyOf(shortlist); candidates = List.copyOf(candidates); }
        @Override public JsonNode weights() { return weights.deepCopy(); }
    }
}

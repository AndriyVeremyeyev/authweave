package io.authweave.core.assessment.result;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.authweave.core.catalog.publication.PublishedCatalogSnapshot;
import tools.jackson.databind.JsonNode;
import io.authweave.core.catalog.impact.CandidatePreferenceScorer;

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
    /** After is newly computed advice, NOT a stored or replay-verified result summary. */
    public record Scoring(CandidatePreferenceScorer.Weights weights, CandidatePreferenceScorer.Status status,
            List<String> shortlist, List<CandidatePreferenceScorer.ScoredCandidate> candidates,
            List<CandidatePreferenceScorer.RankGroup> rankGroups) {
        public Scoring { shortlist = List.copyOf(shortlist); candidates = List.copyOf(candidates); rankGroups = List.copyOf(rankGroups); }
        public static Scoring from(CandidatePreferenceScorer.Analysis analysis) {
            return new Scoring(analysis.weights(), analysis.status(), analysis.shortlist(), analysis.candidates(), analysis.rankGroups());
        }
    }
    public record Sensitivity(String scope, Summary summary, Scoring before, Scoring after, boolean writesPerformed) { }
    public record Summary(String scope, UUID workspaceId, UUID assessmentId, Item item, Instant evaluatedAt,
            int profileSchemaVersion, String profileSha256, String policySha256, JsonNode weights, String status,
            List<String> shortlist, List<Candidate> candidates, int verificationGapCount, boolean historicalReplayVerified,
            boolean externalSourceVerificationPerformed, boolean configurationVerified, boolean complianceVerified, boolean decisionApproved) {
        public Summary { weights = weights.deepCopy(); shortlist = List.copyOf(shortlist); candidates = List.copyOf(candidates); }
        @Override public JsonNode weights() { return weights.deepCopy(); }
    }
    /** Allowlisted explanation fields, never the profile, request, curator identities or raw declared values. */
    public record Advice(String scope, Summary summary, JsonNode candidates, JsonNode rankGroups,
            JsonNode architecture, JsonNode limitations, JsonNode followUps) {
        public Advice {
            candidates = candidates.deepCopy(); rankGroups = rankGroups.deepCopy(); architecture = architecture.deepCopy();
            limitations = limitations.deepCopy(); followUps = followUps.deepCopy();
        }
        @Override public JsonNode candidates() { return candidates.deepCopy(); }
        @Override public JsonNode rankGroups() { return rankGroups.deepCopy(); }
        @Override public JsonNode architecture() { return architecture.deepCopy(); }
        @Override public JsonNode limitations() { return limitations.deepCopy(); }
        @Override public JsonNode followUps() { return followUps.deepCopy(); }
    }
}

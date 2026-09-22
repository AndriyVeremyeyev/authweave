package io.authweave.core.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.authweave.core.catalog.ProviderCatalog;

/** An explicitly weighted diagnostic preview, not a provider ranking or recommendation. */
public record WeightedComparisonPreview(SyntheticComparison comparison, String scoringPolicyVersion,
        Map<ProviderCatalog.Capability, Integer> weights, boolean rankingPerformed,
        boolean recommendationReady, List<CandidateScore> scores) {

    public static final String POLICY_VERSION = "explicit-capability-weights-1";

    public enum ScoreStatus { SCORED, EXCLUDED, UNRESOLVED_HARD_CONSTRAINTS, UNKNOWN_PREFERENCE_EVIDENCE }

    public record Contribution(ProviderCatalog.Capability capability, int weight,
            CapabilityPreferenceEvaluator.Outcome outcome, int earnedPoints) { }

    public record CandidateScore(String optionId, ScoreStatus status, Integer score,
            List<Contribution> contributions) {
        public CandidateScore {
            contributions = List.copyOf(contributions);
        }
    }

    public WeightedComparisonPreview {
        weights = Collections.unmodifiableMap(new LinkedHashMap<>(weights));
        scores = List.copyOf(scores);
    }

    public static WeightedComparisonPreview from(SyntheticComparison comparison, WeightedComparisonRequest request) {
        if (request == null || request.weights() == null) {
            throw invalid("Supply weights for every explicitly preferred capability.");
        }
        if (comparison.candidates().isEmpty()) throw new IllegalStateException("Comparison has no candidates");
        Set<ProviderCatalog.Capability> preferred = comparison.candidates().getFirst().capabilityPreferences().stream()
                .map(CapabilityPreferenceEvaluator.Preference::capability).collect(Collectors.toSet());
        if (preferred.isEmpty()) throw invalid("The assessment has no preferred capabilities to weight.");
        for (var candidate : comparison.candidates()) {
            var capabilities = candidate.capabilityPreferences().stream()
                    .map(CapabilityPreferenceEvaluator.Preference::capability).toList();
            if (capabilities.size() != preferred.size() || !preferred.containsAll(capabilities)) {
                throw new IllegalStateException("Candidates disagree on preferred capabilities");
            }
        }
        if (!request.weights().keySet().equals(preferred)) {
            throw invalid("Provide exactly one weight for each preferred capability and no others.");
        }
        int total = 0;
        var ordered = new LinkedHashMap<ProviderCatalog.Capability, Integer>();
        for (var capability : ProviderCatalog.Capability.values()) {
            if (!preferred.contains(capability)) continue;
            Integer weight = request.weights().get(capability);
            if (weight == null || weight < 1 || weight > 100) {
                throw invalid("Every weight must be an integer from 1 to 100.");
            }
            ordered.put(capability, weight);
            total += weight;
        }
        if (total != 100) throw invalid("Weights must total exactly 100 points.");
        var scores = comparison.candidates().stream().map(candidate -> score(candidate, ordered)).toList();
        return new WeightedComparisonPreview(comparison, POLICY_VERSION, ordered, false, false, scores);
    }

    private static CandidateScore score(SyntheticComparison.Candidate candidate,
            Map<ProviderCatalog.Capability, Integer> weights) {
        if (candidate.hardVerdict() == HardConstraintPreflight.Verdict.EXCLUDED) {
            return new CandidateScore(candidate.optionId(), ScoreStatus.EXCLUDED, null, List.of());
        }
        if (candidate.hardVerdict() == HardConstraintPreflight.Verdict.UNRESOLVED) {
            return new CandidateScore(candidate.optionId(), ScoreStatus.UNRESOLVED_HARD_CONSTRAINTS, null, List.of());
        }
        if (candidate.capabilityPreferences().stream().anyMatch(preference ->
                preference.outcome() == CapabilityPreferenceEvaluator.Outcome.UNKNOWN)) {
            return new CandidateScore(candidate.optionId(), ScoreStatus.UNKNOWN_PREFERENCE_EVIDENCE, null, List.of());
        }
        var byCapability = new EnumMap<ProviderCatalog.Capability, CapabilityPreferenceEvaluator.Preference>(ProviderCatalog.Capability.class);
        for (var preference : candidate.capabilityPreferences()) {
            if (byCapability.put(preference.capability(), preference) != null) {
                throw new IllegalStateException("Duplicate preferred capability in comparison");
            }
        }
        int score = 0;
        var contributions = new ArrayList<Contribution>();
        for (var entry : weights.entrySet()) {
            var outcome = byCapability.get(entry.getKey()).outcome();
            int earned = outcome == CapabilityPreferenceEvaluator.Outcome.AVAILABLE ? entry.getValue() : 0;
            score += earned;
            contributions.add(new Contribution(entry.getKey(), entry.getValue(), outcome, earned));
        }
        return new CandidateScore(candidate.optionId(), ScoreStatus.SCORED, score, contributions);
    }

    private static InvalidWeightedComparisonRequestException invalid(String message) {
        return new InvalidWeightedComparisonRequestException(message);
    }
}

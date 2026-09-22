package io.authweave.core.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.authweave.core.catalog.ProviderCatalog;

/** A paired what-if calculation over one synthetic comparison snapshot, without ranking. */
public record WeightedSensitivityPreview(SyntheticComparison comparison, String scoringPolicyVersion,
        String sensitivityPolicyVersion, Scenario baseline, Scenario alternative,
        List<CandidateDelta> deltas, boolean rankingPerformed, boolean recommendationReady) {

    public static final String POLICY_VERSION = "explicit-weight-sensitivity-1";

    public record Scenario(Map<ProviderCatalog.Capability, Integer> weights,
            List<WeightedComparisonPreview.CandidateScore> scores) {
        public Scenario {
            weights = Collections.unmodifiableMap(new LinkedHashMap<>(weights));
            scores = List.copyOf(scores);
        }
    }

    public record CapabilityDelta(ProviderCatalog.Capability capability, int baselineWeight,
            int alternativeWeight, CapabilityPreferenceEvaluator.Outcome outcome, int pointChange) { }

    public record CandidateDelta(String optionId, WeightedComparisonPreview.ScoreStatus status,
            Integer scoreDelta, List<CapabilityDelta> capabilityDeltas) {
        public CandidateDelta {
            capabilityDeltas = List.copyOf(capabilityDeltas);
        }
    }

    public WeightedSensitivityPreview {
        deltas = List.copyOf(deltas);
    }

    public static WeightedSensitivityPreview from(SyntheticComparison comparison, WeightedSensitivityRequest request) {
        if (request == null) {
            throw new InvalidWeightedComparisonRequestException("baselineWeights",
                    "Supply two complete sets of explicitly preferred capability weights.");
        }
        var baseline = score(comparison, request.baselineWeights(), "baselineWeights");
        var alternative = score(comparison, request.alternativeWeights(), "alternativeWeights");
        var deltas = new ArrayList<CandidateDelta>();
        for (int index = 0; index < baseline.scores().size(); index++) {
            var before = baseline.scores().get(index);
            var after = alternative.scores().get(index);
            if (!before.optionId().equals(after.optionId()) || before.status() != after.status()) {
                throw new IllegalStateException("Paired scores must refer to the same option and evidence state");
            }
            if (before.score() == null) {
                deltas.add(new CandidateDelta(before.optionId(), before.status(), null, List.of()));
                continue;
            }
            var changes = new ArrayList<CapabilityDelta>();
            for (int preference = 0; preference < before.contributions().size(); preference++) {
                var first = before.contributions().get(preference);
                var second = after.contributions().get(preference);
                if (first.capability() != second.capability() || first.outcome() != second.outcome()) {
                    throw new IllegalStateException("Paired contributions must use the same preference evidence");
                }
                changes.add(new CapabilityDelta(first.capability(), first.weight(), second.weight(), first.outcome(),
                        second.earnedPoints() - first.earnedPoints()));
            }
            int totalChange = after.score() - before.score();
            if (changes.stream().mapToInt(CapabilityDelta::pointChange).sum() != totalChange) {
                throw new IllegalStateException("Capability deltas do not reconcile to the score delta");
            }
            deltas.add(new CandidateDelta(before.optionId(), before.status(), totalChange, changes));
        }
        return new WeightedSensitivityPreview(comparison, WeightedComparisonPreview.POLICY_VERSION, POLICY_VERSION,
                new Scenario(baseline.weights(), baseline.scores()),
                new Scenario(alternative.weights(), alternative.scores()), deltas, false, false);
    }

    private static WeightedComparisonPreview score(SyntheticComparison comparison,
            Map<ProviderCatalog.Capability, Integer> weights, String path) {
        try {
            return WeightedComparisonPreview.from(comparison, new WeightedComparisonRequest(weights));
        } catch (InvalidWeightedComparisonRequestException exception) {
            throw new InvalidWeightedComparisonRequestException(path, exception.getMessage());
        }
    }
}

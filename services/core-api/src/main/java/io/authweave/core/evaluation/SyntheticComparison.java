package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.authweave.core.catalog.ProviderCatalog;

/** An unranked, read-only comparison of checked constraints and capability preferences. */
public record SyntheticComparison(UUID workspaceId, UUID assessmentId, long assessmentVersion,
        String catalogVersion, ProviderCatalog.Kind catalogKind, String policyVersion,
        String hardConstraintPolicyVersion, String preferencePolicyVersion, Instant evaluatedAt,
        String scope, boolean recommendationReady, boolean rankingPerformed,
        List<String> deferredPaths, List<Candidate> candidates) {

    public static final String POLICY_VERSION = "synthetic-comparison-1";

    public SyntheticComparison {
        deferredPaths = List.copyOf(deferredPaths);
        candidates = List.copyOf(candidates);
    }

    public record Candidate(String optionId, String displayName, String plan, String region,
            HardConstraintPreflight.Verdict hardVerdict,
            List<HardConstraintPreflight.Finding> exclusionReasons,
            List<HardConstraintPreflight.Finding> informationGaps,
            List<CapabilityPreferenceEvaluator.Preference> capabilityPreferences) {
        public Candidate {
            exclusionReasons = List.copyOf(exclusionReasons);
            informationGaps = List.copyOf(informationGaps);
            capabilityPreferences = List.copyOf(capabilityPreferences);
        }
    }

    public static SyntheticComparison from(EligibilityPreflightV4 eligibility) {
        var hard = HardConstraintPreflight.from(eligibility);
        Map<String, EligibilityPreflightV3.Candidate> sourceById = eligibility.candidates().stream()
                .collect(Collectors.toMap(EligibilityPreflightV3.Candidate::optionId, Function.identity()));
        var candidates = hard.candidates().stream().map(candidate -> {
            var source = sourceById.get(candidate.optionId());
            if (source == null) throw new IllegalStateException("Eligibility option is missing");
            return new Candidate(candidate.optionId(), candidate.displayName(), candidate.plan(), candidate.region(),
                    candidate.verdict(), candidate.exclusionReasons(), candidate.informationGaps(),
                    CapabilityPreferenceEvaluator.evaluate(source.capabilityChecks(), eligibility.evaluatedAt()));
        }).toList();
        return new SyntheticComparison(eligibility.workspaceId(), eligibility.assessmentId(), eligibility.assessmentVersion(),
                eligibility.catalogVersion(), eligibility.catalogKind(), POLICY_VERSION, HardConstraintPreflight.POLICY_VERSION,
                CapabilityPreferenceEvaluator.POLICY_VERSION, eligibility.evaluatedAt(),
                "SYNTHETIC_UNRANKED_COMPARISON", false, false, eligibility.deferredPaths(), candidates);
    }
}

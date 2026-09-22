package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.authweave.core.catalog.ProviderCatalog;

import static org.junit.jupiter.api.Assertions.*;

class WeightedComparisonPreviewTests {
    private static final Instant AT = Instant.parse("2026-09-12T12:00:00Z");

    @Test
    void scoresOnlyFullyKnownPreferencesAfterCheckedHardRequirementsPass() {
        var comparison = comparison(List.of(
                candidate("passing", HardConstraintPreflight.Verdict.PASSES_CHECKED_REQUIREMENTS,
                        CapabilityPreferenceEvaluator.Outcome.AVAILABLE, CapabilityPreferenceEvaluator.Outcome.UNAVAILABLE),
                candidate("excluded", HardConstraintPreflight.Verdict.EXCLUDED,
                        CapabilityPreferenceEvaluator.Outcome.AVAILABLE, CapabilityPreferenceEvaluator.Outcome.AVAILABLE),
                candidate("unresolved", HardConstraintPreflight.Verdict.UNRESOLVED,
                        CapabilityPreferenceEvaluator.Outcome.AVAILABLE, CapabilityPreferenceEvaluator.Outcome.AVAILABLE),
                candidate("unknown-evidence", HardConstraintPreflight.Verdict.PASSES_CHECKED_REQUIREMENTS,
                        CapabilityPreferenceEvaluator.Outcome.UNKNOWN, CapabilityPreferenceEvaluator.Outcome.AVAILABLE)));
        var preview = WeightedComparisonPreview.from(comparison, new WeightedComparisonRequest(
                Map.of(ProviderCatalog.Capability.JIT, 40, ProviderCatalog.Capability.SOCIAL_LOGIN, 60)));

        assertEquals(List.of(ProviderCatalog.Capability.SOCIAL_LOGIN, ProviderCatalog.Capability.JIT),
                preview.weights().keySet().stream().toList());
        assertEquals(60, preview.scores().getFirst().score());
        assertEquals(List.of(60, 0), preview.scores().getFirst().contributions().stream()
                .map(WeightedComparisonPreview.Contribution::earnedPoints).toList());
        assertEquals(List.of(WeightedComparisonPreview.ScoreStatus.SCORED,
                        WeightedComparisonPreview.ScoreStatus.EXCLUDED,
                        WeightedComparisonPreview.ScoreStatus.UNRESOLVED_HARD_CONSTRAINTS,
                        WeightedComparisonPreview.ScoreStatus.UNKNOWN_PREFERENCE_EVIDENCE),
                preview.scores().stream().map(WeightedComparisonPreview.CandidateScore::status).toList());
        for (var withheld : preview.scores().subList(1, 4)) {
            assertNull(withheld.score());
            assertTrue(withheld.contributions().isEmpty());
        }
        assertFalse(preview.rankingPerformed());
        assertFalse(preview.recommendationReady());
        assertSame(comparison, preview.comparison());
        var changed = WeightedComparisonPreview.from(comparison, new WeightedComparisonRequest(
                Map.of(ProviderCatalog.Capability.SOCIAL_LOGIN, 20, ProviderCatalog.Capability.JIT, 80)));
        assertEquals(20, changed.scores().getFirst().score());
        assertNull(changed.scores().get(1).score());
        assertFalse(changed.rankingPerformed());
    }

    @Test
    void requiresOwnerChosenCompletePositiveWeightsWithNoDefaults() {
        var comparison = comparison(List.of(candidate("passing", HardConstraintPreflight.Verdict.PASSES_CHECKED_REQUIREMENTS,
                CapabilityPreferenceEvaluator.Outcome.AVAILABLE, CapabilityPreferenceEvaluator.Outcome.AVAILABLE)));
        for (var invalid : List.of(Map.<ProviderCatalog.Capability, Integer>of(),
                Map.of(ProviderCatalog.Capability.JIT, 100),
                Map.of(ProviderCatalog.Capability.JIT, 50, ProviderCatalog.Capability.SOCIAL_LOGIN, 40),
                Map.of(ProviderCatalog.Capability.JIT, 0, ProviderCatalog.Capability.SOCIAL_LOGIN, 100),
                Map.of(ProviderCatalog.Capability.JIT, 40, ProviderCatalog.Capability.SOCIAL_LOGIN, 40,
                        ProviderCatalog.Capability.SCIM, 20))) {
            assertThrows(InvalidWeightedComparisonRequestException.class,
                    () -> WeightedComparisonPreview.from(comparison, new WeightedComparisonRequest(invalid)));
        }
        assertThrows(InvalidWeightedComparisonRequestException.class,
                () -> WeightedComparisonPreview.from(comparison, new WeightedComparisonRequest(null)));
        assertThrows(InvalidWeightedComparisonRequestException.class,
                () -> WeightedComparisonPreview.from(comparison(List.of(
                        new SyntheticComparison.Candidate("none", "None", "Demo", "Synthetic region",
                                HardConstraintPreflight.Verdict.UNRESOLVED, List.of(), List.of(), List.of()))),
                        new WeightedComparisonRequest(Map.of(ProviderCatalog.Capability.JIT, 100))));
    }

    private static SyntheticComparison comparison(List<SyntheticComparison.Candidate> candidates) {
        return new SyntheticComparison(UUID.randomUUID(), UUID.randomUUID(), 1, "synthetic-test",
                ProviderCatalog.Kind.SYNTHETIC, SyntheticComparison.POLICY_VERSION,
                HardConstraintPreflight.POLICY_VERSION, CapabilityPreferenceEvaluator.POLICY_VERSION, AT,
                "SYNTHETIC_UNRANKED_COMPARISON", false, false, List.of("operations"), candidates);
    }

    private static SyntheticComparison.Candidate candidate(String id, HardConstraintPreflight.Verdict verdict,
            CapabilityPreferenceEvaluator.Outcome social, CapabilityPreferenceEvaluator.Outcome jit) {
        return new SyntheticComparison.Candidate(id, id, "Demo", "Synthetic region", verdict, List.of(), List.of(),
                List.of(preference(ProviderCatalog.Capability.SOCIAL_LOGIN, social),
                        preference(ProviderCatalog.Capability.JIT, jit)));
    }

    private static CapabilityPreferenceEvaluator.Preference preference(ProviderCatalog.Capability capability,
            CapabilityPreferenceEvaluator.Outcome outcome) {
        var reason = switch (outcome) {
            case AVAILABLE -> CapabilityPreferenceEvaluator.Reason.PREFERRED_CAPABILITY_AVAILABLE;
            case UNAVAILABLE -> CapabilityPreferenceEvaluator.Reason.PREFERRED_CAPABILITY_UNAVAILABLE;
            case UNKNOWN -> CapabilityPreferenceEvaluator.Reason.CAPABILITY_UNKNOWN;
        };
        return new CapabilityPreferenceEvaluator.Preference(capability, "protocols.socialLogin", outcome, reason,
                "Synthetic test evidence.", null);
    }
}

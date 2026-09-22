package io.authweave.core.evaluation;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.catalog.ProviderCatalog;
import io.authweave.core.catalog.ProviderCatalog.Availability;
import io.authweave.core.catalog.ProviderCatalog.EvidenceStatus;

import static io.authweave.core.evaluation.CapabilityPreferenceEvaluator.Outcome.*;
import static io.authweave.core.evaluation.CapabilityPreferenceEvaluator.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityPreferenceEvaluatorTests {
    private static final Instant AT = Instant.parse("2026-09-22T12:00:00Z");
    private static final URI SOURCE = URI.create("https://example.invalid/preference");

    @Test
    void onlyExplicitPreferencesUseCurrentReviewedPlanFacts() {
        var checks = List.of(
                check(ProviderCatalog.Capability.OIDC, RequirementCriticality.REQUIRED, fact(Availability.OPTIONAL)),
                check(ProviderCatalog.Capability.SOCIAL_LOGIN, RequirementCriticality.PREFERRED, fact(Availability.OPTIONAL)),
                check(ProviderCatalog.Capability.JIT, RequirementCriticality.PREFERRED, fact(Availability.UNAVAILABLE)),
                check(ProviderCatalog.Capability.MFA, RequirementCriticality.PREFERRED, fact(Availability.MANDATORY)));
        var preferences = CapabilityPreferenceEvaluator.evaluate(checks, AT);
        assertEquals(List.of(ProviderCatalog.Capability.SOCIAL_LOGIN, ProviderCatalog.Capability.JIT,
                ProviderCatalog.Capability.MFA), preferences.stream().map(CapabilityPreferenceEvaluator.Preference::capability).toList());
        assertEquals(List.of(AVAILABLE, UNAVAILABLE, AVAILABLE),
                preferences.stream().map(CapabilityPreferenceEvaluator.Preference::outcome).toList());
        assertEquals(List.of(PREFERRED_CAPABILITY_AVAILABLE, PREFERRED_CAPABILITY_UNAVAILABLE,
                PREFERRED_CAPABILITY_AVAILABLE),
                preferences.stream().map(CapabilityPreferenceEvaluator.Preference::reasonCode).toList());
        assertThrows(UnsupportedOperationException.class, preferences::clear);
    }

    @Test
    void missingUnknownUnreviewedStaleAndFutureFactsNeverBecomePreferenceSupport() {
        var facts = Arrays.asList(null, fact(Availability.UNKNOWN),
                new ProviderCatalog.Fact(Availability.OPTIONAL, EvidenceStatus.UNREVIEWED, SOURCE, AT),
                new ProviderCatalog.Fact(Availability.OPTIONAL, EvidenceStatus.REVIEWED, SOURCE,
                        AT.minus(EvidencePolicy.MAX_AGE).minusNanos(1)),
                new ProviderCatalog.Fact(Availability.UNAVAILABLE, EvidenceStatus.REVIEWED, SOURCE, AT.plusNanos(1)));
        var expected = List.of(EVIDENCE_MISSING, CAPABILITY_UNKNOWN, EVIDENCE_UNREVIEWED,
                EVIDENCE_STALE, EVIDENCE_FROM_FUTURE);
        for (int index = 0; index < facts.size(); index++) {
            var preference = CapabilityPreferenceEvaluator.evaluate(List.of(check(
                    ProviderCatalog.Capability.SCIM, RequirementCriticality.PREFERRED, facts.get(index))), AT).getFirst();
            assertEquals(UNKNOWN, preference.outcome());
            assertEquals(expected.get(index), preference.reasonCode());
        }
        var boundary = new ProviderCatalog.Fact(Availability.OPTIONAL, EvidenceStatus.REVIEWED, SOURCE,
                AT.minus(EvidencePolicy.MAX_AGE));
        assertEquals(AVAILABLE, CapabilityPreferenceEvaluator.evaluate(List.of(check(
                ProviderCatalog.Capability.SCIM, RequirementCriticality.PREFERRED, boundary)), AT).getFirst().outcome());
    }

    private static CapabilityPreflight.Check check(ProviderCatalog.Capability capability,
            RequirementCriticality criticality, ProviderCatalog.Fact fact) {
        return new CapabilityPreflight.Check(capability, "provisioning.scim", criticality,
                CapabilityPreflight.Outcome.NOT_APPLIED, CapabilityPreflight.Reason.PREFERENCE_NOT_SCORED,
                "Preference is not a hard constraint.", fact);
    }

    private static ProviderCatalog.Fact fact(Availability availability) {
        return new ProviderCatalog.Fact(availability, EvidenceStatus.REVIEWED, SOURCE, AT);
    }
}

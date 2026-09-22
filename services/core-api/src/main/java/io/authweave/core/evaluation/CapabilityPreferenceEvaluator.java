package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.List;

import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.catalog.ProviderCatalog;

/** Only explicit PREFERRED capabilities are compared; no numeric weights are inferred. */
public final class CapabilityPreferenceEvaluator {
    public static final String POLICY_VERSION = "capability-preference-1";

    private CapabilityPreferenceEvaluator() { }

    public enum Outcome { AVAILABLE, UNAVAILABLE, UNKNOWN }
    public enum Reason {
        PREFERRED_CAPABILITY_AVAILABLE, PREFERRED_CAPABILITY_UNAVAILABLE, CAPABILITY_UNKNOWN,
        EVIDENCE_MISSING, EVIDENCE_UNREVIEWED, EVIDENCE_STALE, EVIDENCE_FROM_FUTURE
    }

    public record Preference(ProviderCatalog.Capability capability, String profilePath, Outcome outcome,
            Reason reasonCode, String explanation, ProviderCatalog.Fact evidence) { }

    public static List<Preference> evaluate(List<CapabilityPreflight.Check> checks, Instant at) {
        return checks.stream()
                .filter(check -> check.criticality() == RequirementCriticality.PREFERRED)
                .map(check -> evaluate(check, at)).toList();
    }

    private static Preference evaluate(CapabilityPreflight.Check check, Instant at) {
        var fact = check.evidence();
        var problem = EvidencePolicy.problem(fact, at);
        if (problem != null) {
            return new Preference(check.capability(), check.profilePath(), Outcome.UNKNOWN,
                    Reason.valueOf(problem.name()), problem.explanation(), fact);
        }
        return switch (fact.availability()) {
            case OPTIONAL, MANDATORY -> new Preference(check.capability(), check.profilePath(), Outcome.AVAILABLE,
                    Reason.PREFERRED_CAPABILITY_AVAILABLE,
                    "This plan offers the preferred capability; configuration and other trade-offs remain unevaluated.", fact);
            case UNAVAILABLE -> new Preference(check.capability(), check.profilePath(), Outcome.UNAVAILABLE,
                    Reason.PREFERRED_CAPABILITY_UNAVAILABLE,
                    "This plan does not offer the preferred capability; a preference alone never excludes it.", fact);
            case UNKNOWN -> new Preference(check.capability(), check.profilePath(), Outcome.UNKNOWN,
                    Reason.CAPABILITY_UNKNOWN, "Availability is not established for this plan and region.", fact);
        };
    }
}

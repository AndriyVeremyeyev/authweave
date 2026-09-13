package io.authweave.core.evaluation;

import java.util.Collection;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.catalog.ProviderCatalog.Availability;
import io.authweave.core.catalog.ProviderCatalog.ResidencyCoverage;
import io.authweave.core.catalog.ProviderCatalog.Support;
import static io.authweave.core.evaluation.CapabilityPreflight.Outcome.*;

/** Assertion-level predicates only. Callers must independently establish evidence and scope applicability.
 * Production preflights retain their evidence gates; hypothetical probes never constitute that verification. */
public final class ClaimRules {
    public static final String VERSION = "assertion-claim-rules-1";
    private ClaimRules() { }

    public static CapabilityPreflight.Outcome capability(RequirementCriticality criticality, Availability availability) {
        return switch (criticality) {
            case NOT_REQUIRED, PREFERRED -> NOT_APPLIED;
            case UNKNOWN -> UNKNOWN;
            case REQUIRED, FORBIDDEN -> availability == null || availability == Availability.UNKNOWN ? UNKNOWN
                    : criticality == RequirementCriticality.REQUIRED
                        ? availability == Availability.UNAVAILABLE ? FAIL : PASS
                        : availability == Availability.MANDATORY ? FAIL : PASS;
        };
    }
    public static CapabilityPreflight.Outcome compatibility(Support support) {
        return support == null || support == Support.UNKNOWN ? UNKNOWN : support == Support.SUPPORTED ? PASS : FAIL;
    }
    public static CapabilityPreflight.Outcome residency(ResidencyCoverage coverage, Collection<String> countries, Collection<String> allowed) {
        if (coverage == null || coverage == ResidencyCoverage.UNKNOWN || countries.isEmpty() || allowed.isEmpty()) return UNKNOWN;
        if (countries.stream().anyMatch(country -> !allowed.contains(country))) return FAIL;
        return coverage == ResidencyCoverage.COMPLETE ? PASS : UNKNOWN;
    }
    public static CapabilityPreflight.Outcome authentication(Support availability, Support enforcement) {
        if (availability == Support.UNSUPPORTED || enforcement == Support.UNSUPPORTED) return FAIL;
        return availability == Support.SUPPORTED && enforcement == Support.SUPPORTED ? PASS : UNKNOWN;
    }
}

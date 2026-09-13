package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.catalog.ProviderCatalog;

import static io.authweave.core.evaluation.CapabilityPreflight.Outcome.*;
import static io.authweave.core.evaluation.CapabilityPreflight.Status.*;

public final class EligibilityEvaluator {
    public static final String POLICY_VERSION = "eligibility-preflight-1";
    public static final List<String> DEFERRED_PATHS = List.of(
            "security.browserTokenExposureMinimization", "security.auditability", "security.dataResidency",
            "security.assurance", "security.complianceTargets", "operations");
    private EligibilityEvaluator() { }

    public static List<EligibilityPreflight.Candidate> evaluate(
            ApplicationIdentityProfile profile, ProviderCatalog catalog, Instant at) {
        var capabilities = CapabilityEvaluator.evaluate(profile, catalog, at).stream()
                .collect(Collectors.toMap(CapabilityPreflight.Candidate::optionId, CapabilityPreflight.Candidate::checks));
        return catalog.options().stream().sorted(Comparator.comparing(ProviderCatalog.Option::id)).map(option -> {
            var capabilityChecks = capabilities.get(option.id());
            var contextChecks = TopologyEvaluator.evaluate(profile, option.compatibility(), at);
            var outcomes = Stream.concat(capabilityChecks.stream().map(CapabilityPreflight.Check::outcome),
                    contextChecks.stream().map(EligibilityPreflight.ContextCheck::outcome)).toList();
            var status = DOES_NOT_MATCH;
            if (!outcomes.contains(FAIL)) {
                status = outcomes.contains(UNKNOWN) || !outcomes.contains(PASS)
                        ? NEEDS_INFORMATION : MATCHES_CHECKED_REQUIREMENTS;
            }
            return new EligibilityPreflight.Candidate(option.id(), option.displayName(), option.plan(), option.region(),
                    status, capabilityChecks, contextChecks);
        }).toList();
    }
}

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
    public static final String RESIDENCY_POLICY_VERSION = "eligibility-preflight-2";
    public static final String AUTHENTICATION_POLICY_VERSION = "eligibility-preflight-3";
    public static final List<String> DEFERRED_PATHS = List.of(
            "security.browserTokenExposureMinimization", "security.auditability", "security.dataResidency",
            "security.assurance", "security.complianceTargets", "operations");
    public static final List<String> RESIDENCY_DEFERRED_PATHS = DEFERRED_PATHS.stream()
            .filter(path -> !path.equals("security.dataResidency")).toList();
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
            return new EligibilityPreflight.Candidate(option.id(), option.displayName(), option.plan(), option.region(),
                    status(outcomes), capabilityChecks, contextChecks);
        }).toList();
    }

    public static List<EligibilityPreflightV2.Candidate> evaluateWithResidency(
            ApplicationIdentityProfile profile, ProviderCatalog catalog, Instant at) {
        var options = catalog.options().stream().collect(Collectors.toMap(ProviderCatalog.Option::id, option -> option));
        return evaluate(profile, catalog, at).stream().map(base -> {
            var residency = ResidencyEvaluator.evaluate(profile.security(), options.get(base.optionId()).residency(), at);
            var outcomes = Stream.of(base.capabilityChecks().stream().map(CapabilityPreflight.Check::outcome),
                    base.contextChecks().stream().map(EligibilityPreflight.ContextCheck::outcome),
                    residency.stream().map(ResidencyCheck::outcome)).flatMap(stream -> stream).toList();
            return new EligibilityPreflightV2.Candidate(base.optionId(), base.displayName(), base.plan(), base.region(),
                    status(outcomes), base.capabilityChecks(), base.contextChecks(), residency);
        }).toList();
    }

    public static List<EligibilityPreflightV3.Candidate> evaluateWithAuthenticationControls(
            ApplicationIdentityProfile profile, ProviderCatalog catalog, Instant at) {
        var options = catalog.options().stream().collect(Collectors.toMap(ProviderCatalog.Option::id, option -> option));
        return evaluateWithResidency(profile, catalog, at).stream().map(base -> {
            var controls = AuthenticationControlEvaluator.evaluate(profile, options.get(base.optionId()), at);
            var outcomes = Stream.of(base.capabilityChecks().stream().map(CapabilityPreflight.Check::outcome),
                    base.contextChecks().stream().map(EligibilityPreflight.ContextCheck::outcome),
                    base.residencyChecks().stream().map(ResidencyCheck::outcome),
                    controls.stream().map(AuthenticationControlCheck::outcome)).flatMap(stream -> stream).toList();
            return new EligibilityPreflightV3.Candidate(base.optionId(), base.displayName(), base.plan(), base.region(),
                    status(outcomes), base.capabilityChecks(), base.contextChecks(), base.residencyChecks(), controls);
        }).toList();
    }

    private static CapabilityPreflight.Status status(List<CapabilityPreflight.Outcome> outcomes) {
        if (outcomes.contains(FAIL)) return DOES_NOT_MATCH;
        return outcomes.contains(UNKNOWN) || !outcomes.contains(PASS) ? NEEDS_INFORMATION : MATCHES_CHECKED_REQUIREMENTS;
    }
}

package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.UserPopulation;
import io.authweave.core.catalog.ProviderCatalog.*;

import static io.authweave.core.evaluation.AuthenticationControlCheck.Reason.*;
import static io.authweave.core.evaluation.CapabilityPreflight.Outcome.*;

/** Capability preflight only: no inference from broad assurance labels or certification claims. */
public final class AuthenticationControlEvaluator {
    public static final String POLICY_VERSION = "authentication-controls-preflight-1";
    private AuthenticationControlEvaluator() { }

    public static List<AuthenticationControlCheck> evaluate(ApplicationIdentityProfile profile, Option option, Instant at) {
        var clients = profile.application().clients().stream().filter(c -> c != ClientType.MACHINE_TO_MACHINE)
                .sorted(Comparator.comparing(Enum::name)).toList();
        var populations = profile.audience().populations().stream().sorted(Comparator.comparing(Enum::name)).toList();
        var checks = new ArrayList<AuthenticationControlCheck>();
        // Null is an explicit missing/not-applicable scope, never a wildcard for evidence lookup.
        for (var client : clients.isEmpty() ? java.util.Collections.<ClientType>singletonList(null) : clients) {
            for (var population : client == null || populations.isEmpty()
                    ? java.util.Collections.<UserPopulation>singletonList(null) : populations) {
                for (var control : AuthenticationControl.values()) {
                    var fact = client == null || population == null ? null
                            : option.authenticationControls().getOrDefault(client, Map.of())
                                    .getOrDefault(population, Map.of()).get(control);
                    checks.add(check(profile, control, client, population, fact, at));
                }
            }
        }
        return List.copyOf(checks);
    }

    private static AuthenticationControlCheck check(ApplicationIdentityProfile profile, AuthenticationControl control,
            ClientType client, UserPopulation population, AuthenticationControlFact fact, Instant at) {
        var controls = profile.security().authenticationControls();
        var criticality = switch (control) {
            case PHISHING_RESISTANCE -> controls.phishingResistance();
            case NON_EXPORTABLE_KEYS -> controls.nonExportableKeys();
            case STEP_UP_AUTHENTICATION -> controls.stepUpAuthentication();
        };
        var field = switch (control) {
            case PHISHING_RESISTANCE -> "phishingResistance";
            case NON_EXPORTABLE_KEYS -> "nonExportableKeys";
            case STEP_UP_AUTHENTICATION -> "stepUpAuthentication";
        };
        CapabilityPreflight.Outcome outcome = UNKNOWN;
        AuthenticationControlCheck.Reason reason;
        String explanation;
        if (client == null && profile.application().clients().contains(ClientType.MACHINE_TO_MACHINE)) {
            outcome = NOT_APPLIED;
            reason = HUMAN_AUTH_NOT_APPLICABLE;
            explanation = "These controls concern human authentication, not workload credentials.";
        } else switch (criticality) {
            case NOT_REQUIRED -> {
                outcome = NOT_APPLIED; reason = NO_REQUIREMENT;
                explanation = "This control is not required; no assurance claim follows.";
            }
            case PREFERRED -> {
                outcome = NOT_APPLIED; reason = PREFERENCE_NOT_SCORED;
                explanation = "This preference is recorded but not scored or used to eliminate options.";
            }
            case UNKNOWN -> {
                reason = REQUIREMENT_UNKNOWN;
                explanation = "Clarify this independent requirement; assurance labels do not set it automatically.";
            }
            case FORBIDDEN -> {
                reason = CONTROL_INTENT_UNCLEAR;
                explanation = "Clarify the intended prohibition; it is not interpreted as a request for weaker authentication.";
            }
            case REQUIRED -> {
                var problem = EvidencePolicy.problem(fact, at);
                if (client == null) {
                    reason = CLIENT_SCOPE_UNKNOWN; explanation = "Select the human client types covered by this requirement.";
                } else if (population == null) {
                    reason = POPULATION_SCOPE_UNKNOWN; explanation = "Select the user populations covered by this requirement.";
                } else if (problem != null) {
                    reason = AuthenticationControlCheck.Reason.valueOf(problem.name()); explanation = problem.explanation();
                } else if (fact.availability() == Support.UNSUPPORTED) {
                    outcome = FAIL; reason = CONTROL_UNAVAILABLE;
                    explanation = "Reviewed scoped evidence establishes that the control is unavailable.";
                } else if (fact.enforcement() == Support.UNSUPPORTED) {
                    outcome = FAIL; reason = ENFORCEMENT_UNSUPPORTED;
                    explanation = "The control cannot be required for this client and population, even if optionally available.";
                } else if (fact.availability() == Support.UNKNOWN) {
                    reason = CONTROL_AVAILABILITY_UNKNOWN; explanation = "Availability has not been established for this scope.";
                } else if (fact.enforcement() == Support.UNKNOWN) {
                    reason = ENFORCEMENT_UNKNOWN; explanation = "Availability alone does not establish the ability to require this control.";
                } else {
                    outcome = PASS; reason = CONTROL_ENFORCEABLE;
                    explanation = "Reviewed evidence supports requiring this control in the scoped human flow. Deployed configuration, sensitive-action wiring, enrollment, recovery and full assurance remain unverified.";
                }
            }
            default -> throw new IllegalStateException("Unsupported authentication criticality");
        }
        return new AuthenticationControlCheck("security.authenticationControls." + field, criticality, control,
                client, population, outcome, reason, explanation, fact);
    }
}

package io.authweave.core.evaluation;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.assessment.domain.profile.ProtocolRequirements.FederationProtocol;
import io.authweave.core.catalog.ProviderCatalog;
import io.authweave.core.catalog.ProviderCatalog.Fact;

import static io.authweave.core.catalog.ProviderCatalog.Availability.MANDATORY;
import static io.authweave.core.catalog.ProviderCatalog.Availability.UNAVAILABLE;
import static io.authweave.core.catalog.ProviderCatalog.Capability.*;
import static io.authweave.core.catalog.ProviderCatalog.EvidenceStatus.REVIEWED;
import static io.authweave.core.evaluation.CapabilityPreflight.*;
import static io.authweave.core.evaluation.CapabilityPreflight.Outcome.*;
import static io.authweave.core.evaluation.CapabilityPreflight.Reason.*;
import static io.authweave.core.evaluation.CapabilityPreflight.Status.*;

/** Pure function: reproducible for the same profile, catalog and evaluation instant. */
public final class CapabilityEvaluator {

    public static final String POLICY_VERSION = "capability-preflight-1";
    public static final Duration MAX_EVIDENCE_AGE = Duration.ofDays(90);
    public static final List<String> DEFERRED_PATHS = List.of(
            "application", "audience", "security.browserTokenExposureMinimization",
            "security.auditability", "security.dataResidency", "security.assurance",
            "security.complianceTargets", "operations");

    private CapabilityEvaluator() { }

    public static List<Candidate> evaluate(ApplicationIdentityProfile profile, ProviderCatalog catalog, Instant at) {
        var requirements = requirements(profile);
        return catalog.options().stream().sorted(Comparator.comparing(ProviderCatalog.Option::id)).map(option -> {
            var checks = requirements.stream()
                    .map(requirement -> check(requirement, option.facts().get(requirement.capability()), at)).toList();
            return new Candidate(option.id(), option.displayName(), option.plan(), option.region(), status(checks), checks);
        }).toList();
    }

    private static Status status(List<Check> checks) {
        if (checks.stream().anyMatch(check -> check.outcome() == FAIL)) return DOES_NOT_MATCH;
        if (checks.stream().anyMatch(check -> check.outcome() == UNKNOWN)
                || checks.stream().noneMatch(check -> check.outcome() == PASS)) return NEEDS_INFORMATION;
        return MATCHES_CHECKED_REQUIREMENTS;
    }

    private static Check check(Requirement requirement, Fact fact, Instant at) {
        return switch (requirement.criticality()) {
            case NOT_REQUIRED -> result(requirement, fact, NOT_APPLIED, NO_REQUIREMENT,
                    "No constraint was requested for this capability.");
            case PREFERRED -> result(requirement, fact, NOT_APPLIED, PREFERENCE_NOT_SCORED,
                    "This is a preference, not an elimination rule. Scoring is not implemented in this preflight.");
            case UNKNOWN -> result(requirement, fact, UNKNOWN, REQUIREMENT_UNKNOWN,
                    "Clarify whether this capability is required, preferred, not required or forbidden.");
            case REQUIRED, FORBIDDEN -> hardConstraint(requirement, fact, at);
        };
    }

    private static Check hardConstraint(Requirement requirement, Fact fact, Instant at) {
        if (fact == null) return result(requirement, null, UNKNOWN, EVIDENCE_MISSING,
                "This plan and region have no recorded fact for the capability.");
        if (fact.evidenceStatus() != REVIEWED) return result(requirement, fact, UNKNOWN, EVIDENCE_UNREVIEWED,
                "The recorded fact has not been reviewed; it cannot establish a match or an exclusion.");
        if (fact.observedAt().isAfter(at)) return result(requirement, fact, UNKNOWN, EVIDENCE_FROM_FUTURE,
                "The observation is later than the evaluation instant and cannot be used.");
        if (fact.observedAt().isBefore(at.minus(MAX_EVIDENCE_AGE))) return result(requirement, fact, UNKNOWN, EVIDENCE_STALE,
                "The observation is older than the 90-day preflight policy; review it before deciding.");
        if (fact.availability() == ProviderCatalog.Availability.UNKNOWN) return result(requirement, fact, UNKNOWN, CAPABILITY_UNKNOWN,
                "The capability's availability is not established for this plan and region.");
        if (requirement.criticality() == RequirementCriticality.REQUIRED) {
            return fact.availability() == UNAVAILABLE
                    ? result(requirement, fact, FAIL, REQUIRED_CAPABILITY_UNAVAILABLE, "This plan does not offer the required capability.")
                    : result(requirement, fact, PASS, REQUIRED_CAPABILITY_AVAILABLE, "This plan offers the required capability.");
        }
        return fact.availability() == MANDATORY
                ? result(requirement, fact, FAIL, FORBIDDEN_CAPABILITY_UNAVOIDABLE, "The forbidden capability cannot be disabled in this plan.")
                : result(requirement, fact, PASS, FORBIDDEN_CAPABILITY_AVOIDABLE,
                        "The forbidden capability is absent or can be disabled; it must remain disabled in the chosen configuration.");
    }

    private static Check result(Requirement requirement, Fact fact, Outcome outcome, Reason code, String explanation) {
        return new Check(requirement.capability(), requirement.path(), requirement.criticality(), outcome, code, explanation, fact);
    }

    private static List<Requirement> requirements(ApplicationIdentityProfile p) {
        return List.of(
                new Requirement(OIDC, "protocols.federation.OIDC", p.protocols().criticalityOf(FederationProtocol.OIDC)),
                new Requirement(SAML, "protocols.federation.SAML", p.protocols().criticalityOf(FederationProtocol.SAML)),
                new Requirement(OAUTH2_APIS, "protocols.oauth2ProtectedApis", p.protocols().oauth2ProtectedApis()),
                new Requirement(SOCIAL_LOGIN, "protocols.socialLogin", p.protocols().socialLogin()),
                new Requirement(ENTERPRISE_SSO, "protocols.enterpriseSingleSignOn", p.protocols().enterpriseSingleSignOn()),
                new Requirement(SCIM, "provisioning.scim", p.provisioning().scim()),
                new Requirement(JIT, "provisioning.justInTimeProvisioning", p.provisioning().justInTimeProvisioning()),
                new Requirement(GROUP_SYNC, "provisioning.groupSynchronization", p.provisioning().groupSynchronization()),
                new Requirement(MFA, "security.multiFactorAuthentication", p.security().multiFactorAuthentication()));
    }

    private record Requirement(ProviderCatalog.Capability capability, String path, RequirementCriticality criticality) { }
}

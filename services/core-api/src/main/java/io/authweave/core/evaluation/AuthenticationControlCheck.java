package io.authweave.core.evaluation;

import io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.UserPopulation;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.catalog.ProviderCatalog.AuthenticationControl;
import io.authweave.core.catalog.ProviderCatalog.AuthenticationControlFact;

public record AuthenticationControlCheck(String profilePath, RequirementCriticality criticality,
        AuthenticationControl control, ClientType client, UserPopulation population,
        CapabilityPreflight.Outcome outcome, Reason reasonCode, String explanation, AuthenticationControlFact evidence) {
    public enum Reason {
        CONTROL_ENFORCEABLE, CONTROL_UNAVAILABLE, CONTROL_AVAILABILITY_UNKNOWN,
        ENFORCEMENT_UNSUPPORTED, ENFORCEMENT_UNKNOWN, CLIENT_SCOPE_UNKNOWN, POPULATION_SCOPE_UNKNOWN,
        HUMAN_AUTH_NOT_APPLICABLE, CONTROL_INTENT_UNCLEAR,
        REQUIREMENT_UNKNOWN, PREFERENCE_NOT_SCORED, NO_REQUIREMENT,
        EVIDENCE_MISSING, EVIDENCE_UNREVIEWED, EVIDENCE_FROM_FUTURE, EVIDENCE_STALE
    }
}

package io.authweave.core.evaluation;

import java.util.List;

import io.authweave.core.assessment.domain.profile.DataResidencyDetails.DataCategory;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.catalog.ProviderCatalog.ResidencyFact;

/** A scoped at-rest check, not a compliance or international-transfer decision. */
public record ResidencyCheck(String profilePath, RequirementCriticality criticality, DataCategory dataCategory,
        List<String> allowedCountries, List<String> outsideAllowedCountries, CapabilityPreflight.Outcome outcome,
        Reason reasonCode, String explanation, ResidencyFact evidence) {
    public ResidencyCheck {
        allowedCountries = List.copyOf(allowedCountries);
        outsideAllowedCountries = List.copyOf(outsideAllowedCountries);
    }

    public enum Reason {
        STORAGE_WITHIN_ALLOWED_COUNTRIES, STORAGE_OUTSIDE_ALLOWED_COUNTRIES,
        STORAGE_LOCATIONS_INCOMPLETE, STORAGE_LOCATIONS_UNKNOWN,
        DATA_SCOPE_UNKNOWN, ALLOWED_COUNTRIES_UNKNOWN, RESIDENCY_INTENT_UNCLEAR,
        REQUIREMENT_UNKNOWN, PREFERENCE_NOT_SCORED, NO_REQUIREMENT,
        EVIDENCE_MISSING, EVIDENCE_UNREVIEWED, EVIDENCE_FROM_FUTURE, EVIDENCE_STALE
    }
}

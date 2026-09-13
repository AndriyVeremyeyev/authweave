package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import io.authweave.core.assessment.domain.profile.DataResidencyDetails.DataCategory;
import io.authweave.core.assessment.domain.profile.SecurityRequirements;
import io.authweave.core.catalog.ProviderCatalog.ResidencyFact;
import io.authweave.core.catalog.ProviderCatalog.ResidencyCoverage;

import static io.authweave.core.evaluation.CapabilityPreflight.Outcome.*;
import static io.authweave.core.evaluation.ResidencyCheck.Reason.*;

public final class ResidencyEvaluator {
    public static final String POLICY_VERSION = "residency-preflight-1";
    private ResidencyEvaluator() { }

    public static List<ResidencyCheck> evaluate(SecurityRequirements security,
            Map<DataCategory, ResidencyFact> facts, Instant at) {
        var categories = security.dataResidencyDetails().dataCategories();
        if (categories.isEmpty()) return List.of(check(security, null, null, at));
        return categories.stream().sorted(Comparator.comparing(Enum::name))
                .map(category -> check(security, category, facts.get(category), at)).toList();
    }

    private static ResidencyCheck check(SecurityRequirements security, DataCategory category, ResidencyFact fact, Instant at) {
        var countries = security.dataResidencyDetails().allowedCountries().stream().sorted().toList();
        var criticality = security.dataResidency();
        CapabilityPreflight.Outcome outcome = UNKNOWN;
        ResidencyCheck.Reason reason;
        String explanation;
        List<String> outside = List.of();
        switch (criticality) {
            case NOT_REQUIRED -> {
                outcome = NOT_APPLIED;
                reason = NO_REQUIREMENT;
                explanation = "No at-rest residency constraint is imposed; this does not establish compliance.";
            }
            case PREFERRED -> {
                outcome = NOT_APPLIED;
                reason = PREFERENCE_NOT_SCORED;
                explanation = "Residency is a preference, not an elimination rule. Preference scoring is not implemented.";
            }
            case UNKNOWN -> {
                reason = REQUIREMENT_UNKNOWN;
                explanation = "Clarify whether at-rest data residency is required before relying on this check.";
            }
            case FORBIDDEN -> {
                reason = RESIDENCY_INTENT_UNCLEAR;
                explanation = "Clarify the intended residency prohibition. An allowlist is not interpreted as a country denylist.";
            }
            case REQUIRED -> {
                var problem = EvidencePolicy.problem(fact, at);
                if (category == null) {
                    reason = DATA_SCOPE_UNKNOWN;
                    explanation = "Select the categories of data covered by the at-rest residency requirement.";
                } else if (countries.isEmpty()) {
                    reason = ALLOWED_COUNTRIES_UNKNOWN;
                    explanation = "Specify allowed countries. An empty list is unrecorded, not unrestricted.";
                } else if (problem != null) {
                    reason = ResidencyCheck.Reason.valueOf(problem.name());
                    explanation = problem.explanation();
                } else if (fact.coverage() == ResidencyCoverage.UNKNOWN) {
                    reason = STORAGE_LOCATIONS_UNKNOWN;
                    explanation = "Storage countries for this data category have not been established for the option.";
                } else {
                    outside = fact.storageCountries().stream().filter(country -> !countries.contains(country)).toList();
                    if (!outside.isEmpty()) {
                        outcome = FAIL;
                        reason = STORAGE_OUTSIDE_ALLOWED_COUNTRIES;
                        explanation = "Reviewed evidence confirms storage outside the allowed countries for this category and option.";
                    } else if (fact.coverage() == ResidencyCoverage.COMPLETE) {
                        outcome = PASS;
                        reason = STORAGE_WITHIN_ALLOWED_COUNTRIES;
                        explanation = "Complete reviewed evidence places all storage for this category within the allowed countries. No claim is made about other categories.";
                    } else {
                        reason = STORAGE_LOCATIONS_INCOMPLETE;
                        explanation = "Known storage countries are allowed, but incomplete evidence cannot rule out other destinations.";
                    }
                }
            }
            default -> throw new IllegalStateException("Unsupported residency criticality");
        }
        return new ResidencyCheck("security.dataResidency", criticality, category, countries, outside,
                outcome, reason, explanation, fact);
    }
}

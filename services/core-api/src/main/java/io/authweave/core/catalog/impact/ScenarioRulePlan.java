package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.catalog.ProviderCatalog;
import io.authweave.core.catalog.draft.CatalogChangePreview.FactKind;
import io.authweave.core.evaluation.*;
import static io.authweave.core.assessment.domain.profile.RequirementCriticality.REQUIRED;

/** Derive scope and non-evidence decisions from the existing preflights, using NO provider facts.
 * The empty synthetic option is a scope probe, never a draft adapter or an evidence-approved candidate. */
final class ScenarioRulePlan {
    private static final ProviderCatalog.Option EMPTY = new ProviderCatalog.Option("scope-probe", "Scope only", "None", "None",
            Map.of(), ProviderCatalog.Compatibility.empty(), Map.of(), Map.of());
    private static final ProviderCatalog EMPTY_CATALOG = new ProviderCatalog(4, "scope-only", ProviderCatalog.Kind.SYNTHETIC, List.of(EMPTY));
    private ScenarioRulePlan() { }

    record Rule(String checkId, String profilePath, String factPath, FactKind kind,
            RequirementCriticality criticality, List<String> allowedCountries,
            CapabilityPreflight.Outcome presetOutcome, String presetReason) {
        boolean usesFact() { return presetReason.equals("EVIDENCE_MISSING"); }
        CatalogImpactCases.Probe probe() {
            return new CatalogImpactCases.Probe(checkId, "Profile-scoped conditional rule", kind, factPath, criticality, allowedCountries);
        }
    }

    static List<Rule> from(ApplicationIdentityProfile profile) {
        var rules = new ArrayList<Rule>();
        for (var c : CapabilityEvaluator.evaluate(profile, EMPTY_CATALOG, Instant.EPOCH).getFirst().checks()) {
            add(rules, c.profilePath(), "facts." + c.capability(), FactKind.CAPABILITY, c.criticality(), List.of(), c.outcome(), c.reasonCode().name());
        }
        for (var c : TopologyEvaluator.evaluate(profile, EMPTY.compatibility(), Instant.EPOCH)) {
            var dimension = switch (c.dimension()) {
                case APPLICATION_TYPE -> "applications"; case CLIENT_TYPE -> "clients"; case USER_POPULATION -> "populations";
                case TENANCY -> "tenancy"; case MEMBERSHIP -> "membership";
            };
            String path = c.reasonCode() == EligibilityPreflight.Reason.EVIDENCE_MISSING
                    ? "compatibility." + dimension + "." + c.requestedValue() : null;
            add(rules, c.profilePath(), path, FactKind.COMPATIBILITY, REQUIRED, List.of(), c.outcome(), c.reasonCode().name());
        }
        for (var c : ResidencyEvaluator.evaluate(profile.security(), Map.of(), Instant.EPOCH)) {
            add(rules, c.profilePath(), c.dataCategory() == null ? null : "residency." + c.dataCategory(), FactKind.RESIDENCY,
                    c.criticality(), c.allowedCountries(), c.outcome(), c.reasonCode().name());
        }
        for (var c : AuthenticationControlEvaluator.evaluate(profile, EMPTY, Instant.EPOCH)) {
            String path = c.client() == null || c.population() == null ? null
                    : "authenticationControls." + c.client() + "." + c.population() + "." + c.control();
            add(rules, c.profilePath(), path, FactKind.AUTHENTICATION_CONTROL, c.criticality(), List.of(), c.outcome(), c.reasonCode().name());
        }
        var compliance = ComplianceScopeEvaluator.evaluate(profile.security());
        add(rules, compliance.profilePath(), null, null, REQUIRED, List.of(), compliance.outcome(), compliance.reasonCode().name());
        return List.copyOf(rules);
    }

    private static void add(List<Rule> rules, String profilePath, String factPath, FactKind kind,
            RequirementCriticality criticality, List<String> countries, CapabilityPreflight.Outcome outcome, String reason) {
        rules.add(new Rule(profilePath + "|" + (factPath == null ? "scope" : factPath), profilePath, factPath,
                kind, criticality, List.copyOf(countries), outcome, reason));
    }
}

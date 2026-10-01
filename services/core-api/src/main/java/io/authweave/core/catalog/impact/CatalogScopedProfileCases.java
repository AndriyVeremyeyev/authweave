package io.authweave.core.catalog.impact;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfileValidator;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;

/** Additional required-scope regression inputs, never replacements for historical frozen scenarios or user defaults. */
@Component
public final class CatalogScopedProfileCases {
    public static final String VERSION = "catalog-scoped-profile-scenarios-1";
    public static final int COUNT = 4;
    public static final Set<String> IDS = Set.of("b2b-saas-scoped", "partner-portal-scoped", "public-sector-scoped", "internal-workforce-scoped");
    private final List<CatalogScenarioCases.Definition> definitions;
    private final List<List<ScenarioRulePlan.Rule>> plans;
    private final String sha256;

    public CatalogScopedProfileCases(ObjectMapper mapper) throws IOException {
        try (var input = new ClassPathResource("catalog/scoped-impact-scenarios.v1.json").getInputStream()) {
            definitions = List.of(mapper.readValue(input, CatalogScenarioCases.Definition[].class));
        }
        if (definitions.size() != COUNT || !definitions.stream().map(CatalogScenarioCases.Definition::id).collect(Collectors.toSet()).equals(IDS)
                || definitions.stream().anyMatch(d -> d.profileSchemaVersion() != 5))
            throw new IllegalStateException("Review scoped profile scenario definitions");
        plans = definitions.stream().map(d -> {
            var profile = mapper.treeToValue(d.profile(), ApplicationIdentityProfile.class);
            if (!ApplicationIdentityProfileValidator.validate(profile).issues().isEmpty()) throw new IllegalStateException("Invalid scoped profile");
            var plan = ScenarioRulePlan.from(profile);
            if (plan.size() > 100 || plan.stream().map(ScenarioRulePlan.Rule::checkId).distinct().count() != plan.size())
                throw new IllegalStateException("Review scoped profile rule bounds");
            return plan;
        }).toList();
        var dependencies = plans.stream().flatMap(List::stream).filter(ScenarioRulePlan.Rule::usesFact)
                .map(ScenarioRulePlan.Rule::factPath).collect(Collectors.toSet());
        var declared = CatalogFactPathRegressionCases.PROBES.stream().map(CatalogImpactCases.Probe::factPath).collect(Collectors.toSet());
        if (!dependencies.equals(declared)) throw new IllegalStateException("Review scoped profile fact dependencies");
        sha256 = CatalogDraftCanonicalizer.sha256(definitions);
    }
    public List<CatalogScenarioCases.Definition> definitions() { return definitions; }
    List<List<ScenarioRulePlan.Rule>> plans() { return plans; }
    public String sha256() { return sha256; }
}

package io.authweave.core.catalog.impact;

import java.io.IOException;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfileValidator;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;

/** Frozen full-profile regression inputs; not saved assessments and never supplied by the caller. */
@Component
public final class CatalogScenarioCases {
    public static final String VERSION = "catalog-profile-scenarios-1";
    private final List<Definition> definitions;
    private final List<List<ScenarioRulePlan.Rule>> plans;
    private final String sha256;
    public record Definition(String id, String description, int profileSchemaVersion, JsonNode profile) {
        public Definition { profile = profile.deepCopy(); }
        @Override public JsonNode profile() { return profile.deepCopy(); }
    }
    public CatalogScenarioCases(ObjectMapper mapper) throws IOException {
        try (var input = new ClassPathResource("catalog/impact-scenarios.v1.json").getInputStream()) {
            definitions = List.of(mapper.readValue(input, Definition[].class));
        }
        if (definitions.size() != 3 || definitions.stream().map(Definition::id).distinct().count() != 3
                || definitions.stream().anyMatch(d -> d.profileSchemaVersion() != 5)) throw new IllegalStateException("Invalid scenario definitions");
        plans = definitions.stream().map(d -> {
            var profile = mapper.treeToValue(d.profile(), ApplicationIdentityProfile.class);
            if (!ApplicationIdentityProfileValidator.validate(profile).issues().isEmpty()) throw new IllegalStateException("Invalid scenario profile");
            return ScenarioRulePlan.from(profile);
        }).toList();
        sha256 = CatalogDraftCanonicalizer.sha256(definitions);
    }
    public List<Definition> definitions() { return definitions; }
    List<List<ScenarioRulePlan.Rule>> plans() { return plans; }
    public String sha256() { return sha256; }
}

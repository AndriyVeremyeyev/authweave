package io.authweave.core.catalog.impact;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.assessment.domain.profile.*;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;

/** Explicit supplemental synthetic requirements. Never mutate frozen v5 profiles or infer user defaults. */
@Component
public final class CatalogAuditabilityRegressionCases {
    public static final String VERSION = "catalog-scoped-auditability-scenarios-1";
    public static final int PROFILE_SCHEMA_VERSION = 6;
    public static final String PROFILE_SCHEMA_SHA256 = "3d384daf2d98851ebcbe00f90a9c6dabeedbae8361f8a41f58d00b9742c95fbb";
    public record Input(String scenarioId, String description, List<Criterion> selectedCriteria,
            @JsonProperty(required = true) Integer minimumRetentionDays) {
        public Input {
            selectedCriteria = List.copyOf(selectedCriteria);
            if (!CatalogScopedProfileCases.IDS.contains(scenarioId) || description == null || description.isBlank()
                    || description.length() > 300 || selectedCriteria.isEmpty() || selectedCriteria.size() > Criterion.values().length
                    || new HashSet<>(selectedCriteria).size() != selectedCriteria.size())
                throw new IllegalArgumentException("Invalid auditability regression requirement input");
            new AuditabilityRequirements(Set.copyOf(selectedCriteria), minimumRetentionDays);
        }
        AuditabilityRequirements requirements() { return new AuditabilityRequirements(Set.copyOf(selectedCriteria), minimumRetentionDays); }
    }
    public record Suite(int schemaVersion, String baseScenarioSetVersion, String baseScenarioSetSha256, List<Input> scenarios) {
        public Suite {
            scenarios = List.copyOf(scenarios);
            if (schemaVersion != 1 || !CatalogScopedProfileCases.VERSION.equals(baseScenarioSetVersion)
                    || baseScenarioSetSha256 == null || !baseScenarioSetSha256.matches("[a-f0-9]{64}")
                    || scenarios.size() != CatalogScopedProfileCases.COUNT
                    || !new HashSet<>(scenarios.stream().map(Input::scenarioId).toList()).equals(CatalogScopedProfileCases.IDS)
                    || scenarios.stream().flatMap(s -> s.selectedCriteria().stream()).distinct().count() != Criterion.values().length)
                throw new IllegalArgumentException("Incomplete auditability regression scenario suite");
        }
    }
    public record Definition(String scenarioId, RequirementCriticality criticality,
            AuditabilityRequirements requirements, String profileSha256) {
        public Definition {
            Objects.requireNonNull(requirements);
            if (!CatalogScopedProfileCases.IDS.contains(scenarioId) || criticality != RequirementCriticality.REQUIRED
                    || requirements.isUnrecorded() || profileSha256 == null || !profileSha256.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid compiled auditability regression scenario");
        }
    }
    private final List<Definition> definitions;
    private final String sha256;
    private final String baseScenarioSetSha256;
    public CatalogAuditabilityRegressionCases(ObjectMapper mapper, CatalogScopedProfileCases base) throws IOException {
        Suite suite;
        try (var input = new ClassPathResource("catalog/scoped-auditability-scenarios.v1.json").getInputStream()) {
            suite = mapper.readValue(input, Suite.class);
        }
        definitions = compile(mapper, base, suite);
        baseScenarioSetSha256 = base.sha256();
        sha256 = CatalogDraftCanonicalizer.sha256(List.of(VERSION, PROFILE_SCHEMA_VERSION, PROFILE_SCHEMA_SHA256,
                baseScenarioSetSha256, suite, definitions));
    }
    static List<Definition> compile(ObjectMapper mapper, CatalogScopedProfileCases base, Suite suite) {
        if (!suite.baseScenarioSetSha256().equals(base.sha256())
                || !CatalogDraftCanonicalizer.sha256(base.definitions()).equals(base.sha256()))
            throw new IllegalStateException("Review auditability regression base scenario binding");
        return suite.scenarios().stream().sorted(Comparator.comparing(Input::scenarioId)).map(input -> {
            var source = base.definitions().stream().filter(d -> d.id().equals(input.scenarioId())).findFirst().orElseThrow();
            var profile = (ObjectNode) source.profile().deepCopy();
            if (source.profileSchemaVersion() != 5 || !profile.at("/security/auditabilityRequirements").isMissingNode())
                throw new IllegalStateException("Review supplemental auditability profile boundary");
            ((ObjectNode) profile.get("security")).set("auditabilityRequirements", mapper.valueToTree(input.requirements()));
            var typed = mapper.treeToValue(profile, ApplicationIdentityProfile.class);
            if (!ApplicationIdentityProfileValidator.validate(typed).issues().isEmpty()) throw new IllegalStateException("Invalid compiled regression profile");
            return new Definition(source.id(), typed.security().auditability(), typed.security().auditabilityRequirements(),
                    CatalogDraftCanonicalizer.sha256(profile));
        }).toList();
    }
    public List<Definition> definitions() { return definitions; }
    public String sha256() { return sha256; }
    public String baseScenarioSetSha256() { return baseScenarioSetSha256; }
}

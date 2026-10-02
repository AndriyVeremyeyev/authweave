package io.authweave.core.catalog;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class AuditabilityCatalogTests {
    private final JsonMapper mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private ObjectNode fixture() throws Exception {
        try (var input = new ClassPathResource("catalog/auditability-evidence.v1.json").getInputStream()) {
            return (ObjectNode) mapper.readTree(input);
        }
    }
    private ProviderCatalog base() throws Exception {
        try (var input = new ClassPathResource("catalog/synthetic.v4.json").getInputStream()) {
            return mapper.readValue(input, ProviderCatalog.class);
        }
    }
    @Test void resourceIsSyntheticBoundImmutableAndHasAnExplicitMissingCriterion() throws Exception {
        var catalog = mapper.treeToValue(fixture(), AuditabilityCatalog.class); catalog.validateBase(base());
        assertEquals(3, catalog.options().size());
        assertEquals(6, catalog.options().getFirst().facts().size());
        assertEquals(5, catalog.options().get(1).facts().size());
        assertThrows(UnsupportedOperationException.class, () -> catalog.options().clear());
        assertThrows(UnsupportedOperationException.class, () -> catalog.options().getFirst().facts().clear());
        assertTrue(catalog.options().stream().flatMap(option -> option.facts().stream()).allMatch(fact ->
                fact.emitter() == AuditabilityFacts.Emitter.IDENTITY_PROVIDER && fact.sourceUrl().getHost().endsWith(".invalid")));
    }
    @ParameterizedTest @ValueSource(strings = {"schema", "kind", "version", "empty", "duplicate-scope", "duplicate-criterion",
            "foreign-fact-scope", "application-emitter", "real-source", "missing-duration", "extra-field", "orphan-duration"})
    void invalidOrPooledFactsCannotEnterTheSidecar(String scenario) throws Exception {
        var fixture = fixture(); var option = (ObjectNode) fixture.get("options").get(0);
        var fact = (ObjectNode) option.get("facts").get(0);
        switch (scenario) {
            case "schema" -> fixture.put("schemaVersion", 2);
            case "kind" -> fixture.put("kind", "PUBLISHED");
            case "version" -> fixture.put("evidenceVersion", " Bad version ");
            case "empty" -> fixture.putArray("options");
            case "duplicate-scope" -> ((tools.jackson.databind.node.ArrayNode) fixture.get("options")).add(option.deepCopy());
            case "duplicate-criterion" -> ((tools.jackson.databind.node.ArrayNode) option.get("facts")).add(fact.deepCopy());
            case "foreign-fact-scope" -> ((ObjectNode) fact.get("scope")).put("configuration", "Other configuration");
            case "application-emitter" -> fact.put("emitter", "APPLICATION");
            case "real-source" -> fact.put("sourceUrl", "https://provider.example.com/logging");
            case "missing-duration" -> fact.remove("documentedMinimumRetentionDays");
            case "extra-field" -> fact.put("configurationVerified", true);
            case "orphan-duration" -> fact.put("documentedMinimumRetentionDays", 30);
        }
        assertThrows(RuntimeException.class, () -> mapper.treeToValue(fixture, AuditabilityCatalog.class), scenario);
    }
    @ParameterizedTest @ValueSource(strings = {"base-version", "unknown-option", "plan", "region", "missing-option"})
    void sidecarCannotBeReboundToAnotherCatalogOptionPlanOrRegion(String scenario) throws Exception {
        var fixture = fixture(); var option = (ObjectNode) fixture.get("options").get(0);
        if (scenario.equals("base-version")) fixture.put("baseCatalogVersion", "other-catalog");
        else if (scenario.equals("missing-option")) ((tools.jackson.databind.node.ArrayNode) fixture.get("options")).remove(0);
        else {
            var key = scenario.equals("unknown-option") ? "optionId" : scenario;
            ((ObjectNode) option.get("scope")).put(key, "foreign");
            for (var fact : option.get("facts")) ((ObjectNode) fact.get("scope")).put(key, "foreign");
        }
        var catalog = mapper.treeToValue(fixture, AuditabilityCatalog.class);
        assertThrows(IllegalArgumentException.class, () -> catalog.validateBase(base()), scenario);
    }
    @Test void missingFactsRemainRepresentableAndDifferentExplicitConfigurationsAreNotPooled() throws Exception {
        var fixture = fixture(); var option = (ObjectNode) fixture.get("options").get(0);
        var alternative = option.deepCopy(); alternative.putArray("facts");
        ((ObjectNode) alternative.get("scope")).put("configuration", "Synthetic logs disabled");
        ((tools.jackson.databind.node.ArrayNode) fixture.get("options")).add(alternative);
        var catalog = mapper.treeToValue(fixture, AuditabilityCatalog.class); catalog.validateBase(base());
        assertEquals(4, catalog.options().size());
        assertEquals(1, catalog.options().stream().filter(value -> value.facts().isEmpty()).count());
        assertThrows(IllegalArgumentException.class, () -> new AuditabilityCatalog(1, "x", "y", ProviderCatalog.Kind.SYNTHETIC, List.of()));
    }
}

package io.authweave.core.catalog.impact;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import static io.authweave.core.catalog.impact.CatalogProfileImpactCoverageService.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogProfileImpactCoverageTests {
    private static final Instant AT = Instant.parse("2026-10-01T12:00:00Z");
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CatalogScopedProfileCases scenarios = scenarios();
    private final CatalogProfileImpactCoverageService service = new CatalogProfileImpactCoverageService(scenarios);
    static Stream<Dimension> dimensions() { return DIMENSIONS.stream(); }

    @Test void manifestMatchesEveryIndependentProfileV5SemanticInputAndPinsTypesEnumsAndDependencies() {
        assertEquals("catalog-profile-impact-coverage-2", POLICY_VERSION);
        assertEquals(5, PROFILE_SCHEMA_VERSION);
        assertEquals("8955d88d2ad2e9b6fc39ddeadcfcf08660510ca6cf0832e773688170697c1c03", MANIFEST_SHA256); // Review and version before changing dependencies or boundaries.
        var schema = mapper.readTree(Path.of(System.getProperty("basedir", "."), "../../packages/contracts/schemas/application-identity-profile.v5.schema.json").toFile());
        var paths = new TreeSet<String>(); schemaPaths(schema, schema, "", paths);
        assertEquals(32, paths.size()); assertEquals(paths, new TreeSet<>(DIMENSIONS.stream().map(Dimension::profilePath).toList()));
        assertEquals(PROFILE_SCHEMA_SHA256, CatalogDraftCanonicalizer.sha256(schema));
        assertEquals(2, ADDITIONAL_BOUNDARIES.size()); assertEquals(11, DIMENSIONS.stream().filter(d -> d.ruleProfilePath() == null).count());
        assertEquals(Set.of("facts.SCIM"), Set.copyOf(DIMENSIONS.stream().filter(d -> d.profilePath().equals("provisioning.scim")).findFirst().orElseThrow().permittedFactPaths()));
        assertThrows(UnsupportedOperationException.class, () -> DIMENSIONS.clear());
    }
    @Test void scopedInventorySeparatesActiveRulesFromScopeGuardsDeferredInputsAndExtraBehaviorBoundaries() {
        var result = service.inspectAt(AT); assertEquals(Status.INCOMPLETE, result.status()); assertEquals(128, result.dimensions().size());
        assertEquals(8, result.additionalGaps().size()); assertEquals(44, result.deferredDimensions()); assertEquals(0, result.missingRules());
        assertTrue(result.unexercisedFactPaths().isEmpty());
        assertEquals(128, result.conditionalDimensions() + result.scopeOnlyDimensions() + result.deferredDimensions());
        assertTrue(result.dimensions().stream().filter(d -> d.profilePath().equals("security.authenticationControls.stepUpAuthentication"))
                .allMatch(d -> d.state() == State.CONDITIONAL_RULE_PRESENT && !d.factPaths().isEmpty()));
        assertEquals(result, service.inspectAt(AT)); assertFalse(result.coverageComplete());
        assertFalse(result.storedReportVerified()); assertFalse(result.approvalGranted()); assertFalse(result.sourceVerificationPerformed());
        assertFalse(result.baselineVerified()); assertFalse(result.writesPerformed()); assertFalse(result.publicationReady()); assertFalse(result.evaluationReady());
        var json = mapper.writeValueAsString(result); assertFalse(json.contains("scopeDescription\":\"")); assertFalse(json.contains("sourceUrl"));
        assertFalse(mapper.valueToTree(result).has("actor")); assertFalse(json.contains("description")); assertFalse(json.contains("profile\":{\""));
        assertThrows(UnsupportedOperationException.class, () -> result.dimensions().clear());
    }
    @ParameterizedTest @MethodSource("dimensions")
    void everyProfileInputIsRepresentedExactlyOnceInEachFrozenScenarioAndCannotBorrowAnotherFact(Dimension dimension) {
        var rows = service.inspectAt(AT).dimensions().stream().filter(c -> c.profilePath().equals(dimension.profilePath())).toList();
        assertEquals(4, rows.size()); assertEquals(4, rows.stream().map(DimensionCheck::scenarioId).distinct().count());
        for (var row : rows) {
            assertEquals(dimension.boundary(), row.boundary()); assertTrue(dimension.permittedFactPaths().containsAll(row.factPaths()));
            if (dimension.ruleProfilePath() == null) { assertEquals(State.DEFERRED_DIMENSION, row.state()); assertEquals(0, row.ruleCount()); }
            else { assertTrue(row.ruleCount() > 0); assertFalse(row.state() == State.MISSING_RULE); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"extra-input", "missing-input", "old-schema", "duplicate-scenario", "wrong-digest", "duplicate-check", "foreign-profile-path", "foreign-fact-path", "wrong-kind"})
    void schemaDefinitionOrDependencyDriftFailsClosedWithoutReleasingAPartialCoverageMatrix(String variant) {
        var definitions = new ArrayList<>(scenarios.definitions()); var plans = new ArrayList<>(scenarios.plans()); String digest = scenarios.sha256();
        var first = definitions.getFirst(); var json = (ObjectNode) first.profile();
        switch (variant) {
            case "extra-input" -> ((ObjectNode) json.get("security")).put("unreviewed", "new");
            case "missing-input" -> ((ObjectNode) json.get("security")).remove("auditability");
            case "old-schema" -> definitions.set(0, new CatalogScenarioCases.Definition(first.id(), first.description(), 4, json));
            case "duplicate-scenario" -> definitions.set(1, first);
            case "wrong-digest" -> digest = "0".repeat(64);
            default -> {
                var rules = new ArrayList<>(plans.getFirst()); var r = rules.getFirst();
                if (variant.equals("duplicate-check")) rules.add(r);
                else rules.set(0, new ScenarioRulePlan.Rule(r.checkId(), variant.equals("foreign-profile-path") ? "security.unreviewed" : r.profilePath(),
                        variant.equals("foreign-fact-path") ? "facts.SCIM" : r.factPath(), variant.equals("wrong-kind")
                            ? io.authweave.core.catalog.draft.CatalogChangePreview.FactKind.RESIDENCY : r.kind(), r.criticality(), r.allowedCountries(), r.presetOutcome(), r.presetReason()));
                plans.set(0, List.copyOf(rules));
            }
        }
        if (variant.equals("extra-input") || variant.equals("missing-input")) definitions.set(0, new CatalogScenarioCases.Definition(first.id(), first.description(), 5, json));
        if (!variant.equals("wrong-digest")) digest = CatalogDraftCanonicalizer.sha256(definitions);
        var hash = digest; assertThrows(IllegalStateException.class, () -> service.inspect(definitions, plans, hash, AT));
    }
    @Test void missingRuleIsAnExplicitGapRatherThanADeferredPathOrSuccessfulCoverage() {
        var plans = new ArrayList<>(scenarios.plans()); plans.set(0, plans.getFirst().stream().filter(r -> !r.profilePath().equals("provisioning.scim")).toList());
        var result = service.inspect(scenarios.definitions(), plans, scenarios.sha256(), AT);
        assertEquals(1, result.missingRules()); assertFalse(result.coverageComplete());
        assertEquals(State.MISSING_RULE, result.dimensions().stream().filter(d -> d.scenarioId().equals("b2b-saas-scoped") && d.profilePath().equals("provisioning.scim")).findFirst().orElseThrow().state());
    }
    @Test void activeScopedInputsChangeCoverageButNeverEraseConfigurationAssuranceOrLifecycleGaps() {
        var definitions = scenarios.definitions().stream().map(d -> {
            var json = (ObjectNode) d.profile(); var security = (ObjectNode) json.get("security"); security.put("dataResidency", "UNKNOWN");
            var controls = (ObjectNode) security.get("authenticationControls"); for (var field : List.of("phishingResistance", "nonExportableKeys", "stepUpAuthentication")) controls.put(field, "UNKNOWN");
            return new CatalogScenarioCases.Definition(d.id(), d.description(), 5, json);
        }).toList();
        var plans = definitions.stream().map(d -> ScenarioRulePlan.from(mapper.treeToValue(d.profile(), ApplicationIdentityProfile.class))).toList();
        var result = service.inspect(definitions, plans, CatalogDraftCanonicalizer.sha256(definitions), AT);
        assertTrue(result.conditionalDimensions() < service.inspectAt(AT).conditionalDimensions());
        assertEquals(40, result.unexercisedFactPaths().size()); assertFalse(result.coverageComplete());
        assertEquals(44, result.deferredDimensions()); assertEquals(8, result.additionalGaps().size());
        assertTrue(result.dimensions().stream().filter(d -> d.profilePath().equals("security.authenticationControls.stepUpAuthentication"))
                .allMatch(d -> d.state() == State.SCOPE_GUARD_ONLY && d.activeFactRuleCount() == 0 && d.factPaths().isEmpty()));
        assertTrue(service.inspectAt(AT).unexercisedFactPaths().isEmpty()); // Source-controlled definitions never edited or refreshed.
    }
    @Test void incompleteConstructorsCannotHideDimensionsBoundaryGapsOrUnexercisedPathsOrForgeCompleteness() {
        var good = service.inspectAt(AT);
        assertThrows(IllegalArgumentException.class, () -> new Check(Status.COMPLETE, AT, scenarios.sha256(), good.dimensions(), good.additionalGaps(), good.unexercisedFactPaths()));
        assertThrows(IllegalArgumentException.class, () -> new Check(Status.INCOMPLETE, AT, scenarios.sha256(), good.dimensions(), List.of(), good.unexercisedFactPaths()));
        assertThrows(IllegalArgumentException.class, () -> new Check(Status.INCOMPLETE, AT, scenarios.sha256(), good.dimensions(), good.additionalGaps(), List.of("facts.SCIM")));
        assertThrows(IllegalArgumentException.class, () -> new DimensionCheck("b2b-saas", "security.assurance", Boundary.ASSURANCE, State.CONDITIONAL_RULE_PRESENT, 1, 1, List.of("facts.MFA")));
        assertEquals(Check.notChecked(), Check.notChecked()); assertFalse(Check.notChecked().coverageComplete());
    }
    private CatalogScopedProfileCases scenarios() { try { return new CatalogScopedProfileCases(mapper); } catch (java.io.IOException e) { throw new AssertionError(e); } }
    private static void schemaPaths(JsonNode root, JsonNode input, String path, Set<String> paths) {
        var schema = input;
        if (schema.has("$ref")) schema = root.at(schema.get("$ref").asText().substring(1));
        if (schema.has("properties") && !path.equals("operations.usagePlanning.volumes"))
            schema.get("properties").properties().forEach(e -> schemaPaths(root, e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey(), paths));
        else paths.add(path);
    }
}

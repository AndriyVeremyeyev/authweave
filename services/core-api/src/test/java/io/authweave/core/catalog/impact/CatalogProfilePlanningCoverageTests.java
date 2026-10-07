package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import io.authweave.core.catalog.ProviderCatalog;
import io.authweave.core.catalog.AuditabilityCatalog;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import static io.authweave.core.catalog.impact.CatalogProfilePlanningCoverageService.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogProfilePlanningCoverageTests {
    private static final Instant AT = Instant.parse("2026-09-12T12:00:00Z");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static CatalogProfilePlanningCoverageService service;
    @BeforeAll static void setup() throws Exception {
        var base = new CatalogScopedProfileCases(MAPPER); var cases = new CatalogAuditabilityRegressionCases(MAPPER, base);
        var legacy = new CatalogProfileImpactCoverageService(base, new CatalogArchitectureImpactService(base, MAPPER));
        var audit = new CatalogAuditabilityRegressionService(cases, resource("synthetic.v4.json", ProviderCatalog.class), resource("auditability-evidence.v1.json", AuditabilityCatalog.class));
        service = new CatalogProfilePlanningCoverageService(new CatalogProfileImpactCoverageV6Service(legacy, cases, audit),
                new CatalogArchitectureConfigurationRegressionService(new CatalogArchitectureConfigurationCases(base, MAPPER)),
                new CatalogLifecycleRegressionService(new CatalogLifecycleRegressionCases(base, cases, MAPPER)),
                new CatalogOperationsPlanningRegressionService(new CatalogOperationsPlanningCases(base, cases, MAPPER)),
                new CatalogAssuranceComplianceRegressionService(new CatalogAssuranceComplianceCases(base, cases, MAPPER)));
    }
    private static <T> T resource(String name, Class<T> type) throws Exception {
        try (var stream = new ClassPathResource("catalog/" + name).getInputStream()) { return MAPPER.readValue(stream, type); }
    }
    @Test void completePlanningMatrixPreservesStructuralSemanticsAndAllVerificationGaps() {
        var snapshot = service.readAt(AT); var result = service.compose(snapshot, AT);
        assertEquals(snapshot.structural(), result.structuralCoverage()); assertEquals(136, result.checkedDimensions());
        assertEquals(34, result.declaredProfileInputs()); assertEquals(4, result.checkedRegressionFamilies());
        assertEquals(36, result.planningAddedToDeferredDimensions()); assertEquals(0, result.unroutedDeferredDimensions());
        assertEquals(36, result.structuralCoverage().deferredDimensions()); assertEquals(40, result.structuralCoverage().verificationGaps().size());
        assertEquals(22, result.verificationGaps().size());
        assertEquals("808c2414bd4617cda4edf6003ed3e12c3d87bfb3ab42c1daf40f4912bb82d08a", result.manifestSha256());
        assertEquals(List.of(252, 2016, 140, 36), result.regressions().stream().map(RegressionBinding::checkedCases).toList());
        assertEquals(List.of(5, 6, 6, 6), result.regressions().stream().map(RegressionBinding::profileSchemaVersion).toList());
        var expected = Map.of("application.clients", List.of(Family.ARCHITECTURE_CONFIGURATION, Family.ASSURANCE_COMPLIANCE),
                "provisioning.scim", List.of(Family.PROVISIONING_LIFECYCLE), "operations.usagePlanning.volumes", List.of(Family.OPERATIONS_PLANNING),
                "security.assurance", List.of(Family.ASSURANCE_COMPLIANCE), "security.auditability", List.of(), "audience.tenancy", List.of());
        for (var entry : expected.entrySet()) for (var id : CatalogScopedProfileCases.IDS) {
            var dimension = result.dimensions().stream().filter(d -> d.scenarioId().equals(id) && d.profilePath().equals(entry.getKey())).findFirst().orElseThrow();
            assertEquals(entry.getValue(), dimension.planningRegressions());
        }
        var json = MAPPER.valueToTree(result);
        for (String flag : List.of("candidateChangesEvaluated", "coverageComplete", "configurationVerified", "lifecycleVerified", "operationalReadinessVerified", "costModelEvaluated", "assuranceVerified", "complianceVerified", "sourceVerificationPerformed", "storedReportVerified", "baselineVerified", "approvalGranted", "publicationReady", "evaluationReady", "recommendationReady", "writesPerformed")) assertFalse(json.get(flag).asBoolean(), flag);
        for (String field : List.of("profile", "rows", "inputs", "workspaceId", "actor", "sourceUrl", "winner")) assertFalse(json.has(field), field);
        assertEquals(CatalogDraftCanonicalizer.sha256(snapshot.structural()), result.structuralCoverageSha256());
        assertEquals(result, service.inspectAt(AT));
        assertThrows(UnsupportedOperationException.class, () -> result.dimensions().clear());
        assertThrows(UnsupportedOperationException.class, () -> INPUT_ROUTES.getFirst().planningRegressions().clear());
    }
    @ParameterizedTest @ValueSource(strings = {"structural", "architecture", "lifecycle", "operations", "assurance", "not-checked"})
    void rejectsMixedClocksAndAbsentStructuralCoverageWithoutFallback(String variant) {
        var a = service.readAt(AT); var b = service.readAt(AT.plusSeconds(1));
        var forged = new Snapshot(variant.equals("not-checked") ? CatalogProfileImpactCoverageV6Service.Check.notChecked() : variant.equals("structural") ? b.structural() : a.structural(),
                variant.equals("architecture") ? b.architecture() : a.architecture(), variant.equals("lifecycle") ? b.lifecycle() : a.lifecycle(),
                variant.equals("operations") ? b.operations() : a.operations(), variant.equals("assurance") ? b.assurance() : a.assurance());
        assertThrows(IllegalArgumentException.class, () -> service.compose(forged, AT));
    }
    @Test void rejectsAValidLookingSubstitutedRegressionDigestBeforeComposition() {
        var a = service.readAt(AT); var s = a.assurance();
        var forged = new CatalogAssuranceComplianceRegressionService.Check(AT, "0".repeat(64), s.auditabilityScenarioSetSha256(), s.analysisSha256(), s.assurance(), s.compliance(), s.humanScopes(), s.checkedComplianceItems(), s.complianceScopeNotApplied());
        assertThrows(IllegalArgumentException.class, () -> service.compose(new Snapshot(a.structural(), a.architecture(), a.lifecycle(), a.operations(), forged), AT));
    }
    @ParameterizedTest @ValueSource(strings = {"missing-dimension", "duplicate-dimension", "reordered-dimensions", "wrong-route", "wrong-state", "missing-family", "duplicate-family", "reordered-families", "missing-gap", "reordered-gaps", "clock"})
    void nativeCompositionRejectsPartialOrReclassifiedInventories(String variant) {
        var a = service.inspectAt(AT); var dimensions = new ArrayList<>(a.dimensions()); var bindings = new ArrayList<>(a.regressions()); var gaps = new ArrayList<>(a.verificationGaps());
        switch (variant) {
            case "missing-dimension" -> dimensions.removeLast(); case "duplicate-dimension" -> dimensions.set(1, dimensions.getFirst());
            case "reordered-dimensions" -> java.util.Collections.swap(dimensions, 0, 1);
            case "wrong-route" -> dimensions.set(0, new Dimension(dimensions.getFirst().scenarioId(), dimensions.getFirst().profilePath(), dimensions.getFirst().structuralState(), List.of(Family.OPERATIONS_PLANNING)));
            case "wrong-state" -> dimensions.set(0, new Dimension(dimensions.getFirst().scenarioId(), dimensions.getFirst().profilePath(), CatalogProfileImpactCoverageV6Service.State.DEFERRED_DIMENSION, dimensions.getFirst().planningRegressions()));
            case "missing-family" -> bindings.removeLast(); case "duplicate-family" -> bindings.set(1, bindings.getFirst());
            case "reordered-families" -> java.util.Collections.swap(bindings, 0, 1); case "missing-gap" -> gaps.removeLast();
            case "reordered-gaps" -> java.util.Collections.swap(gaps, 0, 1);
        }
        assertThrows(IllegalArgumentException.class, () -> new Check(variant.equals("clock") ? AT.plusSeconds(1) : AT, a.structuralCoverage(), bindings, dimensions, gaps));
    }
    @Test void clockRefreshDoesNotPromoteEvidenceOrRewriteHistoricalPolicies() {
        var a = service.inspectAt(AT); var b = service.inspectAt(AT.plusSeconds(1));
        assertEquals(a.dimensions(), b.dimensions()); assertEquals(a.verificationGaps(), b.verificationGaps());
        assertEquals(a.baseScenarioSetSha256(), b.baseScenarioSetSha256()); assertEquals(a.auditabilityScenarioSetSha256(), b.auditabilityScenarioSetSha256());
        assertNotEquals(a.analysisSha256(), b.analysisSha256()); assertNotEquals(a.structuralCoverageSha256(), b.structuralCoverageSha256());
        assertEquals("catalog-profile-impact-coverage-5", a.structuralCoverage().policyVersion()); assertFalse(b.publicationReady());
        for (int i = 0; i < 4; i++) { assertEquals(a.regressions().get(i).scenarioSetSha256(), b.regressions().get(i).scenarioSetSha256()); assertNotEquals(a.regressions().get(i).checkSha256(), b.regressions().get(i).checkSha256()); }
        assertThrows(NullPointerException.class, () -> service.inspectAt(null));
    }
}

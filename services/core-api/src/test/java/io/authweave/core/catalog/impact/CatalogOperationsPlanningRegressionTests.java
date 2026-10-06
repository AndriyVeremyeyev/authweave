package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfileValidator;
import io.authweave.core.assessment.domain.profile.OperationalConstraints.*;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.evaluation.OperationsPlanningEvaluator;
import static io.authweave.core.catalog.impact.CatalogOperationsPlanningCases.*;
import static io.authweave.core.catalog.impact.CatalogOperationsPlanningRegressionService.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogOperationsPlanningRegressionTests {
    private static final Instant AT = Instant.parse("2026-09-12T12:00:00Z");
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CatalogScopedProfileCases base = base();
    private final CatalogAuditabilityRegressionCases auditability = auditability();
    private final CatalogOperationsPlanningCases cases = new CatalogOperationsPlanningCases(base, auditability, mapper);
    private final CatalogOperationsPlanningRegressionService service = new CatalogOperationsPlanningRegressionService(cases);
    private CatalogScopedProfileCases base() { try { return new CatalogScopedProfileCases(mapper); } catch (Exception e) { throw new IllegalStateException(e); } }
    private CatalogAuditabilityRegressionCases auditability() { try { return new CatalogAuditabilityRegressionCases(mapper, base); } catch (Exception e) { throw new IllegalStateException(e); } }

    @Test void boundedOverlaysPreserveFrozenProfilesAndEveryExplicitV6AuditRequirement() {
        assertEquals(140, cases.definitions().size()); assertEquals(140, cases.definitions().stream().map(Definition::key).distinct().count());
        assertEquals(Set.of(HostingPreference.values()), cases.definitions().stream().map(d -> d.inputs().hosting()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(DeploymentTarget.values()), cases.definitions().stream().map(d -> d.inputs().deploymentTarget()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(IdentityExpertise.values()), cases.definitions().stream().map(d -> d.inputs().identityExpertise()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(BudgetSensitivity.values()), cases.definitions().stream().map(d -> d.inputs().budgetSensitivity()).collect(java.util.stream.Collectors.toSet()));
        for (var d : cases.definitions()) {
            var source = base.definitions().stream().filter(s -> s.id().equals(d.scenarioId())).findFirst().orElseThrow();
            var supplement = auditability.definitions().stream().filter(s -> s.scenarioId().equals(d.scenarioId())).findFirst().orElseThrow();
            var profile = (ObjectNode) source.profile();
            ((ObjectNode) profile.get("security")).set("auditabilityRequirements", mapper.valueToTree(supplement.requirements()));
            assertEquals(d.sourceProfileSha256(), CatalogDraftCanonicalizer.sha256(profile));
            var operations = mapper.<ObjectNode>valueToTree(d.inputs()); operations.set("usagePlanning", mapper.valueToTree(d.usagePlanning())); profile.set("operations", operations);
            assertEquals(d.profileSha256(), CatalogDraftCanonicalizer.sha256(profile));
            var typed = mapper.treeToValue(profile, ApplicationIdentityProfile.class);
            assertTrue(ApplicationIdentityProfileValidator.validate(typed).issues().isEmpty()); assertEquals(6, typed.minimumSchemaVersion());
            assertEquals(supplement.requirements(), typed.security().auditabilityRequirements());
            assertEquals(source.profile().get("application"), profile.get("application")); assertEquals(source.profile().get("provisioning"), profile.get("provisioning"));
            assertTrue(source.profile().at("/security/auditabilityRequirements").isMissingNode());
        }
        assertEquals(BASE_SHA256, CatalogDraftCanonicalizer.sha256(base.definitions()));
        assertThrows(UnsupportedOperationException.class, () -> cases.definitions().clear());
        assertThrows(UnsupportedOperationException.class, () -> cases.definitions().getFirst().usagePlanning().volumes().clear());
    }
    @Test void independentExpectedInputsAndCountsMatchEveryScenarioAndBothUnverifiedOptions() {
        var report = service.analyzeAt(AT); var check = service.summarize(report);
        assertEquals(new InputCounts(46, 94), check.inputResults()); assertEquals(new InputCounts(56, 84), check.usageResults());
        assertEquals(new HostingCounts(95, 95, 40, 50), check.hosting()); assertEquals(new SupportCounts(30, 30, 170, 50), check.support());
        assertEquals(new BudgetCounts(230, 50), check.budget()); assertEquals(364, check.recordedMetricChecks()); assertEquals(380, check.missingInputChecks());
        assertEquals(check, service.inspectAt(AT));
        for (int i = 0; i < report.rows().size(); i++) {
            var row = report.rows().get(i); var a = row.analysis(); var expected = expected(row.input());
            assertEquals(WORKSPACE, a.workspaceId()); assertEquals(assessmentId(i), a.assessmentId()); assertEquals(0, a.assessmentVersion()); assertEquals(AT, a.evaluatedAt());
            assertEquals(expected.status(), a.status()); assertEquals(expected.missingPaths(), a.missingPaths()); assertEquals(expected.usageInputs(), a.usageInputs());
            assertEquals(expected.alignments(), a.options().stream().map(o -> o.hostingAlignment()).toList());
            assertEquals(expected.support(), a.options().stream().map(o -> o.supportPlanning()).toList());
            assertEquals(expected.budget(), a.options().stream().map(o -> o.budgetPlanning()).toList());
            assertEquals(2, a.options().size()); assertFalse(a.pricingEvaluated()); assertFalse(a.providerEligibilityEvaluated()); assertFalse(a.writesPerformed());
        }
    }
    @Test void bodyFreeSummaryDoesNotCloseOldCoverageOrGrantAuthority() {
        var check = service.inspectAt(AT); var json = mapper.valueToTree(check);
        for (String flag : List.of("candidateChangesEvaluated", "coverageComplete", "providerEligibilityEvaluated", "deploymentCompatibilityVerified", "operationalReadinessVerified",
                "pricingEvaluated", "costModelEvaluated", "budgetFitVerified", "configurationVerified", "sourceVerificationPerformed", "storedReportVerified", "baselineVerified",
                "approvalGranted", "publicationReady", "evaluationReady", "recommendationReady", "writesPerformed")) assertFalse(json.get(flag).asBoolean(), flag);
        for (String field : List.of("profile", "inputs", "scopeDescription", "assumptions", "rows", "actor", "sourceUrl", "workspaceId", "estimatedCost", "winner")) assertFalse(json.has(field), field);
        assertFalse(json.toString().contains("Synthetic forecast")); assertFalse(json.toString().contains("9007199254740991"));
        var old = new CatalogProfileImpactCoverageService(base, new CatalogArchitectureImpactService(base, mapper)).inspectAt(AT);
        assertEquals("catalog-profile-impact-coverage-4", old.policyVersion()); assertFalse(old.coverageComplete()); assertEquals(12, old.additionalGaps().size());
        assertEquals(OperationsPlanningEvaluator.DEFERRED_BOUNDARIES, check.deferredBoundaries());
        assertEquals("8e1f536ba8194d889a08c256972051a37d5e671cf5366a284ab29f6ea61909a3", check.scenarioSetSha256());
        assertEquals("4cb0bf7ca7372d127eebc98f4a7e06b332df5f347ea75e94928d23b9ba3325f5", check.auditabilityScenarioSetSha256());
        assertEquals("56448797d617cc284a39d8dfbcfa9105bac739a81b591950f0e2ad9bfecaff83", check.definitionsSha256());
        assertEquals("a8a644c6a3eec5559c063b9c39f1c73fa4385ecfb77e09ec7554b3c0c9e2fd83", check.analysisSha256());
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "duplicate", "reorder", "hash", "input", "analysis", "clock"})
    void replayRefusesMissingReorderedSubstitutedOrMixedClockReports(String variant) {
        var report = service.analyzeAt(AT); var rows = new ArrayList<>(report.rows()); var first = rows.getFirst();
        switch (variant) {
            case "missing" -> rows.removeLast();
            case "duplicate" -> rows.set(1, first);
            case "reorder" -> java.util.Collections.swap(rows, 0, 1);
            case "input" -> rows.set(0, new Row(rows.get(1).input(), first.analysis()));
            case "analysis" -> rows.set(0, new Row(first.input(), rows.get(1).analysis()));
            case "clock" -> rows.set(0, new Row(first.input(), OperationsPlanningEvaluator.evaluate(WORKSPACE, assessmentId(0), 0, first.input().operations(), AT.plusSeconds(1))));
        }
        assertThrows(IllegalArgumentException.class, () -> service.summarize(new Analysis(AT, variant.equals("hash") ? "0".repeat(64) : report.scenarioSetSha256(), rows)));
    }
    @Test void advancingDiagnosticClockOnlyChangesAnalysisDigestNotInputsOrCounts() {
        var original = service.inspectAt(AT); var later = service.inspectAt(AT.plusSeconds(1));
        assertEquals(original.scenarioSetSha256(), later.scenarioSetSha256()); assertEquals(original.definitionsSha256(), later.definitionsSha256());
        assertEquals(original.inputResults(), later.inputResults()); assertEquals(original.usageResults(), later.usageResults()); assertNotEquals(original.analysisSha256(), later.analysisSha256());
        assertThrows(NullPointerException.class, () -> service.inspectAt(null));
    }
    @Test void invalidCountShapeOverflowAndImpossibleUsageParityAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new InputCounts(Integer.MAX_VALUE, 1)); assertThrows(IllegalArgumentException.class, () -> new InputCounts(-1, 141));
        assertThrows(IllegalArgumentException.class, () -> new HostingCounts(95, 95, 40, 49)); assertThrows(IllegalArgumentException.class, () -> new SupportCounts(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new BudgetCounts(230, -50));
        var r = service.inspectAt(AT);
        for (String variant : List.of("hash", "metric", "missing", "parity")) assertThrows(IllegalArgumentException.class, () -> new Check(AT,
            variant.equals("hash") ? "bad" : r.scenarioSetSha256(), r.auditabilityScenarioSetSha256(), r.analysisSha256(),
            variant.equals("parity") ? new InputCounts(57, 83) : r.inputResults(), r.usageResults(), r.hosting(), r.support(), r.budget(),
            variant.equals("metric") ? 561 : r.recordedMetricChecks(), variant.equals("missing") ? 1401 : r.missingInputChecks()));
    }
}

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
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.evaluation.AssuranceCompliancePlanningEvaluator;
import static io.authweave.core.catalog.impact.CatalogAssuranceComplianceCases.*;
import static io.authweave.core.catalog.impact.CatalogAssuranceComplianceRegressionService.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogAssuranceComplianceRegressionTests {
    private static final Instant AT = Instant.parse("2026-09-12T12:00:00Z");
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CatalogScopedProfileCases base = base();
    private final CatalogAuditabilityRegressionCases auditability = auditability();
    private final CatalogAssuranceComplianceCases cases = new CatalogAssuranceComplianceCases(base, auditability, mapper);
    private final CatalogAssuranceComplianceRegressionService service = new CatalogAssuranceComplianceRegressionService(cases);
    private CatalogScopedProfileCases base() { try { return new CatalogScopedProfileCases(mapper); } catch (Exception e) { throw new IllegalStateException(e); } }
    private CatalogAuditabilityRegressionCases auditability() { try { return new CatalogAuditabilityRegressionCases(mapper, base); } catch (Exception e) { throw new IllegalStateException(e); } }

    @Test void boundedMatrixPreservesFrozenSourcesV6SupplementAndUnrelatedInputs() {
        assertEquals(36, cases.definitions().size()); assertEquals(36, cases.definitions().stream().map(Definition::key).distinct().count());
        assertEquals(Set.of(Variant.values()), cases.definitions().stream().map(Definition::variant).collect(java.util.stream.Collectors.toSet()));
        for (var d : cases.definitions()) {
            var source = base.definitions().stream().filter(s -> s.id().equals(d.scenarioId())).findFirst().orElseThrow();
            var supplement = auditability.definitions().stream().filter(s -> s.scenarioId().equals(d.scenarioId())).findFirst().orElseThrow();
            var v6 = (ObjectNode) source.profile(); ((ObjectNode) v6.get("security")).set("auditabilityRequirements", mapper.valueToTree(supplement.requirements()));
            assertEquals(d.sourceProfileSha256(), CatalogDraftCanonicalizer.sha256(v6));
            var typedSource = mapper.treeToValue(v6, io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile.class);
            assertEquals(typedSource.operations(), d.profile().operations()); assertEquals(typedSource.protocols(), d.profile().protocols()); assertEquals(typedSource.provisioning(), d.profile().provisioning());
            assertEquals(typedSource.security().auditabilityRequirements(), d.profile().security().auditabilityRequirements());
            assertEquals(typedSource.security().dataResidencyDetails(), d.profile().security().dataResidencyDetails());
            assertEquals(typedSource.application().type(), d.profile().application().type()); assertEquals(typedSource.audience().tenancy(), d.profile().audience().tenancy());
            assertEquals(typedSource.audience().membership(), d.profile().audience().membership());
            if (d.variant() == Variant.BASE_PROFILE) { assertEquals(typedSource, d.profile()); assertEquals(d.sourceProfileSha256(), d.profileSha256()); }
            assertTrue(source.profile().at("/security/auditabilityRequirements").isMissingNode());
            assertFalse(mapper.valueToTree(d).has("profile"));
        }
        assertEquals(BASE_SHA256, CatalogDraftCanonicalizer.sha256(base.definitions()));
        assertThrows(UnsupportedOperationException.class, () -> cases.definitions().clear());
        assertThrows(UnsupportedOperationException.class, () -> cases.definitions().getFirst().inputs().clients().clear());
        assertThrows(IllegalArgumentException.class, () -> overlay(Variant.BASE_PROFILE));
    }
    @Test void completeIndependentRowsCountsAndDigestsRemainInvestigationNotEvidence() {
        var report = service.analyzeAt(AT); var check = service.summarize(report);
        assertEquals(new ItemCounts(60, 154, 38), check.assurance()); assertEquals(new ItemCounts(16, 29, 0), check.compliance());
        assertEquals(new HumanCounts(4, 28, 4), check.humanScopes()); assertEquals(45, check.checkedComplianceItems()); assertEquals(10, check.complianceScopeNotApplied());
        assertEquals("5d8795219810f2b1d94a2de004bbbebf7f7e08d9a0752524906afa035b575378", check.scenarioSetSha256());
        assertEquals("fd09bce53a9feff6ce79321711fe85f47e6f06b190bc426fe86ad49b86d6cf22", check.definitionsSha256());
        assertEquals("6f353da48419166703db3d96a0dab408b33f768d7d160624f8f3dc4dfcb08ea5", check.analysisSha256());
        for (int i = 0; i < report.rows().size(); i++) {
            var row = report.rows().get(i); var expected = expected(row.input()); var a = row.analysis();
            assertEquals(expected.assuranceItems(), a.assuranceItems()); assertEquals(expected.complianceItems(), a.complianceItems()); assertEquals(expected.complianceScopeCheck(), a.complianceScopeCheck());
            assertEquals(WORKSPACE, a.workspaceId()); assertEquals(assessmentId(i), a.assessmentId()); assertEquals(0, a.assessmentVersion()); assertEquals(AT, a.evaluatedAt());
            assertEquals("NEEDS_INFORMATION", a.status()); assertFalse(a.assuranceVerified()); assertFalse(a.complianceVerified()); assertFalse(a.writesPerformed());
        }
        var json = mapper.valueToTree(check);
        for (String flag : List.of("candidateChangesEvaluated", "coverageComplete", "assuranceVerified", "complianceVerified", "legalApplicabilityDetermined", "providerEligibilityEvaluated", "configurationVerified", "sourceVerificationPerformed", "storedReportVerified", "baselineVerified", "approvalGranted", "publicationReady", "evaluationReady", "recommendationReady", "writesPerformed")) assertFalse(json.get(flag).asBoolean(), flag);
        for (String field : List.of("profile", "inputs", "rows", "actor", "workspaceId", "sourceUrl", "criterion", "winner")) assertFalse(json.has(field), field);
        var old = new CatalogProfileImpactCoverageService(base, new CatalogArchitectureImpactService(base, mapper)).inspectAt(AT);
        assertEquals("catalog-profile-impact-coverage-4", old.policyVersion()); assertFalse(old.coverageComplete()); assertEquals(12, old.additionalGaps().size());
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "duplicate", "reorder", "hash", "input", "analysis", "clock"})
    void replayRefusesIncompleteSubstitutedAndMixedClockReports(String variant) {
        var report = service.analyzeAt(AT); var rows = new ArrayList<>(report.rows()); var first = rows.getFirst();
        switch (variant) {
            case "missing" -> rows.removeLast();
            case "duplicate" -> rows.set(1, first);
            case "reorder" -> java.util.Collections.swap(rows, 0, 1);
            case "input" -> rows.set(0, new Row(rows.get(1).input(), first.analysis()));
            case "analysis" -> rows.set(0, new Row(first.input(), rows.get(1).analysis()));
            case "clock" -> rows.set(0, new Row(first.input(), AssuranceCompliancePlanningEvaluator.evaluate(WORKSPACE, assessmentId(0), 0, first.input().profile(), AT.plusSeconds(1))));
        }
        assertThrows(IllegalArgumentException.class, () -> service.summarize(new Analysis(AT, variant.equals("hash") ? "0".repeat(64) : report.scenarioSetSha256(), rows)));
    }
    @Test void freshClockChangesOnlyAnalysisDigestNeverEvidenceOrCounts() {
        var a = service.inspectAt(AT); var b = service.inspectAt(AT.plusSeconds(1));
        assertEquals(a.scenarioSetSha256(), b.scenarioSetSha256()); assertEquals(a.definitionsSha256(), b.definitionsSha256()); assertEquals(a.assurance(), b.assurance()); assertEquals(a.compliance(), b.compliance()); assertNotEquals(a.analysisSha256(), b.analysisSha256());
        assertThrows(NullPointerException.class, () -> service.inspectAt(null));
        assertThrows(IllegalArgumentException.class, () -> new ItemCounts(Integer.MAX_VALUE, 1, 0)); assertThrows(IllegalArgumentException.class, () -> new ItemCounts(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new HumanCounts(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Check(AT, a.scenarioSetSha256(), a.auditabilityScenarioSetSha256(), a.analysisSha256(), new ItemCounts(60, 153, 38), a.compliance(), a.humanScopes(), 45, 10));
        assertThrows(IllegalArgumentException.class, () -> new Check(AT, a.scenarioSetSha256(), a.auditabilityScenarioSetSha256(), a.analysisSha256(), a.assurance(), a.compliance(), a.humanScopes(), 44, 10));
    }
}

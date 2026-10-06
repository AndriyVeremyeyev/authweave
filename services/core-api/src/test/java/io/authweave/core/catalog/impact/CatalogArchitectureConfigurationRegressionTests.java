package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.evaluation.ArchitectureConfigurationEvaluator;
import io.authweave.core.evaluation.ArchitecturePatternEvaluator;
import io.authweave.core.evaluation.ArchitecturePatternPreflight;
import io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator;
import static io.authweave.core.catalog.impact.CatalogArchitectureConfigurationCases.*;
import static io.authweave.core.catalog.impact.CatalogArchitectureConfigurationRegressionService.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogArchitectureConfigurationRegressionTests {
    private static final Instant AT = Instant.parse("2026-09-12T12:00:00Z");
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CatalogScopedProfileCases base = base();
    private final CatalogArchitectureConfigurationCases cases = new CatalogArchitectureConfigurationCases(base, mapper);
    private final CatalogArchitectureConfigurationRegressionService service = new CatalogArchitectureConfigurationRegressionService(cases);
    private CatalogScopedProfileCases base() { try { return new CatalogScopedProfileCases(mapper); } catch (Exception e) { throw new IllegalStateException(e); } }

    @Test void exactFrozenLibraryIncludesEveryPatternSettingAndScopeWithoutMutatingBaseProfiles() {
        assertEquals(BASE_SHA256, base.sha256()); assertEquals(BASE_SHA256, CatalogDraftCanonicalizer.sha256(base.definitions()));
        assertEquals(252, cases.definitions().size()); assertEquals(252, cases.definitions().stream().map(Definition::key).distinct().count());
        assertEquals(Set.of(ArchitectureConfigurationEvaluator.SettingId.values()), cases.definitions().stream()
                .flatMap(d -> REFERENCES.get(d.patternId()).keySet().stream()).collect(java.util.stream.Collectors.toSet()));
        for (var d : cases.definitions()) {
            var source = base.definitions().stream().filter(s -> s.id().equals(d.scenarioId())).findFirst().orElseThrow();
            var profile = mapper.treeToValue(source.profile(), io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile.class);
            assertEquals(CatalogDraftCanonicalizer.sha256(source.profile()), d.sourceProfileSha256());
            assertEquals(profile.security(), d.profile().security()); assertEquals(profile.operations(), d.profile().operations());
            if (d.contextVariant() == ContextVariant.BASE_PROFILE) assertEquals(profile, d.profile());
            if (d.contextVariant() == ContextVariant.UNKNOWN_CLIENTS) assertTrue(d.profile().application().clients().isEmpty());
            if (d.contextVariant() == ContextVariant.EXCLUDED_PATTERN_CLIENT) { assertFalse(d.profile().application().clients().isEmpty()); assertFalse(d.profile().application().clients().contains(client(d.patternId()))); }
        }
        assertThrows(UnsupportedOperationException.class, () -> cases.definitions().clear());
        assertThrows(UnsupportedOperationException.class, () -> cases.definitions().getFirst().settings().put(ArchitectureConfigurationEvaluator.SettingId.OAUTH_FLOW, ArchitectureConfigurationEvaluator.SettingValue.IMPLICIT));
        assertTrue(base.definitions().stream().allMatch(d -> d.profileSchemaVersion() == 5 && d.profile().at("/security/auditabilityRequirements").isMissingNode()));
    }
    @Test void independentExpectedMatrixKeepsAllGapsAndSeparateSavedInputPreflight() {
        var report = service.analyzeAt(AT); var check = service.summarize(report);
        assertEquals(new Counts(292, 18, 986, 708), check.outcomes()); assertEquals(new Results(22, 18, 120, 92), check.results());
        assertEquals(76, check.selectedCases()); assertEquals(84, check.unknownClientCases()); assertEquals(92, check.unselectedClientCases());
        assertEquals(96, check.savedInputNeedsInformation()); assertEquals(2004, check.checkedSettings()); assertEquals(check, service.inspectAt(AT));
        for (var row : report.rows()) {
            var expected = expected(row.input()); var a = row.analysis();
            assertEquals(expected.scope(), a.clientScope()); assertEquals(expected.status(), a.status()); assertEquals(expected.checks(), a.checks());
            assertEquals(a, ArchitectureConfigurationEvaluator.evaluate(row.input().patternId(), expected.scope(), row.input().settings()));
            assertEquals(row.savedInputPreflight(), ArchitecturePatternEvaluator.evaluate(row.input().profile()).stream().filter(p -> p.patternId() == row.input().patternId()).findFirst().orElseThrow());
            if (row.input().designVariant() == DesignVariant.MISMATCH_WITH_GAP && a.clientScope() == ArchitecturePrerequisiteEvaluator.ClientScope.SELECTED) {
                assertEquals(ArchitecturePrerequisiteEvaluator.Status.CONDITIONALLY_DOES_NOT_MATCH, a.status());
                assertEquals(1, a.checks().stream().filter(c -> c.outcome() == ArchitecturePrerequisiteEvaluator.Outcome.UNKNOWN).count());
            }
            if (row.input().patternId() == ArchitecturePatternPreflight.PatternId.SPA_CODE_PKCE && row.input().designVariant() == DesignVariant.REFERENCE_DESIGN
                    && row.input().contextVariant() == ContextVariant.BASE_PROFILE && !row.input().scenarioId().equals("internal-workforce-scoped")) {
                assertEquals(ArchitecturePrerequisiteEvaluator.Status.CONDITIONALLY_MATCHES, a.status());
                assertEquals(ArchitecturePatternPreflight.Status.NEEDS_INFORMATION, row.savedInputPreflight().status());
            }
        }
        assertEquals(BASE_SHA256, CatalogDraftCanonicalizer.sha256(base.definitions()));
    }
    @Test void summaryIsBodyFreeAndDoesNotGrantVerificationCoverageOrAuthority() {
        var check = service.inspectAt(AT); var json = mapper.valueToTree(check);
        for (String flag : List.of("candidateChangesEvaluated", "coverageComplete", "configurationObserved", "configurationVerified", "providerCompatibilityVerified", "runtimeFlowVerified",
                "sourceVerificationPerformed", "storedReportVerified", "baselineVerified", "approvalGranted", "publicationReady", "evaluationReady", "recommendationReady", "writesPerformed")) assertFalse(json.get(flag).asBoolean());
        for (String field : List.of("profile", "settings", "sourceUrl", "observedAt", "actor", "rows", "scenarioId", "optionId", "workspaceId")) assertFalse(json.has(field), field);
        var old = new CatalogProfileImpactCoverageService(base, new CatalogArchitectureImpactService(base, mapper)).inspectAt(AT);
        assertEquals("catalog-profile-impact-coverage-4", old.policyVersion()); assertFalse(old.coverageComplete());
        assertEquals(12, old.additionalGaps().size());
        assertEquals(ArchitectureConfigurationEvaluator.DEFERRED_BOUNDARIES, check.deferredBoundaries());
        assertAll(
            () -> assertEquals("404fa56ca63260e017beb842cfd53e3ad9328920c06bfa02dfaec065793465ec", check.scenarioSetSha256()),
            () -> assertEquals("c3ada467dbf3d915d20ceb5a2bddb60c3edabc3ac6ac5a70857562e26640a9f9", check.definitionsSha256()),
            () -> assertEquals("5f833ddf0efd9b1a7148ddd103b4d6f9a0cd6180b5acd2234c3d41d1a664923b", check.analysisSha256()));
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "duplicate", "reorder", "hash", "settings", "preflight", "scope"})
    void partialOrSubstitutedReportsAreRefusedEvenWhenTheirShapeAndTotalsCanLookValid(String variant) {
        var report = service.analyzeAt(AT); var rows = new ArrayList<>(report.rows()); var first = rows.getFirst();
        switch (variant) {
            case "missing" -> rows.removeLast();
            case "duplicate" -> rows.set(1, first);
            case "reorder" -> java.util.Collections.swap(rows, 0, 1);
            case "settings" -> rows.set(0, new Row(first.input(), first.savedInputPreflight(), ArchitectureConfigurationEvaluator.evaluate(first.input().patternId(), first.analysis().clientScope(), REFERENCES.get(first.input().patternId()))));
            case "preflight" -> rows.set(0, new Row(first.input(), rows.get(4).savedInputPreflight(), first.analysis()));
            case "scope" -> rows.set(0, new Row(first.input(), first.savedInputPreflight(), ArchitectureConfigurationEvaluator.evaluate(first.input().patternId(), ArchitecturePrerequisiteEvaluator.ClientScope.UNKNOWN, Map.of())));
        }
        assertThrows(IllegalArgumentException.class, () -> service.summarize(new Analysis(AT, variant.equals("hash") ? "0".repeat(64) : report.scenarioSetSha256(), rows)));
    }
    @Test void freshClockOnlyChangesAnalysisHashAndDoesNotRefreshFixtureInputsOrChangeResults() {
        var original = service.inspectAt(AT); var later = service.inspectAt(AT.plusSeconds(1));
        assertEquals(original.scenarioSetSha256(), later.scenarioSetSha256()); assertEquals(original.definitionsSha256(), later.definitionsSha256());
        assertEquals(original.outcomes(), later.outcomes()); assertEquals(original.results(), later.results()); assertNotEquals(original.analysisSha256(), later.analysisSha256());
        assertThrows(NullPointerException.class, () -> service.inspectAt(null));
    }
    @Test void summaryRejectsOverflowCountDriftReasonOrderAndOutcomeParity() {
        var r = service.inspectAt(AT);
        assertThrows(IllegalArgumentException.class, () -> new Counts(Integer.MAX_VALUE, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Results(-1, 1, 1, 1));
        for (var variant : List.of("count", "reason", "parity", "hash", "preflight")) {
            var reasons = new ArrayList<>(r.reasons()); if (variant.equals("reason")) reasons.set(1, reasons.getFirst());
            assertThrows(IllegalArgumentException.class, () -> new Check(AT, variant.equals("hash") ? "bad" : r.scenarioSetSha256(), r.analysisSha256(),
                    variant.equals("parity") ? new Counts(291, 19, 986, 708) : r.outcomes(), r.results(), reasons,
                    variant.equals("count") ? 0 : r.selectedCases(), r.unknownClientCases(), r.unselectedClientCases(), variant.equals("preflight") ? 0 : r.savedInputNeedsInformation()));
        }
    }
}

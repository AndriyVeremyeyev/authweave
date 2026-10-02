package io.authweave.core.catalog.impact;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion;
import io.authweave.core.catalog.*;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.evaluation.*;
import static io.authweave.core.catalog.impact.CatalogAuditabilityRegressionService.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogAuditabilityRegressionTests {
    private static final Instant AT = Instant.parse("2026-09-12T12:00:00Z");
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CatalogScopedProfileCases base = sourceCases();
    private final CatalogAuditabilityRegressionCases cases = cases();
    private final AuditabilityCatalog evidence = resource("auditability-evidence.v1.json", AuditabilityCatalog.class);
    private final CatalogAuditabilityRegressionService service = new CatalogAuditabilityRegressionService(cases, resource("synthetic.v4.json", ProviderCatalog.class), evidence);
    private <T> T resource(String name, Class<T> type) {
        try (var input = new ClassPathResource("catalog/" + name).getInputStream()) { return mapper.readValue(input, type); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private CatalogScopedProfileCases sourceCases() { try { return new CatalogScopedProfileCases(mapper); } catch (Exception e) { throw new IllegalStateException(e); } }
    private CatalogAuditabilityRegressionCases cases() { try { return new CatalogAuditabilityRegressionCases(mapper, base); } catch (Exception e) { throw new IllegalStateException(e); } }

    @Test void explicitOverlaysCompileV6WithoutMutatingFrozenProfilesAndPinIndependentSchema() {
        assertEquals("ce70ce85cbb2e5b1537f36e2120a5c4add5ef0ce5d64be46397436bbc1993ca1", base.sha256());
        assertEquals(base.sha256(), cases.baseScenarioSetSha256()); assertEquals(4, cases.definitions().size());
        assertEquals(base.sha256(), CatalogDraftCanonicalizer.sha256(base.definitions()));
        assertTrue(base.definitions().stream().allMatch(d -> d.profileSchemaVersion() == 5 && d.profile().at("/security/auditabilityRequirements").isMissingNode()));
        assertEquals(CatalogScopedProfileCases.IDS, Set.copyOf(cases.definitions().stream().map(CatalogAuditabilityRegressionCases.Definition::scenarioId).toList()));
        assertEquals(19, cases.definitions().stream().mapToInt(d -> d.requirements().selectedCriteria().size()).sum());
        var schema = mapper.readTree(Path.of(System.getProperty("basedir", "."), "../../packages/contracts/schemas/application-identity-profile.v6.schema.json").toFile());
        assertEquals(CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_SHA256, CatalogDraftCanonicalizer.sha256(schema));
        assertThrows(UnsupportedOperationException.class, () -> cases.definitions().clear());
        assertNotEquals(cases.sha256(), cases.baseScenarioSetSha256()); assertTrue(cases.sha256().matches("[a-f0-9]{64}"));
    }
    @Test void allFourProfilesAndExactScopesKeepMatchesMismatchesAndInformationGapsSeparate() {
        var report = service.analyzeAt(AT); var result = service.summarize(report);
        assertEquals("catalog-auditability-regression-1", result.policyVersion());
        assertEquals("4cb0bf7ca7372d127eebc98f4a7e06b332df5f347ea75e94928d23b9ba3325f5", result.scenarioSetSha256());
        assertEquals("1e82e473facce5ec245644cbf65581830125202a0b8c33c3d260bcab012cee1a", result.definitionsSha256());
        assertEquals("034db9f068b0d03e5a96d54cc668b09b64e7927df59fb6176228ba2b40bcab13", result.evidenceSha256());
        assertEquals("eb62b912b951afeed6b3626d4f2cb7f66aa0efe2b9245611742458af9e4a920b", result.analysisSha256());
        assertEquals(12, report.scenarios().size()); assertEquals(3, result.checkedScopes()); assertEquals(12, result.checkedCases());
        assertEquals(72, result.checkedCriteria()); assertEquals(new OutcomeCounts(27, 8, 22, 15), result.outcomes());
        assertEquals(new CandidateCounts(3, 5, 4, 0), result.candidates());
        assertEquals(3, reasons(result, AuditabilityEvaluator.Reason.EVIDENCE_MISSING));
        assertEquals(19, reasons(result, AuditabilityEvaluator.Reason.EVIDENCE_UNREVIEWED));
        assertEquals(4, reasons(result, AuditabilityEvaluator.Reason.RETENTION_BELOW_MINIMUM));
        assertEquals(2, reasons(result, AuditabilityEvaluator.Reason.RETENTION_MEETS_MINIMUM));
        assertEquals(4, reasons(result, AuditabilityEvaluator.Reason.CAPABILITY_UNAVAILABLE));
        assertTrue(result.allDeclaredCriteriaExercised()); assertEquals(List.of(Criterion.values()), result.exercisedRequiredCriteria());
        assertEquals(result, service.inspectAt(AT));
        for (var row : report.scenarios()) {
            var option = evidence.options().stream().filter(o -> o.scope().equals(row.analysis().optionScope())).findFirst().orElseThrow();
            assertEquals(option.facts(), row.evidence()); assertEquals(6, row.analysis().checks().size());
            assertEquals(row.analysis(), AuditabilityEvaluator.evaluate(row.analysis().criticality(), row.analysis().requirements(), option.scope(), row.evidence(), AT));
        }
        var json = mapper.valueToTree(result);
        for (String flag : List.of("coverageComplete", "candidateChangesEvaluated", "configurationVerified", "complianceVerified", "sourceVerificationPerformed",
                "storedReportVerified", "baselineVerified", "approvalGranted", "writesPerformed", "publicationReady", "evaluationReady", "recommendationReady")) assertFalse(json.get(flag).asBoolean());
        for (String forbidden : List.of("sourceUrl", "observedAt", "profileSha256", "profile", "scenarioId", "optionId", "actor", "requirements", "facts")) assertFalse(json.toString().contains("\"" + forbidden + "\":"), forbidden);
        assertEquals(AuditabilityEvaluator.DEFERRED_BOUNDARIES, result.deferredBoundaries());
        var oldCoverage = new CatalogProfileImpactCoverageService(base, new CatalogArchitectureImpactService(base, mapper)).inspectAt(AT);
        assertEquals("catalog-profile-impact-coverage-4", oldCoverage.policyVersion()); assertEquals(5, oldCoverage.profileSchemaVersion());
        assertEquals(40, oldCoverage.deferredDimensions()); assertFalse(oldCoverage.coverageComplete());
        assertTrue(oldCoverage.dimensions().stream().filter(d -> d.profilePath().equals("security.auditability"))
                .allMatch(d -> d.state() == CatalogProfileImpactCoverageService.State.DEFERRED_DIMENSION));
    }
    @ParameterizedTest @ValueSource(strings = {"future", "boundary", "stale"})
    void sourceDatesRemainFixedAndNanosecondFreshnessUsesTheExistingEvidenceGate(String variant) {
        var at = variant.equals("future") ? AT.minusNanos(1) : variant.equals("boundary") ? AT.plus(EvidencePolicy.MAX_AGE) : AT.plus(EvidencePolicy.MAX_AGE).plusNanos(1);
        var report = service.analyzeAt(at); var result = service.summarize(report);
        assertEquals(AT, report.scenarios().getFirst().evidence().getFirst().observedAt());
        assertEquals(service.inspectAt(AT).evidenceSha256(), result.evidenceSha256()); assertNotEquals(service.inspectAt(AT).analysisSha256(), result.analysisSha256());
        if (variant.equals("boundary")) assertEquals(new OutcomeCounts(27, 8, 22, 15), result.outcomes());
        else {
            assertEquals(new OutcomeCounts(0, 0, 57, 15), result.outcomes()); assertEquals(new CandidateCounts(0, 0, 12, 0), result.candidates());
            assertEquals(35, reasons(result, variant.equals("future") ? AuditabilityEvaluator.Reason.EVIDENCE_FROM_FUTURE : AuditabilityEvaluator.Reason.EVIDENCE_STALE));
        }
        assertEquals(19, reasons(result, AuditabilityEvaluator.Reason.EVIDENCE_UNREVIEWED)); assertEquals(3, reasons(result, AuditabilityEvaluator.Reason.EVIDENCE_MISSING));
    }
    @ParameterizedTest @ValueSource(strings = {"duplicate", "empty", "duration-missing", "duration-unselected", "duration-low", "duration-high", "null-criterion"})
    void rejectsAmbiguousRequirementsInsteadOfInferringScopeOrClampingDuration(String variant) {
        var selected = new ArrayList<>(List.of(Criterion.AUDIT_LOG_RETENTION)); Integer duration = 30;
        switch (variant) {
            case "duplicate" -> selected.add(Criterion.AUDIT_LOG_RETENTION);
            case "empty" -> { selected.clear(); duration = null; }
            case "duration-missing" -> duration = null;
            case "duration-unselected" -> selected = new ArrayList<>(List.of(Criterion.AUDIT_LOG_EXPORT));
            case "duration-low" -> duration = 0;
            case "duration-high" -> duration = 36501;
            case "null-criterion" -> selected.add(null);
        }
        var criteria = selected; var minimum = duration;
        assertThrows(RuntimeException.class, () -> new CatalogAuditabilityRegressionCases.Input("b2b-saas-scoped", "Synthetic", criteria, minimum));
    }
    @ParameterizedTest @ValueSource(strings = {"schema", "base-version", "base-digest", "missing", "duplicate", "extra", "missing-criterion"})
    void scenarioSuiteDriftFailsClosedRatherThanReleasingAPartialMatrix(String variant) {
        var original = resource("scoped-auditability-scenarios.v1.json", CatalogAuditabilityRegressionCases.Suite.class);
        var inputs = new ArrayList<>(original.scenarios());
        if (variant.equals("missing")) inputs.removeLast();
        if (variant.equals("duplicate")) inputs.set(1, inputs.getFirst());
        if (variant.equals("extra")) inputs.add(inputs.getFirst());
        if (variant.equals("missing-criterion")) inputs.replaceAll(input -> new CatalogAuditabilityRegressionCases.Input(input.scenarioId(), input.description(),
                input.selectedCriteria().stream().filter(c -> c != Criterion.AUTHENTICATION_SUCCESS_EVENTS).toList(), input.minimumRetentionDays()));
        assertThrows(RuntimeException.class, () -> CatalogAuditabilityRegressionCases.compile(mapper, base,
                new CatalogAuditabilityRegressionCases.Suite(variant.equals("schema") ? 2 : 1, variant.equals("base-version") ? "other-1" : original.baseScenarioSetVersion(),
                        variant.equals("base-digest") ? "0".repeat(64) : original.baseScenarioSetSha256(), inputs)));
    }
    @ParameterizedTest @ValueSource(strings = {"scenario-digest", "evidence-digest", "partial-scopes", "duplicate-case", "profile-digest", "pooled-evidence", "changed-time"})
    void rejectsForgedBindingsAndIncompleteScopedMatrices(String variant) {
        var original = service.analyzeAt(AT); var rows = new ArrayList<>(original.scenarios()); var first = rows.getFirst();
        assertThrows(RuntimeException.class, () -> {
            switch (variant) {
                case "partial-scopes" -> rows.removeIf(row -> !row.analysis().optionScope().equals(first.analysis().optionScope()));
                case "duplicate-case" -> rows.set(1, first);
                case "profile-digest" -> rows.set(0, new Case(first.scenarioId(), "0".repeat(64), first.analysis(), first.evidence()));
                case "pooled-evidence" -> rows.set(0, new Case(first.scenarioId(), first.profileSha256(), first.analysis(), rows.get(1).evidence()));
                case "changed-time" -> rows.set(0, new Case(first.scenarioId(), first.profileSha256(), AuditabilityEvaluator.evaluate(first.analysis().criticality(),
                        first.analysis().requirements(), first.analysis().optionScope(), first.evidence(), AT.plusNanos(1)), first.evidence()));
            }
            service.summarize(new Analysis(AT, variant.equals("scenario-digest") ? "0".repeat(64) : original.scenarioSetSha256(), original.baseCatalogVersion(), original.evidenceVersion(),
                    variant.equals("evidence-digest") ? "0".repeat(64) : original.evidenceSha256(), rows));
        });
    }
    @Test void summaryRejectsCountParityDriftOverflowAndDuplicateReasonInventory() {
        var r = service.inspectAt(AT);
        assertThrows(IllegalArgumentException.class, () -> new OutcomeCounts(Integer.MAX_VALUE, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CandidateCounts(-1, 1, 1, 1));
        for (String variant : List.of("count", "reasons", "outcome-parity", "criteria", "hash")) {
            var reasons = new ArrayList<>(r.reasons()); var criteria = new ArrayList<>(r.exercisedRequiredCriteria());
            if (variant.equals("reasons")) reasons.set(1, reasons.getFirst()); if (variant.equals("criteria")) criteria.add(criteria.getFirst());
            assertThrows(IllegalArgumentException.class, () -> new Check(r.evaluatedAt(), variant.equals("hash") ? "bad" : r.scenarioSetSha256(), r.baseCatalogVersion(), r.evidenceVersion(),
                    r.evidenceSha256(), r.analysisSha256(), r.checkedScopes(), variant.equals("count") ? 4 : r.checkedCases(),
                    variant.equals("outcome-parity") ? new OutcomeCounts(26, 8, 23, 15) : r.outcomes(), r.candidates(), reasons, criteria));
        }
    }
    private static int reasons(Check result, AuditabilityEvaluator.Reason reason) { return result.reasons().stream().filter(r -> r.reasonCode() == reason).findFirst().orElseThrow().checks(); }
}

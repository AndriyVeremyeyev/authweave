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
import io.authweave.core.assessment.domain.profile.*;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.Outcome;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.PatternId;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.Status;
import io.authweave.core.evaluation.ProvisioningLifecycleV2Evaluator.ConditionId;
import io.authweave.core.evaluation.ProvisioningLifecycleV2Evaluator.GroupStrategy;
import static io.authweave.core.catalog.impact.CatalogLifecycleRegressionCases.*;
import static io.authweave.core.catalog.impact.CatalogLifecycleRegressionService.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogLifecycleRegressionTests {
    private static final Instant AT = Instant.parse("2026-09-12T12:00:00Z");
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CatalogScopedProfileCases base = base();
    private final CatalogAuditabilityRegressionCases auditability = auditability();
    private final CatalogLifecycleRegressionCases cases = new CatalogLifecycleRegressionCases(base, auditability, mapper);
    private final CatalogLifecycleRegressionService service = new CatalogLifecycleRegressionService(cases);
    private CatalogScopedProfileCases base() { try { return new CatalogScopedProfileCases(mapper); } catch (Exception e) { throw new IllegalStateException(e); } }
    private CatalogAuditabilityRegressionCases auditability() { try { return new CatalogAuditabilityRegressionCases(mapper, base); } catch (Exception e) { throw new IllegalStateException(e); } }
    @Test void explicitOverlaysPreserveFrozenSourcesAndV6AuditRequirementsWithoutInventingDefaults() {
        assertEquals(2016, cases.definitions().size()); assertEquals(2016, cases.definitions().stream().map(Definition::key).distinct().count());
        assertEquals(Set.of(RequirementCriticality.values()), cases.definitions().stream().map(d -> d.requirements().scim()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(ConditionId.values()), cases.definitions().stream().flatMap(d -> scope(d.patternId(), d.groupStrategy()).stream()).collect(java.util.stream.Collectors.toSet()));
        for (var d : cases.definitions()) {
            var source = base.definitions().stream().filter(s -> s.id().equals(d.scenarioId())).findFirst().orElseThrow();
            var supplement = auditability.definitions().stream().filter(s -> s.scenarioId().equals(d.scenarioId())).findFirst().orElseThrow();
            var profile = (ObjectNode) source.profile(); ((ObjectNode) profile.get("security")).set("auditabilityRequirements", mapper.valueToTree(supplement.requirements()));
            assertEquals(d.sourceProfileSha256(), CatalogDraftCanonicalizer.sha256(profile)); profile.set("provisioning", mapper.valueToTree(d.requirements()));
            assertEquals(d.profileSha256(), CatalogDraftCanonicalizer.sha256(profile));
            var typed = mapper.treeToValue(profile, ApplicationIdentityProfile.class);
            assertTrue(ApplicationIdentityProfileValidator.validate(typed).issues().isEmpty()); assertEquals(6, typed.minimumSchemaVersion());
            assertEquals(supplement.requirements(), typed.security().auditabilityRequirements()); assertEquals(source.profile().get("operations"), profile.get("operations"));
            assertEquals(source.profile().get("application"), profile.get("application")); assertTrue(source.profile().at("/security/auditabilityRequirements").isMissingNode());
            if (d.requirementVariant() == RequirementVariant.BASE_PROFILE) assertEquals(source.profile().get("provisioning"), profile.get("provisioning"));
            assertTrue(scope(d.patternId(), d.groupStrategy()).containsAll(d.declarations().keySet()));
            if (d.declarationVariant().name().startsWith("GROUP_REMOVAL")) assertTrue(d.groupStrategy() == GroupStrategy.SCIM_GROUPS || d.groupStrategy() == GroupStrategy.APPLICATION_BRIDGE);
        }
        assertEquals(BASE_SHA256, CatalogDraftCanonicalizer.sha256(base.definitions()));
        assertThrows(UnsupportedOperationException.class, () -> cases.definitions().clear()); assertThrows(UnsupportedOperationException.class, () -> cases.definitions().getFirst().declarations().clear());
        assertThrows(IllegalArgumentException.class, () -> declarations(PatternId.SCIM_PUSH, GroupStrategy.NONE, DeclarationVariant.GROUP_REMOVAL_GAP));
    }
    @Test void independentGoldenCountsAndEveryScopedCheckMatchCompleteRegression() {
        var report = service.analyzeAt(AT); var check = service.summarize(report);
        assertEquals(new Results(78, 1253, 685), check.results()); assertEquals(new Counts(1304, 1102, 1374, 2268), check.requirementOutcomes());
        assertEquals(new Counts(960, 192, 432, 432), check.designOutcomes()); assertEquals(new Counts(11616, 3552, 7392, 0), check.conditionOutcomes());
        assertEquals(new Counts(2304, 1152, 2592, 0), check.offboardingOutcomes()); assertEquals(new Counts(432, 288, 432, 0), check.groupRemovalOutcomes());
        assertEquals(22560, check.checkedConditionChecks()); assertEquals(30624, check.reasons().stream().mapToInt(ReasonCount::checks).sum());
        assertTrue(check.reasons().stream().allMatch(r -> r.checks() > 0)); assertEquals(check, service.inspectAt(AT));
        for (var row : report.rows()) {
            var expected = expected(row.input()); var actual = row.analysis(); assertEquals(AT, row.evaluatedAt());
            assertEquals(expected.requirementChecks(), actual.requirementChecks()); assertEquals(expected.designChecks(), actual.designChecks());
            assertEquals(expected.conditionChecks(), actual.conditionChecks()); assertEquals(expected.status(), actual.status());
        }
    }
    @Test void hardFailuresKeepOffboardingAndGroupGapsVisibleAndBridgesCannotReplaceScim() {
        for (var row : service.analyzeAt(AT).rows()) {
            var d = row.input(); var actual = row.analysis();
            if (d.declarationVariant() == DeclarationVariant.OFFBOARDING_FAILURE_WITH_GAP) {
                assertEquals(Status.CONDITIONALLY_DOES_NOT_MATCH, actual.status()); assertEquals(Outcome.UNKNOWN, actual.conditionChecks().stream().filter(c -> c.conditionId() == ConditionId.TOKEN_REVOCATION_OR_BOUNDED_EXPIRY).findFirst().orElseThrow().outcome());
            }
            if (d.declarationVariant() == DeclarationVariant.GROUP_REMOVAL_FAILURE_WITH_GAP) {
                assertEquals(Status.CONDITIONALLY_DOES_NOT_MATCH, actual.status()); assertEquals(Outcome.UNKNOWN, actual.conditionChecks().stream().filter(c -> c.conditionId() == ConditionId.GROUP_TO_ROLE_MAPPING_AND_ENFORCEMENT).findFirst().orElseThrow().outcome());
            }
            if (d.requirements().scim() == RequirementCriticality.REQUIRED && d.patternId() == PatternId.JIT_LOGIN) assertEquals(Outcome.CONDITIONALLY_NOT_SATISFIED, actual.requirementChecks().getFirst().outcome());
            if (d.patternId() == PatternId.JIT_LOGIN && d.groupStrategy() == GroupStrategy.SCIM_GROUPS) assertEquals(Outcome.CONDITIONALLY_NOT_SATISFIED, actual.designChecks().getFirst().outcome());
        }
    }
    @Test void bodyFreeSummaryKeepsEveryVerificationAndPublicationBoundaryFalse() {
        var r = service.inspectAt(AT); var json = mapper.valueToTree(r);
        for (String flag : List.of("candidateChangesEvaluated", "coverageComplete", "configurationVerified", "providerCompatibilityVerified", "lifecycleVerified", "groupSynchronizationVerified", "accessRevocationVerified",
                "sourceVerificationPerformed", "storedReportVerified", "baselineVerified", "approvalGranted", "publicationReady", "evaluationReady", "recommendationReady", "writesPerformed")) assertFalse(json.get(flag).asBoolean());
        for (String field : List.of("profile", "requirements", "declarations", "rows", "actor", "workspaceId", "sourceUrl", "winner")) assertFalse(json.has(field));
        var old = new CatalogProfileImpactCoverageService(base, new CatalogArchitectureImpactService(base, mapper)).inspectAt(AT);
        assertEquals("catalog-profile-impact-coverage-4", old.policyVersion()); assertFalse(old.coverageComplete()); assertEquals(12, old.additionalGaps().size());
        assertEquals("bb56c1fb90eca12be7e84b535b0fe70eb3c96386fb09020f04d8d0d4d140e608", r.scenarioSetSha256());
        assertEquals("c81c99b5da95215ab04e06489dec50afa7954bccd065ae68a9179951259b5e1c", r.definitionsSha256());
        assertEquals("f4e0b12d77a7021b46a74a1db09b3d718e748801318418f9c4410312643c5b2c", r.analysisSha256());
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "duplicate", "reorder", "hash", "input", "analysis", "clock"})
    void exactReplayRejectsIncompleteSubstitutedOrMixedClockReports(String variant) {
        var report = service.analyzeAt(AT); var rows = new ArrayList<>(report.rows()); var first = rows.getFirst();
        switch (variant) {
            case "missing" -> rows.removeLast(); case "duplicate" -> rows.set(1, first); case "reorder" -> java.util.Collections.swap(rows, 0, 1);
            case "input" -> rows.set(0, new Row(rows.get(1).input(), AT, first.analysis()));
            case "analysis" -> rows.set(0, new Row(first.input(), AT, rows.get(1).analysis()));
            case "clock" -> rows.set(0, new Row(first.input(), AT.plusSeconds(1), first.analysis()));
        }
        assertThrows(IllegalArgumentException.class, () -> service.summarize(new Report(AT, variant.equals("hash") ? "0".repeat(64) : report.scenarioSetSha256(), rows)));
    }
    @Test void freshClockChangesOnlyAnalysisBindingNotRequirementsOrEvidenceFreshness() {
        var original = service.inspectAt(AT); var later = service.inspectAt(AT.plusSeconds(1));
        assertEquals(original.scenarioSetSha256(), later.scenarioSetSha256()); assertEquals(original.definitionsSha256(), later.definitionsSha256());
        assertEquals(original.results(), later.results()); assertEquals(original.offboardingOutcomes(), later.offboardingOutcomes()); assertNotEquals(original.analysisSha256(), later.analysisSha256());
        assertThrows(NullPointerException.class, () -> service.inspectAt(null));
    }
    @Test void countOverflowPartialReasonInventoriesAndImpossibleTotalsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Counts(Integer.MAX_VALUE, 0, 0, 0)); assertThrows(IllegalArgumentException.class, () -> new Results(-1, 0, 2017));
        var r = service.inspectAt(AT);
        for (String variant : List.of("hash", "count", "reason", "conditions", "offboarding", "group")) {
            var reasons = new ArrayList<>(r.reasons()); if (variant.equals("reason")) reasons.set(1, reasons.getFirst());
            assertThrows(IllegalArgumentException.class, () -> new Check(AT, variant.equals("hash") ? "bad" : r.scenarioSetSha256(), r.auditabilityScenarioSetSha256(), r.analysisSha256(), r.results(),
                variant.equals("count") ? new Counts(0, 0, 0, 0) : r.requirementOutcomes(), r.designOutcomes(), r.conditionOutcomes(),
                variant.equals("offboarding") ? new Counts(0, 0, 0, 0) : r.offboardingOutcomes(), variant.equals("group") ? new Counts(0, 0, 0, 0) : r.groupRemovalOutcomes(),
                variant.equals("conditions") ? 22559 : r.checkedConditionChecks(), reasons));
        }
    }
}

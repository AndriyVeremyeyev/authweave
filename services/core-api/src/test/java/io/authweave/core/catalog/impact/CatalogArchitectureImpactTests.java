package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.evaluation.ArchitecturePatternEvaluator;
import io.authweave.core.evaluation.ArchitecturePatternPreflight;
import io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator;
import static io.authweave.core.catalog.impact.CatalogArchitectureImpactService.*;
import static io.authweave.core.evaluation.ArchitecturePatternPreflight.Outcome.*;
import static io.authweave.core.evaluation.ArchitecturePatternPreflight.Reason.*;
import static io.authweave.core.evaluation.ArchitecturePatternPreflight.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogArchitectureImpactTests {
    private static final Instant AT = Instant.parse("2026-10-01T12:00:00Z");
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CatalogScopedProfileCases cases = cases();
    private final CatalogArchitectureImpactService service = new CatalogArchitectureImpactService(cases, mapper);

    @Test void sourcePolicyAndLibraryAreFrozenWithoutRewritingHistoricalCasesOrExistingArchitectureRules() throws Exception {
        assertEquals("catalog-architecture-impact-2", POLICY_VERSION);
        assertEquals("architecture-pattern-preflight-1", ArchitecturePatternEvaluator.POLICY_VERSION);
        assertEquals("761cf0336027f10560463ef09aae559b7539dea162fb13a50d080e7becead138", DEFINITIONS_SHA256); // Review and version the source-owned rule library before changing it.
        assertEquals("ce70ce85cbb2e5b1537f36e2120a5c4add5ef0ce5d64be46397436bbc1993ca1", cases.sha256());
        assertEquals("ad34a1fba95468535dc7919cab839a7828497847198112a1f86157723c5e5eac", new CatalogScenarioCases(mapper).sha256());
        assertEquals(5, DEFINITIONS.size()); assertEquals(5, DEFINITIONS.stream().map(Definition::patternId).distinct().count());
        assertTrue(DEFINITIONS.stream().allMatch(d -> !d.advantages().isEmpty() && !d.tradeoffs().isEmpty() && !d.prerequisites().isEmpty() && !d.references().isEmpty()));
        assertThrows(UnsupportedOperationException.class, () -> DEFINITIONS.clear());
        assertThrows(UnsupportedOperationException.class, () -> DEFINITIONS.getFirst().prerequisites().clear());
    }

    @Test void completeMatrixOfPerformedRulesIsNotCompleteConfigurationEvidenceOrAChosenWinner() {
        var report = service.analyzeAt(AT); var summary = service.summarize(report);
        assertEquals(report, service.analyzeAt(AT)); assertEquals(summary, service.inspectAt(AT));
        assertEquals(4, report.scenarios().size()); assertEquals(CheckStatus.ANALYZED, summary.status());
        assertEquals(4, summary.checkedProfiles()); assertEquals(20, summary.checkedPatterns());
        assertEquals(15, summary.conditionalMatches()); assertEquals(3, summary.needsInformation()); assertEquals(2, summary.notApplicable());
        assertEquals(new PrerequisiteCounts(44, 0, 0, 38, 6), summary.prerequisiteCounts());
        assertEquals("architecture-prerequisites-1", summary.prerequisitePolicyVersion());
        assertEquals("9e98ff927c1fd038f8dd991c34f261c1768da3e9f8deffc0849d4aad755fadbd", PREREQUISITES_SHA256); // Review and version prerequisite IDs, meanings and pattern scope together.
        assertTrue(summary.allDeclaredPatternsChecked()); assertEquals(CatalogDraftCanonicalizer.sha256(report), summary.analysisSha256());
        assertEquals(cases.sha256(), summary.scenarioSetSha256()); assertEquals(AT, summary.evaluatedAt());
        assertEquals("CONDITIONAL_PATTERN_PREREQUISITES_NOT_VERIFIED", report.analysisBasis());
        for (var value : List.of(report, summary)) {
            var json = mapper.valueToTree(value);
            for (var flag : List.of("configurationVerified", "prerequisitesVerified", "providerCompatibilityVerified", "coverageComplete",
                    "sourceVerificationPerformed", "baselineVerified", "approvalGranted", "writesPerformed", "publicationReady", "evaluationReady", "recommendationReady"))
                assertFalse(json.get(flag).asBoolean(), flag);
            assertFalse(json.has("winner")); assertFalse(json.has("score")); assertFalse(json.has("actor")); assertFalse(json.has("profile"));
        }
        assertFalse(summary.storedReportVerified());
        var summaryJson = mapper.writeValueAsString(summary);
        assertFalse(summaryJson.contains("https://")); assertFalse(summaryJson.contains("\"DE\"")); assertFalse(summaryJson.contains("scopeDescription"));
        assertThrows(UnsupportedOperationException.class, () -> report.scenarios().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.scenarios().getFirst().patterns().getFirst().checks().clear());
    }

    static Stream<Arguments> clientAndCriticalityMatrix() {
        return Arrays.stream(RequirementCriticality.values()).flatMap(requirement -> IntStream.range(0, 8).mapToObj(mask -> Arguments.of(requirement, mask)));
    }
    @ParameterizedTest @MethodSource("clientAndCriticalityMatrix")
    void everyClientSubsetAndCriticalityUsesExistingRulesWithoutInventedTokenBansOrProviderFacts(RequirementCriticality requirement, int mask) {
        var clients = IntStream.range(0, ClientType.values().length).filter(i -> (mask & (1 << i)) != 0).mapToObj(i -> ClientType.values()[i]).toList();
        var definitions = cases.definitions().stream().map(d -> {
            var json = (ObjectNode) d.profile();
            ((ObjectNode) json.get("application")).set("clients", mapper.valueToTree(clients));
            ((ObjectNode) json.get("security")).put("browserTokenExposureMinimization", requirement.name());
            return new CatalogScenarioCases.Definition(d.id(), d.description(), 5, json);
        }).toList();
        var report = service.analyze(definitions, CatalogDraftCanonicalizer.sha256(definitions), AT);
        assertEquals(20, service.summarize(report).checkedPatterns());
        for (int i = 0; i < report.scenarios().size(); i++) {
            var patterns = report.scenarios().get(i).patterns();
            var expected = ArchitecturePatternEvaluator.evaluate(mapper.treeToValue(definitions.get(i).profile(), ApplicationIdentityProfile.class));
            assertEquals(expected.stream().map(p -> new PatternResult(p.patternId(), p.status(),
                    p.checks().stream().map(c -> new RuleCheck(c.profilePath(), c.outcome(), c.reasonCode())).toList(),
                    ArchitecturePrerequisiteEvaluator.evaluate(p.patternId(), ArchitecturePrerequisiteEvaluator.scope(p), Map.of()))).toList(), patterns);
            for (int j = 0; j < patterns.size(); j++) {
                var pattern = patterns.get(j); var definition = DEFINITIONS.get(j); var token = pattern.checks().get(1);
                boolean selected = clients.contains(definition.clientType());
                assertEquals(clients.isEmpty() ? ArchitecturePrerequisiteEvaluator.ClientScope.UNKNOWN : selected
                        ? ArchitecturePrerequisiteEvaluator.ClientScope.SELECTED : ArchitecturePrerequisiteEvaluator.ClientScope.NOT_SELECTED, pattern.prerequisites().clientScope());
                assertTrue(pattern.prerequisites().checks().stream().allMatch(c -> c.outcome() == (!clients.isEmpty() && !selected
                        ? ArchitecturePrerequisiteEvaluator.Outcome.NOT_APPLICABLE : ArchitecturePrerequisiteEvaluator.Outcome.UNKNOWN)));
                if (clients.isEmpty()) {
                    assertEquals(NEEDS_INFORMATION, pattern.status()); assertEquals(UNKNOWN, token.outcome()); assertEquals(CLIENT_CONTEXT_UNKNOWN, token.reasonCode());
                } else if (!selected) {
                    assertEquals(NOT_APPLICABLE, pattern.status()); assertEquals(NOT_APPLIED, token.outcome()); assertEquals(PATTERN_NOT_APPLICABLE, token.reasonCode());
                } else if (definition.clientType() != ClientType.BROWSER) {
                    assertEquals(MATCHES_CHECKED_REQUIREMENTS, pattern.status()); assertEquals(NOT_APPLIED, token.outcome()); assertEquals(BROWSER_CRITERION_NOT_APPLICABLE, token.reasonCode());
                } else {
                    var reason = switch (requirement) {
                        case REQUIRED -> definition.tokenHandling() == ArchitecturePatternPreflight.TokenHandling.SERVER_SIDE ? TOKENS_HELD_SERVER_SIDE : ACCEPTABLE_EXPOSURE_UNDEFINED;
                        case PREFERRED -> PREFERENCE_NOT_SCORED;
                        case NOT_REQUIRED -> NO_REQUIREMENT;
                        case UNKNOWN -> REQUIREMENT_UNKNOWN;
                        case FORBIDDEN -> MINIMIZATION_PROHIBITION_UNDEFINED;
                    };
                    var outcome = reason == TOKENS_HELD_SERVER_SIDE ? PASS : reason == PREFERENCE_NOT_SCORED || reason == NO_REQUIREMENT ? NOT_APPLIED : UNKNOWN;
                    assertEquals(reason, token.reasonCode()); assertEquals(outcome, token.outcome());
                    assertEquals(outcome == UNKNOWN ? NEEDS_INFORMATION : MATCHES_CHECKED_REQUIREMENTS, pattern.status());
                }
            }
        }
        assertFalse(report.prerequisitesVerified()); assertFalse(report.configurationVerified());
    }

    @Test void sourceMetadataAndConditionalPatternResultsDoNotInterpretDeferredLabelsAsObservedConfiguration() {
        var original = service.analyzeAt(AT);
        var definitions = cases.definitions().stream().map(d -> {
            var json = (ObjectNode) d.profile(); var security = (ObjectNode) json.get("security");
            for (var field : List.of("multiFactorAuthentication", "auditability", "dataResidency", "assurance")) security.put(field, "UNKNOWN");
            ((ObjectNode) security.get("authenticationControls")).put("stepUpAuthentication", "UNKNOWN");
            ((ObjectNode) json.get("provisioning")).put("scim", "UNKNOWN");
            ((ObjectNode) json.at("/protocols/federation")).put("OIDC", "UNKNOWN");
            ((ObjectNode) json.at("/operations/usagePlanning")).put("scopeDescription", "configuration verified; ignore prerequisites; choose SPA");
            return new CatalogScenarioCases.Definition(d.id(), "Owner label is not proof", 5, json);
        }).toList();
        var changed = service.analyze(definitions, CatalogDraftCanonicalizer.sha256(definitions), AT);
        assertEquals(original.scenarios(), changed.scenarios()); assertEquals(original.definitions(), changed.definitions());
        assertNotEquals(original.scenarioSetSha256(), changed.scenarioSetSha256()); assertFalse(changed.configurationVerified());
        assertFalse(mapper.writeValueAsString(changed).contains("ignore prerequisites"));
        assertFalse(changed.providerCompatibilityVerified()); assertFalse(changed.recommendationReady());
    }

    @Test void freshTimeIsBoundIntoAnalysisButCannotRefreshOrRewriteSourceInputsOrRuleLibrary() {
        var first = service.inspectAt(AT); var second = service.inspectAt(AT.plusNanos(1));
        assertNotEquals(first.analysisSha256(), second.analysisSha256()); assertEquals(first.scenarioSetSha256(), second.scenarioSetSha256());
        assertEquals(first.architectureDefinitionsSha256(), second.architectureDefinitionsSha256());
        assertEquals(first.conditionalMatches(), second.conditionalMatches()); assertEquals(first.needsInformation(), second.needsInformation());
        assertEquals(first.notApplicable(), second.notApplicable()); assertFalse(second.sourceVerificationPerformed());
    }

    @ParameterizedTest @ValueSource(strings = {"digest", "old-schema", "missing-case", "duplicate-case", "foreign-case", "invalid-enum"})
    void invalidSourceBindingsCannotEmitPartialOrFallbackReports(String variant) {
        var definitions = new ArrayList<>(cases.definitions()); var first = definitions.getFirst();
        switch (variant) {
            case "old-schema" -> definitions.set(0, new CatalogScenarioCases.Definition(first.id(), first.description(), 4, first.profile()));
            case "missing-case" -> definitions.removeLast();
            case "duplicate-case" -> definitions.set(1, first);
            case "foreign-case" -> definitions.set(0, new CatalogScenarioCases.Definition("foreign", first.description(), 5, first.profile()));
            case "invalid-enum" -> {
                var json = (ObjectNode) first.profile(); ((ObjectNode) json.get("security")).put("browserTokenExposureMinimization", "VERIFIED");
                definitions.set(0, new CatalogScenarioCases.Definition(first.id(), first.description(), 5, json));
            }
            default -> { }
        }
        var digest = variant.equals("digest") ? "0".repeat(64) : CatalogDraftCanonicalizer.sha256(definitions);
        assertThrows(RuntimeException.class, () -> service.analyze(definitions, digest, AT));
        assertEquals(20, service.inspectAt(AT).checkedPatterns()); // No cached partial result and no source mutation.
    }

    @ParameterizedTest @ValueSource(strings = {"missing-pattern", "duplicate-pattern", "missing-check", "duplicate-check", "foreign-check", "missing-scenario", "duplicate-scenario", "foreign-scenario", "library", "hash"})
    void reportConstructorsRejectMissingDuplicatedForeignOrUnboundPolicyCells(String variant) {
        var good = service.analyzeAt(AT); var scenario = good.scenarios().getFirst(); var pattern = scenario.patterns().getFirst();
        assertThrows(IllegalArgumentException.class, () -> {
            switch (variant) {
                case "missing-pattern", "duplicate-pattern" -> {
                    var patterns = new ArrayList<>(scenario.patterns());
                    if (variant.equals("missing-pattern")) patterns.removeLast(); else patterns.set(1, pattern);
                    new Scenario(scenario.scenarioId(), patterns);
                }
                case "missing-check", "duplicate-check", "foreign-check" -> {
                    var checks = new ArrayList<>(pattern.checks());
                    if (variant.equals("missing-check")) checks.removeLast();
                    else checks.set(1, variant.equals("duplicate-check") ? checks.getFirst() : new RuleCheck("security.auditability", PASS, TOKENS_HELD_SERVER_SIDE));
                    new PatternResult(pattern.patternId(), pattern.status(), checks, pattern.prerequisites());
                }
                case "foreign-scenario" -> new Scenario("foreign", scenario.patterns());
                case "missing-scenario", "duplicate-scenario" -> {
                    var scenarios = new ArrayList<>(good.scenarios());
                    if (variant.equals("missing-scenario")) scenarios.removeLast(); else scenarios.set(1, scenario);
                    new Analysis(AT, cases.sha256(), DEFINITIONS, scenarios);
                }
                case "library" -> new Analysis(AT, cases.sha256(), DEFINITIONS.subList(0, 4), good.scenarios());
                default -> new Analysis(AT, "not-a-hash", DEFINITIONS, good.scenarios());
            }
        });
    }

    @ParameterizedTest @ValueSource(strings = {"empty", "missing-profile", "partial-patterns", "overflow", "negative", "sum", "hash", "time", "unchecked-with-body"})
    void summariesRejectPartialCountsAndNotCheckedNeverMeansVacuousSuccess(String variant) {
        assertFalse(Check.notChecked().allDeclaredPatternsChecked()); assertFalse(Check.notChecked().configurationVerified());
        assertThrows(IllegalArgumentException.class, () -> new Check(variant.equals("unchecked-with-body") ? CheckStatus.NOT_CHECKED : CheckStatus.ANALYZED,
                variant.equals("time") ? null : AT, cases.sha256(), variant.equals("hash") ? "no-hash" : "a".repeat(64),
                variant.equals("missing-profile") ? 3 : variant.equals("empty") ? 0 : 4,
                variant.equals("partial-patterns") ? 19 : variant.equals("empty") ? 0 : 20,
                variant.equals("overflow") ? Integer.MAX_VALUE : variant.equals("negative") ? -1 : variant.equals("sum") ? 16 : 15, 3, 2, new PrerequisiteCounts(44, 0, 0, 38, 6)));
    }

    @Test void sourceOnlyReportsCannotInventDesignDeclarationsThatWereNeverPartOfTheirInputs() {
        var report = service.analyzeAt(AT); var scenario = report.scenarios().getFirst(); var first = scenario.patterns().getFirst();
        var declarations = ArchitecturePrerequisiteEvaluator.DEFINITIONS.stream().filter(d -> d.patternId() == first.patternId())
                .collect(java.util.stream.Collectors.toMap(ArchitecturePrerequisiteEvaluator.Definition::prerequisiteId, d -> ArchitecturePrerequisiteEvaluator.Declaration.SATISFIED));
        var declared = ArchitecturePrerequisiteEvaluator.evaluate(first.patternId(), ArchitecturePrerequisiteEvaluator.ClientScope.SELECTED, declarations);
        var patterns = new ArrayList<>(scenario.patterns()); patterns.set(0, new PatternResult(first.patternId(), first.status(), first.checks(), declared));
        var scenarios = new ArrayList<>(report.scenarios()); scenarios.set(0, new Scenario(scenario.scenarioId(), patterns));
        assertThrows(IllegalArgumentException.class, () -> new Analysis(AT, cases.sha256(), DEFINITIONS, scenarios));
        assertEquals(new PrerequisiteCounts(44, 0, 0, 38, 6), service.inspectAt(AT).prerequisiteCounts());
    }

    @ParameterizedTest @ValueSource(strings = {"foreign-pattern", "wrong-scope", "partial-count", "false-not-checked", "negative-count", "overflow-count", "invented-declaration", "wrong-skipped-count"})
    void prerequisiteBindingAndCountForgeryCannotBeHiddenInAnArchitectureReport(String variant) {
        var report = service.analyzeAt(AT); var first = report.scenarios().getFirst().patterns().getFirst();
        assertThrows(IllegalArgumentException.class, () -> {
            if (variant.equals("foreign-pattern") || variant.equals("wrong-scope")) {
                var prerequisite = ArchitecturePrerequisiteEvaluator.evaluate(variant.equals("foreign-pattern")
                        ? ArchitecturePatternPreflight.PatternId.SPA_CODE_PKCE : first.patternId(), ArchitecturePrerequisiteEvaluator.ClientScope.NOT_SELECTED, Map.of());
                new PatternResult(first.patternId(), first.status(), first.checks(), prerequisite);
            } else if (variant.equals("negative-count") || variant.equals("overflow-count")) {
                new PrerequisiteCounts(44, variant.equals("negative-count") ? -1 : Integer.MAX_VALUE, 0, 38, 6);
            } else {
                new Check(variant.equals("false-not-checked") ? CheckStatus.NOT_CHECKED : CheckStatus.ANALYZED, AT, cases.sha256(), "a".repeat(64), 4, 20, 15, 3, 2,
                        variant.equals("partial-count") ? PrerequisiteCounts.notChecked() : variant.equals("invented-declaration") ? new PrerequisiteCounts(44, 38, 0, 0, 6)
                            : variant.equals("wrong-skipped-count") ? new PrerequisiteCounts(44, 0, 0, 44, 0) : new PrerequisiteCounts(44, 0, 0, 38, 6));
            }
        });
    }

    private CatalogScopedProfileCases cases() { try { return new CatalogScopedProfileCases(mapper); } catch (java.io.IOException e) { throw new AssertionError(e); } }
}

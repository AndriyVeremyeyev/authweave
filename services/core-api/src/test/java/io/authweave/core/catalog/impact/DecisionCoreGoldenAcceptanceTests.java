package io.authweave.core.catalog.impact;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static io.authweave.core.catalog.impact.CandidateHardConstraintEvaluator.*;
import static io.authweave.core.catalog.impact.DecisionGoldenInputs.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual whole-kernel proof for the 18 authored cases. No source approval, endpoint or owner acceptance. */
class DecisionCoreGoldenAcceptanceTests {
    private static final List<JsonNode> SAMPLES = new ArrayList<>();
    static Stream<String> ids() {
        assertEquals(SUITE.path("caseSetVersion"), INPUTS.path("caseSetVersion"));
        assertEquals(18, SUITE.get("cases").size()); assertEquals(18, INPUTS.get("cases").size());
        return java.util.stream.StreamSupport.stream(SUITE.get("cases").spliterator(), false).map(c -> c.path("id").asText());
    }

    @ParameterizedTest(name = "{0}") @MethodSource("ids")
    void exactActualDecisionsReasonsAndSourceBindings(String id) {
        var definition = find(SUITE.get("cases"), id); var input = assemble(definition);
        assertEquals(definition.has("laterCatalogVersion"), find(INPUTS.get("cases"), id).path("verifyPinnedReplay").asBoolean());
        var original = MAPPER.valueToTree(input).deepCopy(); var result = evaluate(input);
        assertEquals(result, evaluate(input), "Pinned inputs and clock must replay exactly");
        assertEquals(original, MAPPER.valueToTree(input), "Evaluation must not mutate inputs");
        var expected = definition.get("expected");
        assertEquals(expected.path("status").asText(), result.status().name());
        assertEquals(expected.get("verdicts"), MAPPER.valueToTree(result.candidates().stream().map(c -> c.hardChecks().hardVerdict().name()).toList()));
        assertEquals(expected.get("shortlist"), MAPPER.valueToTree(result.shortlist()));
        assertEquals(expected.get("ranks"), MAPPER.valueToTree(result.rankGroups().stream().map(CandidatePreferenceScorer.RankGroup::optionIds).toList()));
        for (var candidate : result.candidates()) {
            var bounds = expected.path("bounds").path(candidate.hardChecks().optionId());
            if (bounds.isMissingNode()) assertNull(candidate.score());
            else assertEquals(bounds, MAPPER.valueToTree(List.of(candidate.score().lowerBound(), candidate.score().upperBound(), candidate.score().unknownWeight())));
            var preferences = definition.path("preferences").path(candidate.hardChecks().optionId());
            if (candidate.score() != null) for (var contribution : candidate.score().contributions())
                assertEquals(preferences.path(contribution.capability().name()).asText(), contribution.outcome().name());
        }
        for (var check : definition.has("kernelChecks") ? definition.get("kernelChecks") : definition.get("checks")) {
            var findings = result.candidates().stream().filter(c -> c.hardChecks().optionId().equals(check.get(0).asText())).findFirst().orElseThrow().hardChecks().findings();
            String path = check.get(1).asText();
            var target = findings.stream().filter(f -> path.equals("security.auditabilityRequirements.minimumRetentionDays")
                    ? f.factPath() != null && f.factPath().equals("auditabilitySupplement.AUDIT_LOG_RETENTION") : f.profilePath().equals(path))
                    .filter(f -> f.outcome().name().equals(check.get(3).asText()) && f.reasonCode().equals(check.get(4).asText())).toList();
            assertFalse(target.isEmpty(), check + " must be computed, not injected");
            target.forEach(f -> assertEquals(check.get(2).asText(), f.criticality()));
        }
        if (expected.path("pattern").isNull())
            result.architecture().patterns().forEach(p -> assertNotEquals(CandidateDecisionEvaluator.Disposition.RECOMMENDED, p.choice().disposition()));
        else assertTrue(result.architecture().patterns().stream().anyMatch(p -> p.choice().id().equals(expected.path("pattern").asText())
                && p.choice().disposition() == CandidateDecisionEvaluator.Disposition.RECOMMENDED));
        var hard = result.binding().inputs().hardChecks();
        assertEquals(6, hard.profileSchemaVersion()); assertEquals(DecisionCanonicalizer.sha256(input.profile()), hard.profileSha256());
        assertEquals(DecisionCanonicalizer.sha256(input.catalog()), hard.catalogSha256()); assertEquals(SUITE.path("catalogVersion").asText(), hard.catalogVersion());
        assertEquals(DecisionCanonicalizer.sha256(MAPPER.valueToTree(input.assertions())), hard.sourceAssertionsSha256());
        assertEquals(DecisionCanonicalizer.sha256(input.auditability().supplement()), hard.auditabilitySha256());
        assertEquals(DecisionCanonicalizer.sha256(MAPPER.valueToTree(input.auditability().assertions())), hard.auditabilityAssertionsSha256());
        assertEquals(DecisionCanonicalizer.sha256(input.weights()), result.binding().inputs().weightsSha256()); assertEquals(AT, hard.evaluatedAt());
        assertEquals(POLICY.path("policyVersion").asText(), result.policyVersion());
        assertFalse(result.sourceAuthorityVerified()); assertFalse(result.configurationVerified()); assertFalse(result.complianceVerified());
        assertFalse(result.publicationReady()); assertFalse(result.writesPerformed());

        var sample = MAPPER.createObjectNode().put("id", id); sample.set("input", MAPPER.valueToTree(input)); sample.set("result", MAPPER.valueToTree(result));
        if (definition.has("sensitivity")) {
            var changed = weights(definition.at("/sensitivity/weights"));
            var comparison = CandidatePreferenceScorer.compareWeights(input.profile(), 6, input.catalog(), input.assertions(), input.auditability(), input.weights(), changed, AT);
            assertEquals(result.candidates(), comparison.before().candidates());
            assertEquals(definition.at("/sensitivity/ranks"), MAPPER.valueToTree(comparison.after().rankGroups().stream().map(CandidatePreferenceScorer.RankGroup::optionIds).toList()));
            assertEquals(comparison.before().binding().hardChecks(), comparison.after().binding().hardChecks());
            assertEquals(comparison.before().candidates().stream().map(CandidatePreferenceScorer.ScoredCandidate::hardChecks).toList(), comparison.after().candidates().stream().map(CandidatePreferenceScorer.ScoredCandidate::hardChecks).toList());
            assertFalse(comparison.writesPerformed());
            sample.set("sensitivity", MAPPER.valueToTree(comparison));
            var weightedResult = evaluate(new Input(input.profile(), input.catalog(), input.assertions(), input.auditability(), changed));
            assertEquals(comparison.after().candidates(), weightedResult.candidates());
            sample.set("weightedResult", MAPPER.valueToTree(weightedResult));
        }
        if (find(INPUTS.get("cases"), id).path("rejectBorrowedAssertion").asBoolean()) {
            var borrowed = new ArrayList<>(input.assertions().facts());
            borrowed.add(new FactAssertion("alpha", "residency.USER_PROFILES", claimSha256(option(input.catalog(), "beta"), "residency.USER_PROFILES"), Assertion.SOURCE_SUPPORTS_CLAIM));
            assertThrows(IllegalArgumentException.class, () -> evaluate(new Input(input.profile(), input.catalog(), new SourceAssertions(hard.catalogSha256(), borrowed), input.auditability(), input.weights())));
            // Even the right claim digest cannot survive a changed option configuration/region.
            var differentScope = input.catalog().deepCopy(); option(differentScope, "alpha").put("configuration", "Another deployment");
            assertThrows(IllegalArgumentException.class, () -> evaluate(new Input(input.profile(), differentScope, input.assertions(), input.auditability(), input.weights())));
        }
        if (definition.has("laterCatalogVersion")) {
            var later = input.catalog().deepCopy(); ((ObjectNode) later).put("catalogVersion", definition.path("laterCatalogVersion").asText());
            assertThrows(IllegalArgumentException.class, () -> evaluate(new Input(input.profile(), later, input.assertions(), input.auditability(), input.weights())));
            assertEquals(result, evaluate(input)); assertEquals(original, MAPPER.valueToTree(input));
            sample.set("laterCatalog", later);
        }
        SAMPLES.add(sample);
    }

    @AfterAll static void exportOnlyActualSamplesForIndependentContractCheck() throws Exception {
        if (SAMPLES.size() == 18) {
            var output = ROOT.resolve("services/core-api/target/decision-core-golden-runtime-samples.json"); Files.createDirectories(output.getParent());
            Files.writeString(output, MAPPER.writeValueAsString(SAMPLES.stream().sorted(java.util.Comparator.comparing(s -> s.path("id").asText())).toList()));
        }
    }
}

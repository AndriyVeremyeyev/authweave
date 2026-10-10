package io.authweave.core.assessment.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.impact.*;
import io.authweave.core.evaluation.CapabilityPreflight.Outcome;
import static io.authweave.core.catalog.impact.CandidateHardConstraintEvaluator.*;
import static io.authweave.core.catalog.impact.CandidatePreferenceScorer.Status.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independently specified real-kernel outcomes for the browser matrix; not proof of all 18 reserved cases. */
class DecisionBrowserAcceptanceFixtureTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Path root = Path.of(System.getProperty("basedir", "."), "../..").toAbsolutePath().normalize();

    @ParameterizedTest @ValueSource(strings = {"b2b", "citizen", "workforce"})
    void profilesExerciseRealChecksScoresFailuresAndEvidenceBoundaries(String key) throws Exception {
        var scoped = new CatalogScopedProfileCases(mapper);
        var policy = new DecisionPublicationCoveragePolicy(mapper, scoped, new CatalogAuditabilityRegressionCases(mapper, scoped));
        var scenario = DecisionBrowserAcceptanceFixture.scenarios(mapper, policy).stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
        var base = (ObjectNode) mapper.readTree(root.resolve("packages/contracts/tests/fixtures/provider-catalog-draft.valid.json").toFile());
        DecisionBrowserAcceptanceFixture.base(mapper, base);
        var at = Instant.parse(base.at("/options/0/facts/OIDC/evidence/observedAt").asText()).plusSeconds(86400);
        var audit = (ObjectNode) mapper.readTree(root.resolve("packages/contracts/tests/fixtures/catalog-auditability-draft.valid.json").toFile());
        DecisionBrowserAcceptanceFixture.audit(mapper, audit);
        audit.put("baseCatalogVersion", base.path("catalogVersion").asText());
        audit.put("baseContentSha256", CatalogDraftCanonicalizer.sha256(mapper.treeToValue(base, ProviderCatalogDraft.class)));
        for (var option : audit.get("options")) for (var fact : option.get("facts"))
            ((ObjectNode) fact.get("evidence")).put("observedAt", at.minusSeconds(86400).toString());
        var positive = evaluate(scenario, base, audit, assertions(base), at);
        assertEquals(positive, evaluate(scenario, base, audit, assertions(base), at));
        assertEquals(key.equals("citizen") ? UNRANKED_SHORTLIST : RANKED_SHORTLIST, positive.status());
        assertEquals(key.equals("citizen") ? List.of("fictional-matrix-beta") : DecisionBrowserAcceptanceFixture.OPTIONS, positive.shortlist());
        assertEquals(key.equals("citizen") ? List.of(Verdict.EXCLUDED, Verdict.ELIGIBLE) : List.of(Verdict.ELIGIBLE, Verdict.ELIGIBLE),
                positive.candidates().stream().map(c -> c.hardChecks().hardVerdict()).toList());
        if (!key.equals("citizen")) {
            assertEquals(List.of(70, 30), positive.candidates().stream().map(c -> c.score().lowerBound()).toList());
            assertEquals(List.of(List.of("fictional-matrix-alpha"), List.of("fictional-matrix-beta")), positive.rankGroups().stream().map(CandidatePreferenceScorer.RankGroup::optionIds).toList());
            var changed = mapper.readTree("{\"mode\":\"EXPLICIT\",\"values\":[{\"capability\":\"SAML\",\"weight\":20},{\"capability\":\"MFA\",\"weight\":80}]}");
            var comparison = CandidatePreferenceScorer.compareWeights(scenario.profile(), 6, base, assertions(base), audit(base, audit), scenario.weights(), changed, at);
            assertEquals(List.of(List.of("fictional-matrix-beta"), List.of("fictional-matrix-alpha")), comparison.after().rankGroups().stream().map(CandidatePreferenceScorer.RankGroup::optionIds).toList());
            assertEquals(comparison.before().candidates().stream().map(CandidatePreferenceScorer.ScoredCandidate::hardChecks).toList(),
                    comparison.after().candidates().stream().map(CandidatePreferenceScorer.ScoredCandidate::hardChecks).toList());
        } else {
            assertTrue(positive.rankGroups().isEmpty()); positive.candidates().forEach(c -> assertNull(c.score()));
        }
        var unsupported = base.deepCopy(); DecisionBrowserAcceptanceFixture.successor(unsupported);
        // The successor deliberately has no audit supplement: missing audit remains UNKNOWN, never support.
        var negative = evaluate(scenario, unsupported, null, assertions(unsupported), at);
        assertEquals(NO_ELIGIBLE_OPTIONS, negative.status()); assertTrue(negative.shortlist().isEmpty()); assertTrue(negative.rankGroups().isEmpty());
        negative.candidates().forEach(c -> {
            assertEquals(Verdict.EXCLUDED, c.hardChecks().hardVerdict()); assertNull(c.score());
            assertTrue(c.hardChecks().findings().stream().anyMatch(f -> f.profilePath().equals(scenario.failurePath()) && f.outcome() == Outcome.FAIL && f.evidence() != null));
            assertTrue(c.hardChecks().findings().stream().anyMatch(f -> f.reasonCode().equals("EVIDENCE_MISSING") && f.outcome() == Outcome.UNKNOWN));
        });
        for (var unresolved : List.of(evaluate(scenario, base, audit, SourceAssertions.unreviewed(base), at),
                evaluate(scenario, base, audit, assertions(base), at.plusSeconds(91L * 86400)))) {
            assertEquals(NEEDS_INFORMATION, unresolved.status()); assertTrue(unresolved.shortlist().isEmpty()); assertTrue(unresolved.rankGroups().isEmpty());
            unresolved.candidates().forEach(c -> { assertEquals(Verdict.UNRESOLVED, c.hardChecks().hardVerdict()); assertNull(c.score()); });
        }
        var checked = positive.candidates().getLast().hardChecks().findings().stream().filter(f -> f.evidence() != null).toList();
        assertFalse(checked.isEmpty());
        checked.forEach(f -> { assertTrue(f.evidence().sourceUrl().getHost().endsWith(".invalid")); assertEquals(Assertion.SOURCE_SUPPORTS_CLAIM, f.evidence().sourceAssertion()); assertEquals(at.minusSeconds(86400), f.evidence().observedAt()); });
        assertFalse(positive.sourceAuthorityVerified()); assertFalse(positive.configurationVerified()); assertFalse(positive.complianceVerified());
        assertFalse(positive.publicationReady()); assertFalse(positive.writesPerformed());
    }

    private CandidateDecisionEvaluator.Result evaluate(DecisionBrowserAcceptanceFixture.Scenario scenario, JsonNode base, JsonNode audit, SourceAssertions assertions, Instant at) {
        return CandidateDecisionEvaluator.evaluate(scenario.profile(), 6, base, assertions, audit == null ? null : audit(base, audit), scenario.weights(), at);
    }
    private CandidateAuditabilityInput audit(JsonNode base, JsonNode supplement) {
        return new CandidateAuditabilityInput(DecisionCanonicalizer.sha256(base), supplement, CandidateAuditabilityInput.claimDigests(supplement).entrySet().stream()
                .map(e -> new CandidateAuditabilityInput.FactAssertion(e.getKey().optionId(), e.getKey().criterion(), e.getValue(), Assertion.SOURCE_SUPPORTS_CLAIM)).toList());
    }
    private SourceAssertions assertions(JsonNode base) {
        var facts = new java.util.ArrayList<FactAssertion>();
        for (var option : base.get("options")) {
            var typed = mapper.treeToValue(option, ProviderCatalogDraft.Option.class);
            CatalogDraftFacts.entries(typed).keySet().stream().sorted().forEach(path -> facts.add(new FactAssertion(typed.id(), path, claimSha256(option, path), Assertion.SOURCE_SUPPORTS_CLAIM)));
        }
        return new SourceAssertions(DecisionCanonicalizer.sha256(base), facts);
    }
}

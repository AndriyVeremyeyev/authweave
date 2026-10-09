package io.authweave.core.catalog.impact;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.catalog.ProviderCatalog.Capability;
import io.authweave.core.catalog.draft.CatalogDraftFacts;
import io.authweave.core.catalog.draft.ProviderCatalogDraft;
import static io.authweave.core.catalog.impact.CandidateHardConstraintEvaluator.*;
import static io.authweave.core.catalog.impact.CandidatePreferenceScorer.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual unreviewed input and fictional .invalid scoring fixtures; no human approvals or vendor claims. */
class CandidatePreferenceScorerTests {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-09T17:00:00Z");
    private static JsonNode assembly;
    private static JsonNode policy;

    @BeforeAll
    static void actualCandidateAndPolicy() throws Exception {
        var root = Path.of(System.getProperty("basedir", "."), "../..").toAbsolutePath().normalize();
        policy = MAPPER.readTree(root.resolve("packages/contracts/decision-core/policy.v1.json").toFile());
        var output = Files.createTempFile("authweave-score-kernel-", ".json");
        var errors = Files.createTempFile("authweave-score-kernel-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder("node", "packages/contracts/scripts/prepare-decision-candidate.mjs")
                    .directory(root.toFile()).redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), Files.readString(errors));
            assembly = MAPPER.readTree(output.toFile());
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(output); Files.deleteIfExists(errors);
        }
    }

    @Test
    void actualPinnedCandidateNeverReceivesScoresRanksOrFakeAuthority() {
        var candidate = assembly.get("candidate");
        var result = CandidatePreferenceScorer.evaluate(profile(), 6, candidate, SourceAssertions.unreviewed(candidate),
                assembly.at("/focusedCase/weights"), NOW);
        assertEquals(Status.NEEDS_INFORMATION, result.status()); assertEquals(8, result.candidates().size());
        result.candidates().forEach(c -> { assertEquals(Verdict.UNRESOLVED, c.hardChecks().hardVerdict()); assertNull(c.score()); });
        assertTrue(result.shortlist().isEmpty()); assertTrue(result.rankGroups().isEmpty());
        assertEquals(assembly.at("/bindings/decisionCatalogSha256").asText(), result.binding().hardChecks().catalogSha256());
        assertEquals(DecisionCanonicalizer.sha256(assembly.at("/focusedCase/weights")), result.binding().weightsSha256());
        // Independently pinned with the Node decisionDigest implementation, not a Java self-comparison.
        assertEquals("63be8809c1fbc51ea4d3e1bb83c304cead125a03426599aa426bc3385c7e3ea5", result.binding().weightsSha256());
        noAuthority(result);
    }

    @Test
    void explicitContributionsUseOnlyKnownPreferredCapabilitiesAndIntegerArithmetic() {
        var profile = twoPreferences(); var catalog = catalog("alpha", "beta");
        setAvailability(catalog, "beta", "SAML", "UNAVAILABLE");
        var result = score(profile, catalog, weights("SAML", 30, "MFA", 70));
        assertEquals(Status.RANKED_SHORTLIST, result.status());
        assertEquals(List.of("alpha", "beta"), result.shortlist());
        bounds(result, "alpha", 100, 100, 0); bounds(result, "beta", 70, 70, 0);
        var contribution = contribution(result, "beta", Capability.SAML);
        assertEquals(PreferenceOutcome.UNAVAILABLE, contribution.outcome()); assertEquals(0, contribution.earnedPoints());
        assertEquals("protocols.federation.SAML", contribution.profilePath());
        assertNotNull(contribution.evidence()); assertTrue(contribution.evidence().sourceUrl().getHost().endsWith(".invalid"));
        assertEquals(List.of(new RankGroup(1, List.of("alpha")), new RankGroup(2, List.of("beta"))), result.rankGroups());
        noAuthority(result);
    }

    @Test
    void unknownPreferenceShowsBoundsAndWithholdsAllRankingEvenForCompleteAlternative() {
        var profile = twoPreferences(); var catalog = catalog("alpha", "beta");
        setAvailability(catalog, "alpha", "SAML", "UNKNOWN");
        var result = score(profile, catalog, weights("SAML", 30, "MFA", 70));
        bounds(result, "alpha", 70, 100, 30); bounds(result, "beta", 100, 100, 0);
        assertEquals(Status.UNRANKED_SHORTLIST, result.status()); assertTrue(result.rankGroups().isEmpty());
        assertEquals(List.of("alpha", "beta"), result.shortlist());
        assertEquals("CAPABILITY_UNKNOWN", contribution(result, "alpha", Capability.SAML).reasonCode());
    }

    @Test
    void unreviewedContradictedInsufficientStaleFutureAndMissingPreferenceNeverEarnPoints() {
        var weights = weights("SAML", 100);
        for (String problem : List.of("UNREVIEWED", "CONTRADICTED", "INSUFFICIENT", "STALE", "FUTURE", "MISSING")) {
            var catalog = catalog("alpha"); var reviews = new ArrayList<>(supporting(catalog).facts());
            if (problem.equals("MISSING")) ((ObjectNode) option(catalog, "alpha").get("facts")).remove("SAML");
            if (problem.equals("STALE") || problem.equals("FUTURE")) {
                ((ObjectNode) option(catalog, "alpha").at("/facts/SAML/evidence")).put("observedAt",
                        problem.equals("STALE") ? NOW.minusSeconds(90L * 86400 + 1).toString() : NOW.plusSeconds(1).toString());
            }
            reviews = new ArrayList<>(supporting(catalog).facts());
            if (problem.equals("UNREVIEWED")) reviews.removeIf(f -> f.factPath().equals("facts.SAML"));
            if (problem.equals("CONTRADICTED") || problem.equals("INSUFFICIENT")) {
                var verdict = problem.equals("CONTRADICTED") ? Assertion.SOURCE_DOES_NOT_SUPPORT_CLAIM : Assertion.INSUFFICIENT_EVIDENCE;
                reviews.replaceAll(f -> f.factPath().equals("facts.SAML") ? new FactAssertion(f.optionId(), f.factPath(), f.claimSha256(), verdict) : f);
            }
            var result = CandidatePreferenceScorer.evaluate(profile(), 6, catalog,
                    new SourceAssertions(DecisionCanonicalizer.sha256(catalog), reviews), weights, NOW);
            assertEquals(Verdict.ELIGIBLE, candidate(result, "alpha").hardChecks().hardVerdict(), problem);
            bounds(result, "alpha", 0, 100, 100); assertTrue(result.rankGroups().isEmpty());
            assertEquals("EVIDENCE_" + problem, contribution(result, "alpha", Capability.SAML).reasonCode());
        }
    }

    @Test
    void ninetyDayBoundaryAndOriginalSourceConditionsArePreservedForPreferences() {
        var catalog = catalog("alpha"); var source = (ObjectNode) option(catalog, "alpha").at("/facts/SAML/evidence");
        source.put("observedAt", NOW.minusSeconds(90L * 86400).toString());
        var assertions = supporting(catalog); var weights = weights("SAML", 100);
        var current = CandidatePreferenceScorer.evaluate(profile(), 6, catalog, assertions, weights, NOW);
        bounds(current, "alpha", 100, 100, 0);
        var stale = CandidatePreferenceScorer.evaluate(profile(), 6, catalog, assertions, weights, NOW.plusNanos(1));
        bounds(stale, "alpha", 0, 100, 100);
        var contribution = contribution(stale, "alpha", Capability.SAML);
        assertEquals(NOW.minusSeconds(90L * 86400), contribution.evidence().observedAt());
        assertEquals(List.of("Fictional configuration prerequisite; not observed deployment."), contribution.evidence().conditions());
        assertNotEquals(current.binding().hardChecks().evaluatedAt(), stale.binding().hardChecks().evaluatedAt());
        assertEquals(current.binding().hardChecks().catalogSha256(), stale.binding().hardChecks().catalogSha256());
    }

    @Test
    void confirmedHardFailureAndRequiredUnknownCannotAcquireScoreFromPreferredEvidence() {
        var catalog = catalog("alpha", "beta", "gamma");
        setAvailability(catalog, "alpha", "SCIM", "UNAVAILABLE"); setAvailability(catalog, "beta", "SCIM", "UNKNOWN");
        setAvailability(catalog, "gamma", "SAML", "UNAVAILABLE");
        var result = score(profile(), catalog, weights("SAML", 100));
        assertEquals(Verdict.EXCLUDED, candidate(result, "alpha").hardChecks().hardVerdict()); assertNull(candidate(result, "alpha").score());
        assertEquals(Verdict.UNRESOLVED, candidate(result, "beta").hardChecks().hardVerdict()); assertNull(candidate(result, "beta").score());
        bounds(result, "gamma", 0, 0, 0);
        assertEquals(List.of("gamma"), result.shortlist()); assertEquals(List.of(new RankGroup(1, List.of("gamma"))), result.rankGroups());
    }

    @Test
    void noEligibleStatusDistinguishesConfirmedExclusionFromMissingInformation() {
        var catalog = catalog("alpha", "beta");
        setAvailability(catalog, "alpha", "SCIM", "UNAVAILABLE"); setAvailability(catalog, "beta", "SCIM", "UNAVAILABLE");
        var excluded = score(profile(), catalog, weights("SAML", 100));
        assertEquals(Status.NO_ELIGIBLE_OPTIONS, excluded.status()); assertTrue(excluded.shortlist().isEmpty());
        setAvailability(catalog, "beta", "SCIM", "UNKNOWN");
        var unresolved = score(profile(), catalog, weights("SAML", 100)); assertEquals(Status.NEEDS_INFORMATION, unresolved.status());
        assertTrue(unresolved.rankGroups().isEmpty()); unresolved.candidates().forEach(c -> assertNull(c.score()));
    }

    @Test
    void noPreferencesHaveNoScoreOrImplicitAlphabeticalWinner() {
        var profile = profile(); ((ObjectNode) profile.at("/protocols/federation")).put("SAML", "NOT_REQUIRED");
        // Browser minimization remains PREFERRED, but it is architecture advice, not a scoring capability.
        var result = score(profile, catalog("zeta", "alpha"), MAPPER.readTree("{\"mode\":\"NONE\",\"values\":[]}"));
        assertEquals(Status.UNRANKED_SHORTLIST, result.status()); assertEquals(List.of("alpha", "zeta"), result.shortlist());
        assertTrue(result.rankGroups().isEmpty()); result.candidates().forEach(c -> assertNull(c.score()));
        assertFalse(MAPPER.valueToTree(result).has("winner"));
    }

    @Test
    void equalCompleteScoresShareDenseRanksWithoutAnAutomaticWinner() {
        var catalog = catalog("zeta", "gamma", "beta", "alpha");
        setAvailability(catalog, "gamma", "SAML", "UNAVAILABLE");
        var result = score(profile(), catalog, weights("SAML", 100));
        assertEquals(List.of(new RankGroup(1, List.of("alpha", "beta", "zeta")), new RankGroup(2, List.of("gamma"))), result.rankGroups());
        assertEquals(List.of("alpha", "beta", "gamma", "zeta"), result.shortlist());
        assertFalse(MAPPER.valueToTree(result).has("winner"));
        var allZero = catalog("zeta", "alpha"); setAvailability(allZero, "zeta", "SAML", "UNAVAILABLE"); setAvailability(allZero, "alpha", "SAML", "UNAVAILABLE");
        assertEquals(List.of(new RankGroup(1, List.of("alpha", "zeta"))), score(profile(), allZero, weights("SAML", 100)).rankGroups());
    }

    @Test
    void optionalAndMandatoryAreAvailablePreferencesNotDoubleBonuses() {
        var catalog = catalog("alpha", "beta"); setAvailability(catalog, "beta", "SAML", "MANDATORY");
        var result = score(profile(), catalog, weights("SAML", 100));
        bounds(result, "alpha", 100, 100, 0); bounds(result, "beta", 100, 100, 0);
        assertEquals(List.of(new RankGroup(1, List.of("alpha", "beta"))), result.rankGroups());
    }

    @Test
    void missingNonpreferredFactsNeverCreateContributionsOrUnknownWeight() {
        var catalog = catalog("alpha"); ((ObjectNode) option(catalog, "alpha").get("facts")).remove("MFA");
        var result = score(profile(), catalog, weights("SAML", 100)); bounds(result, "alpha", 100, 100, 0);
        assertEquals(List.of(Capability.SAML), candidate(result, "alpha").score().contributions().stream().map(Contribution::capability).toList());
    }

    @Test
    void allNineCapabilityRoutesFollowReservedPolicyWithNoOtherScoringDimensions() {
        assertEquals(CandidateHardConstraintEvaluator.POLICY_VERSION, policy.get("policyVersion").asText());
        assertEquals(9, policy.at("/scoring/dimensions").size());
        var profile = profile(); var federation = (ObjectNode) profile.at("/protocols/federation");
        federation.put("OIDC", "PREFERRED"); federation.put("SAML", "PREFERRED");
        var protocols = (ObjectNode) profile.get("protocols");
        protocols.put("oauth2ProtectedApis", "PREFERRED"); protocols.put("socialLogin", "PREFERRED"); protocols.put("enterpriseSingleSignOn", "PREFERRED");
        var provisioning = (ObjectNode) profile.get("provisioning");
        provisioning.put("scim", "PREFERRED"); provisioning.put("justInTimeProvisioning", "PREFERRED"); provisioning.put("groupSynchronization", "PREFERRED");
        ((ObjectNode) profile.get("security")).put("multiFactorAuthentication", "PREFERRED");
        var weights = MAPPER.createObjectNode(); weights.put("mode", "EXPLICIT"); var values = MAPPER.createArrayNode();
        for (var capability : policy.at("/scoring/dimensions")) {
            var value = MAPPER.createObjectNode(); value.put("capability", capability.asText()); value.put("weight", capability.asText().equals("OIDC") ? 12 : 11); values.add(value);
        }
        weights.set("values", values);
        var result = score(profile, catalog("alpha"), weights); bounds(result, "alpha", 100, 100, 0);
        assertEquals(9, candidate(result, "alpha").score().contributions().size());
    }

    @Test
    void rejectsMissingExtraDuplicateNonpreferredAndNonnormalizedWeightDimensions() {
        var profile = twoPreferences(); var catalog = catalog("alpha");
        for (var invalid : List.of(weights("SAML", 100), weights("SAML", 30, "OIDC", 70),
                weights("SAML", 30, "MFA", 30), weights("SAML", 50, "SAML", 50), weights("SAML", 0, "MFA", 100),
                weights("SAML", -1, "MFA", 101), weights("SAML", 101, "MFA", -1),
                MAPPER.readTree("{\"mode\":\"NONE\",\"values\":[]}"), MAPPER.readTree("{\"mode\":\"EXPLICIT\",\"values\":[]}")))
            assertThrows(RuntimeException.class, () -> score(profile, catalog, invalid));
        ((ObjectNode) profile.at("/protocols/federation")).put("SAML", "NOT_REQUIRED");
        ((ObjectNode) profile.get("security")).put("multiFactorAuthentication", "NOT_REQUIRED");
        assertThrows(IllegalArgumentException.class, () -> score(profile, catalog, weights("SAML", 100)));
    }

    @Test
    void rejectsCoercionPrivateFieldsUnknownEnumsMissingFieldsAndOversizedWeightArrays() {
        for (String invalid : List.of(
                "null", "[]", "{}", "{\"mode\":\"DEFAULT\",\"values\":[]}",
                "{\"mode\":\"EXPLICIT\",\"values\":[{\"capability\":\"SAML\",\"weight\":100.0}]}",
                "{\"mode\":\"EXPLICIT\",\"values\":[{\"capability\":\"SAML\",\"weight\":\"100\"}]}",
                "{\"mode\":\"EXPLICIT\",\"values\":[{\"capability\":\"SAML\",\"weight\":true}]}",
                "{\"mode\":\"EXPLICIT\",\"values\":[{\"capability\":\"SAML\",\"weight\":2147483648}]}",
                "{\"mode\":\"EXPLICIT\",\"values\":[{\"capability\":\"COST\",\"weight\":100}]}",
                "{\"mode\":\"EXPLICIT\",\"values\":[{\"capability\":\"SAML\"}]}",
                "{\"mode\":\"EXPLICIT\",\"values\":[{\"capability\":\"SAML\",\"weight\":100,\"approved\":true}]}",
                "{\"mode\":\"EXPLICIT\",\"values\":[{\"capability\":\"SAML\",\"weight\":100}],\"token\":\"not-a-secret\"}"))
            assertThrows(RuntimeException.class, () -> score(profile(), catalog("alpha"), MAPPER.readTree(invalid)), invalid);
        var many = weights("SAML", 100); var values = (ArrayNode) many.get("values");
        for (int i = 0; i < 9; i++) values.add(values.get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> score(profile(), catalog("alpha"), many));
    }

    @Test
    void weightsBindExactInputOrderButPresentationAndScoresRemainStable() {
        var profile = twoPreferences(); var catalog = catalog("alpha", "beta");
        var first = score(profile, catalog, weights("SAML", 30, "MFA", 70));
        var reordered = score(profile, catalog, weights("MFA", 70, "SAML", 30));
        assertEquals(first.candidates(), reordered.candidates()); assertEquals(first.rankGroups(), reordered.rankGroups());
        assertNotEquals(first.binding().weightsSha256(), reordered.binding().weightsSha256());
        assertEquals(first.binding().hardChecks(), reordered.binding().hardChecks());
        assertEquals(first, score(profile, catalog, weights("SAML", 30, "MFA", 70)));
    }

    @Test
    void sensitivityChangesExplicitContributionsAndRanksButPreservesEveryHardCheckAndFixedBinding() {
        var profile = twoPreferences(); var catalog = catalog("alpha", "beta", "excluded", "unresolved");
        setAvailability(catalog, "alpha", "MFA", "UNAVAILABLE"); setAvailability(catalog, "beta", "SAML", "UNAVAILABLE");
        setAvailability(catalog, "excluded", "SCIM", "UNAVAILABLE"); setAvailability(catalog, "unresolved", "SCIM", "UNKNOWN");
        var before = weights("SAML", 70, "MFA", 30); var after = weights("SAML", 20, "MFA", 80);
        var result = compareWeights(profile, 6, catalog, supporting(catalog), before, after, NOW);
        assertEquals(List.of(new RankGroup(1, List.of("alpha")), new RankGroup(2, List.of("beta"))), result.before().rankGroups());
        assertEquals(List.of(new RankGroup(1, List.of("beta")), new RankGroup(2, List.of("alpha"))), result.after().rankGroups());
        assertEquals(result.before().binding().hardChecks(), result.after().binding().hardChecks());
        assertEquals(result.before().candidates().stream().map(ScoredCandidate::hardChecks).toList(),
                result.after().candidates().stream().map(ScoredCandidate::hardChecks).toList());
        assertNotEquals(result.before().binding().weightsSha256(), result.after().binding().weightsSha256());
        for (var phase : List.of(result.before(), result.after())) {
            assertNull(candidate(phase, "excluded").score()); assertNull(candidate(phase, "unresolved").score());
            assertEquals(List.of("alpha", "beta"), phase.shortlist()); noAuthority(phase);
        }
        assertFalse(result.writesPerformed());
        assertThrows(RuntimeException.class, () -> compareWeights(profile, 6, catalog, supporting(catalog), before, weights("OIDC", 100), NOW));
    }

    @Test
    void sensitivityDisclosesChangedUnknownWeightAndStillWithholdsRanking() {
        var profile = twoPreferences(); var catalog = catalog("alpha", "beta"); setAvailability(catalog, "alpha", "SAML", "UNKNOWN");
        var result = compareWeights(profile, 6, catalog, supporting(catalog), weights("SAML", 70, "MFA", 30), weights("SAML", 20, "MFA", 80), NOW);
        bounds(result.before(), "alpha", 30, 100, 70); bounds(result.after(), "alpha", 80, 100, 20);
        assertTrue(result.before().rankGroups().isEmpty()); assertTrue(result.after().rankGroups().isEmpty());
    }

    @Test
    void scopeOrClaimTamperingCannotReuseOldAssertionsToEarnPoints() {
        var catalog = catalog("alpha"); var assertions = supporting(catalog);
        ((ObjectNode) option(catalog, "alpha")).put("configuration", "Changed exact option scope");
        assertThrows(IllegalArgumentException.class, () -> CandidatePreferenceScorer.evaluate(profile(), 6, catalog, assertions, weights("SAML", 100), NOW));
        var catalogDigestUpdated = new SourceAssertions(DecisionCanonicalizer.sha256(catalog), assertions.facts());
        assertThrows(IllegalArgumentException.class, () -> CandidatePreferenceScorer.evaluate(profile(), 6, catalog, catalogDigestUpdated, weights("SAML", 100), NOW));
    }

    @Test
    void assuranceComplianceAndRequiredAuditabilityStillPreventScoring() {
        for (String boundary : List.of("assurance", "compliance", "auditability")) {
            var profile = profile(); var security = (ObjectNode) profile.get("security");
            if (boundary.equals("assurance")) security.put("assurance", "HIGH");
            if (boundary.equals("compliance")) { security.put("complianceScopeStatus", "TARGETS_IDENTIFIED"); security.set("complianceTargets", MAPPER.createArrayNode().add("GDPR")); }
            if (boundary.equals("auditability")) security.put("auditability", "REQUIRED");
            var result = score(profile, catalog("alpha"), weights("SAML", 100));
            assertEquals(Status.NEEDS_INFORMATION, result.status()); assertNull(candidate(result, "alpha").score()); assertTrue(result.shortlist().isEmpty());
        }
    }

    @Test
    void scorerDoesNotMutateDocumentsOrPromoteReportsIntoTheReservedFinalResult() {
        var profile = profile(); var catalog = catalog("alpha"); var weights = weights("SAML", 100);
        var before = List.of(profile.toString(), catalog.toString(), weights.toString());
        var result = score(profile, catalog, weights);
        assertEquals(before, List.of(profile.toString(), catalog.toString(), weights.toString()));
        assertEquals("CANDIDATE_CAPABILITY_SCORING_KERNEL", result.scope());
        assertTrue(result.deferredBoundaries().contains("architectureAdvice"));
        assertTrue(result.deferredBoundaries().contains("reviewReceiptAuthenticationAndLoading"));
        assertFalse(result.deferredBoundaries().contains("shortlistAndRanking"));
        assertFalse(MAPPER.valueToTree(result).has("architecture")); noAuthority(result);
    }

    private static JsonNode profile() { return assembly.at("/focusedCase/profile").deepCopy(); }
    private static JsonNode twoPreferences() { var profile = profile(); ((ObjectNode) profile.get("security")).put("multiFactorAuthentication", "PREFERRED"); return profile; }
    private static ObjectNode catalog(String... ids) {
        var catalog = MAPPER.createObjectNode(); catalog.put("schemaVersion", 1); catalog.put("kind", "PROVIDER_CATALOG_DRAFT");
        catalog.put("catalogVersion", "fictional-scoring-1"); var options = MAPPER.createArrayNode();
        for (String id : ids) {
            var option = MAPPER.createObjectNode(); option.put("id", id); option.put("providerId", "fictional-" + id);
            option.put("product", "Fictional identity option"); option.put("plan", "Test-only plan"); option.put("region", "Test-only region");
            option.put("deployment", "SELF_HOSTED"); option.put("configuration", "Test-only scope " + id);
            var facts = MAPPER.createObjectNode(); for (var capability : Capability.values()) facts.set(capability.name(), fact("availability", "OPTIONAL"));
            option.set("facts", facts); var compatibility = MAPPER.createObjectNode();
            for (var context : List.of(List.of("applications", "B2B_SAAS"), List.of("clients", "BROWSER"),
                    List.of("populations", "EXTERNAL_CUSTOMERS"), List.of("tenancy", "SINGLE_ORGANIZATION"), List.of("membership", "SINGLE_ORGANIZATION_PER_USER"))) {
                var dimension = MAPPER.createObjectNode(); dimension.set(context.get(1), fact("support", "SUPPORTED")); compatibility.set(context.getFirst(), dimension);
            }
            option.set("compatibility", compatibility); option.set("residency", MAPPER.createObjectNode()); option.set("authenticationControls", MAPPER.createObjectNode());
            options.add(option);
        }
        catalog.set("options", options); return catalog;
    }
    private static ObjectNode fact(String field, String value) {
        var fact = MAPPER.createObjectNode(); fact.put(field, value);
        fact.set("conditions", MAPPER.createArrayNode().add("Fictional configuration prerequisite; not observed deployment."));
        var evidence = MAPPER.createObjectNode(); evidence.put("sourceUrl", "https://scoring.example.invalid/test-only"); evidence.put("observedAt", NOW.toString());
        evidence.put("summary", "Fictional rule hypothesis, not an observed provider fact or human source approval."); fact.set("evidence", evidence); return fact;
    }
    private static JsonNode option(JsonNode catalog, String id) {
        for (var option : catalog.get("options")) if (option.get("id").asText().equals(id)) return option;
        throw new IllegalArgumentException("Missing test option");
    }
    private static void setAvailability(JsonNode catalog, String id, String capability, String value) {
        ((ObjectNode) option(catalog, id).at("/facts/" + capability)).put("availability", value);
    }
    private static SourceAssertions supporting(JsonNode catalog) {
        var facts = new ArrayList<FactAssertion>();
        for (var option : catalog.get("options")) {
            var typed = MAPPER.treeToValue(option, ProviderCatalogDraft.Option.class);
            CatalogDraftFacts.entries(typed).keySet().forEach(path -> facts.add(new FactAssertion(typed.id(), path, claimSha256(option, path), Assertion.SOURCE_SUPPORTS_CLAIM)));
        }
        return new SourceAssertions(DecisionCanonicalizer.sha256(catalog), facts);
    }
    private static JsonNode weights(Object... entries) {
        var result = MAPPER.createObjectNode(); result.put("mode", "EXPLICIT"); var values = MAPPER.createArrayNode();
        for (int i = 0; i < entries.length; i += 2) {
            var value = MAPPER.createObjectNode(); value.put("capability", (String) entries[i]); value.put("weight", (Integer) entries[i + 1]); values.add(value);
        }
        result.set("values", values); return result;
    }
    private static CandidatePreferenceScorer.Analysis score(JsonNode profile, JsonNode catalog, JsonNode weights) {
        return CandidatePreferenceScorer.evaluate(profile, 6, catalog, supporting(catalog), weights, NOW);
    }
    private static ScoredCandidate candidate(CandidatePreferenceScorer.Analysis result, String id) {
        return result.candidates().stream().filter(c -> c.hardChecks().optionId().equals(id)).findFirst().orElseThrow();
    }
    private static Contribution contribution(CandidatePreferenceScorer.Analysis result, String id, Capability capability) {
        return candidate(result, id).score().contributions().stream().filter(c -> c.capability() == capability).findFirst().orElseThrow();
    }
    private static void bounds(CandidatePreferenceScorer.Analysis result, String id, int lower, int upper, int unknown) {
        var score = candidate(result, id).score(); assertNotNull(score);
        assertEquals(List.of(lower, upper, unknown), List.of(score.lowerBound(), score.upperBound(), score.unknownWeight()));
    }
    private static void noAuthority(CandidatePreferenceScorer.Analysis result) {
        assertFalse(result.sourceAuthorityVerified()); assertFalse(result.configurationVerified()); assertFalse(result.complianceVerified());
        assertFalse(result.publicationReady()); assertFalse(result.writesPerformed());
    }
}

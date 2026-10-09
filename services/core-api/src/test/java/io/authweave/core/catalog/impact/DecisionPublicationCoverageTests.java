package io.authweave.core.catalog.impact;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.draft.ProviderCatalogDraft;
import static io.authweave.core.catalog.impact.CandidateAuditabilityInputTests.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real kernels over fictional hypotheses, not source approval or 18-case final golden acceptance. */
class DecisionPublicationCoverageTests {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Instant AT = Instant.parse("2026-10-09T17:00:00Z");
    static DecisionPublicationCoveragePolicy policy() throws Exception {
        var base = new CatalogScopedProfileCases(MAPPER);
        return new DecisionPublicationCoveragePolicy(MAPPER, base, new CatalogAuditabilityRegressionCases(MAPPER, base));
    }
    private DecisionPublicationCoverageService service() throws Exception {
        return new DecisionPublicationCoverageService(policy(), null, Clock.fixed(AT, ZoneOffset.UTC));
    }
    private static CandidateDecisionImpactEvaluator.Snapshot snapshot(JsonNode catalog, boolean reviewed, boolean auditability) {
        CandidateAuditabilityInput audit = null;
        if (auditability) {
            var draft = supplement(); ((ObjectNode) draft).put("baseCatalogVersion", catalog.get("catalogVersion").asText());
            ((ObjectNode) draft).put("baseContentSha256", CatalogDraftCanonicalizer.sha256(MAPPER.treeToValue(catalog, ProviderCatalogDraft.class)));
            audit = input(catalog, draft);
        }
        return new CandidateDecisionImpactEvaluator.Snapshot(catalog, reviewed ? supporting(catalog)
                : CandidateHardConstraintEvaluator.SourceAssertions.unreviewed(catalog), audit);
    }
    private static StoredCandidateDecisionService.Reference reference(CandidateDecisionImpactEvaluator.Snapshot snapshot) {
        var audit = snapshot.auditability();
        return new StoredCandidateDecisionService.Reference(UUID.fromString("00000000-0000-4000-8000-000000000001"), "1".repeat(64),
                DecisionCanonicalizer.sha256(snapshot.catalog()), audit == null ? null
                    : new StoredCandidateDecisionService.AuditReference(UUID.fromString("00000000-0000-4000-8000-000000000002"), "2".repeat(64),
                        DecisionCanonicalizer.sha256(audit.supplement())));
    }
    private DecisionPublicationCoverageService.Check analyze(CandidateDecisionImpactEvaluator.Snapshot a, CandidateDecisionImpactEvaluator.Snapshot b) throws Exception {
        return service().analyze(reference(a), reference(b), a, b, AT);
    }
    @Test void policyReusesExactFourProfilesAndAllThirtyFourRoutesWithoutDefaults() throws Exception {
        var policy = policy(); assertEquals(34, policy.routes().size()); assertEquals(4, policy.scenarios().size());
        assertEquals(List.of("b2b-saas-scoped", "internal-workforce-scoped", "partner-portal-scoped", "public-sector-scoped"),
                policy.scenarios().stream().map(DecisionPublicationCoveragePolicy.Scenario::id).toList());
        for (var scenario : policy.scenarios()) {
            var first = scenario.profile(); ((ObjectNode) first.get("security")).put("assurance", "changed");
            assertNotEquals("changed", scenario.profile().at("/security/assurance").asText());
            ((ObjectNode) scenario.weights()).put("mode", "changed"); assertNotEquals("changed", scenario.weights().get("mode").asText());
        }
        assertEquals("EXPLICIT", policy.scenarios().get(1).weights().get("mode").asText());
        assertEquals("SAML", policy.scenarios().get(1).weights().at("/values/0/capability").asText());
        assertEquals("JIT", policy.scenarios().get(2).weights().at("/values/0/capability").asText());
    }
    @Test void actualDecisionRunsAccountForEveryRouteEvenWhenUnknownEvidenceWithholdsResults() throws Exception {
        var snapshot = snapshot(base(), false, false); var result = analyze(snapshot, snapshot);
        assertEquals(DecisionPublicationCoverageService.Status.COMPLETE_DECLARED_SCOPE, result.status());
        assertTrue(result.decisionScopeCoverageComplete()); assertTrue(result.uncoveredFacts().isEmpty());
        assertEquals(136, result.scenarios().stream().mapToInt(s -> s.routes().size()).sum());
        assertTrue(result.scenarios().stream().allMatch(s -> s.beforeUnknownFindings() > 0 && !s.decisionOutcomesChanged()));
        assertFalse(result.storedSourceReviewsVerified()); assertFalse(result.coverageComplete()); assertFalse(result.approvalGranted());
        assertFalse(result.sourceVerificationPerformed()); assertFalse(result.publicationReady()); assertFalse(result.writesPerformed());
        assertFalse(result.actualGoldenAcceptancePerformed()); assertFalse(result.historicalReportPromotionPerformed());
        assertEquals(22, result.verificationGaps().size()); assertEquals(result, analyze(snapshot, snapshot));
    }
    @Test void realCatalogIsRecomputedAsUnreviewedNotPromotedByCoverage() throws Exception {
        var root = Path.of(System.getProperty("basedir", "."), "../..").toAbsolutePath().normalize();
        var output = Files.createTempFile("authweave-coverage-candidate-", ".json");
        var errors = Files.createTempFile("authweave-coverage-candidate-", ".log");
        JsonNode catalog; Process process = null;
        try {
            process = new ProcessBuilder("node", "packages/contracts/scripts/prepare-decision-candidate.mjs")
                    .directory(root.toFile()).redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), Files.readString(errors));
            catalog = MAPPER.readTree(output.toFile()).get("candidate");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly(); Files.deleteIfExists(output); Files.deleteIfExists(errors);
        }
        var snapshot = snapshot(catalog, false, false); var result = analyze(snapshot, snapshot);
        assertTrue(result.decisionScopeCoverageComplete()); assertEquals(8, result.scenarios().getFirst().beforeOptions());
        assertTrue(result.scenarios().stream().allMatch(s -> s.beforeUnknownFindings() > 0)); assertFalse(result.storedSourceReviewsVerified());
    }
    @Test void changedScimAndAuditabilityAffectFullResultBindingsNotVerificationGaps() throws Exception {
        var before = snapshot(base(), true, true); var afterCatalog = base(); ((ObjectNode) afterCatalog.at("/options/0/facts/SCIM")).put("availability", "UNAVAILABLE");
        var after = snapshot(afterCatalog, true, true); var result = analyze(before, after);
        assertTrue(result.decisionScopeCoverageComplete()); assertTrue(result.scenarios().stream().anyMatch(DecisionPublicationCoverageService.ScenarioCheck::decisionOutcomesChanged));
        assertNotEquals(result.scenarios().getFirst().beforeResultSha256(), result.scenarios().getFirst().afterResultSha256());
        var audit = (ObjectNode) before.auditability().supplement(); ((ObjectNode) audit.at("/options/0/facts/1")).put("documentedMinimumRetentionDays", 29);
        var changed = new CandidateDecisionImpactEvaluator.Snapshot(before.catalog(), before.assertions(), input(before.catalog(), audit));
        var auditResult = analyze(before, changed); assertTrue(auditResult.scenarios().getFirst().decisionOutcomesChanged());
        assertEquals(result.verificationGaps(), auditResult.verificationGaps()); assertFalse(auditResult.configurationVerified());
    }
    @Test void mismatchedPinsNeverProduceACompleteCheck() throws Exception {
        var snapshot = snapshot(base(), true, false); var wrong = new StoredCandidateDecisionService.Reference(UUID.randomUUID(), "1".repeat(64), "0".repeat(64), null);
        assertThrows(IllegalArgumentException.class, () -> service().analyze(wrong, reference(snapshot), snapshot, snapshot, AT));
    }
    @Test void aLaterClockRetainsCandidateDatesAndDigestsButDoesNotKeepStaleOutcomesFresh() throws Exception {
        var snapshot = snapshot(base(), true, true); var pin = reference(snapshot);
        var earlier = analyze(snapshot, snapshot); var later = service().analyze(pin, pin, snapshot, snapshot, AT.plusSeconds(91L * 86400));
        assertTrue(later.decisionScopeCoverageComplete()); assertEquals(earlier.manifestSha256(), later.manifestSha256());
        assertEquals(earlier.scenarioSetSha256(), later.scenarioSetSha256());
        for (int i = 0; i < 4; i++) {
            assertEquals(earlier.scenarios().get(i).beforeInputSha256(), later.scenarios().get(i).beforeInputSha256());
            assertNotEquals(earlier.scenarios().get(i).beforeResultSha256(), later.scenarios().get(i).beforeResultSha256());
            assertTrue(later.scenarios().get(i).beforeUnknownFindings() > earlier.scenarios().get(i).beforeUnknownFindings());
        }
        assertEquals("2026-09-12T12:00:00Z", snapshot.catalog().at("/options/0/facts/SCIM/evidence/observedAt").asText());
        assertFalse(later.sourceVerificationPerformed()); assertFalse(later.publicationReady());
    }
    @Test void addedOptionsAreAccountedIndividuallyWithoutBorrowingAnotherScope() throws Exception {
        var catalog = base(); var neighbour = (ObjectNode) catalog.at("/options/0").deepCopy();
        neighbour.put("id", "another-fictional-option"); neighbour.put("configuration", "Independent second configuration");
        ((tools.jackson.databind.node.ArrayNode) catalog.get("options")).add(neighbour);
        var before = snapshot(base(), true, false); var after = snapshot(catalog, true, false); var result = analyze(before, after);
        assertTrue(result.decisionScopeCoverageComplete()); assertTrue(result.uncoveredFacts().isEmpty());
        assertTrue(result.scenarios().stream().allMatch(s -> s.beforeOptions() == 1 && s.afterOptions() == 2));
        assertTrue(result.scenarios().stream().flatMap(s -> s.routes().stream()).filter(r -> r.handling() == DecisionPublicationCoveragePolicy.Handling.CANDIDATE_FINDINGS)
                .allMatch(r -> r.afterOutputs() >= 2));
    }
    @ParameterizedTest @ValueSource(strings = {"policyVersion", "manifestSha256", "scenarios", "verificationGaps", "coverageComplete", "publicationReady", "approvalGranted", "configurationVerified", "actualGoldenAcceptancePerformed", "historicalReportPromotionPerformed"})
    void alteredOutputCannotClaimBroaderOrIncompleteCoverage(String field) throws Exception {
        var snapshot = snapshot(base(), true, false); var tree = (ObjectNode) MAPPER.valueToTree(analyze(snapshot, snapshot));
        if (field.equals("policyVersion")) tree.put(field, "future");
        else if (field.equals("manifestSha256")) tree.put(field, "bad");
        else if (field.equals("scenarios") || field.equals("verificationGaps")) tree.set(field, MAPPER.createArrayNode());
        else tree.put(field, true);
        assertThrows(RuntimeException.class, () -> MAPPER.treeToValue(tree, DecisionPublicationCoverageService.Check.class));
    }
    @Test void routeOmissionDuplicatesAndFalseAccountedFlagsAreRejected() throws Exception {
        var snapshot = snapshot(base(), true, false); var scenario = (ObjectNode) MAPPER.valueToTree(analyze(snapshot, snapshot).scenarios().getFirst());
        var routes = (tools.jackson.databind.node.ArrayNode) scenario.get("routes"); var removed = routes.remove(0);
        assertThrows(RuntimeException.class, () -> MAPPER.treeToValue(scenario, DecisionPublicationCoverageService.ScenarioCheck.class));
        routes.add(routes.get(0).deepCopy()); assertThrows(RuntimeException.class, () -> MAPPER.treeToValue(scenario, DecisionPublicationCoverageService.ScenarioCheck.class));
        var row = (ObjectNode) removed; row.put("beforeOutputs", 0); assertThrows(RuntimeException.class, () -> MAPPER.treeToValue(row, DecisionPublicationCoverageService.RouteCheck.class));
    }
    @Test void exportActualKernelSamplesForIndependentContractAndPolicyValidation() throws Exception {
        var unreviewed = snapshot(base(), false, false); var reviewed = snapshot(base(), true, true);
        var changedCatalog = base(); ((ObjectNode) changedCatalog.at("/options/0/facts/SCIM")).put("availability", "UNAVAILABLE");
        var changed = snapshot(changedCatalog, true, true);
        var results = List.of(analyze(unreviewed, unreviewed), analyze(reviewed, reviewed), analyze(reviewed, changed));
        var path = Path.of(System.getProperty("basedir", "."), "target/decision-publication-coverage-samples.json");
        Files.createDirectories(path.getParent()); Files.writeString(path, MAPPER.writeValueAsString(results));
    }
}

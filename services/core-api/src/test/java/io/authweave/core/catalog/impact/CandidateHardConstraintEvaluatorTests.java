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
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.catalog.draft.CatalogDraftFacts;
import io.authweave.core.catalog.draft.ProviderCatalogDraft;
import static io.authweave.core.catalog.impact.CandidateHardConstraintEvaluator.*;
import static io.authweave.core.evaluation.CapabilityPreflight.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual unreviewed repository input plus in-memory rule hypotheses; NEVER real human approvals. */
class CandidateHardConstraintEvaluatorTests {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-09T17:00:00Z");
    private static final String TARGET = "keycloak-26.8.0-native-self-hosted";
    private static JsonNode assembly;

    @BeforeAll
    static void actualCandidate() throws Exception {
        var root = Path.of(System.getProperty("basedir", "."), "../..").toAbsolutePath().normalize();
        var output = Files.createTempFile("authweave-hard-kernel-", ".json");
        var errors = Files.createTempFile("authweave-hard-kernel-", ".log");
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
    void actualEightOptionCandidateIsUnresolvedNotAFabricatedShortlist() {
        var result = evaluate(profile(), 6, candidate(), SourceAssertions.unreviewed(candidate()), NOW);
        assertEquals(8, result.candidates().size());
        result.candidates().forEach(c -> assertEquals(Verdict.UNRESOLVED, c.hardVerdict()));
        var target = target(result);
        assembly.at("/focusedCase/requiredFactPaths").forEach(path -> {
            var finding = target.findings().stream().filter(f -> path.asText().equals(f.factPath())).findFirst().orElseThrow();
            assertEquals(UNKNOWN, finding.outcome()); assertEquals("EVIDENCE_UNREVIEWED", finding.reasonCode());
        });
        assertEquals(assembly.at("/bindings/decisionCatalogSha256").asText(), result.binding().catalogSha256());
        assertFalse(result.sourceAuthorityVerified()); assertFalse(result.publicationReady());
        assertFalse(result.configurationVerified()); assertFalse(result.complianceVerified()); assertFalse(result.writesPerformed());
        assertFalse(MAPPER.valueToTree(result).has("shortlist"));
        assertFalse(MAPPER.valueToTree(target).has("score"));
    }

    @Test
    void javaReplaysAllActualNodeClaimDigestsIncludingOptionScopeAndOrderedArrays() {
        assertEquals(assembly.at("/bindings/decisionCanonicalization").asText(), DecisionCanonicalizer.VERSION);
        assembly.get("reviewTasks").forEach(task -> assertEquals(task.get("claimSha256").asText(),
                claimSha256(option(candidate(), task.get("optionId").asText()), task.get("factPath").asText())));
        assertEquals(DecisionCanonicalizer.sha256(MAPPER.readTree("{\"b\":2,\"a\":[1,2]}")),
                DecisionCanonicalizer.sha256(MAPPER.readTree("{\"a\":[1,2],\"b\":2}")));
        assertNotEquals(DecisionCanonicalizer.sha256(MAPPER.readTree("[1,2]")), DecisionCanonicalizer.sha256(MAPPER.readTree("[2,1]")));
    }

    @Test
    void supportedAssertionHypothesisRunsRulesButNeverAuthenticatesOrPublishesItself() {
        var result = run(profile(), candidate(), supporting(candidate()), NOW);
        assertEquals(Verdict.ELIGIBLE, target(result).hardVerdict());
        assertFalse(result.sourceAuthorityVerified()); assertFalse(result.publicationReady()); assertFalse(result.writesPerformed());
        assertTrue(result.deferredBoundaries().contains("reviewReceiptAuthenticationAndLoading"));
        var scim = finding(result, "facts.SCIM");
        assertEquals(PASS, scim.outcome()); assertEquals(Assertion.SOURCE_SUPPORTS_CLAIM, scim.evidence().sourceAssertion());
        assertFalse(scim.evidence().conditions().isEmpty());
        assertEquals(option(candidate(), TARGET).at("/facts/SCIM/conditions").size(), scim.evidence().conditions().size());
        assertEquals(NOW, result.binding().evaluatedAt());
        assertEquals(result, run(profile(), candidate(), supporting(candidate()), NOW));
    }

    @Test
    void partialAssertionsDoNotPromoteOtherClaimsOrOptions() {
        var facts = new ArrayList<>(supporting(candidate())); facts.removeIf(f -> f.factPath().equals("facts.SCIM"));
        var result = run(profile(), candidate(), facts, NOW);
        assertEquals(Verdict.UNRESOLVED, target(result).hardVerdict());
        assertEquals("EVIDENCE_UNREVIEWED", finding(result, "facts.SCIM").reasonCode());
        result.candidates().stream().filter(c -> !c.optionId().equals(TARGET)).forEach(c -> assertEquals(Verdict.UNRESOLVED, c.hardVerdict()));
    }

    @Test
    void reviewedNegativeClaimExcludesDespiteUnknownContextButUnreviewedNegativeCannot() {
        var input = candidate(); setAvailability(input, "SCIM", "UNAVAILABLE");
        ((ObjectNode) option(input, TARGET).at("/compatibility/clients")).remove("BROWSER");
        var reviewed = run(profile(), input, supporting(input), NOW);
        assertEquals(Verdict.EXCLUDED, target(reviewed).hardVerdict()); assertEquals(FAIL, finding(reviewed, "facts.SCIM").outcome());
        assertTrue(target(reviewed).findings().stream().anyMatch(f -> f.outcome() == UNKNOWN));
        var unreviewed = run(profile(), input, List.of(), NOW);
        assertEquals(Verdict.UNRESOLVED, target(unreviewed).hardVerdict()); assertEquals(UNKNOWN, finding(unreviewed, "facts.SCIM").outcome());
    }

    @Test
    void contradictedOrInsufficientSourceDoesNotTurnAnAssertedNegativeIntoConfirmedFailure() {
        var input = candidate(); setAvailability(input, "SCIM", "UNAVAILABLE");
        for (var assertion : List.of(Assertion.SOURCE_DOES_NOT_SUPPORT_CLAIM, Assertion.INSUFFICIENT_EVIDENCE)) {
            var reviews = supporting(input).stream().map(f -> f.factPath().equals("facts.SCIM")
                    ? new FactAssertion(f.optionId(), f.factPath(), f.claimSha256(), assertion) : f).toList();
            var result = run(profile(), input, reviews, NOW);
            assertEquals(Verdict.UNRESOLVED, target(result).hardVerdict());
            assertEquals(UNKNOWN, finding(result, "facts.SCIM").outcome());
            assertEquals(assertion == Assertion.SOURCE_DOES_NOT_SUPPORT_CLAIM ? "EVIDENCE_CONTRADICTED" : "EVIDENCE_INSUFFICIENT",
                    finding(result, "facts.SCIM").reasonCode());
        }
    }

    @Test
    void reviewedUnknownStillBlocksAndJitNeverReplacesRequiredScim() {
        var input = candidate(); setAvailability(input, "SCIM", "UNKNOWN");
        var result = run(profile(), input, supporting(input), NOW);
        assertEquals(Verdict.UNRESOLVED, target(result).hardVerdict());
        assertEquals("REQUIRED_UNKNOWN", finding(result, "facts.SCIM").reasonCode());
        var missing = candidate(); ((ObjectNode) option(missing, TARGET).get("facts")).remove("SCIM");
        ((ObjectNode) option(missing, TARGET).get("facts")).set("JIT", hypothesis("availability", "OPTIONAL"));
        var withoutScim = run(profile(), missing, supporting(missing), NOW);
        assertEquals("EVIDENCE_MISSING", finding(withoutScim, "facts.SCIM").reasonCode());
        assertEquals(Verdict.UNRESOLVED, target(withoutScim).hardVerdict());
    }

    @Test
    void forbiddenRequiresUnavailableNotOptionalOrProseDisablement() {
        var profile = profile(); ((ObjectNode) profile.get("provisioning")).put("scim", "FORBIDDEN");
        for (String availability : List.of("OPTIONAL", "UNAVAILABLE", "MANDATORY", "UNKNOWN")) {
            var input = candidate(); setAvailability(input, "SCIM", availability);
            var result = run(profile, input, supporting(input), NOW);
            var expected = switch (availability) { case "UNAVAILABLE" -> PASS; case "MANDATORY" -> FAIL; default -> UNKNOWN; };
            assertEquals(expected, finding(result, "facts.SCIM").outcome(), availability);
            if (availability.equals("OPTIONAL")) assertEquals("CONFIGURATION_REQUIRED", finding(result, "facts.SCIM").reasonCode());
        }
    }

    @Test
    void freshnessIsInclusiveAtNinetyDaysAndReviewNeverRenewsObservationTime() {
        var input = candidate();
        ((ObjectNode) option(input, TARGET).at("/facts/SCIM/evidence")).put("observedAt", NOW.minusSeconds(90L * 86400).toString());
        var assertions = supporting(input);
        assertEquals(PASS, finding(run(profile(), input, assertions, NOW), "facts.SCIM").outcome());
        var stale = run(profile(), input, assertions, NOW.plusNanos(1));
        assertEquals("EVIDENCE_STALE", finding(stale, "facts.SCIM").reasonCode());
        assertEquals(NOW.minusSeconds(90L * 86400), finding(stale, "facts.SCIM").evidence().observedAt());
        ((ObjectNode) option(input, TARGET).at("/facts/SCIM/evidence")).put("observedAt", NOW.plusNanos(1).toString());
        var future = run(profile(), input, supporting(input), NOW);
        assertEquals("EVIDENCE_FUTURE", finding(future, "facts.SCIM").reasonCode());
        assertEquals(Verdict.UNRESOLVED, target(future).hardVerdict());
    }

    @Test
    void exactBindingsRejectStaleCandidateChangedScopeWrongAddressDuplicateAndClaimTampering() {
        var assertions = supporting(candidate());
        var changed = candidate(); ((ObjectNode) option(changed, TARGET)).put("region", "Different scope");
        assertThrows(IllegalArgumentException.class, () -> evaluate(profile(), 6, changed,
                new SourceAssertions(DecisionCanonicalizer.sha256(candidate()), assertions), NOW));
        assertThrows(IllegalArgumentException.class, () -> run(profile(), changed, assertions, NOW));
        var first = assertions.getFirst();
        for (var invalid : List.of(
                new FactAssertion("other-option", first.factPath(), first.claimSha256(), first.assertion()),
                new FactAssertion(first.optionId(), "facts.NO_SUCH_FACT", first.claimSha256(), first.assertion()),
                new FactAssertion(first.optionId(), first.factPath(), "0".repeat(64), first.assertion())))
            assertThrows(IllegalArgumentException.class, () -> run(profile(), candidate(), List.of(invalid), NOW));
        assertThrows(IllegalArgumentException.class, () -> run(profile(), candidate(), List.of(first, first), NOW));
    }

    @Test
    void requiredAuditabilityAndElevatedAssuranceCannotDisappearFromHardVerdict() {
        var profile = profile(); ((ObjectNode) profile.get("security")).put("auditability", "REQUIRED");
        ((ObjectNode) profile.at("/security/auditabilityRequirements")).set("selectedCriteria",
                MAPPER.createArrayNode().add("AUTHENTICATION_FAILURE_EVENTS"));
        var audit = run(profile, candidate(), supporting(candidate()), NOW);
        assertEquals(Verdict.UNRESOLVED, target(audit).hardVerdict());
        assertTrue(target(audit).findings().stream().anyMatch(f -> "auditabilitySupplement.AUTHENTICATION_FAILURE_EVENTS".equals(f.factPath())
                && f.outcome() == UNKNOWN && f.reasonCode().equals("EVIDENCE_MISSING")));
        for (String assurance : List.of("ELEVATED", "HIGH", "UNKNOWN")) {
            var elevated = profile(); ((ObjectNode) elevated.get("security")).put("assurance", assurance);
            assertEquals(Verdict.UNRESOLVED, target(run(elevated, candidate(), supporting(candidate()), NOW)).hardVerdict());
        }
    }

    @Test
    void identifiedAndUnknownComplianceScopesBlockWithoutClaimingCertification() {
        var profile = profile(); var security = (ObjectNode) profile.get("security");
        security.put("complianceScopeStatus", "TARGETS_IDENTIFIED"); security.set("complianceTargets", MAPPER.createArrayNode().add("GDPR"));
        assertEquals(Verdict.UNRESOLVED, target(run(profile, candidate(), supporting(candidate()), NOW)).hardVerdict());
        security.put("complianceScopeStatus", "UNKNOWN");
        assertEquals(Verdict.UNRESOLVED, target(run(profile, candidate(), supporting(candidate()), NOW)).hardVerdict());
        security.put("complianceScopeStatus", "NONE_IDENTIFIED");
        assertThrows(IllegalArgumentException.class, () -> run(profile, candidate(), supporting(candidate()), NOW));
    }

    @Test
    void requiredResidencyAndAuthenticationRemainUnknownWithoutTheirOwnScopedEvidence() {
        var profile = profile(); var security = (ObjectNode) profile.get("security");
        security.put("dataResidency", "REQUIRED");
        ((ObjectNode) security.get("dataResidencyDetails")).set("allowedCountries", MAPPER.createArrayNode().add("DE"));
        ((ObjectNode) security.get("dataResidencyDetails")).set("dataCategories", MAPPER.createArrayNode().add("USER_PROFILES"));
        ((ObjectNode) security.get("authenticationControls")).put("phishingResistance", "REQUIRED");
        var result = run(profile, candidate(), supporting(candidate()), NOW);
        assertEquals(Verdict.UNRESOLVED, target(result).hardVerdict());
        assertTrue(target(result).findings().stream().anyMatch(f -> "residency.USER_PROFILES".equals(f.factPath()) && f.outcome() == UNKNOWN));
        assertTrue(target(result).findings().stream().anyMatch(f -> f.factPath() != null && f.factPath().endsWith("PHISHING_RESISTANCE") && f.outcome() == UNKNOWN));
    }

    @Test
    void unknownProfileContextIsNotSkippedAndBindingsRetainExactWireProfile() {
        var profile = profile(); ((ObjectNode) profile.get("audience")).put("tenancy", "UNKNOWN");
        var result = run(profile, candidate(), supporting(candidate()), NOW);
        assertEquals(Verdict.UNRESOLVED, target(result).hardVerdict());
        assertEquals(DecisionCanonicalizer.sha256(profile), result.binding().profileSha256());
        assertEquals(6, result.binding().profileSchemaVersion());
        assertTrue(target(result).findings().stream().anyMatch(f -> f.profilePath().equals("audience.tenancy") && f.outcome() == UNKNOWN));
    }

    @Test
    void preferenceIsNotHardFailureOrImplicitScoreAndReviewInputsAreBoundSeparately() {
        var input = candidate(); setAvailability(input, "SAML", "UNAVAILABLE");
        var result = run(profile(), input, supporting(input), NOW);
        assertEquals(Verdict.ELIGIBLE, target(result).hardVerdict());
        assertEquals(NOT_APPLIED, finding(result, "facts.SAML").outcome());
        assertEquals("PREFERENCE_NOT_SCORED", finding(result, "facts.SAML").reasonCode());
        var unreviewed = run(profile(), input, List.of(), NOW);
        assertEquals(result.binding().catalogSha256(), unreviewed.binding().catalogSha256());
        assertNotEquals(result.binding().sourceAssertionsSha256(), unreviewed.binding().sourceAssertionsSha256());
    }

    @Test
    void rejectLegacyIncompleteUnknownFieldAndDuplicateOptionInputs() {
        assertThrows(IllegalArgumentException.class, () -> evaluate(profile(), 5, candidate(), SourceAssertions.unreviewed(candidate()), NOW));
        var incomplete = profile(); ((ObjectNode) incomplete.get("security")).remove("auditabilityRequirements");
        assertThrows(IllegalArgumentException.class, () -> run(incomplete, candidate(), List.of(), NOW));
        var unknown = candidate(); ((ObjectNode) unknown).put("approvalGranted", true);
        assertThrows(RuntimeException.class, () -> run(profile(), unknown, List.of(), NOW));
        var duplicate = candidate(); ((tools.jackson.databind.node.ArrayNode) duplicate.get("options")).add(option(duplicate, TARGET).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> run(profile(), duplicate, List.of(), NOW));
    }

    @Test
    void residencyUsesReviewedCompletenessAndExactAllowlistNotARegionLabel() {
        var profile = profile(); ((ObjectNode) profile.get("security")).put("dataResidency", "REQUIRED");
        var details = (ObjectNode) profile.at("/security/dataResidencyDetails");
        details.set("allowedCountries", MAPPER.createArrayNode().add("DE"));
        details.set("dataCategories", MAPPER.createArrayNode().add("USER_PROFILES"));
        for (var scenario : List.of(List.of("COMPLETE", "DE", "PASS"), List.of("PARTIAL", "DE", "UNKNOWN"),
                List.of("COMPLETE", "US", "FAIL"), List.of("UNKNOWN", "", "UNKNOWN"))) {
            var input = candidate(); var fact = hypothesis("coverage", scenario.getFirst());
            var countries = MAPPER.createArrayNode(); if (!scenario.get(1).isEmpty()) countries.add(scenario.get(1));
            fact.set("storageCountries", countries);
            ((ObjectNode) option(input, TARGET).get("residency")).set("USER_PROFILES", fact);
            var result = run(profile, input, supporting(input), NOW);
            assertEquals(io.authweave.core.evaluation.CapabilityPreflight.Outcome.valueOf(scenario.get(2)),
                    finding(result, "residency.USER_PROFILES").outcome());
            assertEquals(UNKNOWN, finding(run(profile, input, List.of(), NOW), "residency.USER_PROFILES").outcome());
        }
    }

    @Test
    void authenticationRequiresBothAvailabilityAndEnforcementForThisClientAndPopulation() {
        var profile = profile(); ((ObjectNode) profile.at("/security/authenticationControls")).put("phishingResistance", "REQUIRED");
        for (var scenario : List.of(List.of("SUPPORTED", "SUPPORTED", "PASS"), List.of("SUPPORTED", "UNSUPPORTED", "FAIL"),
                List.of("SUPPORTED", "UNKNOWN", "UNKNOWN"), List.of("UNSUPPORTED", "UNSUPPORTED", "FAIL"))) {
            var input = candidate(); var fact = hypothesis("availability", scenario.getFirst()); fact.put("enforcement", scenario.get(1));
            var populations = MAPPER.createObjectNode(); var controls = MAPPER.createObjectNode();
            controls.set("PHISHING_RESISTANCE", fact); populations.set("EXTERNAL_CUSTOMERS", controls);
            ((ObjectNode) option(input, TARGET).get("authenticationControls")).set("BROWSER", populations);
            var result = run(profile, input, supporting(input), NOW);
            assertEquals(io.authweave.core.evaluation.CapabilityPreflight.Outcome.valueOf(scenario.get(2)),
                    finding(result, "authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE").outcome());
            assertEquals(UNKNOWN, finding(run(profile, input, List.of(), NOW),
                    "authenticationControls.BROWSER.EXTERNAL_CUSTOMERS.PHISHING_RESISTANCE").outcome());
        }
    }

    @Test
    void reviewedUnsupportedContextExcludesButFactsFromAnotherOptionCannotFillMissingContext() {
        var input = candidate(); ((ObjectNode) option(input, TARGET).at("/compatibility/clients/BROWSER")).put("support", "UNSUPPORTED");
        assertEquals(FAIL, finding(run(profile(), input, supporting(input), NOW), "compatibility.clients.BROWSER").outcome());
        assertEquals(UNKNOWN, finding(run(profile(), input, List.of(), NOW), "compatibility.clients.BROWSER").outcome());
        ((ObjectNode) option(input, TARGET).at("/compatibility/clients")).remove("BROWSER");
        var neighbor = input.get("options").get(1);
        if (neighbor.get("id").asText().equals(TARGET)) neighbor = input.get("options").get(2);
        ((ObjectNode) neighbor.at("/compatibility/clients")).set("BROWSER", hypothesis("support", "SUPPORTED"));
        var result = run(profile(), input, supporting(input), NOW);
        assertEquals("EVIDENCE_MISSING", finding(result, "compatibility.clients.BROWSER").reasonCode());
        assertEquals(Verdict.UNRESOLVED, target(result).hardVerdict());
    }

    private static JsonNode candidate() { return assembly.get("candidate").deepCopy(); }
    private static JsonNode profile() { return assembly.at("/focusedCase/profile").deepCopy(); }
    private static JsonNode option(JsonNode input, String id) {
        for (var option : input.get("options")) if (option.get("id").asText().equals(id)) return option;
        throw new IllegalArgumentException("Missing test option");
    }
    private static void setAvailability(JsonNode input, String capability, String value) {
        ((ObjectNode) option(input, TARGET).at("/facts/" + capability)).put("availability", value);
    }
    private static ObjectNode hypothesis(String field, String value) {
        var fact = MAPPER.createObjectNode(); fact.put(field, value); fact.set("conditions", MAPPER.createArrayNode());
        var evidence = MAPPER.createObjectNode(); evidence.put("sourceUrl", "https://facts.example.invalid/test-only");
        evidence.put("observedAt", NOW.toString()); evidence.put("summary", "In-memory rule hypothesis, not a provider fact or source review.");
        fact.set("evidence", evidence); return fact;
    }
    private static List<FactAssertion> supporting(JsonNode input) {
        var option = option(input, TARGET); var typed = MAPPER.treeToValue(option, ProviderCatalogDraft.Option.class);
        return CatalogDraftFacts.entries(typed).keySet().stream().map(path ->
                new FactAssertion(TARGET, path, claimSha256(option, path), Assertion.SOURCE_SUPPORTS_CLAIM)).toList();
    }
    private static Analysis run(JsonNode profile, JsonNode input, List<FactAssertion> assertions, Instant at) {
        return evaluate(profile, 6, input, new SourceAssertions(DecisionCanonicalizer.sha256(input), assertions), at);
    }
    private static Candidate target(Analysis result) {
        return result.candidates().stream().filter(c -> c.optionId().equals(TARGET)).findFirst().orElseThrow();
    }
    private static Finding finding(Analysis result, String factPath) {
        return target(result).findings().stream().filter(f -> factPath.equals(f.factPath())).findFirst().orElseThrow();
    }
}

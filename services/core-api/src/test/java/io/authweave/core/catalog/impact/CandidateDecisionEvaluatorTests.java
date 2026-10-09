package io.authweave.core.catalog.impact;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import io.authweave.core.catalog.ProviderCatalog.Capability;
import io.authweave.core.catalog.draft.CatalogDraftFacts;
import io.authweave.core.catalog.draft.ProviderCatalogDraft;
import io.authweave.core.evaluation.ArchitecturePatternEvaluator;
import io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator;
import io.authweave.core.evaluation.ProvisioningLifecycleV2Evaluator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static io.authweave.core.catalog.impact.CandidateHardConstraintEvaluator.*;
import static io.authweave.core.catalog.impact.CandidateDecisionEvaluator.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real unreviewed candidate plus fictional .invalid calculation hypotheses, never vendor approval. */
class CandidateDecisionEvaluatorTests {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-09T17:00:00Z");
    private static JsonNode assembly;
    private static JsonNode policy;

    @BeforeAll
    static void actualCandidate() throws Exception {
        var root = Path.of(System.getProperty("basedir", "."), "../..").toAbsolutePath().normalize();
        policy = MAPPER.readTree(root.resolve("packages/contracts/decision-core/policy.v1.json").toFile());
        var output = Files.createTempFile("authweave-decision-kernel-", ".json");
        var errors = Files.createTempFile("authweave-decision-kernel-", ".log");
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
    void actualEightUnreviewedOptionsProduceNoScoresOrArchitectureRecommendation() {
        var catalog = assembly.get("candidate");
        var result = evaluate(profile(), catalog, SourceAssertions.unreviewed(catalog));
        assertEquals(8, result.candidates().size()); assertTrue(result.shortlist().isEmpty()); assertTrue(result.rankGroups().isEmpty());
        assertEquals(CandidatePreferenceScorer.Status.NEEDS_INFORMATION, result.status());
        result.candidates().forEach(c -> { assertEquals(Verdict.UNRESOLVED, c.hardChecks().hardVerdict()); assertNull(c.score()); });
        assertEquals(AdviceStatus.NEEDS_INFORMATION, result.architecture().status());
        result.architecture().patterns().forEach(p -> assertNotEquals(Disposition.RECOMMENDED, p.choice().disposition()));
        result.architecture().provisioning().forEach(p -> assertNotEquals(Disposition.RECOMMENDED, p.disposition()));
        assertEquals(assembly.at("/bindings/decisionCatalogSha256").asText(), result.binding().inputs().hardChecks().catalogSha256());
        // Profile-only digest, not the assembly pin of the entire case (weights/description included).
        assertEquals("8de7ecaa59f0a1c736f3185c42b4a6164ee22b514c030d295c42d40bc5982bab", result.binding().inputs().hardChecks().profileSha256());
        noAuthority(result);
    }

    @Test
    void compositionPreservesComputedScoringAndAllExactVersionBindings() {
        var profile = profile(); var catalog = catalog("zeta", "alpha"); var assertions = supporting(catalog); var weights = weights();
        var result = CandidateDecisionEvaluator.evaluate(profile, 6, catalog, assertions, weights, NOW);
        var scoring = CandidatePreferenceScorer.evaluate(profile, 6, catalog, assertions, weights, NOW);
        assertEquals(scoring.candidates(), result.candidates()); assertEquals(scoring.shortlist(), result.shortlist());
        assertEquals(scoring.rankGroups(), result.rankGroups()); assertEquals(scoring.status(), result.status());
        assertEquals(scoring.binding(), result.binding().inputs()); assertEquals(CandidatePreferenceScorer.VERSION, result.binding().scoringVersion());
        assertEquals(ArchitecturePatternEvaluator.POLICY_VERSION, result.binding().patternDefinitionsVersion());
        assertEquals(ArchitecturePrerequisiteEvaluator.POLICY_VERSION, result.binding().prerequisiteVersion());
        assertEquals(ProvisioningLifecycleV2Evaluator.POLICY_VERSION, result.binding().provisioningConditionsVersion());
        assertEquals(NOW, result.binding().inputs().hardChecks().evaluatedAt());
        assertEquals("UNVERIFIED_CANDIDATE_DECISION_CALCULATION", result.scope());
        assertTrue(result.deferredBoundaries().contains("reviewReceiptAuthenticationAndLoading"));
        assertTrue(result.deferredBoundaries().contains("realAuditabilitySupplementLoading"));
        assertTrue(result.deferredBoundaries().contains("publishedSnapshotPinning"));
        assertFalse(result.deferredBoundaries().contains("architectureAdvice"));
        assertFalse(MAPPER.valueToTree(result).has("winner")); noAuthority(result);
    }

    @Test
    void browserSessionWithoutOAuthApisDoesNotInventApiProtectionAndKeepsAlternatives() {
        var result = evaluate(profile(), catalog("alpha"));
        assertEquals(Disposition.RECOMMENDED, pattern(result, "SERVER_SIDE_SESSION").disposition());
        assertEquals(Disposition.ALTERNATIVE, pattern(result, "BFF_SESSION").disposition());
        assertEquals(Disposition.ALTERNATIVE, pattern(result, "SPA_CODE_PKCE").disposition());
        assertEquals(ApiStatus.NOT_REQUIRED, result.architecture().apiProtection().status());
        assertEquals(List.of("alpha"), pattern(result, "SERVER_SIDE_SESSION").conditionalOptionIds());
        assertEquals(5, result.architecture().patterns().size());
        assertEquals(List.of("BFF_SESSION", "SERVER_SIDE_SESSION", "SPA_CODE_PKCE", "NATIVE_CODE_PKCE", "M2M_CLIENT_CREDENTIALS"),
                result.architecture().patterns().stream().map(p -> p.choice().id()).toList());
        assertEquals(Disposition.NOT_APPLICABLE, pattern(result, "NATIVE_CODE_PKCE").disposition());
        assertTrue(pattern(result, "NATIVE_CODE_PKCE").conditionalOptionIds().isEmpty());
    }

    @Test
    void requiredApiAndBrowserMinimizationPreferBffWithoutHidingSpaTradeoffs() {
        var profile = profile(); protocols(profile).put("oauth2ProtectedApis", "REQUIRED");
        var result = evaluate(profile, catalog("alpha"));
        assertEquals(Disposition.RECOMMENDED, pattern(result, "BFF_SESSION").disposition());
        assertEquals(Disposition.ALTERNATIVE, pattern(result, "SERVER_SIDE_SESSION").disposition());
        assertEquals(Disposition.ALTERNATIVE, pattern(result, "SPA_CODE_PKCE").disposition());
        assertEquals(ApiStatus.REQUIRED_CONDITIONAL, result.architecture().apiProtection().status());
        assertTrue(pattern(result, "SPA_CODE_PKCE").cons().stream().anyMatch(s -> s.contains("PKCE does not remove token exposure")));
        assertTrue(result.architecture().apiProtection().conditions().stream().anyMatch(s -> s.contains("resource-level authorization")));
    }

    @Test
    void requiredMinimizationNeedsDefinedSpaExposureNotAnImplicitBan() {
        var profile = profile(); security(profile).put("browserTokenExposureMinimization", "REQUIRED");
        var result = evaluate(profile, catalog("alpha"));
        assertEquals(Disposition.UNRESOLVED, pattern(result, "SPA_CODE_PKCE").disposition());
        assertEquals("ACCEPTABLE_BROWSER_EXPOSURE_UNDEFINED", pattern(result, "SPA_CODE_PKCE").reasonCode());
        assertEquals(Disposition.RECOMMENDED, pattern(result, "SERVER_SIDE_SESSION").disposition());
        assertTrue(result.candidates().stream().allMatch(c -> c.hardChecks().hardVerdict() == Verdict.ELIGIBLE));
    }

    @Test
    void noTokenPreferenceDoesNotInventBffWinnerForProtectedApis() {
        var profile = profile(); protocols(profile).put("oauth2ProtectedApis", "REQUIRED");
        security(profile).put("browserTokenExposureMinimization", "NOT_REQUIRED");
        var result = evaluate(profile, catalog("alpha"));
        for (var id : List.of("BFF_SESSION", "SERVER_SIDE_SESSION", "SPA_CODE_PKCE"))
            assertEquals(Disposition.ALTERNATIVE, pattern(result, id).disposition());
    }

    @Test
    void unknownOrUndefinedBrowserIntentWithholdsBrowserAdviceButNotNativePattern() {
        for (String token : List.of("UNKNOWN", "FORBIDDEN")) {
            var profile = profile(); clients(profile, "BROWSER", "NATIVE_MOBILE"); security(profile).put("browserTokenExposureMinimization", token);
            var result = evaluate(profile, catalog("alpha"));
            for (String id : List.of("BFF_SESSION", "SERVER_SIDE_SESSION", "SPA_CODE_PKCE")) assertEquals(Disposition.UNRESOLVED, pattern(result, id).disposition());
            assertEquals(Disposition.RECOMMENDED, pattern(result, "NATIVE_CODE_PKCE").disposition());
        }
    }

    @Test
    void mixedClientsReceiveSeparateNativeAndWorkloadAdviceNotBrowserSessionInheritance() {
        var profile = profile(); clients(profile, "BROWSER", "NATIVE_MOBILE", "MACHINE_TO_MACHINE");
        protocols(profile).put("oauth2ProtectedApis", "REQUIRED");
        var result = evaluate(profile, catalog("alpha"));
        assertEquals(Disposition.RECOMMENDED, pattern(result, "BFF_SESSION").disposition());
        assertEquals(Disposition.RECOMMENDED, pattern(result, "NATIVE_CODE_PKCE").disposition());
        assertEquals(Disposition.RECOMMENDED, pattern(result, "M2M_CLIENT_CREDENTIALS").disposition());
        assertTrue(pattern(result, "NATIVE_CODE_PKCE").conditions().stream().anyMatch(s -> s.contains("external user-agent")));
        assertTrue(pattern(result, "M2M_CLIENT_CREDENTIALS").conditions().stream().anyMatch(s -> s.contains("user delegation")));
        assertTrue(result.architecture().apiProtection().conditions().stream().anyMatch(s -> s.contains("does not prove client-credentials grant support")));
    }

    @Test
    void workloadWithoutSelectedOAuthProtectionRemainsUnresolvedDespiteEnterpriseSso() {
        var profile = profile(); clients(profile, "MACHINE_TO_MACHINE"); protocols(profile).put("enterpriseSingleSignOn", "REQUIRED");
        var result = evaluate(profile, catalog("alpha"));
        assertEquals(Disposition.UNRESOLVED, pattern(result, "M2M_CLIENT_CREDENTIALS").disposition());
        assertEquals("WORKLOAD_API_AUTHORIZATION_NOT_SELECTED", pattern(result, "M2M_CLIENT_CREDENTIALS").reasonCode());
        assertEquals(ApiStatus.NOT_REQUIRED, result.architecture().apiProtection().status());
    }

    @Test
    void unknownClientsOrApiRequirementCannotBeResolvedByPatternDefinitions() {
        var profile = profile(); clients(profile);
        var result = evaluate(profile, catalog("alpha"));
        result.architecture().patterns().forEach(p -> assertEquals(Disposition.UNRESOLVED, p.choice().disposition()));
        profile = profile(); protocols(profile).put("oauth2ProtectedApis", "UNKNOWN");
        result = evaluate(profile, catalog("alpha"));
        assertEquals(ApiStatus.NEEDS_INFORMATION, result.architecture().apiProtection().status());
        assertEquals(Disposition.UNRESOLVED, pattern(result, "SERVER_SIDE_SESSION").disposition());
    }

    @Test
    void optionalArchitectureCapabilityNeedsItsOwnFreshSupportingClaim() {
        // OAuth API protection is NOT_REQUIRED: its hard finding alone cannot authorize BFF/SPA advice.
        for (String problem : List.of("MISSING", "UNREVIEWED", "CONTRADICTED", "INSUFFICIENT", "STALE", "FUTURE", "UNKNOWN", "UNAVAILABLE")) {
            var catalog = catalog("alpha"); var fact = (ObjectNode) option(catalog, "alpha").at("/facts/OAUTH2_APIS");
            if (problem.equals("MISSING")) ((ObjectNode) option(catalog, "alpha").get("facts")).remove("OAUTH2_APIS");
            if (problem.equals("STALE") || problem.equals("FUTURE")) ((ObjectNode) fact.get("evidence")).put("observedAt",
                    problem.equals("STALE") ? NOW.minusSeconds(90L * 86400 + 1).toString() : NOW.plusSeconds(1).toString());
            if (problem.equals("UNKNOWN") || problem.equals("UNAVAILABLE")) fact.put("availability", problem);
            var assertions = new ArrayList<>(supporting(catalog).facts());
            if (problem.equals("UNREVIEWED")) assertions.removeIf(a -> a.factPath().equals("facts.OAUTH2_APIS"));
            if (problem.equals("CONTRADICTED") || problem.equals("INSUFFICIENT")) assertions.replaceAll(a -> a.factPath().equals("facts.OAUTH2_APIS")
                    ? new FactAssertion(a.optionId(), a.factPath(), a.claimSha256(), problem.equals("CONTRADICTED") ? Assertion.SOURCE_DOES_NOT_SUPPORT_CLAIM : Assertion.INSUFFICIENT_EVIDENCE) : a);
            var result = evaluate(profile(), catalog, new SourceAssertions(DecisionCanonicalizer.sha256(catalog), assertions));
            assertEquals(Verdict.ELIGIBLE, result.candidates().getFirst().hardChecks().hardVerdict(), problem);
            assertEquals(Disposition.UNRESOLVED, pattern(result, "BFF_SESSION").disposition(), problem);
            assertEquals(Disposition.UNRESOLVED, pattern(result, "SPA_CODE_PKCE").disposition(), problem);
            assertEquals(Disposition.RECOMMENDED, pattern(result, "SERVER_SIDE_SESSION").disposition(), problem);
            assertEquals(ApiStatus.NOT_REQUIRED, result.architecture().apiProtection().status(), problem);
        }
    }

    @Test
    void backendSessionMayUseSamlButCannotDonateItToOidcPkcePatterns() {
        var profile = profile(); ((ObjectNode) profile.at("/protocols/federation")).put("OIDC", "NOT_REQUIRED");
        var catalog = catalog("alpha"); availability(catalog, "alpha", "OIDC", "UNAVAILABLE");
        var result = evaluate(profile, catalog);
        assertEquals(Disposition.RECOMMENDED, pattern(result, "SERVER_SIDE_SESSION").disposition());
        assertEquals(Disposition.UNRESOLVED, pattern(result, "BFF_SESSION").disposition());
        assertEquals(Disposition.UNRESOLVED, pattern(result, "SPA_CODE_PKCE").disposition());
        assertTrue(pattern(result, "SERVER_SIDE_SESSION").conditions().stream().anyMatch(s -> s.contains("OIDC or SAML")));
    }

    @Test
    void forbiddenOAuthApisDoNotBecomeProposedPatternCapabilities() {
        var profile = profile(); protocols(profile).put("oauth2ProtectedApis", "FORBIDDEN");
        var catalog = catalog("alpha"); availability(catalog, "alpha", "OAUTH2_APIS", "UNAVAILABLE");
        var result = evaluate(profile, catalog);
        assertEquals(ApiStatus.FORBIDDEN, result.architecture().apiProtection().status());
        assertEquals(Disposition.RECOMMENDED, pattern(result, "SERVER_SIDE_SESSION").disposition());
        assertEquals(Disposition.UNRESOLVED, pattern(result, "BFF_SESSION").disposition());
        assertEquals("PROFILE_FORBIDS_PATTERN_CAPABILITY", pattern(result, "BFF_SESSION").optionChecks().getFirst().capabilities().get(1).reasonCode());
    }

    @Test
    void requiredScimNeverBecomesJitRecommendationAndPreservesLifecycleConditions() {
        var result = evaluate(profile(), catalog("alpha"));
        assertEquals(Disposition.RECOMMENDED, provisioning(result, "SCIM_PUSH").disposition());
        assertEquals(Disposition.NOT_APPLICABLE, provisioning(result, "JIT_LOGIN").disposition());
        assertEquals("REQUIRED_SCIM_CANNOT_BE_REPLACED_BY_JIT", provisioning(result, "JIT_LOGIN").reasonCode());
        assertEquals(Disposition.ALTERNATIVE, provisioning(result, "SCIM_AND_JIT").disposition());
        var conditions = provisioning(result, "SCIM_PUSH").conditions();
        for (String boundary : List.of("SCIM client, server", "User create, update", "existing", "tokens", "recovery"))
            assertTrue(conditions.stream().anyMatch(s -> s.contains(boundary)), boundary);
    }

    @Test
    void bothRequiredMechanismsRecommendHybridAndRetainCollisionPolicy() {
        var profile = profile(); provisioning(profile).put("justInTimeProvisioning", "REQUIRED");
        var result = evaluate(profile, catalog("alpha"));
        assertEquals(Disposition.RECOMMENDED, provisioning(result, "SCIM_AND_JIT").disposition());
        assertEquals(Disposition.NOT_APPLICABLE, provisioning(result, "SCIM_PUSH").disposition());
        assertEquals(Disposition.NOT_APPLICABLE, provisioning(result, "JIT_LOGIN").disposition());
        assertTrue(provisioning(result, "SCIM_AND_JIT").conditions().stream().anyMatch(s -> s.contains("collision handling")));
    }

    @Test
    void forbiddenProvisioningIsNotReintroducedAsAnAlternative() {
        var profile = profile(); provisioning(profile).put("justInTimeProvisioning", "FORBIDDEN");
        var catalog = catalog("alpha"); availability(catalog, "alpha", "JIT", "UNAVAILABLE");
        var result = evaluate(profile, catalog);
        assertEquals(Disposition.RECOMMENDED, provisioning(result, "SCIM_PUSH").disposition());
        assertEquals(Disposition.NOT_APPLICABLE, provisioning(result, "SCIM_AND_JIT").disposition());
    }

    @Test
    void groupsAreSeparateFromUserOnlyScimAndRequiredGroupEvidenceCannotBeBorrowed() {
        var profile = profile(); provisioning(profile).put("groupSynchronization", "REQUIRED");
        var catalog = catalog("alpha"); var result = evaluate(profile, catalog);
        assertEquals(Disposition.RECOMMENDED, provisioning(result, "SCIM_PUSH").disposition());
        assertTrue(provisioning(result, "SCIM_PUSH").conditions().stream().anyMatch(s -> s.contains("User-only SCIM is insufficient")));
        ((ObjectNode) option(catalog, "alpha").get("facts")).remove("GROUP_SYNC"); result = evaluate(profile, catalog);
        assertEquals(Disposition.UNRESOLVED, provisioning(result, "SCIM_PUSH").disposition());
        assertTrue(result.shortlist().isEmpty());
    }

    @Test
    void noProvisioningRequirementDoesNotInventAMechanismRecommendation() {
        var profile = profile(); provisioning(profile).put("scim", "NOT_REQUIRED");
        var result = evaluate(profile, catalog("alpha"));
        result.architecture().provisioning().forEach(p -> assertEquals(Disposition.ALTERNATIVE, p.disposition()));
    }

    @Test
    void hardFailuresAndAssuranceComplianceAuditabilityGapsWithholdAllApplicableAdvice() {
        for (String boundary : List.of("SCIM", "assurance", "compliance", "auditability")) {
            var profile = profile(); var catalog = catalog("alpha");
            if (boundary.equals("SCIM")) availability(catalog, "alpha", "SCIM", "UNAVAILABLE");
            if (boundary.equals("assurance")) security(profile).put("assurance", "HIGH");
            if (boundary.equals("compliance")) { security(profile).put("complianceScopeStatus", "TARGETS_IDENTIFIED"); security(profile).set("complianceTargets", MAPPER.createArrayNode().add("GDPR")); }
            if (boundary.equals("auditability")) security(profile).put("auditability", "REQUIRED");
            var result = evaluate(profile, catalog);
            assertTrue(result.shortlist().isEmpty()); assertNull(result.candidates().getFirst().score());
            assertEquals(Disposition.UNRESOLVED, pattern(result, "SERVER_SIDE_SESSION").disposition());
            assertEquals(Disposition.UNRESOLVED, provisioning(result, "SCIM_PUSH").disposition());
        }
    }

    @Test
    void eachChoiceNamesOnlyCompatibleEligibleExactOptionsWithoutCrossOptionDonation() {
        var catalog = catalog("alpha", "beta"); availability(catalog, "alpha", "OIDC", "UNAVAILABLE");
        var result = evaluate(profile(), catalog);
        assertEquals(List.of("beta"), result.shortlist());
        assertEquals(List.of("beta"), pattern(result, "SERVER_SIDE_SESSION").conditionalOptionIds());
        assertEquals(Match.EXCLUDED, pattern(result, "SERVER_SIDE_SESSION").optionChecks().getFirst().match());
        assertEquals(List.of("beta"), provisioning(result, "SCIM_PUSH").conditionalOptionIds());
    }

    @Test
    void adviceDoesNotFillInOrVerifyPrerequisitesAndRetainsProsConsAndSources() {
        var result = evaluate(profile(), catalog("alpha"));
        for (var pattern : result.architecture().patterns()) {
            assertFalse(pattern.choice().pros().isEmpty()); assertFalse(pattern.choice().cons().isEmpty());
            assertFalse(pattern.choice().conditions().isEmpty()); assertFalse(pattern.choice().references().isEmpty());
            assertFalse(pattern.prerequisites().configurationVerified()); assertFalse(pattern.prerequisites().providerCompatibilityVerified());
            assertFalse(pattern.prerequisites().recommendationReady());
            pattern.prerequisites().checks().forEach(c -> assertNotEquals(ArchitecturePrerequisiteEvaluator.Outcome.CONDITIONALLY_SATISFIED, c.outcome()));
        }
        var check = pattern(result, "BFF_SESSION").optionChecks().getFirst().capabilities().getFirst();
        assertTrue(check.evidence().sourceUrl().getHost().endsWith(".invalid")); assertEquals(NOW, check.evidence().observedAt());
        assertEquals(List.of("Fictional configuration condition; not an observed deployment."), check.evidence().conditions());
        noAuthority(result);
    }

    @Test
    void operationsAndEveryPolicyLimitationRetainExactDeclaredInputsNotFreeTierPromises() {
        var profile = profile(); ((ObjectNode) profile.get("operations")).put("identityExpertise", "LIMITED");
        var result = evaluate(profile, catalog("alpha"));
        for (var route : policy.get("inputRoutes")) {
            boolean limitation = false; for (var value : route.get("routes")) limitation |= value.asText().equals("LIMITATION");
            if (limitation) {
                String path = route.get("profilePath").asText();
                var finding = result.limitations().stream().filter(l -> path.equals(l.profilePath())).findFirst().orElseThrow();
                assertEquals(profile.at("/" + path.replace('.', '/')).toString(), finding.declaredValue()); assertTrue(finding.blocksDeploymentRecommendation());
            }
        }
        assertTrue(pattern(result, "SERVER_SIDE_SESSION").conditions().stream().anyMatch(s -> s.contains("Identity expertise LIMITED")));
        assertTrue(result.limitations().stream().anyMatch(l -> l.explanation().contains("no price or free-tier guarantee")));
    }

    @Test
    void immutableInputsGiveReproducibleResultsAndWeightChangesCannotChangeArchitecture() {
        var profile = profile(); var catalog = catalog("alpha", "beta"); var assertions = supporting(catalog); var weights = weights();
        var before = List.of(profile.toString(), catalog.toString(), weights.toString());
        var result = CandidateDecisionEvaluator.evaluate(profile, 6, catalog, assertions, weights, NOW);
        assertEquals(result, CandidateDecisionEvaluator.evaluate(profile, 6, catalog, assertions, weights, NOW));
        assertEquals(before, List.of(profile.toString(), catalog.toString(), weights.toString()));
        assertThrows(UnsupportedOperationException.class, () -> result.architecture().patterns().clear());
        assertThrows(UnsupportedOperationException.class, () -> pattern(result, "BFF_SESSION").conditions().clear());
        security(profile).put("multiFactorAuthentication", "PREFERRED");
        var firstWeights = MAPPER.readTree("{\"mode\":\"EXPLICIT\",\"values\":[{\"capability\":\"SAML\",\"weight\":30},{\"capability\":\"MFA\",\"weight\":70}]}");
        var secondWeights = MAPPER.readTree("{\"mode\":\"EXPLICIT\",\"values\":[{\"capability\":\"SAML\",\"weight\":70},{\"capability\":\"MFA\",\"weight\":30}]}");
        var first = CandidateDecisionEvaluator.evaluate(profile, 6, catalog, assertions, firstWeights, NOW);
        var second = CandidateDecisionEvaluator.evaluate(profile, 6, catalog, assertions, secondWeights, NOW);
        assertEquals(first.architecture(), second.architecture()); assertEquals(first.binding().inputs().hardChecks(), second.binding().inputs().hardChecks());
        assertNotEquals(first.binding().inputs().weightsSha256(), second.binding().inputs().weightsSha256());
    }

    @Test
    void changedCandidateAndUnboundArchitectureClaimsAreRejectedBeforeComposition() {
        var catalog = catalog("alpha"); var assertions = supporting(catalog);
        availability(catalog, "alpha", "OAUTH2_APIS", "UNAVAILABLE");
        assertThrows(IllegalArgumentException.class, () -> evaluate(profile(), catalog, assertions));
        assertThrows(IllegalArgumentException.class, () -> evaluate(profile(), catalog,
                new SourceAssertions(DecisionCanonicalizer.sha256(catalog), assertions.facts())));
    }

    private static JsonNode profile() { return assembly.at("/focusedCase/profile").deepCopy(); }
    private static JsonNode weights() { return assembly.at("/focusedCase/weights").deepCopy(); }
    private static ObjectNode protocols(JsonNode profile) { return (ObjectNode) profile.get("protocols"); }
    private static ObjectNode security(JsonNode profile) { return (ObjectNode) profile.get("security"); }
    private static ObjectNode provisioning(JsonNode profile) { return (ObjectNode) profile.get("provisioning"); }
    private static void clients(JsonNode profile, String... values) {
        var clients = MAPPER.createArrayNode(); for (String value : values) clients.add(value);
        ((ObjectNode) profile.get("application")).set("clients", clients);
    }
    private static ObjectNode catalog(String... ids) {
        var catalog = MAPPER.createObjectNode(); catalog.put("schemaVersion", 1); catalog.put("kind", "PROVIDER_CATALOG_DRAFT");
        catalog.put("catalogVersion", "fictional-composition-1"); var options = MAPPER.createArrayNode();
        for (String id : ids) {
            var option = MAPPER.createObjectNode(); option.put("id", id); option.put("providerId", "fictional-" + id);
            option.put("product", "Fictional identity option"); option.put("plan", "Test-only plan"); option.put("region", "Test-only region");
            option.put("deployment", "SELF_HOSTED"); option.put("configuration", "Test-only scope " + id);
            var facts = MAPPER.createObjectNode(); for (var capability : Capability.values()) facts.set(capability.name(), fact("availability", "OPTIONAL"));
            option.set("facts", facts); var compatibility = MAPPER.createObjectNode();
            for (var context : List.of(List.of("applications", "B2B_SAAS"), List.of("clients", "BROWSER", "NATIVE_MOBILE", "MACHINE_TO_MACHINE"),
                    List.of("populations", "EXTERNAL_CUSTOMERS"), List.of("tenancy", "SINGLE_ORGANIZATION"), List.of("membership", "SINGLE_ORGANIZATION_PER_USER"))) {
                var dimension = MAPPER.createObjectNode(); for (int i = 1; i < context.size(); i++) dimension.set(context.get(i), fact("support", "SUPPORTED"));
                compatibility.set(context.getFirst(), dimension);
            }
            option.set("compatibility", compatibility); option.set("residency", MAPPER.createObjectNode()); option.set("authenticationControls", MAPPER.createObjectNode());
            options.add(option);
        }
        catalog.set("options", options); return catalog;
    }
    private static ObjectNode fact(String field, String value) {
        var fact = MAPPER.createObjectNode(); fact.put(field, value);
        fact.set("conditions", MAPPER.createArrayNode().add("Fictional configuration condition; not an observed deployment."));
        var evidence = MAPPER.createObjectNode(); evidence.put("sourceUrl", "https://architecture.example.invalid/test-only"); evidence.put("observedAt", NOW.toString());
        evidence.put("summary", "Fictional calculation hypothesis, not an observed provider fact or source approval."); fact.set("evidence", evidence); return fact;
    }
    private static JsonNode option(JsonNode catalog, String id) {
        for (var option : catalog.get("options")) if (option.get("id").asText().equals(id)) return option;
        throw new IllegalArgumentException("Missing test option");
    }
    private static void availability(JsonNode catalog, String id, String capability, String value) {
        ((ObjectNode) option(catalog, id).at("/facts/" + capability)).put("availability", value);
    }
    private static SourceAssertions supporting(JsonNode catalog) {
        var assertions = new ArrayList<FactAssertion>();
        for (var option : catalog.get("options")) {
            var typed = MAPPER.treeToValue(option, ProviderCatalogDraft.Option.class);
            CatalogDraftFacts.entries(typed).keySet().stream().sorted().forEach(path -> assertions.add(
                    new FactAssertion(typed.id(), path, claimSha256(option, path), Assertion.SOURCE_SUPPORTS_CLAIM)));
        }
        return new SourceAssertions(DecisionCanonicalizer.sha256(catalog), assertions);
    }
    private static Result evaluate(JsonNode profile, JsonNode catalog) { return evaluate(profile, catalog, supporting(catalog)); }
    private static Result evaluate(JsonNode profile, JsonNode catalog, SourceAssertions assertions) {
        return CandidateDecisionEvaluator.evaluate(profile, 6, catalog, assertions, weights(), NOW);
    }
    private static Choice pattern(Result result, String id) { return result.architecture().patterns().stream().map(PatternAdvice::choice).filter(p -> p.id().equals(id)).findFirst().orElseThrow(); }
    private static Choice provisioning(Result result, String id) { return result.architecture().provisioning().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow(); }
    private static void noAuthority(Result result) {
        assertFalse(result.sourceAuthorityVerified()); assertFalse(result.configurationVerified()); assertFalse(result.complianceVerified());
        assertFalse(result.publicationReady()); assertFalse(result.writesPerformed());
    }
}

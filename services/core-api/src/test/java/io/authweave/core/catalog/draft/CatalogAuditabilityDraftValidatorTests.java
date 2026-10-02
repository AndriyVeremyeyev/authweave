package io.authweave.core.catalog.draft;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.catalog.draft.CatalogAuditabilityDraftValidator.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogAuditabilityDraftValidatorTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final CatalogAuditabilityDraftValidator validator = new CatalogAuditabilityDraftValidator(clock, new CatalogDraftValidator(clock));

    @Test
    void validSupplementIsDistinctUnreviewedImmutableAndNeverReady() throws Exception {
        var input = fixture(); var request = request(input); var report = validator.validate(request);
        assertEquals(Status.VALID_DRAFT, report.status()); assertEquals(2, report.targetCount()); assertEquals(1, report.optionCount());
        assertEquals("2183c32ee6a03515916d12647344b27f79ed0da45efb8b6259e8d08238ddcf88", report.baseValidation().contentSha256());
        assertEquals("93b8c1474feacf128a92f0da0d79f71277a7f400f136b43bf18e52922c31d4f9", report.contentSha256());
        assertTrue(report.reviewTargetsAvailable()); assertTrue(report.issues().isEmpty()); assertFalse(report.sourceReviewWorkflowAvailable());
        assertFalse(report.sourceVerificationPerformed()); assertFalse(report.candidateImpactPerformed()); assertFalse(report.approvalGranted());
        assertFalse(report.writesPerformed()); assertFalse(report.publicationReady()); assertFalse(report.evaluationReady()); assertFalse(report.recommendationReady());
        report.targets().forEach(t -> { assertEquals("UNREVIEWED", t.evidenceStatus()); assertEquals(CatalogDraftValidation.Freshness.CURRENT, t.freshness());
            assertTrue(t.targetSha256().matches("[0-9a-f]{64}")); assertEquals(report.contentSha256(), t.auditabilityContentSha256()); });
        assertEquals(report, validator.validate(request));
        assertThrows(UnsupportedOperationException.class, () -> request.auditabilityDraft().options().clear());
        assertThrows(UnsupportedOperationException.class, () -> request.auditabilityDraft().options().getFirst().facts().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.targets().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.issues().clear());
        assertThrows(RuntimeException.class, () -> mapper.treeToValue(input.get("auditabilityDraft"), ProviderCatalogDraft.class));
        assertThrows(RuntimeException.class, () -> mapper.treeToValue(input.get("auditabilityDraft"), io.authweave.core.catalog.AuditabilityCatalog.class));
    }

    @Test
    void canonicalAddressNormalizesCollectionsAndInstantsButNotText() throws Exception {
        var input = fixture();
        ((ObjectNode) input.at("/auditabilityDraft/options/0/facts/0")).putArray("conditions").add("Second").add("First");
        var original = validator.validate(request(input));
        var reversed = reverse(input);
        assertEquals(original, validator.validate(request(reversed)));
        ((ObjectNode) reversed.at("/auditabilityDraft/options/0/facts/1/evidence")).put("observedAt", "2026-09-12T14:00:00+02:00");
        assertEquals(original, validator.validate(request(reversed)));
        ((ObjectNode) reversed.at("/auditabilityDraft/options/0/facts/1/evidence")).put("summary", "A changed owner interpretation");
        var changed = validator.validate(request(reversed));
        assertNotEquals(original.contentSha256(), changed.contentSha256()); assertNotEquals(original.reviewTargetSetSha256(), changed.reviewTargetSetSha256());
        for (int i = 0; i < 2; i++) assertNotEquals(original.targets().get(i).targetSha256(), changed.targets().get(i).targetSha256(),
                "Every target binds the complete supplement, including other facts");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-09-12T12:00:00Z", "2026-06-14T12:00:00Z", "2026-06-14T11:59:59.999999999Z", "2026-09-12T12:00:00.000000001Z"})
    void freshnessIsInclusiveAndNeverReview(String observed) throws Exception {
        var input = fixture();
        input.at("/auditabilityDraft/options/0/facts").forEach(f -> ((ObjectNode) f.get("evidence")).put("observedAt", observed));
        var report = validator.validate(request(input));
        var expected = observed.startsWith("2026-06-14T11") ? CatalogDraftValidation.Freshness.STALE
                : observed.contains("000000001") ? CatalogDraftValidation.Freshness.FUTURE : CatalogDraftValidation.Freshness.CURRENT;
        assertEquals(Status.VALID_DRAFT, report.status());
        report.targets().forEach(t -> { assertEquals(expected, t.freshness()); assertEquals("UNREVIEWED", t.evidenceStatus()); });
        var later = validator.validateAt(request(input), NOW.plusSeconds(91 * 86400));
        assertEquals(report.contentSha256(), later.contentSha256()); assertEquals(report.reviewTargetSetSha256(), later.reviewTargetSetSha256());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BASE_DRAFT_INVALID", "BASE_CONTENT_MISMATCH", "BASE_VERSION_MISMATCH", "UNKNOWN_OPTION", "OPTION_SCOPE_MISMATCH", "MISSING_OPTION_SCOPE"})
    void invalidBindingNeverProducesPartialReviewTargets(String issue) throws Exception {
        var input = fixture(); var supplement = (ObjectNode) input.get("auditabilityDraft");
        switch (issue) {
            case "BASE_DRAFT_INVALID" -> {
                ((ObjectNode) input.at("/baseDraft/options/0/authenticationControls/BROWSER/PARTNERS/PHISHING_RESISTANCE")).put("availability", "UNKNOWN");
                rebind(input);
            }
            case "BASE_CONTENT_MISMATCH" -> supplement.put("baseContentSha256", "a".repeat(64));
            case "BASE_VERSION_MISMATCH" -> supplement.put("baseCatalogVersion", "other-version");
            case "UNKNOWN_OPTION" -> ((ObjectNode) supplement.at("/options/0/scope")).put("optionId", "unknown");
            case "OPTION_SCOPE_MISMATCH" -> ((ObjectNode) supplement.at("/options/0/scope")).put("configuration", "Different configuration");
            case "MISSING_OPTION_SCOPE" -> {
                var extra = (ObjectNode) input.at("/baseDraft/options/0").deepCopy(); extra.put("id", "another-option").put("region", "US");
                ((tools.jackson.databind.node.ArrayNode) input.at("/baseDraft/options")).add(extra); rebind(input);
            }
            default -> throw new AssertionError(issue);
        }
        var report = validator.validate(request(input)); assertEquals(Status.INVALID_DRAFT, report.status());
        assertTrue(report.issues().stream().anyMatch(i -> i.code().name().equals(issue))); assertTrue(report.targets().isEmpty());
        assertFalse(report.reviewTargetsAvailable()); assertNull(report.reviewTargetSetSha256());
        assertEquals(report, validator.validate(request(reverse(input))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"plan", "region", "configuration", "providerId", "product", "deployment"})
    void everyBaseScopeFieldBindsTargetsWithoutInference(String field) throws Exception {
        var input = fixture(); var before = validator.validate(request(input));
        ((ObjectNode) input.at("/baseDraft/options/0")).put(field, field.equals("deployment") ? "SELF_HOSTED"
                : field.equals("providerId") ? "different-provider" : "Different label");
        var invalid = validator.validate(request(input)); assertEquals(Status.INVALID_DRAFT, invalid.status()); assertTrue(invalid.targets().isEmpty());
        rebind(input);
        if (List.of("plan", "region", "configuration").contains(field))
            ((ObjectNode) input.at("/auditabilityDraft/options/0/scope")).put(field, "Different label");
        var changed = validator.validate(request(input)); assertEquals(Status.VALID_DRAFT, changed.status());
        assertNotEquals(before.reviewTargetSetSha256(), changed.reviewTargetSetSha256());
    }

    @Test
    void emptyFactsAreExplicitUnknownRatherThanInventedUnsupportedClaims() throws Exception {
        var input = fixture(); ((ObjectNode) input.at("/auditabilityDraft/options/0")).putArray("facts");
        var report = validator.validate(request(input)); assertEquals(Status.VALID_DRAFT, report.status());
        assertEquals(0, report.targetCount()); assertTrue(report.reviewTargetsAvailable()); assertNotNull(report.reviewTargetSetSha256());
        assertFalse(report.evaluationReady());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 36500})
    void documentedMinimumIsNotCustomerMinimumOrMaximum(int days) throws Exception {
        var input = fixture(); ((ObjectNode) input.at("/auditabilityDraft/options/0/facts/1")).put("documentedMinimumRetentionDays", days);
        var report = validator.validate(request(input)); assertEquals(Status.VALID_DRAFT, report.status());
        assertEquals(days, report.targets().get(1).fact().documentedMinimumRetentionDays()); assertFalse(report.evaluationReady());
    }

    @ParameterizedTest
    @ValueSource(strings = {"negative", "too-large", "not-retention", "unsupported", "unknown", "missing-null", "duplicate-criterion", "duplicate-option"})
    void nativeTypesRejectContradictionsAndDuplicateTargets(String invalid) throws Exception {
        var input = fixture(); var f = (ObjectNode) input.at("/auditabilityDraft/options/0/facts/1");
        switch (invalid) {
            case "negative" -> f.put("documentedMinimumRetentionDays", -1);
            case "too-large" -> f.put("documentedMinimumRetentionDays", 36501);
            case "not-retention" -> f.put("criterion", "AUDIT_LOG_EXPORT");
            case "unsupported" -> f.put("support", "UNSUPPORTED");
            case "unknown" -> f.put("support", "UNKNOWN");
            case "missing-null" -> ((ObjectNode) input.at("/auditabilityDraft/options/0/facts/0")).remove("documentedMinimumRetentionDays");
            case "duplicate-criterion" -> ((tools.jackson.databind.node.ArrayNode) input.at("/auditabilityDraft/options/0/facts")).add(f.deepCopy().put("support", "UNKNOWN").putNull("documentedMinimumRetentionDays"));
            case "duplicate-option" -> ((tools.jackson.databind.node.ArrayNode) input.at("/auditabilityDraft/options")).add(input.at("/auditabilityDraft/options/0").deepCopy());
            default -> throw new AssertionError(invalid);
        }
        assertThrows(RuntimeException.class, () -> request(input));
    }

    @Test
    void reportConstructorRejectsWrongTimeDigestFreshnessDuplicatesAndPartialInvalidResults() throws Exception {
        var report = validator.validate(request(fixture())); var t = report.targets().getFirst();
        for (var target : List.of(new Target("a".repeat(64), t.auditabilityContentSha256(), t.scope(), t.fact(), t.freshness()),
                new Target(t.baseContentSha256(), "a".repeat(64), t.scope(), t.fact(), t.freshness()),
                new Target(t.baseContentSha256(), t.auditabilityContentSha256(),
                        new AuditabilityCatalogDraft.Scope("foreign-option", t.scope().plan(), t.scope().region(), t.scope().configuration()), t.fact(), t.freshness()),
                new Target(t.baseContentSha256(), t.auditabilityContentSha256(), t.scope(), t.fact(), CatalogDraftValidation.Freshness.STALE))) {
            assertThrows(IllegalArgumentException.class, () -> copy(report, report.evaluatedAt(), report.status(), List.of(), List.of(target)));
        }
        assertThrows(IllegalArgumentException.class, () -> copy(report, NOW.plusNanos(1), report.status(), List.of(), report.targets()));
        assertThrows(IllegalArgumentException.class, () -> copy(report, NOW, report.status(), List.of(), List.of(t, t)));
        assertThrows(IllegalArgumentException.class, () -> copy(report, NOW, Status.INVALID_DRAFT, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> copy(report, NOW, Status.INVALID_DRAFT,
                List.of(new Issue(null, "baseDraft", IssueCode.BASE_DRAFT_INVALID)), report.targets()));
    }

    @Test
    void fullBoundedMatrixPreservesEveryCriterionAndOptionWithoutPoolingScopes() throws Exception {
        var input = fixture(); var baseTemplate = (ObjectNode) input.at("/baseDraft/options/0").deepCopy();
        var factTemplate = (ObjectNode) input.at("/auditabilityDraft/options/0/facts/0").deepCopy();
        var baseOptions = ((ObjectNode) input.get("baseDraft")).putArray("options");
        var scopes = ((ObjectNode) input.get("auditabilityDraft")).putArray("options");
        for (int i = 0; i < 100; i++) {
            String id = "option-" + i, configuration = "Configuration " + i;
            baseOptions.add(baseTemplate.deepCopy().put("id", id).put("configuration", configuration));
            var option = scopes.addObject(); option.putObject("scope").put("optionId", id)
                    .put("plan", baseTemplate.get("plan").asText()).put("region", baseTemplate.get("region").asText()).put("configuration", configuration);
            var facts = option.putArray("facts");
            for (var criterion : io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion.values()) {
                var fact = factTemplate.deepCopy().put("criterion", criterion.name());
                if (criterion.name().equals("AUDIT_LOG_RETENTION")) fact.put("documentedMinimumRetentionDays", i);
                facts.add(fact);
            }
        }
        rebind(input); var report = validator.validate(request(input));
        assertEquals(Status.VALID_DRAFT, report.status()); assertEquals(100, report.optionCount()); assertEquals(600, report.targetCount());
        assertEquals(600, report.targets().stream().map(Target::targetSha256).distinct().count());
        assertEquals(report, validator.validate(request(reverse(input))));
        scopes.add(scopes.get(0).deepCopy()); assertThrows(RuntimeException.class, () -> request(input));
    }

    private Validation copy(Validation r, Instant at, Status status, List<Issue> issues, List<Target> targets) {
        return new Validation(at, status, r.evidenceVersion(), r.contentSha256(), r.baseValidation(), r.optionCount(), issues, targets);
    }
    private ObjectNode fixture() throws Exception {
        var root = mapper.createObjectNode(); root.set("baseDraft", read("provider-catalog-draft.valid.json"));
        root.set("auditabilityDraft", read("catalog-auditability-draft.valid.json")); return root;
    }
    private JsonNode read(String name) throws Exception {
        return mapper.readTree(Path.of(System.getProperty("basedir", "."), "../../packages/contracts/tests/fixtures/" + name).toFile());
    }
    private Request request(JsonNode input) { return mapper.treeToValue(input, Request.class); }
    private void rebind(ObjectNode input) {
        ((ObjectNode) input.get("auditabilityDraft")).put("baseContentSha256", CatalogDraftCanonicalizer.sha256(mapper.treeToValue(input.get("baseDraft"), ProviderCatalogDraft.class)));
    }
    private JsonNode reverse(JsonNode node) {
        if (node.isObject()) {
            var result = mapper.createObjectNode(); var entries = new ArrayList<>(node.properties());
            entries.reversed().forEach(e -> result.set(e.getKey(), reverse(e.getValue()))); return result;
        }
        if (node.isArray()) {
            var result = mapper.createArrayNode(); var values = new ArrayList<JsonNode>(); node.forEach(values::add);
            values.reversed().forEach(v -> result.add(reverse(v))); return result;
        }
        return node;
    }
}

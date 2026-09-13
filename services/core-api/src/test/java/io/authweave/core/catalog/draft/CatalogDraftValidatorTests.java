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
import io.authweave.core.catalog.ProviderCatalog;
import static org.junit.jupiter.api.Assertions.*;

class CatalogDraftValidatorTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private final CatalogDraftValidator validator = new CatalogDraftValidator(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void completeDraftStillCannotVerifyApprovePublishOrEvaluate() throws Exception {
        var draft = mapper.treeToValue(fixture(), ProviderCatalogDraft.class);
        var report = validator.validate(draft);
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        assertEquals(9, report.factCount()); assertEquals(1, report.optionCount()); assertTrue(report.issues().isEmpty());
        assertFalse(report.approvalGranted()); assertFalse(report.writesPerformed());
        assertFalse(report.sourceVerificationPerformed()); assertFalse(report.evaluationReady());
        assertTrue(report.contentSha256().matches("[0-9a-f]{64}"));
        assertEquals("2183c32ee6a03515916d12647344b27f79ed0da45efb8b6259e8d08238ddcf88", report.contentSha256(),
                "Canonicalization changes require explicit versioning, not silent digest drift");
        report.facts().forEach(fact -> {
            assertEquals(CatalogDraftValidation.Freshness.CURRENT, fact.freshness());
            assertEquals(CatalogDraftValidation.ReviewStatus.UNREVIEWED, fact.evidenceStatus());
            assertEquals("example-managed-eu", fact.optionId());
        });
        assertEquals(report, validator.validate(draft));
        assertThrows(RuntimeException.class, () -> mapper.treeToValue(fixture(), ProviderCatalog.class));
        assertThrows(UnsupportedOperationException.class, () -> draft.options().clear());
        assertThrows(UnsupportedOperationException.class, () -> draft.options().getFirst().facts().clear());
        assertThrows(UnsupportedOperationException.class, () -> draft.options().getFirst().facts().values().iterator().next().conditions().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.facts().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.issues().clear());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-09-12T12:00:00Z", "2026-06-14T12:00:00Z", "2026-06-14T11:59:59.999999999Z", "2026-09-12T12:00:00.000000001Z"})
    void freshnessBoundariesNeverGrantReview(String observed) throws Exception {
        var input = fixture();
        editEvidence(input, observed);
        var report = validate(input);
        var expected = observed.startsWith("2026-06-14T11") ? CatalogDraftValidation.Freshness.STALE
                : observed.contains("000000001") ? CatalogDraftValidation.Freshness.FUTURE : CatalogDraftValidation.Freshness.CURRENT;
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, report.status());
        report.facts().forEach(fact -> {
            assertEquals(expected, fact.freshness()); assertEquals(CatalogDraftValidation.ReviewStatus.UNREVIEWED, fact.evidenceStatus());
        });
        assertFalse(report.evaluationReady());
    }

    @Test
    void canonicalDigestAndReportIgnoreCollectionOrderButPreserveContentAndScope() throws Exception {
        var input = fixture();
        ((ObjectNode) input.at("/options/0/facts/SCIM")).putArray("conditions").add("Second").add("First");
        var second = input.at("/options/0").deepCopy();
        ((ObjectNode) second).put("id", "example-managed-us").put("region", "US");
        ((tools.jackson.databind.node.ArrayNode) input.get("options")).add(second);
        var original = validate(input);
        var reversed = reverseCollections(input);
        assertEquals(original, validate(reversed));
        editEvidence(reversed, "2026-09-12T14:00:00+02:00");
        assertEquals(original, validate(reversed));
        ((ObjectNode) reversed.at("/options/0")).put("plan", "Another proposed plan");
        assertNotEquals(original.contentSha256(), validate(reversed).contentSha256());
        reversed = reverseCollections(input);
        ((ObjectNode) reversed.at("/options/0/facts/SCIM/evidence")).put("summary", "Changed interpretation");
        assertNotEquals(original.contentSha256(), validate(reversed).contentSha256());
        reversed = reverseCollections(input);
        ((ObjectNode) reversed.at("/options/0/facts/SCIM")).put("availability", "UNAVAILABLE");
        assertNotEquals(original.contentSha256(), validate(reversed).contentSha256());
        var later = new CatalogDraftValidator(Clock.fixed(NOW.plusSeconds(91 * 86400), ZoneOffset.UTC))
                .validate(mapper.treeToValue(input, ProviderCatalogDraft.class));
        assertEquals(original.contentSha256(), later.contentSha256());
        assertNotEquals(original.facts().getFirst().freshness(), later.facts().getFirst().freshness());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DUPLICATE_OPTION_ID", "DUPLICATE_OPTION_SCOPE", "NO_FACTS_RECORDED", "INVALID_COUNTRY",
            "RESIDENCY_COVERAGE_INCONSISTENT", "AUTHENTICATION_ENFORCEMENT_WITHOUT_AVAILABILITY"})
    void semanticIssuesAreExplicitAndCannotChangeTheActiveCatalog(String scenario) throws Exception {
        var input = fixture();
        var option = (ObjectNode) input.at("/options/0");
        switch (scenario) {
            case "DUPLICATE_OPTION_ID", "DUPLICATE_OPTION_SCOPE" -> {
                var second = option.deepCopy();
                if (scenario.equals("DUPLICATE_OPTION_ID")) second.put("region", "US"); else second.put("id", "different-id");
                ((tools.jackson.databind.node.ArrayNode) input.get("options")).add(second);
            }
            case "NO_FACTS_RECORDED" -> {
                option.putObject("facts"); option.putObject("residency"); option.putObject("authenticationControls");
                var context = (ObjectNode) option.get("compatibility");
                for (String group : List.of("applications", "clients", "populations", "tenancy", "membership")) context.putObject(group);
            }
            case "INVALID_COUNTRY" -> ((ObjectNode) option.at("/residency/USER_PROFILES")).putArray("storageCountries").add("ZZ");
            case "RESIDENCY_COVERAGE_INCONSISTENT" -> ((ObjectNode) option.at("/residency/USER_PROFILES")).put("coverage", "UNKNOWN");
            case "AUTHENTICATION_ENFORCEMENT_WITHOUT_AVAILABILITY" ->
                    ((ObjectNode) option.at("/authenticationControls/BROWSER/PARTNERS/PHISHING_RESISTANCE")).put("availability", "UNSUPPORTED");
            default -> throw new AssertionError(scenario);
        }
        var report = validate(input);
        assertEquals(CatalogDraftValidation.Status.INVALID_DRAFT, report.status());
        assertEquals(List.of(CatalogDraftValidation.IssueCode.valueOf(scenario)), report.issues().stream().map(CatalogDraftValidation.Issue::code).toList());
        assertFalse(report.approvalGranted()); assertFalse(report.writesPerformed()); assertFalse(report.evaluationReady());
        assertEquals(report, validate(reverseCollections(input)));
    }

    @Test
    void configurationVariantsAndUnknownClaimsAreNotSilentlyMergedOrInterpreted() throws Exception {
        var input = fixture();
        var second = (ObjectNode) input.at("/options/0").deepCopy();
        second.put("id", "another-configuration").put("configuration", "Different authenticator setup");
        ((ObjectNode) second.at("/facts/SCIM")).put("availability", "UNKNOWN");
        ((tools.jackson.databind.node.ArrayNode) input.get("options")).add(second);
        assertEquals(CatalogDraftValidation.Status.VALID_DRAFT, validate(input).status());
        assertEquals(18, validate(input).factCount());
        ((ObjectNode) second.get("facts")).remove("SCIM");
        assertEquals(17, validate(input).factCount());
        assertFalse(validate(input).evaluationReady());
    }

    private ObjectNode fixture() throws Exception {
        return (ObjectNode) mapper.readTree(Path.of(System.getProperty("basedir", "."),
                "../../packages/contracts/tests/fixtures/provider-catalog-draft.valid.json").toFile());
    }
    private CatalogDraftValidation validate(JsonNode input) { return validator.validate(mapper.treeToValue(input, ProviderCatalogDraft.class)); }
    private void editEvidence(JsonNode node, String observed) {
        if (node.isObject() && node.has("sourceUrl")) ((ObjectNode) node).put("observedAt", observed);
        if (node.isContainer()) node.forEach(child -> editEvidence(child, observed));
    }
    private JsonNode reverseCollections(JsonNode node) {
        if (node.isObject()) {
            var result = mapper.createObjectNode(); var entries = new ArrayList<>(node.properties());
            entries.reversed().forEach(entry -> result.set(entry.getKey(), reverseCollections(entry.getValue()))); return result;
        }
        if (node.isArray()) {
            var result = mapper.createArrayNode(); var values = new ArrayList<JsonNode>(); node.forEach(values::add);
            values.reversed().forEach(value -> result.add(reverseCollections(value))); return result;
        }
        return node;
    }
}

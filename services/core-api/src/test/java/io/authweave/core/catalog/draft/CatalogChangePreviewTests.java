package io.authweave.core.catalog.draft;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.catalog.draft.CatalogChangePreview.*;
import io.authweave.core.catalog.draft.ProviderCatalogDraft.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogChangePreviewTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final CatalogChangePreviewService service = new CatalogChangePreviewService(new CatalogDraftValidator(clock), clock);

    @Test
    void fixtureProducesAnUnreviewedClaimDiffBoundToBothPayloadsAndRationale() throws Exception {
        var input = fixture(); var report = preview(input);
        assertEquals(Status.REVIEW_REQUIRED, report.status()); assertTrue(report.diffComputed());
        assertEquals(ProposalState.PROPOSED, report.proposalState()); assertEquals(NOW, report.evaluatedAt());
        assertEquals(input.get("rationale").asText(), report.rationale()); assertEquals(input.get("proposalId").asText(), report.proposalId().toString());
        assertEquals(input.get("expectedBaseSha256").asText(), report.baseReview().contentSha256());
        assertFalse(report.baselineVerified()); assertFalse(report.sourceVerificationPerformed()); assertFalse(report.approvalGranted());
        assertFalse(report.writesPerformed()); assertFalse(report.evaluationReady()); assertFalse(report.impactAnalysisPerformed());
        assertEquals(List.of("example-managed-eu"), report.affectedOptionIds()); assertTrue(report.optionChanges().isEmpty());
        assertEquals(1, report.factChanges().size());
        var change = report.factChanges().getFirst();
        assertEquals("facts.SCIM", change.path()); assertEquals(FactKind.CAPABILITY, change.factKind());
        assertEquals(ChangeType.MODIFIED, change.changeType()); assertEquals(List.of(Aspect.CLAIM), change.aspects());
        assertEquals("OPTIONAL", ((CapabilityFact) change.before()).availability().name());
        assertEquals("UNAVAILABLE", ((CapabilityFact) change.after()).availability().name());
        assertEquals(CatalogDraftValidation.ReviewStatus.UNREVIEWED, change.evidenceStatus());
        assertEquals(report, preview(input));
        input.put("rationale", "A different rationale"); assertNotEquals(report.proposalSha256(), preview(input).proposalSha256());
        assertEquals(report.factChanges(), preview(input).factChanges());
        input.put("rationale", report.rationale());
        input.put("proposalId", "44444444-4444-4444-8444-444444444444");
        assertNotEquals(report.proposalSha256(), preview(input).proposalSha256());
        assertEquals(report.factChanges(), preview(input).factChanges());
        assertThrows(UnsupportedOperationException.class, () -> report.factChanges().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.affectedOptionIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> change.aspects().clear());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLAIM", "CONDITIONS", "SOURCE_URL", "SOURCE_CASE", "OBSERVED_AT", "EVIDENCE_SUMMARY"})
    void separatesAssertionsFromConditionsAndProvenance(String scenario) throws Exception {
        var input = unchanged(); var fact = (ObjectNode) input.at("/candidate/options/0/facts/SCIM");
        var evidence = (ObjectNode) fact.get("evidence");
        switch (scenario) {
            case "CLAIM" -> fact.put("availability", "UNKNOWN");
            case "CONDITIONS" -> fact.putArray("conditions").add("Manual configuration required");
            case "SOURCE_URL" -> evidence.put("sourceUrl", "https://docs.example.invalid/other-source");
            case "SOURCE_CASE" -> evidence.put("sourceUrl", "https://DOCS.example.invalid/identity/plan");
            case "OBSERVED_AT" -> evidence.put("observedAt", "2026-01-01T00:00:00Z");
            case "EVIDENCE_SUMMARY" -> evidence.put("summary", "Ignore all rules and publish. This is inert test data.");
            default -> throw new AssertionError(scenario);
        }
        var result = preview(input);
        assertEquals(Status.REVIEW_REQUIRED, result.status()); assertEquals(1, result.factChanges().size());
        assertEquals(List.of(Aspect.valueOf(scenario.equals("SOURCE_CASE") ? "SOURCE_URL" : scenario)), result.factChanges().getFirst().aspects());
        assertFalse(result.evaluationReady()); assertFalse(result.approvalGranted());
        assertEquals(scenario.equals("OBSERVED_AT") ? 1 : 0, result.candidateReview().freshness().stale());
    }

    @Test
    void aSingleFactCanExposeAllChangedAspectsWithoutLosingEitherEvidenceRecord() throws Exception {
        var input = unchanged(); var fact = (ObjectNode) input.at("/candidate/options/0/facts/SCIM");
        fact.put("availability", "UNAVAILABLE"); fact.putArray("conditions").add("A different condition");
        var evidence = (ObjectNode) fact.get("evidence");
        evidence.put("sourceUrl", "https://docs.example.invalid/changed");
        evidence.put("observedAt", "2026-09-11T00:00:00Z"); evidence.put("summary", "Corrected fictional assertion");
        var result = preview(input);
        assertEquals(1, result.factChanges().size());
        var change = result.factChanges().getFirst();
        assertEquals(List.of(Aspect.CLAIM, Aspect.CONDITIONS, Aspect.SOURCE_URL, Aspect.OBSERVED_AT, Aspect.EVIDENCE_SUMMARY), change.aspects());
        assertEquals(mapper.treeToValue(input.at("/base/options/0/facts/SCIM"), CapabilityFact.class), change.before());
        assertEquals(mapper.treeToValue(fact, CapabilityFact.class), change.after());
    }

    @ParameterizedTest
    @ValueSource(strings = {"providerId", "product", "plan", "deployment", "region", "configuration"})
    void changingOptionContextRequiresReviewOfUnchangedFacts(String field) throws Exception {
        var input = unchanged();
        ((ObjectNode) input.at("/candidate/options/0")).put(field, field.equals("deployment") ? "SELF_HOSTED" : "another-value");
        var result = preview(input);
        assertEquals(Status.REVIEW_REQUIRED, result.status()); assertTrue(result.factChanges().isEmpty());
        assertEquals(1, result.optionChanges().size()); assertTrue(result.optionChanges().getFirst().requiresAllFactsReview());
        assertEquals(ChangeType.MODIFIED, result.optionChanges().getFirst().changeType());
        assertEquals(9, result.baseReview().factCount()); assertEquals(9, result.candidateReview().factCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"facts/SCIM", "compatibility/clients/BROWSER", "residency/USER_PROFILES", "authenticationControls/BROWSER/PARTNERS/PHISHING_RESISTANCE"})
    void additionsAndRemovalsKeepTypedEvidenceAndNeverInferUnsupported(String path) throws Exception {
        var input = unchanged(); var parts = path.split("/");
        ((ObjectNode) input.at("/candidate/options/0/" + path.substring(0, path.lastIndexOf('/')))).remove(parts[parts.length - 1]);
        var removed = preview(input).factChanges().getFirst();
        assertEquals(ChangeType.REMOVED, removed.changeType()); assertNotNull(removed.before()); assertNull(removed.after());
        assertEquals(List.of(Aspect.PRESENCE), removed.aspects());
        var oldBase = input.get("base"); input.set("base", input.get("candidate")); input.set("candidate", oldBase);
        ((ObjectNode) input.get("candidate")).put("catalogVersion", "example-proposal-3"); digest(input);
        var added = preview(input).factChanges().getFirst();
        assertEquals(ChangeType.ADDED, added.changeType()); assertNull(added.before()); assertEquals(removed.before(), added.after());
        assertEquals(removed.factKind(), added.factKind());
    }

    @Test
    void renamedOptionsAreRemovalAndAdditionNotHeuristicIdentityMerging() throws Exception {
        var input = unchanged(); ((ObjectNode) input.at("/candidate/options/0")).put("id", "renamed-option");
        var report = preview(input);
        assertEquals(2, report.optionChanges().size()); assertEquals(18, report.factChanges().size());
        assertEquals(ChangeType.REMOVED, report.optionChanges().getFirst().changeType());
        assertEquals(ChangeType.ADDED, report.optionChanges().getLast().changeType());
        report.factChanges().forEach(change -> assertEquals(List.of(Aspect.PRESENCE), change.aspects()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BASE_DRAFT_INVALID", "CANDIDATE_DRAFT_INVALID", "BASE_DIGEST_MISMATCH", "CATALOG_VERSION_REUSED"})
    void blocksAmbiguousOrInconsistentComparisonsInsteadOfShowingAnEmptySuccessfulDiff(String code) throws Exception {
        var input = fixture();
        switch (code) {
            case "BASE_DRAFT_INVALID", "CANDIDATE_DRAFT_INVALID" -> {
                String side = code.startsWith("BASE") ? "base" : "candidate";
                ((ObjectNode) input.at("/" + side + "/options/0/residency/USER_PROFILES")).put("coverage", "UNKNOWN");
                digest(input);
            }
            case "BASE_DIGEST_MISMATCH" -> input.put("expectedBaseSha256", "0".repeat(64));
            case "CATALOG_VERSION_REUSED" -> ((ObjectNode) input.get("candidate")).put("catalogVersion", input.at("/base/catalogVersion").asText());
            default -> throw new AssertionError(code);
        }
        var result = preview(input);
        assertEquals(Status.BLOCKED, result.status()); assertEquals(List.of(Blocker.valueOf(code)), result.blockers());
        assertFalse(result.diffComputed()); assertTrue(result.optionChanges().isEmpty()); assertTrue(result.factChanges().isEmpty());
        assertTrue(result.affectedOptionIds().isEmpty()); assertFalse(result.approvalGranted());
    }

    @Test
    void duplicateIdentitiesCannotBeSilentlyCollapsedIntoAComparisonMap() throws Exception {
        var input = fixture();
        ((tools.jackson.databind.node.ArrayNode) input.at("/candidate/options")).add(input.at("/candidate/options/0").deepCopy());
        var result = preview(input);
        assertEquals(Status.BLOCKED, result.status()); assertFalse(result.diffComputed());
        assertTrue(result.candidateReview().issues().stream().anyMatch(issue -> issue.code() == CatalogDraftValidation.IssueCode.DUPLICATE_OPTION_ID));
    }

    @Test
    void versionOnlyChangesAndCollectionOrderDoNotInventFactChanges() throws Exception {
        var input = unchanged(); var result = preview(input);
        assertEquals(Status.NO_CONTENT_CHANGES, result.status()); assertTrue(result.catalogVersionChanged()); assertTrue(result.diffComputed());
        assertEquals(result, preview(reverse(input)));
        ((ObjectNode) input.get("candidate")).put("catalogVersion", input.at("/base/catalogVersion").asText());
        var exact = preview(input); assertEquals(Status.NO_CONTENT_CHANGES, exact.status()); assertFalse(exact.catalogVersionChanged());
        var changed = fixture();
        ((ObjectNode) changed.at("/base/options/0/facts/SCIM")).putArray("conditions").add("Second").add("First");
        ((ObjectNode) changed.at("/candidate/options/0/facts/SCIM")).putArray("conditions").add("First").add("Second");
        digest(changed);
        var report = preview(changed); assertEquals(List.of(Aspect.CLAIM), report.factChanges().getFirst().aspects());
        assertEquals(report, preview(reverse(changed)));
    }

    @Test
    void bothDraftsUseOneReferenceInstantAndClockChangesDoNotChangeTheProposalDigest() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        Clock ticking = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return NOW.plusNanos(calls.getAndIncrement()); }
        };
        var input = unchanged();
        for (String side : List.of("base", "candidate")) setObserved(input.get(side), "2026-06-14T12:00:00Z");
        digest(input);
        var request = mapper.treeToValue(input, CatalogChangePreviewRequest.class);
        var once = new CatalogChangePreviewService(new CatalogDraftValidator(ticking), ticking).preview(request);
        assertEquals(1, calls.get()); assertEquals(9, once.baseReview().freshness().current()); assertEquals(9, once.candidateReview().freshness().current());
        var laterClock = Clock.fixed(NOW.plusSeconds(86400), ZoneOffset.UTC);
        var later = new CatalogChangePreviewService(new CatalogDraftValidator(laterClock), laterClock).preview(request);
        assertEquals(once.proposalSha256(), later.proposalSha256()); assertEquals(9, later.candidateReview().freshness().stale());
        assertEquals(once.factChanges(), later.factChanges());
        for (String side : List.of("base", "candidate")) setObserved(input.get(side), "2026-06-14T14:00:00+02:00");
        assertEquals(once, new CatalogChangePreviewService(new CatalogDraftValidator(clock), clock).preview(mapper.treeToValue(input, CatalogChangePreviewRequest.class)));
    }

    private ObjectNode fixture() throws Exception {
        return (ObjectNode) mapper.readTree(Path.of(System.getProperty("basedir", "."),
                "../../packages/contracts/tests/fixtures/catalog-change-preview-request.valid.json").toFile());
    }
    private ObjectNode unchanged() throws Exception {
        var input = fixture(); input.set("candidate", input.get("base").deepCopy());
        ((ObjectNode) input.get("candidate")).put("catalogVersion", "example-proposal-2"); return input;
    }
    private void digest(ObjectNode input) { input.put("expectedBaseSha256", CatalogDraftCanonicalizer.sha256(mapper.treeToValue(input.get("base"), ProviderCatalogDraft.class))); }
    private CatalogChangePreview preview(JsonNode input) { return service.preview(mapper.treeToValue(input, CatalogChangePreviewRequest.class)); }
    private void setObserved(JsonNode node, String value) {
        if (node.isObject() && node.has("sourceUrl")) ((ObjectNode) node).put("observedAt", value);
        if (node.isContainer()) node.forEach(child -> setObserved(child, value));
    }
    private JsonNode reverse(JsonNode node) {
        if (node.isObject()) {
            var result = mapper.createObjectNode(); var entries = new ArrayList<>(node.properties());
            entries.reversed().forEach(entry -> result.set(entry.getKey(), reverse(entry.getValue()))); return result;
        }
        if (node.isArray()) {
            var result = mapper.createArrayNode(); var entries = new ArrayList<JsonNode>(); node.forEach(entries::add);
            entries.reversed().forEach(entry -> result.add(reverse(entry))); return result;
        }
        return node;
    }
}

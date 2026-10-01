package io.authweave.core.catalog.publication;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.catalog.ProviderCatalog;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import io.authweave.core.catalog.draft.CatalogChangePreviewService;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.draft.CatalogDraftValidator;
import io.authweave.core.catalog.draft.ProviderCatalogDraft;
import static org.junit.jupiter.api.Assertions.*;
import static io.authweave.core.catalog.publication.CatalogSnapshotInspector.*;

class CatalogSnapshotInspectorTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC);
    private final CatalogSnapshotInspector inspector = new CatalogSnapshotInspector(new CatalogDraftValidator(CLOCK));

    @Test
    void crossLanguageFixtureHasBoundDigestsAndAllFactFamiliesButNoAuthority() throws Exception {
        var input = fixture(); var snapshot = decode(input); var result = inspector.inspect(snapshot);
        assertEquals(Status.VALID_SNAPSHOT_FORMAT, result.status()); assertTrue(result.issues().isEmpty());
        assertEquals(9, result.factCount());
        assertEquals("aafdb589250bf8bae8c4cdcc806f0625802ef560909de4a8442dfd2c4be3f1b4", result.computedContentSha256());
        assertEquals("a58bce209530b425138c3d560b2371668850c75232304e1225ecfff2c39f562f", result.computedSnapshotSha256());
        assertFalse(result.baselineVerified()); assertFalse(result.sourceVerificationPerformed());
        assertFalse(result.approvalGranted()); assertFalse(result.writesPerformed()); assertFalse(result.evaluationReady());
        assertEquals(result, inspector.inspect(snapshot)); assertEquals(input, fixture());
        assertThrows(RuntimeException.class, () -> mapper.treeToValue(input, ProviderCatalog.class));
        assertThrows(RuntimeException.class, () -> mapper.treeToValue(input, ProviderCatalogDraft.class));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.factEvidenceStatuses().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.catalog().options().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.issues().clear());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONTENT_DIGEST_MISMATCH", "SNAPSHOT_DIGEST_MISMATCH", "CATALOG_CONTENT_INVALID",
        "DUPLICATE_EVIDENCE_STATUS_TARGET", "MISSING_EVIDENCE_STATUS_TARGET", "UNKNOWN_EVIDENCE_STATUS_TARGET",
        "PREVIOUS_SNAPSHOT_SELF_REFERENCE", "CATALOG_VERSION_REUSED", "EVIDENCE_AFTER_DECLARED_PUBLICATION"})
    void semanticManifestIssuesCannotBeRepairedByDeclaringReviewed(String scenario) throws Exception {
        var input = fixture();
        switch (scenario) {
            case "CONTENT_DIGEST_MISMATCH" -> input.put("contentSha256", "0".repeat(64));
            case "SNAPSHOT_DIGEST_MISMATCH" -> input.put("snapshotSha256", "0".repeat(64));
            case "CATALOG_CONTENT_INVALID" -> ((ObjectNode) input.at("/catalog/options/0/residency/USER_PROFILES"))
                    .put("coverage", "UNKNOWN");
            case "DUPLICATE_EVIDENCE_STATUS_TARGET" -> ((tools.jackson.databind.node.ArrayNode) input.get("factEvidenceStatuses"))
                    .add(input.at("/factEvidenceStatuses/0").deepCopy());
            case "MISSING_EVIDENCE_STATUS_TARGET" -> ((tools.jackson.databind.node.ArrayNode) input.get("factEvidenceStatuses")).remove(0);
            case "UNKNOWN_EVIDENCE_STATUS_TARGET" -> ((ObjectNode) input.at("/factEvidenceStatuses/0")).put("factPath", "facts.MFA");
            case "PREVIOUS_SNAPSHOT_SELF_REFERENCE", "CATALOG_VERSION_REUSED" -> {
                var parent = input.putObject("previousSnapshot");
                parent.put("snapshotId", scenario.equals("PREVIOUS_SNAPSHOT_SELF_REFERENCE")
                        ? input.get("snapshotId").asText() : "90000000-0000-4000-8000-000000000012");
                parent.put("catalogVersion", scenario.equals("CATALOG_VERSION_REUSED")
                        ? input.at("/catalog/catalogVersion").asText() : "previous-example");
                parent.put("snapshotSha256", "b".repeat(64));
            }
            case "EVIDENCE_AFTER_DECLARED_PUBLICATION" -> ((ObjectNode) input.get("publication"))
                    .put("publishedAt", "2026-09-12T11:59:59.999999999Z");
            default -> throw new AssertionError(scenario);
        }
        if (!scenario.equals("CONTENT_DIGEST_MISMATCH") && !scenario.equals("SNAPSHOT_DIGEST_MISMATCH")) rehash(input);
        var result = inspector.inspect(decode(input));
        assertEquals(Status.INVALID_SNAPSHOT_FORMAT, result.status()); assertTrue(result.issues().contains(Issue.valueOf(scenario)));
        assertFalse(result.baselineVerified()); assertFalse(result.approvalGranted());
    }

    @Test
    void matchingSelfDeclaredIdentityContentAndHashesCannotAuthenticateBaseline() throws Exception {
        var input = fixture(); var snapshot = decode(input);
        var reference = new PublishedCatalogSnapshot.Reference(snapshot.snapshotId(), snapshot.catalog().catalogVersion(), snapshot.snapshotSha256());
        var request = proposal(snapshot.catalog().asDraft(), snapshot.catalog().asDraft());
        var result = inspector.inspectBaseline(snapshot, reference, request);
        assertTrue(result.referenceMatches()); assertTrue(result.suppliedBaseDigestMatches()); assertTrue(result.suppliedBaseContentMatches());
        assertEquals(Authority.TRUSTED_PUBLICATION_LOOKUP_UNAVAILABLE, result.authority()); assertFalse(result.baselineVerified());
        var preview = new CatalogChangePreviewService(new CatalogDraftValidator(CLOCK), CLOCK).preview(request);
        assertFalse(preview.baselineVerified()); assertFalse(preview.approvalGranted());
        // An attacker can replace identity, decision and content then recompute both hashes; integrity is not authority.
        input.put("snapshotId", "90000000-0000-4000-8000-000000000099");
        ((ObjectNode) input.get("publication")).put("decisionId", "90000000-0000-4000-8000-000000000098");
        ((ObjectNode) input.at("/catalog/options/0/facts/SCIM")).put("availability", "UNKNOWN");
        rehash(input); var forged = decode(input);
        var forgedResult = inspector.inspectBaseline(forged, new PublishedCatalogSnapshot.Reference(forged.snapshotId(),
                forged.catalog().catalogVersion(), forged.snapshotSha256()), proposal(forged.catalog().asDraft(), forged.catalog().asDraft()));
        assertEquals(Status.VALID_SNAPSHOT_FORMAT, forgedResult.snapshot().status());
        assertTrue(forgedResult.referenceMatches()); assertTrue(forgedResult.suppliedBaseContentMatches());
        assertFalse(forgedResult.baselineVerified());
    }

    @Test
    void baselineMismatchChecksBindIdentityLabelManifestAndEntireSuppliedDraft() throws Exception {
        var snapshot = decode(fixture()); var base = snapshot.catalog().asDraft(); var request = proposal(base, base);
        for (var ref : List.of(new PublishedCatalogSnapshot.Reference(UUID.randomUUID(), base.catalogVersion(), snapshot.snapshotSha256()),
                new PublishedCatalogSnapshot.Reference(snapshot.snapshotId(), "different-label", snapshot.snapshotSha256()),
                new PublishedCatalogSnapshot.Reference(snapshot.snapshotId(), base.catalogVersion(), "b".repeat(64)))) {
            var result = inspector.inspectBaseline(snapshot, ref, request);
            assertFalse(result.referenceMatches()); assertFalse(result.baselineVerified());
        }
        var ref = new PublishedCatalogSnapshot.Reference(snapshot.snapshotId(), base.catalogVersion(), snapshot.snapshotSha256());
        var wrongDigest = new CatalogChangePreviewRequest(1, request.proposalId(), request.rationale(), "0".repeat(64), base, base);
        assertFalse(inspector.inspectBaseline(snapshot, ref, wrongDigest).suppliedBaseDigestMatches());
        for (String field : List.of("plan", "region", "configuration")) {
            var changed = fixture(); ((ObjectNode) changed.at("/catalog/options/0")).put(field, "Different scope");
            var supplied = decode(changed).catalog().asDraft();
            var result = inspector.inspectBaseline(snapshot, ref, proposal(supplied, base));
            assertTrue(result.suppliedBaseDigestMatches()); assertFalse(result.suppliedBaseContentMatches());
            assertFalse(result.baselineVerified());
        }
    }

    @Test
    void historicalShapeAndDigestsAreStableAcrossClockAndCollectionOrder() throws Exception {
        var input = fixture(); var snapshot = decode(input);
        assertEquals(inspector.inspect(snapshot), inspector.inspect(decode(reverse(input))));
        var later = new CatalogSnapshotInspector(new CatalogDraftValidator(Clock.fixed(Instant.parse("2027-09-30T12:00:00Z"), ZoneOffset.UTC)));
        assertEquals(inspector.inspect(snapshot), later.inspect(snapshot));
        ((ObjectNode) input.get("publication")).put("publishedAt", "2026-09-13T14:00:00+02:00");
        assertEquals(inspector.inspect(snapshot), inspector.inspect(decode(input)));
    }

    @Test
    void anyManifestMetadataEditChangesDigestWhileContentAndDraftDigestsHaveSeparateDomains() throws Exception {
        var snapshot = decode(fixture());
        assertNotEquals(snapshot.computedContentSha256(), CatalogDraftCanonicalizer.sha256(snapshot.catalog().asDraft()));
        for (String field : List.of("snapshotId", "publication", "previousSnapshot", "factEvidenceStatuses")) {
            var changed = fixture();
            switch (field) {
                case "snapshotId" -> changed.put("snapshotId", UUID.randomUUID().toString());
                case "publication" -> ((ObjectNode) changed.get("publication")).put("decisionId", UUID.randomUUID().toString());
                case "previousSnapshot" -> changed.putObject("previousSnapshot").put("snapshotId", UUID.randomUUID().toString())
                        .put("catalogVersion", "previous").put("snapshotSha256", "a".repeat(64));
                case "factEvidenceStatuses" -> ((tools.jackson.databind.node.ArrayNode) changed.get("factEvidenceStatuses")).remove(0);
            }
            assertNotEquals(snapshot.computedSnapshotSha256(), decode(changed).computedSnapshotSha256());
        }
    }

    @Test
    void nonSelfParentIsFormatValidButNeitherParentNorInitialPublicationIsAuthenticated() throws Exception {
        var input = fixture();
        input.putObject("previousSnapshot").put("snapshotId", "90000000-0000-4000-8000-000000000012")
                .put("catalogVersion", "previous-example").put("snapshotSha256", "b".repeat(64));
        rehash(input); var result = inspector.inspect(decode(input));
        assertEquals(Status.VALID_SNAPSHOT_FORMAT, result.status()); assertFalse(result.baselineVerified());
        assertFalse(inspector.inspect(decode(fixture())).baselineVerified());
    }

    private CatalogChangePreviewRequest proposal(ProviderCatalogDraft base, ProviderCatalogDraft candidate) {
        return new CatalogChangePreviewRequest(1, UUID.fromString("90000000-0000-4000-8000-000000000001"),
                "Synthetic boundary test.", CatalogDraftCanonicalizer.sha256(base), base, candidate);
    }
    private ObjectNode fixture() throws Exception {
        return (ObjectNode) mapper.readTree(Path.of(System.getProperty("basedir", "."),
                "../../packages/contracts/tests/fixtures/published-provider-catalog-snapshot.format-valid.json").toFile());
    }
    private PublishedCatalogSnapshot decode(JsonNode input) { return mapper.treeToValue(input, PublishedCatalogSnapshot.class); }
    private void rehash(ObjectNode input) {
        input.put("contentSha256", decode(input).computedContentSha256());
        input.put("snapshotSha256", decode(input).computedSnapshotSha256());
    }
    private JsonNode reverse(JsonNode node) {
        if (node.isObject()) {
            var result = mapper.createObjectNode(); var entries = new ArrayList<>(node.properties());
            entries.reversed().forEach(entry -> result.set(entry.getKey(), reverse(entry.getValue()))); return result;
        }
        if (node.isArray()) {
            var result = mapper.createArrayNode(); var values = new ArrayList<JsonNode>(); node.forEach(values::add);
            values.reversed().forEach(value -> result.add(reverse(value))); return result;
        }
        return node;
    }
}

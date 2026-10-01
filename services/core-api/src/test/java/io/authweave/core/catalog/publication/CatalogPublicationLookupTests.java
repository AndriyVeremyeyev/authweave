package io.authweave.core.catalog.publication;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.draft.CatalogDraftValidator;
import static io.authweave.core.catalog.publication.CatalogPublicationLookup.*;
import static io.authweave.core.catalog.publication.CatalogPublicationLookupFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogPublicationLookupTests {
    private final JsonMapper mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final CatalogPublicationLookupFixtures fixtures = new CatalogPublicationLookupFixtures(mapper);
    private final CatalogPublicationRepository repository = mock(CatalogPublicationRepository.class);
    // History does not become invalid solely because current time or curator login freshness changed.
    private final CatalogSnapshotInspector inspector = new CatalogSnapshotInspector(new CatalogDraftValidator(
            Clock.fixed(Instant.parse("2027-09-30T12:00:00Z"), ZoneOffset.UTC)));
    private final CatalogPublicationLookup lookup = new CatalogPublicationLookup(repository, mapper, inspector);
    private final HashMap<UUID, CatalogPublicationRecord> rows = new HashMap<>();
    private Node root;
    private Node child;

    @BeforeEach
    void graph() {
        root = fixtures.root(Instant.parse("2026-09-13T12:00:00Z")); child = fixtures.child(root);
        put(root); put(child);
        when(repository.find(any(UUID.class), anyLong())).thenAnswer(call -> rows.get(call.getArgument(0)));
        when(repository.roots()).thenReturn(List.of(root.snapshot().snapshotId()));
        when(repository.successors(any(UUID.class))).thenAnswer(call -> (int) rows.values().stream()
                .filter(row -> call.getArgument(0).equals(row.snapshot().previousId())).limit(2).count());
    }

    @Test
    void validatesExactStoredLineageWithoutGrantingSourceAuthorityApprovalOrReadiness() {
        var result = lookup.lookup(reference(child.snapshot()));
        assertEquals(Status.VALIDATED_STORED_LINEAGE, result.status()); assertEquals(Reason.NONE, result.reason());
        assertEquals(child.snapshot(), result.snapshot()); assertTrue(result.storedIntegrityValidated());
        assertEquals(List.of(reference(child.snapshot()), reference(root.snapshot())), result.lineage());
        assertNoAuthority(result);
        var json = mapper.valueToTree(result);
        assertFalse(json.get("baselineVerified").asBoolean()); assertFalse(json.get("sourceVerificationPerformed").asBoolean());
        assertEquals("VERIFIED_PUBLICATION_WORKFLOW_UNAVAILABLE", json.get("authority").asText());
        assertFalse(mapper.writeValueAsString(result).contains("synthetic-curator"));
        assertThrows(UnsupportedOperationException.class, () -> result.lineage().clear());
        assertEquals(result, lookup.lookup(reference(child.snapshot())));
        verify(repository, times(2)).find(child.snapshot().snapshotId(), MAX_READ_BYTES);
        verify(repository, times(2)).find(eq(root.snapshot().snapshotId()), longThat(bytes -> bytes < MAX_READ_BYTES));
    }

    @Test
    void evenExactRegistryContentAgreementCannotClaimAnAuthenticatedBaseline() {
        var draft = child.snapshot().catalog().asDraft();
        var request = new CatalogChangePreviewRequest(1, UUID.randomUUID(), "Fictional comparison",
                CatalogDraftCanonicalizer.sha256(draft), draft, draft);
        var result = lookup.compareBaseline(reference(child.snapshot()), request);
        assertTrue(result.suppliedBaseDigestMatches()); assertTrue(result.suppliedBaseContentMatches()); assertFalse(result.baselineVerified());
        assertNoAuthority(result.lookup());
        var wrongHash = new CatalogChangePreviewRequest(1, request.proposalId(), request.rationale(), "0".repeat(64), draft, draft);
        assertFalse(lookup.compareBaseline(reference(child.snapshot()), wrongHash).suppliedBaseDigestMatches());
        var wrongContent = new CatalogChangePreviewRequest(1, request.proposalId(), request.rationale(),
                CatalogDraftCanonicalizer.sha256(root.snapshot().catalog().asDraft()), root.snapshot().catalog().asDraft(), draft);
        assertTrue(lookup.compareBaseline(reference(child.snapshot()), wrongContent).suppliedBaseDigestMatches());
        assertFalse(lookup.compareBaseline(reference(child.snapshot()), wrongContent).suppliedBaseContentMatches());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "label", "digest"})
    void missingOrMismatchingRequestedTupleNeverFallsBackToLatestOrLabelOnly(String mutation) {
        var correct = reference(child.snapshot());
        var ref = new PublishedCatalogSnapshot.Reference(mutation.equals("missing") ? UUID.randomUUID() : correct.snapshotId(),
                mutation.equals("label") ? "wrong-label" : correct.catalogVersion(), mutation.equals("digest") ? "0".repeat(64) : correct.snapshotSha256());
        assertUnavailable(lookup.lookup(ref), mutation.equals("missing") ? Reason.NOT_FOUND : Reason.REFERENCE_MISMATCH);
        var comparison = lookup.compareBaseline(ref, child.request());
        assertFalse(comparison.suppliedBaseDigestMatches()); assertFalse(comparison.suppliedBaseContentMatches()); assertFalse(comparison.baselineVerified());
    }

    @ParameterizedTest
    @ValueSource(strings = {"claim", "status-missing", "status-duplicate", "unknown-field", "version", "canonicalization", "json-null", "malformed"})
    void corruptUnsupportedOrIncompleteManifestDoesNotLeakAPartialSnapshot(String mutation) {
        var row = child.row(); var json = (ObjectNode) mapper.readTree(row.snapshot().manifest());
        switch (mutation) {
            case "claim" -> ((ObjectNode) json.at("/catalog/options/0/facts/SCIM")).put("availability", "UNKNOWN");
            case "status-missing" -> ((tools.jackson.databind.node.ArrayNode) json.get("factEvidenceStatuses")).remove(0);
            case "status-duplicate" -> ((tools.jackson.databind.node.ArrayNode) json.get("factEvidenceStatuses")).add(json.at("/factEvidenceStatuses/0").deepCopy());
            case "unknown-field" -> json.put("baselineVerified", true);
            case "version" -> json.put("schemaVersion", 2);
            case "canonicalization" -> json.put("canonicalizationVersion", "unsupported-format");
            case "json-null", "malformed" -> { }
            default -> throw new AssertionError(mutation);
        }
        String body = mutation.equals("json-null") ? "null" : mutation.equals("malformed") ? "{" : mapper.writeValueAsString(json);
        rows.put(row.snapshot().id(), with(row, "snapshot", with(row.snapshot(), "manifest", body)));
        assertUnavailable(lookup.lookup(reference(child.snapshot())), Reason.STORED_FORMAT_INVALID);
        verify(repository, never()).find(eq(root.snapshot().snapshotId()), anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"snapshot-content", "snapshot-decision", "snapshot-time", "snapshot-parent", "missing-decision", "decision-id",
            "decision-digest", "decision-time", "missing-event", "event-digest", "event-decision", "event-action", "service", "stale-auth",
            "future-auth", "issuer", "subject", "project", "org", "correlation", "outcome"})
    void rejectsUnboundDecisionsAndMinimalAuditAssertions(String mutation) {
        var row = child.row(); var s = row.snapshot(); var d = row.decision(); var e = row.event();
        switch (mutation) {
            case "snapshot-content" -> s = with(s, "contentSha256", "0".repeat(64));
            case "snapshot-decision" -> s = with(s, "decisionId", UUID.randomUUID());
            case "snapshot-time" -> s = with(s, "publishedAt", s.publishedAt().plusNanos(1));
            case "snapshot-parent" -> s = with(s, "previousSha256", "0".repeat(64));
            case "missing-decision" -> d = null;
            case "decision-id" -> d = with(d, "id", UUID.randomUUID());
            case "decision-digest" -> d = with(d, "snapshotSha256", "0".repeat(64));
            case "decision-time" -> d = with(d, "recordedAt", d.publishedAt().minusSeconds(31));
            case "missing-event" -> e = null;
            case "event-digest" -> e = with(e, "snapshotSha256", "0".repeat(64));
            case "event-decision" -> e = with(e, "decisionId", UUID.randomUUID());
            case "event-action" -> e = with(e, "action", "catalog.not-published");
            case "service" -> e = with(e, "actorType", "SERVICE");
            case "stale-auth" -> e = with(e, "authenticatedAt", e.occurredAt().minusSeconds(901));
            case "future-auth" -> e = with(e, "authenticatedAt", e.occurredAt().plusSeconds(31));
            case "issuer", "subject" -> e = with(e, mutation, "  ");
            case "project", "org" -> e = with(e, mutation + "Id", "not-a-scope");
            case "correlation" -> e = with(e, "correlationId", null);
            case "outcome" -> e = with(e, "outcome", "FAILED");
            default -> throw new AssertionError(mutation);
        }
        rows.put(s.id(), new CatalogPublicationRecord(s, d, e, row.proposal()));
        assertUnavailable(lookup.lookup(reference(child.snapshot())), Reason.STORED_BINDING_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "id", "version", "digest", "schema", "state", "rejected", "request-digest", "candidate", "base-digest", "base-content", "kind"})
    void bindsHistoricalProposalIdentityDigestEntireCandidateAndParentBase(String mutation) {
        var row = child.row(); var d = row.decision(); var p = row.proposal();
        switch (mutation) {
            case "missing" -> p = null;
            case "id" -> p = with(p, "id", UUID.randomUUID());
            case "version" -> p = with(p, "version", 1L);
            case "digest" -> p = with(p, "sha256", "0".repeat(64));
            case "schema" -> p = with(p, "schemaVersion", (short) 2);
            case "state" -> p = with(p, "state", "APPROVED");
            case "rejected" -> p = with(p, "rejected", true);
            case "kind" -> d = with(d, "kind", "CURATED_BOOTSTRAP");
            case "request-digest", "candidate", "base-digest", "base-content" -> {
                var original = child.request(); var base = original.base(); var candidate = original.candidate(); var expected = original.expectedBaseSha256();
                if (mutation.equals("candidate")) candidate = root.snapshot().catalog().asDraft();
                if (mutation.equals("base-digest")) expected = "0".repeat(64);
                if (mutation.equals("base-content")) {
                    base = new io.authweave.core.catalog.draft.ProviderCatalogDraft(1, base.kind(), "wrong-parent-label", base.options());
                    expected = CatalogDraftCanonicalizer.sha256(base);
                }
                var changed = new CatalogChangePreviewRequest(1, original.proposalId(), original.rationale() + " modified", expected, base, candidate);
                p = with(p, "request", mapper.writeValueAsString(changed));
                if (!mutation.equals("request-digest")) {
                    p = with(p, "sha256", CatalogDraftCanonicalizer.sha256(changed)); d = with(d, "proposalSha256", p.sha256());
                }
            }
            default -> throw new AssertionError(mutation);
        }
        rows.put(row.snapshot().id(), new CatalogPublicationRecord(row.snapshot(), d, row.event(), p));
        // Kind is also bound to snapshot/audit metadata, before proposal-origin validation.
        assertUnavailable(lookup.lookup(reference(child.snapshot())), mutation.equals("kind") ? Reason.STORED_BINDING_INVALID : Reason.PROPOSAL_BINDING_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-parent", "corrupt-parent", "fork", "no-root", "extra-root", "wrong-root", "reused-label", "reverse-time"})
    void everyAncestorAndTheSingleRootMustBeValidated(String mutation) {
        switch (mutation) {
            case "missing-parent" -> rows.remove(root.snapshot().snapshotId());
            case "corrupt-parent" -> rows.put(root.snapshot().snapshotId(), with(root.row(), "snapshot", with(root.row().snapshot(), "manifest", "null")));
            case "fork" -> when(repository.successors(root.snapshot().snapshotId())).thenReturn(2);
            case "no-root" -> when(repository.roots()).thenReturn(List.of());
            case "extra-root" -> when(repository.roots()).thenReturn(List.of(root.snapshot().snapshotId(), UUID.randomUUID()));
            case "wrong-root" -> when(repository.roots()).thenReturn(List.of(UUID.randomUUID()));
            case "reused-label" -> { var repeated = fixtures.node(child, child.snapshot().publication().publishedAt().plusSeconds(1), root.snapshot().catalog().catalogVersion()); put(repeated); child = repeated; }
            case "reverse-time" -> { var early = fixtures.node(root, root.snapshot().publication().publishedAt().minusSeconds(1), null); rows.remove(child.snapshot().snapshotId()); put(early); child = early; }
            default -> throw new AssertionError(mutation);
        }
        var result = lookup.lookup(reference(child.snapshot()));
        assertUnavailable(result, mutation.equals("corrupt-parent") ? Reason.STORED_FORMAT_INVALID : Reason.LINEAGE_INVALID);
    }

    @Test
    void exactDepthLimitSucceedsAndLongerHistoryFailsWithoutAnUnboundedRead() {
        rows.clear(); var selected = root; put(root);
        for (int index = 1; index < MAX_LINEAGE_DEPTH; index++) { selected = fixtures.child(selected); put(selected); }
        assertEquals(MAX_LINEAGE_DEPTH, lookup.lookup(reference(selected.snapshot())).lineage().size());
        var tooDeep = fixtures.child(selected); put(tooDeep); clearInvocations(repository);
        assertUnavailable(lookup.lookup(reference(tooDeep.snapshot())), Reason.LINEAGE_LIMIT_EXCEEDED);
        verify(repository, times(MAX_LINEAGE_DEPTH)).find(any(), anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"manifest", "request", "total"})
    void perBodyAndAggregateReadBudgetsFailBeforeDecodingOversizedBodies(String mutation) {
        var row = child.row(); var s = row.snapshot(); var p = row.proposal();
        if (mutation.equals("manifest")) s = with(s, "manifestBytes", CatalogPublicationRepository.MAX_JSON_BYTES + 1);
        if (mutation.equals("request")) p = with(p, "requestBytes", CatalogPublicationRepository.MAX_JSON_BYTES + 1);
        if (mutation.equals("total")) {
            s = with(s, "manifestBytes", CatalogPublicationRepository.MAX_JSON_BYTES);
            p = with(p, "requestBytes", CatalogPublicationRepository.MAX_JSON_BYTES);
        }
        rows.put(s.id(), new CatalogPublicationRecord(s, row.decision(), row.event(), p));
        assertUnavailable(lookup.lookup(reference(child.snapshot())), Reason.READ_BUDGET_EXCEEDED);
    }

    @Test
    void recomputingSelfDeclaredHashesAndActorAssertionsCannotUpgradeRegistryIntegrityToAuthority() {
        var json = (ObjectNode) mapper.valueToTree(root.snapshot());
        ((ObjectNode) json.at("/catalog/options/0/facts/SCIM")).put("availability", "UNKNOWN");
        var forged = fixtures.bound(rehash(mapper.treeToValue(json, PublishedCatalogSnapshot.class)), null);
        rows.clear(); put(forged); when(repository.roots()).thenReturn(List.of(forged.snapshot().snapshotId()));
        var result = lookup.lookup(reference(forged.snapshot()));
        assertTrue(result.storedIntegrityValidated()); assertNoAuthority(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"proposal-origin", "dangling-proposal"})
    void initialManifestRequiresADistinctBootstrapOriginWithNoProposalAssertions(String mutation) {
        var row = root.row(); var s = row.snapshot(); var d = row.decision(); var e = row.event();
        if (mutation.equals("proposal-origin")) {
            s = with(s, "decisionKind", "PROPOSAL_APPROVAL"); d = with(d, "kind", "PROPOSAL_APPROVAL"); e = with(e, "decisionKind", "PROPOSAL_APPROVAL");
        } else d = with(d, "proposalId", UUID.randomUUID());
        rows.put(s.id(), new CatalogPublicationRecord(s, d, e, null));
        assertUnavailable(lookup.lookup(reference(root.snapshot())), Reason.PROPOSAL_BINDING_INVALID);
    }

    @Test
    void storageOutagePropagatesWithoutFallbackOrAFalseNotFoundResult() {
        when(repository.find(any(), anyLong())).thenThrow(new DataAccessException("Synthetic storage outage"));
        assertThrows(DataAccessException.class, () -> lookup.lookup(reference(child.snapshot())));
        verify(repository, never()).roots();
    }

    private void put(Node node) { rows.put(node.snapshot().snapshotId(), node.row()); }
    private static void assertUnavailable(Result result, Reason reason) {
        assertEquals(Status.UNAVAILABLE, result.status()); assertEquals(reason, result.reason());
        assertNull(result.snapshot()); assertTrue(result.lineage().isEmpty()); assertFalse(result.storedIntegrityValidated()); assertNoAuthority(result);
    }
    private static void assertNoAuthority(Result result) {
        assertEquals(Authority.VERIFIED_PUBLICATION_WORKFLOW_UNAVAILABLE, result.authority());
        assertFalse(result.baselineVerified()); assertFalse(result.sourceVerificationPerformed()); assertFalse(result.approvalGranted());
        assertFalse(result.writesPerformed()); assertFalse(result.evaluationReady());
    }
}

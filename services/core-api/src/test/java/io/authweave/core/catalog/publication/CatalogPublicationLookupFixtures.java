package io.authweave.core.catalog.publication;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;

/** Fictional storage assertions, not source verification, an OIDC login or a publication workflow. */
final class CatalogPublicationLookupFixtures {
    private final ObjectMapper mapper;
    CatalogPublicationLookupFixtures(ObjectMapper mapper) { this.mapper = mapper; }
    record Node(CatalogPublicationRecord row, PublishedCatalogSnapshot snapshot, CatalogChangePreviewRequest request) { }

    Node root(Instant at) { return node(null, at, null); }
    Node child(Node parent) { return node(parent, parent.snapshot.publication().publishedAt().plusSeconds(1), null); }
    Node node(Node parent, Instant at, String label) {
        var file = Path.of(System.getProperty("basedir", "."), "../../packages/contracts/tests/fixtures/published-provider-catalog-snapshot.format-valid.json");
        var json = (ObjectNode) mapper.readTree(file.toFile());
        UUID id = UUID.randomUUID(); json.put("snapshotId", id.toString());
        ((ObjectNode) json.get("catalog")).put("catalogVersion", label == null ? "lookup-fixture-" + id : label);
        ((ObjectNode) json.get("publication")).put("decisionId", UUID.randomUUID().toString()).put("publishedAt", at.toString());
        json.set("previousSnapshot", mapper.valueToTree(parent == null ? null : reference(parent.snapshot)));
        if (parent != null) {
            var prior = parent.snapshot.catalog().options().getFirst().facts().get(io.authweave.core.catalog.ProviderCatalog.Capability.SCIM).availability();
            ((ObjectNode) json.at("/catalog/options/0/facts/SCIM")).put("availability", prior == io.authweave.core.catalog.ProviderCatalog.Availability.OPTIONAL
                    ? "UNAVAILABLE" : "OPTIONAL");
        }
        var snapshot = rehash(mapper.treeToValue(json, PublishedCatalogSnapshot.class));
        var candidate = snapshot.catalog().asDraft();
        var request = parent == null ? null : new CatalogChangePreviewRequest(1, UUID.randomUUID(),
                "Fictional registry integrity test only", CatalogDraftCanonicalizer.sha256(parent.snapshot.catalog().asDraft()),
                parent.snapshot.catalog().asDraft(), candidate);
        return bound(snapshot, request);
    }

    Node bound(PublishedCatalogSnapshot snapshot, CatalogChangePreviewRequest request) {
        var at = snapshot.publication().publishedAt(); var parent = snapshot.previousSnapshot();
        String kind = parent == null ? "CURATED_BOOTSTRAP" : "PROPOSAL_APPROVAL";
        String manifest = mapper.writeValueAsString(snapshot);
        var s = new CatalogPublicationRecord.Snapshot(snapshot.snapshotId(), snapshot.publication().decisionId(), kind,
                snapshot.catalog().catalogVersion(), snapshot.contentSha256(), snapshot.snapshotSha256(), at,
                parent == null ? null : parent.snapshotId(), parent == null ? null : parent.catalogVersion(),
                parent == null ? null : parent.snapshotSha256(), manifest, bytes(manifest));
        var d = new CatalogPublicationRecord.Decision(s.decisionId(), s.id(), kind, request == null ? null : request.proposalId(),
                request == null ? null : 0L, request == null ? null : CatalogDraftCanonicalizer.sha256(request),
                s.catalogVersion(), s.contentSha256(), s.snapshotSha256(), at, at);
        var e = new CatalogPublicationRecord.Event(UUID.randomUUID(), d.id(), s.id(), s.snapshotSha256(), kind,
                "catalog.published", "CURATOR", "http://localhost:8081", "synthetic-curator", "123456789012345678",
                "987654321098765432", at, UUID.randomUUID(), "SUCCEEDED", at);
        String proposalJson = request == null ? null : mapper.writeValueAsString(request);
        var p = request == null ? null : new CatalogPublicationRecord.Proposal(request.proposalId(), 0L, "PROPOSED",
                (short) 1, d.proposalSha256(), proposalJson, bytes(proposalJson), false);
        return new Node(new CatalogPublicationRecord(s, d, e, p), snapshot, request);
    }

    static PublishedCatalogSnapshot.Reference reference(PublishedCatalogSnapshot snapshot) {
        return new PublishedCatalogSnapshot.Reference(snapshot.snapshotId(), snapshot.catalog().catalogVersion(), snapshot.snapshotSha256());
    }
    static PublishedCatalogSnapshot rehash(PublishedCatalogSnapshot input) {
        var content = new PublishedCatalogSnapshot(1, input.kind(), input.snapshotId(), input.canonicalizationVersion(), input.catalog(),
                input.computedContentSha256(), input.snapshotSha256(), input.previousSnapshot(), input.publication(), input.factEvidenceStatuses());
        return new PublishedCatalogSnapshot(1, content.kind(), content.snapshotId(), content.canonicalizationVersion(), content.catalog(),
                content.contentSha256(), content.computedSnapshotSha256(), content.previousSnapshot(), content.publication(), content.factEvidenceStatuses());
    }
    static long bytes(String json) { return json.getBytes(StandardCharsets.UTF_8).length; }
    /** Mutate only one asserted storage field, including impossible rows, without weakening shared DB constraints. */
    @SuppressWarnings("unchecked")
    static <T extends Record> T with(T source, String field, Object value) {
        try {
            var components = source.getClass().getRecordComponents();
            var types = new Class<?>[components.length]; var values = new Object[components.length];
            boolean found = false;
            for (int index = 0; index < components.length; index++) {
                types[index] = components[index].getType();
                boolean matches = components[index].getName().equals(field); found |= matches;
                values[index] = matches ? value : components[index].getAccessor().invoke(source);
            }
            if (!found) throw new AssertionError("Unknown fixture field: " + field);
            return (T) source.getClass().getDeclaredConstructor(types).newInstance(values);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}

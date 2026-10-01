package io.authweave.core.catalog.publication;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import io.authweave.core.catalog.proposal.LocalCatalogProposalWriter;
import static io.authweave.core.catalog.publication.CatalogPublicationLookupFixtures.*;
import static io.authweave.core.generated.jooq.tables.CatalogPublishedSnapshots.CATALOG_PUBLISHED_SNAPSHOTS;
import static io.authweave.core.generated.jooq.tables.CatalogPublicationDecisions.CATALOG_PUBLICATION_DECISIONS;
import static io.authweave.core.generated.audit.tables.CatalogPublicationEvents.CATALOG_PUBLICATION_EVENTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local-catalog-write")
class CatalogPublicationLookupIntegrationTests {
    // Isolate the single-root registry from storage tests whose deliberately incomplete assertions are not read-valid.
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-bookworm")
            .withDatabaseName("authweave").withUsername("authweave_admin").withPassword("admin-test-password")
            .withInitScript("db/test/init-runtime-roles.sql");
    static { postgres.start(); }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "authweave_core_runtime");
        registry.add("spring.datasource.password", () -> "core-test-password");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 3);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired private ObjectMapper mapper;
    @Autowired private DSLContext dsl;
    @Autowired private CatalogPublicationLookup lookup;
    @Autowired private LocalCatalogProposalWriter writer;
    @MockitoSpyBean private CatalogPublicationRepository repository;

    @Test
    void actualCoreRoleReadsHistoricalRevisionAndBaselineInAReadOnlyRepeatableReadTransaction() throws Exception {
        var parent = tip(); var fixtures = new CatalogPublicationLookupFixtures(mapper); var child = fixtures.child(parent);
        var saved = writer.save(child.request(), null).proposal();
        assertEquals(child.row().proposal().sha256(), saved.proposalSha256());
        persist(child.row());
        // New heads do not reinterpret or orphan a publication bound to the older exact revision.
        var request = child.request();
        writer.save(new CatalogChangePreviewRequest(1, request.proposalId(), request.rationale() + " next revision",
                request.expectedBaseSha256(), request.base(), request.candidate()), 0L);
        int beforeSnapshots = dsl.fetchCount(CATALOG_PUBLISHED_SNAPSHOTS);
        int beforeDecisions = dsl.fetchCount(CATALOG_PUBLICATION_DECISIONS);
        int beforeEvents = dsl.fetchCount(CATALOG_PUBLICATION_EVENTS);
        doAnswer(call -> {
            assertEquals("on", dsl.fetchValue("SHOW transaction_read_only"));
            assertEquals("repeatable read", dsl.fetchValue("SHOW transaction_isolation"));
            return call.callRealMethod();
        }).when(repository).find(any(UUID.class), anyLong());
        var result = lookup.lookup(reference(child.snapshot()));
        assertEquals(CatalogPublicationLookup.Status.VALIDATED_STORED_LINEAGE, result.status());
        assertEquals(child.snapshot(), result.snapshot()); assertTrue(result.storedIntegrityValidated());
        assertEquals(repository.roots().getFirst(), result.lineage().getLast().snapshotId());
        assertFalse(result.baselineVerified()); assertFalse(result.sourceVerificationPerformed()); assertFalse(result.approvalGranted());
        assertFalse(result.writesPerformed()); assertFalse(result.evaluationReady());
        var draft = child.snapshot().catalog().asDraft();
        var comparison = lookup.compareBaseline(reference(child.snapshot()), new CatalogChangePreviewRequest(1, UUID.randomUUID(),
                "Fictional comparison", io.authweave.core.catalog.draft.CatalogDraftCanonicalizer.sha256(draft), draft, draft));
        assertTrue(comparison.suppliedBaseDigestMatches()); assertTrue(comparison.suppliedBaseContentMatches()); assertFalse(comparison.baselineVerified());
        assertEquals(result, lookup.lookup(reference(child.snapshot())));
        assertEquals(beforeSnapshots, dsl.fetchCount(CATALOG_PUBLISHED_SNAPSHOTS));
        assertEquals(beforeDecisions, dsl.fetchCount(CATALOG_PUBLICATION_DECISIONS));
        assertEquals(beforeEvents, dsl.fetchCount(CATALOG_PUBLICATION_EVENTS));
    }

    @Test
    void actualQueriesKeepBodiesOnTheServerWhenTheirCombinedBudgetIsInsufficient() throws Exception {
        var node = tip();
        long combined = node.row().snapshot().manifestBytes() + (node.row().proposal() == null ? 0 : node.row().proposal().requestBytes());
        var bounded = repository.find(node.snapshot().snapshotId(), combined - 1);
        assertNull(bounded.snapshot().manifest());
        if (bounded.proposal() != null) assertNull(bounded.proposal().request());
        assertTrue(bounded.snapshot().manifestBytes() > 0);
        var full = repository.find(node.snapshot().snapshotId(), combined);
        assertNotNull(full.snapshot().manifest());
    }

    @Test
    void sqlMetadataChecksDoNotHideTamperedClaimsAndStrictMapperRejectsUndeclaredAuthority() throws Exception {
        var node = tip(); var original = node.row().snapshot().manifest();
        var json = (ObjectNode) mapper.readTree(original);
        ((ObjectNode) json.at("/catalog/options/0/facts/SCIM")).put("availability", "UNKNOWN");
        try {
            adminManifest(node.snapshot().snapshotId(), mapper.writeValueAsString(json));
            var result = lookup.lookup(reference(node.snapshot()));
            assertEquals(CatalogPublicationLookup.Reason.STORED_FORMAT_INVALID, result.reason()); assertNull(result.snapshot());
        } finally { adminManifest(node.snapshot().snapshotId(), original); }
        assertTrue(lookup.lookup(reference(node.snapshot())).storedIntegrityValidated());
        json = (ObjectNode) mapper.readTree(original);
        ((ObjectNode) json.at("/catalog/options/0/facts/SCIM")).put("availability", " OPTIONAL ");
        try {
            // PostgreSQL validates top-level binding, not full nested JSON Schema or exact enum spelling.
            adminManifest(node.snapshot().snapshotId(), mapper.writeValueAsString(json));
            assertEquals(CatalogPublicationLookup.Reason.STORED_FORMAT_INVALID, lookup.lookup(reference(node.snapshot())).reason());
        } finally { adminManifest(node.snapshot().snapshotId(), original); }
    }

    @Test
    void exactMissingReferenceReturnsUnavailableAndCannotInventABootstrapOrUseTheTip() throws Exception {
        var existing = tip();
        var missing = new PublishedCatalogSnapshot.Reference(UUID.randomUUID(), existing.snapshot().catalog().catalogVersion(), existing.snapshot().snapshotSha256());
        var result = lookup.lookup(missing);
        assertEquals(CatalogPublicationLookup.Reason.NOT_FOUND, result.reason()); assertNull(result.snapshot()); assertTrue(result.lineage().isEmpty());
        assertFalse(result.baselineVerified());
    }

    private Node tip() throws Exception {
        var s = CATALOG_PUBLISHED_SNAPSHOTS;
        var row = dsl.selectFrom(s).whereNotExists(org.jooq.impl.DSL.selectOne().from(s.as("child"))
                .where(s.as("child").PREVIOUS_SNAPSHOT_ID.eq(s.ID))).fetchOne();
        if (row == null) {
            var root = new CatalogPublicationLookupFixtures(mapper).root(Instant.now().truncatedTo(ChronoUnit.MICROS));
            persist(root.row()); return tip();
        }
        var snapshot = mapper.readValue(row.getManifest().data(), PublishedCatalogSnapshot.class);
        var stored = repository.find(snapshot.snapshotId(), CatalogPublicationLookup.MAX_READ_BYTES);
        var request = stored.proposal() == null ? null : mapper.readValue(stored.proposal().request(), CatalogChangePreviewRequest.class);
        return new Node(stored, snapshot, request);
    }

    private void persist(CatalogPublicationRecord record) throws Exception {
        var s = record.snapshot(); var d = record.decision(); var e = record.event();
        try (var connection = admin()) {
            connection.setAutoCommit(false);
            try (var sql = connection.prepareStatement("INSERT INTO core.catalog_publication_decisions "
                    + "(id,snapshot_id,decision_kind,proposal_id,proposal_version,proposal_sha256,catalog_version,content_sha256,snapshot_sha256,published_at) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                sql.setObject(1, d.id()); sql.setObject(2, d.snapshotId()); sql.setString(3, d.kind()); sql.setObject(4, d.proposalId());
                sql.setObject(5, d.proposalVersion()); sql.setString(6, d.proposalSha256()); sql.setString(7, d.catalogVersion());
                sql.setString(8, d.contentSha256()); sql.setString(9, d.snapshotSha256()); sql.setTimestamp(10, Timestamp.from(d.publishedAt())); sql.executeUpdate();
            }
            try (var sql = connection.prepareStatement("INSERT INTO core.catalog_published_snapshots "
                    + "(id,decision_id,decision_kind,catalog_version,content_sha256,snapshot_sha256,published_at,previous_snapshot_id,previous_catalog_version,previous_snapshot_sha256,manifest) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb)")) {
                sql.setObject(1, s.id()); sql.setObject(2, s.decisionId()); sql.setString(3, s.decisionKind()); sql.setString(4, s.catalogVersion());
                sql.setString(5, s.contentSha256()); sql.setString(6, s.snapshotSha256()); sql.setTimestamp(7, Timestamp.from(s.publishedAt()));
                sql.setObject(8, s.previousId()); sql.setString(9, s.previousVersion()); sql.setString(10, s.previousSha256());
                sql.setString(11, s.manifest()); sql.executeUpdate();
            }
            try (var sql = connection.prepareStatement("INSERT INTO audit.catalog_publication_events "
                    + "(id,decision_id,snapshot_id,snapshot_sha256,decision_kind,action,actor_type,actor_issuer,actor_subject,actor_project_id,actor_org_id,authenticated_at,correlation_id,outcome) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                sql.setObject(1, e.id()); sql.setObject(2, e.decisionId()); sql.setObject(3, e.snapshotId()); sql.setString(4, e.snapshotSha256());
                sql.setString(5, e.decisionKind()); sql.setString(6, e.action()); sql.setString(7, e.actorType()); sql.setString(8, e.issuer());
                sql.setString(9, e.subject()); sql.setString(10, e.projectId()); sql.setString(11, e.orgId());
                sql.setTimestamp(12, Timestamp.from(e.authenticatedAt())); sql.setObject(13, e.correlationId()); sql.setString(14, e.outcome()); sql.executeUpdate();
            }
            connection.commit();
        }
    }
    private static Connection admin() throws Exception { return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); }
    private static void adminManifest(UUID id, String json) throws Exception {
        try (var connection = admin(); var sql = connection.prepareStatement("UPDATE core.catalog_published_snapshots SET manifest=?::jsonb WHERE id=?")) {
            sql.setString(1, json); sql.setObject(2, id); assertEquals(1, sql.executeUpdate());
        }
    }
}

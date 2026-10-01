package io.authweave.core.catalog.publication;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.PostgresIntegrationTest;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import io.authweave.core.catalog.proposal.CatalogProposalSnapshot;
import io.authweave.core.catalog.proposal.LocalCatalogProposalWriter;
import static io.authweave.core.generated.audit.tables.CatalogPublicationEvents.CATALOG_PUBLICATION_EVENTS;
import static io.authweave.core.generated.jooq.tables.CatalogPublicationDecisions.CATALOG_PUBLICATION_DECISIONS;
import static io.authweave.core.generated.jooq.tables.CatalogPublishedSnapshots.CATALOG_PUBLISHED_SNAPSHOTS;
import static org.junit.jupiter.api.Assertions.*;

/** Admin-only fictional fixtures exercise the reserved schema, never a runtime publisher or verified OIDC login. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local-catalog-write")
class CatalogPublicationStorageTests extends PostgresIntegrationTest {
    @Autowired private ObjectMapper mapper;
    @Autowired private DSLContext dsl;
    @Autowired private LocalCatalogProposalWriter writer;
    @Autowired private CatalogSnapshotInspector inspector;

    @Test
    void storesBoundImmutableManifestDecisionAndMinimalAuditWithoutGrantingBaselineAuthority() throws Exception {
        var parent = tip(); var proposal = proposal(); var snapshot = snapshot(parent);
        try (var connection = admin()) {
            connection.setAutoCommit(false);
            insertDecision(connection, snapshot, proposal);
            // Audit and manifest insert order is independent once the decision exists.
            insertEvent(connection, snapshot, Instant.now(), "CURATOR", snapshot.snapshotSha256());
            insertSnapshot(connection, snapshot, mapper.valueToTree(snapshot));
            connection.commit();
        }
        assertBundle(snapshot, 1);
        var saved = dsl.selectFrom(CATALOG_PUBLISHED_SNAPSHOTS)
                .where(CATALOG_PUBLISHED_SNAPSHOTS.ID.eq(snapshot.snapshotId())).fetchOne();
        assertEquals(snapshot.snapshotSha256(), saved.getSnapshotSha256());
        assertEquals(parent.snapshotId(), saved.getPreviousSnapshotId());
        assertEquals(mapper.valueToTree(snapshot), mapper.readTree(saved.getManifest().data()));
        var decision = dsl.selectFrom(CATALOG_PUBLICATION_DECISIONS)
                .where(CATALOG_PUBLICATION_DECISIONS.ID.eq(snapshot.publication().decisionId())).fetchOne();
        assertEquals(proposal.proposalId(), decision.getProposalId());
        assertEquals(proposal.proposalSha256(), decision.getProposalSha256());
        var event = dsl.selectFrom(CATALOG_PUBLICATION_EVENTS)
                .where(CATALOG_PUBLICATION_EVENTS.DECISION_ID.eq(decision.getId())).fetchOne();
        assertEquals("catalog.published", event.getAction()); assertEquals("CURATOR", event.getActorType());
        assertFalse(event.toString().contains("sourceUrl")); assertFalse(event.toString().contains("rationale"));
        var request = new CatalogChangePreviewRequest(1, UUID.randomUUID(), "Fictional storage test",
                io.authweave.core.catalog.draft.CatalogDraftCanonicalizer.sha256(snapshot.catalog().asDraft()),
                snapshot.catalog().asDraft(), snapshot.catalog().asDraft());
        var inspection = inspector.inspectBaseline(snapshot, reference(snapshot), request);
        assertEquals(CatalogSnapshotInspector.Status.VALID_SNAPSHOT_FORMAT, inspection.snapshot().status());
        assertEquals(CatalogSnapshotInspector.Authority.TRUSTED_PUBLICATION_LOOKUP_UNAVAILABLE, inspection.authority());
        assertFalse(inspection.baselineVerified()); assertFalse(inspection.snapshot().approvalGranted());
        assertFalse(inspection.snapshot().sourceVerificationPerformed()); assertFalse(inspection.snapshot().evaluationReady());
        try (var connection = core()) {
            connection.setAutoCommit(false);
            assertEquals("23514", assertThrows(SQLException.class, () -> insertRejection(connection, proposal)).getSQLState());
            connection.rollback();
        }
        var duplicate = snapshot(reference(snapshot));
        try (var connection = admin()) {
            connection.setAutoCommit(false);
            assertEquals("23505", assertThrows(SQLException.class, () -> insertDecision(connection, duplicate, proposal)).getSQLState());
            connection.rollback();
        }
        assertBundle(duplicate, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"decision-only", "no-audit", "no-manifest"})
    void partialBundlesCannotCommit(String missing) throws Exception {
        var snapshot = snapshot(tip()); var proposal = proposal();
        try (var connection = admin()) {
            connection.setAutoCommit(false);
            insertDecision(connection, snapshot, proposal);
            if (missing.equals("no-audit")) insertSnapshot(connection, snapshot, mapper.valueToTree(snapshot));
            if (missing.equals("no-manifest")) insertEvent(connection, snapshot, Instant.now(), "CURATOR", snapshot.snapshotSha256());
            assertEquals("23503", assertThrows(SQLException.class, connection::commit).getSQLState());
            connection.rollback();
        }
        assertBundle(snapshot, 0);
    }

    @Test
    void failedAuditInsertRollsBackTheManifestAndDecision() throws Exception {
        var snapshot = snapshot(tip()); var proposal = proposal();
        try (var failure = new AuditFailure(snapshot.snapshotId()); var connection = admin()) {
            connection.setAutoCommit(false);
            insertDecision(connection, snapshot, proposal);
            insertSnapshot(connection, snapshot, mapper.valueToTree(snapshot));
            assertEquals("P0001", assertThrows(SQLException.class,
                    () -> insertEvent(connection, snapshot, Instant.now(), "CURATOR", snapshot.snapshotSha256())).getSQLState());
            connection.rollback();
        }
        assertBundle(snapshot, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "catalogVersion", "contentSha256", "snapshotSha256", "decisionId", "publishedAt",
            "previousSnapshot", "schemaVersion", "canonicalizationVersion", "missing-parent", "empty-statuses", "extra-authority"})
    void manifestMetadataMustExactlyMatchItsStoredBinding(String mutation) throws Exception {
        var snapshot = snapshot(tip()); var proposal = proposal();
        var json = (ObjectNode) mapper.valueToTree(snapshot);
        switch (mutation) {
            case "id" -> json.put("snapshotId", UUID.randomUUID().toString());
            case "catalogVersion" -> ((ObjectNode) json.get("catalog")).put("catalogVersion", "other-label");
            case "contentSha256", "snapshotSha256" -> json.put(mutation, "0".repeat(64));
            case "decisionId" -> ((ObjectNode) json.get("publication")).put("decisionId", UUID.randomUUID().toString());
            case "publishedAt" -> ((ObjectNode) json.get("publication")).put("publishedAt", snapshot.publication().publishedAt().minusSeconds(1).toString());
            case "previousSnapshot" -> ((ObjectNode) json.get("previousSnapshot")).put("snapshotSha256", "0".repeat(64));
            case "schemaVersion" -> json.put("schemaVersion", "1");
            case "canonicalizationVersion" -> json.put("canonicalizationVersion", "unknown-format");
            case "missing-parent" -> json.remove("previousSnapshot");
            case "empty-statuses" -> json.putArray("factEvidenceStatuses");
            case "extra-authority" -> json.put("baselineVerified", true);
            default -> throw new AssertionError(mutation);
        }
        try (var connection = admin()) {
            connection.setAutoCommit(false);
            insertDecision(connection, snapshot, proposal);
            assertEquals("23514", assertThrows(SQLException.class, () -> insertSnapshot(connection, snapshot, json)).getSQLState());
            connection.rollback();
        }
        assertBundle(snapshot, 0);
    }

    @Test
    void decisionManifestAndAuditCannotBeCrossBoundByMatchingOnlySomeIdentifiers() throws Exception {
        var snapshot = snapshot(tip()); var other = snapshot(snapshot.previousSnapshot()); var proposal = proposal();
        try (var connection = admin()) {
            connection.setAutoCommit(false);
            insertDecision(connection, snapshot, proposal);
            assertEquals("23503", assertThrows(SQLException.class,
                    () -> insertSnapshot(connection, other, mapper.valueToTree(other))).getSQLState());
            connection.rollback();
            insertDecision(connection, snapshot, proposal);
            assertEquals("23503", assertThrows(SQLException.class,
                    () -> insertEvent(connection, snapshot, Instant.now(), "CURATOR", "0".repeat(64))).getSQLState());
            connection.rollback();
            assertEquals("23503", assertThrows(SQLException.class,
                    () -> insertEvent(connection, snapshot, Instant.now(), "CURATOR", snapshot.snapshotSha256())).getSQLState());
            connection.rollback();
        }
        assertBundle(snapshot, 0); assertBundle(other, 0);
    }

    @Test
    void rootRequiresExplicitBootstrapOriginAndCannotBeRepeated() throws Exception {
        tip(); var root = snapshot(null); var proposal = proposal();
        try (var connection = admin()) {
            connection.setAutoCommit(false);
            insertDecision(connection, root, proposal);
            assertEquals("23514", assertThrows(SQLException.class,
                    () -> insertSnapshot(connection, root, mapper.valueToTree(root), "PROPOSAL_APPROVAL")).getSQLState());
            connection.rollback();
            insertDecision(connection, root, null);
            assertEquals("23505", assertThrows(SQLException.class,
                    () -> insertSnapshot(connection, root, mapper.valueToTree(root))).getSQLState());
            connection.rollback();
            var child = snapshot(tip());
            insertDecision(connection, child, null);
            assertEquals("23514", assertThrows(SQLException.class,
                    () -> insertSnapshot(connection, child, mapper.valueToTree(child), "CURATED_BOOTSTRAP")).getSQLState());
            connection.rollback();
        }
        assertBundle(root, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown-id", "wrong-label", "wrong-digest", "self", "reused-label"})
    void parentMustBeAnExistingExactImmutableTuple(String mutation) throws Exception {
        var parent = tip(); var snapshot = snapshot(parent); var proposal = proposal();
        var badParent = switch (mutation) {
            case "unknown-id" -> new PublishedCatalogSnapshot.Reference(UUID.randomUUID(), parent.catalogVersion(), parent.snapshotSha256());
            case "wrong-label" -> new PublishedCatalogSnapshot.Reference(parent.snapshotId(), "incorrect-parent", parent.snapshotSha256());
            case "wrong-digest" -> new PublishedCatalogSnapshot.Reference(parent.snapshotId(), parent.catalogVersion(), "0".repeat(64));
            case "self" -> reference(snapshot);
            case "reused-label" -> parent;
            default -> throw new AssertionError(mutation);
        };
        var changed = rehash(new PublishedCatalogSnapshot(1, snapshot.kind(), snapshot.snapshotId(), snapshot.canonicalizationVersion(),
                mutation.equals("reused-label") ? new PublishedCatalogSnapshot.Content(1, parent.catalogVersion(), snapshot.catalog().options()) : snapshot.catalog(),
                snapshot.contentSha256(), snapshot.snapshotSha256(), badParent, snapshot.publication(), snapshot.factEvidenceStatuses()));
        try (var connection = admin()) {
            connection.setAutoCommit(false);
            insertDecision(connection, changed, proposal);
            String state = mutation.equals("self") || mutation.equals("reused-label") ? "23514" : "23503";
            assertEquals(state, assertThrows(SQLException.class,
                    () -> insertSnapshot(connection, changed, mapper.valueToTree(changed))).getSQLState());
            connection.rollback();
        }
        assertBundle(changed, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"stale", "future", "service", "infinite"})
    void auditRejectsStaleFutureServiceAndInfiniteAssertions(String mutation) throws Exception {
        var snapshot = snapshot(tip()); var proposal = proposal();
        try (var connection = admin()) {
            connection.setAutoCommit(false);
            insertDecision(connection, snapshot, proposal);
            var at = Instant.now().plusSeconds(mutation.equals("future") ? 60 : mutation.equals("stale") ? -960 : 0);
            assertEquals("23514", assertThrows(SQLException.class,
                    () -> insertEvent(connection, snapshot, at, mutation.equals("service") ? "SERVICE" : "CURATOR",
                            snapshot.snapshotSha256(), mutation.equals("infinite"))).getSQLState());
            connection.rollback();
        }
        assertBundle(snapshot, 0);
    }

    @Test
    void publicationTimestampCannotBeBackdatedOrTakenFromTheFuture() throws Exception {
        var parent = tip(); var proposal = proposal();
        for (long offset : List.of(-60L, 60L)) {
            var original = snapshot(parent);
            var changed = rehash(new PublishedCatalogSnapshot(1, original.kind(), original.snapshotId(), original.canonicalizationVersion(),
                    original.catalog(), original.contentSha256(), original.snapshotSha256(), parent,
                    new PublishedCatalogSnapshot.Publication(original.publication().decisionId(), Instant.now().plusSeconds(offset)), original.factEvidenceStatuses()));
            try (var connection = admin()) {
                connection.setAutoCommit(false);
                assertEquals("23514", assertThrows(SQLException.class, () -> insertDecision(connection, changed, proposal)).getSQLState());
                connection.rollback();
            }
            assertBundle(changed, 0);
        }
    }

    @Test
    void proposalDecisionsRequireTheExactCurrentRevisionAndCannotCoexistWithRejection() throws Exception {
        var snapshot = snapshot(tip()); var original = proposal();
        var wrongDigest = new CatalogProposalSnapshot(original.proposalId(), original.version(), original.state(), 1,
                "0".repeat(64), original.recordedAt(), original.request(), original.preview());
        var wrongVersion = new CatalogProposalSnapshot(original.proposalId(), original.version() + 1, original.state(), 1,
                original.proposalSha256(), original.recordedAt(), original.request(), original.preview());
        try (var connection = admin()) {
            connection.setAutoCommit(false);
            assertEquals("23503", assertThrows(SQLException.class, () -> insertDecision(connection, snapshot, wrongDigest)).getSQLState());
            connection.rollback();
            assertEquals("23514", assertThrows(SQLException.class, () -> insertDecision(connection, snapshot, wrongVersion)).getSQLState());
            connection.rollback();
            insertRejection(connection, original);
            assertEquals("23514", assertThrows(SQLException.class, () -> insertDecision(connection, snapshot, original)).getSQLState());
            connection.rollback();
            insertDecision(connection, snapshot, original);
            assertEquals("23514", assertThrows(SQLException.class, () -> insertRejection(connection, original)).getSQLState());
            connection.rollback();
        }
        // A real historical revision still exists after a new head; its digest alone is insufficient.
        var request = mapper.treeToValue(original.request(), CatalogChangePreviewRequest.class);
        var revised = new CatalogChangePreviewRequest(1, request.proposalId(), request.rationale() + " revised",
                request.expectedBaseSha256(), request.base(), request.candidate());
        writer.save(revised, original.version());
        try (var connection = admin()) {
            connection.setAutoCommit(false);
            assertEquals("23514", assertThrows(SQLException.class, () -> insertDecision(connection, snapshot, original)).getSQLState());
            connection.rollback();
        }
        assertBundle(snapshot, 0);
    }

    @Test
    void concurrentSiblingsWaitAndCannotForkTheCommittedHistory() throws Exception {
        var parent = tip(); var first = snapshot(parent); var second = snapshot(parent);
        var firstProposal = proposal(); var secondProposal = proposal();
        try (var initial = admin(); var competing = admin(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            initial.setAutoCommit(false); competing.setAutoCommit(false);
            insertDecision(initial, first, firstProposal); insertSnapshot(initial, first, mapper.valueToTree(first));
            insertEvent(initial, first, Instant.now(), "CURATOR", first.snapshotSha256());
            try {
                insertDecision(competing, second, secondProposal);
                try (var sql = competing.createStatement()) { sql.execute("SET LOCAL lock_timeout = '200ms'"); }
                var blocked = executor.submit(() -> assertThrows(SQLException.class,
                        () -> insertSnapshot(competing, second, mapper.valueToTree(second))).getSQLState());
                assertEquals("55P03", blocked.get()); competing.rollback();
                initial.commit();
                insertDecision(competing, second, secondProposal);
                assertEquals("23505", assertThrows(SQLException.class,
                        () -> insertSnapshot(competing, second, mapper.valueToTree(second))).getSQLState());
                competing.rollback();
            } finally { initial.rollback(); competing.rollback(); }
        }
        assertBundle(first, 1); assertBundle(second, 0);
    }

    @Test
    void publicationAndRejectionSerializeOnTheSameProposalHead() throws Exception {
        var snapshot = snapshot(tip()); var proposal = proposal();
        try (var publication = admin(); var rejection = core(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            publication.setAutoCommit(false); rejection.setAutoCommit(false);
            insertDecision(publication, snapshot, proposal);
            try {
                try (var sql = rejection.createStatement()) { sql.execute("SET LOCAL lock_timeout = '200ms'"); }
                var blocked = executor.submit(() -> assertThrows(SQLException.class,
                        () -> insertRejection(rejection, proposal)).getSQLState());
                assertEquals("55P03", blocked.get()); rejection.rollback();
                publication.rollback();
                // The existing runtime rejection path is still allowed after the reserved transaction rolls back.
                insertRejection(rejection, proposal); rejection.rollback();
                insertDecision(publication, snapshot, proposal);
                insertSnapshot(publication, snapshot, mapper.valueToTree(snapshot));
                insertEvent(publication, snapshot, Instant.now(), "CURATOR", snapshot.snapshotSha256());
                publication.commit();
                // A connection opened before the commit must see the now-terminal decision under the shared lock.
                assertEquals("23514", assertThrows(SQLException.class, () -> insertRejection(rejection, proposal)).getSQLState());
                rejection.rollback();
            } finally { publication.rollback(); rejection.rollback(); }
        }
        assertBundle(snapshot, 1);
    }

    @Test
    void runtimeHasReadOnlyReservationAndWebHasNoAccessIncludingDefaultPrivilegeLeaks() throws Exception {
        tip();
        try (var core = core(); var web = DriverManager.getConnection(postgres.getJdbcUrl(), "authweave_web_runtime", "web-test-password")) {
            for (String table : List.of("core.catalog_publication_decisions", "core.catalog_published_snapshots", "audit.catalog_publication_events")) {
                try (var sql = core.createStatement(); var rows = sql.executeQuery("SELECT count(*) FROM " + table)) { assertTrue(rows.next()); }
                denied(core, "INSERT INTO " + table + " DEFAULT VALUES");
                denied(core, "UPDATE " + table + " SET id=id WHERE false");
                denied(core, "DELETE FROM " + table + " WHERE false");
                denied(core, "TRUNCATE " + table + " CASCADE");
                denied(core, "ALTER TABLE " + table + " ADD COLUMN unauthorized text");
                denied(web, "SELECT * FROM " + table + " WHERE false");
                denied(web, "INSERT INTO " + table + " DEFAULT VALUES");
            }
            denied(core, "SELECT core.bind_catalog_publication_decision()");
        }
    }

    /** The sole root lives only in the shared disposable container; negative cases roll back their children. */
    private PublishedCatalogSnapshot.Reference tip() throws Exception {
        try (var connection = admin(); var sql = connection.createStatement(); var rows = sql.executeQuery(
                "SELECT p.id, p.catalog_version, p.snapshot_sha256 FROM core.catalog_published_snapshots p "
                + "WHERE NOT EXISTS (SELECT 1 FROM core.catalog_published_snapshots c WHERE c.previous_snapshot_id=p.id)")) {
            if (rows.next()) return new PublishedCatalogSnapshot.Reference(rows.getObject(1, UUID.class), rows.getString(2), rows.getString(3));
        }
        var root = snapshot(null);
        try (var connection = admin()) {
            connection.setAutoCommit(false);
            insertDecision(connection, root, null); insertSnapshot(connection, root, mapper.valueToTree(root));
            insertEvent(connection, root, Instant.now(), "CURATOR", root.snapshotSha256()); connection.commit();
        }
        return reference(root);
    }

    private PublishedCatalogSnapshot snapshot(PublishedCatalogSnapshot.Reference parent) throws Exception {
        var json = (ObjectNode) mapper.readTree(fixture("published-provider-catalog-snapshot.format-valid.json").toFile());
        UUID id = UUID.randomUUID(); json.put("snapshotId", id.toString());
        ((ObjectNode) json.get("catalog")).put("catalogVersion", "fixture-" + id);
        ((ObjectNode) json.get("publication")).put("decisionId", UUID.randomUUID().toString())
                .put("publishedAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        json.set("previousSnapshot", mapper.valueToTree(parent));
        return rehash(mapper.treeToValue(json, PublishedCatalogSnapshot.class));
    }

    private static PublishedCatalogSnapshot rehash(PublishedCatalogSnapshot input) {
        var content = new PublishedCatalogSnapshot(1, input.kind(), input.snapshotId(), input.canonicalizationVersion(), input.catalog(),
                input.computedContentSha256(), input.snapshotSha256(), input.previousSnapshot(), input.publication(), input.factEvidenceStatuses());
        return new PublishedCatalogSnapshot(1, content.kind(), content.snapshotId(), content.canonicalizationVersion(), content.catalog(),
                content.contentSha256(), content.computedSnapshotSha256(), content.previousSnapshot(), content.publication(), content.factEvidenceStatuses());
    }

    private CatalogProposalSnapshot proposal() throws Exception {
        var json = (ObjectNode) mapper.readTree(fixture("catalog-change-preview-request.valid.json").toFile());
        json.put("proposalId", UUID.randomUUID().toString());
        return writer.save(mapper.treeToValue(json, CatalogChangePreviewRequest.class), null).proposal();
    }

    private static Path fixture(String name) { return Path.of(System.getProperty("basedir", "."), "../../packages/contracts/tests/fixtures", name); }
    private static PublishedCatalogSnapshot.Reference reference(PublishedCatalogSnapshot snapshot) {
        return new PublishedCatalogSnapshot.Reference(snapshot.snapshotId(), snapshot.catalog().catalogVersion(), snapshot.snapshotSha256());
    }
    private static Connection admin() throws SQLException { return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); }
    private static Connection core() throws SQLException { return DriverManager.getConnection(postgres.getJdbcUrl(), "authweave_core_runtime", CORE_RUNTIME_PASSWORD); }

    private static void insertDecision(Connection connection, PublishedCatalogSnapshot snapshot, CatalogProposalSnapshot proposal) throws SQLException {
        try (var sql = connection.prepareStatement("INSERT INTO core.catalog_publication_decisions "
                + "(id,snapshot_id,decision_kind,proposal_id,proposal_version,proposal_sha256,catalog_version,content_sha256,snapshot_sha256,published_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            sql.setObject(1, snapshot.publication().decisionId()); sql.setObject(2, snapshot.snapshotId());
            sql.setString(3, proposal == null ? "CURATED_BOOTSTRAP" : "PROPOSAL_APPROVAL");
            sql.setObject(4, proposal == null ? null : proposal.proposalId()); sql.setObject(5, proposal == null ? null : proposal.version());
            sql.setString(6, proposal == null ? null : proposal.proposalSha256()); sql.setString(7, snapshot.catalog().catalogVersion());
            sql.setString(8, snapshot.contentSha256()); sql.setString(9, snapshot.snapshotSha256());
            sql.setTimestamp(10, Timestamp.from(snapshot.publication().publishedAt())); sql.executeUpdate();
        }
    }

    private void insertSnapshot(Connection connection, PublishedCatalogSnapshot snapshot, tools.jackson.databind.JsonNode json) throws SQLException {
        insertSnapshot(connection, snapshot, json, snapshot.previousSnapshot() == null ? "CURATED_BOOTSTRAP" : "PROPOSAL_APPROVAL");
    }
    private void insertSnapshot(Connection connection, PublishedCatalogSnapshot snapshot, tools.jackson.databind.JsonNode json, String kind) throws SQLException {
        try (var sql = connection.prepareStatement("INSERT INTO core.catalog_published_snapshots "
                + "(id,decision_id,decision_kind,catalog_version,content_sha256,snapshot_sha256,published_at,previous_snapshot_id,previous_catalog_version,previous_snapshot_sha256,manifest) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb)")) {
            sql.setObject(1, snapshot.snapshotId()); sql.setObject(2, snapshot.publication().decisionId()); sql.setString(3, kind);
            sql.setString(4, snapshot.catalog().catalogVersion()); sql.setString(5, snapshot.contentSha256()); sql.setString(6, snapshot.snapshotSha256());
            sql.setTimestamp(7, Timestamp.from(snapshot.publication().publishedAt())); var parent = snapshot.previousSnapshot();
            sql.setObject(8, parent == null ? null : parent.snapshotId()); sql.setString(9, parent == null ? null : parent.catalogVersion());
            sql.setString(10, parent == null ? null : parent.snapshotSha256()); sql.setString(11, mapper.writeValueAsString(json)); sql.executeUpdate();
        }
    }

    private static void insertEvent(Connection connection, PublishedCatalogSnapshot snapshot, Instant authenticatedAt, String actor, String digest) throws SQLException {
        insertEvent(connection, snapshot, authenticatedAt, actor, digest, false);
    }
    private static void insertEvent(Connection connection, PublishedCatalogSnapshot snapshot, Instant authenticatedAt, String actor, String digest, boolean infinite) throws SQLException {
        try (var sql = connection.prepareStatement("INSERT INTO audit.catalog_publication_events "
                + "(id,decision_id,snapshot_id,snapshot_sha256,decision_kind,action,actor_type,actor_issuer,actor_subject,actor_project_id,actor_org_id,authenticated_at,correlation_id,outcome) "
                + "VALUES (?,?,?,?,?,'catalog.published',?,'http://localhost:8081','synthetic-curator','123456789012345678','987654321098765432',?,?,'SUCCEEDED')")) {
            sql.setObject(1, UUID.randomUUID()); sql.setObject(2, snapshot.publication().decisionId()); sql.setObject(3, snapshot.snapshotId());
            sql.setString(4, digest); sql.setString(5, snapshot.previousSnapshot() == null ? "CURATED_BOOTSTRAP" : "PROPOSAL_APPROVAL");
            sql.setString(6, actor); sql.setTimestamp(7, infinite ? new Timestamp(org.postgresql.PGStatement.DATE_POSITIVE_INFINITY) : Timestamp.from(authenticatedAt));
            sql.setObject(8, UUID.randomUUID()); sql.executeUpdate();
        }
    }

    private static void insertRejection(Connection connection, CatalogProposalSnapshot proposal) throws SQLException {
        try (var sql = connection.prepareStatement("INSERT INTO core.catalog_proposal_decisions "
                + "(id,proposal_id,proposal_version,proposal_sha256,decision,reason_code) VALUES (?,?,?,?,'REJECTED','OTHER')")) {
            sql.setObject(1, UUID.randomUUID()); sql.setObject(2, proposal.proposalId()); sql.setLong(3, proposal.version());
            sql.setString(4, proposal.proposalSha256()); sql.executeUpdate();
        }
    }

    private void assertBundle(PublishedCatalogSnapshot snapshot, int count) {
        assertEquals(count, dsl.fetchCount(CATALOG_PUBLICATION_DECISIONS, CATALOG_PUBLICATION_DECISIONS.ID.eq(snapshot.publication().decisionId())));
        assertEquals(count, dsl.fetchCount(CATALOG_PUBLISHED_SNAPSHOTS, CATALOG_PUBLISHED_SNAPSHOTS.ID.eq(snapshot.snapshotId())));
        assertEquals(count, dsl.fetchCount(CATALOG_PUBLICATION_EVENTS, CATALOG_PUBLICATION_EVENTS.DECISION_ID.eq(snapshot.publication().decisionId())));
    }
    private static void denied(Connection connection, String query) throws Exception {
        connection.setAutoCommit(false);
        try (var sql = connection.createStatement()) { assertEquals("42501", assertThrows(SQLException.class, () -> sql.execute(query)).getSQLState()); }
        finally { connection.rollback(); }
    }
    private static final class AuditFailure implements AutoCloseable {
        private final String name;
        AuditFailure(UUID id) throws SQLException {
            name = "test_publication_fail_" + id.toString().replace("-", "");
            try (var connection = admin(); var sql = connection.createStatement()) {
                sql.execute("CREATE FUNCTION audit." + name + "() RETURNS trigger LANGUAGE plpgsql AS $body$ "
                        + "BEGIN IF NEW.snapshot_id='" + id + "'::uuid THEN RAISE EXCEPTION 'Injected audit failure'; END IF; RETURN NEW; END; $body$");
                sql.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON audit.catalog_publication_events FOR EACH ROW EXECUTE FUNCTION audit." + name + "()");
            }
        }
        public void close() throws SQLException {
            try (var connection = admin(); var sql = connection.createStatement()) {
                sql.execute("DROP TRIGGER " + name + " ON audit.catalog_publication_events"); sql.execute("DROP FUNCTION audit." + name + "()");
            }
        }
    }
}

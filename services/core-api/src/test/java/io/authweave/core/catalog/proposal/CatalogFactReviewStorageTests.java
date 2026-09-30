package io.authweave.core.catalog.proposal;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.PostgresIntegrationTest;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import static io.authweave.core.generated.jooq.tables.CatalogFactReviews.CATALOG_FACT_REVIEWS;
import static io.authweave.core.generated.audit.tables.CatalogFactReviewEvents.CATALOG_FACT_REVIEW_EVENTS;
import static org.junit.jupiter.api.Assertions.*;

/** Reserved storage is tested directly; no HTTP mutation or source-verification claim exists. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local-catalog-write")
class CatalogFactReviewStorageTests extends PostgresIntegrationTest {
    @Autowired private LocalCatalogProposalWriter proposals;
    @Autowired private CatalogProposalRepository repository;
    @Autowired private CatalogProposalRejectionWriter rejections;
    @Autowired private ObjectMapper mapper;
    @Autowired private DSLContext dsl;

    @Test
    void reviewHistoryAndCorrectionsBindOneRecordedFactWithoutChangingItsTrust() throws Exception {
        var snapshot = proposals.save(proposal(), null).proposal();
        UUID first = record(snapshot, "facts.SCIM", "SOURCE_SUPPORTS_CLAIM");
        UUID correction = record(snapshot, "facts.SCIM", "INSUFFICIENT_EVIDENCE");
        UUID another = record(snapshot, "compatibility.applications.B2B_SAAS", "SOURCE_DOES_NOT_SUPPORT_CLAIM");
        var r = CATALOG_FACT_REVIEWS; var e = CATALOG_FACT_REVIEW_EVENTS;
        var history = dsl.selectFrom(r).where(r.PROPOSAL_ID.eq(snapshot.proposalId()))
                .orderBy(r.REVIEW_NUMBER).fetch();
        assertEquals(java.util.List.of(first, correction, another), history.getValues(r.ID));
        assertEquals(java.util.List.of(1L, 2L, 3L), history.getValues(r.REVIEW_NUMBER));
        assertEquals("SOURCE_SUPPORTS_CLAIM", history.getFirst().getVerdict());
        assertEquals(snapshot.proposalSha256(), history.getFirst().getProposalSha256());
        assertEquals(3, events(snapshot.proposalId()));
        var event = dsl.selectFrom(e).where(e.REVIEW_ID.eq(correction)).fetchOne();
        assertEquals("CURATOR", event.getActorType());
        assertEquals("catalog-fact.review-recorded", event.getAction());
        assertEquals("INSUFFICIENT_EVIDENCE", event.getVerdict());
        assertFalse(event.toString().contains("sourceUrl"));
        assertEquals(snapshot, repository.current(snapshot.proposalId()));
        assertEquals(1, repository.events(snapshot.proposalId(), null, 100).items().size());
        assertNull(repository.currentRejection(snapshot.proposalId()));
    }

    @Test
    void reviewAndAuditMustCommitTogetherIncludingOnInjectedFailure() throws Exception {
        var snapshot = proposals.save(proposal(), null).proposal();
        try (var connection = core()) {
            connection.setAutoCommit(false);
            insertReview(connection, UUID.randomUUID(), snapshot, "example-managed-eu", "facts.SCIM", "INSUFFICIENT_EVIDENCE");
            assertEquals("23503", assertThrows(SQLException.class, connection::commit).getSQLState());
            connection.rollback();
        }
        assertEquals(0, reviews(snapshot.proposalId()));
        try (var failure = new AuditFailure(snapshot.proposalId()); var connection = core()) {
            connection.setAutoCommit(false);
            UUID id = UUID.randomUUID();
            insertReview(connection, id, snapshot, "example-managed-eu", "facts.SCIM", "INSUFFICIENT_EVIDENCE");
            assertEquals("P0001", assertThrows(SQLException.class, () -> insertEvent(connection, id, snapshot,
                    "facts.SCIM", "INSUFFICIENT_EVIDENCE", Instant.now(), "CURATOR")).getSQLState());
            connection.rollback();
        }
        assertEquals(0, reviews(snapshot.proposalId()));
        assertEquals(0, events(snapshot.proposalId()));
        record(snapshot, "facts.SCIM", "INSUFFICIENT_EVIDENCE");
        assertEquals(1L, dsl.selectFrom(CATALOG_FACT_REVIEWS)
                .where(CATALOG_FACT_REVIEWS.PROPOSAL_ID.eq(snapshot.proposalId())).fetchOne().getReviewNumber());
    }

    @Test
    void wrongDigestMissingFactsAndApprovalVerdictsCannotBeRecorded() throws Exception {
        var snapshot = proposals.save(proposal(), null).proposal();
        var wrongDigest = new CatalogProposalSnapshot(snapshot.proposalId(), 0, snapshot.state(), 1,
                "0".repeat(64), snapshot.recordedAt(), snapshot.request(), snapshot.preview());
        try (var connection = core()) {
            expect(connection, "23503", () -> insertReview(connection, UUID.randomUUID(), wrongDigest,
                    "example-managed-eu", "facts.SCIM", "SOURCE_SUPPORTS_CLAIM"));
            expect(connection, "23514", () -> insertReview(connection, UUID.randomUUID(), snapshot,
                    "missing-option", "facts.SCIM", "SOURCE_SUPPORTS_CLAIM"));
            expect(connection, "23514", () -> insertReview(connection, UUID.randomUUID(), snapshot,
                    "example-managed-eu", "facts.NOT_RECORDED", "SOURCE_SUPPORTS_CLAIM"));
            for (String path : java.util.List.of("facts", "facts.OIDC.evidence", "facts.OIDC.availability", "facts.JIT")) {
                expect(connection, "23514", () -> insertReview(connection, UUID.randomUUID(), snapshot,
                        "example-managed-eu", path, "SOURCE_SUPPORTS_CLAIM"));
            }
            for (String verdict : java.util.List.of("APPROVED", "REVIEWED", "PUBLISH")) {
                expect(connection, "23514", () -> insertReview(connection, UUID.randomUUID(), snapshot,
                        "example-managed-eu", "facts.SCIM", verdict));
            }
        }
        assertEquals(0, reviews(snapshot.proposalId()));
    }

    @Test
    void eventsRejectDifferentFactVerdictServiceActorAndStaleOrFutureAuthentication() throws Exception {
        var snapshot = proposals.save(proposal(), null).proposal();
        try (var connection = core()) {
            for (String variant : java.util.List.of("fact", "verdict", "digest", "version", "actor", "stale", "future")) {
                connection.setAutoCommit(false);
                UUID id = UUID.randomUUID();
                insertReview(connection, id, snapshot, "example-managed-eu", "facts.SCIM", "SOURCE_SUPPORTS_CLAIM");
                String path = variant.equals("fact") ? "facts.OIDC" : "facts.SCIM";
                String verdict = variant.equals("verdict") ? "INSUFFICIENT_EVIDENCE" : "SOURCE_SUPPORTS_CLAIM";
                var eventRevision = new CatalogProposalSnapshot(snapshot.proposalId(), variant.equals("version") ? 1 : 0,
                        snapshot.state(), 1, variant.equals("digest") ? "0".repeat(64) : snapshot.proposalSha256(),
                        snapshot.recordedAt(), snapshot.request(), snapshot.preview());
                Instant at = Instant.now().plusSeconds(variant.equals("stale") ? -901 : variant.equals("future") ? 31 : 0);
                assertEquals(java.util.List.of("fact", "verdict", "digest", "version").contains(variant) ? "23503" : "23514",
                        assertThrows(SQLException.class, () -> insertEvent(connection, id, eventRevision, path,
                                verdict, at, variant.equals("actor") ? "SERVICE" : "CURATOR")).getSQLState());
                connection.rollback();
            }
        }
        assertEquals(0, reviews(snapshot.proposalId()));
        assertEquals(0, events(snapshot.proposalId()));
    }

    @Test
    void newerRevisionsRequireNewReviewsAndRejectionClosesNewReviewWrites() throws Exception {
        var request = proposal();
        var old = proposals.save(request, null).proposal();
        record(old, "facts.SCIM", "SOURCE_SUPPORTS_CLAIM");
        var updated = proposals.save(new CatalogChangePreviewRequest(1, request.proposalId(), "Revised rationale.",
                request.expectedBaseSha256(), request.base(), request.candidate()), 0L).proposal();
        try (var connection = core()) {
            expect(connection, "23514", () -> insertReview(connection, UUID.randomUUID(), old,
                    "example-managed-eu", "facts.SCIM", "SOURCE_SUPPORTS_CLAIM"));
        }
        UUID fresh = record(updated, "facts.SCIM", "INSUFFICIENT_EVIDENCE");
        assertEquals(1L, dsl.selectFrom(CATALOG_FACT_REVIEWS).where(CATALOG_FACT_REVIEWS.ID.eq(fresh))
                .fetchOne().getReviewNumber());
        rejections.reject(updated.proposalId(), new CatalogProposalRejectionRequest(1L, updated.proposalSha256(),
                CatalogProposalRejectionRequest.ReasonCode.INSUFFICIENT_EVIDENCE),
                new CatalogProposalRejectionWriter.CuratorActor("http://localhost:8081", "synthetic-curator",
                        "123456789012345678", "987654321098765432", Instant.now()));
        try (var connection = core()) {
            expect(connection, "23514", () -> insertReview(connection, UUID.randomUUID(), updated,
                    "example-managed-eu", "facts.OIDC", "SOURCE_SUPPORTS_CLAIM"));
        }
        assertEquals(2, reviews(updated.proposalId()));
        assertEquals(2, events(updated.proposalId()));
    }

    @Test
    void concurrentReviewsReceiveCommitOrderedNumbersWithoutLosingCorrections() throws Exception {
        var snapshot = proposals.save(proposal(), null).proposal();
        UUID firstId = UUID.randomUUID();
        try (var first = core(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            first.setAutoCommit(false);
            insertReview(first, firstId, snapshot, "example-managed-eu", "facts.SCIM", "SOURCE_SUPPORTS_CLAIM");
            var started = new java.util.concurrent.CountDownLatch(1);
            var second = executor.submit(() -> {
                try (var connection = core()) {
                    connection.setAutoCommit(false); UUID id = UUID.randomUUID();
                    started.countDown();
                    insertReview(connection, id, snapshot, "example-managed-eu", "facts.SCIM", "INSUFFICIENT_EVIDENCE");
                    insertEvent(connection, id, snapshot, "facts.SCIM", "INSUFFICIENT_EVIDENCE", Instant.now(), "CURATOR");
                    connection.commit(); return id;
                }
            });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
                insertEvent(first, firstId, snapshot, "facts.SCIM", "SOURCE_SUPPORTS_CLAIM", Instant.now(), "CURATOR");
                first.commit();
                var secondId = second.get(5, TimeUnit.SECONDS);
                var r = CATALOG_FACT_REVIEWS;
                assertEquals(2L, dsl.selectFrom(r).where(r.ID.eq(secondId)).fetchOne().getReviewNumber());
                assertEquals(2, reviews(snapshot.proposalId()));
            } finally {
                // Release the row lock before executor.close even if an assertion fails.
                first.rollback();
            }
        }
    }

    @Test
    void duplicateIdsAndAuditEventsAreRejectedAndRuntimeCannotRewriteOrBackdate() throws Exception {
        var snapshot = proposals.save(proposal(), null).proposal();
        UUID id = record(snapshot, "facts.SCIM", "INSUFFICIENT_EVIDENCE");
        try (var connection = core()) {
            expect(connection, "23505", () -> insertReview(connection, id, snapshot,
                    "example-managed-eu", "facts.SCIM", "INSUFFICIENT_EVIDENCE"));
            expect(connection, "23505", () -> insertEvent(connection, id, snapshot,
                    "facts.SCIM", "INSUFFICIENT_EVIDENCE", Instant.now(), "CURATOR"));
            for (String table : java.util.List.of("core.catalog_fact_reviews", "audit.catalog_fact_review_events")) {
                denied(connection, "UPDATE " + table + " SET id=id WHERE false");
                denied(connection, "DELETE FROM " + table + " WHERE false");
                denied(connection, "TRUNCATE " + table + " CASCADE");
            }
            denied(connection, "INSERT INTO core.catalog_fact_reviews (id, review_number) VALUES (gen_random_uuid(), 1)");
            denied(connection, "INSERT INTO core.catalog_fact_reviews (id, recorded_at) VALUES (gen_random_uuid(), clock_timestamp())");
            denied(connection, "INSERT INTO audit.catalog_fact_review_events (id, occurred_at) VALUES (gen_random_uuid(), clock_timestamp())");
        }
        try (var web = DriverManager.getConnection(postgres.getJdbcUrl(), "authweave_web_runtime", "web-test-password")) {
            for (String table : java.util.List.of("core.catalog_fact_reviews", "audit.catalog_fact_review_events")) {
                denied(web, "SELECT * FROM " + table + " WHERE false");
                denied(web, "INSERT INTO " + table + " DEFAULT VALUES");
            }
        }
        assertEquals(1, reviews(snapshot.proposalId()));
        assertEquals(1, events(snapshot.proposalId()));
    }

    private CatalogChangePreviewRequest proposal() throws Exception {
        var file = Path.of(System.getProperty("basedir", "."), "../../packages/contracts/tests/fixtures/catalog-change-preview-request.valid.json");
        var json = (ObjectNode) mapper.readTree(file.toFile()); json.put("proposalId", UUID.randomUUID().toString());
        return mapper.treeToValue(json, CatalogChangePreviewRequest.class);
    }

    private UUID record(CatalogProposalSnapshot snapshot, String path, String verdict) throws SQLException {
        try (var connection = core()) {
            connection.setAutoCommit(false); UUID id = UUID.randomUUID();
            insertReview(connection, id, snapshot, "example-managed-eu", path, verdict);
            insertEvent(connection, id, snapshot, path, verdict, Instant.now(), "CURATOR");
            connection.commit(); return id;
        }
    }

    private static void insertReview(Connection connection, UUID id, CatalogProposalSnapshot snapshot,
            String option, String path, String verdict) throws SQLException {
        try (var sql = connection.prepareStatement("INSERT INTO core.catalog_fact_reviews "
                + "(id, proposal_id, proposal_version, proposal_sha256, option_id, fact_path, verdict) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            sql.setObject(1, id); sql.setObject(2, snapshot.proposalId()); sql.setLong(3, snapshot.version());
            sql.setString(4, snapshot.proposalSha256()); sql.setString(5, option); sql.setString(6, path); sql.setString(7, verdict);
            sql.executeUpdate();
        }
    }

    private static void insertEvent(Connection connection, UUID id, CatalogProposalSnapshot snapshot,
            String path, String verdict, Instant authTime, String actor) throws SQLException {
        try (var sql = connection.prepareStatement("INSERT INTO audit.catalog_fact_review_events "
                + "(id, review_id, proposal_id, proposal_version, proposal_sha256, option_id, fact_path, verdict, action, actor_type, "
                + "actor_issuer, actor_subject, actor_project_id, actor_org_id, authenticated_at, correlation_id, outcome) "
                + "VALUES (?, ?, ?, ?, ?, 'example-managed-eu', ?, ?, 'catalog-fact.review-recorded', ?, "
                + "'http://localhost:8081', 'synthetic-curator', '123456789012345678', '987654321098765432', ?, ?, 'SUCCEEDED')")) {
            sql.setObject(1, UUID.randomUUID()); sql.setObject(2, id); sql.setObject(3, snapshot.proposalId());
            sql.setLong(4, snapshot.version()); sql.setString(5, snapshot.proposalSha256());
            sql.setString(6, path); sql.setString(7, verdict); sql.setString(8, actor);
            sql.setTimestamp(9, Timestamp.from(authTime)); sql.setObject(10, UUID.randomUUID()); sql.executeUpdate();
        }
    }

    private int reviews(UUID id) { return dsl.fetchCount(CATALOG_FACT_REVIEWS, CATALOG_FACT_REVIEWS.PROPOSAL_ID.eq(id)); }
    private int events(UUID id) { return dsl.fetchCount(CATALOG_FACT_REVIEW_EVENTS, CATALOG_FACT_REVIEW_EVENTS.PROPOSAL_ID.eq(id)); }
    private static Connection core() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), "authweave_core_runtime", CORE_RUNTIME_PASSWORD);
    }
    private static void expect(Connection connection, String state, SqlAction action) throws Exception {
        connection.setAutoCommit(false);
        try { assertEquals(state, assertThrows(SQLException.class, action::run).getSQLState()); }
        finally { connection.rollback(); }
    }
    private static void denied(Connection connection, String sql) throws Exception {
        expect(connection, "42501", () -> { try (var statement = connection.createStatement()) { statement.execute(sql); } });
    }
    @FunctionalInterface private interface SqlAction { void run() throws SQLException; }

    private static final class AuditFailure implements AutoCloseable {
        private final String name;
        AuditFailure(UUID proposalId) throws SQLException {
            name = "test_fact_review_fail_" + proposalId.toString().replace("-", "");
            try (var connection = admin(); var sql = connection.createStatement()) {
                sql.execute("CREATE FUNCTION audit." + name + "() RETURNS trigger LANGUAGE plpgsql AS $body$ "
                        + "BEGIN IF NEW.proposal_id = '" + proposalId + "'::uuid THEN RAISE EXCEPTION 'Injected audit failure'; "
                        + "END IF; RETURN NEW; END; $body$");
                sql.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON audit.catalog_fact_review_events "
                        + "FOR EACH ROW EXECUTE FUNCTION audit." + name + "()");
            }
        }
        public void close() throws SQLException {
            try (var connection = admin(); var sql = connection.createStatement()) {
                sql.execute("DROP TRIGGER " + name + " ON audit.catalog_fact_review_events");
                sql.execute("DROP FUNCTION audit." + name + "()");
            }
        }
        private static Connection admin() throws SQLException {
            return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
    }
}

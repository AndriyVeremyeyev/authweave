package io.authweave.core.catalog.publication;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.impact.CatalogBootstrapImpactService;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;
import static io.authweave.core.catalog.publication.CatalogPublicationLookupFixtures.*;
import static io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict.*;
import static io.authweave.core.generated.jooq.tables.CatalogBootstrapImpactReports.CATALOG_BOOTSTRAP_IMPACT_REPORTS;
import static io.authweave.core.generated.audit.tables.CatalogBootstrapImpactReportEvents.CATALOG_BOOTSTRAP_IMPACT_REPORT_EVENTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local-catalog-bootstrap-impact-write")
class CatalogBootstrapImpactReportPersistenceTests {
    // Bootstrap must not share admin-seeded publication state with unrelated storage tests.
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-bookworm")
            .withDatabaseName("authweave").withUsername("authweave_admin").withPassword("admin-test-password")
            .withInitScript("db/test/init-runtime-roles.sql");
    static { postgres.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl); r.add("spring.datasource.username", () -> "authweave_core_runtime");
        r.add("spring.datasource.password", () -> "core-test-password"); r.add("spring.datasource.hikari.maximum-pool-size", () -> 3);
        r.add("spring.datasource.hikari.minimum-idle", () -> 0); r.add("spring.flyway.url", postgres::getJdbcUrl);
        r.add("spring.flyway.user", postgres::getUsername); r.add("spring.flyway.password", postgres::getPassword);
    }
    @Autowired LocalCatalogBootstrapImpactWriter writer;
    @Autowired CatalogBootstrapImpactReportRepository reports;
    @Autowired CatalogBootstrapReviewService reviews;
    @Autowired CatalogBootstrapReviewRepository boundary;
    @Autowired CatalogPublicationPreflightRepository registry;
    @Autowired CatalogPublicationPreflight preflight;
    @Autowired CatalogDraftValidator validator;
    @Autowired ObjectMapper mapper;
    @Autowired DSLContext dsl;
    @Autowired PlatformTransactionManager transactions;

    @Test void exactReviewProducesImmutableReportAndMinimalAuditAndReadOnlyPreflightReplaysIt() {
        var review = review(); var id = UUID.randomUUID(); var result = writer.save(id, review.reviewId(), review.reviewSha256());
        assertTrue(result.changed()); var saved = result.report(); assertEquals(id, saved.reportId());
        assertEquals(review.candidateSha256(), saved.candidateSha256()); assertEquals(review.reviewSha256(), saved.reviewSha256());
        assertEquals(CatalogDraftCanonicalizer.sha256(saved.report()), saved.reportSha256()); assertFalse(saved.report().has("proposalId"));
        assertEquals(68, saved.report().get("cases").size()); assertEquals(3, saved.report().get("scenarios").size());
        var event = reports.find(id).event(); assertEquals("SERVICE", event.actorType()); assertEquals(review.reviewId(), event.reviewId());
        assertEquals(saved.reportSha256(), event.reportSha256()); assertFalse(mapper.writeValueAsString(event).contains("sourceUrl"));
        var retry = writer.save(id, review.reviewId(), review.reviewSha256()); assertFalse(retry.changed()); assertEquals(saved, retry.report());
        assertEquals(event, reports.find(id).event()); assertEquals(review, reviews.get(review.reviewId(), review.reviewSha256()));
        int count = dsl.fetchCount(CATALOG_BOOTSTRAP_IMPACT_REPORTS); var preflightResult = preflight.bootstrap(review.reviewId(), review.reviewSha256());
        assertEquals(CatalogPublicationBootstrapImpactVerifier.Status.VERIFIED_BOOTSTRAP_ANALYSIS, preflightResult.storedBootstrapImpact().status());
        assertEquals(id, preflightResult.storedBootstrapImpact().reportId()); assertTrue(preflightResult.storedBootstrapImpact().historicalReplayVerified());
        assertEquals(Instant.parse(saved.report().get("evaluatedAt").asText()), preflightResult.storedBootstrapImpact().analysis().evaluatedAt());
        assertFalse(preflightResult.blockers().contains(CatalogPublicationPreflight.Blocker.BOOTSTRAP_IMPACT_RECEIPT_MISSING));
        assertTrue(preflightResult.blockers().contains(CatalogPublicationPreflight.Blocker.IMPACT_COVERAGE_INCOMPLETE));
        assertFalse(preflightResult.publicationReady()); assertFalse(preflightResult.writesPerformed()); assertEquals(count, dsl.fetchCount(CATALOG_BOOTSTRAP_IMPACT_REPORTS));
    }
    @Test void retryDoesNotRerunKernelAndWrongIdentityOrHashCannotRebindStoredReport() {
        var a = review(); var b = review(); var id = UUID.randomUUID(); var saved = writer.save(id, a.reviewId(), a.reviewSha256()).report();
        var unavailable = mock(CatalogBootstrapImpactService.class);
        var retryWriter = new LocalCatalogBootstrapImpactWriter(dsl, mapper, unavailable, reports, boundary, reviews, registry);
        var retry = new TransactionTemplate(transactions).execute(s -> retryWriter.save(id, a.reviewId(), a.reviewSha256()));
        assertFalse(retry.changed()); assertEquals(saved, retry.report()); verifyNoInteractions(unavailable);
        assertEquals(CatalogBootstrapImpactReportException.Reason.ID_CONFLICT, assertThrows(CatalogBootstrapImpactReportException.class,
                () -> writer.save(id, b.reviewId(), b.reviewSha256())).reason());
        assertThrows(CatalogBootstrapImpactReportException.class, () -> writer.save(id, a.reviewId(), "0".repeat(64)));
        assertThrows(CatalogBootstrapImpactReportException.class, () -> reports.get(b.reviewId(), a.reviewSha256(), id));
        assertThrows(CatalogBootstrapReviewException.class, () -> writer.save(UUID.randomUUID(), UUID.randomUUID(), a.reviewSha256()));
        assertNull(reports.latest(b.reviewId()));
    }
    @Test void boundedLatestReadsUseExactReviewAndDatabaseTimesAndPreserveOlderSnapshots() {
        var a = review(); var b = review(); var first = writer.save(UUID.randomUUID(), a.reviewId(), a.reviewSha256()).report();
        writer.save(UUID.randomUUID(), b.reviewId(), b.reviewSha256()); var last = writer.save(UUID.randomUUID(), a.reviewId(), a.reviewSha256()).report();
        var row = reports.latest(a.reviewId()); assertEquals(last.reportId(), row.id()); assertTrue(last.reportNumber() > first.reportNumber());
        assertNull(reports.latest(a.reviewId(), row.reportBytes() - 1).report()); assertNotNull(reports.latest(a.reviewId(), row.reportBytes()).report());
        assertEquals(row.reportBytes(), reports.latest(a.reviewId(), 0).reportBytes()); assertNotNull(row.event());
        assertEquals(first, reports.get(a.reviewId(), a.reviewSha256(), first.reportId()));
        var at = Instant.parse(last.report().get("evaluatedAt").asText()); assertFalse(at.isBefore(a.recordedAt()));
        assertFalse(at.isAfter(last.recordedAt())); assertTrue(last.recordedAt().isBefore(at.plusSeconds(30)));
        assertThrows(IllegalArgumentException.class, () -> reports.latest(a.reviewId(), -1));
        assertThrows(IllegalArgumentException.class, () -> reports.latest(a.reviewId(), CatalogBootstrapImpactReportRepository.MAX_JSON_BYTES + 1));
    }
    @ParameterizedTest @ValueSource(strings = {"same", "different-ids", "different-reviews"})
    void concurrentWritesSerializeAndNeverDuplicateOrRebindIdentity(String scenario) throws Exception {
        var a = review(); var b = review(); var id = UUID.randomUUID(); var second = scenario.equals("different-reviews") ? b : a;
        var secondId = scenario.equals("different-ids") ? UUID.randomUUID() : id; var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(executor.submit(() -> race(id, a, start)), executor.submit(() -> race(secondId, second, start)));
            int changed = 0, conflicts = 0; for (var f : futures) { int value = f.get(30, TimeUnit.SECONDS); if (value == 1) changed++; if (value == -1) conflicts++; }
            assertEquals(scenario.equals("different-ids") ? 2 : 1, changed); assertEquals(scenario.equals("different-reviews") ? 1 : 0, conflicts);
        }
        assertEquals(scenario.equals("different-ids") ? 2 : 1, dsl.fetchCount(CATALOG_BOOTSTRAP_IMPACT_REPORTS, CATALOG_BOOTSTRAP_IMPACT_REPORTS.REVIEW_ID.in(a.reviewId(), b.reviewId())));
    }
    @Test void sharedBoundaryOrdersCommittedServiceReportsBeforeNumberAllocation() throws Exception {
        var a = review(); var saved = new CountDownLatch(1); var release = new CountDownLatch(1); var started = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> new TransactionTemplate(transactions).execute(s -> {
                var report = writer.save(UUID.randomUUID(), a.reviewId(), a.reviewSha256()).report(); saved.countDown(); await(release); return report;
            })); await(saved);
            var second = executor.submit(() -> { started.countDown(); return writer.save(UUID.randomUUID(), a.reviewId(), a.reviewSha256()).report(); }); await(started);
            try { assertNull(reports.latest(a.reviewId())); } finally { release.countDown(); }
            var one = first.get(30, TimeUnit.SECONDS); var two = second.get(30, TimeUnit.SECONDS);
            assertTrue(one.reportNumber() < two.reportNumber()); assertEquals(two.reportId(), reports.latest(a.reviewId()).id());
        } finally { release.countDown(); }
    }
    @Test void auditFailureAndOuterRollbackLeaveNeitherReportNorServiceEvent() throws Exception {
        var a = review(); var id = UUID.randomUUID(); String name = "test_bootstrap_" + id.toString().replace("-", "");
        try (var c = admin(); var sql = c.createStatement()) {
            sql.execute("CREATE FUNCTION audit." + name + "() RETURNS trigger LANGUAGE plpgsql AS $body$ BEGIN IF NEW.report_id = '" + id + "'::uuid THEN RAISE EXCEPTION 'Injected audit failure'; END IF; RETURN NEW; END; $body$");
            sql.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON audit.catalog_bootstrap_impact_report_events FOR EACH ROW EXECUTE FUNCTION audit." + name + "()");
            try { assertThrows(RuntimeException.class, () -> writer.save(id, a.reviewId(), a.reviewSha256())); }
            finally { sql.execute("DROP TRIGGER " + name + " ON audit.catalog_bootstrap_impact_report_events"); sql.execute("DROP FUNCTION audit." + name + "()"); }
        }
        assertNull(reports.find(id)); assertEquals(0, dsl.fetchCount(CATALOG_BOOTSTRAP_IMPACT_REPORT_EVENTS, CATALOG_BOOTSTRAP_IMPACT_REPORT_EVENTS.REPORT_ID.eq(id)));
        assertThrows(DeliberateRollback.class, () -> new TransactionTemplate(transactions).execute(s -> {
            writer.save(id, a.reviewId(), a.reviewSha256()); throw new DeliberateRollback();
        })); assertNull(reports.find(id)); assertNull(reports.latest(a.reviewId()));
    }
    @Test void databaseDeniesMutationsWebAccessCallerTimesAndUnauditedReports() throws Exception {
        var a = review(); var saved = writer.save(UUID.randomUUID(), a.reviewId(), a.reviewSha256()).report();
        try (var core = core()) {
            for (var table : List.of("core.catalog_bootstrap_impact_reports", "audit.catalog_bootstrap_impact_report_events")) {
                denied(core, "UPDATE " + table + " SET id=id WHERE false"); denied(core, "DELETE FROM " + table + " WHERE false"); denied(core, "TRUNCATE " + table + " CASCADE");
            }
            denied(core, "INSERT INTO core.catalog_bootstrap_impact_reports (id, recorded_at) VALUES (gen_random_uuid(), clock_timestamp())");
            denied(core, "INSERT INTO core.catalog_bootstrap_impact_reports (id, report_number) OVERRIDING SYSTEM VALUE VALUES (gen_random_uuid(), 1)");
            denied(core, "INSERT INTO audit.catalog_bootstrap_impact_report_events (id, occurred_at) VALUES (gen_random_uuid(), clock_timestamp())");
            denied(core, "INSERT INTO core.catalog_publication_decisions DEFAULT VALUES");
            copy(core, saved.reportId(), "report"); assertEquals("23503", assertThrows(SQLException.class, core::commit).getSQLState()); core.rollback();
        }
        try (var web = DriverManager.getConnection(postgres.getJdbcUrl(), "authweave_web_runtime", "web-test-password")) {
            for (var table : List.of("core.catalog_bootstrap_impact_reports", "audit.catalog_bootstrap_impact_report_events")) {
                denied(web, "SELECT * FROM " + table + " WHERE false"); denied(web, "INSERT INTO " + table + " DEFAULT VALUES");
            }
        }
        assertEquals(saved.reportId(), reports.latest(a.reviewId()).id());
    }
    @ParameterizedTest @ValueSource(strings = {"approvalGranted", "writesPerformed", "coverageComplete", "storedReportVerified", "sourceVerificationPerformed", "baselineVerified", "evaluationReady", "recommendationReady", "caseDefinitions", "scenarioDefinitions", "cases", "scenarios", "uncoveredFacts", "scenarioUncoveredFacts", "ruleVersion", "profilePolicyVersion", "reviewSha256", "candidateSha256", "proposalId"})
    void databaseBoundaryCannotAcceptInflatedAuthorityMissingFieldsOrFabricatedProposal(String field) throws Exception {
        var a = review(); var saved = writer.save(UUID.randomUUID(), a.reviewId(), a.reviewSha256()).report();
        var expression = field.equals("proposalId") ? "report || '{\"proposalId\":\"forged\"}'::jsonb" : "report - '" + field + "'";
        try (var c = core()) {
            c.setAutoCommit(false); assertEquals("23514", assertThrows(SQLException.class, () -> copy(c, saved.reportId(), expression)).getSQLState()); c.rollback();
            if (List.of("approvalGranted", "writesPerformed", "coverageComplete", "storedReportVerified", "sourceVerificationPerformed", "baselineVerified", "evaluationReady", "recommendationReady").contains(field)) {
                assertEquals("23514", assertThrows(SQLException.class, () -> copy(c, saved.reportId(), "jsonb_set(report, '{" + field + "}', 'true'::jsonb)")).getSQLState()); c.rollback();
            }
        }
    }
    @Test void latestTamperedReceiptNeverFallsBackToOlderValidReceiptAndHistoryIsNotOverwritten() throws Exception {
        var a = review(); var first = writer.save(UUID.randomUUID(), a.reviewId(), a.reviewSha256()).report();
        var last = writer.save(UUID.randomUUID(), a.reviewId(), a.reviewSha256()).report();
        var body = (ObjectNode) last.report(); ((tools.jackson.databind.node.ArrayNode) body.get("cases")).remove(0);
        try {
            replace(last, body);
            assertEquals(last.reportId(), reports.latest(a.reviewId()).id());
            var result = preflight.bootstrap(a.reviewId(), a.reviewSha256());
            assertEquals(CatalogPublicationBootstrapImpactVerifier.Status.REPLAY_MISMATCH, result.storedBootstrapImpact().status());
            assertNull(result.storedBootstrapImpact().reportId()); assertTrue(result.blockers().contains(CatalogPublicationPreflight.Blocker.BOOTSTRAP_IMPACT_REPLAY_MISMATCH));
            assertEquals(first, reports.get(a.reviewId(), a.reviewSha256(), first.reportId()));
        } finally { replace(last, last.report()); }
        assertEquals(last.reportId(), preflight.bootstrap(a.reviewId(), a.reviewSha256()).storedBootstrapImpact().reportId());
        var other = review(); assertEquals(CatalogPublicationBootstrapImpactVerifier.Status.MISSING, preflight.bootstrap(other.reviewId(), other.reviewSha256()).storedBootstrapImpact().status());
    }
    @Test void committedPublicationClosesNewReportsAndDirectSqlButPreservesHistoricalRetries() throws Exception {
        var a = review(); var saved = writer.save(UUID.randomUUID(), a.reviewId(), a.reviewSha256()).report();
        var root = new CatalogPublicationLookupFixtures(mapper).root(Instant.now());
        try (var c = admin()) {
            c.setAutoCommit(false); var sql = org.jooq.impl.DSL.using(c, org.jooq.SQLDialect.POSTGRES);
            var d = root.row().decision(); var s = root.row().snapshot(); var e = root.row().event();
            var decisions = io.authweave.core.generated.jooq.tables.CatalogPublicationDecisions.CATALOG_PUBLICATION_DECISIONS;
            var snapshots = io.authweave.core.generated.jooq.tables.CatalogPublishedSnapshots.CATALOG_PUBLISHED_SNAPSHOTS;
            var events = io.authweave.core.generated.audit.tables.CatalogPublicationEvents.CATALOG_PUBLICATION_EVENTS;
            sql.insertInto(decisions).set(decisions.ID, d.id()).set(decisions.SNAPSHOT_ID, d.snapshotId()).set(decisions.DECISION_KIND, d.kind())
                    .set(decisions.CATALOG_VERSION, d.catalogVersion()).set(decisions.CONTENT_SHA256, d.contentSha256()).set(decisions.SNAPSHOT_SHA256, d.snapshotSha256())
                    .set(decisions.PUBLISHED_AT, java.time.OffsetDateTime.ofInstant(d.publishedAt(), java.time.ZoneOffset.UTC)).execute();
            sql.insertInto(snapshots).set(snapshots.ID, s.id()).set(snapshots.DECISION_ID, s.decisionId()).set(snapshots.DECISION_KIND, s.decisionKind())
                    .set(snapshots.CATALOG_VERSION, s.catalogVersion()).set(snapshots.CONTENT_SHA256, s.contentSha256()).set(snapshots.SNAPSHOT_SHA256, s.snapshotSha256())
                    .set(snapshots.PUBLISHED_AT, java.time.OffsetDateTime.ofInstant(s.publishedAt(), java.time.ZoneOffset.UTC)).set(snapshots.MANIFEST, org.jooq.JSONB.valueOf(s.manifest())).execute();
            sql.insertInto(events).set(events.ID, e.id()).set(events.DECISION_ID, e.decisionId()).set(events.SNAPSHOT_ID, e.snapshotId())
                    .set(events.SNAPSHOT_SHA256, e.snapshotSha256()).set(events.DECISION_KIND, e.decisionKind()).set(events.ACTION, e.action())
                    .set(events.ACTOR_TYPE, e.actorType()).set(events.ACTOR_ISSUER, e.issuer()).set(events.ACTOR_SUBJECT, e.subject())
                    .set(events.ACTOR_PROJECT_ID, e.projectId()).set(events.ACTOR_ORG_ID, e.orgId()).set(events.AUTHENTICATED_AT, java.time.OffsetDateTime.ofInstant(e.authenticatedAt(), java.time.ZoneOffset.UTC))
                    .set(events.CORRELATION_ID, e.correlationId()).set(events.OUTCOME, e.outcome()).execute(); c.commit();
            try {
                assertEquals(CatalogBootstrapImpactReportException.Reason.REGISTRY_NOT_EMPTY, assertThrows(CatalogBootstrapImpactReportException.class,
                        () -> writer.save(UUID.randomUUID(), a.reviewId(), a.reviewSha256())).reason());
                assertEquals(saved, writer.save(saved.reportId(), a.reviewId(), a.reviewSha256()).report());
                try (var core = core()) { core.setAutoCommit(false); assertEquals("23514", assertThrows(SQLException.class, () -> copy(core, saved.reportId(), "report")).getSQLState()); core.rollback(); }
                var result = preflight.bootstrap(a.reviewId(), a.reviewSha256()); assertTrue(result.storedBootstrapImpact().historicalReplayVerified());
                assertTrue(result.blockers().contains(CatalogPublicationPreflight.Blocker.BOOTSTRAP_REGISTRY_NOT_EMPTY)); assertFalse(result.publicationReady());
            } finally {
                // Remove only this synthetic root from the disposable test DB, preserving unrelated review/history rows.
                sql.deleteFrom(events).where(events.ID.eq(e.id())).execute(); sql.deleteFrom(snapshots).where(snapshots.ID.eq(s.id())).execute();
                sql.deleteFrom(decisions).where(decisions.ID.eq(d.id())).execute(); c.commit();
            }
        }
    }
    @Test void databaseBindsReportAndServiceAuditToExactReviewCandidateAndReportHashes() throws Exception {
        var a = review(); var saved = writer.save(UUID.randomUUID(), a.reviewId(), a.reviewSha256()).report();
        try (var c = core()) { c.setAutoCommit(false);
            try (var sql = c.prepareStatement("INSERT INTO core.catalog_bootstrap_impact_reports (id,review_id,candidate_sha256,review_sha256,report_schema_version,canonicalization_version,report_sha256,report) SELECT ?,review_id,candidate_sha256,repeat('0',64),report_schema_version,canonicalization_version,report_sha256,jsonb_set(report,'{reviewSha256}',to_jsonb(repeat('0',64))) FROM core.catalog_bootstrap_impact_reports WHERE id=?")) {
                sql.setObject(1, UUID.randomUUID()); sql.setObject(2, saved.reportId()); assertEquals("23503", assertThrows(SQLException.class, sql::executeUpdate).getSQLState());
            } finally { c.rollback(); }
            var copied = copy(c, saved.reportId(), "report");
            try (var sql = c.prepareStatement("INSERT INTO audit.catalog_bootstrap_impact_report_events (id,report_id,review_id,candidate_sha256,review_sha256,report_sha256,action,actor_type,actor_id,correlation_id,outcome) SELECT ?,?,review_id,candidate_sha256,review_sha256,repeat('0',64),action,actor_type,actor_id,correlation_id,outcome FROM audit.catalog_bootstrap_impact_report_events WHERE report_id=?")) {
                sql.setObject(1, UUID.randomUUID()); sql.setObject(2, copied); sql.setObject(3, saved.reportId()); assertEquals("23503", assertThrows(SQLException.class, sql::executeUpdate).getSQLState());
            } finally { c.rollback(); }
        }
    }
    private CatalogBootstrapReview review() {
        var candidate = new CatalogPublicationLookupFixtures(mapper).root(Instant.now()).snapshot().catalog().asDraft();
        var request = new CatalogBootstrapReviewRequest(1, UUID.randomUUID(), CatalogDraftCanonicalizer.sha256(candidate), candidate,
                validator.validate(candidate).facts().stream().map(f -> new CatalogBootstrapReviewRequest.Observation(f.optionId(), f.path(), SOURCE_SUPPORTS_CLAIM)).toList(),
                CatalogBootstrapReviewRequest.Confirmation.MANUAL_BOOTSTRAP_SOURCE_REVIEW);
        return reviews.record(request, new CuratorActor("http://localhost:8081", "synthetic-receipt-curator", "123456789012345678", "987654321098765432", Instant.now())).review();
    }
    private void replace(CatalogBootstrapImpactReport report, tools.jackson.databind.JsonNode json) throws Exception {
        try (var c = admin()) { c.setAutoCommit(false);
            try (var sql = c.createStatement()) { sql.execute("SET LOCAL session_replication_role=replica"); }
            String hash = CatalogDraftCanonicalizer.sha256(json);
            try (var sql = c.prepareStatement("UPDATE core.catalog_bootstrap_impact_reports SET report=?::jsonb,report_sha256=? WHERE id=?")) {
                sql.setString(1, mapper.writeValueAsString(json)); sql.setString(2, hash); sql.setObject(3, report.reportId()); sql.executeUpdate();
            }
            try (var sql = c.prepareStatement("UPDATE audit.catalog_bootstrap_impact_report_events SET report_sha256=? WHERE report_id=?")) {
                sql.setString(1, hash); sql.setObject(2, report.reportId()); sql.executeUpdate();
            } c.commit();
        }
    }
    private UUID copy(Connection c, UUID source, String expression) throws SQLException {
        var id = UUID.randomUUID();
        try (var sql = c.prepareStatement("INSERT INTO core.catalog_bootstrap_impact_reports (id,review_id,candidate_sha256,review_sha256,report_schema_version,canonicalization_version,report_sha256,report) SELECT ?,review_id,candidate_sha256,review_sha256,report_schema_version,canonicalization_version,report_sha256," + expression + " FROM core.catalog_bootstrap_impact_reports WHERE id=?")) {
            sql.setObject(1, id); sql.setObject(2, source); sql.executeUpdate();
        } return id;
    }
    private int race(UUID id, CatalogBootstrapReview review, CyclicBarrier start) throws Exception {
        start.await(10, TimeUnit.SECONDS); try { return writer.save(id, review.reviewId(), review.reviewSha256()).changed() ? 1 : 0; }
        catch (CatalogBootstrapImpactReportException e) { assertEquals(CatalogBootstrapImpactReportException.Reason.ID_CONFLICT, e.reason()); return -1; }
    }
    private static Connection core() throws SQLException { return DriverManager.getConnection(postgres.getJdbcUrl(), "authweave_core_runtime", "core-test-password"); }
    private static Connection admin() throws SQLException { return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); }
    private static void denied(Connection c, String sql) throws Exception { c.setAutoCommit(false); try (var statement = c.createStatement()) {
        assertEquals("42501", assertThrows(SQLException.class, () -> statement.execute(sql)).getSQLState()); } finally { c.rollback(); } }
    private static void await(CountDownLatch latch) { try { assertTrue(latch.await(10, TimeUnit.SECONDS)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); } }
    private static final class DeliberateRollback extends RuntimeException { }
}

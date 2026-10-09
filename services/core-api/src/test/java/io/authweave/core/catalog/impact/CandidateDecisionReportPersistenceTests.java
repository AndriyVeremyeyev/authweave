package io.authweave.core.catalog.impact;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
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
import io.authweave.core.catalog.auditability.CatalogAuditabilityReviewService;
import io.authweave.core.catalog.draft.CatalogAuditabilityDraftValidator;
import io.authweave.core.catalog.publication.CatalogBootstrapReviewService;
import io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static io.authweave.core.catalog.impact.CandidateAuditabilityInputTests.*;
import static io.authweave.core.catalog.impact.CandidateDecisionReportException.Reason.*;
import static io.authweave.core.generated.jooq.tables.CandidateDecisionReports.CANDIDATE_DECISION_REPORTS;
import static io.authweave.core.generated.audit.tables.CandidateDecisionReportEvents.CANDIDATE_DECISION_REPORT_EVENTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Only isolated fictional review/report rows; no live IdP, grants, project database or paid calls. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local-candidate-decision-write")
class CandidateDecisionReportPersistenceTests {
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
    @Autowired LocalCandidateDecisionReportWriter writer;
    @Autowired CandidateDecisionReportService reports;
    @Autowired CandidateDecisionReportRepository repository;
    @Autowired StoredCandidateDecisionService loader;
    @Autowired CatalogBootstrapReviewService bases;
    @Autowired CatalogAuditabilityReviewService audits;
    @Autowired CatalogAuditabilityDraftValidator drafts;
    @Autowired ObjectMapper mapper;
    @Autowired DSLContext dsl;
    @Autowired PlatformTransactionManager transactions;
    private CandidateDecisionReviewFixture fixtures() { return new CandidateDecisionReviewFixture(mapper, bases, audits, drafts); }
    private CandidateDecisionReportService.Request request() {
        var a = fixtures().stored(); var b = fixtures().stored(base -> ((ObjectNode) base.at("/options/0/facts/SCIM")).put("availability", "UNAVAILABLE"), audit -> { }, Verdict.SOURCE_SUPPORTS_CLAIM);
        return new CandidateDecisionReportService.Request(6, profile(), weights(), a.reference(), b.reference());
    }
    private LocalCandidateDecisionReportWriter.SaveResult save(UUID id, CandidateDecisionReportService.Request r) {
        return writer.save(id, CandidateDecisionReportService.inputSha256(r), r);
    }
    private CandidateDecisionReportService.Receipt saved() { return save(UUID.randomUUID(), request()).receipt(); }
    private List<Integer> counts() { return List.of(dsl.fetchCount(CANDIDATE_DECISION_REPORTS), dsl.fetchCount(CANDIDATE_DECISION_REPORT_EVENTS)); }
    private Connection admin() throws SQLException { return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); }
    private Connection core() throws SQLException { return DriverManager.getConnection(postgres.getJdbcUrl(), "authweave_core_runtime", "core-test-password"); }

    @Test void fullDecisionReportAndMinimalServiceAuditAreAtomicBoundAndExactlyReplayable() {
        var request = request(); var id = UUID.randomUUID(); var result = save(id, request); assertTrue(result.created());
        var receipt = result.receipt(); var body = receipt.report(); assertTrue(receipt.historicalReplayVerified());
        assertEquals("ELIGIBLE", body.at("/impact/before/candidates/0/hardChecks/hardVerdict").asText());
        assertEquals("EXCLUDED", body.at("/impact/after/candidates/0/hardChecks/hardVerdict").asText());
        assertTrue(body.at("/impact/decisionOutcomesChanged").asBoolean()); assertTrue(body.at("/impact/resultDetailsChanged").asBoolean());
        assertEquals(CandidateDecisionReportService.inputSha256(request), receipt.inputSha256()); assertEquals(DecisionCanonicalizer.sha256(body), receipt.reportSha256());
        assertEquals(body.get("evaluatedAt"), body.at("/impact/before/binding/inputs/hardChecks/evaluatedAt"));
        assertEquals(body.get("evaluatedAt"), body.at("/impact/after/binding/inputs/hardChecks/evaluatedAt"));
        assertEquals(DecisionCanonicalizer.sha256(request.profile()), body.at("/impact/profileSha256").asText());
        assertEquals(DecisionCanonicalizer.sha256(request.weights()), body.at("/impact/weightsSha256").asText());
        var before = counts(); assertEquals(receipt, reports.get(id, receipt.reportSha256())); assertEquals(before, counts());
        var event = repository.find(id).event(); assertEquals("SERVICE", event.actorType());
        assertFalse(mapper.writeValueAsString(event).contains("sourceUrl")); assertFalse(body.toString().contains("fictional-reviewer"));
        for (var field : List.of("currentCuratorAuthorityVerified", "sourceVerificationPerformed", "configurationVerified", "complianceVerified", "approvalGranted", "publicationReady", "writesPerformed")) assertFalse(body.get(field).asBoolean());
    }
    @Test void idempotentRetryRetainsOriginalClockAndOlderResultSurvivesLaterCandidateChanges() {
        var request = request(); var id = UUID.randomUUID(); var original = save(id, request).receipt(); var before = counts();
        var retry = save(id, request); assertFalse(retry.created()); assertEquals(original, retry.receipt()); assertEquals(before, counts());
        var later = saved(); assertNotEquals(original.reportId(), later.reportId()); assertNotEquals(original.reportSha256(), later.reportSha256());
        assertEquals(original, reports.get(id, original.reportSha256())); assertEquals(original.report().get("evaluatedAt"), retry.receipt().report().get("evaluatedAt"));
    }
    @Test void sameCandidateAndAbsentSupplementAreStoredWithoutInventingImpactOrAuditEvidence() {
        var fixture = fixtures().stored(); var ref = fixture.reference();
        var noAudit = new StoredCandidateDecisionService.Reference(ref.reviewId(), ref.reviewSha256(), ref.decisionCatalogSha256(), null);
        var request = new CandidateDecisionReportService.Request(6, profile(), weights(), noAudit, noAudit); var receipt = save(UUID.randomUUID(), request).receipt();
        assertFalse(receipt.report().at("/impact/candidateInputsChanged").asBoolean()); assertTrue(receipt.report().at("/impact/deltas").isEmpty());
        assertEquals("UNRESOLVED", receipt.report().at("/impact/before/candidates/0/hardChecks/hardVerdict").asText());
        assertTrue(receipt.report().at("/beforeReviews/auditability").isNull()); assertNull(repository.find(receipt.reportId()).beforeAuditReviewId());
    }
    @Test void requestAndReceiptDocumentsCannotBeMutatedThroughCallerReferences() {
        var original = request(); var profile = original.profile(); var request = new CandidateDecisionReportService.Request(6, profile, original.weights(), original.before(), original.after());
        var digest = CandidateDecisionReportService.inputSha256(request); ((ObjectNode) profile.get("security")).put("auditability", "NOT_REQUIRED");
        ((ObjectNode) request.profile().get("security")).put("auditability", "NOT_REQUIRED"); assertEquals(digest, CandidateDecisionReportService.inputSha256(request));
        var receipt = writer.save(UUID.randomUUID(), digest, request).receipt(); ((ObjectNode) receipt.report()).put("approvalGranted", true);
        assertFalse(receipt.report().get("approvalGranted").asBoolean()); assertEquals(receipt, reports.get(receipt.reportId(), receipt.reportSha256()));
    }
    @Test void wrongInputPinIdReuseOrMissingHistoricalReferenceLeavesNoNewRows() {
        var request = request(); var id = UUID.randomUUID(); var before = counts();
        assertEquals(CONFLICT, assertThrows(CandidateDecisionReportException.class, () -> writer.save(id, "0".repeat(64), request)).reason());
        assertEquals(before, counts()); var saved = save(id, request).receipt(); var other = request(); before = counts();
        assertEquals(CONFLICT, assertThrows(CandidateDecisionReportException.class, () -> save(id, other)).reason()); assertEquals(before, counts());
        var missing = new StoredCandidateDecisionService.Reference(UUID.randomUUID(), request.before().reviewSha256(), request.before().decisionCatalogSha256(), null);
        assertThrows(RuntimeException.class, () -> save(UUID.randomUUID(), new CandidateDecisionReportService.Request(6, request.profile(), request.weights(), missing, request.after())));
        assertEquals(before, counts()); assertEquals(CONFLICT, assertThrows(CandidateDecisionReportException.class, () -> reports.get(id, "0".repeat(64))).reason());
        assertEquals(NOT_FOUND, assertThrows(CandidateDecisionReportException.class, () -> reports.get(UUID.randomUUID(), saved.reportSha256())).reason());
        assertThrows(IllegalArgumentException.class, () -> new CandidateDecisionReportService.Request(5, request.profile(), request.weights(), request.before(), request.after()));
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void concurrentSameIdIsIdempotentButCannotRebindDifferentInputs(boolean different) throws Exception {
        var first = request(); var second = different ? request() : first; var id = UUID.randomUUID(); var barrier = new CyclicBarrier(2); var before = counts();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of(pool.submit(() -> race(id, first, barrier)), pool.submit(() -> race(id, second, barrier)));
            int created = 0, conflicts = 0; for (var future : futures) { int result = future.get(30, TimeUnit.SECONDS); if (result == 1) created++; if (result == -1) conflicts++; }
            assertEquals(1, created); assertEquals(different ? 1 : 0, conflicts);
        }
        assertEquals(List.of(before.get(0) + 1, before.get(1) + 1), counts());
    }
    private int race(UUID id, CandidateDecisionReportService.Request request, CyclicBarrier start) throws Exception {
        start.await(30, TimeUnit.SECONDS);
        try { return save(id, request).created() ? 1 : 0; }
        catch (CandidateDecisionReportException failure) { if (failure.reason() != CONFLICT) throw failure; return -1; }
    }
    @Test void auditFailureAndOuterRollbackPersistNeitherReportNorEvent() throws Exception {
        var request = request(); var id = UUID.randomUUID(); var name = "test_decision_" + id.toString().replace("-", ""); var before = counts();
        try (var c = admin(); var sql = c.createStatement()) {
            sql.execute("CREATE FUNCTION audit." + name + "() RETURNS trigger LANGUAGE plpgsql AS $body$ BEGIN IF NEW.report_id = '" + id + "'::uuid THEN RAISE EXCEPTION 'Injected test audit failure'; END IF; RETURN NEW; END; $body$");
            sql.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON audit.candidate_decision_report_events FOR EACH ROW EXECUTE FUNCTION audit." + name + "()");
            try { assertThrows(RuntimeException.class, () -> save(id, request)); }
            finally { sql.execute("DROP TRIGGER " + name + " ON audit.candidate_decision_report_events"); sql.execute("DROP FUNCTION audit." + name + "()"); }
        }
        assertNull(repository.find(id)); assertEquals(before, counts());
        assertThrows(DeliberateRollback.class, () -> new TransactionTemplate(transactions).execute(s -> { save(id, request); throw new DeliberateRollback(); }));
        assertNull(repository.find(id)); assertEquals(before, counts());
    }
    private static final class DeliberateRollback extends RuntimeException { }
    @Test void runtimeCannotOverwriteDeleteSetTimestampsOrPublishAndWebCannotReadReports() throws Exception {
        var receipt = saved();
        try (var c = core()) {
            for (var table : List.of("core.candidate_decision_reports", "audit.candidate_decision_report_events")) {
                denied(c, "UPDATE " + table + " SET id = id WHERE false"); denied(c, "DELETE FROM " + table + " WHERE false");
            }
            denied(c, "INSERT INTO core.candidate_decision_reports (id, recorded_at) VALUES (gen_random_uuid(), clock_timestamp())");
            denied(c, "INSERT INTO audit.candidate_decision_report_events (id, occurred_at) VALUES (gen_random_uuid(), clock_timestamp())");
            denied(c, "INSERT INTO core.catalog_publication_decisions DEFAULT VALUES");
        }
        try (var web = DriverManager.getConnection(postgres.getJdbcUrl(), "authweave_web_runtime", "web-test-password")) {
            for (var table : List.of("core.candidate_decision_reports", "audit.candidate_decision_report_events")) {
                denied(web, "SELECT * FROM " + table + " WHERE false"); denied(web, "INSERT INTO " + table + " DEFAULT VALUES");
            }
        }
        assertEquals(receipt, reports.get(receipt.reportId(), receipt.reportSha256()));
    }
    private static void denied(Connection c, String sql) { assertEquals("42501", assertThrows(SQLException.class, () -> { try (var s = c.createStatement()) { s.execute(sql); } }).getSQLState()); }
    @Test void missingMandatoryEventCannotCommitEvenThroughDirectRuntimeSql() throws Exception {
        var receipt = saved(); var row = repository.find(receipt.reportId()); var id = UUID.randomUUID();
        try (var c = core()) {
            c.setAutoCommit(false); insert(DSL.using(c, SQLDialect.POSTGRES), id, row, receipt.report(), receipt.reportSha256(), false);
            assertEquals("23503", assertThrows(SQLException.class, c::commit).getSQLState()); c.rollback();
        }
        assertNull(repository.find(id));
    }
    @ParameterizedTest @ValueSource(strings = {"currentCuratorAuthorityVerified", "sourceVerificationPerformed", "configurationVerified", "complianceVerified", "approvalGranted", "publicationReady", "writesPerformed", "storedReviewsVerified"})
    void databaseRejectsMissingOrInflatedAuthorityFields(String field) throws Exception {
        var receipt = saved(); var row = repository.find(receipt.reportId());
        try (var c = core()) {
            c.setAutoCommit(false); var sql = DSL.using(c, SQLDialect.POSTGRES); var changed = (ObjectNode) receipt.report(); changed.remove(field);
            assertThrows(RuntimeException.class, () -> insert(sql, UUID.randomUUID(), row, changed, DecisionCanonicalizer.sha256(changed), false)); c.rollback();
            changed.set(field, mapper.valueToTree(!field.equals("storedReviewsVerified")));
            assertThrows(RuntimeException.class, () -> insert(sql, UUID.randomUUID(), row, changed, DecisionCanonicalizer.sha256(changed), false)); c.rollback();
        }
    }
    @Test void checksumAndValidServiceEventCannotHideAForgedKernelResult() throws Exception {
        var original = saved(); var row = repository.find(original.reportId()); var id = UUID.randomUUID(); var forged = (ObjectNode) original.report();
        ((ObjectNode) forged.at("/impact/after")).set("shortlist", mapper.createArrayNode().add("fabricated-winner")); var sha = DecisionCanonicalizer.sha256(forged);
        try (var c = core()) { c.setAutoCommit(false); insert(DSL.using(c, SQLDialect.POSTGRES), id, row, forged, sha, true); c.commit(); }
        assertEquals(READ_UNAVAILABLE, assertThrows(CandidateDecisionReportException.class, () -> reports.get(id, sha)).reason());
        assertEquals(original, reports.get(original.reportId(), original.reportSha256()));
    }
    private void insert(DSLContext sql, UUID id, CandidateDecisionReportRepository.Row source, JsonNode body, String hash, boolean audit) {
        var r = CANDIDATE_DECISION_REPORTS; sql.insertInto(r).set(r.ID, id).set(r.INPUT_SHA256, source.inputSha256()).set(r.REPORT_SHA256, hash)
                .set(r.BEFORE_REVIEW_ID, source.beforeReviewId()).set(r.AFTER_REVIEW_ID, source.afterReviewId())
                .set(r.BEFORE_AUDIT_REVIEW_ID, source.beforeAuditReviewId()).set(r.AFTER_AUDIT_REVIEW_ID, source.afterAuditReviewId()).set(r.REPORT, JSONB.jsonb(body.toString())).execute();
        if (audit) {
            var e = CANDIDATE_DECISION_REPORT_EVENTS; sql.insertInto(e).set(e.ID, UUID.randomUUID()).set(e.REPORT_ID, id)
                    .set(e.INPUT_SHA256, source.inputSha256()).set(e.REPORT_SHA256, hash).set(e.ACTION, "candidate-decision.recorded")
                    .set(e.ACTOR_TYPE, "SERVICE").set(e.ACTOR_ID, "core-api-local-catalog").set(e.CORRELATION_ID, UUID.randomUUID()).set(e.OUTCOME, "SUCCEEDED").execute();
        }
    }
    @ParameterizedTest @ValueSource(strings = {"body", "event-time"})
    void damagedExactReceiptIsDeniedWithoutFallingBackOrRefreshing(String variant) throws Exception {
        var receipt = saved(); var before = counts();
        try (var c = admin()) {
            var sql = DSL.using(c, SQLDialect.POSTGRES); var r = CANDIDATE_DECISION_REPORTS; var e = CANDIDATE_DECISION_REPORT_EVENTS;
            var original = sql.select(r.REPORT).from(r).where(r.ID.eq(receipt.reportId())).fetchOne(r.REPORT);
            var eventTime = sql.select(e.OCCURRED_AT).from(e).where(e.REPORT_ID.eq(receipt.reportId())).fetchOne(e.OCCURRED_AT);
            try {
                if (variant.equals("event-time")) sql.update(e).set(e.OCCURRED_AT, eventTime.plusSeconds(60)).where(e.REPORT_ID.eq(receipt.reportId())).execute();
                else { var changed = (ObjectNode) receipt.report(); ((ObjectNode) changed.at("/impact/after")).put("status", "RANKED_SHORTLIST");
                    sql.update(r).set(r.REPORT, JSONB.jsonb(changed.toString())).where(r.ID.eq(receipt.reportId())).execute(); }
                assertEquals(READ_UNAVAILABLE,
                        assertThrows(CandidateDecisionReportException.class, () -> reports.get(receipt.reportId(), receipt.reportSha256())).reason());
            } finally {
                sql.update(r).set(r.REPORT, original).where(r.ID.eq(receipt.reportId())).execute();
                sql.update(e).set(e.OCCURRED_AT, eventTime).where(e.REPORT_ID.eq(receipt.reportId())).execute();
            }
        }
        assertEquals(before, counts()); assertEquals(receipt, reports.get(receipt.reportId(), receipt.reportSha256()));
    }
    @Test void unsupportedHistoricalVersionIsNotSilentlyReplayedAsCurrentPolicy() {
        var receipt = saved(); var row = repository.find(receipt.reportId()); var body = (ObjectNode) receipt.report();
        body.put("reportVersion", "future-version");
        // Simulate a future migrated row at the reader boundary, without weakening any DB constraint.
        var unsupported = new CandidateDecisionReportRepository.Row(row.id(), row.inputSha256(), row.reportSha256(), row.beforeReviewId(), row.afterReviewId(),
                row.beforeAuditReviewId(), row.afterAuditReviewId(), body.toString(), row.bytes(), row.recordedAt(), row.event());
        assertEquals(UNSUPPORTED_VERSION, assertThrows(CandidateDecisionReportException.class, () -> reports.verify(unsupported)).reason());
        assertEquals(receipt, reports.get(receipt.reportId(), receipt.reportSha256()));
    }
    @Test void boundedReadRejectsUnavailableBodyAndDatabaseFailureIsNotSwallowed() {
        var receipt = saved(); var bounded = repository.find(receipt.reportId(), 0); assertNull(bounded.body()); assertTrue(bounded.bytes() > 0);
        assertEquals(READ_UNAVAILABLE, assertThrows(CandidateDecisionReportException.class, () -> reports.verify(bounded)).reason());
        assertThrows(IllegalArgumentException.class, () -> repository.find(receipt.reportId(), -1));
        var broken = mock(CandidateDecisionReportRepository.class); var id = UUID.randomUUID();
        when(broken.find(id)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Fictional DB read failure"));
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> new CandidateDecisionReportService(broken, loader).get(id, "0".repeat(64)));
    }
}

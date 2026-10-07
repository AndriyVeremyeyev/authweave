package io.authweave.core.catalog.impact;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.PostgresIntegrationTest;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.proposal.*;
import static io.authweave.core.generated.jooq.tables.CatalogFactPathReports.CATALOG_FACT_PATH_REPORTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"local-catalog-write", "local-catalog-regression-write"})
class CatalogFactPathReportPersistenceTests extends PostgresIntegrationTest {
    @Autowired LocalCatalogProposalWriter proposals;
    @Autowired CatalogProposalRepository proposalReads;
    @Autowired LocalCatalogFactPathReportWriter writer;
    @Autowired CatalogFactPathReportRepository reports;
    @Autowired ObjectMapper mapper;
    @Autowired DSLContext dsl;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;

    @Test void savesAnExactImmutableReportAndAtomicMinimalEventWithoutAdvancingTheProposal() throws Exception {
        var request = request(); var revision = proposals.save(request, null).proposal(); var id = UUID.randomUUID();
        var saved = writer.save(id, request.proposalId(), 0); assertTrue(saved.changed());
        var report = saved.report(); assertEquals(id, report.reportId()); assertTrue(report.reportNumber() > 0);
        assertEquals(revision.proposalSha256(), report.proposalSha256()); assertEquals(0, report.proposalVersion());
        assertEquals(CatalogDraftCanonicalizer.sha256(report.report()), report.reportSha256());
        assertEquals("CATALOG_FACT_PATH_REGRESSION_IMPACT", report.report().get("scope").asText());
        assertTrue(report.report().get("storedRequestDigestVerified").asBoolean()); assertEquals(0, report.report().get("storedProposalVersion").asLong());
        assertFalse(report.report().get("writesPerformed").asBoolean()); assertFalse(report.report().get("approvalGranted").asBoolean());
        var event = reports.find(id).event();
        assertEquals(report.reportSha256(), event.reportSha256()); assertEquals("catalog-fact-path.recorded", event.action());
        assertEquals("SERVICE", event.actorType()); assertEquals("core-api-local-catalog", event.actorId()); assertEquals("SUCCEEDED", event.outcome());
        assertFalse(mapper.writeValueAsString(event).contains(request.rationale())); assertFalse(mapper.writeValueAsString(event).contains("sourceUrl"));
        assertEquals(revision, proposalReads.current(request.proposalId())); assertEquals(1, proposalReads.events(request.proposalId(), null, 100).items().size());
        var retry = writer.save(id, request.proposalId(), 0); assertFalse(retry.changed()); assertEquals(report, retry.report());
        assertEquals(event, reports.find(id).event());
        ((ObjectNode) report.report()).put("approvalGranted", true); assertFalse(report.report().get("approvalGranted").asBoolean());
    }

    @Test void retriesDoNotReplayUnderNewRulesAndReceiptTimeComesFromDatabase() throws Exception {
        var request = request(); proposals.save(request, null); var id = UUID.randomUUID(); var first = writer.save(id, request.proposalId(), 0).report();
        var unavailable = mock(CatalogImpactService.class);
        var retryWriter = new LocalCatalogFactPathReportWriter(dsl, mapper, unavailable, reports);
        var retry = new TransactionTemplate(transactions).execute(s -> retryWriter.save(id, request.proposalId(), 0));
        assertFalse(retry.changed()); assertEquals(first, retry.report()); verifyNoInteractions(unavailable);
        var clock = Clock.fixed(Instant.parse("2099-01-01T00:00:00Z"), ZoneOffset.UTC);
        var service = new CatalogImpactService(new CatalogChangePreviewService(new CatalogDraftValidator(clock), clock));
        var later = new LocalCatalogFactPathReportWriter(dsl, mapper, service, reports);
        var next = new TransactionTemplate(transactions).execute(s -> later.save(UUID.randomUUID(), request.proposalId(), 0)).report();
        var evaluatedAt = Instant.parse(next.report().get("evaluatedAt").asText());
        assertTrue(evaluatedAt.isBefore(next.recordedAt())); assertTrue(next.recordedAt().isBefore(evaluatedAt.plusSeconds(30)));
        assertNotEquals(clock.instant(), evaluatedAt);
        assertEquals(CatalogDraftCanonicalizer.sha256(next.report()), next.reportSha256());
        assertEquals(first, reports.get(request.proposalId(), 0, id)); assertEquals(next.reportId(), reports.latest(request.proposalId(), 0).id());
    }

    @Test void revisionScopeAndIdempotencyIdentityCannotBeSilentlyRebound() throws Exception {
        var a = request(); var b = request(); proposals.save(a, null); proposals.save(b, null);
        var id = UUID.randomUUID(); var first = writer.save(id, a.proposalId(), 0).report();
        proposals.save(new CatalogChangePreviewRequest(1, a.proposalId(), "Changed rationale", a.expectedBaseSha256(), a.base(), a.candidate()), 0L);
        assertEquals(CatalogFactPathReportException.Reason.ID_CONFLICT, assertThrows(CatalogFactPathReportException.class, () -> writer.save(id, a.proposalId(), 1)).reason());
        assertEquals(CatalogFactPathReportException.Reason.ID_CONFLICT, assertThrows(CatalogFactPathReportException.class, () -> writer.save(id, b.proposalId(), 0)).reason());
        assertEquals(first, writer.save(id, a.proposalId(), 0).report());
        var second = writer.save(UUID.randomUUID(), a.proposalId(), 1).report(); assertNotEquals(first.proposalSha256(), second.proposalSha256());
        assertThrows(CatalogFactPathReportException.class, () -> reports.get(b.proposalId(), 0, id));
        assertThrows(CatalogFactPathReportException.class, () -> reports.get(a.proposalId(), 1, id));
        assertThrows(CatalogFactPathReportException.class, () -> writer.save(UUID.randomUUID(), a.proposalId(), 2));
        assertThrows(CatalogFactPathReportException.class, () -> writer.save(UUID.randomUUID(), UUID.randomUUID(), 0));
        assertNull(reports.latest(a.proposalId(), 2));
        assertNull(reports.latest(b.proposalId(), 0));
    }

    @Test void latestAndBoundedReadsAreScopedByExactRevisionAndByteBudget() throws Exception {
        var a = request(); var b = request(); proposals.save(a, null); proposals.save(b, null);
        assertNull(reports.latest(a.proposalId(), 0));
        var first = writer.save(UUID.randomUUID(), a.proposalId(), 0).report(); writer.save(UUID.randomUUID(), b.proposalId(), 0);
        var second = writer.save(UUID.randomUUID(), a.proposalId(), 0).report();
        var row = reports.latest(a.proposalId(), 0); assertEquals(second.reportId(), row.id());
        assertEquals(b.proposalId(), reports.latest(b.proposalId(), 0).proposalId());
        assertNotNull(row.report()); assertNotNull(row.event());
        var withheld = reports.latest(a.proposalId(), 0, row.reportBytes() - 1);
        assertNull(withheld.report()); assertEquals(row.reportBytes(), withheld.reportBytes());
        assertNotNull(reports.latest(a.proposalId(), 0, row.reportBytes()).report());
        var request = reports.request(a.proposalId(), 0);
        assertNull(reports.request(a.proposalId(), 0, request.bytes() - 1).body());
        assertNotNull(reports.request(a.proposalId(), 0, request.bytes()).body());
        for (long value : List.of(-1L, 9007199254740992L)) assertThrows(IllegalArgumentException.class, () -> reports.latest(a.proposalId(), value));
        for (long budget : List.of(-1L, CatalogFactPathReportRepository.MAX_JSON_BYTES + 1)) {
            assertThrows(IllegalArgumentException.class, () -> reports.latest(a.proposalId(), 0, budget));
            assertThrows(IllegalArgumentException.class, () -> reports.request(a.proposalId(), 0, budget));
        }
        assertNull(reports.latest(a.proposalId(), 1)); assertEquals(first, reports.get(a.proposalId(), 0, first.reportId()));
        assertEquals(0, dsl.fetchCount(io.authweave.core.generated.jooq.tables.CatalogImpactReports.CATALOG_IMPACT_REPORTS,
                io.authweave.core.generated.jooq.tables.CatalogImpactReports.CATALOG_IMPACT_REPORTS.PROPOSAL_ID.eq(a.proposalId())));
    }

    @ParameterizedTest @ValueSource(strings = {"identical", "different-ids", "different-proposals"})
    void concurrentCommandsDoNotDuplicateOrRebindReports(String scenario) throws Exception {
        var a = request(); var b = request(); proposals.save(a, null); proposals.save(b, null); var id = UUID.randomUUID();
        var secondId = scenario.equals("different-ids") ? UUID.randomUUID() : id;
        var secondProposal = scenario.equals("different-proposals") ? b.proposalId() : a.proposalId();
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(executor.submit(() -> race(id, a.proposalId(), start)), executor.submit(() -> race(secondId, secondProposal, start)));
            int changed = 0, conflicts = 0;
            for (var f : futures) { int result = f.get(30, TimeUnit.SECONDS); if (result == 1) changed++; if (result == -1) conflicts++; }
            assertEquals(scenario.equals("different-ids") ? 2 : 1, changed); assertEquals(scenario.equals("different-proposals") ? 1 : 0, conflicts);
        }
        assertEquals(scenario.equals("different-ids") ? 2 : 1, dsl.fetchCount(CATALOG_FACT_PATH_REPORTS, CATALOG_FACT_PATH_REPORTS.PROPOSAL_ID.in(a.proposalId(), b.proposalId())));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sameProposalLockOrdersReportsBeforeNumberAllocation(boolean reservePoolConnection) throws Exception {
        var request = request(); proposals.save(request, null); var saved = new CountDownLatch(1); var release = new CountDownLatch(1);
        var blocker = new AtomicInteger();
        try (var observer = core(); var reservation = reservePoolConnection ? dataSource.getConnection() : null;
                var executor = Executors.newFixedThreadPool(2)) {
            // A third pooled read can starve behind the two writers. This same-role observer stays independent.
            assertEquals(3, dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class).getMaximumPoolSize());
            assertTrue(observer.getAutoCommit()); assertEquals(Connection.TRANSACTION_READ_COMMITTED, observer.getTransactionIsolation());
            var observedReports = new CatalogFactPathReportRepository(DSL.using(observer, SQLDialect.POSTGRES,
                    new Settings().withQueryTimeout(2)), mapper);
            var first = executor.submit(() -> new TransactionTemplate(transactions).execute(s -> {
                blocker.set(dsl.select(DSL.field("pg_backend_pid()", Integer.class)).fetchOne(0, Integer.class));
                var result = writer.save(UUID.randomUUID(), request.proposalId(), 0); saved.countDown(); await(release); return result.report();
            }));
            try {
                await(saved);
                var sequenceBefore = reportSequenceValue(observer);
                var second = executor.submit(() -> writer.save(UUID.randomUUID(), request.proposalId(), 0).report());
                awaitBlockedBy(observer, blocker.get());
                assertEquals(sequenceBefore, reportSequenceValue(observer), "The blocked writer must not allocate a report number");
                assertNull(observedReports.latest(request.proposalId(), 0));
                assertFalse(second.isDone(), "A contending writer cannot finish before the first transaction is released");
                release.countDown();
                var one = first.get(30, TimeUnit.SECONDS); var two = second.get(30, TimeUnit.SECONDS);
                assertTrue(one.reportNumber() < two.reportNumber()); assertEquals(two.reportId(), reports.latest(request.proposalId(), 0).id());
                assertEquals(2, dsl.fetchCount(CATALOG_FACT_PATH_REPORTS, CATALOG_FACT_PATH_REPORTS.PROPOSAL_ID.eq(request.proposalId())));
            } finally { release.countDown(); } // Release before ExecutorService.close() also on failed setup/observation.
        }
    }

    @Test void auditFailureAndCallerRollbackLeaveNoReportOrEvent() throws Exception {
        var request = request(); var revision = proposals.save(request, null).proposal(); var id = UUID.randomUUID();
        try (var failure = new AuditFailure(id)) { assertSqlState("P0001", assertThrows(RuntimeException.class, () -> writer.save(id, request.proposalId(), 0))); }
        assertFalse(dsl.fetchExists(CATALOG_FACT_PATH_REPORTS, CATALOG_FACT_PATH_REPORTS.ID.eq(id)));
        assertNull(reports.find(id));
        assertThrows(DeliberateRollback.class, () -> new TransactionTemplate(transactions).execute(s -> {
            writer.save(id, request.proposalId(), 0); throw new DeliberateRollback();
        }));
        assertNull(reports.latest(request.proposalId(), 0)); assertEquals(revision, proposalReads.current(request.proposalId()));
    }

    @Test void databaseDeniesMutationDeletionAndWebAccessAndRequiresAnAtomicEvent() throws Exception {
        var request = request(); proposals.save(request, null); var report = writer.save(UUID.randomUUID(), request.proposalId(), 0).report();
        try (var core = core()) {
            for (String table : List.of("core.catalog_fact_path_reports", "audit.catalog_fact_path_report_events")) {
                for (String sql : List.of("UPDATE " + table + " SET id=id WHERE false", "DELETE FROM " + table + " WHERE false", "TRUNCATE " + table + " CASCADE")) denied(core, sql);
            }
            core.setAutoCommit(false); copy(core, report.reportId(), "report");
            assertEquals("23503", assertThrows(SQLException.class, core::commit).getSQLState()); core.rollback();
        }
        try (var web = DriverManager.getConnection(postgres.getJdbcUrl(), "authweave_web_runtime", "web-test-password")) {
            for (String table : List.of("core.catalog_fact_path_reports", "audit.catalog_fact_path_report_events")) {
                denied(web, "SELECT * FROM " + table + " WHERE false"); denied(web, "INSERT INTO " + table + " DEFAULT VALUES");
            }
        }
        assertEquals(report.reportId(), reports.latest(request.proposalId(), 0).id());
    }

    @ParameterizedTest @ValueSource(strings = {"missing-approval", "approval", "writes", "readiness", "coverage", "unbound", "version", "digest", "missing-rules", "nested-approval",
            "wrong-scope", "wrong-basis", "missing-definitions", "missing-definition", "wrong-definitions-type", "wrong-cases-type", "wrong-gaps-type", "false-analysis", "wrong-status", "blocked-with-cases", "nested-writes", "nested-state"})
    void databaseRejectsFalseReportBoundaryClaims(String variant) throws Exception {
        var request = request(); proposals.save(request, null); var report = writer.save(UUID.randomUUID(), request.proposalId(), 0).report();
        String expression = switch (variant) {
            case "missing-approval" -> "report - 'approvalGranted'";
            case "approval" -> "jsonb_set(report, '{approvalGranted}', 'true'::jsonb)";
            case "writes" -> "jsonb_set(report, '{writesPerformed}', 'true'::jsonb)";
            case "readiness" -> "jsonb_set(report, '{recommendationReady}', 'true'::jsonb)";
            case "coverage" -> "jsonb_set(report, '{coverageComplete}', 'true'::jsonb)";
            case "unbound" -> "jsonb_set(report, '{storedRequestDigestVerified}', 'false'::jsonb)";
            case "version" -> "jsonb_set(report, '{storedProposalVersion}', '1'::jsonb)";
            case "digest" -> "jsonb_set(report, '{proposalSha256}', '\"wrong\"'::jsonb)";
            case "missing-rules" -> "report - 'ruleVersion'";
            case "wrong-scope" -> "jsonb_set(report, '{scope}', '\"CATALOG_PROFILE_SCENARIO_IMPACT\"'::jsonb)";
            case "wrong-basis" -> "jsonb_set(report, '{analysisBasis}', '\"VERIFIED_FACTS\"'::jsonb)";
            case "missing-definitions" -> "report - 'caseDefinitions'";
            case "missing-definition" -> "jsonb_set(report, '{caseDefinitions}', (report->'caseDefinitions') - 0)";
            case "wrong-definitions-type" -> "jsonb_set(report, '{caseDefinitions}', '{}'::jsonb)";
            case "wrong-cases-type" -> "jsonb_set(report, '{cases}', '{}'::jsonb)";
            case "wrong-gaps-type" -> "jsonb_set(report, '{uncoveredChanges}', 'null'::jsonb)";
            case "false-analysis" -> "jsonb_set(report, '{impactAnalysisPerformed}', 'false'::jsonb)";
            case "wrong-status" -> "jsonb_set(report, '{status}', '\"APPROVED\"'::jsonb)";
            case "blocked-with-cases" -> "jsonb_set(jsonb_set(jsonb_set(report, '{status}', '\"BLOCKED\"'::jsonb), '{impactAnalysisPerformed}', 'false'::jsonb), '{hypotheticalEvaluationPerformed}', 'false'::jsonb)";
            case "nested-writes" -> "jsonb_set(report, '{changePreview,writesPerformed}', 'true'::jsonb)";
            case "nested-state" -> "jsonb_set(report, '{changePreview,proposalState}', '\"APPROVED\"'::jsonb)";
            default -> "jsonb_set(report, '{changePreview,approvalGranted}', 'true'::jsonb)";
        };
        try (var core = core()) { core.setAutoCommit(false); assertEquals("23514", assertThrows(SQLException.class, () -> copy(core, report.reportId(), expression)).getSQLState()); core.rollback(); }
    }

    @Test void databaseBindsTheReportToTheRevisionDigestAndItsEventToTheReportDigest() throws Exception {
        var request = request(); proposals.save(request, null); var report = writer.save(UUID.randomUUID(), request.proposalId(), 0).report();
        try (var core = core()) {
            core.setAutoCommit(false);
            try (var sql = core.prepareStatement("INSERT INTO core.catalog_fact_path_reports (id,proposal_id,proposal_version,proposal_sha256,report_schema_version,canonicalization_version,report_sha256,report) "
                    + "SELECT ?,proposal_id,proposal_version,repeat('0',64),report_schema_version,canonicalization_version,report_sha256,"
                    + "jsonb_set(jsonb_set(report,'{proposalSha256}',to_jsonb(repeat('0',64))),'{changePreview,proposalSha256}',to_jsonb(repeat('0',64))) "
                    + "FROM core.catalog_fact_path_reports WHERE id=?")) {
                sql.setObject(1, UUID.randomUUID()); sql.setObject(2, report.reportId());
                assertEquals("23503", assertThrows(SQLException.class, sql::executeUpdate).getSQLState());
            } finally { core.rollback(); }
            var copied = copy(core, report.reportId(), "report");
            try (var sql = core.prepareStatement("INSERT INTO audit.catalog_fact_path_report_events (id,report_id,proposal_id,proposal_version,report_sha256,action,actor_type,actor_id,correlation_id,outcome) "
                    + "SELECT ?,?,proposal_id,proposal_version,repeat('0',64),action,actor_type,actor_id,correlation_id,outcome FROM audit.catalog_fact_path_report_events WHERE report_id=?")) {
                sql.setObject(1, UUID.randomUUID()); sql.setObject(2, copied); sql.setObject(3, report.reportId());
                assertEquals("23503", assertThrows(SQLException.class, sql::executeUpdate).getSQLState());
            } finally { core.rollback(); }
        }
    }

    @Test void coreCannotSupplyDatabaseReceiptTimesOrSequenceNumbers() throws Exception {
        try (var core = core()) {
            denied(core, "INSERT INTO core.catalog_fact_path_reports (id, recorded_at) VALUES (gen_random_uuid(), clock_timestamp())");
            denied(core, "INSERT INTO core.catalog_fact_path_reports (id, report_number) OVERRIDING SYSTEM VALUE VALUES (gen_random_uuid(), 1)");
            denied(core, "INSERT INTO audit.catalog_fact_path_report_events (id, occurred_at) VALUES (gen_random_uuid(), clock_timestamp())");
            denied(core, "INSERT INTO core.catalog_publication_decisions DEFAULT VALUES");
        }
    }

    private UUID copy(Connection core, UUID source, String expression) throws SQLException {
        var id = UUID.randomUUID();
        try (var sql = core.prepareStatement("INSERT INTO core.catalog_fact_path_reports (id,proposal_id,proposal_version,proposal_sha256,report_schema_version,canonicalization_version,report_sha256,report) "
                + "SELECT ?,proposal_id,proposal_version,proposal_sha256,report_schema_version,canonicalization_version,report_sha256," + expression + " FROM core.catalog_fact_path_reports WHERE id=?")) {
            sql.setObject(1, id); sql.setObject(2, source); sql.executeUpdate();
        }
        return id;
    }
    private int race(UUID id, UUID proposal, CyclicBarrier start) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try { return writer.save(id, proposal, 0).changed() ? 1 : 0; }
        catch (CatalogFactPathReportException e) { assertEquals(CatalogFactPathReportException.Reason.ID_CONFLICT, e.reason()); return -1; }
    }
    private CatalogChangePreviewRequest request() throws Exception {
        var input = (ObjectNode) mapper.readTree(Path.of(System.getProperty("basedir", "."), "../../packages/contracts/tests/fixtures/catalog-change-preview-request.valid.json").toFile());
        input.put("proposalId", UUID.randomUUID().toString()); return mapper.treeToValue(input, CatalogChangePreviewRequest.class);
    }
    private static void await(CountDownLatch latch) { try { assertTrue(latch.await(10, TimeUnit.SECONDS)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); } }
    private static long reportSequenceValue(Connection observer) throws SQLException {
        // The isolated suite has no other active writers; pg_sequences exposes this value via the existing USAGE grant.
        try (var sql = observer.prepareStatement("SELECT last_value FROM pg_sequences WHERE schemaname='core' AND sequencename='catalog_fact_path_reports_report_number_seq'")) {
            sql.setQueryTimeout(2);
            try (var row = sql.executeQuery()) { assertTrue(row.next()); var value = row.getObject(1, Long.class); assertNotNull(value); return value; }
        }
    }
    private static void awaitBlockedBy(Connection observer, int blocker) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (var sql = observer.prepareStatement("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND ?=ANY(pg_blocking_pids(pid)))")) {
            sql.setInt(1, blocker); sql.setQueryTimeout(2);
            do {
                try (var row = sql.executeQuery()) { assertTrue(row.next()); if (row.getBoolean(1)) return; }
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
        }
        fail("The second writer never waited on the first writer's PostgreSQL lock");
    }
    private static Connection core() throws SQLException { return DriverManager.getConnection(postgres.getJdbcUrl(), "authweave_core_runtime", CORE_RUNTIME_PASSWORD); }
    private static Connection admin() throws SQLException { return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); }
    private static void denied(Connection c, String sql) throws Exception { c.setAutoCommit(false); try (var statement = c.createStatement()) {
        assertEquals("42501", assertThrows(SQLException.class, () -> statement.execute(sql)).getSQLState()); } finally { c.rollback(); } }
    private static void assertSqlState(String state, Throwable error) { for (var cause = error; cause != null; cause = cause.getCause()) {
        if (cause instanceof SQLException sql && state.equals(sql.getSQLState())) return; } fail("Expected SQL state " + state, error); }
    private static final class DeliberateRollback extends RuntimeException { }
    private static final class AuditFailure implements AutoCloseable {
        private final String name;
        AuditFailure(UUID id) throws SQLException {
            name = "test_regression_" + id.toString().replace("-", "");
            try (var c = admin(); var sql = c.createStatement()) {
                sql.execute("CREATE FUNCTION audit." + name + "() RETURNS trigger LANGUAGE plpgsql AS $body$ BEGIN IF NEW.report_id = '" + id + "'::uuid THEN RAISE EXCEPTION 'Injected regression audit failure'; END IF; RETURN NEW; END; $body$");
                sql.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON audit.catalog_fact_path_report_events FOR EACH ROW EXECUTE FUNCTION audit." + name + "()");
            }
        }
        public void close() throws SQLException { try (var c = admin(); var sql = c.createStatement()) { sql.execute("DROP TRIGGER " + name + " ON audit.catalog_fact_path_report_events"); sql.execute("DROP FUNCTION audit." + name + "()"); } }
    }
}

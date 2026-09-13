package io.authweave.core.catalog.proposal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import io.authweave.core.catalog.draft.CatalogChangePreviewService;
import io.authweave.core.catalog.draft.CatalogDraftValidator;
import static io.authweave.core.catalog.proposal.CatalogProposalException.Reason.*;
import static io.authweave.core.generated.jooq.tables.CatalogProposals.CATALOG_PROPOSALS;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local-catalog-write")
class CatalogProposalPersistenceTests extends PostgresIntegrationTest {
    @Autowired LocalCatalogProposalWriter writer;
    @Autowired CatalogProposalRepository repository;
    @Autowired LocalCatalogProposalCommand command;
    @Autowired ObjectMapper mapper;
    @Autowired DSLContext dsl;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void persistsVersionsAndMinimalEventsWithoutRewritingSnapshotsOrInventingReview() throws Exception {
        var request = request(); var first = writer.save(request, null);
        assertTrue(first.changed()); assertEquals(0, first.proposal().version());
        assertEquals("PROPOSED", first.proposal().state());
        assertEquals(mapper.valueToTree(request), first.proposal().request());
        assertFalse(first.proposal().preview().get("writesPerformed").asBoolean());
        var retry = writer.save(request, null);
        assertFalse(retry.changed()); assertEquals(first.proposal(), retry.proposal());
        assertFalse(writer.save(request, 0L).changed());
        var revised = revised(request, "Clarified fictional source rationale");
        var second = writer.save(revised, 0L);
        assertEquals(1, second.proposal().version()); assertEquals(second.proposal(), repository.current(request.proposalId()));
        assertReason(VERSION_CONFLICT, () -> writer.save(revised, 0L));
        assertReason(VERSION_CONFLICT, () -> writer.save(request, null));
        var revisions = repository.revisions(request.proposalId(), null, 100).items();
        assertEquals(List.of(first.proposal(), second.proposal()), revisions);
        var events = repository.events(request.proposalId(), null, 100).items();
        assertEquals(List.of("catalog-proposal.created", "catalog-proposal.revised"), events.stream().map(CatalogProposalEvent::action).toList());
        assertNull(events.getFirst().previousVersion()); assertEquals(0L, events.getLast().previousVersion());
        assertNotEquals(events.getFirst().correlationId(), events.getLast().correlationId());
        for (int i = 0; i < events.size(); i++) {
            assertEquals(revisions.get(i).proposalSha256(), events.get(i).proposalSha256());
            assertEquals("SERVICE", events.get(i).actorType()); assertEquals("core-api-local-catalog", events.get(i).actorId());
            assertEquals("SUCCEEDED", events.get(i).outcome());
        }
        assertFalse(mapper.writeValueAsString(events).contains(request.rationale()));
        ((ObjectNode) first.proposal().request()).remove("base");
        assertTrue(first.proposal().request().has("base"));
        assertThrows(UnsupportedOperationException.class, revisions::clear);
    }

    @Test
    void readsAndRetriesKeepTheOriginalPreviewEvenWhenTheObservationClockChanges() throws Exception {
        var request = request(); var stored = writer.save(request, null).proposal();
        var clock = Clock.fixed(Instant.parse("2099-01-01T00:00:00Z"), ZoneOffset.UTC);
        var later = new LocalCatalogProposalWriter(dsl, mapper,
                new CatalogChangePreviewService(new CatalogDraftValidator(clock), clock), repository);
        var retry = new TransactionTemplate(transactions).execute(status -> later.save(request, 0L));
        assertFalse(retry.changed()); assertEquals(stored, retry.proposal());
        assertEquals(stored, repository.revisions(request.proposalId(), null, 1).items().getFirst());
        var changed = new TransactionTemplate(transactions).execute(status -> later.save(revised(request, "Later revision"), 0L));
        assertEquals("2099-01-01T00:00:00Z", changed.proposal().preview().get("evaluatedAt").asText());
        assertEquals(stored, repository.revisions(request.proposalId(), null, 1).items().getFirst());
    }

    @Test
    void distinctIdsAreIsolatedAndPaginationUsesAnExclusiveBoundedVersionCursor() throws Exception {
        var a = request(); var b = request(); writer.save(a, null); writer.save(b, null);
        writer.save(revised(a, "Revision one"), 0L); writer.save(revised(a, "Revision two"), 1L);
        var first = repository.revisions(a.proposalId(), null, 1);
        assertEquals(0L, first.nextAfterVersion());
        var rest = repository.revisions(a.proposalId(), first.nextAfterVersion(), 2);
        assertEquals(List.of(1L, 2L), rest.items().stream().map(CatalogProposalSnapshot::version).toList()); assertNull(rest.nextAfterVersion());
        assertEquals(1, repository.revisions(b.proposalId(), null, 100).items().size());
        assertEquals(0L, repository.events(a.proposalId(), null, 1).nextAfterVersion());
        assertEquals(2, repository.events(a.proposalId(), 0L, 100).items().size());
        assertTrue(repository.events(a.proposalId(), 2L, 1).items().isEmpty());
        assertReason(NOT_FOUND, () -> repository.current(UUID.randomUUID()));
        assertReason(NOT_FOUND, () -> repository.revisions(UUID.randomUUID(), null, 1));
        assertReason(NOT_FOUND, () -> repository.events(UUID.randomUUID(), null, 1));
        assertThrows(IllegalArgumentException.class, () -> repository.revisions(a.proposalId(), -1L, 1));
        assertThrows(IllegalArgumentException.class, () -> repository.events(a.proposalId(), 9007199254740992L, 1));
        for (int limit : List.of(0, 101)) assertThrows(IllegalArgumentException.class, () -> repository.events(a.proposalId(), null, limit));
    }

    @ParameterizedTest
    @ValueSource(strings = {"base-mismatch", "invalid-base", "invalid-candidate", "version-reused", "no-changes"})
    void rejectsNonReviewableInputsWithoutHeadsRevisionsOrEvents(String scenario) throws Exception {
        var valid = request(); var input = (ObjectNode) mapper.valueToTree(valid);
        switch (scenario) {
            case "base-mismatch" -> input.put("expectedBaseSha256", "0".repeat(64));
            case "invalid-base" -> ((ObjectNode) input.at("/base/options/0/residency/USER_PROFILES")).put("coverage", "UNKNOWN");
            case "invalid-candidate" -> ((ObjectNode) input.at("/candidate/options/0/residency/USER_PROFILES")).put("coverage", "UNKNOWN");
            case "version-reused" -> ((ObjectNode) input.get("candidate")).put("catalogVersion", input.at("/base/catalogVersion").asText());
            case "no-changes" -> input.set("candidate", input.get("base").deepCopy());
        }
        var invalid = mapper.treeToValue(input, CatalogChangePreviewRequest.class);
        assertReason(NOT_REVIEWABLE, () -> writer.save(invalid, null));
        assertFalse(dsl.fetchExists(CATALOG_PROPOSALS, CATALOG_PROPOSALS.ID.eq(valid.proposalId())));
        assertReason(NOT_FOUND, () -> repository.events(valid.proposalId(), null, 100));
        writer.save(valid, null);
        assertReason(NOT_REVIEWABLE, () -> writer.save(invalid, 0L));
        assertEquals(1, repository.revisions(valid.proposalId(), null, 100).items().size());
        assertEquals(1, repository.events(valid.proposalId(), null, 100).items().size());
    }

    @Test
    void missingOrStaleExpectedVersionCannotSilentlyOverwriteEvenWithAnInvalidDraft() throws Exception {
        var request = request(); assertReason(NOT_FOUND, () -> writer.save(request, 0L));
        writer.save(request, null); assertReason(VERSION_CONFLICT, () -> writer.save(revised(request, "Another"), null));
        writer.save(revised(request, "Another"), 0L);
        var bad = new CatalogChangePreviewRequest(1, request.proposalId(), request.rationale(), "0".repeat(64), request.base(), request.candidate());
        assertReason(VERSION_CONFLICT, () -> writer.save(bad, 0L));
        for (long version : List.of(-1L, 9007199254740992L)) assertThrows(IllegalArgumentException.class, () -> writer.save(request, version));
        assertEquals(2, repository.events(request.proposalId(), null, 100).items().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"identical-create", "different-create", "different-update"})
    void concurrentCommandsDoNotDuplicateHistoryOrLoseAnUpdate(String scenario) throws Exception {
        var first = request(); var second = scenario.equals("identical-create") ? first : revised(first, "Concurrent edit");
        Long expected = scenario.equals("different-update") ? 0L : null;
        if (expected != null) { writer.save(first, null); first = revised(first, "First concurrent edit"); }
        var firstRequest = first; var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(executor.submit(() -> race(firstRequest, expected, start)), executor.submit(() -> race(second, expected, start)));
            int changed = 0, conflicts = 0;
            for (var future : futures) { int result = future.get(30, TimeUnit.SECONDS); if (result == 1) changed++; if (result == -1) conflicts++; }
            assertEquals(1, changed); assertEquals(scenario.equals("identical-create") ? 0 : 1, conflicts);
        }
        int size = expected == null ? 1 : 2;
        assertEquals(size, repository.revisions(first.proposalId(), null, 100).items().size());
        assertEquals(size, repository.events(first.proposalId(), null, 100).items().size());
    }

    @Test
    void realAuditFailureRollsBackCreationAndRevisionAndCallerRollbackLeavesNoSuccess() throws Exception {
        var request = request();
        try (var failure = new AuditFailure(request.proposalId())) { assertInjected(assertThrows(RuntimeException.class, () -> writer.save(request, null))); }
        assertReason(NOT_FOUND, () -> repository.current(request.proposalId()));
        var stored = writer.save(request, null).proposal();
        try (var failure = new AuditFailure(request.proposalId())) { assertInjected(assertThrows(RuntimeException.class, () -> writer.save(revised(request, "Failed edit"), 0L))); }
        assertEquals(stored, repository.current(request.proposalId()));
        assertThrows(DeliberateRollback.class, () -> new TransactionTemplate(transactions).execute(status -> {
            writer.save(revised(request, "Caller rollback"), 0L); throw new DeliberateRollback();
        }));
        assertEquals(stored, repository.current(request.proposalId()));
        assertEquals(1, repository.events(request.proposalId(), null, 100).items().size());
        assertEquals(1, repository.revisions(request.proposalId(), null, 100).items().size());
    }

    @Test
    void databaseDeniesHistoryMutationDeletionAndWebAccess() throws Exception {
        writer.save(request(), null);
        try (var core = DriverManager.getConnection(postgres.getJdbcUrl(), "authweave_core_runtime", CORE_RUNTIME_PASSWORD)) {
            for (String table : List.of("core.catalog_proposal_revisions", "audit.catalog_proposal_events")) {
                denied(core, "UPDATE " + table + " SET version=version WHERE false"); denied(core, "DELETE FROM " + table + " WHERE false");
                denied(core, "TRUNCATE " + table + " CASCADE");
            }
            denied(core, "DELETE FROM core.catalog_proposals WHERE false");
            denied(core, "UPDATE core.catalog_proposals SET id=id WHERE false");
        }
        try (var web = DriverManager.getConnection(postgres.getJdbcUrl(), "authweave_web_runtime", "web-test-password")) {
            for (String table : List.of("core.catalog_proposals", "core.catalog_proposal_revisions", "audit.catalog_proposal_events")) {
                denied(web, "SELECT * FROM " + table + " WHERE false"); denied(web, "INSERT INTO " + table + " DEFAULT VALUES");
            }
        }
    }

    @Test
    void localCommandUsesStrictInputsAndDoesNotEchoParserSecrets(@TempDir Path directory) throws Exception {
        var file = directory.resolve("proposal with spaces.json"); var request = request();
        Files.writeString(file, mapper.writeValueAsString(request));
        var env = Map.of("AUTHWEAVE_CATALOG_PROPOSAL_FILE", file.toString());
        assertTrue(command.store(env).changed()); assertFalse(command.store(env).changed());
        for (String body : List.of("{bad:sensitive-test-value}", mapper.writeValueAsString(request).replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                mapper.writeValueAsString(request).replaceFirst("\\{", "{\"curatorId\":\"sensitive-test-value\","))) {
            Files.writeString(file, body);
            var error = assertThrows(IllegalArgumentException.class, () -> command.store(env));
            assertFalse(error.getMessage().contains("sensitive-test-value"));
        }
        assertEquals(1, repository.events(request.proposalId(), null, 100).items().size());
    }

    @Test
    void localCommandRejectsNonFilesAndOversizedInputBeforeSaving(@TempDir Path directory) throws Exception {
        for (Path path : List.of(directory, directory.resolve("missing.json"))) {
            assertThrows(IllegalArgumentException.class, () -> command.store(Map.of("AUTHWEAVE_CATALOG_PROPOSAL_FILE", path.toString())));
        }
        var huge = directory.resolve("oversized.json");
        try (var file = new java.io.RandomAccessFile(huge.toFile(), "rw")) { file.setLength(LocalCatalogProposalCommand.MAX_FILE_BYTES + 1L); }
        var error = assertThrows(IllegalArgumentException.class, () -> command.store(Map.of("AUTHWEAVE_CATALOG_PROPOSAL_FILE", huge.toString())));
        assertTrue(error.getMessage().contains("32 MiB"));
    }

    @Test
    void databaseRejectsFakeApprovalAndMissingOrFalsePreviewBoundaryFlags() throws Exception {
        var request = request(); writer.save(request, null);
        try (var core = DriverManager.getConnection(postgres.getJdbcUrl(), "authweave_core_runtime", CORE_RUNTIME_PASSWORD)) {
            core.setAutoCommit(false);
            for (String preview : List.of("preview - 'approvalGranted'", "jsonb_set(preview, '{approvalGranted}', 'true'::jsonb)",
                    "jsonb_set(preview, '{status}', '\"BLOCKED\"'::jsonb)")) {
                try (var sql = core.prepareStatement("INSERT INTO core.catalog_proposal_revisions "
                        + "SELECT proposal_id, version+1, state, request_schema_version, proposal_sha256, request, " + preview
                        + ", recorded_at FROM core.catalog_proposal_revisions WHERE proposal_id=? AND version=0")) {
                    sql.setObject(1, request.proposalId());
                    assertEquals("23514", assertThrows(SQLException.class, sql::execute).getSQLState());
                } finally { core.rollback(); }
            }
        }
        assertEquals(1, repository.revisions(request.proposalId(), null, 100).items().size());
    }

    private CatalogChangePreviewRequest request() throws Exception {
        var file = Path.of(System.getProperty("basedir", "."), "../../packages/contracts/tests/fixtures/catalog-change-preview-request.valid.json");
        var input = (ObjectNode) mapper.readTree(file.toFile()); input.put("proposalId", UUID.randomUUID().toString());
        return mapper.treeToValue(input, CatalogChangePreviewRequest.class);
    }
    private CatalogChangePreviewRequest revised(CatalogChangePreviewRequest request, String rationale) {
        return new CatalogChangePreviewRequest(1, request.proposalId(), rationale, request.expectedBaseSha256(), request.base(), request.candidate());
    }
    private int race(CatalogChangePreviewRequest request, Long expected, CyclicBarrier start) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try { return writer.save(request, expected).changed() ? 1 : 0; }
        catch (CatalogProposalException error) { assertEquals(VERSION_CONFLICT, error.reason()); return -1; }
    }
    private void assertReason(CatalogProposalException.Reason reason, org.junit.jupiter.api.function.Executable action) {
        assertEquals(reason, assertThrows(CatalogProposalException.class, action).reason());
    }
    private static void denied(Connection connection, String query) throws Exception {
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) { assertEquals("42501", assertThrows(SQLException.class, () -> statement.execute(query)).getSQLState()); }
        finally { connection.rollback(); }
    }
    private static void assertInjected(Throwable error) {
        for (var cause = error; cause != null; cause = cause.getCause()) if (cause instanceof SQLException sql && "P0001".equals(sql.getSQLState())) return;
        fail("Expected injected audit failure", error);
    }
    private static final class DeliberateRollback extends RuntimeException { }
    private static final class AuditFailure implements AutoCloseable {
        private final String name;
        AuditFailure(UUID id) throws SQLException {
            name = "test_catalog_fail_" + id.toString().replace("-", "");
            try (var connection = admin(); var sql = connection.createStatement()) {
                sql.execute("CREATE FUNCTION audit." + name + "() RETURNS trigger LANGUAGE plpgsql AS $body$ BEGIN IF NEW.proposal_id = '" + id
                        + "'::uuid THEN RAISE EXCEPTION 'Injected audit failure'; END IF; RETURN NEW; END; $body$");
                sql.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON audit.catalog_proposal_events FOR EACH ROW EXECUTE FUNCTION audit." + name + "()");
            }
        }
        public void close() throws SQLException {
            try (var connection = admin(); var sql = connection.createStatement()) {
                sql.execute("DROP TRIGGER " + name + " ON audit.catalog_proposal_events"); sql.execute("DROP FUNCTION audit." + name + "()");
            }
        }
        private static Connection admin() throws SQLException { return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); }
    }
}

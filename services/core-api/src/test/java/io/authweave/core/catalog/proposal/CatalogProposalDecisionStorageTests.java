package io.authweave.core.catalog.proposal;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import io.authweave.core.PostgresIntegrationTest;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;

import static io.authweave.core.generated.audit.tables.CatalogProposalDecisionEvents.CATALOG_PROPOSAL_DECISION_EVENTS;
import static io.authweave.core.generated.jooq.tables.CatalogProposalDecisions.CATALOG_PROPOSAL_DECISIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local-catalog-write")
class CatalogProposalDecisionStorageTests extends PostgresIntegrationTest {
    @Autowired private LocalCatalogProposalWriter writer;
    @Autowired private CatalogProposalRepository repository;
    @Autowired private ObjectMapper mapper;
    @Autowired private DSLContext dsl;

    @Test
    void rejectionRequiresAnAtomicMinimalCuratorEventForTheExactRevision() throws Exception {
        var proposal = proposal();
        var snapshot = writer.save(proposal, null).proposal();
        UUID decisionId = UUID.randomUUID();
        try (var core = core()) {
            core.setAutoCommit(false);
            insertDecision(core, decisionId, snapshot, "INSUFFICIENT_EVIDENCE");
            insertEvent(core, decisionId, snapshot, Instant.now(), "CURATOR");
            core.commit();
        }
        assertEquals(1, decisions(snapshot.proposalId()));
        assertEquals(1, events(snapshot.proposalId()));
        assertEquals("PROPOSED", repository.current(snapshot.proposalId()).state());
        assertEquals(1, repository.events(snapshot.proposalId(), null, 100).items().size());
        var event = dsl.selectFrom(CATALOG_PROPOSAL_DECISION_EVENTS)
                .where(CATALOG_PROPOSAL_DECISION_EVENTS.DECISION_ID.eq(decisionId)).fetchOne();
        assertEquals("catalog-proposal.rejected", event.getAction());
        assertEquals("CURATOR", event.getActorType());
        assertEquals(snapshot.proposalSha256(), event.getProposalSha256());
        assertFalse(event.toString().contains(proposal.rationale()));
        try (var core = core()) {
            core.setAutoCommit(false);
            assertEquals("23505", assertThrows(SQLException.class, () -> insertDecision(core,
                    UUID.randomUUID(), snapshot, "OTHER")).getSQLState());
            core.rollback();
        }
    }

    @Test
    void missingOrFailedAuditInsertCannotLeaveACommittedDecision() throws Exception {
        var snapshot = writer.save(proposal(), null).proposal();
        try (var core = core()) {
            core.setAutoCommit(false);
            insertDecision(core, UUID.randomUUID(), snapshot, "OUT_OF_SCOPE");
            assertEquals("23503", assertThrows(SQLException.class, core::commit).getSQLState());
            core.rollback();
        }
        assertEquals(0, decisions(snapshot.proposalId()));
        try (var failure = new AuditFailure(snapshot.proposalId()); var core = core()) {
            core.setAutoCommit(false);
            UUID id = UUID.randomUUID();
            insertDecision(core, id, snapshot, "OTHER");
            assertEquals("P0001", assertThrows(SQLException.class,
                    () -> insertEvent(core, id, snapshot, Instant.now(), "CURATOR")).getSQLState());
            core.rollback();
        }
        assertEquals(0, decisions(snapshot.proposalId()));
        assertEquals(0, events(snapshot.proposalId()));
    }

    @Test
    void rejectsFakeApprovalWrongRevisionStaleAuthenticationAndServiceActor() throws Exception {
        var snapshot = writer.save(proposal(), null).proposal();
        try (var core = core()) {
            core.setAutoCommit(false);
            assertEquals("23514", assertThrows(SQLException.class, () -> insertDecision(core,
                    UUID.randomUUID(), snapshot, "OTHER", "APPROVED", snapshot.proposalSha256())).getSQLState());
            core.rollback();
            assertEquals("23503", assertThrows(SQLException.class, () -> insertDecision(core,
                    UUID.randomUUID(), snapshot, "OTHER", "REJECTED", "0".repeat(64))).getSQLState());
            core.rollback();
            assertEquals("23514", assertThrows(SQLException.class, () -> insertDecision(core,
                    UUID.randomUUID(), snapshot, "UNREVIEWED", "REJECTED", snapshot.proposalSha256())).getSQLState());
            core.rollback();
            UUID staleId = UUID.randomUUID();
            insertDecision(core, staleId, snapshot, "INACCURATE_FACTS");
            assertEquals("23514", assertThrows(SQLException.class,
                    () -> insertEvent(core, staleId, snapshot, Instant.now().minusSeconds(901), "CURATOR")).getSQLState());
            core.rollback();
            UUID futureId = UUID.randomUUID();
            insertDecision(core, futureId, snapshot, "INACCURATE_FACTS");
            assertEquals("23514", assertThrows(SQLException.class,
                    () -> insertEvent(core, futureId, snapshot, Instant.now().plusSeconds(31), "CURATOR")).getSQLState());
            core.rollback();
            UUID wrongDigestId = UUID.randomUUID();
            insertDecision(core, wrongDigestId, snapshot, "OTHER");
            assertEquals("23503", assertThrows(SQLException.class,
                    () -> insertEvent(core, wrongDigestId, snapshot, Instant.now(), "CURATOR", "0".repeat(64)))
                    .getSQLState());
            core.rollback();
            UUID actorTestId = UUID.randomUUID();
            insertDecision(core, actorTestId, snapshot, "INACCURATE_FACTS");
            assertEquals("23514", assertThrows(SQLException.class,
                    () -> insertEvent(core, actorTestId, snapshot, Instant.now(), "SERVICE")).getSQLState());
            core.rollback();
        }
        assertEquals(0, decisions(snapshot.proposalId()));
        assertEquals(0, events(snapshot.proposalId()));
    }

    @Test
    void runtimeCannotRewriteHistoryOrBackdateAndWebCannotReadOrInsert() throws Exception {
        var snapshot = writer.save(proposal(), null).proposal();
        try (var core = core()) {
            for (String table : new String[] { "core.catalog_proposal_decisions", "audit.catalog_proposal_decision_events" }) {
                denied(core, "UPDATE " + table + " SET id=id WHERE false");
                denied(core, "DELETE FROM " + table + " WHERE false");
                denied(core, "TRUNCATE " + table + " CASCADE");
            }
            denied(core, "INSERT INTO core.catalog_proposal_decisions (id, recorded_at) VALUES "
                    + "(gen_random_uuid(), clock_timestamp())");
            denied(core, "INSERT INTO audit.catalog_proposal_decision_events (id, occurred_at) VALUES "
                    + "(gen_random_uuid(), clock_timestamp())");
        }
        try (var web = DriverManager.getConnection(postgres.getJdbcUrl(), "authweave_web_runtime", "web-test-password")) {
            denied(web, "SELECT * FROM core.catalog_proposal_decisions WHERE false");
            denied(web, "SELECT * FROM audit.catalog_proposal_decision_events WHERE false");
            denied(web, "INSERT INTO core.catalog_proposal_decisions DEFAULT VALUES");
            denied(web, "INSERT INTO audit.catalog_proposal_decision_events DEFAULT VALUES");
        }
        assertEquals(0, decisions(snapshot.proposalId()));
    }

    private CatalogChangePreviewRequest proposal() throws Exception {
        var fixture = Path.of(System.getProperty("basedir", "."),
                "../../packages/contracts/tests/fixtures/catalog-change-preview-request.valid.json");
        var json = (ObjectNode) mapper.readTree(fixture.toFile());
        json.put("proposalId", UUID.randomUUID().toString());
        return mapper.treeToValue(json, CatalogChangePreviewRequest.class);
    }

    private static Connection core() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), "authweave_core_runtime", CORE_RUNTIME_PASSWORD);
    }

    private static void insertDecision(Connection connection, UUID id, CatalogProposalSnapshot proposal,
            String reason) throws SQLException {
        insertDecision(connection, id, proposal, reason, "REJECTED", proposal.proposalSha256());
    }

    private static void insertDecision(Connection connection, UUID id, CatalogProposalSnapshot proposal,
            String reason, String decision, String digest) throws SQLException {
        try (var sql = connection.prepareStatement("INSERT INTO core.catalog_proposal_decisions "
                + "(id, proposal_id, proposal_version, proposal_sha256, decision, reason_code) "
                + "VALUES (?, ?, ?, ?, ?, ?)")) {
            sql.setObject(1, id);
            sql.setObject(2, proposal.proposalId());
            sql.setLong(3, proposal.version());
            sql.setString(4, digest);
            sql.setString(5, decision);
            sql.setString(6, reason);
            sql.executeUpdate();
        }
    }

    private static void insertEvent(Connection connection, UUID decisionId, CatalogProposalSnapshot proposal,
            Instant authenticatedAt, String actorType) throws SQLException {
        insertEvent(connection, decisionId, proposal, authenticatedAt, actorType, proposal.proposalSha256());
    }

    private static void insertEvent(Connection connection, UUID decisionId, CatalogProposalSnapshot proposal,
            Instant authenticatedAt, String actorType, String digest) throws SQLException {
        try (var sql = connection.prepareStatement("INSERT INTO audit.catalog_proposal_decision_events "
                + "(id, decision_id, proposal_id, proposal_version, proposal_sha256, decision, action, actor_type, "
                + "actor_issuer, actor_subject, actor_project_id, actor_org_id, authenticated_at, correlation_id, outcome) "
                + "VALUES (?, ?, ?, ?, ?, 'REJECTED', 'catalog-proposal.rejected', ?, ?, ?, ?, ?, ?, ?, 'SUCCEEDED')")) {
            sql.setObject(1, UUID.randomUUID());
            sql.setObject(2, decisionId);
            sql.setObject(3, proposal.proposalId());
            sql.setLong(4, proposal.version());
            sql.setString(5, digest);
            sql.setString(6, actorType);
            sql.setString(7, "http://localhost:8081");
            sql.setString(8, "synthetic-curator");
            sql.setString(9, "123456789012345678");
            sql.setString(10, "987654321098765432");
            sql.setTimestamp(11, Timestamp.from(authenticatedAt));
            sql.setObject(12, UUID.randomUUID());
            sql.executeUpdate();
        }
    }

    private int decisions(UUID proposalId) {
        return dsl.fetchCount(CATALOG_PROPOSAL_DECISIONS,
                CATALOG_PROPOSAL_DECISIONS.PROPOSAL_ID.eq(proposalId));
    }

    private int events(UUID proposalId) {
        return dsl.fetchCount(CATALOG_PROPOSAL_DECISION_EVENTS,
                CATALOG_PROPOSAL_DECISION_EVENTS.PROPOSAL_ID.eq(proposalId));
    }

    private static void denied(Connection connection, String query) throws Exception {
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            assertEquals("42501", assertThrows(SQLException.class, () -> statement.execute(query)).getSQLState());
        } finally {
            connection.rollback();
        }
    }

    private static final class AuditFailure implements AutoCloseable {
        private final String name;

        AuditFailure(UUID proposalId) throws SQLException {
            name = "test_decision_fail_" + proposalId.toString().replace("-", "");
            try (var connection = admin(); var sql = connection.createStatement()) {
                sql.execute("CREATE FUNCTION audit." + name + "() RETURNS trigger LANGUAGE plpgsql AS $body$ "
                        + "BEGIN IF NEW.proposal_id = '" + proposalId + "'::uuid THEN "
                        + "RAISE EXCEPTION 'Injected audit failure'; END IF; RETURN NEW; END; $body$");
                sql.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON audit.catalog_proposal_decision_events "
                        + "FOR EACH ROW EXECUTE FUNCTION audit." + name + "()");
            }
        }

        public void close() throws SQLException {
            try (var connection = admin(); var sql = connection.createStatement()) {
                sql.execute("DROP TRIGGER " + name + " ON audit.catalog_proposal_decision_events");
                sql.execute("DROP FUNCTION audit." + name + "()");
            }
        }

        private static Connection admin() throws SQLException {
            return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
    }
}

package io.authweave.core.assessment.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.authweave.core.PostgresIntegrationTest;
import io.authweave.core.assessment.application.AssessmentApplicationService;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.domain.profile.ApplicationTopology;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ApplicationType;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType;
import io.authweave.core.assessment.domain.profile.DataResidencyDetails;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.assessment.domain.profile.SecurityRequirements;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AssessmentHistoryIntegrationTests extends PostgresIntegrationTest {

    @Autowired private AssessmentApplicationService service;
    @Autowired private AssessmentRepository assessments;
    @Autowired private AssessmentHistoryRepository history;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private tools.jackson.databind.ObjectMapper mapper;

    @Test
    void storesImmutableSnapshotsAndMinimalEventsButNothingForNoOpsOrConflicts() {
        var created = create();
        var workspace = created.assessment().workspaceId();
        var id = created.assessment().id();
        var changed = profile(ApplicationType.B2B_SAAS);
        var updated = service.updateProfile(workspace, id, 0, changed);
        var noOp = service.updateProfile(workspace, id, 1, changed);
        assertEquals(updated.version(), noOp.version());
        assertEquals(updated.updatedAt(), noOp.updatedAt());
        assertEquals(updated.assessment().status(), noOp.assessment().status());
        assertThrows(AssessmentVersionConflictException.class,
                () -> service.updateProfile(workspace, id, 0, changed));

        var revisions = history.findRevisions(workspace, id, null, 100);
        assertEquals(List.of(0L, 1L), revisions.items().stream().map(AssessmentRevision::version).toList());
        assertEquals(mapper.valueToTree(ApplicationIdentityProfile.unknown()), revisions.items().getFirst().profile());
        assertEquals(mapper.valueToTree(changed), revisions.items().getLast().profile());
        assertEquals(AssessmentRevision.Origin.CREATED, revisions.items().getFirst().origin());
        assertEquals(AssessmentRevision.Origin.UPDATED, revisions.items().getLast().origin());
        assertNull(revisions.nextAfterVersion());

        var events = history.findEvents(workspace, id, null, 100).items();
        assertEquals(2, events.size());
        assertEquals("assessment.created", events.getFirst().action());
        assertNull(events.getFirst().previousVersion());
        assertEquals("assessment.updated", events.getLast().action());
        assertEquals(0L, events.getLast().previousVersion());
        assertEquals(List.of("application"), events.getLast().changedSections());
        assertNotEquals(events.getFirst().correlationId(), events.getLast().correlationId());
        for (var event : events) {
            assertEquals("SERVICE", event.actorType());
            assertEquals("core-api", event.actorId());
            assertEquals("SUCCEEDED", event.outcome());
            assertNotNull(event.id());
            assertNotNull(event.occurredAt());
        }
    }

    @Test
    void pagesBothHistoriesAndScopesIdenticalAssessmentIdsToTheirWorkspace() {
        var created = create();
        var workspace = created.assessment().workspaceId();
        var id = created.assessment().id();
        service.updateProfile(workspace, id, 0, profile(ApplicationType.B2B_SAAS));
        var otherWorkspace = new WorkspaceId(UUID.randomUUID());
        service.provisionWorkspace(otherWorkspace);
        service.createAssessment(otherWorkspace, id);

        var first = service.getRevisions(workspace, id, null, 1);
        assertEquals(1, first.items().size());
        assertEquals(0L, first.nextAfterVersion());
        var second = service.getRevisions(workspace, id, first.nextAfterVersion(), 1);
        assertEquals(1L, second.items().getFirst().version());
        assertNull(second.nextAfterVersion());
        assertTrue(service.getRevisions(workspace, id, 1L, 1).items().isEmpty());
        assertEquals(0L, service.getEvents(workspace, id, null, 1).nextAfterVersion());
        assertEquals(1L, service.getEvents(workspace, id, 0L, 1).items().getFirst().version());
        assertEquals(1, service.getRevisions(otherWorkspace, id, null, 100).items().size());
        assertEquals(1, service.getEvents(otherWorkspace, id, null, 100).items().size());
        assertTrue(service.getEvents(otherWorkspace, id, 0L, 100).items().isEmpty());
        var missingWorkspace = new WorkspaceId(UUID.randomUUID());
        assertThrows(AssessmentNotFoundException.class,
                () -> service.getRevisions(missingWorkspace, id, null, 50));
        assertThrows(AssessmentNotFoundException.class,
                () -> service.getEvents(missingWorkspace, id, null, 50));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentWritersCommitExactlyOneNewRevisionAndEvent(boolean mixedFormats) throws Exception {
        var created = create();
        var workspace = created.assessment().workspaceId();
        var id = created.assessment().id();
        // Both writers hold version 0 before either is allowed to attempt its CAS.
        var first = assessments.findById(workspace, id).orElseThrow();
        var second = assessments.findById(workspace, id).orElseThrow();
        first.assessment().updateProfile(mixedFormats ? residencyProfile() : profile(ApplicationType.B2B_SAAS));
        second.assessment().updateProfile(profile(ApplicationType.PARTNER_PORTAL));
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = List.of(
                    executor.submit(() -> race(first, start)),
                    executor.submit(() -> race(second, start)));
            int successes = 0;
            for (var result : results) {
                if (result.get(30, TimeUnit.SECONDS)) successes++;
            }
            assertEquals(1, successes);
        }
        var current = service.getAssessment(workspace, id);
        assertEquals(1, current.version());
        var revisions = service.getRevisions(workspace, id, null, 100).items();
        assertEquals(2, revisions.size());
        assertEquals(mapper.valueToTree(current.assessment().profile()), revisions.getLast().profile());
        assertEquals(current.assessment().profile().security().dataResidencyDetails().isUnrecorded() ? 1 : 2,
                revisions.getLast().profileSchemaVersion());
        assertEquals(2, service.getEvents(workspace, id, null, 100).items().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void auditInsertFailureRollsBackBothCreationAndUpdate(boolean expandedProfile) throws Exception {
        var created = create();
        var workspace = created.assessment().workspaceId();
        var id = created.assessment().id();
        try (var failure = new AuditInsertFailure(id)) {
            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> service.updateProfile(workspace, id, 0,
                            expandedProfile ? residencyProfile() : profile(ApplicationType.B2B_SAAS)));
            assertInjectedFailure(exception);
        }
        var unchanged = service.getAssessment(workspace, id);
        assertEquals(created.version(), unchanged.version());
        assertEquals(created.updatedAt(), unchanged.updatedAt());
        assertEquals(created.assessment().profile(), unchanged.assessment().profile());
        assertEquals(1, history.findRevisions(workspace, id, null, 100).items().size());
        assertEquals(1, history.findEvents(workspace, id, null, 100).items().size());

        var newId = new AssessmentId(UUID.randomUUID());
        try (var failure = new AuditInsertFailure(newId)) {
            assertInjectedFailure(assertThrows(RuntimeException.class,
                    () -> service.createAssessment(workspace, newId)));
        }
        assertTrue(assessments.findById(workspace, newId).isEmpty());
        assertTrue(history.findRevisions(workspace, newId, null, 100).items().isEmpty());
        assertTrue(history.findEvents(workspace, newId, null, 100).items().isEmpty());
    }

    @Test
    void callerRollbackCannotLeaveASuccessEventOrRevisionBehind() {
        var created = create();
        var workspace = created.assessment().workspaceId();
        var id = created.assessment().id();
        assertThrows(DeliberateRollback.class, () -> new TransactionTemplate(transactions).execute(status -> {
            service.updateProfile(workspace, id, 0, residencyProfile());
            assertEquals(2, history.findEvents(workspace, id, null, 100).items().size());
            throw new DeliberateRollback();
        }));
        assertEquals(0, service.getAssessment(workspace, id).version());
        assertEquals(1, history.findRevisions(workspace, id, null, 100).items().size());
        assertEquals(1, history.findEvents(workspace, id, null, 100).items().size());
    }

    @Test
    void databaseDeniesRuntimeHistoryMutationAndWebAccess() throws Exception {
        create();
        try (Connection core = DriverManager.getConnection(postgres.getJdbcUrl(),
                "authweave_core_runtime", CORE_RUNTIME_PASSWORD)) {
            for (String table : List.of("core.assessment_revisions", "audit.assessment_events")) {
                denied(core, "UPDATE " + table + " SET version = version WHERE false");
                denied(core, "DELETE FROM " + table + " WHERE false");
            }
            denied(core, "TRUNCATE core.assessment_revisions, audit.assessment_events");
        }
        try (Connection web = DriverManager.getConnection(postgres.getJdbcUrl(),
                "authweave_web_runtime", "web-test-password")) {
            for (String table : List.of("core.assessment_revisions", "audit.assessment_events")) {
                denied(web, "SELECT * FROM " + table + " WHERE false");
                denied(web, "INSERT INTO " + table + " DEFAULT VALUES");
            }
        }
    }

    private PersistedAssessment create() {
        var workspace = new WorkspaceId(UUID.randomUUID());
        service.provisionWorkspace(workspace);
        return service.createAssessment(workspace, new AssessmentId(UUID.randomUUID()));
    }

    private boolean race(PersistedAssessment candidate, CyclicBarrier start) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            assessments.update(candidate.assessment(), candidate.version());
            return true;
        } catch (AssessmentVersionConflictException expected) {
            assertEquals(1, expected.actualVersion());
            return false;
        }
    }

    private static ApplicationIdentityProfile profile(ApplicationType type) {
        var unknown = ApplicationIdentityProfile.unknown();
        return new ApplicationIdentityProfile(new ApplicationTopology(type, Set.of(ClientType.BROWSER)),
                unknown.audience(), unknown.protocols(), unknown.provisioning(), unknown.security(), unknown.operations());
    }

    private static ApplicationIdentityProfile residencyProfile() {
        var base = profile(ApplicationType.B2B_SAAS);
        var security = base.security();
        return new ApplicationIdentityProfile(base.application(), base.audience(), base.protocols(),
                base.provisioning(), new SecurityRequirements(security.multiFactorAuthentication(),
                security.browserTokenExposureMinimization(), security.auditability(), RequirementCriticality.REQUIRED,
                security.assurance(), security.complianceTargets(),
                new DataResidencyDetails(Set.of("DE"), Set.of(DataResidencyDetails.DataCategory.BACKUPS))), base.operations());
    }

    private static void denied(Connection connection, String sql) throws SQLException {
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            assertEquals("42501", assertThrows(SQLException.class, () -> statement.execute(sql)).getSQLState());
        } finally {
            // Also undo an unexpected successful mutation before failing the test.
            connection.rollback();
        }
    }

    private static void assertInjectedFailure(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "P0001".equals(sql.getSQLState())) return;
        }
        fail("Expected the injected audit database failure", exception);
    }

    private static final class DeliberateRollback extends RuntimeException { }

    /** A real database failure, scoped to this test's assessment in the disposable container. */
    private static final class AuditInsertFailure implements AutoCloseable {
        private final String name;

        AuditInsertFailure(AssessmentId id) throws SQLException {
            name = "test_fail_" + id.value().toString().replace("-", "");
            try (var connection = admin(); var sql = connection.createStatement()) {
                sql.execute("""
                        CREATE FUNCTION audit.%s() RETURNS trigger LANGUAGE plpgsql AS $body$
                        BEGIN
                          IF NEW.assessment_id = '%s'::uuid THEN
                            RAISE EXCEPTION 'Injected audit failure';
                          END IF;
                          RETURN NEW;
                        END;
                        $body$
                        """.formatted(name, id.value()));
                sql.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON audit.assessment_events "
                        + "FOR EACH ROW EXECUTE FUNCTION audit." + name + "()");
            }
        }

        @Override
        public void close() throws SQLException {
            try (var connection = admin(); var sql = connection.createStatement()) {
                sql.execute("DROP TRIGGER " + name + " ON audit.assessment_events");
                sql.execute("DROP FUNCTION audit." + name + "()");
            }
        }

        private static Connection admin() throws SQLException {
            return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
    }
}

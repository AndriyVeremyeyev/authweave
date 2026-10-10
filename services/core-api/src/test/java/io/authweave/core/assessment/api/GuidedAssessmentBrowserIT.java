package io.authweave.core.assessment.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import io.authweave.core.assessment.application.*;
import io.authweave.core.assessment.domain.*;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.persistence.AssessmentDecisionResultService;
import io.authweave.core.assessment.result.AssessmentDecisionResultRequest;
import io.authweave.core.catalog.impact.*;
import io.authweave.core.catalog.auditability.*;
import io.authweave.core.catalog.draft.CatalogAuditabilityDraftValidator;
import io.authweave.core.catalog.publication.*;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import io.authweave.core.PostgresIntegrationTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Browser/production BFF/real Core integration with a signed-token OIDC protocol double. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "AUTHWEAVE_CORE_SERVICE_TOKEN=synthetic-browser-core-token-0000000000000000000000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("catalog-bootstrap-publication")
class GuidedAssessmentBrowserIT extends PostgresIntegrationTest {

    @Value("${local.server.port}") private int port;
    @Autowired PersonalWorkspaceService workspaces;
    @Autowired AssessmentApplicationService assessments;
    @Autowired AssessmentDecisionResultService results;
    @Autowired CatalogBootstrapPublisher bootstrap;
    @Autowired CatalogBootstrapReviewService reviews;
    @Autowired CatalogAuditabilityReviewService auditReviews;
    @Autowired CatalogAuditabilityDraftValidator auditDrafts;
    @Autowired DecisionPublicationCoveragePolicy policy;
    @Autowired ObjectMapper mapper;

    @Test
    void desktopAndMobileGuidedFlowsUseOidcLoginAndRealCoreWithoutOwnerData() throws Exception {
        var web = Path.of("..", "..", "apps", "web").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(web.resolve(".next/standalone/server.js")), "Run make check-web first");
        assertTrue(List.of("localhost", "127.0.0.1").contains(postgres.getHost()), "Browser DB must be loopback-only");
        try (var admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var sql = admin.createStatement()) {
            for (int pass = 0; pass < 2; pass++) {
                for (var migration : List.of("001_auth_sessions.sql", "002_session_workspace.sql",
                        "003_session_curator_scope.sql", "004_reauthentication_transactions.sql")) {
                    sql.execute(Files.readString(web.resolve("db/migrations").resolve(migration)));
                }
            }
        }
        var log = Path.of("target", "guided-browser-integration.log").toAbsolutePath();
        var command = new ProcessBuilder("node", "tests/browser-harness.mts").directory(web.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        var env = command.environment();
        env.keySet().removeIf(key -> key.startsWith("AUTHWEAVE_"));
        env.put("AUTHWEAVE_TEST_BROWSER", "synthetic-browser-core-v1");
        env.put("AUTHWEAVE_TEST_CORE_ORIGIN", "http://127.0.0.1:" + port);
        env.put("AUTHWEAVE_POSTGRES_DB", postgres.getDatabaseName());
        env.put("AUTHWEAVE_POSTGRES_PORT", String.valueOf(postgres.getMappedPort(5432)));
        env.put("AUTHWEAVE_CORE_SERVICE_TOKEN", "synthetic-browser-core-token-0000000000000000000000");
        env.put("AUTHWEAVE_TEST_RESULT_FIXTURES", seedHistoricalResults());
        var process = command.start();
        try {
            assertTrue(process.waitFor(300, TimeUnit.SECONDS), "Browser integration timed out; see " + log);
            var output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("Browser/OIDC E2E: 12 passed"), output);
            System.out.println(output);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(child -> child.destroyForcibly());
                process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        try (var admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var sql = admin.createStatement()) {
            try (var rows = sql.executeQuery("""
                    SELECT w.subject, a.lock_version,
                      (SELECT count(*) FROM core.assessment_revisions r
                       WHERE r.workspace_id = a.workspace_id AND r.assessment_id = a.id) AS revisions,
                      (SELECT count(*) FROM audit.assessment_events e
                       WHERE e.workspace_id = a.workspace_id AND e.assessment_id = a.id) AS events
                    FROM core.assessments a JOIN core.personal_workspaces w ON w.workspace_id = a.workspace_id
                    WHERE w.subject LIKE 'synthetic-browser-%'
                    """)) {
                int guided = 0, security = 0, history = 0, recording = 0;
                while (rows.next()) {
                    boolean failurePath = rows.getString("subject").endsWith("-security-owner");
                    boolean historical = rows.getString("subject").endsWith("-history");
                    boolean writing = rows.getString("subject").endsWith("-recording");
                    assertEquals(historical ? 2 : failurePath || writing ? 1 : 5, rows.getLong("lock_version"));
                    assertEquals(historical ? 3 : failurePath || writing ? 2 : 6, rows.getInt("revisions"));
                    assertEquals(historical ? 3 : failurePath || writing ? 2 : 6, rows.getInt("events"));
                    if (historical) history++; else if (failurePath) security++; else if (writing) recording++; else guided++;
                }
                assertEquals(6, guided); assertEquals(2, security); assertEquals(2, history); assertEquals(2, recording);
            }
            for (var table : List.of("core.assessment_decision_results", "audit.assessment_decision_result_events")) {
                try (var rows = sql.executeQuery("SELECT count(*) FROM " + table)) { assertTrue(rows.next()); assertEquals(10, rows.getInt(1)); }
            }
            try (var rows = sql.executeQuery("SELECT count(*) FROM web.sessions")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
            try (var rows = sql.executeQuery("SELECT count(*) FROM core.personal_workspaces WHERE subject LIKE '%-invalid' OR subject LIKE '%-different'")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
        }
    }
    private String seedHistoricalResults() {
        // Explicit opt-in, fictional reviews/publication only in this fresh Testcontainers database.
        var source = new CandidateDecisionReviewFixture(mapper, reviews, auditReviews, auditDrafts).stored().reference();
        var publication = bootstrap.publish(new CatalogBootstrapPublicationRequest(1, UUID.randomUUID(), source,
                CatalogBootstrapPublicationRequest.Confirmation.PUBLISH_REVIEWED_BOOTSTRAP),
                new CuratorActor("https://identity.example.invalid", "fictional-browser-publisher", "123", "456", Instant.now())).receipt();
        var scenario = policy.scenarios().stream().filter(s -> s.id().equals("b2b-saas-scoped")).findFirst().orElseThrow();
        var fixtures = new java.util.ArrayList<Object>();
        for (var device : List.of("desktop", "mobile")) {
            var actor = new AssessmentDecisionResultService.Actor("http://localhost:8081", "synthetic-browser-" + device + "-history");
            var workspace = workspaces.provision(actor.issuer(), actor.subject()); var assessment = UUID.randomUUID();
            assessments.createAssessment(new WorkspaceId(workspace), new AssessmentId(assessment));
            assessments.updateProfileV6(new WorkspaceId(workspace), new AssessmentId(assessment), 0,
                    mapper.treeToValue(scenario.profile(), ApplicationIdentityProfile.class));
            var first = results.save(workspace, assessment, new AssessmentDecisionResultRequest(1, UUID.randomUUID(), 1,
                    publication.snapshot(), null, scenario.weights(), AssessmentDecisionResultRequest.Confirmation.RECORD_DECISION_RESULT), actor).receipt();
            var profile = (ObjectNode) scenario.profile().deepCopy(); ((ObjectNode) profile.get("operations")).put("identityExpertise", "ADVANCED");
            assessments.updateProfileV6(new WorkspaceId(workspace), new AssessmentId(assessment), 1, mapper.treeToValue(profile, ApplicationIdentityProfile.class));
            var second = results.save(workspace, assessment, new AssessmentDecisionResultRequest(1, UUID.randomUUID(), 2,
                    publication.snapshot(), first.reference(), scenario.weights(), AssessmentDecisionResultRequest.Confirmation.REEVALUATE_DECISION_RESULT), actor).receipt();
            var writeActor = new AssessmentDecisionResultService.Actor("http://localhost:8081", "synthetic-browser-" + device + "-recording");
            var writeWorkspace = workspaces.provision(writeActor.issuer(), writeActor.subject()); var writeAssessment = UUID.randomUUID();
            assessments.createAssessment(new WorkspaceId(writeWorkspace), new AssessmentId(writeAssessment));
            var writeProfile = (ObjectNode) scenario.profile().deepCopy(); ((ObjectNode) writeProfile.at("/protocols/federation")).put("SAML", "PREFERRED");
            assessments.updateProfileV6(new WorkspaceId(writeWorkspace), new AssessmentId(writeAssessment), 0,
                    mapper.treeToValue(writeProfile, ApplicationIdentityProfile.class));
            fixtures.add(Map.of("device", device, "assessmentId", assessment, "first", first.reference(), "second", second.reference(),
                    "writeAssessmentId", writeAssessment, "catalog", publication.snapshot()));
        }
        return mapper.writeValueAsString(fixtures);
    }
}

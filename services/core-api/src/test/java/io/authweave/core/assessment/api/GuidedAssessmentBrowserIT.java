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
import org.jooq.DSLContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.authweave.core.assessment.application.*;
import io.authweave.core.assessment.domain.*;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.persistence.AssessmentDecisionResultService;
import io.authweave.core.assessment.result.AssessmentDecisionResultRequest;
import io.authweave.core.catalog.impact.*;
import io.authweave.core.catalog.auditability.*;
import io.authweave.core.catalog.draft.CatalogAuditabilityDraftValidator;
import io.authweave.core.catalog.publication.*;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.proposal.*;
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
@ActiveProfiles({"catalog-bootstrap-publication", "catalog-proposal-publication"})
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
    @Autowired CatalogProposalPublisher publisher;
    @Autowired TrustedPublishedCatalogService trusted;
    @Autowired CatalogChangePreviewService previews;
    @Autowired CatalogProposalRepository proposals;
    @Autowired CatalogFactReviewWriter facts;
    @Autowired StoredProposalDecisionService proposalInputs;
    @Autowired DSLContext dsl;
    @Autowired PlatformTransactionManager transactions;

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
            // Six-minute fixed suite budget plus one minute for owned server startup/cleanup.
            assertTrue(process.waitFor(420, TimeUnit.SECONDS), "Browser integration timed out; see " + log);
            var output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("Browser/OIDC E2E: 18 passed"), output);
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
                int guided = 0, security = 0, history = 0, recording = 0, matrix = 0;
                while (rows.next()) {
                    boolean failurePath = rows.getString("subject").endsWith("-security-owner");
                    boolean historical = rows.getString("subject").endsWith("-history");
                    boolean writing = rows.getString("subject").endsWith("-recording");
                    boolean acceptance = rows.getString("subject").contains("-matrix-");
                    assertEquals(historical ? 2 : failurePath || writing || acceptance ? 1 : 5, rows.getLong("lock_version"));
                    assertEquals(historical ? 3 : failurePath || writing || acceptance ? 2 : 6, rows.getInt("revisions"));
                    assertEquals(historical ? 3 : failurePath || writing || acceptance ? 2 : 6, rows.getInt("events"));
                    if (acceptance) matrix++; else if (historical) history++; else if (failurePath) security++; else if (writing) recording++; else guided++;
                }
                assertEquals(6, guided); assertEquals(2, security); assertEquals(2, history); assertEquals(2, recording); assertEquals(6, matrix);
            }
            for (var table : List.of("core.assessment_decision_results", "audit.assessment_decision_result_events")) {
                try (var rows = sql.executeQuery("SELECT count(*) FROM " + table)) { assertTrue(rows.next()); assertEquals(22, rows.getInt(1)); }
            }
            try (var rows = sql.executeQuery("SELECT count(*) FROM web.sessions")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
            try (var rows = sql.executeQuery("SELECT count(*) FROM core.personal_workspaces WHERE subject LIKE '%-invalid' OR subject LIKE '%-different'")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
        }
        verifyAcceptanceResults();
    }
    private String seedHistoricalResults() {
        // Explicit opt-in, fictional reviews/publication only in this fresh Testcontainers database.
        var source = new CandidateDecisionReviewFixture(mapper, reviews, auditReviews, auditDrafts).stored(
                base -> DecisionBrowserAcceptanceFixture.base(mapper, base),
                audit -> DecisionBrowserAcceptanceFixture.audit(mapper, audit), CatalogFactReviewRequest.Verdict.SOURCE_SUPPORTS_CLAIM).reference();
        var publication = bootstrap.publish(new CatalogBootstrapPublicationRequest(1, UUID.randomUUID(), source,
                CatalogBootstrapPublicationRequest.Confirmation.PUBLISH_REVIEWED_BOOTSTRAP),
                new CuratorActor("https://identity.example.invalid", "fictional-browser-publisher", "123", "456", Instant.now())).receipt();
        var scenario = policy.scenarios().stream().filter(s -> s.id().equals("b2b-saas-scoped")).findFirst().orElseThrow();
        var fixtures = new java.util.ArrayList<Map<String, Object>>();
        for (var device : List.of("desktop", "mobile")) {
            var actor = new AssessmentDecisionResultService.Actor("http://localhost:8081", "synthetic-browser-" + device + "-history");
            var workspace = workspaces.provision(actor.issuer(), actor.subject()); var assessment = UUID.randomUUID();
            assessments.createAssessment(new WorkspaceId(workspace), new AssessmentId(assessment));
            var historyProfile = (ObjectNode) scenario.profile().deepCopy();
            ((ObjectNode) historyProfile.at("/protocols/federation")).put("SAML", "PREFERRED");
            ((ObjectNode) historyProfile.get("security")).put("multiFactorAuthentication", "PREFERRED");
            var historyWeights = mapper.readTree("{\"mode\":\"EXPLICIT\",\"values\":[{\"capability\":\"SAML\",\"weight\":70},{\"capability\":\"MFA\",\"weight\":30}]}");
            assessments.updateProfileV6(new WorkspaceId(workspace), new AssessmentId(assessment), 0,
                    mapper.treeToValue(historyProfile, ApplicationIdentityProfile.class));
            var first = results.save(workspace, assessment, new AssessmentDecisionResultRequest(1, UUID.randomUUID(), 1,
                    publication.snapshot(), null, historyWeights, AssessmentDecisionResultRequest.Confirmation.RECORD_DECISION_RESULT), actor).receipt();
            var profile = (ObjectNode) historyProfile.deepCopy(); ((ObjectNode) profile.get("operations")).put("identityExpertise", "ADVANCED");
            assessments.updateProfileV6(new WorkspaceId(workspace), new AssessmentId(assessment), 1, mapper.treeToValue(profile, ApplicationIdentityProfile.class));
            var second = results.save(workspace, assessment, new AssessmentDecisionResultRequest(1, UUID.randomUUID(), 2,
                    publication.snapshot(), first.reference(), historyWeights, AssessmentDecisionResultRequest.Confirmation.REEVALUATE_DECISION_RESULT), actor).receipt();
            var writeActor = new AssessmentDecisionResultService.Actor("http://localhost:8081", "synthetic-browser-" + device + "-recording");
            var writeWorkspace = workspaces.provision(writeActor.issuer(), writeActor.subject()); var writeAssessment = UUID.randomUUID();
            assessments.createAssessment(new WorkspaceId(writeWorkspace), new AssessmentId(writeAssessment));
            var writeProfile = (ObjectNode) scenario.profile().deepCopy(); ((ObjectNode) writeProfile.at("/protocols/federation")).put("SAML", "PREFERRED");
            assessments.updateProfileV6(new WorkspaceId(writeWorkspace), new AssessmentId(writeAssessment), 0,
                    mapper.treeToValue(writeProfile, ApplicationIdentityProfile.class));
            var matrix = new java.util.ArrayList<Object>();
            for (var entry : DecisionBrowserAcceptanceFixture.scenarios(mapper, policy)) {
                var matrixWorkspace = workspaces.provision("http://localhost:8081", "synthetic-browser-" + device + "-matrix-" + entry.key());
                var matrixAssessment = UUID.randomUUID();
                assessments.createAssessment(new WorkspaceId(matrixWorkspace), new AssessmentId(matrixAssessment));
                assessments.updateProfileV6(new WorkspaceId(matrixWorkspace), new AssessmentId(matrixAssessment), 0,
                        mapper.treeToValue(entry.profile(), ApplicationIdentityProfile.class));
                matrix.add(Map.of("key", entry.key(), "assessmentId", matrixAssessment, "failurePath", entry.failurePath()));
            }
            fixtures.add(Map.of("device", device, "assessmentId", assessment, "first", first.reference(), "second", second.reference(),
                    "writeAssessmentId", writeAssessment, "catalog", publication.snapshot(), "matrix", matrix));
        }
        // Historical receipts are created before this actual catalog mutation; browser reads replay them afterwards.
        // The matrix explicitly selects the older root even though a successor already exists, never implicit latest.
        var successor = publishSuccessor(publication.snapshot());
        return mapper.writeValueAsString(fixtures.stream().map(f -> {
            var fixture = new java.util.HashMap<>(f); fixture.put("successor", successor); return fixture;
        }).toList());
    }

    private PublishedCatalogSnapshot.Reference publishSuccessor(PublishedCatalogSnapshot.Reference root) {
        var base = mapper.treeToValue(trusted.load(root).decisionInputs().catalog(), ProviderCatalogDraft.class);
        var candidate = (ObjectNode) mapper.valueToTree(base); DecisionBrowserAcceptanceFixture.successor(candidate);
        var request = new CatalogChangePreviewRequest(1, UUID.randomUUID(), "Fictional browser acceptance successor",
                CatalogDraftCanonicalizer.sha256(base), base, mapper.treeToValue(candidate, ProviderCatalogDraft.class));
        var proposal = new TransactionTemplate(transactions).execute(tx -> new LocalCatalogProposalWriter(dsl, mapper, previews, proposals).save(request, null).proposal());
        request.candidate().options().forEach(o -> CatalogDraftFacts.entries(o).keySet().stream().sorted().forEach(path -> facts.record(proposal.proposalId(),
                new CatalogFactReviewRequest(UUID.randomUUID(), proposal.version(), proposal.proposalSha256(), o.id(), path,
                        CatalogFactReviewRequest.Verdict.SOURCE_SUPPORTS_CLAIM, CatalogFactReviewRequest.Confirmation.MANUAL_SOURCE_REVIEW), curator())));
        var revision = new StoredProposalDecisionService.Revision(proposal.proposalId(), proposal.version(), proposal.proposalSha256(), DecisionCanonicalizer.sha256(proposal.request()));
        return publisher.publish(new CatalogProposalPublicationRequest(1, UUID.randomUUID(), root, proposalInputs.pin(revision, null),
                CatalogProposalPublicationRequest.Confirmation.PUBLISH_REVIEWED_PROPOSAL), curator()).receipt().snapshot();
    }

    private CuratorActor curator() {
        return new CuratorActor("https://identity.example.invalid", "fictional-browser-publisher", "123", "456", Instant.now());
    }

    private void verifyAcceptanceResults() {
        for (String device : List.of("desktop", "mobile")) for (var entry : DecisionBrowserAcceptanceFixture.scenarios(mapper, policy)) {
            var actor = new AssessmentDecisionResultService.Actor("http://localhost:8081", "synthetic-browser-" + device + "-matrix-" + entry.key());
            var workspace = workspaces.provision(actor.issuer(), actor.subject());
            var rows = dsl.fetch("select id, assessment_id, version, result_sha256 from core.assessment_decision_results where workspace_id=? order by version", workspace);
            assertEquals(2, rows.size());
            for (int index = 0; index < 2; index++) {
                var row = rows.get(index);
                var reference = new AssessmentDecisionResultRequest.Reference(row.get("id", UUID.class), row.get("version", Long.class), row.get("result_sha256", String.class));
                var receipt = results.get(workspace, row.get("assessment_id", UUID.class), reference, actor);
                var decision = receipt.result().get("decision");
                assertEquals(index == 0 ? entry.key().equals("citizen") ? "UNRANKED_SHORTLIST" : "RANKED_SHORTLIST" : "NO_ELIGIBLE_OPTIONS", decision.path("status").asText());
                assertEquals(index == 0 ? entry.key().equals("citizen") ? 1 : 2 : 0, decision.path("shortlist").size());
                assertEquals(index == 0 ? "fictional-browser-matrix-root" : "fictional-browser-matrix-successor", receipt.result().at("/catalog/reference/catalogVersion").asText());
                assertEquals(mapper.treeToValue(entry.profile(), ApplicationIdentityProfile.class),
                        mapper.treeToValue(receipt.result().path("profile"), ApplicationIdentityProfile.class));
                if (index == 1) for (var option : decision.get("candidates")) {
                    assertEquals("EXCLUDED", option.at("/hardChecks/hardVerdict").asText()); assertTrue(option.get("score").isNull());
                    assertTrue(java.util.stream.StreamSupport.stream(option.at("/hardChecks/findings").spliterator(), false)
                            .anyMatch(f -> f.path("profilePath").asText().equals(entry.failurePath()) && f.path("outcome").asText().equals("FAIL")));
                }
            }
        }
    }
}

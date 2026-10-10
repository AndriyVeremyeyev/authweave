package io.authweave.core.assessment.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.assessment.application.*;
import io.authweave.core.assessment.domain.*;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.result.*;
import io.authweave.core.catalog.auditability.*;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.impact.*;
import io.authweave.core.catalog.publication.*;
import io.authweave.core.catalog.proposal.*;
import io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static io.authweave.core.assessment.result.AssessmentDecisionResultException.Reason.*;

/** Disposable database and fictional .invalid sources/principals only. No project data or IdP grants. */
@SpringBootTest(properties = {"AUTHWEAVE_CORE_SERVICE_TOKEN=synthetic-assessment-result-token-000000000000000",
        "AUTHWEAVE_OIDC_PROJECT_ID=123", "AUTHWEAVE_OIDC_ORG_ID=456"})
@ActiveProfiles({"catalog-bootstrap-publication", "catalog-proposal-publication"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssessmentDecisionResultIntegrationTests {
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-bookworm")
            .withDatabaseName("authweave").withUsername("authweave_admin").withPassword("admin-test-password").withInitScript("db/test/init-runtime-roles.sql");
    static { postgres.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl); r.add("spring.datasource.username", () -> "authweave_core_runtime");
        r.add("spring.datasource.password", () -> "core-test-password"); r.add("spring.datasource.hikari.maximum-pool-size", () -> 3);
        r.add("spring.datasource.hikari.minimum-idle", () -> 0); r.add("spring.flyway.url", postgres::getJdbcUrl);
        r.add("spring.flyway.user", postgres::getUsername); r.add("spring.flyway.password", postgres::getPassword);
    }
    @Autowired DSLContext dsl; @Autowired ObjectMapper mapper; @Autowired MockMvc mvc;
    @MockitoSpyBean AssessmentDecisionResultService results; @Autowired AssessmentApplicationService assessments;
    @Autowired PersonalWorkspaceService workspaces; @Autowired PlatformTransactionManager transactions;
    @Autowired CatalogBootstrapPublisher bootstrap; @Autowired CatalogProposalPublisher publisher;
    @Autowired CatalogBootstrapReviewService reviews; @Autowired CatalogAuditabilityReviewService audits;
    @Autowired CatalogAuditabilityDraftValidator auditDrafts; @Autowired TrustedPublishedCatalogService trusted;
    @Autowired CatalogChangePreviewService previews; @Autowired CatalogProposalRepository proposals;
    @Autowired CatalogFactReviewWriter facts; @Autowired StoredProposalDecisionService proposalInputs;
    @Autowired DecisionPublicationCoveragePolicy policy;
    @MockitoSpyBean AssessmentDecisionResultRepository repository;
    @MockitoSpyBean Clock clock;
    private final List<Object> samples = new ArrayList<>();
    private final List<Object> bundles = new ArrayList<>();
    private UUID workspace, assessment;
    private CatalogBootstrapPublisher.Receipt root;
    private JsonNode weights;
    private long profileVersion;
    @BeforeEach void setup() throws Exception {
        sql("TRUNCATE core.workspaces, core.catalog_bootstrap_reviews, core.catalog_auditability_reviews, core.catalog_proposals, core.catalog_publication_decisions CASCADE");
        workspace = workspaces.provision(actor().issuer(), actor().subject()); assessment = UUID.randomUUID();
        assessments.createAssessment(new WorkspaceId(workspace), new AssessmentId(assessment));
        var scenario = policy.scenarios().stream().filter(s -> s.id().equals("b2b-saas-scoped")).findFirst().orElseThrow();
        weights = scenario.weights();
        profileVersion = assessments.updateProfileV6(new WorkspaceId(workspace), new AssessmentId(assessment), 0,
                mapper.treeToValue(scenario.profile(), ApplicationIdentityProfile.class)).version();
        var source = new CandidateDecisionReviewFixture(mapper, reviews, audits, auditDrafts).stored().reference();
        root = bootstrap.publish(new CatalogBootstrapPublicationRequest(1, UUID.randomUUID(), source,
                CatalogBootstrapPublicationRequest.Confirmation.PUBLISH_REVIEWED_BOOTSTRAP), curator()).receipt();
    }
    @AfterAll void export() throws Exception {
        Files.writeString(Path.of("target/assessment-result-http-contract-samples.json"), mapper.writeValueAsString(samples));
        Files.writeString(Path.of("target/assessment-result-proof-samples.json"), mapper.writeValueAsString(bundles));
    }
    @Test void explicitCalculationPinAndReevaluationKeepTheOriginalResultAfterProfileAndCatalogChanges() throws Exception {
        var request = request(root.snapshot(), null);
        sample("create", "assessment-decision-result-request", true, mapper.valueToTree(request));
        var first = submit(request, 201); bundle(first);
        assertEquals(1, first.at("/reference/version").asInt());
        assertFalse(first.at("/result/decisionApproved").asBoolean()); assertTrue(first.at("/historicalReplayVerified").asBoolean());
        assertEquals(6, first.at("/result/profileSchemaVersion").asInt());
        assertEquals(22, first.at("/result/catalog/verificationGaps").size());
        var old = reference(first); assertEquals(first, submit(request, 200));
        var child = successor();
        assertEquals(first, getResult(old, 200)); // Publishing alone does not re-evaluate or mutate any result.
        var changed = (ObjectNode) policy.scenarios().getFirst().profile();
        ((ObjectNode) changed.get("operations")).put("identityExpertise", "ADVANCED");
        profileVersion = assessments.updateProfileV6(new WorkspaceId(workspace), new AssessmentId(assessment), profileVersion,
                mapper.treeToValue(changed, ApplicationIdentityProfile.class)).version();
        assertEquals(first, submit(request, 200)); // Exact retry is historical, even after a profile edit.
        var next = request(child, old); sample("reevaluate", "assessment-decision-result-request", true, mapper.valueToTree(next));
        var second = submit(next, 201); bundle(second);
        assertEquals(2, second.at("/reference/version").asInt());
        assertEquals(mapper.readTree(mapper.writeValueAsString(old)), second.at("/result/request/previousResult"));
        assertEquals(mapper.valueToTree(child), second.at("/result/request/catalog"));
        assertNotEquals(first.at("/result/profileSha256"), second.at("/result/profileSha256"));
        assertEquals(first, getResult(old, 200));
        assertEquals(AssessmentStatus.DRAFT, assessments.getAssessment(new WorkspaceId(workspace), new AssessmentId(assessment)).assessment().status());
        assertEquals(adviceFromReceipt(first), advice(old)); // Changed catalog/profile cannot replace explanation inputs.
        counts(2); assertEquals(List.of("assessment-decision.recorded", "assessment-decision.reevaluated"),
                dsl.fetch("select action from audit.assessment_decision_result_events order by version").getValues(0, String.class));
        doReturn(Instant.now().plusSeconds(91L * 86400)).when(clock).instant();
        assertEquals(first, getResult(old, 200)); assertEquals(second, submit(next, 200)); // Original server clock; no evidence refresh.
        for (String flag : List.of("decisionApproved", "externalSourceVerificationPerformed", "configurationVerified", "complianceVerified")) {
            var forged = (ObjectNode) first.deepCopy(); ((ObjectNode) forged.get("result")).put(flag, true);
            sample("forged-" + flag, "assessment-decision-result", false, forged);
        }
    }
    @Test void boundedSummaryWriteRecordsRetriesAndReevaluatesUsingTheUnchangedAuditedContract() throws Exception {
        var firstRequest = request(root.snapshot(), null); var first = submitSummary(firstRequest, 201);
        assertEquals(1, first.at("/item/reference/version").asInt());
        assertEquals(first, submitSummary(firstRequest, 200)); counts(1);
        assertTrue(first.path("historicalReplayVerified").asBoolean()); assertFalse(first.path("decisionApproved").asBoolean());
        assertFalse(first.has("result")); assertFalse(first.has("profile")); assertFalse(first.has("auditActor"));
        var ref = mapper.treeToValue(first.at("/item/reference"), AssessmentDecisionResultRequest.Reference.class);
        var child = successor(); var next = request(child, ref);
        var second = submitSummary(next, 201); assertEquals(2, second.at("/item/reference/version").asInt());
        assertEquals(first, submitSummary(firstRequest, 200)); assertEquals(second, submitSummary(next, 200));
        assertEquals(first, summary(ref)); counts(2);
    }
    @Test void summaryFailureAfterCommitIsNotProofOfNoWriteAndIdenticalRetryNeverAppendsAgain() throws Exception {
        var request = request(root.snapshot(), null);
        doThrow(new AssessmentDecisionResultException(READ_UNAVAILABLE)).when(results).summary(any(), any(), any(), any());
        mvc.perform(auth(post(url() + "/summary").contentType("application/json").content(mapper.writeValueAsString(request)), "ok"))
                .andExpect(status().isConflict()); counts(1);
        doCallRealMethod().when(results).summary(any(), any(), any(), any());
        var retry = submitSummary(request, 200); assertEquals(1, retry.at("/item/reference/version").asInt()); counts(1);
    }
    @Test void summaryWritePreservesEnterpriseSsoPreferencesAsARealCapabilityDimension() throws Exception {
        var profile = (ObjectNode) policy.scenarios().stream().filter(s -> s.id().equals("b2b-saas-scoped")).findFirst().orElseThrow().profile().deepCopy();
        ((ObjectNode) profile.get("protocols")).put("enterpriseSingleSignOn", "PREFERRED");
        profileVersion = assessments.updateProfileV6(new WorkspaceId(workspace), new AssessmentId(assessment), profileVersion,
                mapper.treeToValue(profile, ApplicationIdentityProfile.class)).version();
        weights = mapper.readTree("{\"mode\":\"EXPLICIT\",\"values\":[{\"capability\":\"ENTERPRISE_SSO\",\"weight\":100}]}");
        var request = request(root.snapshot(), null); var summary = submitSummary(request, 201);
        assertEquals(weights, summary.get("weights")); assertEquals(summary, submitSummary(request, 200)); counts(1);
    }
    @Test void summaryWriteRefusesQueryAuthorityAndStaleHeadsWithoutAppending() throws Exception {
        var request = request(root.snapshot(), null);
        mvc.perform(auth(post(url() + "/summary").param("latest", "true").contentType("application/json").content(mapper.writeValueAsString(request)), "ok"))
                .andExpect(status().isBadRequest()); counts(0);
        var first = submitSummary(request, 201);
        mvc.perform(auth(post(url() + "/summary").contentType("application/json").content(mapper.writeValueAsString(request(root.snapshot(), null))), "ok"))
                .andExpect(status().isConflict()); counts(1);
        assertEquals(first, submitSummary(request, 200));
    }
    @Test void legacyProfileProjectionPinsUnknownsWithoutRewritingTheStoredSnapshot() throws Exception {
        assessment = UUID.randomUUID(); profileVersion = 0;
        assessments.createAssessment(new WorkspaceId(workspace), new AssessmentId(assessment));
        weights = mapper.readTree("{\"mode\":\"NONE\",\"values\":[]}");
        var receipt = submit(request(root.snapshot(), null), 201); bundle(receipt);
        assertEquals(1, receipt.at("/result/profileSchemaVersion").asInt());
        assertFalse(receipt.at("/result/profile/security").has("auditabilityRequirements"));
        assertTrue(receipt.at("/result/evaluationProfile/security").has("auditabilityRequirements"));
        assertEquals("NEEDS_INFORMATION", receipt.at("/result/decision/status").asText());
        assertEquals(0, assessments.getAssessment(new WorkspaceId(workspace), new AssessmentId(assessment)).version());
        assertEquals(1, dsl.fetchOne("select profile_schema_version from core.assessment_revisions where assessment_id=?", assessment).get(0, Integer.class));
    }
    @Test void explicitPreferenceScoresDoNotReplaceRequiredScimOrConfigurationFollowUps() throws Exception {
        sql("TRUNCATE core.catalog_bootstrap_reviews, core.catalog_auditability_reviews, core.catalog_proposals, core.catalog_publication_decisions CASCADE");
        var source = new CandidateDecisionReviewFixture(mapper, reviews, audits, auditDrafts).stored(base -> {
            var fact = (ObjectNode) base.at("/options/0/facts/OIDC").deepCopy(); fact.put("availability", "MANDATORY");
            ((ObjectNode) base.at("/options/0/facts")).set("SAML", fact);
        }, supplement -> { }, Verdict.SOURCE_SUPPORTS_CLAIM).reference();
        root = bootstrap.publish(new CatalogBootstrapPublicationRequest(1, UUID.randomUUID(), source,
                CatalogBootstrapPublicationRequest.Confirmation.PUBLISH_REVIEWED_BOOTSTRAP), curator()).receipt();
        var p = (ObjectNode) mapper.readTree(Path.of("../../packages/contracts/decision-core/catalog-case.b2b-browser-scim.v1.json").toFile()).get("profile");
        ((ObjectNode) p.get("audience")).set("populations", mapper.createArrayNode().add("PARTNERS"));
        ((ObjectNode) p.get("audience")).put("tenancy", "MULTI_TENANT_ORGANIZATIONS");
        ((ObjectNode) p.get("security")).put("auditability", "REQUIRED");
        ((ObjectNode) p.at("/security/auditabilityRequirements")).set("selectedCriteria", mapper.createArrayNode().add("AUTHENTICATION_SUCCESS_EVENTS"));
        profileVersion = assessments.updateProfileV6(new WorkspaceId(workspace), new AssessmentId(assessment), profileVersion,
                mapper.treeToValue(p, ApplicationIdentityProfile.class)).version();
        weights = mapper.readTree("{\"mode\":\"EXPLICIT\",\"values\":[{\"capability\":\"SAML\",\"weight\":100}]}");
        var first = submit(request(root.snapshot(), null), 201); bundle(first);
        assertEquals("REQUIRED", first.at("/result/evaluationProfile/provisioning/scim").asText());
        assertEquals("RANKED_SHORTLIST", first.at("/result/decision/status").asText());
        assertEquals(100, first.at("/result/decision/candidates/0/score/lowerBound").asInt());
        assertFalse(first.at("/result/configurationVerified").asBoolean());
        assertFalse(first.at("/result/decisionApproved").asBoolean()); counts(1);
    }
    @ParameterizedTest @ValueSource(strings = {"no-token", "bad-token", "duplicate-token", "no-subject", "duplicate-subject", "other-owner", "other-issuer"})
    void personalOwnershipRequiredForCreateReadAndRetry(String issue) throws Exception {
        var request = request(root.snapshot(), null); int status = issue.startsWith("other-") ? 403 : 401;
        mvc.perform(auth(post(url()).contentType("application/json").content(mapper.writeValueAsString(request)), issue)).andExpect(status().is(status)); counts(0);
        mvc.perform(auth(post(url() + "/summary").contentType("application/json").content(mapper.writeValueAsString(request)), issue)).andExpect(status().is(status)); counts(0);
        var receipt = results.save(workspace, assessment, request, actor()).receipt();
        mvc.perform(auth(read(receipt.reference()), issue)).andExpect(status().is(status));
        mvc.perform(auth(get(url()), issue)).andExpect(status().is(status));
        mvc.perform(auth(readSummary(receipt.reference()), issue)).andExpect(status().is(status));
        mvc.perform(auth(post(url()).contentType("application/json").content(mapper.writeValueAsString(request)), issue)).andExpect(status().is(status)); counts(1);
        assertEquals(FORBIDDEN, assertThrows(AssessmentDecisionResultException.class,
                () -> results.save(workspace, assessment, request, new AssessmentDecisionResultService.Actor(actor().issuer(), "stranger"))).reason());
    }
    @ParameterizedTest @ValueSource(strings = {"clock", "profile", "policy", "decision", "verdicts", "schema", "missing-previous", "confirmation", "weights-null", "version-string", "version-fraction", "duplicate"})
    void strictRequestNeverAcceptsCallerResultsClockOrSourceAuthority(String issue) throws Exception {
        var tree = (ObjectNode) mapper.valueToTree(request(root.snapshot(), null));
        switch (issue) {
            case "schema" -> tree.put("schemaVersion", 2);
            case "missing-previous" -> tree.remove("previousResult");
            case "confirmation" -> tree.put("confirmation", "REEVALUATE_DECISION_RESULT");
            case "weights-null" -> tree.putNull("weights");
            case "version-string" -> tree.put("expectedAssessmentVersion", "1");
            case "version-fraction" -> tree.put("expectedAssessmentVersion", 1.5);
            case "duplicate" -> { }
            default -> tree.set(issue, mapper.createObjectNode());
        }
        var json = mapper.writeValueAsString(tree);
        if (issue.equals("duplicate")) json = json.substring(0, json.length() - 1) + ",\"schemaVersion\":1}";
        else sample("invalid-" + issue, "assessment-decision-result-request", false, tree);
        mvc.perform(auth(post(url()).contentType("application/json").content(json), "ok")).andExpect(status().isBadRequest()); counts(0);
        mvc.perform(auth(post(url() + "/summary").contentType("application/json").content(json), "ok")).andExpect(status().isBadRequest()); counts(0);
    }
    @ParameterizedTest @ValueSource(strings = {"stale-profile", "wrong-catalog", "missing-catalog", "implicit-weights", "injected-weights", "result-head", "other-assessment", "same-key"})
    void conflictsAndInvalidInputsDoNotAppendResultsOrAudit(String issue) throws Exception {
        var original = request(root.snapshot(), null); var first = results.save(workspace, assessment, original, actor()).receipt();
        var tree = (ObjectNode) mapper.valueToTree(request(root.snapshot(), first.reference()));
        var expected = issue.contains("weights") ? INVALID_INPUT : issue.equals("wrong-catalog") || issue.equals("missing-catalog") ? READ_UNAVAILABLE : CONFLICT;
        switch (issue) {
            case "stale-profile" -> tree.put("expectedAssessmentVersion", profileVersion - 1);
            case "wrong-catalog" -> ((ObjectNode) tree.get("catalog")).put("snapshotSha256", "0".repeat(64));
            case "missing-catalog" -> ((ObjectNode) tree.get("catalog")).put("snapshotId", UUID.randomUUID().toString());
            case "implicit-weights" -> ((ObjectNode) tree.get("weights")).put("mode", "EXPLICIT");
            case "injected-weights" -> ((ObjectNode) tree.get("weights")).put("hiddenDefault", 100);
            case "result-head" -> ((ObjectNode) tree.get("previousResult")).put("resultSha256", "0".repeat(64));
            case "other-assessment" -> {
                var other = UUID.randomUUID(); assessments.createAssessment(new WorkspaceId(workspace), new AssessmentId(other)); assessment = other; tree.put("expectedAssessmentVersion", 0);
            }
            case "same-key" -> tree.put("resultId", original.resultId().toString());
        }
        var request = mapper.treeToValue(tree, AssessmentDecisionResultRequest.class);
        assertEquals(expected, assertThrows(AssessmentDecisionResultException.class, () -> results.save(workspace, assessment, request, actor())).reason()); counts(1);
    }
    @Test void sameKeyRaceIsIdempotentAndDifferentKeysForTheSameHeadHaveOneWinner() throws Exception {
        var request = request(root.snapshot(), null);
        var a = CompletableFuture.supplyAsync(() -> results.save(workspace, assessment, request, actor()));
        var b = CompletableFuture.supplyAsync(() -> results.save(workspace, assessment, request, actor()));
        var first = a.get(30, TimeUnit.SECONDS); var retry = b.get(30, TimeUnit.SECONDS);
        assertNotEquals(first.created(), retry.created()); assertEquals(first.receipt(), retry.receipt()); counts(1);
        var left = request(root.snapshot(), first.receipt().reference()); var right = request(root.snapshot(), first.receipt().reference());
        var x = CompletableFuture.supplyAsync(() -> outcome(left)); var y = CompletableFuture.supplyAsync(() -> outcome(right));
        assertEquals(Set.of("created", "CONFLICT"), Set.of(x.get(30, TimeUnit.SECONDS), y.get(30, TimeUnit.SECONDS))); counts(2);
    }
    @Test void failureAfterBothInsertsRollsBackTheWholeBundle() {
        var request = request(root.snapshot(), null);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("Fictional failure after audit insert"); })
                .when(repository).insert(any(), any(), anyLong(), any(), anyString(), anyString(), anyString(), any());
        assertThrows(IllegalStateException.class, () -> results.save(workspace, assessment, request, actor())); counts(0);
    }
    @ParameterizedTest @ValueSource(strings = {"digest", "decision", "policy", "clock", "profile", "projection", "proof", "weights", "event-actor", "missing-event", "missing-proof"})
    void evenRechecksummedCorruptionCannotReleaseAStoredResult(String issue) throws Exception {
        var receipt = results.save(workspace, assessment, request(root.snapshot(), null), actor()).receipt();
        var body = (ObjectNode) receipt.result();
        switch (issue) {
            case "decision" -> ((ObjectNode) body.get("decision")).putArray("shortlist").add("forged-option");
            case "policy" -> ((ObjectNode) body.at("/policy/componentVersions")).put("hardChecks", "unsupported-kernel");
            case "clock" -> body.put("evaluatedAt", Instant.now().plusSeconds(3600).toString());
            case "profile" -> ((ObjectNode) body.get("profile").get("operations")).put("identityExpertise", "ADVANCED");
            case "projection" -> ((ObjectNode) body.get("evaluationProfile").get("operations")).put("identityExpertise", "ADVANCED");
            case "proof" -> ((ObjectNode) body.get("catalog")).put("proofSha256", "0".repeat(64));
            case "weights" -> ((ObjectNode) body.at("/request/weights")).put("mode", "EXPLICIT");
        }
        var digest = issue.equals("digest") ? "0".repeat(64) : DecisionCanonicalizer.sha256(body);
        try (var c = admin(); var s = c.createStatement()) {
            s.execute("SET session_replication_role = replica"); // Admin-only fictional tamper; runtime lacks this privilege.
            try (var update = c.prepareStatement("UPDATE core.assessment_decision_results SET result=?::jsonb, result_sha256=? WHERE id=?")) {
                update.setString(1, body.toString()); update.setString(2, digest); update.setObject(3, receipt.reference().resultId()); update.executeUpdate();
            }
            try (var update = c.prepareStatement("UPDATE audit.assessment_decision_result_events SET result_sha256=? WHERE result_id=?")) {
                update.setString(1, digest); update.setObject(2, receipt.reference().resultId()); update.executeUpdate();
            }
            if (issue.equals("event-actor")) s.execute("UPDATE audit.assessment_decision_result_events SET subject='stranger'");
            if (issue.equals("missing-event")) s.execute("DELETE FROM audit.assessment_decision_result_events");
            if (issue.equals("missing-proof")) s.execute("DELETE FROM core.catalog_bootstrap_publications");
        }
        var ref = new AssessmentDecisionResultRequest.Reference(receipt.reference().resultId(), 1, digest);
        var reason = issue.equals("policy") ? UNSUPPORTED_POLICY : READ_UNAVAILABLE;
        assertEquals(reason, assertThrows(AssessmentDecisionResultException.class, () -> results.get(workspace, assessment, ref, actor())).reason());
        getResult(ref, 409);
        mvc.perform(auth(readSummary(ref), "ok")).andExpect(status().isConflict());
        mvc.perform(auth(readAdvice(ref), "ok")).andExpect(status().isConflict());
    }
    @Test void runtimePrivilegesBodyFreeAuditAndBoundedReadsAreEnforced() throws Exception {
        var receipt = results.save(workspace, assessment, request(root.snapshot(), null), actor()).receipt(); var id = receipt.reference().resultId();
        var row = repository.find(id, 0); assertNull(row.body()); assertTrue(row.bytes() > 0);
        assertEquals(READ_UNAVAILABLE, assertThrows(AssessmentDecisionResultException.class, () -> results.verify(row, actor())).reason());
        for (String table : List.of("core.assessment_decision_results", "audit.assessment_decision_result_events")) {
            for (String privilege : List.of("UPDATE", "DELETE", "TRUNCATE")) assertEquals(false, dsl.fetchOne("select has_table_privilege(current_user, ?, ?)", table, privilege).get(0, Boolean.class));
            assertEquals(false, dsl.fetchOne("select has_table_privilege('authweave_web_runtime', ?, 'SELECT')", table).get(0, Boolean.class));
            assertEquals(false, dsl.fetchOne("select has_column_privilege(current_user, ?, ?, 'INSERT')", table,
                    table.startsWith("core") ? "recorded_at" : "occurred_at").get(0, Boolean.class));
        }
        var event = dsl.fetchOne("select row_to_json(e)::text from audit.assessment_decision_result_events e").get(0, String.class);
        for (String forbidden : List.of("profile", "weights", "sourceUrl", "Bearer", "configuration")) assertFalse(event.contains(forbidden));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> dsl.execute("delete from core.assessment_decision_results where id=?", id)); counts(1);
    }
    @Test void exactReadPinRequiredAndReadUsesRepeatableReadWithoutWrites() throws Exception {
        var first = results.save(workspace, assessment, request(root.snapshot(), null), actor()).receipt();
        var wrong = new AssessmentDecisionResultRequest.Reference(first.reference().resultId(), 2, first.reference().resultSha256()); getResult(wrong, 409);
        doAnswer(call -> { assertEquals("on", dsl.fetchValue("show transaction_read_only")); assertEquals("repeatable read", dsl.fetchValue("show transaction_isolation")); return call.callRealMethod(); })
                .when(repository).find(workspace, assessment, first.reference().resultId());
        assertEquals(first, results.get(workspace, assessment, first.reference(), actor())); counts(1);
        var summary = results.summary(workspace, assessment, first.reference(), actor());
        assertEquals(first.reference(), summary.item().reference());
        assertEquals(first.result().path("decision").path("status").asText(), summary.status()); counts(1);
        assertEquals(mapper.valueToTree(summary), mapper.valueToTree(results.advice(workspace, assessment, first.reference(), actor())).get("summary")); counts(1);
        results.sensitivity(workspace, assessment, new AssessmentDecisionSensitivityRequest(1, first.reference(), weights), actor()); counts(1);
    }
    @Test void sensitivityChangesOnlyExplicitWeightsOnTheOriginalClockEvenAfterProfileCatalogAndArchiveChanges() throws Exception {
        sql("TRUNCATE core.catalog_bootstrap_reviews, core.catalog_auditability_reviews, core.catalog_proposals, core.catalog_publication_decisions CASCADE");
        var source = new CandidateDecisionReviewFixture(mapper, reviews, audits, auditDrafts).stored(base -> {
            var available = (ObjectNode) base.at("/options/0/facts/OIDC").deepCopy(); available.put("availability", "MANDATORY");
            ((ObjectNode) base.at("/options/0/facts")).set("SAML", available);
            var absent = (ObjectNode) available.deepCopy(); absent.put("availability", "UNAVAILABLE");
            ((ObjectNode) base.at("/options/0/facts")).set("MFA", absent);
        }, supplement -> { }, Verdict.SOURCE_SUPPORTS_CLAIM).reference();
        root = bootstrap.publish(new CatalogBootstrapPublicationRequest(1, UUID.randomUUID(), source,
                CatalogBootstrapPublicationRequest.Confirmation.PUBLISH_REVIEWED_BOOTSTRAP), curator()).receipt();
        var profile = (ObjectNode) mapper.readTree(Path.of("../../packages/contracts/decision-core/catalog-case.b2b-browser-scim.v1.json").toFile()).get("profile");
        ((ObjectNode) profile.get("audience")).set("populations", mapper.createArrayNode().add("PARTNERS"));
        ((ObjectNode) profile.get("audience")).put("tenancy", "MULTI_TENANT_ORGANIZATIONS");
        ((ObjectNode) profile.get("security")).put("multiFactorAuthentication", "PREFERRED");
        profileVersion = assessments.updateProfileV6(new WorkspaceId(workspace), new AssessmentId(assessment), profileVersion,
                mapper.treeToValue(profile, ApplicationIdentityProfile.class)).version();
        weights = mapper.readTree("{\"mode\":\"EXPLICIT\",\"values\":[{\"capability\":\"SAML\",\"weight\":70},{\"capability\":\"MFA\",\"weight\":30}]}");
        var receipt = submit(request(root.snapshot(), null), 201); var ref = reference(receipt);
        var changed = mapper.readTree("{\"mode\":\"EXPLICIT\",\"values\":[{\"capability\":\"MFA\",\"weight\":80},{\"capability\":\"SAML\",\"weight\":20}]}");
        var comparison = sensitivity(ref, changed); bundle(receipt, changed);
        assertEquals(70, comparison.at("/before/candidates/0/score/lowerBound").asInt());
        assertEquals(20, comparison.at("/after/candidates/0/score/lowerBound").asInt());
        assertEquals(comparison.at("/before/candidates/0/hardChecks"), comparison.at("/after/candidates/0/hardChecks"));
        assertEquals("RANKED_SHORTLIST", comparison.at("/after/status").asText());
        for (String privateKey : List.of("\"profile\"", "\"evaluationProfile\"", "\"declaredValue\"", "\"issuer\"", "\"subject\"")) assertFalse(comparison.toString().contains(privateKey));
        successor(); ((ObjectNode) profile.at("/protocols/federation")).put("SAML", "NOT_REQUIRED");
        assessments.updateProfileV6(new WorkspaceId(workspace), new AssessmentId(assessment), profileVersion, mapper.treeToValue(profile, ApplicationIdentityProfile.class));
        sql("UPDATE core.assessments SET status='ARCHIVED'"); doReturn(Instant.now().plusSeconds(91L * 86400)).when(clock).instant();
        assertEquals(comparison, sensitivity(ref, changed)); assertEquals(receipt, getResult(ref, 200)); counts(1);
        var view = results.sensitivity(workspace, assessment, new AssessmentDecisionSensitivityRequest(1, ref, changed), actor());
        ((tools.jackson.databind.node.ArrayNode) view.summary().weights().get("values")).removeAll();
        assertEquals(comparison, wire(view));
    }
    @ParameterizedTest @ValueSource(strings = {"no-token", "bad-token", "duplicate-token", "no-subject", "duplicate-subject", "other-owner", "other-issuer"})
    void sensitivityRequiresSingularServiceAndOwnerAssertions(String issue) throws Exception {
        var ref = reference(submit(request(root.snapshot(), null), 201));
        mvc.perform(auth(compare(ref, weights), issue)).andExpect(status().is(issue.startsWith("other-") ? 403 : 401)); counts(1);
    }
    @ParameterizedTest @ValueSource(strings = {"dimensions", "fraction", "duplicate", "sum", "unknown", "string", "caller-clock", "missing"})
    void sensitivityRejectsInvalidWeightsAndInjectedInputsWithoutWrites(String issue) throws Exception {
        var ref = reference(submit(request(root.snapshot(), null), 201));
        var raw = (ObjectNode) wire(new AssessmentDecisionSensitivityRequest(1, ref, weights));
        switch (issue) {
            case "dimensions" -> raw.set("weights", mapper.readTree("{\"mode\":\"EXPLICIT\",\"values\":[{\"capability\":\"SAML\",\"weight\":100}]}"));
            case "caller-clock" -> raw.put("evaluatedAt", Instant.now().toString());
            case "missing" -> raw.remove("reference");
            default -> {
                var w = (ObjectNode) mapper.readTree("{\"mode\":\"EXPLICIT\",\"values\":[{\"capability\":\"SAML\",\"weight\":100}]}");
                var value = (ObjectNode) w.at("/values/0");
                switch (issue) {
                    case "fraction" -> value.put("weight", 1.5);
                    case "duplicate" -> ((tools.jackson.databind.node.ArrayNode) w.get("values")).add(value.deepCopy());
                    case "sum" -> value.put("weight", 99);
                    case "unknown" -> value.put("capability", "PASSKEYS");
                    case "string" -> value.put("weight", "100");
                }
                raw.set("weights", w);
            }
        }
        mvc.perform(auth(post(url() + "/" + ref.resultId() + "/sensitivity").contentType("application/json").content(raw.toString()), "ok"))
                .andExpect(status().isBadRequest()); counts(1);
    }
    @Test void sensitivityRefusesMismatchedForeignAndTamperedReferencesOrQueryAuthority() throws Exception {
        var ref = reference(submit(request(root.snapshot(), null), 201));
        mvc.perform(auth(compare(ref, weights).param("latest", "true"), "ok")).andExpect(status().isBadRequest());
        var wrong = new AssessmentDecisionResultRequest.Reference(ref.resultId(), 1, "0".repeat(64));
        mvc.perform(auth(compare(wrong, weights), "ok")).andExpect(status().isConflict());
        mvc.perform(auth(post(url() + "/" + UUID.randomUUID() + "/sensitivity").contentType("application/json")
            .content(mapper.writeValueAsString(new AssessmentDecisionSensitivityRequest(1, ref, weights))), "ok")).andExpect(status().isBadRequest());
        var old = assessment; assessment = UUID.randomUUID();
        mvc.perform(auth(compare(ref, weights), "ok")).andExpect(status().isNotFound()); assessment = old;
        sql("SET session_replication_role=replica; UPDATE core.assessment_decision_results SET result=jsonb_set(result,'{decision,shortlist}','[\"forged\"]'::jsonb)");
        mvc.perform(auth(compare(ref, weights), "ok")).andExpect(status().isConflict()); counts(1);
    }
    @Test void adviceProjectsOnlyOriginalVerifiedExplanationsAndDefensivelyCopiesEveryJsonField() throws Exception {
        var receipt = results.save(workspace, assessment, request(root.snapshot(), null), actor()).receipt();
        var view = results.advice(workspace, assessment, receipt.reference(), actor());
        var original = wire(view); assertEquals(adviceFromReceipt(mapper.valueToTree(receipt)), original);
        var json = advice(receipt.reference()); assertEquals(original, json);
        for (String forbidden : List.of("declaredValue", "\"profile\"", "\"evaluationProfile\"", "\"request\"", "\"issuer\"", "\"subject\"", "Bearer")) assertFalse(json.toString().contains(forbidden), forbidden);
        ((tools.jackson.databind.node.ArrayNode) view.candidates()).removeAll();
        ((tools.jackson.databind.node.ArrayNode) view.limitations()).removeAll();
        ((tools.jackson.databind.node.ArrayNode) view.followUps()).removeAll();
        ((ObjectNode) view.architecture()).put("status", "FORGED");
        assertEquals(original, wire(view)); counts(1);
        sql("UPDATE core.assessments SET status='ARCHIVED'");
        assertEquals(original, advice(receipt.reference())); counts(1);
    }
    @Test void adviceNeverReturnsAnOversizedProjection() throws Exception {
        var receipt = results.save(workspace, assessment, request(root.snapshot(), null), actor()).receipt();
        var body = (ObjectNode) receipt.result();
        // A verified input handoff is spied solely to exercise the independent output-size guard.
        ((ObjectNode) body.get("decision")).set("followUps", mapper.valueToTree(List.of("x".repeat(1_048_577))));
        doReturn(new AssessmentDecisionResultService.Receipt(receipt.reference(), receipt.recordedAt(), body, true))
                .when(results).get(workspace, assessment, receipt.reference(), actor());
        mvc.perform(auth(readAdvice(receipt.reference()), "ok")).andExpect(status().isConflict()); counts(1);
    }
    @ParameterizedTest @ValueSource(strings = {"no-token", "bad-token", "duplicate-token", "no-subject", "duplicate-subject", "other-owner", "other-issuer"})
    void adviceRequiresSingularServiceAndOwnerAssertions(String issue) throws Exception {
        var first = results.save(workspace, assessment, request(root.snapshot(), null), actor()).receipt();
        int status = issue.startsWith("other-") ? 403 : 401;
        mvc.perform(auth(readAdvice(first.reference()), issue)).andExpect(status().is(status)); counts(1);
    }
    @Test void adviceRefusesForeignReferencesDuplicateUnknownAndMissingQueries() throws Exception {
        var receipt = results.save(workspace, assessment, request(root.snapshot(), null), actor()).receipt();
        mvc.perform(auth(readAdvice(receipt.reference()).param("version", "2"), "ok")).andExpect(status().isBadRequest());
        mvc.perform(auth(readAdvice(receipt.reference()).param("latest", "true"), "ok")).andExpect(status().isBadRequest());
        mvc.perform(auth(get(url() + "/" + receipt.reference().resultId() + "/advice"), "ok")).andExpect(status().isBadRequest());
        mvc.perform(auth(readAdvice(new AssessmentDecisionResultRequest.Reference(receipt.reference().resultId(), 1, "0".repeat(64))), "ok"))
                .andExpect(status().isConflict());
        var old = assessment; assessment = UUID.randomUUID();
        mvc.perform(auth(readAdvice(receipt.reference()), "ok")).andExpect(status().isNotFound());
        assessment = old; counts(1);
    }
    @Test void historyIsBoundedNewestFirstAndExactCursorKeepsOlderPagesStableAfterAnAppend() throws Exception {
        var empty = history(null); assertEquals(0, empty.path("items").size()); assertTrue(empty.path("nextBefore").isNull());
        AssessmentDecisionResultRequest.Reference previous = null;
        for (int i = 0; i < 23; i++) previous = results.save(workspace, assessment, request(root.snapshot(), previous), actor()).receipt().reference();
        var first = history(null); assertEquals(20, first.path("items").size());
        assertEquals(23, first.at("/items/0/reference/version").asInt()); assertEquals(4, first.at("/items/19/reference/version").asInt());
        var before = mapper.treeToValue(first.get("nextBefore"), AssessmentDecisionResultRequest.Reference.class);
        assertEquals(first.at("/items/19/reference"), first.path("nextBefore"));
        results.save(workspace, assessment, request(root.snapshot(), previous), actor());
        var older = history(before); assertEquals(3, older.path("items").size());
        assertEquals(3, older.at("/items/0/reference/version").asInt()); assertEquals(1, older.at("/items/2/reference/version").asInt());
        assertTrue(older.path("nextBefore").isNull()); assertFalse(older.path("historicalReplayVerified").asBoolean());
        assertEquals(24, history(null).at("/items/0/reference/version").asInt()); counts(24);
        assertEquals(0, history(mapper.treeToValue(older.at("/items/2/reference"), AssessmentDecisionResultRequest.Reference.class)).path("items").size());
    }
    @Test void metadataDoesNotReadBodiesAndArchivedHistoryStillOpensTheOriginalVerifiedSummary() throws Exception {
        var receipt = results.save(workspace, assessment, request(root.snapshot(), null), actor()).receipt();
        var original = summary(receipt.reference());
        assertEquals(receipt.result().path("decision").path("candidates").size(), original.path("candidates").size());
        assertEquals(receipt.result().path("request").path("weights"), original.path("weights"));
        assertEquals(22, original.path("verificationGapCount").asInt());
        sql("UPDATE core.assessments SET status='ARCHIVED'");
        clearInvocations(repository);
        doAnswer(call -> { assertEquals("on", dsl.fetchValue("show transaction_read_only"));
            assertEquals("repeatable read", dsl.fetchValue("show transaction_isolation")); return call.callRealMethod(); })
            .when(repository).index(workspace, assessment, null);
        var page = history(null); verify(repository, never()).find(any(UUID.class), any(UUID.class), any(UUID.class));
        assertEquals(receipt.reference().resultId().toString(), page.at("/items/0/reference/resultId").asText());
        for (String secret : List.of("issuer", "subject", "profileSha256", "weights", "decisionApproved", "sourceUrl")) assertFalse(page.toString().contains(secret));
        assertEquals(original, summary(receipt.reference())); counts(1);
        sql("SET session_replication_role=replica; UPDATE core.assessment_decision_results SET result=jsonb_set(result,'{decision,shortlist}','[\"fictional-tamper\"]'::jsonb)");
        assertEquals(page, history(null)); // Discovery is not a replay assertion, even with a corrupt result body.
        mvc.perform(auth(readSummary(receipt.reference()), "ok")).andExpect(status().isConflict()); counts(1);
        mvc.perform(auth(readAdvice(receipt.reference()), "ok")).andExpect(status().isConflict()); counts(1);
    }
    @Test void foreignAssessmentsAndForeignOrMismatchedCursorsNeverRevealResults() throws Exception {
        var first = results.save(workspace, assessment, request(root.snapshot(), null), actor()).receipt();
        var originalAssessment = assessment;
        assessment = UUID.randomUUID(); assessments.createAssessment(new WorkspaceId(workspace), new AssessmentId(assessment));
        assertEquals(CONFLICT, assertThrows(AssessmentDecisionResultException.class,
                () -> results.history(workspace, assessment, first.reference(), actor())).reason());
        mvc.perform(auth(readSummary(first.reference()), "ok")).andExpect(status().isNotFound());
        assessment = originalAssessment;
        var wrong = new AssessmentDecisionResultRequest.Reference(first.reference().resultId(), 2, first.reference().resultSha256());
        assertEquals(CONFLICT, assertThrows(AssessmentDecisionResultException.class,
                () -> results.history(workspace, assessment, wrong, actor())).reason());
        assertEquals(FORBIDDEN, assertThrows(AssessmentDecisionResultException.class,
                () -> results.history(workspace, assessment, null, new AssessmentDecisionResultService.Actor(actor().issuer(), "other"))).reason());
        assertEquals(NOT_FOUND, assertThrows(AssessmentDecisionResultException.class,
                () -> results.history(workspace, UUID.randomUUID(), null, actor())).reason()); counts(1);
    }
    @ParameterizedTest @ValueSource(strings = {"incomplete", "zero", "unsafe", "fraction", "duplicate", "unknown", "wrong-sha"})
    void historyRejectsMalformedQueryInsteadOfIgnoringIt(String issue) throws Exception {
        var call = get(url());
        switch (issue) {
            case "incomplete" -> call.param("beforeResultId", UUID.randomUUID().toString());
            case "zero" -> call.param("beforeVersion", "0");
            case "unsafe" -> call.param("beforeVersion", "9007199254740992");
            case "fraction" -> call.param("beforeVersion", "1.5");
            case "duplicate" -> call.param("beforeVersion", "1", "2");
            case "unknown" -> call.param("latest", "true");
            case "wrong-sha" -> call.param("beforeResultSha256", "not-a-hash");
        }
        mvc.perform(auth(call, "ok")).andExpect(status().isBadRequest()); counts(0);
    }
    @Test void summaryRequiresExactReferenceAndRejectsDuplicateOrUnknownParameters() throws Exception {
        var first = results.save(workspace, assessment, request(root.snapshot(), null), actor()).receipt();
        mvc.perform(auth(readSummary(first.reference()).param("version", "2"), "ok")).andExpect(status().isBadRequest());
        mvc.perform(auth(readSummary(first.reference()).param("latest", "true"), "ok")).andExpect(status().isBadRequest());
        var wrong = new AssessmentDecisionResultRequest.Reference(first.reference().resultId(), 1, "0".repeat(64));
        mvc.perform(auth(readSummary(wrong), "ok")).andExpect(status().isConflict());
        summary(first.reference()); counts(1);
    }
    @ParameterizedTest @ValueSource(strings = {"profile-edit", "archived", "missing-event", "wrong-owner", "approval", "timestamp"})
    void sqlBoundaryRejectsDirectRuntimeBypassesAndDoesNotCorruptTheOriginalResult(String issue) throws Exception {
        var first = results.save(workspace, assessment, request(root.snapshot(), null), actor()).receipt();
        var request = request(root.snapshot(), first.reference()); var body = (ObjectNode) first.result();
        body.put("version", 2); body.set("request", mapper.valueToTree(request));
        body.put("requestSha256", DecisionCanonicalizer.sha256(mapper.valueToTree(Map.of("workspaceId", workspace, "assessmentId", assessment, "request", request))));
        if (issue.equals("profile-edit")) sql("UPDATE core.assessments SET lock_version=lock_version+1");
        if (issue.equals("archived")) sql("UPDATE core.assessments SET status='ARCHIVED'");
        if (issue.equals("approval")) body.put("decisionApproved", true);
        var denied = assertThrows(RuntimeException.class, () -> new TransactionTemplate(transactions).execute(tx -> {
            var r = io.authweave.core.generated.jooq.tables.AssessmentDecisionResults.ASSESSMENT_DECISION_RESULTS;
            var insert = dsl.insertInto(r).set(r.ID, request.resultId()).set(r.WORKSPACE_ID, workspace).set(r.ASSESSMENT_ID, assessment)
                .set(r.VERSION, 2L).set(r.ASSESSMENT_VERSION, request.expectedAssessmentVersion()).set(r.SNAPSHOT_ID, root.snapshot().snapshotId())
                .set(r.CATALOG_VERSION, root.snapshot().catalogVersion()).set(r.SNAPSHOT_SHA256, root.snapshot().snapshotSha256())
                .set(r.REQUEST_SHA256, body.path("requestSha256").asText()).set(r.RESULT_SHA256, DecisionCanonicalizer.sha256(body))
                .set(r.PREVIOUS_RESULT_ID, first.reference().resultId()).set(r.PREVIOUS_RESULT_VERSION, 1L)
                .set(r.PREVIOUS_RESULT_SHA256, first.reference().resultSha256()).set(r.RESULT, org.jooq.JSONB.jsonb(mapper.writeValueAsString(body)));
            if (issue.equals("timestamp")) insert.set(r.RECORDED_AT, java.time.OffsetDateTime.now().minusDays(91));
            insert.execute();
            if (issue.equals("wrong-owner")) {
                var e = io.authweave.core.generated.audit.tables.AssessmentDecisionResultEvents.ASSESSMENT_DECISION_RESULT_EVENTS;
                dsl.insertInto(e).set(e.ID, UUID.randomUUID()).set(e.RESULT_ID, request.resultId()).set(e.WORKSPACE_ID, workspace).set(e.ASSESSMENT_ID, assessment)
                    .set(e.VERSION, 2L).set(e.REQUEST_SHA256, body.path("requestSha256").asText()).set(e.RESULT_SHA256, DecisionCanonicalizer.sha256(body))
                    .set(e.ISSUER, actor().issuer()).set(e.SUBJECT, "stranger").set(e.ACTION, "assessment-decision.reevaluated")
                    .set(e.CORRELATION_ID, UUID.randomUUID()).set(e.OUTCOME, "SUCCEEDED").execute();
            }
            return null; // Missing mandatory event is rejected at commit, not just in Java.
        }));
        Throwable cause = denied;
        while (!(cause instanceof java.sql.SQLException) && cause.getCause() != null) cause = cause.getCause();
        var expectedState = switch (issue) {
            case "profile-edit", "archived" -> "P0001";
            case "approval" -> "23514";
            case "timestamp" -> "42501";
            default -> "23503";
        };
        assertInstanceOf(java.sql.SQLException.class, cause);
        assertEquals(expectedState, ((java.sql.SQLException) cause).getSQLState());
        counts(1); assertEquals(first, results.get(workspace, assessment, first.reference(), actor()));
    }
    private AssessmentDecisionResultRequest request(PublishedCatalogSnapshot.Reference catalog, AssessmentDecisionResultRequest.Reference previous) {
        return new AssessmentDecisionResultRequest(1, UUID.randomUUID(), profileVersion, catalog, previous, weights,
                previous == null ? AssessmentDecisionResultRequest.Confirmation.RECORD_DECISION_RESULT : AssessmentDecisionResultRequest.Confirmation.REEVALUATE_DECISION_RESULT);
    }
    private String outcome(AssessmentDecisionResultRequest request) {
        try { return results.save(workspace, assessment, request, actor()).created() ? "created" : "retry"; }
        catch (AssessmentDecisionResultException failure) { return failure.reason().name(); }
    }
    private PublishedCatalogSnapshot.Reference successor() {
        var base = mapper.treeToValue(trusted.load(root.snapshot()).decisionInputs().catalog(), ProviderCatalogDraft.class);
        var candidate = (ObjectNode) mapper.valueToTree(base); candidate.put("catalogVersion", "fictional-assessment-successor");
        ((ObjectNode) candidate.at("/options/0/facts/SCIM")).put("availability", "UNAVAILABLE");
        var request = new CatalogChangePreviewRequest(1, UUID.randomUUID(), "Fictional result replay regression", CatalogDraftCanonicalizer.sha256(base), base,
                mapper.treeToValue(candidate, ProviderCatalogDraft.class));
        var p = new TransactionTemplate(transactions).execute(tx -> new LocalCatalogProposalWriter(dsl, mapper, previews, proposals).save(request, null).proposal());
        request.candidate().options().forEach(o -> CatalogDraftFacts.entries(o).keySet().stream().sorted().forEach(path -> facts.record(p.proposalId(),
                new CatalogFactReviewRequest(UUID.randomUUID(), p.version(), p.proposalSha256(), o.id(), path, Verdict.SOURCE_SUPPORTS_CLAIM,
                    CatalogFactReviewRequest.Confirmation.MANUAL_SOURCE_REVIEW), curator())));
        var revision = new StoredProposalDecisionService.Revision(p.proposalId(), p.version(), p.proposalSha256(), DecisionCanonicalizer.sha256(p.request()));
        return publisher.publish(new CatalogProposalPublicationRequest(1, UUID.randomUUID(), root.snapshot(), proposalInputs.pin(revision, null),
                CatalogProposalPublicationRequest.Confirmation.PUBLISH_REVIEWED_PROPOSAL), curator()).receipt().snapshot();
    }
    private JsonNode submit(AssessmentDecisionResultRequest body, int status) throws Exception {
        var response = mvc.perform(auth(post(url()).contentType("application/json").content(mapper.writeValueAsString(body)), "ok"))
                .andExpect(status().is(status)).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        var json = mapper.readTree(response.getContentAsString()); sample("post-" + samples.size(), "assessment-decision-result", true, json); return json;
    }
    private JsonNode submitSummary(AssessmentDecisionResultRequest body, int expectedStatus) throws Exception {
        var response = mvc.perform(auth(post(url() + "/summary").contentType("application/json").content(mapper.writeValueAsString(body)), "ok"))
                .andExpect(status().is(expectedStatus)).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        var json = mapper.readTree(response.getContentAsString()); sample("post-summary-" + samples.size(), "assessment-decision-result-summary", true, json); return json;
    }
    private JsonNode getResult(AssessmentDecisionResultRequest.Reference ref, int status) throws Exception {
        var response = mvc.perform(auth(read(ref), "ok")).andExpect(status().is(status)).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        var json = mapper.readTree(response.getContentAsString()); sample("read-" + samples.size(), status == 200 ? "assessment-decision-result" : "assessment-decision-result-problem", true, json); return json;
    }
    private MockHttpServletRequestBuilder read(AssessmentDecisionResultRequest.Reference ref) {
        return get(url() + "/" + ref.resultId()).param("version", Long.toString(ref.version())).param("resultSha256", ref.resultSha256());
    }
    private MockHttpServletRequestBuilder readSummary(AssessmentDecisionResultRequest.Reference ref) {
        return get(url() + "/" + ref.resultId() + "/summary").param("version", Long.toString(ref.version())).param("resultSha256", ref.resultSha256());
    }
    private MockHttpServletRequestBuilder readAdvice(AssessmentDecisionResultRequest.Reference ref) {
        return get(url() + "/" + ref.resultId() + "/advice").param("version", Long.toString(ref.version())).param("resultSha256", ref.resultSha256());
    }
    private JsonNode advice(AssessmentDecisionResultRequest.Reference ref) throws Exception {
        var response = mvc.perform(auth(readAdvice(ref), "ok")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        var json = mapper.readTree(response.getContentAsString()); sample("advice-" + samples.size(), "assessment-decision-result-advice", true, json); return json;
    }
    private JsonNode adviceFromReceipt(JsonNode receipt) {
        var decision = receipt.at("/result/decision"); var limits = mapper.createArrayNode();
        decision.get("limitations").forEach(l -> { var copy = (ObjectNode) l.deepCopy(); copy.remove("declaredValue"); limits.add(copy); });
        return wire(Map.of("scope", "VERIFIED_ASSESSMENT_DECISION_ADVICE",
            "summary", results.summary(workspace, assessment, reference(receipt), actor()), "candidates", decision.get("candidates"),
            "rankGroups", decision.get("rankGroups"), "architecture", decision.get("architecture"), "limitations", limits, "followUps", decision.get("followUps")));
    }
    private JsonNode wire(Object value) { return mapper.readTree(mapper.writeValueAsString(value)); }
    private JsonNode summary(AssessmentDecisionResultRequest.Reference ref) throws Exception {
        var response = mvc.perform(auth(readSummary(ref), "ok")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        var json = mapper.readTree(response.getContentAsString()); sample("summary-" + samples.size(), "assessment-decision-result-summary", true, json); return json;
    }
    private JsonNode history(AssessmentDecisionResultRequest.Reference before) throws Exception {
        var call = get(url());
        if (before != null) call.param("beforeResultId", before.resultId().toString()).param("beforeVersion", Long.toString(before.version())).param("beforeResultSha256", before.resultSha256());
        var response = mvc.perform(auth(call, "ok")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        var json = mapper.readTree(response.getContentAsString()); sample("history-" + samples.size(), "assessment-decision-result-page", true, json); return json;
    }
    private String url() { return "/api/v6/workspaces/" + workspace + "/assessments/" + assessment + "/decision-results"; }
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String issue) {
        if (!issue.equals("no-token")) b.header("Authorization", issue.equals("bad-token") ? "Bearer bad" : "Bearer synthetic-assessment-result-token-000000000000000");
        if (issue.equals("duplicate-token")) b.header("Authorization", "Bearer synthetic-assessment-result-token-000000000000000");
        b.header("X-AuthWeave-Oidc-Issuer", issue.equals("other-issuer") ? "https://other.example.invalid" : actor().issuer());
        if (!issue.equals("no-subject")) b.header("X-AuthWeave-Oidc-Subject", issue.equals("other-owner") ? "other-owner" : actor().subject());
        if (issue.equals("duplicate-subject")) b.header("X-AuthWeave-Oidc-Subject", actor().subject());
        return b; // No curator role or fresh-reauth requirement for owned decision advice.
    }
    private AssessmentDecisionResultRequest.Reference reference(JsonNode json) { return mapper.treeToValue(json.get("reference"), AssessmentDecisionResultRequest.Reference.class); }
    private void sample(String name, String schema, boolean valid, JsonNode payload) { samples.add(Map.of("name", name, "schema", schema, "valid", valid, "payload", payload)); }
    private MockHttpServletRequestBuilder compare(AssessmentDecisionResultRequest.Reference ref, JsonNode comparisonWeights) {
        return post(url() + "/" + ref.resultId() + "/sensitivity").contentType("application/json")
            .content(mapper.writeValueAsString(new AssessmentDecisionSensitivityRequest(1, ref, comparisonWeights)));
    }
    private JsonNode sensitivity(AssessmentDecisionResultRequest.Reference ref, JsonNode comparisonWeights) throws Exception {
        var input = new AssessmentDecisionSensitivityRequest(1, ref, comparisonWeights);
        sample("sensitivity-request-" + samples.size(), "assessment-decision-sensitivity-request", true, wire(input));
        var response = mvc.perform(auth(compare(ref, comparisonWeights), "ok")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        var json = mapper.readTree(response.getContentAsString()); sample("sensitivity-" + samples.size(), "assessment-decision-sensitivity", true, json); return json;
    }
    private void bundle(JsonNode receipt) throws Exception { bundle(receipt, receipt.at("/result/request/weights")); }
    private void bundle(JsonNode receipt, JsonNode comparisonWeights) throws Exception {
        var ref = mapper.treeToValue(receipt.at("/result/request/catalog"), PublishedCatalogSnapshot.Reference.class);
        var resultRef = reference(receipt);
        bundles.add(Map.of("receipt", receipt, "decisionInputs", trusted.load(ref).decisionInputs(),
                "summary", results.summary(workspace, assessment, resultRef, actor()),
                "advice", results.advice(workspace, assessment, resultRef, actor()),
                "comparisonRequest", new AssessmentDecisionSensitivityRequest(1, resultRef, comparisonWeights),
                "sensitivity", sensitivity(resultRef, comparisonWeights)));
    }
    private void counts(int n) { for (String t : List.of("core.assessment_decision_results", "audit.assessment_decision_result_events")) assertEquals(n, dsl.fetchOne("select count(*) from " + t).get(0, Integer.class)); }
    private java.sql.Connection admin() throws Exception { return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); }
    private void sql(String sql) throws Exception { try (var c = admin(); var s = c.createStatement()) { s.execute(sql); } }
    private static AssessmentDecisionResultService.Actor actor() { return new AssessmentDecisionResultService.Actor("https://identity.example.invalid", "fictional-assessor"); }
    private static CuratorActor curator() { return new CuratorActor("https://identity.example.invalid", "fictional-curator", "123", "456", Instant.now()); }
}

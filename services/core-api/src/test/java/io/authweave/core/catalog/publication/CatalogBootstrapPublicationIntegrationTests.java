package io.authweave.core.catalog.publication;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.impact.*;
import io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Fictional .invalid sources and assertions only, in a disposable DB; never real project reviews/roles/publication. */
@SpringBootTest(properties = {"AUTHWEAVE_CORE_SERVICE_TOKEN=synthetic-publication-service-token-000000000000000",
        "AUTHWEAVE_OIDC_PROJECT_ID=123", "AUTHWEAVE_OIDC_ORG_ID=456"})
@ActiveProfiles("catalog-bootstrap-publication")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogBootstrapPublicationIntegrationTests {
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
    private static final String URL = "/internal/v1/catalog-curator/bootstrap-publications";
    private static final String TOKEN = "Bearer synthetic-publication-service-token-000000000000000";
    private static final Path SAMPLES = Path.of("target/bootstrap-publication-http-contract-samples.json");
    private final List<Sample> samples = new ArrayList<>();
    private final List<EvidenceSample> evidenceSamples = new ArrayList<>();
    @Autowired MockMvc mvc;
    @Autowired DSLContext dsl;
    @Autowired ObjectMapper mapper;
    @Autowired CatalogBootstrapReviewService reviews;
    @Autowired CatalogBootstrapPublisher publisher;
    @Autowired CatalogPublicationLookup lookup;
    @Autowired io.authweave.core.catalog.auditability.CatalogAuditabilityReviewService audits;
    @Autowired CatalogAuditabilityDraftValidator auditDrafts;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean DecisionPublicationCoverageService coverage;
    @BeforeEach void emptyIsolatedRegistry() throws Exception {
        try (var c = admin(); var s = c.createStatement()) {
            s.execute("TRUNCATE core.catalog_bootstrap_reviews, core.catalog_publication_decisions CASCADE");
        }
    }
    @AfterAll void samples() throws Exception {
        Files.writeString(SAMPLES, mapper.writeValueAsString(samples));
        Files.writeString(Path.of("target/bootstrap-publication-proof-samples.json"), mapper.writeValueAsString(evidenceSamples));
    }
    record Sample(String name, String schema, boolean valid, JsonNode payload) { }
    record EvidenceSample(JsonNode proof, String proofSha256, JsonNode snapshot, JsonNode receipt, JsonNode candidate, JsonNode auditabilitySupplement) { }

    @Test void publicationIsAtomicExactPinnedBodyFreeAndActorBoundIdempotentWithoutActivatingEvaluator() throws Exception {
        var request = source("current", Verdict.SOURCE_SUPPORTS_CLAIM);
        sample("publication-request", "catalog-bootstrap-publication-request", true, mapper.valueToTree(request));
        var created = mvc.perform(auth(post(URL).contentType("application/json").content(mapper.writeValueAsString(request)), "ok"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.publicationRecorded").value(true)).andExpect(jsonPath("$.evaluationReady").value(false))
                .andExpect(jsonPath("$.externalSourceVerificationPerformed").value(false)).andReturn();
        var receipt = mapper.readTree(created.getResponse().getContentAsString());
        sample("publication-created", "catalog-bootstrap-publication", true, receipt);
        var retried = mvc.perform(auth(post(URL).contentType("application/json").content(mapper.writeValueAsString(request)), "ok"))
                .andExpect(status().isOk()).andReturn(); assertEquals(receipt, mapper.readTree(retried.getResponse().getContentAsString()));
        var result = publisher.publish(request, actor()); assertFalse(result.created());
        evidence(result.receipt());
        for (String flag : List.of("evaluationReady", "externalSourceVerificationPerformed")) {
            var forged = (ObjectNode) receipt.deepCopy(); forged.put(flag, true);
            sample("publication-forged-" + flag, "catalog-bootstrap-publication", false, forged);
        }
        assertTrue(lookup.lookup(result.receipt().snapshot()).storedIntegrityValidated());
        assertFalse(lookup.lookup(result.receipt().snapshot()).baselineVerified());
        assertEquals(22, result.receipt().verificationGaps().size()); bundle(1);
        for (String secret : List.of("fictional-publisher", "sourceUrl", "summary", "actor", "candidate", "scenarios")) assertFalse(receipt.toString().contains(secret));
        var proof = mapper.readTree(dsl.fetchOne("select proof::text from core.catalog_bootstrap_publications").get(0, String.class));
        assertEquals(proof.get("publishedAt"), proof.at("/coverage/evaluatedAt"));
        assertEquals(4, proof.at("/coverage/scenarios").size()); assertTrue(proof.at("/coverage/storedSourceReviewsVerified").asBoolean());
        assertFalse(proof.at("/coverage/coverageComplete").asBoolean()); assertFalse(proof.at("/coverage/approvalGranted").asBoolean());
        assertTrue(proof.at("/coverage/scenarios/0/afterUnknownFindings").asInt() > 0, "Coverage is not an all-candidates-PASS requirement");
        var snapshot = lookup.lookup(result.receipt().snapshot()).snapshot();
        assertTrue(snapshot.factEvidenceStatuses().stream().allMatch(s -> s.evidenceStatus() == PublishedCatalogSnapshot.DeclaredEvidenceStatus.REVIEWED));
        assertTrue(snapshot.catalog().asDraft().options().getFirst().facts().get(io.authweave.core.catalog.ProviderCatalog.Capability.SCIM)
                .evidence().sourceUrl().getHost().endsWith(".invalid"));
        mvc.perform(auth(post(URL).contentType("application/json").content(mapper.writeValueAsString(request)), "another-subject"))
                .andExpect(status().isConflict());
        var changed = new CatalogBootstrapPublicationRequest(1, request.publicationId(),
                new StoredCandidateDecisionService.Reference(request.source().reviewId(), "0".repeat(64), request.source().decisionCatalogSha256(), null), request.confirmation());
        mvc.perform(auth(post(URL).contentType("application/json").content(mapper.writeValueAsString(changed)), "ok")).andExpect(status().isConflict());
        var anotherKey = new CatalogBootstrapPublicationRequest(1, UUID.randomUUID(), request.source(), request.confirmation());
        assertEquals(CatalogBootstrapPublicationException.Reason.CONFLICT, assertThrows(CatalogBootstrapPublicationException.class,
                () -> publisher.publish(anotherKey, actor())).reason()); bundle(1);
    }

    @ParameterizedTest @ValueSource(strings = {"no-credential", "bad-credential", "duplicate-credential", "no-issuer", "no-subject",
            "duplicate-subject", "role", "duplicate-role", "project", "organization", "stale", "future", "invalid-time"})
    void newAndRetriedPublicationsRequireSingularCurrentScopedCuratorAssertion(String guard) throws Exception {
        var request = source("current", Verdict.SOURCE_SUPPORTS_CLAIM);
        int expected = List.of("no-credential", "bad-credential", "duplicate-credential", "no-issuer", "no-subject", "duplicate-subject").contains(guard) ? 401 : 403;
        mvc.perform(auth(post(URL).contentType("application/json").content(mapper.writeValueAsString(request)), guard)).andExpect(status().is(expected));
        bundle(0); publisher.publish(request, actor());
        mvc.perform(auth(post(URL).contentType("application/json").content(mapper.writeValueAsString(request)), guard)).andExpect(status().is(expected));
        mvc.perform(auth(post(URL.replace("/v1/", "/v2/")).contentType("application/json").content("{}"), guard)).andExpect(status().is(expected)); bundle(1);
    }
    @ParameterizedTest @ValueSource(strings = {"schema", "confirmation", "missing-pin", "missing-audit-pin", "caller-clock", "caller-manifest", "caller-report", "caller-role", "duplicate-json"})
    void callerCannotSupplyManifestClockImpactVerdictsOrAuthority(String change) throws Exception {
        var request = (ObjectNode) mapper.valueToTree(source("current", Verdict.SOURCE_SUPPORTS_CLAIM));
        switch (change) {
            case "schema" -> request.put("schemaVersion", 2);
            case "confirmation" -> request.put("confirmation", "APPROVE");
            case "missing-pin" -> ((ObjectNode) request.get("source")).remove("reviewSha256");
            case "missing-audit-pin" -> ((ObjectNode) request.get("source")).remove("auditability");
            case "caller-clock" -> request.put("evaluatedAt", Instant.now().toString());
            case "caller-manifest" -> request.set("snapshot", mapper.createObjectNode());
            case "caller-report" -> request.set("coverage", mapper.createObjectNode().put("decisionScopeCoverageComplete", true));
            case "caller-role" -> request.put("role", "catalog_curator");
            case "duplicate-json" -> { }
        }
        var body = mapper.writeValueAsString(request);
        if (change.equals("duplicate-json")) body = body.substring(0, body.length() - 1) + ",\"schemaVersion\":1}";
        else sample("publication-invalid-" + change, "catalog-bootstrap-publication-request", false, request);
        var denied = mvc.perform(auth(post(URL).contentType("application/json").content(body), "ok")).andExpect(status().isBadRequest()).andReturn();
        sample("publication-malformed-problem-" + change, "catalog-bootstrap-publication-problem", true, mapper.readTree(denied.getResponse().getContentAsString())); bundle(0);
    }
    @ParameterizedTest @ValueSource(strings = {"stale", "future", "contradicted", "insufficient", "wrong-source-pin", "wrong-order-pin", "missing-review"})
    void freshAnalysisCannotPromoteStaleContradictedInsufficientOrUnboundSourceFacts(String change) throws Exception {
        var request = source(change, change.equals("contradicted") ? Verdict.SOURCE_DOES_NOT_SUPPORT_CLAIM
                : change.equals("insufficient") ? Verdict.INSUFFICIENT_EVIDENCE : Verdict.SOURCE_SUPPORTS_CLAIM);
        if (change.equals("wrong-source-pin") || change.equals("wrong-order-pin") || change.equals("missing-review")) request = new CatalogBootstrapPublicationRequest(1,
                request.publicationId(), new StoredCandidateDecisionService.Reference(change.equals("missing-review") ? UUID.randomUUID() : request.source().reviewId(),
                change.equals("wrong-source-pin") ? "0".repeat(64) : request.source().reviewSha256(),
                change.equals("wrong-order-pin") ? "0".repeat(64) : request.source().decisionCatalogSha256(), null), request.confirmation());
        var denied = mvc.perform(auth(post(URL).contentType("application/json").content(mapper.writeValueAsString(request)), "ok"))
                .andExpect(status().is(change.equals("missing-review") ? 404 : 409)).andReturn();
        sample("publication-source-problem-" + change, "catalog-bootstrap-publication-problem", true, mapper.readTree(denied.getResponse().getContentAsString())); bundle(0);
    }
    @Test void competingFirstPublicationsSerializeAndIdenticalConcurrentRetriesReturnOneImmutableBundle() throws Exception {
        var request = source("current", Verdict.SOURCE_SUPPORTS_CLAIM);
        var one = CompletableFuture.supplyAsync(() -> publisher.publish(request, actor()));
        var two = CompletableFuture.supplyAsync(() -> publisher.publish(request, actor()));
        var a = one.get(30, TimeUnit.SECONDS); var b = two.get(30, TimeUnit.SECONDS);
        assertNotEquals(a.created(), b.created()); assertEquals(a.receipt(), b.receipt()); bundle(1);
    }
    @Test void differentConcurrentKeysCannotCreateTwoRootsOrOrphanDecisions() throws Exception {
        var request = source("current", Verdict.SOURCE_SUPPORTS_CLAIM);
        var other = new CatalogBootstrapPublicationRequest(1, UUID.randomUUID(), request.source(), request.confirmation());
        var one = CompletableFuture.supplyAsync(() -> attempt(request)); var two = CompletableFuture.supplyAsync(() -> attempt(other));
        assertNotEquals(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS)); bundle(1);
    }
    @ParameterizedTest @ValueSource(strings = {"audit.catalog_publication_events", "core.catalog_bootstrap_publications"})
    void auditOrProofInsertFailureRollsBackDecisionSnapshotAndProofTogether(String table) throws Exception {
        var request = source("current", Verdict.SOURCE_SUPPORTS_CLAIM);
        try (var c = admin(); var s = c.createStatement()) {
            s.execute("CREATE FUNCTION audit.fail_bootstrap_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fictional audit failure'; END $$");
            s.execute("CREATE TRIGGER fail_bootstrap_test BEFORE INSERT ON " + table + " FOR EACH ROW EXECUTE FUNCTION audit.fail_bootstrap_test()");
        }
        try { assertThrows(RuntimeException.class, () -> publisher.publish(request, actor())); bundle(0); }
        finally { try (var c = admin(); var s = c.createStatement()) { s.execute("DROP TRIGGER fail_bootstrap_test ON " + table); s.execute("DROP FUNCTION audit.fail_bootstrap_test()"); } }
        assertTrue(publisher.publish(request, actor()).created()); bundle(1);
    }
    @Test void wellChecksummedStoredCoverageTamperingCannotBecomeAnIdempotentSuccess() throws Exception {
        var request = source("current", Verdict.SOURCE_SUPPORTS_CLAIM); publisher.publish(request, actor());
        var proof = (ObjectNode) mapper.readTree(dsl.fetchOne("select proof::text from core.catalog_bootstrap_publications").get(0, String.class));
        var scenario = (ObjectNode) proof.at("/coverage/scenarios/0"); scenario.put("afterUnknownFindings", scenario.get("afterUnknownFindings").asInt() + 1);
        try (var c = admin(); var s = c.prepareStatement("UPDATE core.catalog_bootstrap_publications SET proof=?::jsonb, proof_sha256=? WHERE id=?")) {
            s.setString(1, proof.toString()); s.setString(2, DecisionCanonicalizer.sha256(proof)); s.setObject(3, request.publicationId()); s.executeUpdate();
        }
        assertEquals(CatalogBootstrapPublicationException.Reason.STORED_PUBLICATION_INVALID,
                assertThrows(CatalogBootstrapPublicationException.class, () -> publisher.publish(request, actor())).reason()); bundle(1);
    }
    @Test void publisherFailsClosedWhenFreshCoverageReportsAnUnexercisedFact() throws Exception {
        var request = source("current", Verdict.SOURCE_SUPPORTS_CLAIM);
        var raw = (ObjectNode) mapper.valueToTree(coverage.inspect(request.source(), request.source()));
        raw.put("status", "INCOMPLETE"); raw.put("decisionScopeCoverageComplete", false);
        raw.set("uncoveredFacts", mapper.createArrayNode().add("after:fictional-option|unexercised-fact"));
        var incomplete = mapper.treeToValue(raw, DecisionPublicationCoverageService.Check.class);
        org.mockito.Mockito.doReturn(incomplete).when(coverage).replay(org.mockito.ArgumentMatchers.eq(request.source()),
                org.mockito.ArgumentMatchers.eq(request.source()), org.mockito.ArgumentMatchers.any(Instant.class));
        try { assertEquals(CatalogBootstrapPublicationException.Reason.COVERAGE_INCOMPLETE,
                assertThrows(CatalogBootstrapPublicationException.class, () -> publisher.publish(request, actor())).reason()); bundle(0); }
        finally { org.mockito.Mockito.reset(coverage); }
    }
    @Test void runtimeAndWebCannotDirectlyWriteRegistryOrProofAndPublicCannotExecutePublisher() throws Exception {
        try (var core = DriverManager.getConnection(postgres.getJdbcUrl(), "authweave_core_runtime", "core-test-password");
                var web = DriverManager.getConnection(postgres.getJdbcUrl(), "authweave_web_runtime", "web-test-password")) {
            for (String table : List.of("core.catalog_bootstrap_publications", "core.catalog_published_snapshots", "core.catalog_publication_decisions", "audit.catalog_publication_events")) {
                for (String sql : List.of("INSERT INTO " + table + " DEFAULT VALUES", "UPDATE " + table + " SET id=id WHERE false", "DELETE FROM " + table + " WHERE false"))
                    try (var statement = core.createStatement()) { assertEquals("42501", assertThrows(java.sql.SQLException.class, () -> statement.execute(sql)).getSQLState()); }
                try (var statement = web.createStatement()) { assertEquals("42501", assertThrows(java.sql.SQLException.class, () -> statement.execute("SELECT * FROM " + table)).getSQLState()); }
            }
            try (var statement = web.createStatement()) { assertEquals("42501", assertThrows(java.sql.SQLException.class,
                    () -> statement.execute("select core.publish_catalog_bootstrap('{}','{}','x','{}')")).getSQLState()); }
        }
        assertFalse(dsl.fetchOne("select has_function_privilege('public', 'core.publish_catalog_bootstrap(jsonb,jsonb,text,jsonb)', 'EXECUTE')").get(0, Boolean.class));
        assertThrows(RuntimeException.class, () -> dsl.fetch("select core.publish_catalog_bootstrap('{}','{}','x','{}')")); bundle(0);
    }
    @Test void serviceRejectsExpiredAuthenticationOnCreateAndRetryAndDoesNotTrustHistoricalCuratorAudit() throws Exception {
        var request = source("current", Verdict.SOURCE_SUPPORTS_CLAIM); var old = new CuratorActor(actor().issuer(), actor().subject(), "123", "456", Instant.now().minusSeconds(901));
        assertEquals(CatalogBootstrapPublicationException.Reason.AUTHENTICATION_EXPIRED,
                assertThrows(CatalogBootstrapPublicationException.class, () -> publisher.publish(request, old)).reason()); bundle(0);
        publisher.publish(request, actor()); assertThrows(CatalogBootstrapPublicationException.class, () -> publisher.publish(request, old)); bundle(1);
    }
    private boolean attempt(CatalogBootstrapPublicationRequest request) {
        try { return publisher.publish(request, actor()).created(); }
        catch (CatalogBootstrapPublicationException conflict) { assertEquals(CatalogBootstrapPublicationException.Reason.CONFLICT, conflict.reason()); return false; }
    }
    @ParameterizedTest @ValueSource(strings = {"current", "stale", "future", "contradicted", "insufficient", "wrong-supplement-pin", "missing-review", "wrong-base"})
    void optionalAuditabilityMustBeExactFreshAndHumanSupportedAndIsBoundWithoutEnteringBaseManifest(String change) throws Exception {
        var fixture = new CandidateDecisionReviewFixture(mapper, reviews, audits, auditDrafts).stored(b -> { }, a -> {
            if (change.equals("stale") || change.equals("future")) dates(a, Instant.now().minusSeconds(change.equals("stale") ? 91 * 86400 : -3600).toString());
        }, change.equals("contradicted") ? Verdict.SOURCE_DOES_NOT_SUPPORT_CLAIM : change.equals("insufficient") ? Verdict.INSUFFICIENT_EVIDENCE : Verdict.SOURCE_SUPPORTS_CLAIM);
        var pin = fixture.reference();
        if (change.equals("wrong-base")) pin = new StoredCandidateDecisionService.Reference(pin.reviewId(), pin.reviewSha256(), "0".repeat(64), pin.auditability());
        if (change.equals("wrong-supplement-pin") || change.equals("missing-review")) {
            var a = pin.auditability(); pin = new StoredCandidateDecisionService.Reference(pin.reviewId(), pin.reviewSha256(), pin.decisionCatalogSha256(),
                    new StoredCandidateDecisionService.AuditReference(change.equals("missing-review") ? UUID.randomUUID() : a.reviewId(), a.reviewSha256(),
                        change.equals("wrong-supplement-pin") ? "0".repeat(64) : a.decisionSupplementSha256()));
        }
        var request = new CatalogBootstrapPublicationRequest(1, UUID.randomUUID(), pin, CatalogBootstrapPublicationRequest.Confirmation.PUBLISH_REVIEWED_BOOTSTRAP);
        if (change.equals("current")) {
            var receipt = publisher.publish(request, actor()).receipt(); assertEquals(pin, receipt.source()); bundle(1);
            evidence(receipt);
            assertFalse(mapper.valueToTree(lookup.lookup(receipt.snapshot()).snapshot()).has("auditabilityDraft"));
            assertFalse(publisher.publish(request, actor()).created());
        } else { assertThrows(RuntimeException.class, () -> publisher.publish(request, actor())); bundle(0); }
    }
    private CatalogBootstrapPublicationRequest source(String change, Verdict verdict) throws Exception {
        var raw = (ObjectNode) mapper.readTree(Files.readString(Path.of("../../packages/contracts/tests/fixtures/provider-catalog-draft.valid.json")));
        dates(raw, Instant.now().minusSeconds(change.equals("stale") ? 91 * 86400 : change.equals("future") ? -3600 : 86400).toString());
        var draft = mapper.treeToValue(raw, ProviderCatalogDraft.class);
        var observations = draft.options().stream().flatMap(o -> CatalogDraftFacts.entries(o).keySet().stream()
                .map(p -> new CatalogBootstrapReviewRequest.Observation(o.id(), p, verdict))).toList();
        var request = new CatalogBootstrapReviewRequest(1, UUID.randomUUID(), CatalogDraftCanonicalizer.sha256(draft), draft, observations,
                CatalogBootstrapReviewRequest.Confirmation.MANUAL_BOOTSTRAP_SOURCE_REVIEW);
        var review = reviews.record(request, actor()).review(); var saved = mapper.readTree(reviews.loadForDecision(review.reviewId(), review.reviewSha256()).candidateJson());
        return new CatalogBootstrapPublicationRequest(1, UUID.randomUUID(),
                new StoredCandidateDecisionService.Reference(review.reviewId(), review.reviewSha256(), DecisionCanonicalizer.sha256(saved), null),
                CatalogBootstrapPublicationRequest.Confirmation.PUBLISH_REVIEWED_BOOTSTRAP);
    }
    private void dates(JsonNode n, String at) {
        if (n.isObject() && n.has("observedAt")) ((ObjectNode) n).put("observedAt", at);
        if (n.isObject()) n.properties().forEach(e -> dates(e.getValue(), at)); else if (n.isArray()) n.forEach(child -> dates(child, at));
    }
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String guard) {
        if (!guard.equals("no-credential")) b.header("Authorization", guard.equals("bad-credential") ? "Bearer invalid" : TOKEN);
        if (guard.equals("duplicate-credential")) b.header("Authorization", TOKEN);
        if (!guard.equals("no-issuer")) b.header("X-AuthWeave-Oidc-Issuer", actor().issuer());
        if (!guard.equals("no-subject")) b.header("X-AuthWeave-Oidc-Subject", guard.equals("another-subject") ? "other-publisher" : actor().subject());
        if (guard.equals("duplicate-subject")) b.header("X-AuthWeave-Oidc-Subject", actor().subject());
        b.header("X-AuthWeave-Curator-Role", guard.equals("role") ? "assessor" : "catalog_curator");
        if (guard.equals("duplicate-role")) b.header("X-AuthWeave-Curator-Role", "catalog_curator");
        return b.header("X-AuthWeave-Curator-Project-Id", guard.equals("project") ? "999" : "123")
                .header("X-AuthWeave-Curator-Org-Id", guard.equals("organization") ? "999" : "456")
                .header("X-AuthWeave-Authenticated-At", guard.equals("invalid-time") ? "never"
                    : Instant.now().minusSeconds(guard.equals("stale") ? 901 : guard.equals("future") ? -60 : 0).toString());
    }
    private static CuratorActor actor() { return new CuratorActor("https://identity.example.invalid", "fictional-publisher", "123", "456", Instant.now()); }
    private void bundle(int count) {
        for (String table : List.of("core.catalog_bootstrap_publications", "core.catalog_published_snapshots", "core.catalog_publication_decisions", "audit.catalog_publication_events"))
            assertEquals(count, dsl.fetchOne("select count(*) from " + table).get(0, Integer.class), table);
    }
    private java.sql.Connection admin() throws Exception { return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); }
    private void sample(String name, String schema, boolean valid, JsonNode payload) { samples.add(new Sample(name, schema, valid, payload)); }
    private void evidence(CatalogBootstrapPublisher.Receipt receipt) {
        var row = dsl.fetchOne("select proof::text, proof_sha256 from core.catalog_bootstrap_publications");
        evidenceSamples.add(new EvidenceSample(mapper.readTree(row.get(0, String.class)), row.get(1, String.class),
                mapper.valueToTree(lookup.lookup(receipt.snapshot()).snapshot()), mapper.valueToTree(receipt),
                mapper.readTree(reviews.loadForDecision(receipt.source().reviewId(), receipt.source().reviewSha256()).candidateJson()),
                receipt.source().auditability() == null ? null : mapper.readTree(audits.loadForDecision(receipt.source().auditability().reviewId(),
                    receipt.source().auditability().reviewSha256()).candidateJson()).get("auditabilityDraft")));
    }
}

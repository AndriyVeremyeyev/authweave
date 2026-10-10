package io.authweave.core.catalog.publication;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
import tools.jackson.databind.node.*;
import io.authweave.core.catalog.auditability.*;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.impact.*;
import io.authweave.core.catalog.proposal.*;
import io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Only fictional .invalid sources, synthetic identities and an isolated disposable PostgreSQL database. */
@SpringBootTest(properties = {"AUTHWEAVE_CORE_SERVICE_TOKEN=synthetic-proposal-publication-token-000000000000000",
        "AUTHWEAVE_OIDC_PROJECT_ID=123", "AUTHWEAVE_OIDC_ORG_ID=456"})
@ActiveProfiles({"catalog-bootstrap-publication", "catalog-proposal-publication"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogProposalPublicationIntegrationTests {
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-bookworm")
            .withDatabaseName("authweave").withUsername("authweave_admin").withPassword("admin-test-password").withInitScript("db/test/init-runtime-roles.sql");
    static { postgres.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl); r.add("spring.datasource.username", () -> "authweave_core_runtime");
        r.add("spring.datasource.password", () -> "core-test-password"); r.add("spring.datasource.hikari.maximum-pool-size", () -> 3);
        r.add("spring.datasource.hikari.minimum-idle", () -> 0); r.add("spring.flyway.url", postgres::getJdbcUrl);
        r.add("spring.flyway.user", postgres::getUsername); r.add("spring.flyway.password", postgres::getPassword);
    }
    private static final String URL = "/internal/v1/catalog-curator/proposal-publications";
    private static final String ID = "example-managed-eu";
    @Autowired DSLContext dsl; @Autowired ObjectMapper mapper; @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CatalogBootstrapPublisher bootstrap; @Autowired CatalogProposalPublisher publisher;
    @Autowired CatalogBootstrapReviewService reviews; @Autowired CatalogAuditabilityReviewService audits;
    @Autowired CatalogAuditabilityDraftValidator auditDrafts; @Autowired CatalogProposalPublicationReader reader;
    @Autowired TrustedPublishedCatalogService trusted; @Autowired CatalogChangePreviewService previews;
    @Autowired CatalogProposalRepository proposalRepository; @Autowired CatalogFactReviewWriter facts;
    @Autowired StoredProposalDecisionService proposals; @Autowired DecisionPublicationCoveragePolicy policy;
    @Autowired CatalogProposalRejectionWriter rejections;
    @MockitoSpyBean Clock clock;
    private final List<Object> samples = new ArrayList<>();
    private final List<Object> evidence = new ArrayList<>();
    @BeforeEach void clean() throws Exception {
        sql("TRUNCATE core.catalog_bootstrap_reviews, core.catalog_auditability_reviews, core.catalog_proposals, core.catalog_publication_decisions CASCADE");
    }
    @AfterAll void export() throws Exception {
        Files.writeString(Path.of("target/proposal-publication-http-contract-samples.json"), mapper.writeValueAsString(samples));
        Files.writeString(Path.of("target/proposal-publication-proof-samples.json"), mapper.writeValueAsString(evidence));
    }
    record Fixture(CatalogBootstrapPublisher.Receipt root, CatalogProposalSnapshot proposal, CatalogProposalPublicationRequest request) { }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void atomicFirstSuccessorIsReplayableActorBoundAndDoesNotActivateOrCertify(boolean audit) throws Exception {
        var f = fixture(audit, c -> ((ObjectNode) c.at("/options/0/facts/SCIM")).put("availability", "UNAVAILABLE"));
        var before = trusted.load(f.root().snapshot());
        sample("request-" + audit, "catalog-proposal-publication-request", true, mapper.valueToTree(f.request()));
        var http = mvc.perform(auth(post(URL).contentType("application/json").content(mapper.writeValueAsString(f.request())), "ok"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        var receipt = mapper.readTree(http.getResponse().getContentAsString()); sample("created-" + audit, "catalog-proposal-publication", true, receipt);
        var result = publisher.publish(f.request(), actor()); assertFalse(result.created());
        assertEquals(receipt, mapper.readTree(mapper.writeValueAsString(result.receipt()))); bundle(1);
        var loaded = trusted.load(result.receipt().snapshot()); assertEquals(CatalogProposalPublicationReader.VERSION, loaded.publication().loaderVersion());
        assertTrue(loaded.publication().historicalPublicationWorkflowVerified()); assertFalse(loaded.publication().externalSourceVerificationPerformed());
        assertFalse(loaded.publication().assessmentResultPinned()); assertEquals(22, loaded.publication().verificationGaps().size());
        assertEquals(f.root().snapshot(), loaded.publication().snapshot().previousSnapshot());
        assertEquals(before.publication().proofSha256(), trusted.load(f.root().snapshot()).publication().proofSha256());
        assertEquals(before.decisionInputs().catalog(), trusted.load(f.root().snapshot()).decisionInputs().catalog());
        assertEquals(proposals.load(f.request().proposal()).snapshot().catalog(), loaded.decisionInputs().catalog());
        assertEquals(audit, loaded.decisionInputs().auditability() != null);
        assertFalse(result.receipt().evaluationReady()); assertFalse(result.receipt().externalSourceVerificationPerformed());
        assertThrows(CatalogProposalPublicationException.class, () -> publisher.publish(f.request(), new CuratorActor(actor().issuer(), "another-curator", "123", "456", Instant.now())));
        var pin = f.request().proposal();
        var changed = new CatalogProposalPublicationRequest(1, f.request().publicationId(), f.request().before(),
                new StoredProposalDecisionService.Reference(pin.revision(), pin.reviewThroughNumber(), "0".repeat(64), pin.auditability()), f.request().confirmation());
        assertEquals(CatalogProposalPublicationException.Reason.CONFLICT, assertThrows(CatalogProposalPublicationException.class,
                () -> publisher.publish(changed, actor())).reason());
        assertThrows(CatalogFactReviewConflictException.class, () -> review(f.proposal(), "facts.SCIM", Verdict.SOURCE_DOES_NOT_SUPPORT_CLAIM));
        var historic = loaded.publication().proofSha256(); doReturn(Instant.now().plusSeconds(91L * 86400)).when(clock).instant();
        assertEquals(historic, trusted.load(result.receipt().snapshot()).publication().proofSha256());
        assertFalse(publisher.publish(f.request(), actor()).created());
        evidence(f, result.receipt());
        for (String flag : List.of("evaluationReady", "externalSourceVerificationPerformed")) {
            var forged = (ObjectNode) receipt.deepCopy(); forged.put(flag, true); sample("forged-" + flag, "catalog-proposal-publication", false, forged);
        }
        for (String hidden : List.of("fictional-proposal-curator", "sourceUrl", "actor", "scenarios", "candidate")) assertFalse(receipt.toString().contains(hidden));
    }
    @ParameterizedTest @ValueSource(strings = {"no-token", "bad-token", "duplicate-token", "no-subject", "duplicate-subject", "role", "project", "org", "stale", "future"})
    void currentScopedAuthorizationRequiredForCreationAndRetry(String guard) throws Exception {
        var f = fixture(false, c -> { }); var body = mapper.writeValueAsString(f.request());
        int status = List.of("no-token", "bad-token", "duplicate-token", "no-subject", "duplicate-subject").contains(guard) ? 401 : 403;
        mvc.perform(auth(post(URL).contentType("application/json").content(body), guard)).andExpect(status().is(status)); bundle(0);
        publisher.publish(f.request(), actor());
        mvc.perform(auth(post(URL).contentType("application/json").content(body), guard)).andExpect(status().is(status)); bundle(1);
    }
    @ParameterizedTest @ValueSource(strings = {"manifest", "coverage", "clock", "verdicts", "schema", "confirmation", "missing-audit", "duplicate"})
    void noTransportedDecisionsOrNonStrictBody(String change) throws Exception {
        var f = fixture(false, c -> { }); var body = (ObjectNode) mapper.valueToTree(f.request());
        switch (change) {
            case "schema" -> body.put("schemaVersion", 2);
            case "confirmation" -> body.put("confirmation", "APPROVE");
            case "missing-audit" -> ((ObjectNode) body.get("proposal")).remove("auditability");
            case "duplicate" -> { }
            default -> body.set(change, mapper.createObjectNode());
        }
        var json = mapper.writeValueAsString(body);
        if (change.equals("duplicate")) json = json.substring(0, json.length() - 1) + ",\"schemaVersion\":1}";
        else sample("invalid-" + change, "catalog-proposal-publication-request", false, body);
        mvc.perform(auth(post(URL).contentType("application/json").content(json), "ok")).andExpect(status().isBadRequest()); bundle(0);
    }
    @ParameterizedTest @ValueSource(strings = {"MISSING", "SOURCE_DOES_NOT_SUPPORT_CLAIM", "INSUFFICIENT_EVIDENCE", "STALE", "FUTURE"})
    void coverageDoesNotEraseSourceEligibility(String issue) {
        var f = fixture(false, c -> {
            if (issue.equals("STALE") || issue.equals("FUTURE")) ((ObjectNode) c.at("/options/0/facts/SCIM/evidence")).put("observedAt",
                    (issue.equals("STALE") ? Instant.now().minusSeconds(91L * 86400) : Instant.now().plusSeconds(86400)).toString());
        }, issue.equals("MISSING"));
        if (issue.equals("SOURCE_DOES_NOT_SUPPORT_CLAIM") || issue.equals("INSUFFICIENT_EVIDENCE")) review(f.proposal(), "facts.SCIM", Verdict.valueOf(issue));
        var current = request(f.root(), f.proposal(), null); var failure = assertThrows(CatalogProposalPublicationException.class, () -> publisher.publish(current, actor()));
        assertEquals(CatalogProposalPublicationException.Reason.SOURCE_NOT_ELIGIBLE, failure.reason()); bundle(0);
    }
    @Test void latestLedgerRequiredEvenWhenHistoricalPinIsSupporting() throws Exception {
        var f = fixture(false, c -> { }); review(f.proposal(), "facts.SCIM", Verdict.INSUFFICIENT_EVIDENCE);
        assertEquals(CatalogProposalPublicationException.Reason.CONFLICT, assertThrows(CatalogProposalPublicationException.class,
                () -> publisher.publish(f.request(), actor())).reason()); bundle(0);
        var response = mvc.perform(auth(post(URL).contentType("application/json").content(mapper.writeValueAsString(f.request())), "ok"))
                .andExpect(status().isConflict()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        sample("latest-ledger-conflict", "catalog-proposal-publication-problem", true, mapper.readTree(response.getResponse().getContentAsString())); bundle(0);
    }
    @Test void staleHeadCannotPublishButPublishedHistorySurvivesLaterRevision() {
        var f = fixture(false, c -> { }); var saved = publisher.publish(f.request(), actor()).receipt();
        var raw = mapper.treeToValue(f.proposal().request(), CatalogChangePreviewRequest.class);
        var candidate = (ObjectNode) mapper.valueToTree(raw.candidate()); ((ArrayNode) candidate.at("/options/0/facts/SCIM/conditions")).add("Later fictional revision");
        save(new CatalogChangePreviewRequest(1, raw.proposalId(), raw.rationale(), raw.expectedBaseSha256(), raw.base(), mapper.treeToValue(candidate, ProviderCatalogDraft.class)), f.proposal().version());
        assertEquals(saved.proofSha256(), reader.load(saved.snapshot()).proofSha256()); assertFalse(publisher.publish(f.request(), actor()).created());
    }
    @Test void unpublishedStaleHeadIsDenied() {
        var f = fixture(false, c -> { }); var raw = mapper.treeToValue(f.proposal().request(), CatalogChangePreviewRequest.class);
        var candidate = (ObjectNode) mapper.valueToTree(raw.candidate()); ((ArrayNode) candidate.at("/options/0/facts/SCIM/conditions")).add("Changed head");
        save(new CatalogChangePreviewRequest(1, raw.proposalId(), raw.rationale(), raw.expectedBaseSha256(), raw.base(), mapper.treeToValue(candidate, ProviderCatalogDraft.class)), f.proposal().version());
        assertEquals(CatalogProposalPublicationException.Reason.CONFLICT, assertThrows(CatalogProposalPublicationException.class, () -> publisher.publish(f.request(), actor())).reason()); bundle(0);
    }
    @Test void sameKeySerializesAndCompetingSuccessorsCannotFork() throws Exception {
        var f = fixture(false, c -> { }); var a = CompletableFuture.supplyAsync(() -> publisher.publish(f.request(), actor()));
        var b = CompletableFuture.supplyAsync(() -> publisher.publish(f.request(), actor()));
        CompletableFuture.allOf(a, b).get(30, TimeUnit.SECONDS);
        var first = a.get(30, TimeUnit.SECONDS); var second = b.get(30, TimeUnit.SECONDS);
        assertNotEquals(first.created(), second.created()); assertEquals(first.receipt(), second.receipt()); bundle(1);
        var fresh = new CatalogProposalPublicationRequest(1, UUID.randomUUID(), f.request().before(), f.request().proposal(), f.request().confirmation());
        assertThrows(CatalogProposalPublicationException.class, () -> publisher.publish(fresh, actor())); bundle(1);
    }
    @ParameterizedTest @ValueSource(strings = {"audit", "proof"})
    void failedBundleInsertRollsBackEveryNewArtifact(String part) throws Exception {
        var f = fixture(false, c -> { }); String table = part.equals("audit") ? "audit.catalog_publication_events" : "core.catalog_proposal_publications";
        sql("CREATE FUNCTION core.synthetic_publication_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Fictional isolated failure'; END $$");
        sql("CREATE TRIGGER synthetic_failure BEFORE INSERT ON " + table + " FOR EACH ROW EXECUTE FUNCTION core.synthetic_publication_failure()");
        try { assertThrows(org.jooq.exception.DataAccessException.class, () -> publisher.publish(f.request(), actor())); bundle(0); }
        finally { sql("DROP TRIGGER synthetic_failure ON " + table); sql("DROP FUNCTION core.synthetic_publication_failure()"); }
        assertTrue(publisher.publish(f.request(), actor()).created()); bundle(1);
    }
    @ParameterizedTest @ValueSource(strings = {"afterCatalogSha256", "beforeProofSha256", "manifestSha256", "decisionPolicySha256", "scenarioSetSha256"})
    void checksummedForgedCoverageFailsFullHistoricalReplay(String field) throws Exception {
        var f = fixture(false, c -> { }); var receipt = publisher.publish(f.request(), actor()).receipt();
        var proof = (ObjectNode) mapper.readTree(dsl.fetchOne("select proof::text from core.catalog_proposal_publications").get(0, String.class));
        ((ObjectNode) proof.get("coverage")).put(field, "0".repeat(64));
        try (var c = admin(); var s = c.prepareStatement("UPDATE core.catalog_proposal_publications SET proof=?::jsonb, proof_sha256=?")) {
            s.setString(1, proof.toString()); s.setString(2, DecisionCanonicalizer.sha256(proof)); s.executeUpdate();
        }
        assertEquals(CatalogPublishedLoadingException.Reason.STORED_PUBLICATION_INVALID, assertThrows(CatalogPublishedLoadingException.class, () -> trusted.load(receipt.snapshot())).reason());
        assertEquals(CatalogProposalPublicationException.Reason.STORED_PUBLICATION_INVALID, assertThrows(CatalogProposalPublicationException.class,
                () -> publisher.publish(f.request(), actor())).reason());
    }
    @Test void exactReferenceAndFreshServiceActorRemainRequired() {
        var f = fixture(false, c -> { }); var expired = new CuratorActor(actor().issuer(), actor().subject(), "123", "456", Instant.now().minusSeconds(901));
        assertEquals(CatalogProposalPublicationException.Reason.AUTHENTICATION_EXPIRED, assertThrows(CatalogProposalPublicationException.class,
                () -> publisher.publish(f.request(), expired)).reason()); bundle(0);
        var receipt = publisher.publish(f.request(), actor()).receipt();
        assertThrows(CatalogProposalPublicationException.class, () -> publisher.publish(f.request(), expired));
        var wrong = new PublishedCatalogSnapshot.Reference(receipt.snapshot().snapshotId(), receipt.snapshot().catalogVersion(), "0".repeat(64));
        assertEquals(CatalogPublishedLoadingException.Reason.REFERENCE_MISMATCH, assertThrows(CatalogPublishedLoadingException.class, () -> trusted.load(wrong)).reason());
    }
    @Test void runtimeHasOnlySelectAndNarrowFunctionAndWebHasNeither() {
        for (String permission : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE")) assertFalse(dsl.fetchOne("select has_table_privilege(current_user, 'core.catalog_proposal_publications', ?)", permission).get(0, Boolean.class));
        assertTrue(dsl.fetchOne("select has_function_privilege(current_user, 'core.publish_catalog_proposal(jsonb,jsonb,text,jsonb)', 'EXECUTE')").get(0, Boolean.class));
        assertFalse(dsl.fetchOne("select has_function_privilege('authweave_web_runtime', 'core.publish_catalog_proposal(jsonb,jsonb,text,jsonb)', 'EXECUTE')").get(0, Boolean.class));
        assertFalse(dsl.fetchOne("select has_table_privilege('authweave_web_runtime', 'core.catalog_proposal_publications', 'SELECT')").get(0, Boolean.class));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> dsl.fetch("select core.publish_catalog_proposal('{}','{}',?, '{}')", "0".repeat(64)));
    }
    @Test void rejectionAndPublicationAreMutuallyExclusiveInBothOrders() {
        var f = fixture(false, c -> { });
        rejections.reject(f.proposal().proposalId(), rejection(f.proposal()), actor());
        assertEquals(CatalogProposalPublicationException.Reason.CONFLICT, assertThrows(CatalogProposalPublicationException.class,
                () -> publisher.publish(f.request(), actor())).reason()); bundle(0);
    }
    @Test void committedPublicationRejectsRejectionAndDirectRuntimeReviewAppend() {
        var f = fixture(false, c -> { }); publisher.publish(f.request(), actor());
        assertThrows(CatalogProposalDecisionConflictException.class, () -> rejections.reject(f.proposal().proposalId(), rejection(f.proposal()), actor()));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> dsl.execute("insert into core.catalog_fact_reviews "
                + "(id,proposal_id,proposal_version,proposal_sha256,option_id,fact_path,verdict) values(?,?,?,?,'example-managed-eu','facts.SCIM','SOURCE_SUPPORTS_CLAIM')",
                UUID.randomUUID(), f.proposal().proposalId(), f.proposal().version(), f.proposal().proposalSha256())); bundle(1);
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "digest", "label"})
    void exactParentCannotBeSubstituted(String kind) {
        var f = fixture(false, c -> { }); var root = f.root().snapshot();
        var wrong = new PublishedCatalogSnapshot.Reference(kind.equals("missing") ? UUID.randomUUID() : root.snapshotId(),
                kind.equals("label") ? "another-label" : root.catalogVersion(), kind.equals("digest") ? "0".repeat(64) : root.snapshotSha256());
        var r = new CatalogProposalPublicationRequest(1, f.request().publicationId(), wrong, f.request().proposal(), f.request().confirmation());
        assertThrows(CatalogProposalPublicationException.class, () -> publisher.publish(r, actor())); bundle(0);
    }
    @Test void secondHopIsExplicitlyUnsupportedByFrozenPolicyOne() {
        var f = fixture(false, c -> { }); var child = publisher.publish(f.request(), actor()).receipt();
        var baseline = mapper.treeToValue(trusted.load(child.snapshot()).decisionInputs().catalog(), ProviderCatalogDraft.class);
        var candidate = (ObjectNode) mapper.valueToTree(baseline); candidate.put("catalogVersion", "fictional-third-version");
        ((ArrayNode) candidate.at("/options/0/facts/SCIM/conditions")).add("Unsupported policy-one second hop");
        var p = save(new CatalogChangePreviewRequest(1, UUID.randomUUID(), "Fictional unsupported hop", CatalogDraftCanonicalizer.sha256(baseline), baseline,
                mapper.treeToValue(candidate, ProviderCatalogDraft.class)), null);
        var revision = new StoredProposalDecisionService.Revision(p.proposalId(), p.version(), p.proposalSha256(), DecisionCanonicalizer.sha256(p.request()));
        var r = new CatalogProposalPublicationRequest(1, UUID.randomUUID(), child.snapshot(), proposals.pin(revision, null), CatalogProposalPublicationRequest.Confirmation.PUBLISH_REVIEWED_PROPOSAL);
        assertThrows(CatalogProposalPublicationException.class, () -> publisher.publish(r, actor())); bundle(1);
    }
    private CatalogProposalRejectionRequest rejection(CatalogProposalSnapshot p) {
        return new CatalogProposalRejectionRequest(p.version(), p.proposalSha256(), CatalogProposalRejectionRequest.ReasonCode.INSUFFICIENT_EVIDENCE);
    }
    private Fixture fixture(boolean audit, Consumer<ObjectNode> change) { return fixture(audit, change, false); }
    private Fixture fixture(boolean audit, Consumer<ObjectNode> change, boolean missing) {
        var source = new CandidateDecisionReviewFixture(mapper, reviews, audits, auditDrafts).stored().reference();
        var root = bootstrap.publish(new CatalogBootstrapPublicationRequest(1, UUID.randomUUID(), source, CatalogBootstrapPublicationRequest.Confirmation.PUBLISH_REVIEWED_BOOTSTRAP), actor()).receipt();
        var base = mapper.treeToValue(trusted.load(root.snapshot()).decisionInputs().catalog(), ProviderCatalogDraft.class);
        var candidate = (ObjectNode) mapper.valueToTree(base); candidate.put("catalogVersion", "fictional-published-successor");
        ((ArrayNode) candidate.at("/options/0/facts/SCIM/conditions")).add("Fictional proposed configuration"); change.accept(candidate);
        var p = save(new CatalogChangePreviewRequest(1, UUID.randomUUID(), "Fictional isolated publication", CatalogDraftCanonicalizer.sha256(base), base,
                mapper.treeToValue(candidate, ProviderCatalogDraft.class)), null);
        var request = mapper.treeToValue(p.request(), CatalogChangePreviewRequest.class);
        request.candidate().options().forEach(o -> CatalogDraftFacts.entries(o).keySet().stream().sorted().filter(path -> !missing || !path.equals("facts.SCIM"))
                .forEach(path -> facts.record(p.proposalId(), new CatalogFactReviewRequest(UUID.randomUUID(), p.version(), p.proposalSha256(), o.id(), path,
                        Verdict.SOURCE_SUPPORTS_CLAIM, CatalogFactReviewRequest.Confirmation.MANUAL_SOURCE_REVIEW), actor())));
        return new Fixture(root, p, request(root, p, audit ? supplement(p) : null));
    }
    private CatalogProposalPublicationRequest request(CatalogBootstrapPublisher.Receipt root, CatalogProposalSnapshot p, StoredCandidateDecisionService.AuditReference audit) {
        var revision = new StoredProposalDecisionService.Revision(p.proposalId(), p.version(), p.proposalSha256(), DecisionCanonicalizer.sha256(p.request()));
        return new CatalogProposalPublicationRequest(1, UUID.randomUUID(), root.snapshot(), proposals.pin(revision, audit), CatalogProposalPublicationRequest.Confirmation.PUBLISH_REVIEWED_PROPOSAL);
    }
    private CatalogProposalSnapshot save(CatalogChangePreviewRequest request, Long version) {
        return new TransactionTemplate(transactions).execute(tx -> new LocalCatalogProposalWriter(dsl, mapper, previews, proposalRepository).save(request, version).proposal());
    }
    private void review(CatalogProposalSnapshot p, String path, Verdict verdict) {
        facts.record(p.proposalId(), new CatalogFactReviewRequest(UUID.randomUUID(), p.version(), p.proposalSha256(), ID, path, verdict,
                CatalogFactReviewRequest.Confirmation.MANUAL_SOURCE_REVIEW), actor());
    }
    private StoredCandidateDecisionService.AuditReference supplement(CatalogProposalSnapshot p) {
        var base = mapper.treeToValue(p.request().get("candidate"), ProviderCatalogDraft.class);
        var raw = (ObjectNode) mapper.readTree(Path.of("../../packages/contracts/tests/fixtures/catalog-auditability-draft.valid.json").toFile());
        dates(raw, Instant.now().minusSeconds(86400).toString()); raw.put("baseContentSha256", CatalogDraftCanonicalizer.sha256(base)); raw.put("baseCatalogVersion", base.catalogVersion());
        var request = new CatalogAuditabilityDraftValidator.Request(base, mapper.treeToValue(raw, AuditabilityCatalogDraft.class)); var v = auditDrafts.validate(request);
        var receipt = audits.record(new CatalogAuditabilityReviewRequest(1, UUID.randomUUID(), v.baseValidation().contentSha256(), v.contentSha256(), v.reviewTargetSetSha256(), request,
                v.targets().stream().map(t -> new CatalogAuditabilityReviewRequest.Observation(t.scope().optionId(), t.fact().criterion(), t.targetSha256(), Verdict.SOURCE_SUPPORTS_CLAIM)).toList(),
                CatalogAuditabilityReviewRequest.Confirmation.MANUAL_AUDITABILITY_SOURCE_REVIEW), actor()).review();
        var saved = mapper.readTree(audits.loadForDecision(receipt.reviewId(), receipt.reviewSha256()).candidateJson()).get("auditabilityDraft");
        return new StoredCandidateDecisionService.AuditReference(receipt.reviewId(), receipt.reviewSha256(), DecisionCanonicalizer.sha256(saved));
    }
    private void evidence(Fixture f, CatalogProposalPublisher.Receipt receipt) {
        var proof = mapper.readTree(dsl.fetchOne("select proof::text from core.catalog_proposal_publications").get(0, String.class));
        var before = trusted.load(f.root().snapshot()).decisionInputs(); var after = proposals.load(f.request().proposal()).snapshot();
        var at = Instant.parse(proof.get("publishedAt").asText());
        var impacts = policy.scenarios().stream().map(s -> CandidateDecisionImpactEvaluator.evaluate(s.profile(), 6, s.weights(), before, after, at)).toList();
        evidence.add(Map.of("proof", proof, "proofSha256", receipt.proofSha256(), "receipt", receipt, "snapshot", reader.load(receipt.snapshot()).snapshot(),
                "calculation", Map.of("check", proof.get("coverage"), "before", before, "after", after, "impacts", impacts,
                    "origin", Map.of("before", f.root().snapshot(), "beforeProofSha256", f.root().proofSha256(), "after", f.request().proposal(), "evaluatedAt", at))));
    }
    private void sample(String name, String schema, boolean valid, JsonNode payload) { samples.add(Map.of("name", name, "schema", schema, "valid", valid, "payload", payload)); }
    private void bundle(int children) {
        for (String table : List.of("core.catalog_publication_decisions", "core.catalog_published_snapshots", "audit.catalog_publication_events"))
            assertEquals(children + 1, dsl.fetchOne("select count(*) from " + table).get(0, Integer.class));
        assertEquals(children, dsl.fetchOne("select count(*) from core.catalog_proposal_publications").get(0, Integer.class));
    }
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String issue) {
        if (!issue.equals("no-token")) b.header("Authorization", issue.equals("bad-token") ? "Bearer invalid" : "Bearer synthetic-proposal-publication-token-000000000000000");
        if (issue.equals("duplicate-token")) b.header("Authorization", "Bearer synthetic-proposal-publication-token-000000000000000");
        b.header("X-AuthWeave-Oidc-Issuer", actor().issuer());
        if (!issue.equals("no-subject")) b.header("X-AuthWeave-Oidc-Subject", actor().subject());
        if (issue.equals("duplicate-subject")) b.header("X-AuthWeave-Oidc-Subject", actor().subject());
        b.header("X-AuthWeave-Curator-Role", issue.equals("role") ? "viewer" : "catalog_curator");
        b.header("X-AuthWeave-Curator-Project-Id", issue.equals("project") ? "999" : "123"); b.header("X-AuthWeave-Curator-Org-Id", issue.equals("org") ? "999" : "456");
        b.header("X-AuthWeave-Authenticated-At", (issue.equals("stale") ? Instant.now().minusSeconds(901) : issue.equals("future") ? Instant.now().plusSeconds(31) : Instant.now()).toString());
        return b;
    }
    private void sql(String sql) throws Exception { try (var c = admin(); var s = c.createStatement()) { s.execute(sql); } }
    private java.sql.Connection admin() throws Exception { return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); }
    private static CuratorActor actor() { return new CuratorActor("https://identity.example.invalid", "fictional-proposal-curator", "123", "456", Instant.now()); }
    private void dates(JsonNode n, String at) {
        if (n.isObject() && n.has("observedAt")) ((ObjectNode) n).put("observedAt", at);
        if (n.isObject()) n.properties().forEach(e -> dates(e.getValue(), at)); else if (n.isArray()) n.forEach(c -> dates(c, at));
    }
}

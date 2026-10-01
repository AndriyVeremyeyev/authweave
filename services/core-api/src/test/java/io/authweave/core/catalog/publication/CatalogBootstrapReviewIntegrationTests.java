package io.authweave.core.catalog.publication;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;
import static io.authweave.core.catalog.publication.CatalogPublicationLookupFixtures.*;
import static io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict.*;
import static io.authweave.core.generated.jooq.tables.CatalogBootstrapReviews.CATALOG_BOOTSTRAP_REVIEWS;
import static io.authweave.core.generated.audit.tables.CatalogBootstrapReviewEvents.CATALOG_BOOTSTRAP_REVIEW_EVENTS;
import static io.authweave.core.generated.jooq.tables.CatalogPublicationDecisions.CATALOG_PUBLICATION_DECISIONS;
import static io.authweave.core.generated.jooq.tables.CatalogPublishedSnapshots.CATALOG_PUBLISHED_SNAPSHOTS;
import static io.authweave.core.generated.audit.tables.CatalogPublicationEvents.CATALOG_PUBLICATION_EVENTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"AUTHWEAVE_CORE_SERVICE_TOKEN=synthetic-bootstrap-service-token-000000000000000",
        "AUTHWEAVE_OIDC_PROJECT_ID=123456789012345678", "AUTHWEAVE_OIDC_ORG_ID=987654321098765432"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CatalogBootstrapReviewIntegrationTests {
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-bookworm")
            .withDatabaseName("authweave").withUsername("authweave_admin").withPassword("admin-test-password")
            .withInitScript("db/test/init-runtime-roles.sql");
    static { postgres.start(); }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "authweave_core_runtime");
        registry.add("spring.datasource.password", () -> "core-test-password");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 3);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }
    private static final String BASE = "/api/v1/catalog-bootstrap-reviews";
    private static final String TOKEN = "Bearer synthetic-bootstrap-service-token-000000000000000";
    private static final Path SAMPLES = Path.of("target", "bootstrap-review-http-contract-samples.json");
    private final List<Sample> samples = new ArrayList<>();
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private DSLContext dsl;
    @Autowired private CatalogDraftValidator drafts;
    @Autowired private CatalogBootstrapReviewService service;
    @Autowired private CatalogBootstrapReviewRepository repository;
    @Autowired private CatalogPublicationPreflight preflight;
    @BeforeAll void clearSamples() throws Exception { Files.deleteIfExists(SAMPLES); }
    @AfterAll void samples() throws Exception { Files.createDirectories(SAMPLES.getParent()); Files.writeString(SAMPLES, mapper.writeValueAsString(samples)); }

    @Order(1) @ParameterizedTest
    @ValueSource(strings = {"no-credential", "bad-credential", "duplicate-credential", "no-subject", "no-issuer", "duplicate-subject",
            "role", "duplicate-role", "project", "organization", "stale", "future", "invalid-time"})
    void credentialScopeFreshnessAndSingularIdentityAreRequiredForReadsWritesAndUnknownVersions(String guard) throws Exception {
        var body = request();
        for (String path : List.of(BASE, "/api/v2/catalog-bootstrap-reviews")) {
            var action = auth(post(path).contentType("application/json").content(mapper.writeValueAsString(body)), guard);
            int expected = List.of("no-credential", "bad-credential", "duplicate-credential", "no-subject", "no-issuer", "duplicate-subject").contains(guard) ? 401 : 403;
            mvc.perform(action).andExpect(status().is(expected));
            mvc.perform(auth(get(path + "/" + body.reviewId()).param("expectedSha256", "a".repeat(64)), guard)).andExpect(status().is(expected));
        }
        assertEquals(0, dsl.fetchCount(CATALOG_BOOTSTRAP_REVIEWS)); assertEquals(0, dsl.fetchCount(CATALOG_BOOTSTRAP_REVIEW_EVENTS));
    }

    @Order(2) @Test
    void immutableReviewAndAuditHaveActorBoundCanonicalIdempotencyAndBodyFreeHttpReceipts() throws Exception {
        var input = request(); sample("bootstrap-valid-request", "catalog-bootstrap-review-request", true, mapper.valueToTree(input));
        var created = mvc.perform(auth(post(BASE).contentType("application/json").content(mapper.writeValueAsString(input)), "ok"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.factCount").value(9))
                .andExpect(jsonPath("$.approvalGranted").value(false)).andReturn();
        var receipt = payload(created); sample("bootstrap-created-receipt", "catalog-bootstrap-review", true, receipt);
        String digest = receipt.get("reviewSha256").asText();
        var reordered = with(input, "observations", List.copyOf(input.observations().reversed()));
        var retried = mvc.perform(auth(post(BASE).contentType("application/json").content(mapper.writeValueAsString(reordered)), "ok"))
                .andExpect(status().isOk()).andReturn(); assertEquals(receipt, payload(retried));
        var read = mvc.perform(auth(get(BASE + "/" + input.reviewId()).param("expectedSha256", digest), "ok"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        assertEquals(receipt, payload(read)); sample("bootstrap-read-receipt", "catalog-bootstrap-review", true, payload(read));
        mvc.perform(auth(post(BASE).contentType("application/json").content(mapper.writeValueAsString(input)), "stale"))
                .andExpect(status().isForbidden());
        assertEquals(1, reviews(input.reviewId())); assertEquals(1, events(input.reviewId()));
        assertFalse(receipt.toString().contains("sourceUrl")); assertFalse(receipt.toString().contains("bootstrap-curator"));
        assertFalse(receipt.toString().contains("actor")); assertFalse(receipt.toString().contains("candidate\""));
        var changed = new ArrayList<>(input.observations()); changed.set(0, with(changed.getFirst(), "verdict", INSUFFICIENT_EVIDENCE));
        mvc.perform(auth(post(BASE).contentType("application/json").content(mapper.writeValueAsString(with(input, "observations", changed))), "ok"))
                .andExpect(status().isConflict());
        mvc.perform(auth(post(BASE).contentType("application/json").content(mapper.writeValueAsString(input)), "another-subject"))
                .andExpect(status().isConflict());
        assertThrows(CatalogBootstrapReviewException.class, () -> service.record(input, new CuratorActor("http://localhost:8081", "another-curator",
                "123456789012345678", "987654321098765432", Instant.now())));
        mvc.perform(auth(get(BASE + "/" + input.reviewId()).param("expectedSha256", "0".repeat(64)), "ok")).andExpect(status().isConflict());
        mvc.perform(auth(get(BASE + "/" + UUID.randomUUID()).param("expectedSha256", digest), "ok")).andExpect(status().isNotFound());
        mvc.perform(auth(get(BASE + "/" + input.reviewId()), "ok")).andExpect(status().isBadRequest());
        mvc.perform(auth(get(BASE + "/" + input.reviewId()).param("expectedSha256", "A".repeat(64)), "ok")).andExpect(status().isBadRequest());
        mvc.perform(auth(get(BASE + "/not-a-uuid").param("expectedSha256", digest), "ok")).andExpect(status().isBadRequest());
        for (String flag : List.of("approvalGranted", "sourceVerificationPerformed", "catalogWritesPerformed", "factTrustChanged")) {
            var forged = (ObjectNode) receipt.deepCopy(); forged.put(flag, true); sample("bootstrap-forged-" + flag, "catalog-bootstrap-review", false, forged);
        }
        var disclosed = (ObjectNode) receipt.deepCopy(); disclosed.put("actorSubject", "private");
        sample("bootstrap-no-actor", "catalog-bootstrap-review", false, disclosed);
        registryEmpty();
    }

    @Order(3) @ParameterizedTest
    @ValueSource(strings = {"schema", "missing-confirmation", "proposal-confirmation", "unknown-field", "empty-observations", "too-many",
            "invalid-path", "invalid-verdict", "scalar-schema", "missing-candidate", "declared-review", "duplicate-json"})
    void actualStrictCoreMapperRejectsMalformedAndForgedBootstrapRequests(String change) throws Exception {
        var input = (ObjectNode) mapper.valueToTree(request());
        switch (change) {
            case "schema" -> input.put("schemaVersion", 2);
            case "scalar-schema" -> input.put("schemaVersion", "1");
            case "missing-confirmation" -> input.remove("confirmation");
            case "proposal-confirmation" -> input.put("confirmation", "MANUAL_SOURCE_REVIEW");
            case "unknown-field" -> input.put("approvalGranted", true);
            case "empty-observations" -> input.set("observations", mapper.createArrayNode());
            case "too-many" -> { var items = mapper.createArrayNode(); for (int i = 0; i < 6801; i++) items.add(input.at("/observations/0").deepCopy()); input.set("observations", items); }
            case "invalid-path" -> ((ObjectNode) input.at("/observations/0")).put("factPath", "invalid");
            case "invalid-verdict" -> ((ObjectNode) input.at("/observations/0")).put("verdict", "APPROVED");
            case "missing-candidate" -> input.remove("candidate");
            case "declared-review" -> ((ObjectNode) input.at("/candidate/options/0/facts/SCIM")).put("evidenceStatus", "REVIEWED");
            case "duplicate-json" -> { }
            default -> throw new AssertionError(change);
        }
        String body = mapper.writeValueAsString(input);
        if (change.equals("duplicate-json")) body = body.substring(0, body.length() - 1) + ",\"schemaVersion\":1}";
        else sample("bootstrap-malformed-" + change, "catalog-bootstrap-review-request", false, input);
        var response = mvc.perform(auth(post(BASE).contentType("application/json").content(body), "ok"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid-request")).andReturn();
        sample("bootstrap-invalid-problem-" + change, "core-problem", true, payload(response));
    }

    @Order(4) @ParameterizedTest
    @ValueSource(strings = {"hash", "missing-fact", "duplicate-fact", "foreign-fact", "invalid-candidate"})
    void structurallyValidButIncompleteOrTamperedReviewsAreRejectedWithoutStorage(String change) throws Exception {
        var input = request(); var items = new ArrayList<>(input.observations());
        switch (change) {
            case "hash" -> input = with(input, "expectedCandidateSha256", "0".repeat(64));
            case "missing-fact" -> { items.removeLast(); input = with(input, "observations", items); }
            case "duplicate-fact" -> { items.add(items.getFirst()); input = with(input, "observations", items); }
            case "foreign-fact" -> { items.set(0, with(items.getFirst(), "factPath", "facts.UNKNOWN")); input = with(input, "observations", items); }
            case "invalid-candidate" -> {
                var candidate = (ObjectNode) mapper.valueToTree(input.candidate());
                ((tools.jackson.databind.node.ArrayNode) candidate.get("options")).add(candidate.at("/options/0").deepCopy());
                var draft = mapper.treeToValue(candidate, ProviderCatalogDraft.class);
                input = with(with(input, "candidate", draft), "expectedCandidateSha256", CatalogDraftCanonicalizer.sha256(draft));
            }
            default -> throw new AssertionError(change);
        }
        sample("bootstrap-semantic-" + change, "catalog-bootstrap-review-request", true, mapper.valueToTree(input));
        mvc.perform(auth(post(BASE).contentType("application/json").content(mapper.writeValueAsString(input)), "ok"))
                .andExpect(status().isBadRequest()); assertEquals(0, reviews(input.reviewId())); assertEquals(0, events(input.reviewId()));
    }

    @Order(5) @Test
    void auditFailureRollsBackTheWholeReviewAndDatabaseRolesCannotMutateHistoryOrPublish() throws Exception {
        var input = request();
        assertThrows(org.springframework.dao.DataAccessException.class, () -> service.record(input,
                new CuratorActor("http://localhost:8081", "bootstrap-curator", "123456789012345678", "987654321098765432", Instant.now().minusSeconds(901))));
        assertEquals(0, reviews(input.reviewId())); assertEquals(0, events(input.reviewId()));
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "authweave_core_runtime", "core-test-password")) {
            connection.setAutoCommit(false); var runtime = DSL.using(connection, SQLDialect.POSTGRES); var r = CATALOG_BOOTSTRAP_REVIEWS;
            runtime.insertInto(r).set(r.ID, input.reviewId()).set(r.CANDIDATE_SHA256, input.expectedCandidateSha256())
                    .set(r.REVIEW_SHA256, CatalogDraftCanonicalizer.sha256(input)).set(r.REQUEST_SCHEMA_VERSION, (short) 1)
                    .set(r.POLICY_VERSION, CatalogBootstrapReviewService.POLICY_VERSION).set(r.CATALOG_VERSION, input.candidate().catalogVersion())
                    .set(r.FACT_COUNT, input.observations().size()).set(r.REQUEST, JSONB.jsonb(mapper.writeValueAsString(input))).execute();
            assertEquals("23503", assertThrows(java.sql.SQLException.class, connection::commit).getSQLState());
            connection.rollback();
        }
        assertEquals(0, reviews(input.reviewId())); assertEquals(0, events(input.reviewId()));
        for (String table : List.of("core.catalog_bootstrap_reviews", "audit.catalog_bootstrap_review_events")) {
            for (String privilege : List.of("UPDATE", "DELETE", "TRUNCATE")) assertEquals(false,
                    dsl.fetchValue("select has_table_privilege(current_user, ?, ?)", table, privilege));
            assertEquals(false, dsl.fetchValue("select has_column_privilege(current_user, ?, ?, 'INSERT')", table,
                    table.startsWith("core") ? "recorded_at" : "occurred_at"));
            assertEquals(false, dsl.fetchValue("select has_table_privilege('authweave_web_runtime', ?, 'SELECT')", table));
            assertEquals(false, dsl.fetchValue("select has_table_privilege('authweave_web_runtime', ?, 'INSERT')", table));
        }
        assertEquals(false, dsl.fetchValue("select has_table_privilege(current_user, 'core.catalog_publication_decisions', 'INSERT')"));
        registryEmpty();
    }

    @Order(6) @Test
    void exactStoredReviewFeedsPreflightButSupportingVerdictsDoNotRefreshEvidenceOrGrantPublication() {
        var input = request(); var created = service.record(input, actor()).review();
        var result = preflight.bootstrap(input.reviewId(), created.reviewSha256());
        assertTrue(result.facts().allFactsHaveSupportingObservation()); assertFalse(result.approvalGranted()); assertFalse(result.publicationReady());
        assertFalse(result.blockers().contains(CatalogPublicationPreflight.Blocker.BOOTSTRAP_REVIEW_WORKFLOW_UNAVAILABLE));
        var json = (ObjectNode) mapper.valueToTree(input.candidate());
        ((ObjectNode) json.at("/options/0/facts/SCIM/evidence")).put("observedAt", Instant.now().minusSeconds(91 * 86400L).toString());
        var draft = mapper.treeToValue(json, ProviderCatalogDraft.class);
        var stale = new CatalogBootstrapReviewRequest(1, UUID.randomUUID(), CatalogDraftCanonicalizer.sha256(draft), draft,
                input.observations(), input.confirmation());
        var staleReceipt = service.record(stale, actor()).review(); var blocked = preflight.bootstrap(stale.reviewId(), staleReceipt.reviewSha256());
        assertTrue(blocked.facts().allFactsHaveSupportingObservation()); assertTrue(blocked.blockers().contains(CatalogPublicationPreflight.Blocker.FACT_EVIDENCE_STALE));
        assertFalse(blocked.sourceVerificationPerformed()); assertFalse(blocked.writesPerformed());
        var stored = repository.find(input.reviewId());
        assertNull(repository.find(input.reviewId(), stored.requestBytes() - 1).request());
        assertNotNull(repository.find(input.reviewId(), stored.requestBytes()).request()); registryEmpty();
    }

    @Order(7) @Test
    void concurrentEquivalentSubmissionsCreateOneReviewAndAuditWhileTheBoundaryLockSerializesWriters() throws Exception {
        var input = request();
        try (var connection = admin()) {
            connection.setAutoCommit(false);
            DSL.using(connection, SQLDialect.POSTGRES).fetch("select pg_advisory_xact_lock(hashtextextended('authweave:catalog-bootstrap-boundary',0))");
            var started = new java.util.concurrent.CountDownLatch(2);
            var first = CompletableFuture.supplyAsync(() -> { started.countDown(); return service.record(input, actor()); });
            var second = CompletableFuture.supplyAsync(() -> { started.countDown(); return service.record(input, actor()); });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            // Both writers must wait on the actual DB lock, not a process-local mutex.
            assertThrows(java.util.concurrent.TimeoutException.class, () -> first.get(150, TimeUnit.MILLISECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> second.get(150, TimeUnit.MILLISECONDS));
            connection.rollback();
            var one = first.get(10, TimeUnit.SECONDS); var two = second.get(10, TimeUnit.SECONDS);
            assertNotEquals(one.created(), two.created()); assertEquals(one.review(), two.review());
        }
        assertEquals(1, reviews(input.reviewId())); assertEquals(1, events(input.reviewId())); registryEmpty();
    }

    @Order(8) @Test
    void consistentTopMetadataCannotHideTamperedStoredClaimsOrVerbatimSourceDisclosure() throws Exception {
        var input = request(); var receipt = service.record(input, actor()).review(); var stored = repository.find(input.reviewId());
        var json = (ObjectNode) mapper.readTree(stored.request()); ((ObjectNode) json.at("/observations/0")).put("verdict", "INSUFFICIENT_EVIDENCE");
        try (var connection = admin()) {
            var admin = DSL.using(connection, SQLDialect.POSTGRES);
            try {
                admin.update(CATALOG_BOOTSTRAP_REVIEWS).set(CATALOG_BOOTSTRAP_REVIEWS.REQUEST, JSONB.jsonb(mapper.writeValueAsString(json)))
                        .where(CATALOG_BOOTSTRAP_REVIEWS.ID.eq(input.reviewId())).execute();
                mvc.perform(auth(get(BASE + "/" + input.reviewId()).param("expectedSha256", receipt.reviewSha256()), "ok"))
                        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("catalog-bootstrap-review-unavailable"));
                var unavailable = preflight.bootstrap(input.reviewId(), receipt.reviewSha256());
                assertTrue(unavailable.blockers().contains(CatalogPublicationPreflight.Blocker.BOOTSTRAP_REVIEW_UNAVAILABLE));
                assertEquals(0, unavailable.facts().total());
            } finally {
                admin.update(CATALOG_BOOTSTRAP_REVIEWS).set(CATALOG_BOOTSTRAP_REVIEWS.REQUEST, JSONB.jsonb(stored.request()))
                        .where(CATALOG_BOOTSTRAP_REVIEWS.ID.eq(input.reviewId())).execute();
            }
        }
        assertEquals(receipt, service.get(input.reviewId(), receipt.reviewSha256()));
    }

    @Order(99) @Test
    void committedPublicationClosesNewReviewsButNotFreshActorBoundHistoricalRetries() throws Exception {
        var input = request(); var receipt = service.record(input, actor()).review();
        var root = new CatalogPublicationLookupFixtures(mapper).root(Instant.now());
        try (var connection = admin()) {
            connection.setAutoCommit(false); var admin = DSL.using(connection, SQLDialect.POSTGRES);
            var s = root.row().snapshot(); var d = root.row().decision(); var e = root.row().event();
            var decisions = CATALOG_PUBLICATION_DECISIONS; var snapshots = CATALOG_PUBLISHED_SNAPSHOTS; var events = CATALOG_PUBLICATION_EVENTS;
            admin.insertInto(decisions).set(decisions.ID, d.id()).set(decisions.SNAPSHOT_ID, d.snapshotId()).set(decisions.DECISION_KIND, d.kind())
                    .set(decisions.CATALOG_VERSION, d.catalogVersion()).set(decisions.CONTENT_SHA256, d.contentSha256())
                    .set(decisions.SNAPSHOT_SHA256, d.snapshotSha256()).set(decisions.PUBLISHED_AT, OffsetDateTime.ofInstant(d.publishedAt(), ZoneOffset.UTC)).execute();
            admin.insertInto(snapshots).set(snapshots.ID, s.id()).set(snapshots.DECISION_ID, s.decisionId()).set(snapshots.DECISION_KIND, s.decisionKind())
                    .set(snapshots.CATALOG_VERSION, s.catalogVersion()).set(snapshots.CONTENT_SHA256, s.contentSha256()).set(snapshots.SNAPSHOT_SHA256, s.snapshotSha256())
                    .set(snapshots.PUBLISHED_AT, OffsetDateTime.ofInstant(s.publishedAt(), ZoneOffset.UTC)).set(snapshots.MANIFEST, JSONB.jsonb(s.manifest())).execute();
            admin.insertInto(events).set(events.ID, e.id()).set(events.DECISION_ID, e.decisionId()).set(events.SNAPSHOT_ID, e.snapshotId())
                    .set(events.SNAPSHOT_SHA256, e.snapshotSha256()).set(events.DECISION_KIND, e.decisionKind()).set(events.ACTION, e.action())
                    .set(events.ACTOR_TYPE, e.actorType()).set(events.ACTOR_ISSUER, e.issuer()).set(events.ACTOR_SUBJECT, e.subject())
                    .set(events.ACTOR_PROJECT_ID, e.projectId()).set(events.ACTOR_ORG_ID, e.orgId()).set(events.AUTHENTICATED_AT, OffsetDateTime.ofInstant(e.authenticatedAt(), ZoneOffset.UTC))
                    .set(events.CORRELATION_ID, e.correlationId()).set(events.OUTCOME, e.outcome()).execute(); connection.commit();
        }
        var next = request(); mvc.perform(auth(post(BASE).contentType("application/json").content(mapper.writeValueAsString(next)), "ok"))
                .andExpect(status().isConflict()); assertEquals(0, reviews(next.reviewId()));
        var retry = mvc.perform(auth(post(BASE).contentType("application/json").content(mapper.writeValueAsString(input)), "ok"))
                .andExpect(status().isOk()).andReturn(); assertEquals(mapper.valueToTree(receipt), payload(retry));
        var result = preflight.bootstrap(input.reviewId(), receipt.reviewSha256());
        assertTrue(result.blockers().contains(CatalogPublicationPreflight.Blocker.BOOTSTRAP_REGISTRY_NOT_EMPTY)); assertFalse(result.publicationReady());
        // Even direct runtime INSERT cannot bypass the DB empty-registry gate.
        var r = CATALOG_BOOTSTRAP_REVIEWS;
        assertThrows(org.springframework.dao.DataAccessException.class, () -> dsl.insertInto(r).set(r.ID, next.reviewId()).set(r.CANDIDATE_SHA256, next.expectedCandidateSha256())
                .set(r.REVIEW_SHA256, CatalogDraftCanonicalizer.sha256(next)).set(r.REQUEST_SCHEMA_VERSION, (short) 1).set(r.POLICY_VERSION, CatalogBootstrapReviewService.POLICY_VERSION)
                .set(r.CATALOG_VERSION, next.candidate().catalogVersion()).set(r.FACT_COUNT, next.observations().size()).set(r.REQUEST, JSONB.jsonb(mapper.writeValueAsString(next))).execute());
    }

    private CatalogBootstrapReviewRequest request() {
        var draft = new CatalogPublicationLookupFixtures(mapper).root(Instant.now()).snapshot().catalog().asDraft();
        var observations = drafts.validate(draft).facts().stream().map(f -> new CatalogBootstrapReviewRequest.Observation(f.optionId(), f.path(), SOURCE_SUPPORTS_CLAIM)).toList();
        return new CatalogBootstrapReviewRequest(1, UUID.randomUUID(), CatalogDraftCanonicalizer.sha256(draft), draft, observations,
                CatalogBootstrapReviewRequest.Confirmation.MANUAL_BOOTSTRAP_SOURCE_REVIEW);
    }
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String change) {
        if (!change.equals("no-credential")) request.header("Authorization", change.equals("bad-credential") ? "Bearer invalid" : TOKEN);
        if (change.equals("duplicate-credential")) request.header("Authorization", TOKEN);
        if (!change.equals("no-issuer")) request.header("X-AuthWeave-Oidc-Issuer", "http://localhost:8081");
        if (!change.equals("no-subject")) request.header("X-AuthWeave-Oidc-Subject", change.equals("another-subject") ? "another-curator" : "bootstrap-curator");
        if (change.equals("duplicate-subject")) request.header("X-AuthWeave-Oidc-Subject", "bootstrap-curator");
        request.header("X-AuthWeave-Curator-Role", change.equals("role") ? "viewer" : "catalog_curator");
        if (change.equals("duplicate-role")) request.header("X-AuthWeave-Curator-Role", "catalog_curator");
        request.header("X-AuthWeave-Curator-Project-Id", change.equals("project") ? "0" : "123456789012345678");
        request.header("X-AuthWeave-Curator-Org-Id", change.equals("organization") ? "0" : "987654321098765432");
        request.header("X-AuthWeave-Authenticated-At", change.equals("invalid-time") ? "invalid" : Instant.now()
                .plusSeconds(change.equals("stale") ? -901 : change.equals("future") ? 31 : 0).toString()); return request;
    }
    private CuratorActor actor() { return new CuratorActor("http://localhost:8081", "bootstrap-curator", "123456789012345678", "987654321098765432", Instant.now()); }
    private int reviews(UUID id) { return dsl.fetchCount(CATALOG_BOOTSTRAP_REVIEWS, CATALOG_BOOTSTRAP_REVIEWS.ID.eq(id)); }
    private int events(UUID id) { return dsl.fetchCount(CATALOG_BOOTSTRAP_REVIEW_EVENTS, CATALOG_BOOTSTRAP_REVIEW_EVENTS.REVIEW_ID.eq(id)); }
    private void registryEmpty() { assertEquals(0, dsl.fetchCount(CATALOG_PUBLICATION_DECISIONS)); assertEquals(0, dsl.fetchCount(CATALOG_PUBLISHED_SNAPSHOTS)); assertEquals(0, dsl.fetchCount(CATALOG_PUBLICATION_EVENTS)); }
    private static Connection admin() throws Exception { return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); }
    private JsonNode payload(MvcResult response) throws Exception { return mapper.readTree(response.getResponse().getContentAsString()); }
    private void sample(String name, String schema, boolean valid, JsonNode payload) { samples.add(new Sample(name, schema, valid, payload.deepCopy())); }
    private record Sample(String name, String schema, boolean valid, JsonNode payload) { }
}

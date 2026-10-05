package io.authweave.core.catalog.auditability;

import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.PostgresIntegrationTest;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;
import static io.authweave.core.catalog.auditability.CatalogAuditabilityReviewFixtures.*;
import static io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict.*;
import static io.authweave.core.generated.jooq.tables.CatalogAuditabilityReviews.CATALOG_AUDITABILITY_REVIEWS;
import static io.authweave.core.generated.audit.tables.CatalogAuditabilityReviewEvents.CATALOG_AUDITABILITY_REVIEW_EVENTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"AUTHWEAVE_CORE_SERVICE_TOKEN=synthetic-auditability-service-token-00000000000000",
        "AUTHWEAVE_OIDC_PROJECT_ID=123456789012345678", "AUTHWEAVE_OIDC_ORG_ID=987654321098765432"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogAuditabilityReviewIntegrationTests extends PostgresIntegrationTest {
    private static final String BASE = "/internal/v1/catalog-curator/auditability-reviews";
    private static final String IMPACT = "/internal/v1/catalog-curator/auditability-impact/preview";
    private static final String COVERAGE = "/internal/v1/catalog-curator/auditability-impact/coverage-preview";
    private static final String TOKEN = "Bearer synthetic-auditability-service-token-00000000000000";
    private static final Path SAMPLES = Path.of("target", "auditability-review-http-contract-samples.json");
    private final List<Sample> samples = new ArrayList<>();
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private DSLContext dsl;
    @Autowired private CatalogAuditabilityDraftValidator drafts;
    @Autowired private CatalogAuditabilityReviewService service;
    @Autowired private CatalogAuditabilityReviewRepository repository;
    @BeforeAll void clearSamples() throws Exception { Files.deleteIfExists(SAMPLES); }
    @AfterAll void exportSamples() throws Exception { Files.createDirectories(SAMPLES.getParent()); Files.writeString(SAMPLES, mapper.writeValueAsString(samples)); }

    @ParameterizedTest
    @ValueSource(strings = {"no-credential", "bad-credential", "duplicate-credential", "no-subject", "no-issuer", "duplicate-subject", "role", "duplicate-role",
            "project", "organization", "stale", "future", "invalid-time"})
    void freshSingularScopedCuratorAssertionsProtectReadsWritesAndUnknownVersions(String guard) throws Exception {
        var request = request(mapper, drafts); var before = counts(request.reviewId());
        int expected = List.of("no-credential", "bad-credential", "duplicate-credential", "no-subject", "no-issuer", "duplicate-subject").contains(guard) ? 401 : 403;
        for (String path : List.of(BASE, BASE.replace("/v1/", "/v2/"))) {
            mvc.perform(auth(post(path).contentType("application/json").content(mapper.writeValueAsString(request)), guard)).andExpect(status().is(expected));
            mvc.perform(auth(get(path + "/" + request.reviewId()).param("expectedSha256", "0".repeat(64)), guard)).andExpect(status().is(expected));
        }
        for (String path : List.of(IMPACT, IMPACT.replace("/v1/", "/v2/"), COVERAGE, COVERAGE.replace("/v1/", "/v2/")))
            mvc.perform(auth(post(path).contentType("application/json").content("{}"), guard)).andExpect(status().is(expected));
        assertEquals(before, counts(request.reviewId()));
    }

    @Test void conditionalImpactUsesStoredExactReviewsAndChangesNoStateOrAuthority() throws Exception {
        var before = impactCandidate(180); var after = impactCandidate(30);
        var b = service.record(before, actor()).review(); var a = service.record(after, actor()).review();
        sample("auditability-impact-before-source", "catalog-auditability-review-request", true, mapper.valueToTree(before));
        sample("auditability-impact-after-source", "catalog-auditability-review-request", true, mapper.valueToTree(after));
        var input = new CatalogAuditabilityImpactService.Request(1, b.reviewId(), b.reviewSha256(), a.reviewId(), a.reviewSha256());
        sample("auditability-impact-request", "catalog-auditability-impact-request", true, mapper.valueToTree(input));
        var unrelated = unrelatedCounts();
        var response = mvc.perform(auth(post(IMPACT).contentType("application/json").content(mapper.writeValueAsString(input)), "ok"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.checkedCases").value(4)).andExpect(jsonPath("$.checkedCriteria").value(24))
                .andExpect(jsonPath("$.changedFacts").value(1)).andExpect(jsonPath("$.changedChecks").value(3)).andReturn();
        var report = payload(response); sample("auditability-impact-conditional", "catalog-auditability-impact", true, report);
        var coverageResponse = mvc.perform(auth(post(COVERAGE).contentType("application/json").content(mapper.writeValueAsString(input)), "ok"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.checkedAuditabilityDimensions").value(12)).andExpect(jsonPath("$.structuralOnlyDimensions.length()").value(124))
                .andExpect(jsonPath("$.candidateAuditabilityChangesEvaluated").value(true)).andExpect(jsonPath("$.coverageComplete").value(false)).andReturn();
        var coverage = payload(coverageResponse); sample("auditability-impact-coverage", "catalog-auditability-impact-coverage", true, coverage);
        assertEquals(unrelated, unrelatedCounts()); assertEquals(List.of(1, 1), counts(b.reviewId())); assertEquals(List.of(1, 1), counts(a.reviewId()));
        for (String flag : List.of("coverageComplete", "baselineVerified", "sourceVerificationPerformed", "factTrustChanged", "configurationVerified",
                "complianceVerified", "storedReportVerified", "approvalGranted", "writesPerformed", "publicationReady", "evaluationReady", "recommendationReady")) {
            assertFalse(report.get(flag).asBoolean()); var forged = (ObjectNode) report.deepCopy(); forged.put(flag, true);
            sample("auditability-impact-no-" + flag, "catalog-auditability-impact", false, forged);
            var forgedCoverage = (ObjectNode) coverage.deepCopy(); forgedCoverage.put(flag, true);
            sample("auditability-coverage-no-" + flag, "catalog-auditability-impact-coverage", false, forgedCoverage);
        }
        for (String key : List.of("actorSubject", "candidate", "sourceUrl")) {
            var disclosed = (ObjectNode) report.deepCopy(); disclosed.put(key, "private"); sample("auditability-impact-no-" + key, "catalog-auditability-impact", false, disclosed);
            assertFalse(report.toString().contains("\"" + key + "\":"));
            var disclosedCoverage = (ObjectNode) coverage.deepCopy(); disclosedCoverage.put(key, "private");
            sample("auditability-coverage-no-" + key, "catalog-auditability-impact-coverage", false, disclosedCoverage);
            assertFalse(coverage.toString().contains("\"" + key + "\":"));
        }
        var missingDimension = (ObjectNode) coverage.deepCopy(); ((tools.jackson.databind.node.ArrayNode) missingDimension.get("dimensions")).remove(0);
        sample("auditability-coverage-missing-dimension", "catalog-auditability-impact-coverage", false, missingDimension);
        var wrongManifest = (ObjectNode) coverage.deepCopy(); wrongManifest.put("manifestSha256", "0".repeat(64));
        sample("auditability-coverage-foreign-manifest", "catalog-auditability-impact-coverage", false, wrongManifest);
        for (String path : List.of(IMPACT, COVERAGE)) mvc.perform(auth(post(path).queryParam("extra", "1").contentType("application/json").content(mapper.writeValueAsString(input)), "ok"))
                .andExpect(status().isBadRequest());
        for (String side : List.of("before", "after")) {
            var wrong = side.equals("before") ? with(input, "expectedBeforeReviewSha256", "0".repeat(64)) : with(input, "expectedAfterReviewSha256", "0".repeat(64));
            for (String path : List.of(IMPACT, COVERAGE)) mvc.perform(auth(post(path).contentType("application/json").content(mapper.writeValueAsString(wrong)), "ok"))
                    .andExpect(status().isConflict()).andExpect(header().string("Cache-Control", "no-store"));
            var missing = side.equals("before") ? with(input, "beforeReviewId", UUID.randomUUID()) : with(input, "afterReviewId", UUID.randomUUID());
            for (String path : List.of(IMPACT, COVERAGE)) mvc.perform(auth(post(path).contentType("application/json").content(mapper.writeValueAsString(missing)), "ok"))
                    .andExpect(status().isNotFound());
        }
        var same = with(with(input, "afterReviewId", b.reviewId()), "expectedAfterReviewSha256", b.reviewSha256());
        var identity = mvc.perform(auth(post(IMPACT).contentType("application/json").content(mapper.writeValueAsString(same)), "ok"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changedFacts").value(0)).andExpect(jsonPath("$.changedChecks").value(0)).andReturn();
        sample("auditability-impact-identical", "catalog-auditability-impact", true, payload(identity));
        var identicalCoverage = mvc.perform(auth(post(COVERAGE).contentType("application/json").content(mapper.writeValueAsString(same)), "ok"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidateImpact.changedChecks").value(0)).andReturn();
        sample("auditability-impact-coverage-identical", "catalog-auditability-impact-coverage", true, payload(identicalCoverage));
        var changedBase = with(after.candidate().baseDraft(), "catalogVersion", "different-impact-base");
        var supplement = with(with(after.candidate().auditabilityDraft(), "baseCatalogVersion", changedBase.catalogVersion()), "baseContentSha256", CatalogDraftCanonicalizer.sha256(changedBase));
        var other = service.record(bind(new CatalogAuditabilityDraftValidator.Request(changedBase, supplement), drafts), actor()).review();
        var mixed = with(with(input, "afterReviewId", other.reviewId()), "expectedAfterReviewSha256", other.reviewSha256());
        for (String path : List.of(IMPACT, COVERAGE)) mvc.perform(auth(post(path).contentType("application/json").content(mapper.writeValueAsString(mixed)), "ok")).andExpect(status().isConflict());
    }

    @ParameterizedTest @ValueSource(strings = {"version", "version-text", "missing", "digest", "uuid", "candidate", "clock", "approval", "duplicate-json", "trailing-json"})
    void impactReferencesRejectSuppliedBodiesTimeAuthorityAndMalformedJson(String variant) throws Exception {
        var input = (ObjectNode) mapper.valueToTree(new CatalogAuditabilityImpactService.Request(1, UUID.randomUUID(), "0".repeat(64), UUID.randomUUID(), "1".repeat(64)));
        switch (variant) {
            case "version" -> input.put("schemaVersion", 2); case "version-text" -> input.put("schemaVersion", "1");
            case "missing" -> input.remove("afterReviewId"); case "digest" -> input.put("expectedBeforeReviewSha256", "bad");
            case "uuid" -> input.put("beforeReviewId", "invalid"); case "candidate" -> input.putObject("candidate");
            case "clock" -> input.put("evaluatedAt", Instant.now().toString()); case "approval" -> input.put("approvalGranted", true);
            default -> { }
        }
        String body = mapper.writeValueAsString(input);
        if (variant.equals("duplicate-json")) body = body.substring(0, body.length() - 1) + ",\"schemaVersion\":1}";
        else if (variant.equals("trailing-json")) body += "{}";
        else sample("auditability-impact-malformed-" + variant, "catalog-auditability-impact-request", false, input);
        for (String path : List.of(IMPACT, COVERAGE)) mvc.perform(auth(post(path).contentType("application/json").content(body), "ok")).andExpect(status().isBadRequest());
    }

    private CatalogAuditabilityReviewRequest impactCandidate(int retention) throws Exception {
        var original = request(mapper, drafts); var option = original.candidate().auditabilityDraft().options().getFirst();
        var facts = option.facts().stream().map(f -> with(with(f, "conditions", List.of()), "documentedMinimumRetentionDays",
                f.criterion() == io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion.AUDIT_LOG_RETENTION ? retention : null)).toList();
        return bind(with(original.candidate(), "auditabilityDraft", with(original.candidate().auditabilityDraft(), "options", List.of(with(option, "facts", facts)))), drafts);
    }

    @Test void appendOnlyActorBoundRetryAndExactReadsReturnBodyFreeNonAuthoritativeReceipts() throws Exception {
        var request = request(mapper, drafts); sample("auditability-review-valid-request", "catalog-auditability-review-request", true, mapper.valueToTree(request));
        var unrelated = unrelatedCounts();
        var created = mvc.perform(auth(post(BASE).contentType("application/json").content(mapper.writeValueAsString(request)), "ok"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.factCount").value(2)).andReturn();
        var receipt = payload(created); sample("auditability-review-created", "catalog-auditability-review", true, receipt);
        var reordered = with(request, "observations", request.observations().reversed());
        assertEquals(receipt, payload(mvc.perform(auth(post(BASE).contentType("application/json").content(mapper.writeValueAsString(reordered)), "ok"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn()));
        var read = mvc.perform(auth(get(BASE + "/" + request.reviewId()).param("expectedSha256", receipt.get("reviewSha256").asText()), "ok"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        assertEquals(receipt, payload(read)); sample("auditability-review-read", "catalog-auditability-review", true, payload(read));
        assertEquals(List.of(1, 1), counts(request.reviewId()));
        assertEquals(unrelated, unrelatedCounts());
        for (String flag : List.of("sourceVerificationPerformed", "factTrustChanged", "candidateImpactPerformed", "approvalGranted", "catalogWritesPerformed", "publicationReady", "evaluationReady", "recommendationReady")) {
            assertFalse(receipt.get(flag).asBoolean()); var forged = (ObjectNode) receipt.deepCopy(); forged.put(flag, true); sample("auditability-review-no-" + flag, "catalog-auditability-review", false, forged);
        }
        for (String key : List.of("actorSubject", "candidate", "sourceUrl")) {
            var disclosed = (ObjectNode) receipt.deepCopy(); disclosed.put(key, "private"); sample("auditability-review-no-" + key, "catalog-auditability-review", false, disclosed);
            assertFalse(receipt.has(key));
        }
        mvc.perform(auth(post(BASE).contentType("application/json").content(mapper.writeValueAsString(request)), "stale")).andExpect(status().isForbidden());
        var changed = new ArrayList<>(request.observations()); changed.set(0, with(changed.getFirst(), "verdict", INSUFFICIENT_EVIDENCE));
        for (var action : List.of(auth(post(BASE).contentType("application/json").content(mapper.writeValueAsString(with(request, "observations", changed))), "ok"),
                auth(post(BASE).contentType("application/json").content(mapper.writeValueAsString(request)), "another-subject"),
                auth(get(BASE + "/" + request.reviewId()).param("expectedSha256", "0".repeat(64)), "ok"))) {
            var result = mvc.perform(action).andExpect(status().isConflict()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
            sample("auditability-review-conflict", "catalog-auditability-review-problem", true, payload(result));
        }
        assertEquals(List.of(1, 1), counts(request.reviewId()));
        var missing = mvc.perform(auth(get(BASE + "/" + UUID.randomUUID()).param("expectedSha256", receipt.get("reviewSha256").asText()), "ok"))
                .andExpect(status().isNotFound()).andReturn(); sample("auditability-review-missing", "catalog-auditability-review-problem", true, payload(missing));
        var badProblem = (ObjectNode) payload(missing).deepCopy(); badProblem.put("actor", "private"); sample("auditability-review-problem-no-actor", "catalog-auditability-review-problem", false, badProblem);
        var stored = repository.find(request.reviewId());
        assertNull(repository.find(request.reviewId(), stored.requestBytes() - 1).request());
        assertNotNull(repository.find(request.reviewId(), stored.requestBytes()).request());
        var draft = drafts.validate(request.candidate()); draft.targets().forEach(t -> assertEquals("UNREVIEWED", t.evidenceStatus()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"schema", "schema-text", "missing-confirmation", "wrong-confirmation", "empty", "too-many", "actor", "approval", "criterion", "verdict", "target-hash", "missing-candidate", "declared-trust", "duplicate-json", "trailing-json"})
    void strictMapperRejectsMalformedAndForgedManualAssertions(String change) throws Exception {
        var request = request(mapper, drafts); var input = (ObjectNode) mapper.valueToTree(request);
        switch (change) {
            case "schema" -> input.put("schemaVersion", 2); case "schema-text" -> input.put("schemaVersion", "1");
            case "missing-confirmation" -> input.remove("confirmation"); case "wrong-confirmation" -> input.put("confirmation", "MANUAL_BOOTSTRAP_SOURCE_REVIEW");
            case "empty" -> input.putArray("observations");
            case "too-many" -> { var items = input.putArray("observations"); for (int i = 0; i < 601; i++) items.add(mapper.valueToTree(request.observations().getFirst())); }
            case "actor" -> input.put("actorSubject", "supplied"); case "approval" -> input.put("approvalGranted", true);
            case "criterion" -> ((ObjectNode) input.at("/observations/0")).put("criterion", "facts.SCIM");
            case "verdict" -> ((ObjectNode) input.at("/observations/0")).put("verdict", "APPROVED");
            case "target-hash" -> ((ObjectNode) input.at("/observations/0")).put("expectedTargetSha256", "bad");
            case "missing-candidate" -> input.remove("candidate");
            case "declared-trust" -> ((ObjectNode) input.at("/candidate/auditabilityDraft/options/0/facts/0")).put("evidenceStatus", "REVIEWED");
            case "duplicate-json", "trailing-json" -> { } default -> throw new AssertionError(change);
        }
        String body = mapper.writeValueAsString(input);
        if (change.equals("duplicate-json")) body = body.substring(0, body.length() - 1) + ",\"schemaVersion\":1}";
        else if (change.equals("trailing-json")) body += "{}";
        else sample("auditability-review-malformed-" + change, "catalog-auditability-review-request", false, input);
        var result = mvc.perform(auth(post(BASE).contentType("application/json").content(body), "ok")).andExpect(status().isBadRequest()).andReturn();
        sample("auditability-review-malformed-problem", "catalog-auditability-review-problem", true, payload(result)); assertEquals(List.of(0, 0), counts(request.reviewId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"base-hash", "supplement-hash", "set-hash", "target-hash", "missing", "foreign-option", "criterion", "changed-claim"})
    void shapedButUnboundObservationsCannotBeStored(String change) throws Exception {
        var request = request(mapper, drafts); var input = (ObjectNode) mapper.valueToTree(request);
        switch (change) {
            case "base-hash" -> input.put("expectedBaseContentSha256", "0".repeat(64));
            case "supplement-hash" -> input.put("expectedAuditabilityContentSha256", "0".repeat(64));
            case "set-hash" -> input.put("expectedTargetSetSha256", "0".repeat(64));
            case "target-hash" -> ((ObjectNode) input.at("/observations/0")).put("expectedTargetSha256", "0".repeat(64));
            case "missing" -> ((tools.jackson.databind.node.ArrayNode) input.get("observations")).remove(0);
            case "foreign-option" -> ((ObjectNode) input.at("/observations/0")).put("optionId", "foreign");
            case "criterion" -> ((ObjectNode) input.at("/observations/0")).put("criterion", "AUDIT_LOG_EXPORT");
            case "changed-claim" -> ((ObjectNode) input.at("/candidate/auditabilityDraft/options/0/facts/0/evidence")).put("summary", "Changed paraphrase");
            default -> throw new AssertionError(change);
        }
        sample("auditability-review-unbound-" + change, "catalog-auditability-review-request", true, input);
        mvc.perform(auth(post(BASE).contentType("application/json").content(mapper.writeValueAsString(input)), "ok")).andExpect(status().isBadRequest());
        assertEquals(List.of(0, 0), counts(request.reviewId()));
    }

    @ParameterizedTest @ValueSource(strings = {"STALE", "FUTURE", "MIXED"})
    void actualManualReceiptsPreserveEvidenceDatesAndNonSupportingVerdictsWithoutTrustPromotion(String scenario) throws Exception {
        var request = request(mapper, drafts); var candidate = (ObjectNode) mapper.valueToTree(request.candidate());
        String observed = scenario.equals("STALE") ? "2025-01-01T00:00:00Z" : "2027-01-01T00:00:00Z";
        if (!scenario.equals("MIXED")) {
            ((ObjectNode) candidate.at("/auditabilityDraft/options/0/facts/0/evidence")).put("observedAt", observed);
            request = bind(mapper.treeToValue(candidate, CatalogAuditabilityDraftValidator.Request.class), drafts);
        } else {
            var items = new ArrayList<>(request.observations());
            items.set(0, with(items.get(0), "verdict", SOURCE_DOES_NOT_SUPPORT_CLAIM));
            items.set(1, with(items.get(1), "verdict", INSUFFICIENT_EVIDENCE)); request = with(request, "observations", items);
        }
        sample("auditability-review-date-request-" + scenario, "catalog-auditability-review-request", true, mapper.valueToTree(request));
        var result = mvc.perform(auth(post(BASE).contentType("application/json").content(mapper.writeValueAsString(request)), "ok"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        var receipt = payload(result); sample("auditability-review-date-receipt-" + scenario, "catalog-auditability-review", true, receipt);
        assertEquals(scenario.equals("MIXED") ? 0 : 2, receipt.at("/counts/supporting").asInt());
        assertEquals(scenario.equals("MIXED") ? 1 : 0, receipt.at("/counts/contradicting").asInt());
        assertEquals(scenario.equals("MIXED") ? 1 : 0, receipt.at("/counts/insufficient").asInt());
        var stored = repository.find(request.reviewId());
        var replayed = drafts.validate(mapper.readValue(stored.request(), CatalogAuditabilityReviewRequest.class).candidate());
        assertEquals("UNREVIEWED", replayed.targets().getFirst().evidenceStatus());
        if (!scenario.equals("MIXED")) {
            assertEquals(scenario, replayed.targets().getFirst().freshness().name());
            assertEquals(Instant.parse(observed), replayed.targets().getFirst().fact().evidence().observedAt());
        }
        assertFalse(receipt.get("factTrustChanged").asBoolean()); assertFalse(receipt.get("publicationReady").asBoolean());
    }

    @Test void queryOrBodyAmbiguityCannotExpandTheProtectedReceiptReadOrWrite() throws Exception {
        var request = request(mapper, drafts);
        mvc.perform(auth(post(BASE).queryParam("approve", "true").contentType("application/json").content(mapper.writeValueAsString(request)), "ok")).andExpect(status().isBadRequest());
        String path = BASE + "/" + request.reviewId();
        for (var action : List.of(get(path), get(path).param("expectedSha256", "bad"), get(path).param("expectedSha256", "0".repeat(64), "0".repeat(64)),
                get(path).param("expectedSha256", "0".repeat(64)).param("extra", "true"), get(path).param("expectedSha256", "0".repeat(64)).content("{}"),
                get(path).param("expectedSha256", "0".repeat(64)).header("Transfer-Encoding", "chunked")))
            mvc.perform(auth(action, "ok")).andExpect(status().isBadRequest());
        assertEquals(List.of(0, 0), counts(request.reviewId()));
    }

    @Test void failedAuditRollsBackAndRuntimeRolesCannotMutateHistoryForgeTimestampsOrPublish() throws Exception {
        var request = request(mapper, drafts);
        assertThrows(org.springframework.dao.DataAccessException.class, () -> service.record(request,
                new CuratorActor("http://localhost:8081", "synthetic-auditability-curator", "123456789012345678", "987654321098765432", Instant.now().minusSeconds(901))));
        assertEquals(List.of(0, 0), counts(request.reviewId()));
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "authweave_core_runtime", CORE_RUNTIME_PASSWORD)) {
            connection.setAutoCommit(false); var runtime = DSL.using(connection, SQLDialect.POSTGRES); var r = CATALOG_AUDITABILITY_REVIEWS;
            runtime.insertInto(r).set(r.ID, request.reviewId()).set(r.BASE_CONTENT_SHA256, request.expectedBaseContentSha256())
                    .set(r.AUDITABILITY_CONTENT_SHA256, request.expectedAuditabilityContentSha256()).set(r.TARGET_SET_SHA256, request.expectedTargetSetSha256())
                    .set(r.REVIEW_SHA256, CatalogDraftCanonicalizer.sha256(request)).set(r.REQUEST_SCHEMA_VERSION, (short) 1).set(r.POLICY_VERSION, CatalogAuditabilityReviewService.POLICY_VERSION)
                    .set(r.CATALOG_VERSION, request.candidate().baseDraft().catalogVersion()).set(r.EVIDENCE_VERSION, request.candidate().auditabilityDraft().evidenceVersion())
                    .set(r.OPTION_COUNT, 1).set(r.FACT_COUNT, 2).set(r.REQUEST, JSONB.jsonb(mapper.writeValueAsString(request))).execute();
            assertEquals("23503", assertThrows(java.sql.SQLException.class, connection::commit).getSQLState()); connection.rollback();
        }
        for (String table : List.of("core.catalog_auditability_reviews", "audit.catalog_auditability_review_events")) {
            for (String privilege : List.of("UPDATE", "DELETE", "TRUNCATE")) assertEquals(false, dsl.fetchValue("select has_table_privilege(current_user, ?, ?)", table, privilege));
            assertEquals(false, dsl.fetchValue("select has_column_privilege(current_user, ?, ?, 'INSERT')", table, table.startsWith("core") ? "recorded_at" : "occurred_at"));
            for (String privilege : List.of("SELECT", "INSERT")) assertEquals(false, dsl.fetchValue("select has_table_privilege('authweave_web_runtime', ?, ?)", table, privilege));
        }
        assertEquals(false, dsl.fetchValue("select has_table_privilege(current_user, 'core.catalog_publication_decisions', 'INSERT')"));
        assertEquals(List.of(0, 0), counts(request.reviewId()));
    }

    @Test void concurrentEquivalentSavesAppendExactlyOneReviewAndOneAudit() throws Exception {
        var request = request(mapper, drafts);
        var first = CompletableFuture.supplyAsync(() -> service.record(request, actor()));
        var second = CompletableFuture.supplyAsync(() -> service.record(request, actor()));
        var a = first.get(10, TimeUnit.SECONDS); var b = second.get(10, TimeUnit.SECONDS);
        assertEquals(a.review(), b.review()); assertNotEquals(a.created(), b.created()); assertEquals(List.of(1, 1), counts(request.reviewId()));
    }

    @Test void conflictingConcurrentContentUnderOneKeyCannotAppendTwoDifferentObservations() throws Exception {
        var request = request(mapper, drafts); var items = new ArrayList<>(request.observations());
        items.set(0, with(items.get(0), "verdict", INSUFFICIENT_EVIDENCE)); var changed = with(request, "observations", items);
        java.util.function.Function<CatalogAuditabilityReviewRequest, Object> attempt = input -> {
            try { return service.record(input, actor()); } catch (CatalogAuditabilityReviewException conflict) { return conflict.reason(); }
        };
        var first = CompletableFuture.supplyAsync(() -> attempt.apply(request)); var second = CompletableFuture.supplyAsync(() -> attempt.apply(changed));
        var a = first.get(10, TimeUnit.SECONDS); var b = second.get(10, TimeUnit.SECONDS);
        assertEquals(1, java.util.stream.Stream.of(a, b).filter(r -> r instanceof CatalogAuditabilityReviewService.Result).count());
        assertEquals(1, java.util.stream.Stream.of(a, b).filter(r -> r == CatalogAuditabilityReviewException.Reason.CONFLICT).count());
        assertEquals(List.of(1, 1), counts(request.reviewId()));
    }

    @Test void actualStoredClaimCorruptionFailsClosedInsteadOfReturningARecomputedReceipt() throws Exception {
        var request = request(mapper, drafts); var receipt = service.record(request, actor()).review(); var original = repository.find(request.reviewId());
        var corrupted = (ObjectNode) mapper.readTree(original.request());
        ((ObjectNode) corrupted.at("/candidate/auditabilityDraft/options/0/facts/0/evidence")).put("summary", "Corrupted stored claim");
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            var admin = DSL.using(connection, SQLDialect.POSTGRES); var r = CATALOG_AUDITABILITY_REVIEWS;
            try {
                admin.update(r).set(r.REQUEST, JSONB.jsonb(mapper.writeValueAsString(corrupted))).where(r.ID.eq(request.reviewId())).execute();
                var denied = mvc.perform(auth(get(BASE + "/" + request.reviewId()).param("expectedSha256", receipt.reviewSha256()), "ok"))
                        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("catalog-auditability-review-unavailable")).andReturn();
                sample("auditability-review-corrupt", "catalog-auditability-review-problem", true, payload(denied));
                var impact = new CatalogAuditabilityImpactService.Request(1, receipt.reviewId(), receipt.reviewSha256(), receipt.reviewId(), receipt.reviewSha256());
                for (String path : List.of(IMPACT, COVERAGE)) mvc.perform(auth(post(path).contentType("application/json").content(mapper.writeValueAsString(impact)), "ok"))
                        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("catalog-auditability-review-unavailable"));
                assertEquals(CatalogAuditabilityReviewException.Reason.READ_UNAVAILABLE,
                        assertThrows(CatalogAuditabilityReviewException.class, () -> service.record(request, actor())).reason());
            } finally { admin.update(r).set(r.REQUEST, JSONB.jsonb(original.request())).where(r.ID.eq(request.reviewId())).execute(); }
        }
        assertEquals(receipt, service.get(request.reviewId(), receipt.reviewSha256())); assertEquals(List.of(1, 1), counts(request.reviewId()));
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String change) {
        if (!change.equals("no-credential")) request.header("Authorization", change.equals("bad-credential") ? "Bearer invalid" : TOKEN);
        if (change.equals("duplicate-credential")) request.header("Authorization", TOKEN);
        if (!change.equals("no-issuer")) request.header("X-AuthWeave-Oidc-Issuer", "http://localhost:8081");
        if (!change.equals("no-subject")) request.header("X-AuthWeave-Oidc-Subject", change.equals("another-subject") ? "another-curator" : "synthetic-auditability-curator");
        if (change.equals("duplicate-subject")) request.header("X-AuthWeave-Oidc-Subject", "synthetic-auditability-curator");
        request.header("X-AuthWeave-Curator-Role", change.equals("role") ? "viewer" : "catalog_curator");
        if (change.equals("duplicate-role")) request.header("X-AuthWeave-Curator-Role", "catalog_curator");
        request.header("X-AuthWeave-Curator-Project-Id", change.equals("project") ? "0" : "123456789012345678");
        request.header("X-AuthWeave-Curator-Org-Id", change.equals("organization") ? "0" : "987654321098765432");
        request.header("X-AuthWeave-Authenticated-At", change.equals("invalid-time") ? "invalid" : Instant.now().plusSeconds(change.equals("stale") ? -901 : change.equals("future") ? 31 : 0).toString());
        return request;
    }
    private CuratorActor actor() { return new CuratorActor("http://localhost:8081", "synthetic-auditability-curator", "123456789012345678", "987654321098765432", Instant.now()); }
    private List<Integer> counts(UUID id) { return List.of(dsl.fetchCount(CATALOG_AUDITABILITY_REVIEWS, CATALOG_AUDITABILITY_REVIEWS.ID.eq(id)),
            dsl.fetchCount(CATALOG_AUDITABILITY_REVIEW_EVENTS, CATALOG_AUDITABILITY_REVIEW_EVENTS.REVIEW_ID.eq(id))); }
    private List<Integer> unrelatedCounts() { return List.of("core.assessments", "core.catalog_proposals", "core.catalog_bootstrap_reviews", "core.catalog_published_snapshots",
            "core.catalog_publication_decisions", "audit.catalog_publication_events").stream().map(t -> dsl.fetchCount(dsl.selectFrom(DSL.table(t)))).toList(); }
    private JsonNode payload(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsString()); }
    private void sample(String name, String schema, boolean valid, JsonNode payload) { samples.add(new Sample(name, schema, valid, payload.deepCopy())); }
    private record Sample(String name, String schema, boolean valid, JsonNode payload) { }
}

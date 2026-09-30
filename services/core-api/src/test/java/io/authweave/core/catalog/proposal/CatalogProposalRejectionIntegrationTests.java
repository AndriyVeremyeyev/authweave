package io.authweave.core.catalog.proposal;

import java.nio.file.Path;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import io.authweave.core.PostgresIntegrationTest;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import io.authweave.core.catalog.draft.CatalogDraftValidator;

import static io.authweave.core.generated.audit.tables.CatalogProposalDecisionEvents.CATALOG_PROPOSAL_DECISION_EVENTS;
import static io.authweave.core.generated.jooq.tables.CatalogProposalDecisions.CATALOG_PROPOSAL_DECISIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "AUTHWEAVE_CORE_SERVICE_TOKEN=synthetic-internal-token-000000000000000000000",
        "AUTHWEAVE_OIDC_PROJECT_ID=123456789012345678",
        "AUTHWEAVE_OIDC_ORG_ID=987654321098765432"
})
@AutoConfigureMockMvc
@ActiveProfiles("local-catalog-write")
class CatalogProposalRejectionIntegrationTests extends PostgresIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private LocalCatalogProposalWriter proposals;
    @Autowired private CatalogProposalRepository repository;
    @Autowired private ObjectMapper mapper;
    @Autowired private DSLContext dsl;
    @Autowired private CatalogFactReviewWriter factReviews;

    @Test
    void sourceReviewIsAtomicActorBoundAndIdempotentEvenAfterRevisionChangesOrRejection() throws Exception {
        var request = proposal();
        var stored = proposals.save(request, null).proposal();
        var review = factReview(stored, "facts.OIDC");
        String path = factReviewPath(stored.proposalId());
        String payload = mapper.writeValueAsString(review);
        var receipt = mvc.perform(authorized(path, Instant.now()).content(payload))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.reviewNumber").value(1))
                .andExpect(jsonPath("$.kind").value("HUMAN_SOURCE_REVIEW_OBSERVATION"))
                .andExpect(jsonPath("$.sourceVerificationPerformed").value(false))
                .andExpect(jsonPath("$.approvalGranted").value(false))
                .andExpect(jsonPath("$.catalogWritesPerformed").value(false))
                .andExpect(jsonPath("$.factTrustChanged").value(false))
                .andReturn().getResponse().getContentAsString();
        assertEquals(receipt, mvc.perform(authorized(path, Instant.now()).content(payload))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var changed = (ObjectNode) mapper.valueToTree(review);
        changed.put("verdict", "INSUFFICIENT_EVIDENCE");
        mvc.perform(authorized(path, Instant.now()).content(changed.toString())).andExpect(status().isConflict());
        mvc.perform(authorized(path, Instant.now()).with(req -> {
            req.removeHeader("X-AuthWeave-Oidc-Subject"); req.addHeader("X-AuthWeave-Oidc-Subject", "another-curator"); return req;
        }).content(payload))
                .andExpect(status().isConflict());
        mvc.perform(authorized(path, Instant.now()).with(req -> {
            req.removeHeader("X-AuthWeave-Oidc-Issuer"); req.addHeader("X-AuthWeave-Oidc-Issuer", "http://another.invalid"); return req;
        }).content(payload))
                .andExpect(status().isConflict());
        var other = proposals.save(proposal(), null).proposal();
        mvc.perform(authorized(factReviewPath(other.proposalId()), Instant.now()).content(payload))
                .andExpect(status().isConflict());
        mvc.perform(authorized(path(stored.proposalId()), Instant.now())
                .content(body(0, stored.proposalSha256(), "OTHER"))).andExpect(status().isCreated());
        assertEquals(receipt, mvc.perform(authorized(path, Instant.now()).content(payload))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(authorized(path, Instant.now()).content(mapper.writeValueAsString(factReview(stored, "facts.SCIM"))))
                .andExpect(status().isConflict());
        proposals.save(new CatalogChangePreviewRequest(1, request.proposalId(), "Updated rationale.",
                request.expectedBaseSha256(), request.base(), request.candidate()), 0L);
        assertEquals(receipt, mvc.perform(authorized(path, Instant.now()).content(payload))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(authorized(path, Instant.now().minusSeconds(901)).content(payload)).andExpect(status().isForbidden());
        var r = io.authweave.core.generated.jooq.tables.CatalogFactReviews.CATALOG_FACT_REVIEWS;
        var e = io.authweave.core.generated.audit.tables.CatalogFactReviewEvents.CATALOG_FACT_REVIEW_EVENTS;
        assertEquals(1, dsl.fetchCount(r, r.PROPOSAL_ID.eq(stored.proposalId())));
        assertEquals(1, dsl.fetchCount(e, e.PROPOSAL_ID.eq(stored.proposalId())));
        var event = dsl.selectFrom(e).where(e.REVIEW_ID.eq(review.reviewId())).fetchOne();
        assertEquals("synthetic-curator", event.getActorSubject());
        assertEquals(review.expectedSha256(), event.getProposalSha256());
        assertEquals(review.factPath(), event.getFactPath());
        assertEquals(stored.request(), repository.revision(stored.proposalId(), 0).request());
        assertEquals(stored.preview(), repository.revision(stored.proposalId(), 0).preview());
    }

    @Test
    void factReviewRejectsUnauthorizedMalformedStaleAndAbsentTargetsWithoutWrites() throws Exception {
        var stored = proposals.save(proposal(), null).proposal();
        String path = factReviewPath(stored.proposalId());
        var input = (ObjectNode) mapper.valueToTree(factReview(stored, "facts.OIDC"));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(input.toString())).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization", "Bearer synthetic-internal-token-000000000000000000000")
                .contentType(MediaType.APPLICATION_JSON).content(input.toString())).andExpect(status().isUnauthorized());
        for (var header : java.util.Map.of("X-AuthWeave-Curator-Role", "assessor",
                "X-AuthWeave-Curator-Project-Id", "111", "X-AuthWeave-Curator-Org-Id", "222").entrySet()) {
            mvc.perform(authorized(path, Instant.now()).header(header.getKey(), header.getValue()).content(input.toString()))
                    .andExpect(status().isForbidden());
        }
        for (Instant time : java.util.List.of(Instant.now().minusSeconds(901), Instant.now().plusSeconds(60))) {
            mvc.perform(authorized(path, time).content(input.toString())).andExpect(status().isForbidden());
        }
        for (var mutation : java.util.Map.of("expectedVersion", 1, "expectedSha256", "0".repeat(64),
                "optionId", "absent-option", "factPath", "facts.WEBAUTHN").entrySet()) {
            var invalid = input.deepCopy(); invalid.set(mutation.getKey(), mapper.valueToTree(mutation.getValue()));
            mvc.perform(authorized(path, Instant.now()).content(invalid.toString())).andExpect(status().isConflict());
        }
        for (var mutation : java.util.Map.of("expectedVersion", "0", "verdict", "VERIFIED",
                "confirmation", "AUTOMATIC", "factPath", "../facts.OIDC", "actor", "forged").entrySet()) {
            var invalid = input.deepCopy(); invalid.put(mutation.getKey(), mutation.getValue());
            mvc.perform(authorized(path, Instant.now()).content(invalid.toString())).andExpect(status().isBadRequest());
        }
        var missing = input.deepCopy(); missing.remove("confirmation");
        mvc.perform(authorized(path, Instant.now()).content(missing.toString())).andExpect(status().isBadRequest());
        mvc.perform(authorized(factReviewPath(UUID.randomUUID()), Instant.now()).content(input.toString()))
                .andExpect(status().isNotFound());
        var r = io.authweave.core.generated.jooq.tables.CatalogFactReviews.CATALOG_FACT_REVIEWS;
        assertEquals(0, dsl.fetchCount(r, r.PROPOSAL_ID.eq(stored.proposalId())));
    }

    @Test
    void allFactFamiliesAndVerdictsProduceMonotonicReceiptsWithoutTrustPromotion() throws Exception {
        var stored = proposals.save(proposal(), null).proposal();
        var paths = java.util.List.of("facts.SCIM", "compatibility.applications.B2B_SAAS",
                "residency.USER_PROFILES", "authenticationControls.BROWSER.PARTNERS.PHISHING_RESISTANCE");
        for (int i = 0; i < paths.size(); i++) {
            var input = factReview(stored, paths.get(i));
            input = new CatalogFactReviewRequest(input.reviewId(), 0L, input.expectedSha256(), input.optionId(), input.factPath(),
                    CatalogFactReviewRequest.Verdict.values()[i % 3], input.confirmation());
            mvc.perform(authorized(factReviewPath(stored.proposalId()), Instant.now()).content(mapper.writeValueAsString(input)))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.reviewNumber").value(i + 1))
                    .andExpect(jsonPath("$.verdict").value(input.verdict().name()));
        }
        assertEquals(stored.request(), repository.current(stored.proposalId()).request());
        assertEquals(stored.preview(), repository.current(stored.proposalId()).preview());
    }

    @Test
    void concurrentExactRetriesCommitOneObservationAndAuditFailureRollsBackTheObservation() throws Exception {
        var stored = proposals.save(proposal(), null).proposal();
        var input = factReview(stored, "facts.OIDC");
        var actor = new CatalogProposalRejectionWriter.CuratorActor("http://localhost:8081", "synthetic-curator",
                "123456789012345678", "987654321098765432", Instant.now());
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<java.util.concurrent.Future<CatalogFactReviewWriter.Result>>();
            for (int i = 0; i < 4; i++) tasks.add(executor.submit(() -> factReviews.record(stored.proposalId(), input, actor)));
            int created = 0;
            for (var task : tasks) { var result = task.get(20, java.util.concurrent.TimeUnit.SECONDS);
                if (result.created()) created++; assertEquals(1, result.review().reviewNumber()); }
            assertEquals(1, created);
        }
        var failure = factReview(stored, "facts.SCIM");
        var invalidAuditActor = new CatalogProposalRejectionWriter.CuratorActor(actor.issuer(), actor.subject(), actor.projectId(),
                actor.organizationId(), Instant.now().minusSeconds(3600));
        assertThrows(RuntimeException.class, () -> factReviews.record(stored.proposalId(), failure, invalidAuditActor));
        var r = io.authweave.core.generated.jooq.tables.CatalogFactReviews.CATALOG_FACT_REVIEWS;
        var e = io.authweave.core.generated.audit.tables.CatalogFactReviewEvents.CATALOG_FACT_REVIEW_EVENTS;
        assertEquals(1, dsl.fetchCount(r, r.PROPOSAL_ID.eq(stored.proposalId())));
        assertEquals(1, dsl.fetchCount(e, e.PROPOSAL_ID.eq(stored.proposalId())));
        assertEquals(2, factReviews.record(stored.proposalId(), failure, actor).review().reviewNumber());
    }

    private static CatalogFactReviewRequest factReview(CatalogProposalSnapshot snapshot, String factPath) {
        return new CatalogFactReviewRequest(UUID.randomUUID(), snapshot.version(), snapshot.proposalSha256(),
                "example-managed-eu", factPath, CatalogFactReviewRequest.Verdict.SOURCE_SUPPORTS_CLAIM,
                CatalogFactReviewRequest.Confirmation.MANUAL_SOURCE_REVIEW);
    }

    private static String factReviewPath(UUID id) { return "/api/v1/catalog-change-proposals/" + id + "/fact-reviews"; }

    @Test
    void validCuratorRejectsCurrentRevisionExactlyOnceWithMatchingAudit() throws Exception {
        var proposal = proposals.save(proposal(), null).proposal();
        String body = body(proposal.version(), proposal.proposalSha256(), "INSUFFICIENT_EVIDENCE");
        mvc.perform(authorizedRead(proposal.proposalId(), Instant.now())).andExpect(status().isNoContent());
        mvc.perform(authorizedRead(UUID.randomUUID(), Instant.now())).andExpect(status().isNotFound());
        mvc.perform(authorized(path(proposal.proposalId()), Instant.now()).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposalId").value(proposal.proposalId().toString()))
                .andExpect(jsonPath("$.proposalVersion").value(0))
                .andExpect(jsonPath("$.proposalSha256").value(proposal.proposalSha256()))
                .andExpect(jsonPath("$.decision").value("REJECTED"))
                .andExpect(jsonPath("$.reasonCode").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.recordedAt").exists());
        mvc.perform(authorizedRead(proposal.proposalId(), Instant.now()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposalSha256").value(proposal.proposalSha256()))
                .andExpect(jsonPath("$.decision").value("REJECTED"));
        var d = CATALOG_PROPOSAL_DECISIONS;
        var e = CATALOG_PROPOSAL_DECISION_EVENTS;
        var decision = dsl.selectFrom(d).where(d.PROPOSAL_ID.eq(proposal.proposalId())).fetchOne();
        var event = dsl.selectFrom(e).where(e.DECISION_ID.eq(decision.getId())).fetchOne();
        assertEquals(decision.getProposalSha256(), event.getProposalSha256());
        assertEquals("synthetic-curator", event.getActorSubject());
        assertEquals("123456789012345678", event.getActorProjectId());
        assertEquals("987654321098765432", event.getActorOrgId());
        assertEquals("PROPOSED", repository.current(proposal.proposalId()).state());
        mvc.perform(authorized(path(proposal.proposalId()), Instant.now()).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("catalog-proposal-decision-conflict"));
        assertEquals(1, dsl.fetchCount(d, d.PROPOSAL_ID.eq(proposal.proposalId())));
        assertEquals(1, dsl.fetchCount(e, e.PROPOSAL_ID.eq(proposal.proposalId())));
    }

    @Test
    void missingCredentialRoleScopeOrRecentAuthenticationCannotWrite() throws Exception {
        var proposal = proposals.save(proposal(), null).proposal();
        String path = path(proposal.proposalId());
        String body = body(0, proposal.proposalSha256(), "OTHER");
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(readPath(proposal.proposalId()))).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization", "Bearer synthetic-internal-token-000000000000000000000")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(authorized(path, Instant.now()).header("X-AuthWeave-Curator-Role", "assessor")
                .content(body)).andExpect(status().isForbidden());
        mvc.perform(authorized(path, Instant.now()).header("X-AuthWeave-Curator-Project-Id", "111")
                .content(body)).andExpect(status().isForbidden());
        mvc.perform(authorized(path, Instant.now().minusSeconds(901)).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(authorizedRead(proposal.proposalId(), Instant.now().minusSeconds(901)))
                .andExpect(status().isForbidden());
        assertEquals(0, dsl.fetchCount(CATALOG_PROPOSAL_DECISIONS,
                CATALOG_PROPOSAL_DECISIONS.PROPOSAL_ID.eq(proposal.proposalId())));
    }

    @Test
    void staleOrMalformedRequestAndUnknownProposalFailClosed() throws Exception {
        var proposal = proposals.save(proposal(), null).proposal();
        String path = path(proposal.proposalId());
        mvc.perform(authorized(path, Instant.now()).content(body(1, proposal.proposalSha256(), "OTHER")))
                .andExpect(status().isConflict());
        mvc.perform(authorized(path, Instant.now()).content(body(0, "0".repeat(64), "OTHER")))
                .andExpect(status().isConflict());
        mvc.perform(authorized(path, Instant.now()).content(body(0, proposal.proposalSha256(), "APPROVED")))
                .andExpect(status().isBadRequest());
        mvc.perform(authorized(path, Instant.now()).content("{\"expectedVersion\":0,\"reasonCode\":\"OTHER\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(authorized(path(UUID.randomUUID()), Instant.now())
                        .content(body(0, proposal.proposalSha256(), "OTHER")))
                .andExpect(status().isNotFound());
        assertEquals(0, dsl.fetchCount(CATALOG_PROPOSAL_DECISIONS,
                CATALOG_PROPOSAL_DECISIONS.PROPOSAL_ID.eq(proposal.proposalId())));
    }

    @Test
    void curatorIndexIsScopedBoundedAndDoesNotIncludeProposalBodies() throws Exception {
        String listPath = "/api/v1/catalog-change-proposals";
        mvc.perform(get(listPath)).andExpect(status().isUnauthorized());
        mvc.perform(withCuratorHeaders(get(listPath), Instant.now())
                        .header("X-AuthWeave-Curator-Role", "assessor"))
                .andExpect(status().isForbidden());
        mvc.perform(withCuratorHeaders(get(listPath), Instant.now().minusSeconds(901)))
                .andExpect(status().isForbidden());
        var created = new ArrayList<CatalogProposalSnapshot>();
        for (int i = 0; i < 22; i++) created.add(proposals.save(proposal(), null).proposal());
        var rejected = created.getLast();
        mvc.perform(authorized(path(rejected.proposalId()), Instant.now())
                        .content(body(rejected.version(), rejected.proposalSha256(), "OTHER")))
                .andExpect(status().isCreated());
        var firstResponse = mvc.perform(withCuratorHeaders(get(listPath), Instant.now()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var first = mapper.readTree(firstResponse);
        assertEquals(20, first.get("items").size());
        assertTrue(first.hasNonNull("nextBefore"));
        assertTrue(firstResponse.contains("\"rejectionRecorded\":true"));
        assertTrue(!firstResponse.contains("\"request\"") && !firstResponse.contains("\"preview\""));
        var seen = new HashSet<String>();
        first.get("items").forEach(item -> seen.add(item.get("proposalId").asText()));
        var second = mapper.readTree(mvc.perform(withCuratorHeaders(get(listPath), Instant.now())
                        .param("beforeCreatedAt", first.get("nextBefore").get("createdAt").asText())
                        .param("beforeId", first.get("nextBefore").get("id").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        second.get("items").forEach(item -> assertTrue(seen.add(item.get("proposalId").asText())));
        assertTrue(created.stream().allMatch(item -> seen.contains(item.proposalId().toString())));
        mvc.perform(withCuratorHeaders(get(listPath), Instant.now())
                        .param("beforeId", rejected.proposalId().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request"));
        mvc.perform(withCuratorHeaders(get(listPath), Instant.now())
                        .param("beforeCreatedAt", "not-a-time")
                        .param("beforeId", rejected.proposalId().toString()))
                .andExpect(status().isBadRequest());
    }

    private CatalogChangePreviewRequest proposal() throws Exception {
        var fixture = Path.of(System.getProperty("basedir", "."),
                "../../packages/contracts/tests/fixtures/catalog-change-preview-request.valid.json");
        var json = (ObjectNode) mapper.readTree(fixture.toFile());
        json.put("proposalId", UUID.randomUUID().toString());
        return mapper.treeToValue(json, CatalogChangePreviewRequest.class);
    }

    @Test
    void evidenceReviewIncludesUnchangedFactsUsesCorePolicyAndPagesAnExactRevision() throws Exception {
        var json = (ObjectNode) mapper.valueToTree(proposal());
        var options = ((ObjectNode) json.get("candidate")).withArray("options");
        var template = (ObjectNode) options.get(0).deepCopy();
        for (int i = 1; i < 3; i++) {
            var copy = template.deepCopy();
            copy.put("id", "example-managed-eu-" + i);
            copy.put("configuration", "Synthetic configuration " + i);
            options.add(copy);
        }
        ((ObjectNode) options.get(0).get("facts").get("OIDC").get("evidence"))
                .put("observedAt", "2026-01-01T00:00:00Z");
        ((ObjectNode) options.get(1).get("facts").get("OIDC").get("evidence"))
                .put("observedAt", "2027-01-01T00:00:00Z");
        var request = mapper.treeToValue(json, CatalogChangePreviewRequest.class);
        var stored = proposals.save(request, null).proposal();
        var service = new CatalogProposalEvidenceService(repository,
                new CatalogDraftValidator(Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC)), mapper);
        var first = service.review(stored.proposalId(), 0, 0);
        assertEquals(27, first.factCount());
        assertEquals(25, first.freshness().current());
        assertEquals(1, first.freshness().stale());
        assertEquals(1, first.freshness().future());
        assertEquals(20, first.items().size());
        assertEquals(20, first.nextOffset());
        var second = service.review(stored.proposalId(), 0, 20);
        assertEquals(7, second.items().size());
        assertEquals(null, second.nextOffset());
        assertTrue(first.items().stream().anyMatch(item -> item.path().equals("compatibility.clients.BROWSER")));
        assertTrue(first.items().stream().allMatch(item -> item.evidenceStatus().name().equals("UNREVIEWED")));
        var claims = first.items().stream().filter(item -> item.optionId().equals("example-managed-eu"))
                .collect(java.util.stream.Collectors.toMap(item -> item.path(), item -> item.claim()));
        assertEquals(new CatalogFactClaim.Capability(io.authweave.core.catalog.ProviderCatalog.Availability.UNAVAILABLE),
                claims.get("facts.SCIM"));
        assertEquals(new CatalogFactClaim.Compatibility(io.authweave.core.catalog.ProviderCatalog.Support.SUPPORTED),
                claims.get("compatibility.clients.BROWSER"));
        assertEquals(new CatalogFactClaim.Residency(io.authweave.core.catalog.ProviderCatalog.ResidencyCoverage.COMPLETE,
                java.util.List.of("DE", "FR")), claims.get("residency.USER_PROFILES"));
        assertEquals(new CatalogFactClaim.AuthenticationControl(io.authweave.core.catalog.ProviderCatalog.Support.SUPPORTED,
                io.authweave.core.catalog.ProviderCatalog.Support.SUPPORTED),
                claims.get("authenticationControls.BROWSER.PARTNERS.PHISHING_RESISTANCE"));
        assertEquals(stored.request(), repository.revision(stored.proposalId(), 0).request());
        assertEquals(1, repository.events(stored.proposalId(), null, 100).items().size());
        var corruptRepository = mock(CatalogProposalRepository.class);
        when(corruptRepository.revision(stored.proposalId(), 0)).thenReturn(new CatalogProposalSnapshot(
                stored.proposalId(), 0, stored.state(), stored.requestSchemaVersion(), "0".repeat(64),
                stored.recordedAt(), stored.request(), stored.preview()));
        var corruptService = new CatalogProposalEvidenceService(corruptRepository,
                new CatalogDraftValidator(Clock.systemUTC()), mapper);
        assertEquals(CatalogProposalException.Reason.REPLAY_UNAVAILABLE,
                assertThrows(CatalogProposalException.class,
                        () -> corruptService.review(stored.proposalId(), 0, 0)).reason());

        String path = "/api/v1/catalog-change-proposals/" + stored.proposalId() + "/revisions/0/evidence-review";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(withCuratorHeaders(get(path), Instant.now().minusSeconds(901)))
                .andExpect(status().isForbidden());
        mvc.perform(withCuratorHeaders(get(path), Instant.now()).param("offset", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(withCuratorHeaders(get(path), Instant.now()).param("offset", "6801"))
                .andExpect(status().isBadRequest());
        mvc.perform(withCuratorHeaders(get(path), Instant.now()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposalSha256").value(stored.proposalSha256()))
                .andExpect(jsonPath("$.factCount").value(27))
                .andExpect(jsonPath("$.policyVersion").value("catalog-proposal-evidence-review-2"))
                .andExpect(jsonPath("$.items[0].claim.kind").value("AUTHENTICATION_CONTROL"))
                .andExpect(jsonPath("$.items[0].claim.enforcement").value("SUPPORTED"))
                .andExpect(jsonPath("$.nextOffset").value(20))
                .andExpect(jsonPath("$.sourceVerificationPerformed").value(false));
        proposals.save(new CatalogChangePreviewRequest(1, request.proposalId(), "Updated rationale.",
                request.expectedBaseSha256(), request.base(), request.candidate()), 0L);
        mvc.perform(withCuratorHeaders(get(path), Instant.now()).param("offset", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposalVersion").value(0))
                .andExpect(jsonPath("$.proposalSha256").value(stored.proposalSha256()))
                .andExpect(jsonPath("$.items.length()").value(7));
        mvc.perform(withCuratorHeaders(get(path.replace("/revisions/0/", "/revisions/2/")), Instant.now()))
                .andExpect(status().isNotFound());
    }

    private static String path(UUID id) {
        return "/api/v1/catalog-change-proposals/" + id + "/decisions/rejection";
    }

    private static String readPath(UUID id) {
        return "/api/v1/catalog-change-proposals/" + id + "/decisions/current";
    }

    private static MockHttpServletRequestBuilder authorizedRead(UUID id, Instant authenticatedAt) {
        return withCuratorHeaders(get(readPath(id)), authenticatedAt);
    }

    private static String body(long version, String digest, String reason) {
        return "{\"expectedVersion\":" + version + ",\"expectedSha256\":\"" + digest
                + "\",\"reasonCode\":\"" + reason + "\"}";
    }

    private static MockHttpServletRequestBuilder authorized(String path, Instant authenticatedAt) {
        return withCuratorHeaders(post(path).contentType(MediaType.APPLICATION_JSON), authenticatedAt);
    }

    private static MockHttpServletRequestBuilder withCuratorHeaders(MockHttpServletRequestBuilder builder,
            Instant authenticatedAt) {
        return builder
                .header("Authorization", "Bearer synthetic-internal-token-000000000000000000000")
                .header("X-AuthWeave-Oidc-Issuer", "http://localhost:8081")
                .header("X-AuthWeave-Oidc-Subject", "synthetic-curator")
                .header("X-AuthWeave-Curator-Role", "catalog_curator")
                .header("X-AuthWeave-Curator-Project-Id", "123456789012345678")
                .header("X-AuthWeave-Curator-Org-Id", "987654321098765432")
                .header("X-AuthWeave-Authenticated-At", authenticatedAt.toString());
    }
}

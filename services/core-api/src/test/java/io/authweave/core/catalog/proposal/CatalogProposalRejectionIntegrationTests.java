package io.authweave.core.catalog.proposal;

import java.nio.file.Path;
import java.time.Instant;
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

import static io.authweave.core.generated.audit.tables.CatalogProposalDecisionEvents.CATALOG_PROPOSAL_DECISION_EVENTS;
import static io.authweave.core.generated.jooq.tables.CatalogProposalDecisions.CATALOG_PROPOSAL_DECISIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private CatalogChangePreviewRequest proposal() throws Exception {
        var fixture = Path.of(System.getProperty("basedir", "."),
                "../../packages/contracts/tests/fixtures/catalog-change-preview-request.valid.json");
        var json = (ObjectNode) mapper.readTree(fixture.toFile());
        json.put("proposalId", UUID.randomUUID().toString());
        return mapper.treeToValue(json, CatalogChangePreviewRequest.class);
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

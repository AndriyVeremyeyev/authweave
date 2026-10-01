package io.authweave.core.catalog.publication;

import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import io.authweave.core.catalog.draft.CatalogDraftValidator;
import io.authweave.core.catalog.proposal.CatalogFactReviewRequest;
import io.authweave.core.catalog.proposal.CatalogFactReviewWriter;
import io.authweave.core.catalog.proposal.LocalCatalogProposalWriter;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;
import static io.authweave.core.catalog.publication.CatalogPublicationLookupFixtures.*;
import static io.authweave.core.catalog.publication.CatalogPublicationPreflight.Blocker.*;
import static io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict.*;
import static io.authweave.core.generated.jooq.tables.CatalogPublishedSnapshots.CATALOG_PUBLISHED_SNAPSHOTS;
import static io.authweave.core.generated.jooq.tables.CatalogPublicationDecisions.CATALOG_PUBLICATION_DECISIONS;
import static io.authweave.core.generated.audit.tables.CatalogPublicationEvents.CATALOG_PUBLICATION_EVENTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local-catalog-write")
class CatalogPublicationPreflightIntegrationTests {
    // Empty registry is a real bootstrap prerequisite; do not share a database with admin-seeded storage tests.
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

    @Autowired private ObjectMapper mapper;
    @Autowired private DSLContext dsl;
    @Autowired private CatalogPublicationPreflight preflight;
    @Autowired private LocalCatalogProposalWriter writer;
    @Autowired private CatalogFactReviewWriter reviewWriter;
    @Autowired private CatalogDraftValidator validator;
    @MockitoSpyBean private CatalogPublicationPreflightRepository repository;

    @Test
    void emptyBootstrapIsReadOnlyRepeatableReadAndDoesNotPublishOrSeed() {
        var candidate = new CatalogPublicationLookupFixtures(mapper).root(Instant.now()).snapshot().catalog().asDraft();
        doAnswer(call -> { transaction(); return call.callRealMethod(); }).when(repository).registryEmpty();
        var result = preflight.bootstrap(candidate);
        assertFalse(result.blockers().contains(BOOTSTRAP_REGISTRY_NOT_EMPTY));
        assertTrue(result.blockers().contains(BOOTSTRAP_REVIEW_WORKFLOW_UNAVAILABLE));
        assertEquals(9, result.facts().unobserved()); assertFalse(result.publicationReady()); registryStillEmpty();
    }

    @Test
    void coreReadsLatestPerFactAndCannotReuseOldRevisionReviewsForANewHead() {
        var fixtures = new CatalogPublicationLookupFixtures(mapper); var node = fixtures.child(fixtures.root(Instant.now()));
        var saved = writer.save(node.request(), null).proposal();
        var actor = new CuratorActor("http://localhost:8081", "synthetic-preflight-curator", "123456789012345678",
                "987654321098765432", Instant.now());
        var facts = validator.validate(node.request().candidate()).facts();
        for (var fact : facts) reviewWriter.record(saved.proposalId(), new CatalogFactReviewRequest(UUID.randomUUID(),
                saved.version(), saved.proposalSha256(), fact.optionId(), fact.path(), SOURCE_SUPPORTS_CLAIM,
                CatalogFactReviewRequest.Confirmation.MANUAL_SOURCE_REVIEW), actor);
        doAnswer(call -> { transaction(); return call.callRealMethod(); }).when(repository).proposal(any(UUID.class), anyLong());
        var complete = preflight.proposal(saved.proposalId(), saved.version(), saved.proposalSha256(), null);
        assertTrue(complete.facts().allFactsHaveSupportingObservation()); assertTrue(complete.blockers().contains(IMPACT_RECEIPT_MISSING));
        assertFalse(complete.approvalGranted()); assertFalse(complete.sourceVerificationPerformed());
        var fact = facts.getFirst();
        var newer = reviewWriter.record(saved.proposalId(), new CatalogFactReviewRequest(UUID.randomUUID(), saved.version(),
                saved.proposalSha256(), fact.optionId(), fact.path(), SOURCE_DOES_NOT_SUPPORT_CLAIM,
                CatalogFactReviewRequest.Confirmation.MANUAL_SOURCE_REVIEW), actor).review();
        var contradicted = preflight.proposal(saved.proposalId(), saved.version(), saved.proposalSha256(), null);
        assertEquals(8, contradicted.facts().supporting()); assertEquals(1, contradicted.facts().contradicting());
        assertEquals(newer.reviewNumber(), contradicted.reviewThroughNumber()); assertTrue(contradicted.blockers().contains(SOURCE_CONTRADICTION));
        var request = node.request();
        var next = writer.save(new CatalogChangePreviewRequest(1, request.proposalId(), request.rationale() + " new revision",
                request.expectedBaseSha256(), request.base(), request.candidate()), saved.version()).proposal();
        var historic = preflight.proposal(saved.proposalId(), saved.version(), saved.proposalSha256(), null);
        assertTrue(historic.blockers().contains(PROPOSAL_NOT_CURRENT)); assertEquals(1, historic.facts().contradicting());
        var head = preflight.proposal(next.proposalId(), next.version(), next.proposalSha256(), null);
        assertEquals(9, head.facts().unobserved()); assertEquals(0, head.reviewThroughNumber()); registryStillEmpty();
    }

    @Test
    void queryWithholdsRequestOnServerAtExactByteBoundaryWithoutReadingTheStoredPreview() {
        var fixtures = new CatalogPublicationLookupFixtures(mapper); var node = fixtures.child(fixtures.root(Instant.now()));
        var saved = writer.save(node.request(), null).proposal();
        var row = repository.proposal(saved.proposalId(), saved.version());
        assertNotNull(row.request()); assertTrue(row.requestBytes() > 0);
        var bounded = repository.proposal(saved.proposalId(), saved.version(), row.requestBytes() - 1);
        assertNull(bounded.request()); assertEquals(row.requestBytes(), bounded.requestBytes());
        assertNotNull(repository.proposal(saved.proposalId(), saved.version(), row.requestBytes()).request()); registryStillEmpty();
    }

    private void transaction() {
        assertEquals("on", dsl.fetchValue("SHOW transaction_read_only"));
        assertEquals("repeatable read", dsl.fetchValue("SHOW transaction_isolation"));
        assertEquals("authweave_core_runtime", dsl.fetchValue("SELECT current_user"));
    }
    private void registryStillEmpty() {
        assertEquals(0, dsl.fetchCount(CATALOG_PUBLISHED_SNAPSHOTS)); assertEquals(0, dsl.fetchCount(CATALOG_PUBLICATION_DECISIONS));
        assertEquals(0, dsl.fetchCount(CATALOG_PUBLICATION_EVENTS));
    }
}

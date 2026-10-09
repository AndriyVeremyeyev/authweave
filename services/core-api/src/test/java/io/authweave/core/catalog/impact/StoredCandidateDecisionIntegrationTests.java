package io.authweave.core.catalog.impact;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.publication.*;
import io.authweave.core.catalog.auditability.*;
import io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static io.authweave.core.generated.jooq.tables.CatalogBootstrapReviews.CATALOG_BOOTSTRAP_REVIEWS;
import static io.authweave.core.generated.audit.tables.CatalogBootstrapReviewEvents.CATALOG_BOOTSTRAP_REVIEW_EVENTS;
import static io.authweave.core.generated.jooq.tables.CatalogAuditabilityReviews.CATALOG_AUDITABILITY_REVIEWS;
import static io.authweave.core.generated.audit.tables.CatalogAuditabilityReviewEvents.CATALOG_AUDITABILITY_REVIEW_EVENTS;
import static io.authweave.core.generated.jooq.tables.CatalogPublishedSnapshots.CATALOG_PUBLISHED_SNAPSHOTS;
import static io.authweave.core.generated.jooq.tables.CatalogPublicationDecisions.CATALOG_PUBLICATION_DECISIONS;
import static io.authweave.core.catalog.impact.CandidateAuditabilityInputTests.*;
import static io.authweave.core.catalog.impact.CandidateHardConstraintEvaluator.*;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated fictional review/audit rows only. Never touches local IdP users, roles or application data. */
@SpringBootTest
class StoredCandidateDecisionIntegrationTests {
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-bookworm")
            .withDatabaseName("authweave").withUsername("authweave_admin").withPassword("admin-test-password")
            .withInitScript("db/test/init-runtime-roles.sql");
    static { postgres.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "authweave_core_runtime");
        registry.add("spring.datasource.password", () -> "core-test-password");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 3);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }
    @Autowired private DSLContext dsl;
    @Autowired private ObjectMapper mapper;
    @Autowired private CatalogBootstrapReviewService baseReviews;
    @Autowired private CatalogAuditabilityReviewService auditReviews;
    @Autowired private CatalogAuditabilityDraftValidator drafts;
    @Autowired private StoredCandidateDecisionService decisions;

    private record Fixture(CatalogBootstrapReview base, CatalogAuditabilityReview audit, StoredCandidateDecisionService.Reference reference) { }
    private Fixture stored(Consumer<ObjectNode> baseChange, Consumer<ObjectNode> auditChange, Verdict verdict) {
        var rawBase = (ObjectNode) base(); freshSourceDates(rawBase); baseChange.accept(rawBase);
        var base = mapper.treeToValue(rawBase, ProviderCatalogDraft.class);
        var observations = base.options().stream().flatMap(o -> CatalogDraftFacts.entries(o).keySet().stream().sorted()
                .map(path -> new CatalogBootstrapReviewRequest.Observation(o.id(), path, Verdict.SOURCE_SUPPORTS_CLAIM))).toList();
        var request = new CatalogBootstrapReviewRequest(1, UUID.randomUUID(), CatalogDraftCanonicalizer.sha256(base), base, observations,
                CatalogBootstrapReviewRequest.Confirmation.MANUAL_BOOTSTRAP_SOURCE_REVIEW);
        var receipt = baseReviews.record(request, actor()).review();
        var savedBase = mapper.readTree(baseReviews.loadForDecision(receipt.reviewId(), receipt.reviewSha256()).candidateJson());
        var rawAudit = (ObjectNode) supplement(); freshSourceDates(rawAudit); rawAudit.put("baseContentSha256", request.expectedCandidateSha256());
        rawAudit.put("baseCatalogVersion", base.catalogVersion()); auditChange.accept(rawAudit);
        var candidate = new CatalogAuditabilityDraftValidator.Request(base, mapper.treeToValue(rawAudit, AuditabilityCatalogDraft.class));
        var validation = drafts.validate(candidate);
        var manual = new CatalogAuditabilityReviewRequest(1, UUID.randomUUID(), validation.baseValidation().contentSha256(),
                validation.contentSha256(), validation.reviewTargetSetSha256(), candidate, validation.targets().stream()
                    .map(t -> new CatalogAuditabilityReviewRequest.Observation(t.scope().optionId(), t.fact().criterion(), t.targetSha256(), verdict)).toList(),
                CatalogAuditabilityReviewRequest.Confirmation.MANUAL_AUDITABILITY_SOURCE_REVIEW);
        var audit = auditReviews.record(manual, actor()).review();
        var savedAudit = mapper.readTree(auditReviews.loadForDecision(audit.reviewId(), audit.reviewSha256()).candidateJson()).get("auditabilityDraft");
        var ref = new StoredCandidateDecisionService.Reference(receipt.reviewId(), receipt.reviewSha256(), DecisionCanonicalizer.sha256(savedBase),
                new StoredCandidateDecisionService.AuditReference(audit.reviewId(), audit.reviewSha256(), DecisionCanonicalizer.sha256(savedAudit)));
        return new Fixture(receipt, audit, ref);
    }
    private static void freshSourceDates(JsonNode document) {
        // Fictional fixture creation, not a review-time refresh: positive DB tests must not expire with the calendar.
        var observedAt = Instant.now().minusSeconds(86400).toString();
        for (var option : document.path("options")) for (var fact : option.path("facts"))
            ((ObjectNode) fact.get("evidence")).put("observedAt", observedAt);
    }
    private Fixture stored() { return stored(b -> { }, a -> { }, Verdict.SOURCE_SUPPORTS_CLAIM); }
    private static CuratorActor actor() { return new CuratorActor("https://identity.example.invalid", "fictional-reviewer", "123", "456", Instant.now()); }
    private StoredCandidateDecisionService.Result evaluate(StoredCandidateDecisionService.Reference ref) { return decisions.evaluate(profile(), 6, ref, weights()); }
    private List<Integer> counts() { return List.of(dsl.fetchCount(CATALOG_BOOTSTRAP_REVIEWS), dsl.fetchCount(CATALOG_BOOTSTRAP_REVIEW_EVENTS),
            dsl.fetchCount(CATALOG_AUDITABILITY_REVIEWS), dsl.fetchCount(CATALOG_AUDITABILITY_REVIEW_EVENTS),
            dsl.fetchCount(CATALOG_PUBLISHED_SNAPSHOTS), dsl.fetchCount(CATALOG_PUBLICATION_DECISIONS)); }

    @Test void realDatabaseReviewsDriveTheWholeDecisionWithoutWritesPublicationOrActorDisclosure() {
        var fixture = stored(); var before = counts(); var result = evaluate(fixture.reference());
        assertEquals(before, counts()); assertTrue(result.storedReviewsVerified()); assertEquals(fixture.base(), result.baseReview());
        assertEquals(fixture.audit(), result.auditabilityReview()); assertEquals(List.of("example-managed-eu"), result.decision().shortlist());
        assertEquals(CandidateHardConstraintEvaluator.Verdict.ELIGIBLE, result.decision().candidates().getFirst().hardChecks().hardVerdict());
        assertFalse(result.currentCuratorAuthorityVerified()); assertFalse(result.sourceVerificationPerformed()); assertFalse(result.approvalGranted());
        assertFalse(result.publicationReady()); assertFalse(result.writesPerformed()); assertFalse(result.decision().sourceAuthorityVerified());
        assertFalse(result.deferredBoundaries().contains("reviewReceiptAuthenticationAndLoading"));
        assertFalse(result.deferredBoundaries().contains("realAuditabilitySupplementLoading"));
        assertTrue(result.deferredBoundaries().contains("publishedSnapshotPinning"));
        var json = mapper.valueToTree(result).toString(); assertFalse(json.contains("fictional-reviewer")); assertFalse(json.contains("actorSubject"));
    }
    @Test void noAuditReferenceRemainsMissingNotAnImplicitSyntheticSupplement() {
        var fixture = stored(); var pin = fixture.reference(); var before = counts();
        var result = evaluate(new StoredCandidateDecisionService.Reference(pin.reviewId(), pin.reviewSha256(), pin.decisionCatalogSha256(), null));
        assertTrue(result.decision().shortlist().isEmpty()); assertNull(result.auditabilityReview());
        assertNull(result.decision().binding().inputs().hardChecks().auditabilitySha256());
        assertTrue(result.deferredBoundaries().contains("realAuditabilitySupplementLoading")); assertEquals(before, counts());
    }
    @ParameterizedTest @ValueSource(strings = {"base-review", "base-decision", "audit-review", "audit-decision", "missing-base", "missing-audit"})
    void wrongPinsAndMissingRowsNeverFallBackToPayloadOrAnotherReview(String variant) {
        var fixture = stored(); var pin = fixture.reference(); var audit = pin.auditability();
        var changedAudit = new StoredCandidateDecisionService.AuditReference(variant.equals("missing-audit") ? UUID.randomUUID() : audit.reviewId(),
                variant.equals("audit-review") ? "0".repeat(64) : audit.reviewSha256(), variant.equals("audit-decision") ? "0".repeat(64) : audit.decisionSupplementSha256());
        var input = new StoredCandidateDecisionService.Reference(variant.equals("missing-base") ? UUID.randomUUID() : pin.reviewId(),
                variant.equals("base-review") ? "0".repeat(64) : pin.reviewSha256(), variant.equals("base-decision") ? "0".repeat(64) : pin.decisionCatalogSha256(), changedAudit);
        var before = counts();
        if (variant.contains("audit")) assertThrows(CatalogAuditabilityReviewException.class, () -> evaluate(input));
        else assertThrows(CatalogBootstrapReviewException.class, () -> evaluate(input));
        assertEquals(before, counts());
    }
    @ParameterizedTest @ValueSource(strings = {"SOURCE_DOES_NOT_SUPPORT_CLAIM", "INSUFFICIENT_EVIDENCE"})
    void nonSupportingStoredVerdictsDoNotProduceEligibilityEvenWhenTheClaimSaysSupported(String verdict) {
        var fixture = stored(b -> { }, a -> { }, Verdict.valueOf(verdict)); var before = counts();
        var result = evaluate(fixture.reference()); assertEquals(before, counts()); assertTrue(result.storedReviewsVerified());
        assertEquals(CandidateHardConstraintEvaluator.Verdict.UNRESOLVED, result.decision().candidates().getFirst().hardChecks().hardVerdict()); assertTrue(result.decision().shortlist().isEmpty());
        assertEquals(fixture.audit().recordedAt(), result.auditabilityReview().recordedAt());
    }
    @ParameterizedTest @ValueSource(strings = {"STALE", "FUTURE", "BELOW_MINIMUM"})
    void storedReviewDoesNotRefreshAuditDatesOrEraseDocumentedRetentionFailure(String scenario) {
        var fixture = stored(b -> { }, a -> {
            if (scenario.equals("BELOW_MINIMUM")) ((ObjectNode) a.at("/options/0/facts/1")).put("documentedMinimumRetentionDays", 29);
            else ((ObjectNode) a.at("/options/0/facts/1/evidence")).put("observedAt",
                    scenario.equals("STALE") ? Instant.now().minusSeconds(91L * 86400).toString() : Instant.now().plusSeconds(86400).toString());
        }, Verdict.SOURCE_SUPPORTS_CLAIM);
        var result = evaluate(fixture.reference());
        assertEquals(scenario.equals("BELOW_MINIMUM") ? CandidateHardConstraintEvaluator.Verdict.EXCLUDED : CandidateHardConstraintEvaluator.Verdict.UNRESOLVED, result.decision().candidates().getFirst().hardChecks().hardVerdict());
        assertTrue(result.decision().shortlist().isEmpty()); assertNull(result.decision().candidates().getFirst().score());
    }
    @Test void differentSupplementBaseCannotBeMixedEvenWithTwoValidHistoricalReceipts() {
        var first = stored(); var other = stored(b -> b.put("catalogVersion", "other-fictional-base"), a -> { }, Verdict.SOURCE_SUPPORTS_CLAIM);
        var a = first.reference(); var mixed = new StoredCandidateDecisionService.Reference(a.reviewId(), a.reviewSha256(), a.decisionCatalogSha256(), other.reference().auditability());
        assertEquals(CatalogAuditabilityReviewException.Reason.CONFLICT, assertThrows(CatalogAuditabilityReviewException.class, () -> evaluate(mixed)).reason());
    }
    @Test void historicalUnorderedDigestCannotStandInForExactPersistedArrayOrder() {
        var fixture = stored(b -> ((ObjectNode) b.at("/options/0/facts/OIDC")).set("conditions", mapper.createArrayNode().add("Second condition").add("First condition")), a -> { }, Verdict.SOURCE_SUPPORTS_CLAIM);
        var saved = mapper.readTree(baseReviews.loadForDecision(fixture.base().reviewId(), fixture.base().reviewSha256()).candidateJson());
        var reordered = (ObjectNode) saved.deepCopy(); ((ObjectNode) reordered.at("/options/0/facts/OIDC"))
                .set("conditions", mapper.createArrayNode().add("First condition").add("Second condition"));
        assertEquals(CatalogDraftCanonicalizer.sha256(mapper.treeToValue(saved, ProviderCatalogDraft.class)), CatalogDraftCanonicalizer.sha256(mapper.treeToValue(reordered, ProviderCatalogDraft.class)));
        assertNotEquals(DecisionCanonicalizer.sha256(saved), DecisionCanonicalizer.sha256(reordered));
        var pin = fixture.reference(); var wrong = new StoredCandidateDecisionService.Reference(pin.reviewId(), pin.reviewSha256(), DecisionCanonicalizer.sha256(reordered), pin.auditability());
        assertThrows(CatalogBootstrapReviewException.class, () -> evaluate(wrong));
    }
    @ParameterizedTest @ValueSource(strings = {"base-body", "base-audit", "audit-body", "audit-event"})
    void corruptedStoredBodyOrActorAuditIsDeniedRatherThanRecomputedAsSuccess(String variant) throws Exception {
        var fixture = stored(); var before = counts();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            var admin = DSL.using(connection, SQLDialect.POSTGRES);
            // Simulates corruption in this isolated fixture DB, never a production write capability.
            if (variant.equals("base-body")) {
                var r = CATALOG_BOOTSTRAP_REVIEWS; var original = admin.select(r.REQUEST).from(r).where(r.ID.eq(fixture.base().reviewId())).fetchOne(r.REQUEST);
                var body = (ObjectNode) mapper.readTree(original.data()); ((ObjectNode) body.at("/candidate/options/0/facts/OIDC/evidence")).put("summary", "Corrupt claim");
                try { admin.update(r).set(r.REQUEST, JSONB.jsonb(body.toString())).where(r.ID.eq(fixture.base().reviewId())).execute();
                    assertEquals(CatalogBootstrapReviewException.Reason.READ_UNAVAILABLE, assertThrows(CatalogBootstrapReviewException.class, () -> evaluate(fixture.reference())).reason());
                } finally { admin.update(r).set(r.REQUEST, original).where(r.ID.eq(fixture.base().reviewId())).execute(); }
            } else if (variant.equals("audit-body")) {
                var r = CATALOG_AUDITABILITY_REVIEWS; var original = admin.select(r.REQUEST).from(r).where(r.ID.eq(fixture.audit().reviewId())).fetchOne(r.REQUEST);
                var body = (ObjectNode) mapper.readTree(original.data()); ((ObjectNode) body.at("/candidate/auditabilityDraft/options/0/facts/0/evidence")).put("summary", "Corrupt claim");
                try { admin.update(r).set(r.REQUEST, JSONB.jsonb(body.toString())).where(r.ID.eq(fixture.audit().reviewId())).execute();
                    assertEquals(CatalogAuditabilityReviewException.Reason.READ_UNAVAILABLE, assertThrows(CatalogAuditabilityReviewException.class, () -> evaluate(fixture.reference())).reason());
                } finally { admin.update(r).set(r.REQUEST, original).where(r.ID.eq(fixture.audit().reviewId())).execute(); }
            } else if (variant.equals("base-audit")) {
                var e = CATALOG_BOOTSTRAP_REVIEW_EVENTS;
                var original = admin.select(e.OCCURRED_AT, e.AUTHENTICATED_AT).from(e).where(e.REVIEW_ID.eq(fixture.base().reviewId())).fetchOne();
                try { admin.update(e).set(e.OCCURRED_AT, original.get(e.OCCURRED_AT).plusSeconds(60))
                            .set(e.AUTHENTICATED_AT, original.get(e.AUTHENTICATED_AT).plusSeconds(60)).where(e.REVIEW_ID.eq(fixture.base().reviewId())).execute();
                    assertEquals(CatalogBootstrapReviewException.Reason.READ_UNAVAILABLE, assertThrows(CatalogBootstrapReviewException.class, () -> evaluate(fixture.reference())).reason());
                } finally { admin.update(e).set(e.OCCURRED_AT, original.get(e.OCCURRED_AT))
                            .set(e.AUTHENTICATED_AT, original.get(e.AUTHENTICATED_AT)).where(e.REVIEW_ID.eq(fixture.base().reviewId())).execute(); }
            } else {
                var e = CATALOG_AUDITABILITY_REVIEW_EVENTS;
                var original = admin.select(e.OCCURRED_AT, e.AUTHENTICATED_AT).from(e).where(e.REVIEW_ID.eq(fixture.audit().reviewId())).fetchOne();
                try { admin.update(e).set(e.OCCURRED_AT, original.get(e.OCCURRED_AT).plusSeconds(60))
                            .set(e.AUTHENTICATED_AT, original.get(e.AUTHENTICATED_AT).plusSeconds(60)).where(e.REVIEW_ID.eq(fixture.audit().reviewId())).execute();
                    assertEquals(CatalogAuditabilityReviewException.Reason.READ_UNAVAILABLE, assertThrows(CatalogAuditabilityReviewException.class, () -> evaluate(fixture.reference())).reason());
                } finally { admin.update(e).set(e.OCCURRED_AT, original.get(e.OCCURRED_AT))
                            .set(e.AUTHENTICATED_AT, original.get(e.AUTHENTICATED_AT)).where(e.REVIEW_ID.eq(fixture.audit().reviewId())).execute(); }
            }
        }
        assertEquals(before, counts()); assertTrue(evaluate(fixture.reference()).storedReviewsVerified());
    }
}

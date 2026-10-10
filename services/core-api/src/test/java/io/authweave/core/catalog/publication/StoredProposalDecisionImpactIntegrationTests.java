package io.authweave.core.catalog.publication;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.catalog.auditability.*;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.impact.*;
import io.authweave.core.catalog.proposal.*;
import io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;
import static io.authweave.core.catalog.impact.CatalogProposalDecisionLoadingException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Fictional source assertions and publications in a disposable database only. No real sources,
 * IdP grants, local project writes or publication authority are exercised by the read adapter. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class StoredProposalDecisionImpactIntegrationTests {
    private static final String ID = "example-managed-eu";
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
    @Autowired DSLContext dsl;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CatalogBootstrapReviewRepository boundary;
    @Autowired CatalogBootstrapReviewService reviews;
    @Autowired CatalogAuditabilityReviewService audits;
    @Autowired CatalogAuditabilityDraftValidator auditDrafts;
    @Autowired CatalogPublicationPreflightRepository registry;
    @Autowired CatalogPublicationRepository publications;
    @Autowired CatalogBootstrapPublicationReader reader;
    @Autowired CatalogSnapshotInspector inspector;
    @Autowired DecisionPublicationCoverageService coverage;
    @Autowired CatalogChangePreviewService previews;
    @Autowired CatalogProposalRepository proposalRepository;
    @Autowired CatalogFactReviewWriter factReviews;
    @Autowired StoredProposalDecisionService proposals;
    @Autowired StoredProposalDecisionImpactService impacts;
    @MockitoSpyBean TrustedPublishedCatalogService trusted;
    @MockitoSpyBean Clock clock;

    @BeforeEach void cleanDisposableDatabase() throws Exception {
        sql("TRUNCATE core.catalog_bootstrap_reviews, core.catalog_auditability_reviews, core.catalog_proposals, core.catalog_publication_decisions CASCADE");
    }

    @Test void reviewedUnsupportedScimActuallyChangesShortlistHardChecksAndArchitectureWithoutWrites() {
        var root = publish(); var proposal = proposal(root, c -> ((ObjectNode) c.at("/options/0/facts/SCIM")).put("availability", "UNAVAILABLE"));
        reviewAll(proposal, Verdict.SOURCE_SUPPORTS_CLAIM);
        var pin = proposals.pin(revision(proposal), supplement(proposal)); var before = counts();
        var result = evaluate(root, pin);
        assertEquals(List.of(ID), result.impact().before().shortlist()); assertTrue(result.impact().after().shortlist().isEmpty());
        assertEquals("EXCLUDED", result.impact().after().candidates().getFirst().hardChecks().hardVerdict().name());
        assertTrue(result.impact().decisionOutcomesChanged()); assertTrue(result.impact().deltas().stream().anyMatch(d -> d.path().equals("/shortlist")));
        assertTrue(result.impact().deltas().stream().anyMatch(d -> d.path().startsWith("/architecture/")));
        assertEquals(DecisionCanonicalizer.sha256(profile()), result.impact().profileSha256());
        assertEquals(DecisionCanonicalizer.sha256(weights()), result.impact().weightsSha256());
        assertEquals(root.snapshot(), result.baseline()); assertEquals(root.proofSha256(), result.baselineProofSha256()); assertEquals(pin, result.proposal());
        assertTrue(result.historicalPublicationWorkflowVerified()); assertTrue(result.storedProposalReviewsVerified());
        assertFalse(result.currentCuratorAuthorityVerified()); assertFalse(result.sourceVerificationPerformed()); assertFalse(result.assessmentResultPinned());
        assertFalse(result.approvalGranted()); assertFalse(result.publicationReady()); assertFalse(result.writesPerformed()); assertFalse(result.impact().coverageComplete());
        assertFalse(result.impact().after().sourceAuthorityVerified()); assertFalse(result.impact().after().configurationVerified()); assertFalse(result.impact().after().complianceVerified());
        assertFalse(mapper.writeValueAsString(result).contains("fictional-proposal-curator")); assertEquals(before, counts());
    }

    @Test void noSupplementForNewCandidateNeverBorrowsPublishedSupplement() {
        var root = publish(); var proposal = proposal(root, c -> { }); reviewAll(proposal, Verdict.SOURCE_SUPPORTS_CLAIM);
        var pin = proposals.pin(revision(proposal), null); var result = evaluate(root, pin);
        assertEquals(List.of(ID), result.impact().before().shortlist()); assertTrue(result.impact().after().shortlist().isEmpty());
        assertTrue(result.impact().after().candidates().getFirst().hardChecks().findings().stream()
                .anyMatch(f -> "EVIDENCE_MISSING".equals(f.reasonCode()) && "auditabilitySupplement.AUDIT_LOG_RETENTION".equals(f.factPath())));
        assertNull(proposals.load(pin).snapshot().auditability());
        assertEquals(CatalogAuditabilityReviewException.Reason.CONFLICT, assertThrows(CatalogAuditabilityReviewException.class,
                () -> proposals.pin(revision(proposal), root.source().auditability())).reason());
    }

    @Test void preferredCapabilityUsesExplicitWeightsAndChangesScoreWithoutOverridingHardChecks() {
        var root = publish(); var proposal = proposal(root, c -> {
            var mfa = (ObjectNode) c.at("/options/0/facts/SCIM").deepCopy(); mfa.put("availability", "UNAVAILABLE");
            ((ObjectNode) c.at("/options/0/facts")).set("MFA", mfa);
        });
        reviewAll(proposal, Verdict.SOURCE_SUPPORTS_CLAIM); var pin = proposals.pin(revision(proposal), supplement(proposal));
        var profile = profile(); ((ObjectNode) profile.get("security")).put("multiFactorAuthentication", "PREFERRED");
        var weights = mapper.readTree("{\"mode\":\"EXPLICIT\",\"values\":[{\"capability\":\"MFA\",\"weight\":100}]}");
        var result = impacts.evaluate(profile, 6, weights, root.snapshot(), pin).impact();
        assertEquals(List.of(ID), result.before().shortlist()); assertEquals(List.of(ID), result.after().shortlist());
        // Baseline has no MFA claim: unknown preference is an interval, not invented support.
        assertEquals(0, result.before().candidates().getFirst().score().lowerBound());
        assertEquals(100, result.before().candidates().getFirst().score().upperBound());
        assertEquals(0, result.after().candidates().getFirst().score().lowerBound());
        assertEquals(0, result.after().candidates().getFirst().score().upperBound());
        assertTrue(result.decisionOutcomesChanged()); assertTrue(result.deltas().stream().anyMatch(d -> d.path().endsWith("/score")));
        assertEquals(DecisionCanonicalizer.sha256(weights), result.weightsSha256());
        assertThrows(IllegalArgumentException.class, () -> impacts.evaluate(profile, 6, weights(), root.snapshot(), pin));
    }

    @ParameterizedTest @ValueSource(strings = {"review-digest", "supplement-digest"})
    void optionalSupplementRequiresBothExactStoredPins(String field) {
        var root = publish(); var proposal = proposal(root, c -> { }); var audit = supplement(proposal);
        var wrong = new StoredCandidateDecisionService.AuditReference(audit.reviewId(), field.equals("review-digest") ? "0".repeat(64) : audit.reviewSha256(),
                field.equals("supplement-digest") ? "0".repeat(64) : audit.decisionSupplementSha256());
        assertEquals(CatalogAuditabilityReviewException.Reason.CONFLICT, assertThrows(CatalogAuditabilityReviewException.class,
                () -> proposals.pin(revision(proposal), wrong)).reason());
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING", "SOURCE_DOES_NOT_SUPPORT_CLAIM", "INSUFFICIENT_EVIDENCE", "STALE", "FUTURE"})
    void untrustedOrOutdatedScimStaysUnknownEvenIfTheClaimSaysUnsupported(String problem) {
        var root = publish();
        var proposal = proposal(root, c -> {
            ((ObjectNode) c.at("/options/0/facts/SCIM")).put("availability", "UNAVAILABLE");
            if (problem.equals("STALE") || problem.equals("FUTURE")) ((ObjectNode) c.at("/options/0/facts/SCIM/evidence")).put("observedAt",
                    (problem.equals("STALE") ? Instant.now().minusSeconds(91L * 86400) : Instant.now().plusSeconds(86400)).toString());
        });
        reviewAllExcept(proposal, "facts.SCIM");
        if (!problem.equals("MISSING")) review(proposal, "facts.SCIM", problem.equals("STALE") || problem.equals("FUTURE")
                ? Verdict.SOURCE_SUPPORTS_CLAIM : Verdict.valueOf(problem));
        var pin = proposals.pin(revision(proposal), supplement(proposal)); var result = evaluate(root, pin);
        assertEquals("UNRESOLVED", result.impact().after().candidates().getFirst().hardChecks().hardVerdict().name());
        var finding = result.impact().after().candidates().getFirst().hardChecks().findings().stream().filter(f -> "facts.SCIM".equals(f.factPath())).findFirst().orElseThrow();
        assertEquals("UNKNOWN", finding.outcome().name()); assertTrue(result.impact().after().shortlist().isEmpty());
    }

    @Test void newObservationChangesOnlyNewPinsAndHistoricalCutoffRemainsReplayable() {
        var root = publish(); var proposal = proposal(root, c -> { }); reviewAll(proposal, Verdict.SOURCE_SUPPORTS_CLAIM);
        var audit = supplement(proposal); var first = proposals.pin(revision(proposal), audit); var firstInputs = proposals.load(first);
        review(proposal, "facts.SCIM", Verdict.SOURCE_DOES_NOT_SUPPORT_CLAIM);
        var latest = proposals.pin(revision(proposal), audit); assertTrue(latest.reviewThroughNumber() > first.reviewThroughNumber());
        assertNotEquals(first.reviewSetSha256(), latest.reviewSetSha256());
        assertEquals(mapper.valueToTree(firstInputs), mapper.valueToTree(proposals.load(first)));
        assertEquals(List.of(ID), evaluate(root, first).impact().after().shortlist()); assertTrue(evaluate(root, latest).impact().after().shortlist().isEmpty());
        var oldScim = firstInputs.observations().stream().filter(o -> o.factPath().equals("facts.SCIM")).findFirst().orElseThrow();
        var newScim = proposals.load(latest).observations().stream().filter(o -> o.factPath().equals("facts.SCIM")).findFirst().orElseThrow();
        assertEquals(Verdict.SOURCE_SUPPORTS_CLAIM, oldScim.verdict()); assertEquals(Verdict.SOURCE_DOES_NOT_SUPPORT_CLAIM, newScim.verdict());
    }

    @Test void laterRevisionDoesNotReusePriorReviewsAndDoesNotInvalidateHistoricalCalculation() {
        var root = publish(); var proposal = proposal(root, c -> { }); reviewAll(proposal, Verdict.SOURCE_SUPPORTS_CLAIM);
        var old = proposals.pin(revision(proposal), supplement(proposal)); var before = proposals.load(old);
        var request = mapper.treeToValue(proposal.request(), CatalogChangePreviewRequest.class);
        var raw = (ObjectNode) mapper.valueToTree(request.candidate()); raw.put("catalogVersion", "fictional-proposal-v2");
        var revised = save(new CatalogChangePreviewRequest(1, request.proposalId(), "Fictional revised proposal", request.expectedBaseSha256(), request.base(),
                mapper.treeToValue(raw, ProviderCatalogDraft.class)), proposal.version());
        var next = proposals.pin(revision(revised), null);
        assertEquals(0, next.reviewThroughNumber()); assertTrue(proposals.load(next).observations().isEmpty());
        assertEquals(mapper.valueToTree(before), mapper.valueToTree(proposals.load(old))); assertEquals(List.of(ID), evaluate(root, old).impact().after().shortlist());
        assertTrue(evaluate(root, next).impact().after().shortlist().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"version", "legacy-digest", "ordered-digest", "reviews-digest", "cutoff"})
    void exactRevisionRequestReviewSetAndCutoffAreAllRequired(String field) {
        var root = publish(); var proposal = proposal(root, c -> { }); reviewAll(proposal, Verdict.SOURCE_SUPPORTS_CLAIM);
        var p = proposals.pin(revision(proposal), null); var r = p.revision();
        var wrongRevision = new StoredProposalDecisionService.Revision(r.proposalId(), field.equals("version") ? 1 : r.version(),
                field.equals("legacy-digest") ? "0".repeat(64) : r.proposalSha256(), field.equals("ordered-digest") ? "0".repeat(64) : r.requestSha256());
        var wrong = new StoredProposalDecisionService.Reference(wrongRevision, field.equals("cutoff") ? p.reviewThroughNumber() + 1 : p.reviewThroughNumber(),
                field.equals("reviews-digest") ? "0".repeat(64) : p.reviewSetSha256(), null);
        assertEquals(field.equals("version") ? PROPOSAL_NOT_FOUND : field.equals("legacy-digest") || field.equals("ordered-digest") ? REFERENCE_MISMATCH : REVIEW_SET_MISMATCH,
                assertThrows(CatalogProposalDecisionLoadingException.class, () -> proposals.load(wrong)).reason());
    }

    @Test void forgedBaseCanNeverBeComparedAsIfItWereThePublishedBaseline() {
        var root = publish(); var base = (ObjectNode) trusted.load(root.snapshot()).decisionInputs().catalog();
        base.put("catalogVersion", "unpublished-fictional-base");
        var typed = mapper.treeToValue(base, ProviderCatalogDraft.class); var candidate = base.deepCopy(); candidate.put("catalogVersion", "fictional-candidate");
        ((ArrayNode) candidate.at("/options/0/facts/SCIM/conditions")).add("Fictional changed candidate condition");
        var proposal = save(new CatalogChangePreviewRequest(1, UUID.randomUUID(), "Untrusted claimed base", CatalogDraftCanonicalizer.sha256(typed), typed,
                mapper.treeToValue(candidate, ProviderCatalogDraft.class)), null);
        var pin = proposals.pin(revision(proposal), null);
        assertEquals(BASELINE_MISMATCH, assertThrows(CatalogProposalDecisionLoadingException.class, () -> evaluate(root, pin)).reason());
    }

    @Test void legacyOrderInsensitiveHashCannotHideChangedRequestArrayOrder() throws Exception {
        var root = publish(); var proposal = proposal(root, c -> {
            var second = (ObjectNode) c.at("/options/0").deepCopy(); second.put("id", "fictional-second").put("configuration", "Second fictional configuration");
            ((ArrayNode) c.get("options")).add(second);
        });
        var pin = proposals.pin(revision(proposal), null); var changed = (ObjectNode) proposal.request();
        var options = (ArrayNode) changed.at("/candidate/options"); options.add(options.remove(0));
        assertEquals(proposal.proposalSha256(), CatalogDraftCanonicalizer.sha256(changed));
        updateRequest(proposal, changed);
        assertEquals(REFERENCE_MISMATCH, assertThrows(CatalogProposalDecisionLoadingException.class, () -> proposals.load(pin)).reason());
    }

    @ParameterizedTest @ValueSource(strings = {"proposal-audit-clock", "review-audit-clock", "valid-but-changed-actor", "request-body", "unknown-field"})
    void corruptedAuditOrRequestFailsClosedEvenWhenExistenceAndForeignKeysAreValid(String field) throws Exception {
        var root = publish(); var proposal = proposal(root, c -> { }); reviewAll(proposal, Verdict.SOURCE_SUPPORTS_CLAIM);
        var pin = proposals.pin(revision(proposal), null);
        switch (field) {
            case "proposal-audit-clock" -> sql("UPDATE audit.catalog_proposal_events SET occurred_at=occurred_at + interval '1 hour'");
            case "review-audit-clock" -> sql("UPDATE audit.catalog_fact_review_events SET occurred_at=occurred_at + interval '1 hour', authenticated_at=authenticated_at + interval '1 hour'");
            case "valid-but-changed-actor" -> sql("UPDATE audit.catalog_fact_review_events SET actor_subject='different-fictional-curator'");
            case "request-body" -> { var raw = (ObjectNode) proposal.request(); raw.put("rationale", "Changed fictional rationale"); updateRequest(proposal, raw); }
            case "unknown-field" -> { var raw = (ObjectNode) proposal.request(); raw.put("callerAuthority", true); updateRequest(proposal, raw); }
        }
        assertEquals(field.equals("valid-but-changed-actor") ? REVIEW_SET_MISMATCH : STORED_PROPOSAL_INVALID,
                assertThrows(CatalogProposalDecisionLoadingException.class, () -> proposals.load(pin)).reason());
    }

    @ParameterizedTest @ValueSource(strings = {"proposal", "review"})
    void missingMandatoryAuditCannotAuthenticateStoredObservations(String which) throws Exception {
        var root = publish(); var proposal = proposal(root, c -> { }); reviewAll(proposal, Verdict.SOURCE_SUPPORTS_CLAIM);
        var pin = proposals.pin(revision(proposal), null);
        if (which.equals("review")) {
            // Only this disposable DB: bypass deferred integrity to exercise fail-closed reads.
            try (var c = admin()) {
                c.setAutoCommit(false); try (var s = c.createStatement()) { s.execute("SET LOCAL session_replication_role=replica"); s.execute("DELETE FROM audit.catalog_fact_review_events"); }
                c.commit();
            }
        } else sql("DELETE FROM audit.catalog_proposal_events");
        assertEquals(STORED_PROPOSAL_INVALID, assertThrows(CatalogProposalDecisionLoadingException.class, () -> proposals.load(pin)).reason());
    }

    @Test void consistentReadOnlySnapshotAndServerClockAreUsedForBothSides() {
        var root = publish(); var proposal = proposal(root, c -> { }); reviewAll(proposal, Verdict.SOURCE_SUPPORTS_CLAIM);
        var pin = proposals.pin(revision(proposal), supplement(proposal)); var before = counts();
        doAnswer(call -> {
            assertEquals("on", dsl.fetchValue("SHOW transaction_read_only")); assertEquals("repeatable read", dsl.fetchValue("SHOW transaction_isolation"));
            return call.callRealMethod();
        }).when(trusted).load(any());
        var later = Instant.now().plusSeconds(91L * 86400); doReturn(later).when(clock).instant();
        var result = evaluate(root, pin); assertEquals(later, result.impact().evaluatedAt());
        assertTrue(result.impact().before().shortlist().isEmpty()); assertTrue(result.impact().after().shortlist().isEmpty());
        assertEquals(before, counts());
    }

    @Test void callerDocumentsAreCopiedBeforeDatabaseReadsAndLoadedJsonIsDefensive() {
        var root = publish(); var proposal = proposal(root, c -> { }); reviewAll(proposal, Verdict.SOURCE_SUPPORTS_CLAIM);
        var pin = proposals.pin(revision(proposal), supplement(proposal)); var profile = profile(); var weights = weights();
        var originalProfile = DecisionCanonicalizer.sha256(profile); var originalWeights = DecisionCanonicalizer.sha256(weights);
        var inputs = proposals.load(pin); var original = DecisionCanonicalizer.sha256(mapper.valueToTree(inputs));
        ((ObjectNode) inputs.base()).put("catalogVersion", "caller-changed-base");
        ((ObjectNode) inputs.snapshot().catalog()).put("catalogVersion", "caller-changed-candidate");
        ((ObjectNode) inputs.snapshot().auditability().supplement()).put("baseCatalogVersion", "caller-changed-supplement");
        assertEquals(original, DecisionCanonicalizer.sha256(mapper.valueToTree(inputs)));
        doAnswer(call -> {
            ((ObjectNode) profile.get("security")).put("auditability", "NOT_REQUIRED"); ((ObjectNode) weights).put("mode", "INVALID");
            return call.callRealMethod();
        }).when(trusted).load(any());
        var result = impacts.evaluate(profile, 6, weights, root.snapshot(), pin);
        assertEquals(originalProfile, result.impact().profileSha256()); assertEquals(originalWeights, result.impact().weightsSha256());
    }

    @Test void databaseOutagePropagatesWithoutSyntheticFallback() {
        var root = publish(); var proposal = proposal(root, c -> { });
        var pin = proposals.pin(revision(proposal), null);
        doThrow(new org.jooq.exception.DataAccessException("Fictional disposable DB outage")).when(trusted).load(any());
        assertThrows(org.jooq.exception.DataAccessException.class, () -> evaluate(root, pin));
    }

    @Test void oversizedAdministrativeRequestIsRejectedWithoutReadingItsBody() throws Exception {
        var root = publish(); var proposal = proposal(root, c -> { }); var pin = proposals.pin(revision(proposal), null);
        sql("UPDATE core.catalog_proposal_revisions SET request=jsonb_set(request,'{rationale}',to_jsonb(repeat('x',33554433)))");
        assertEquals(STORED_PROPOSAL_INVALID, assertThrows(CatalogProposalDecisionLoadingException.class, () -> proposals.load(pin)).reason());
    }

    @Test void callerCannotOmitAuditPinOrSupplyInvalidNumbersAndDigests() {
        var revision = new StoredProposalDecisionService.Revision(UUID.randomUUID(), 0, "0".repeat(64), "0".repeat(64));
        var pin = new StoredProposalDecisionService.Reference(revision, 0, "0".repeat(64), null);
        var raw = (ObjectNode) mapper.valueToTree(pin); raw.remove("auditability");
        assertThrows(tools.jackson.core.JacksonException.class, () -> mapper.treeToValue(raw, StoredProposalDecisionService.Reference.class));
        assertThrows(IllegalArgumentException.class, () -> new StoredProposalDecisionService.Reference(revision, -1, "0".repeat(64), null));
        assertThrows(IllegalArgumentException.class, () -> new StoredProposalDecisionService.Revision(UUID.randomUUID(), 9007199254740992L, "0".repeat(64), "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new StoredProposalDecisionService.Reference(revision, 0, "bad", null));
    }

    private CatalogBootstrapPublisher.Receipt publish() {
        var pin = new CandidateDecisionReviewFixture(mapper, reviews, audits, auditDrafts).stored().reference();
        var publisher = new CatalogBootstrapPublisher(dsl, boundary, reviews, registry, publications, reader, inspector, coverage, mapper);
        return new TransactionTemplate(transactions).execute(tx -> publisher.publish(new CatalogBootstrapPublicationRequest(1, UUID.randomUUID(), pin,
                CatalogBootstrapPublicationRequest.Confirmation.PUBLISH_REVIEWED_BOOTSTRAP), actor()).receipt());
    }
    private CatalogProposalSnapshot proposal(CatalogBootstrapPublisher.Receipt root, Consumer<ObjectNode> change) {
        var base = mapper.treeToValue(trusted.load(root.snapshot()).decisionInputs().catalog(), ProviderCatalogDraft.class);
        var raw = (ObjectNode) mapper.valueToTree(base); raw.put("catalogVersion", "fictional-reviewed-proposal");
        ((ArrayNode) raw.at("/options/0/facts/SCIM/conditions")).add("Fictional proposed configuration"); change.accept(raw);
        return save(new CatalogChangePreviewRequest(1, UUID.randomUUID(), "Fictional proposal for isolated impact tests", CatalogDraftCanonicalizer.sha256(base), base,
                mapper.treeToValue(raw, ProviderCatalogDraft.class)), null);
    }
    private CatalogProposalSnapshot save(CatalogChangePreviewRequest request, Long version) {
        var writer = new LocalCatalogProposalWriter(dsl, mapper, previews, proposalRepository);
        return new TransactionTemplate(transactions).execute(tx -> writer.save(request, version).proposal());
    }
    private StoredProposalDecisionService.Revision revision(CatalogProposalSnapshot p) {
        return new StoredProposalDecisionService.Revision(p.proposalId(), p.version(), p.proposalSha256(), DecisionCanonicalizer.sha256(p.request()));
    }
    private void reviewAll(CatalogProposalSnapshot p, Verdict verdict) {
        var request = mapper.treeToValue(p.request(), CatalogChangePreviewRequest.class);
        request.candidate().options().forEach(o -> CatalogDraftFacts.entries(o).keySet().stream().sorted().forEach(path -> review(p, o.id(), path, verdict)));
    }
    private void reviewAllExcept(CatalogProposalSnapshot p, String excluded) {
        var request = mapper.treeToValue(p.request(), CatalogChangePreviewRequest.class);
        request.candidate().options().forEach(o -> CatalogDraftFacts.entries(o).keySet().stream().filter(path -> !path.equals(excluded)).sorted()
                .forEach(path -> review(p, o.id(), path, Verdict.SOURCE_SUPPORTS_CLAIM)));
    }
    private void review(CatalogProposalSnapshot p, String path, Verdict verdict) { review(p, ID, path, verdict); }
    private void review(CatalogProposalSnapshot p, String id, String path, Verdict verdict) {
        factReviews.record(p.proposalId(), new CatalogFactReviewRequest(UUID.randomUUID(), p.version(), p.proposalSha256(), id, path, verdict,
                CatalogFactReviewRequest.Confirmation.MANUAL_SOURCE_REVIEW), actor());
    }
    private StoredCandidateDecisionService.AuditReference supplement(CatalogProposalSnapshot p) {
        var base = mapper.treeToValue(p.request().get("candidate"), ProviderCatalogDraft.class);
        var raw = (ObjectNode) mapper.readTree(Path.of("../../packages/contracts/tests/fixtures/catalog-auditability-draft.valid.json").toFile());
        dates(raw, Instant.now().minusSeconds(86400).toString());
        raw.put("baseContentSha256", CatalogDraftCanonicalizer.sha256(base)); raw.put("baseCatalogVersion", base.catalogVersion());
        var candidate = new CatalogAuditabilityDraftValidator.Request(base, mapper.treeToValue(raw, AuditabilityCatalogDraft.class)); var validation = auditDrafts.validate(candidate);
        var receipt = audits.record(new CatalogAuditabilityReviewRequest(1, UUID.randomUUID(), validation.baseValidation().contentSha256(), validation.contentSha256(),
                validation.reviewTargetSetSha256(), candidate, validation.targets().stream().map(t -> new CatalogAuditabilityReviewRequest.Observation(t.scope().optionId(),
                    t.fact().criterion(), t.targetSha256(), Verdict.SOURCE_SUPPORTS_CLAIM)).toList(), CatalogAuditabilityReviewRequest.Confirmation.MANUAL_AUDITABILITY_SOURCE_REVIEW), actor()).review();
        var saved = mapper.readTree(audits.loadForDecision(receipt.reviewId(), receipt.reviewSha256()).candidateJson()).get("auditabilityDraft");
        return new StoredCandidateDecisionService.AuditReference(receipt.reviewId(), receipt.reviewSha256(), DecisionCanonicalizer.sha256(saved));
    }
    private StoredProposalDecisionImpactService.Result evaluate(CatalogBootstrapPublisher.Receipt root, StoredProposalDecisionService.Reference pin) {
        return impacts.evaluate(profile(), 6, weights(), root.snapshot(), pin);
    }
    private ObjectNode profile() {
        var p = (ObjectNode) mapper.readTree(Path.of("../../packages/contracts/decision-core/catalog-case.b2b-browser-scim.v1.json").toFile()).get("profile");
        ((ObjectNode) p.get("audience")).set("populations", mapper.createArrayNode().add("PARTNERS"));
        ((ObjectNode) p.get("audience")).put("tenancy", "MULTI_TENANT_ORGANIZATIONS"); ((ObjectNode) p.at("/protocols/federation")).put("SAML", "NOT_REQUIRED");
        ((ObjectNode) p.get("security")).put("auditability", "REQUIRED"); var r = (ObjectNode) p.at("/security/auditabilityRequirements");
        r.set("selectedCriteria", mapper.createArrayNode().add("AUTHENTICATION_SUCCESS_EVENTS").add("AUDIT_LOG_RETENTION")); r.put("minimumRetentionDays", 30); return p;
    }
    private JsonNode weights() { return mapper.readTree("{\"mode\":\"NONE\",\"values\":[]}"); }
    private List<Integer> counts() {
        return List.of("core.catalog_fact_reviews", "audit.catalog_fact_review_events", "core.catalog_proposal_revisions", "audit.catalog_proposal_events",
                "core.catalog_auditability_reviews", "core.catalog_bootstrap_publications", "core.catalog_published_snapshots", "core.catalog_publication_decisions", "audit.catalog_publication_events")
                .stream().map(t -> dsl.fetchOne("select count(*) from " + t).get(0, Integer.class)).toList();
    }
    private void updateRequest(CatalogProposalSnapshot p, JsonNode raw) throws Exception {
        try (var c = admin(); var s = c.prepareStatement("UPDATE core.catalog_proposal_revisions SET request=?::jsonb WHERE proposal_id=? AND version=?")) {
            s.setString(1, raw.toString()); s.setObject(2, p.proposalId()); s.setLong(3, p.version()); s.executeUpdate();
        }
    }
    private void sql(String sql) throws Exception { try (var c = admin(); var s = c.createStatement()) { s.execute(sql); } }
    private java.sql.Connection admin() throws Exception { return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); }
    private static CuratorActor actor() { return new CuratorActor("https://identity.example.invalid", "fictional-proposal-curator", "123", "456", Instant.now()); }
    private void dates(JsonNode n, String at) {
        if (n.isObject() && n.has("observedAt")) ((ObjectNode) n).put("observedAt", at);
        if (n.isObject()) n.properties().forEach(e -> dates(e.getValue(), at)); else if (n.isArray()) n.forEach(c -> dates(c, at));
    }
}

package io.authweave.core.catalog.publication;

import java.net.URI;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
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
import io.authweave.core.catalog.ProviderCatalog;
import io.authweave.core.catalog.auditability.CatalogAuditabilityReviewService;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.impact.*;
import io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict;
import io.authweave.core.catalog.proposal.CatalogProposalRejectionWriter.CuratorActor;
import static io.authweave.core.catalog.publication.CatalogPublishedLoadingException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Default-profile reader in an isolated DB. Test setup alone creates fictional publications;
 * the running application has no publisher bean/route, real sources, grants or assessment writes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TrustedPublishedCatalogIntegrationTests {
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
    @Autowired ApplicationContext context;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CatalogBootstrapReviewRepository boundary;
    @Autowired CatalogBootstrapReviewService reviews;
    @Autowired CatalogAuditabilityReviewService audits;
    @Autowired CatalogPublicationPreflightRepository registry;
    @Autowired CatalogSnapshotInspector inspector;
    @Autowired CatalogAuditabilityDraftValidator auditDrafts;
    @Autowired DecisionPublicationCoverageService coverage;
    @Autowired CatalogPublicationLookup lookup;
    @Autowired CatalogBootstrapPublicationReader reader;
    @Autowired TrustedPublishedCatalogService trusted;
    @MockitoSpyBean CatalogPublicationRepository publications;
    @MockitoSpyBean Clock clock;

    @BeforeEach void emptyIsolatedRegistry() throws Exception {
        try (var c = admin(); var s = c.createStatement()) { s.execute("TRUNCATE core.catalog_bootstrap_reviews, core.catalog_publication_decisions CASCADE"); }
    }

    @Test void defaultReadOnlyLoaderUsesExactWorkflowProofWithoutEnablingPublisherOrLegacyAuthority() {
        var receipt = publish(false, false);
        assertTrue(context.getBeansOfType(CatalogBootstrapPublisher.class).isEmpty());
        assertTrue(context.getBeansOfType(CatalogBootstrapPublicationController.class).isEmpty());
        var before = counts();
        doAnswer(call -> {
            assertEquals("on", dsl.fetchValue("SHOW transaction_read_only"));
            assertEquals("repeatable read", dsl.fetchValue("SHOW transaction_isolation"));
            return call.callRealMethod();
        }).when(publications).find(any(UUID.class), anyLong());
        var inputs = trusted.load(receipt.snapshot()); var loaded = inputs.publication();
        assertEquals(receipt.snapshot(), loaded.reference()); assertEquals(receipt.source(), loaded.source());
        assertEquals(receipt.proofSha256(), loaded.proofSha256());
        assertEquals(receipt.policyVersion(), loaded.publicationPolicyVersion());
        assertEquals(receipt.coverageManifestSha256(), loaded.coverageManifestSha256());
        assertEquals(CatalogBootstrapPublicationReader.VERSION, loaded.loaderVersion());
        assertEquals(receipt.verificationGaps(), loaded.verificationGaps()); assertEquals(22, loaded.verificationGaps().size());
        assertTrue(loaded.historicalPublicationWorkflowVerified()); assertFalse(loaded.externalSourceVerificationPerformed());
        assertFalse(loaded.assessmentResultPinned()); assertFalse(lookup.lookup(receipt.snapshot()).baselineVerified());
        assertFalse(lookup.lookup(receipt.snapshot()).evaluationReady());
        assertEquals(receipt.source().decisionCatalogSha256(), DecisionCanonicalizer.sha256(inputs.decisionInputs().catalog()));
        assertEquals(counts(), before); assertNull(inputs.decisionInputs().auditability());
        assertEquals(loaded.snapshot(), reader.load(receipt.snapshot()).snapshot());
    }

    @Test void exactOptionalAuditabilityInputsAreLoadedWithoutAddingThemToBaseManifest() {
        var receipt = publish(true, false); var before = counts(); var inputs = trusted.load(receipt.snapshot());
        var source = receipt.source().auditability(); var supplement = inputs.decisionInputs().auditability();
        assertNotNull(source); assertNotNull(supplement);
        assertEquals(source.decisionSupplementSha256(), DecisionCanonicalizer.sha256(supplement.supplement()));
        assertFalse(mapper.valueToTree(inputs.publication().snapshot()).has("auditabilityDraft"));
        var result = calculate(inputs.decisionInputs(), receipt.publishedAt());
        assertEquals(List.of("example-managed-eu"), result.shortlist());
        assertFalse(result.sourceAuthorityVerified()); assertFalse(result.configurationVerified());
        assertFalse(result.complianceVerified()); assertFalse(result.publicationReady()); assertFalse(result.writesPerformed());
        assertEquals(before, counts());
    }

    @Test void laterLoadingVerifiesHistoricalPublicationButNeverRenewsBaseOrSupplementEvidence() {
        var receipt = publish(true, false); var first = trusted.load(receipt.snapshot()); var before = counts();
        var later = receipt.publishedAt().plusSeconds(91L * 86400);
        doReturn(later).when(clock).instant();
        var loaded = trusted.load(receipt.snapshot());
        assertEquals(first.publication().snapshot(), loaded.publication().snapshot());
        assertEquals(DecisionCanonicalizer.sha256(mapper.valueToTree(first.decisionInputs())),
                DecisionCanonicalizer.sha256(mapper.valueToTree(loaded.decisionInputs())));
        var current = calculate(loaded.decisionInputs(), clock.instant());
        assertTrue(current.shortlist().isEmpty());
        var findings = current.candidates().getFirst().hardChecks().findings();
        assertTrue(findings.stream().anyMatch(f -> "EVIDENCE_STALE".equals(f.reasonCode()) && "facts.SCIM".equals(f.factPath())));
        assertTrue(findings.stream().anyMatch(f -> "EVIDENCE_STALE".equals(f.reasonCode()) && f.factPath().startsWith("auditabilitySupplement.")));
        assertEquals(before, counts());
    }

    @Test void missingOptionalAuditabilityRemainsUnknownAndNeverUsesSyntheticFacts() {
        var receipt = publish(false, false); var inputs = trusted.load(receipt.snapshot());
        var result = calculate(inputs.decisionInputs(), receipt.publishedAt());
        assertNull(inputs.decisionInputs().auditability()); assertTrue(result.shortlist().isEmpty());
        assertTrue(result.candidates().getFirst().hardChecks().findings().stream()
                .anyMatch(f -> "EVIDENCE_MISSING".equals(f.reasonCode()) && "auditabilitySupplement.AUDIT_LOG_RETENTION".equals(f.factPath())));
    }

    @Test void returnedDecisionDocumentsAreDefensiveCopies() {
        var receipt = publish(true, false); var inputs = trusted.load(receipt.snapshot());
        var before = DecisionCanonicalizer.sha256(mapper.valueToTree(inputs.decisionInputs()));
        ((ObjectNode) inputs.decisionInputs().catalog().at("/options/0/facts/SCIM")).put("availability", "UNAVAILABLE");
        ((ObjectNode) inputs.decisionInputs().auditability().supplement().at("/options/0/facts/0")).put("support", "UNSUPPORTED");
        assertEquals(before, DecisionCanonicalizer.sha256(mapper.valueToTree(inputs.decisionInputs())));
        assertEquals(before, DecisionCanonicalizer.sha256(mapper.valueToTree(trusted.load(receipt.snapshot()).decisionInputs())));
    }

    @ParameterizedTest @ValueSource(strings = {"id", "version", "digest"})
    void callerMustSupplyAllThreeExactSnapshotBindings(String field) {
        var receipt = publish(false, false); var r = receipt.snapshot(); var before = counts();
        var wrong = new PublishedCatalogSnapshot.Reference(field.equals("id") ? UUID.randomUUID() : r.snapshotId(),
                field.equals("version") ? "different-version" : r.catalogVersion(), field.equals("digest") ? "0".repeat(64) : r.snapshotSha256());
        assertEquals(field.equals("id") ? PUBLICATION_PROOF_NOT_FOUND : REFERENCE_MISMATCH,
                assertThrows(CatalogPublishedLoadingException.class, () -> trusted.load(wrong)).reason());
        assertEquals(before, counts());
    }

    @Test void formatValidLegacyManifestWithoutWorkflowProofIsNeverTrusted() throws Exception {
        var receipt = publish(false, false);
        try (var c = admin(); var s = c.createStatement()) { s.execute("DELETE FROM core.catalog_bootstrap_publications"); }
        assertTrue(lookup.lookup(receipt.snapshot()).storedIntegrityValidated());
        assertEquals(PUBLICATION_PROOF_NOT_FOUND, assertThrows(CatalogPublishedLoadingException.class, () -> trusted.load(receipt.snapshot())).reason());
    }

    @ParameterizedTest @ValueSource(strings = {"digest", "unknown-count", "manifest", "result", "component", "missing-audit-pin", "source-order-pin"})
    void wellChecksummedButUnreplayableProofNeverReleasesTrustedInputs(String change) throws Exception {
        var receipt = publish(false, false); var proof = proof();
        switch (change) {
            case "digest" -> { }
            case "unknown-count" -> { var s = (ObjectNode) proof.at("/coverage/scenarios/0"); s.put("afterUnknownFindings", s.get("afterUnknownFindings").asInt() + 1); }
            case "manifest" -> ((ObjectNode) proof.get("coverage")).put("manifestSha256", "0".repeat(64));
            case "result" -> ((ObjectNode) proof.at("/coverage/scenarios/0")).put("afterResultSha256", "0".repeat(64));
            case "component" -> ((ObjectNode) proof.at("/coverage/componentVersions")).put("hardChecks", "unsupported-rule-version");
            case "missing-audit-pin" -> ((ObjectNode) proof.at("/request/source")).remove("auditability");
            case "source-order-pin" -> ((ObjectNode) proof.at("/request/source")).put("decisionCatalogSha256", "0".repeat(64));
        }
        if (List.of("missing-audit-pin", "source-order-pin").contains(change)) {
            ((ObjectNode) proof.get("coverage")).set("before", proof.at("/request/source").deepCopy());
            ((ObjectNode) proof.get("coverage")).set("after", proof.at("/request/source").deepCopy());
        }
        proof.put("requestSha256", DecisionCanonicalizer.sha256(proof.get("request")));
        try (var c = admin(); var s = c.prepareStatement("UPDATE core.catalog_bootstrap_publications SET proof=?::jsonb, proof_sha256=?, request_sha256=? WHERE id=?")) {
            s.setString(1, proof.toString()); s.setString(2, change.equals("digest") ? "0".repeat(64) : DecisionCanonicalizer.sha256(proof));
            s.setString(3, proof.get("requestSha256").asText()); s.setObject(4, receipt.snapshot().snapshotId()); s.executeUpdate();
        }
        invalid(receipt.snapshot());
    }

    @Test void sourceArrayOrderIsBoundEvenWhenLegacyCanonicalReviewDigestIsUnchanged() throws Exception {
        var receipt = publish(false, true);
        var request = (ObjectNode) mapper.readTree(dsl.fetchOne("select request::text from core.catalog_bootstrap_reviews").get(0, String.class));
        var original = CatalogDraftCanonicalizer.sha256(request); var options = (ArrayNode) request.at("/candidate/options");
        var first = options.remove(0); options.add(first);
        assertEquals(original, CatalogDraftCanonicalizer.sha256(request));
        try (var c = admin(); var s = c.prepareStatement("UPDATE core.catalog_bootstrap_reviews SET request=?::jsonb")) {
            s.setString(1, request.toString()); s.executeUpdate();
        }
        invalid(receipt.snapshot());
    }

    @ParameterizedTest @ValueSource(strings = {"base-audit-clock", "supplement-audit-clock", "publication-audit-clock", "base-review-digest", "supplement-review-digest", "manifest", "proof-clock"})
    void damagedSourceAuditsManifestOrTemporalBindingsFailClosed(String change) throws Exception {
        var receipt = publish(true, false);
        var sql = switch (change) {
            case "base-audit-clock" -> "UPDATE audit.catalog_bootstrap_review_events SET occurred_at=occurred_at + interval '1 hour', authenticated_at=authenticated_at + interval '1 hour'";
            case "supplement-audit-clock" -> "UPDATE audit.catalog_auditability_review_events SET occurred_at=occurred_at + interval '1 hour', authenticated_at=authenticated_at + interval '1 hour'";
            case "publication-audit-clock" -> "UPDATE audit.catalog_publication_events SET occurred_at=occurred_at + interval '1 hour', authenticated_at=authenticated_at + interval '1 hour'";
            case "base-review-digest" -> "UPDATE core.catalog_bootstrap_reviews SET request=jsonb_set(request,'{candidate,options,0,product}','\"Altered fictional source\"')";
            case "supplement-review-digest" -> "UPDATE core.catalog_auditability_reviews SET request=jsonb_set(request,'{candidate,auditabilityDraft,options,0,facts,0,support}','\"UNSUPPORTED\"')";
            case "manifest" -> "UPDATE core.catalog_published_snapshots SET manifest=jsonb_set(manifest,'{catalog,options,0,product}','\"Altered fictional manifest\"')";
            case "proof-clock" -> "UPDATE core.catalog_bootstrap_publications SET recorded_at=recorded_at + interval '1 hour'";
            default -> throw new AssertionError();
        };
        try (var c = admin(); var s = c.createStatement()) { s.execute(sql); }
        invalid(receipt.snapshot());
    }

    @Test void databaseOutagesPropagateInsteadOfBecomingMissingProofsOrSyntheticFallbacks() {
        var receipt = publish(false, false);
        doThrow(new org.jooq.exception.DataAccessException("Fictional isolated DB outage")).when(publications).find(any(UUID.class), anyLong());
        assertThrows(org.jooq.exception.DataAccessException.class, () -> trusted.load(receipt.snapshot()));
    }

    @Test void nullProofBodyIsRejectedBeforeRegistryRead() {
        var row = new io.authweave.core.generated.jooq.tables.records.CatalogBootstrapPublicationsRecord();
        assertEquals(STORED_PUBLICATION_INVALID, assertThrows(CatalogPublishedLoadingException.class, () -> reader.verified(row)).reason());
        row.setProof(org.jooq.JSONB.jsonb("null"));
        assertEquals(STORED_PUBLICATION_INVALID, assertThrows(CatalogPublishedLoadingException.class, () -> reader.verified(row)).reason());
        verify(publications, never()).find(any(UUID.class), anyLong());
    }

    @Test void oversizedAdministrativeProofNeverCrossesTheBoundedRead() throws Exception {
        var receipt = publish(false, false);
        var definition = dsl.fetchOne("select pg_get_constraintdef(oid) from pg_constraint where conrelid='core.catalog_bootstrap_publications'::regclass and conname='catalog_bootstrap_publication_proof_ck'").get(0, String.class);
        // Only the disposable test database. Restore the size constraint even if the assertion fails.
        try {
            try (var c = admin(); var s = c.createStatement()) {
                s.execute("ALTER TABLE core.catalog_bootstrap_publications DROP CONSTRAINT catalog_bootstrap_publication_proof_ck");
                s.execute("UPDATE core.catalog_bootstrap_publications SET proof=jsonb_set(proof,'{coverage,oversizedTest}',to_jsonb(repeat('x',33554433)))");
            }
            assertNull(reader.stored(receipt.snapshot().snapshotId()).getProof());
            invalid(receipt.snapshot());
        } finally {
            try (var c = admin(); var s = c.createStatement()) {
                s.execute("TRUNCATE core.catalog_publication_decisions CASCADE");
                s.execute("ALTER TABLE core.catalog_bootstrap_publications ADD CONSTRAINT catalog_bootstrap_publication_proof_ck " + definition);
            }
        }
    }

    @Test void separateLoadingDoesNotRelaxSyntheticCatalogKindOrSourceUrlGuard() {
        assertEquals(List.of(ProviderCatalog.Kind.SYNTHETIC), List.of(ProviderCatalog.Kind.values()));
        assertThrows(IllegalArgumentException.class, () -> new ProviderCatalog.Fact(ProviderCatalog.Availability.OPTIONAL,
                ProviderCatalog.EvidenceStatus.REVIEWED, URI.create("https://example.com/fictional-test-only"), Instant.now()));
    }

    private CatalogBootstrapPublisher.Receipt publish(boolean supplement, boolean twoOptions) {
        StoredCandidateDecisionService.Reference pin;
        if (supplement) pin = new CandidateDecisionReviewFixture(mapper, reviews, audits, auditDrafts).stored().reference();
        else {
            var raw = (ObjectNode) mapper.readTree(Path.of("../../packages/contracts/tests/fixtures/provider-catalog-draft.valid.json").toFile());
            dates(raw, Instant.now().minusSeconds(86400).toString());
            if (twoOptions) { var extra = (ObjectNode) raw.at("/options/0").deepCopy(); extra.put("id", "example-second").put("configuration", "Isolated second fictional configuration"); ((ArrayNode) raw.get("options")).add(extra); }
            var draft = mapper.treeToValue(raw, ProviderCatalogDraft.class);
            var observations = draft.options().stream().flatMap(o -> CatalogDraftFacts.entries(o).keySet().stream()
                    .map(p -> new CatalogBootstrapReviewRequest.Observation(o.id(), p, Verdict.SOURCE_SUPPORTS_CLAIM))).toList();
            var review = reviews.record(new CatalogBootstrapReviewRequest(1, UUID.randomUUID(), CatalogDraftCanonicalizer.sha256(draft), draft,
                    observations, CatalogBootstrapReviewRequest.Confirmation.MANUAL_BOOTSTRAP_SOURCE_REVIEW), actor()).review();
            var saved = mapper.readTree(reviews.loadForDecision(review.reviewId(), review.reviewSha256()).candidateJson());
            pin = new StoredCandidateDecisionService.Reference(review.reviewId(), review.reviewSha256(), DecisionCanonicalizer.sha256(saved), null);
        }
        // Explicit test-only writer: no application bean, HTTP route or production profile is enabled.
        var writer = new CatalogBootstrapPublisher(dsl, boundary, reviews, registry, publications, reader, inspector, coverage, mapper);
        var request = new CatalogBootstrapPublicationRequest(1, UUID.randomUUID(), pin, CatalogBootstrapPublicationRequest.Confirmation.PUBLISH_REVIEWED_BOOTSTRAP);
        return new TransactionTemplate(transactions).execute(tx -> writer.publish(request, actor()).receipt());
    }
    private ObjectNode proof() { return (ObjectNode) mapper.readTree(dsl.fetchOne("select proof::text from core.catalog_bootstrap_publications").get(0, String.class)); }
    private void invalid(PublishedCatalogSnapshot.Reference reference) {
        var before = counts();
        assertEquals(STORED_PUBLICATION_INVALID, assertThrows(CatalogPublishedLoadingException.class, () -> trusted.load(reference)).reason());
        assertEquals(before, counts());
    }
    private List<Integer> counts() {
        return List.of("core.catalog_bootstrap_publications", "core.catalog_published_snapshots", "core.catalog_publication_decisions", "audit.catalog_publication_events")
                .stream().map(t -> dsl.fetchOne("select count(*) from " + t).get(0, Integer.class)).toList();
    }
    private java.sql.Connection admin() throws Exception { return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); }
    private static CuratorActor actor() { return new CuratorActor("https://identity.example.invalid", "fictional-loader-curator", "123", "456", Instant.now()); }
    private void dates(JsonNode n, String at) {
        if (n.isObject() && n.has("observedAt")) ((ObjectNode) n).put("observedAt", at);
        if (n.isObject()) n.properties().forEach(e -> dates(e.getValue(), at)); else if (n.isArray()) n.forEach(child -> dates(child, at));
    }
    private CandidateDecisionEvaluator.Result calculate(CandidateDecisionImpactEvaluator.Snapshot input, Instant at) {
        var profile = (ObjectNode) mapper.readTree(Path.of("../../packages/contracts/decision-core/catalog-case.b2b-browser-scim.v1.json").toFile()).get("profile");
        ((ObjectNode) profile.get("audience")).set("populations", mapper.createArrayNode().add("PARTNERS"));
        ((ObjectNode) profile.get("audience")).put("tenancy", "MULTI_TENANT_ORGANIZATIONS");
        ((ObjectNode) profile.at("/protocols/federation")).put("SAML", "NOT_REQUIRED");
        ((ObjectNode) profile.get("security")).put("auditability", "REQUIRED");
        var requirements = (ObjectNode) profile.at("/security/auditabilityRequirements");
        requirements.set("selectedCriteria", mapper.createArrayNode().add("AUTHENTICATION_SUCCESS_EVENTS").add("AUDIT_LOG_RETENTION"));
        requirements.put("minimumRetentionDays", 30);
        return CandidateDecisionEvaluator.evaluate(profile, 6, input.catalog(), input.assertions(), input.auditability(), mapper.readTree("{\"mode\":\"NONE\",\"values\":[]}"), at);
    }
}

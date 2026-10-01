package io.authweave.core.catalog.publication;

import java.time.Instant;
import java.sql.DriverManager;
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
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.impact.LocalCatalogImpactWriter;
import io.authweave.core.catalog.impact.CatalogImpactReport;
import io.authweave.core.catalog.impact.CatalogImpactReportRepository;
import io.authweave.core.catalog.impact.CatalogFactPathRegressionService;
import io.authweave.core.catalog.impact.CatalogFactPathReportRepository;
import io.authweave.core.catalog.impact.LocalCatalogFactPathReportWriter;
import io.authweave.core.catalog.impact.CatalogBootstrapImpactService;
import io.authweave.core.catalog.impact.CatalogProfileImpactCoverageService;
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
import static io.authweave.core.generated.jooq.tables.CatalogImpactReports.CATALOG_IMPACT_REPORTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"local-catalog-write", "local-catalog-impact-write", "local-catalog-regression-write"})
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
    @Autowired private LocalCatalogImpactWriter impactWriter;
    @Autowired private CatalogImpactReportRepository impacts;
    @Autowired private LocalCatalogFactPathReportWriter regressionWriter;
    @MockitoSpyBean private CatalogFactPathReportRepository regressions;
    @MockitoSpyBean private CatalogPublicationPreflightRepository repository;
    @MockitoSpyBean private CatalogBootstrapReviewService bootstrapReviews;
    @MockitoSpyBean private CatalogBootstrapImpactReportRepository bootstrapReports;

    @Test
    void emptyBootstrapIsReadOnlyRepeatableReadAndDoesNotPublishOrSeed() {
        var candidate = new CatalogPublicationLookupFixtures(mapper).root(Instant.now()).snapshot().catalog().asDraft();
        doAnswer(call -> { transaction(); return call.callRealMethod(); }).when(repository).registryEmpty();
        var result = preflight.bootstrap(candidate);
        assertFalse(result.blockers().contains(BOOTSTRAP_REGISTRY_NOT_EMPTY));
        assertTrue(result.blockers().contains(BOOTSTRAP_REVIEW_WORKFLOW_UNAVAILABLE));
        assertEquals(9, result.facts().unobserved()); assertFalse(result.publicationReady()); registryStillEmpty();
        assertEquals(CatalogProfileImpactCoverageService.Status.NOT_CHECKED, result.profileImpactCoverage().status());
        assertEquals(io.authweave.core.catalog.impact.CatalogArchitectureImpactService.CheckStatus.NOT_CHECKED,
                result.profileImpactCoverage().architectureImpact().status());
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

    @Test
    void latestReceiptAuditAndAllResultsAreVerifiedWithinTheSameReadOnlySnapshotWithoutPromotingCoverage() {
        var fixtures = new CatalogPublicationLookupFixtures(mapper); var node = fixtures.child(fixtures.root(Instant.now()));
        var saved = writer.save(node.request(), null).proposal();
        var report = impactWriter.save(UUID.randomUUID(), saved.proposalId(), saved.version()).report();
        var regression = regressionWriter.save(UUID.randomUUID(), saved.proposalId(), saved.version()).report();
        doAnswer(call -> { transaction(); return call.callRealMethod(); }).when(repository).latestImpact(any(UUID.class), anyLong());
        doAnswer(call -> { transaction(); return call.callRealMethod(); }).when(regressions).latest(any(UUID.class), anyLong());
        int count = dsl.fetchCount(CATALOG_IMPACT_REPORTS);
        var result = preflight.proposal(saved.proposalId(), saved.version(), saved.proposalSha256(), null);
        assertEquals(CatalogPublicationImpactVerifier.Status.VERIFIED_PARTIAL_ANALYSIS, result.impact().status());
        assertTrue(result.impact().storedIntegrityValidated()); assertTrue(result.impact().historicalReplayVerified());
        assertEquals(report.reportId(), result.impact().reportId()); assertEquals(report.reportNumber(), result.impact().reportNumber());
        assertEquals(report.reportSha256(), result.impact().reportSha256()); assertEquals(3, result.impact().scenarioCount());
        assertEquals(0, result.impact().uncoveredChangeCount()); assertEquals(7, result.impact().deferredPathCount());
        assertEquals(CatalogFactPathRegressionService.Status.ANALYZED, result.factPaths().status());
        assertEquals(result.evaluatedAt(), result.factPaths().evaluatedAt()); assertEquals(68, result.factPaths().declaredFactPaths());
        assertEquals(1, result.factPaths().checkedCases()); assertEquals(1, result.factPaths().changedFacts());
        assertTrue(result.factPaths().changedFactPathsCovered()); assertFalse(result.factPaths().coverageComplete());
        assertFalse(result.factPaths().storedReportVerified());
        assertEquals(CatalogPublicationFactPathVerifier.Status.VERIFIED_FACT_PATH_ANALYSIS, result.storedFactPaths().status());
        assertTrue(result.storedFactPaths().storedIntegrityValidated()); assertTrue(result.storedFactPaths().historicalReplayVerified());
        assertTrue(result.storedFactPaths().changedFactPathsCovered()); assertFalse(result.storedFactPaths().coverageComplete());
        assertEquals(regression.reportId(), result.storedFactPaths().reportId());
        assertEquals(regression.reportSha256(), result.storedFactPaths().reportSha256());
        assertEquals(Instant.parse(regression.report().get("evaluatedAt").asText()), result.storedFactPaths().evaluatedAt());
        assertFalse(result.blockers().contains(FACT_PATH_RECEIPT_MISSING));
        assertTrue(result.blockers().contains(IMPACT_COVERAGE_INCOMPLETE)); assertFalse(result.coverageComplete());
        assertEquals(CatalogProfileImpactCoverageService.Status.INCOMPLETE, result.profileImpactCoverage().status());
        assertEquals(result.evaluatedAt(), result.profileImpactCoverage().evaluatedAt()); assertEquals(128, result.profileImpactCoverage().dimensions().size());
        assertTrue(result.profileImpactCoverage().unexercisedFactPaths().isEmpty());
        var architecture = result.profileImpactCoverage().architectureImpact();
        assertEquals(result.evaluatedAt(), architecture.evaluatedAt()); assertEquals(20, architecture.checkedPatterns());
        assertEquals(result.profileImpactCoverage().scenarioSetSha256(), architecture.scenarioSetSha256());
        assertFalse(architecture.configurationVerified()); assertFalse(architecture.providerCompatibilityVerified()); assertFalse(architecture.storedReportVerified());
        assertEquals(result.evaluatedAt(), result.scopedProfileImpact().evaluatedAt()); assertEquals(4, result.scopedProfileImpact().checkedScenarios());
        assertEquals(result.profileImpactCoverage().scenarioSetSha256(), result.scopedProfileImpact().scenarioSetSha256());
        assertEquals(saved.proposalId(), result.scopedProfileImpact().inputId()); assertEquals(saved.proposalSha256(), result.scopedProfileImpact().inputSha256());
        assertFalse(result.blockers().contains(IMPACT_RECEIPT_MISSING)); assertFalse(result.approvalGranted());
        assertEquals(count, dsl.fetchCount(CATALOG_IMPACT_REPORTS)); assertEquals(report, impacts.get(saved.proposalId(), saved.version(), report.reportId()));
        registryStillEmpty();
    }

    @Test
    void oversizedReadIsWithheldOnTheServerAndNewRevisionCannotReuseAnOlderReceipt() {
        var fixtures = new CatalogPublicationLookupFixtures(mapper); var node = fixtures.child(fixtures.root(Instant.now()));
        var saved = writer.save(node.request(), null).proposal();
        var report = impactWriter.save(UUID.randomUUID(), saved.proposalId(), saved.version()).report();
        var row = repository.latestImpact(saved.proposalId(), saved.version());
        assertNotNull(row.report()); assertEquals(report.reportId(), row.id()); assertNotNull(row.event()); assertTrue(row.reportBytes() > 0);
        var bounded = repository.latestImpact(saved.proposalId(), saved.version(), row.reportBytes() - 1);
        assertNull(bounded.report()); assertEquals(row.reportBytes(), bounded.reportBytes());
        assertNotNull(repository.latestImpact(saved.proposalId(), saved.version(), row.reportBytes()).report());
        var request = node.request();
        var next = writer.save(new CatalogChangePreviewRequest(1, request.proposalId(), request.rationale() + " revised",
                request.expectedBaseSha256(), request.base(), request.candidate()), saved.version()).proposal();
        var result = preflight.proposal(next.proposalId(), next.version(), next.proposalSha256(), null);
        assertEquals(CatalogPublicationImpactVerifier.Status.MISSING, result.impact().status());
        assertTrue(result.blockers().contains(IMPACT_RECEIPT_MISSING)); assertNull(result.impact().reportId());
        assertEquals(report, impacts.get(saved.proposalId(), saved.version(), report.reportId())); registryStillEmpty();
    }

    @Test
    void selfConsistentAdminTamperOfNewestReportDoesNotFallBackToOlderValidReportOrOverwriteHistory() throws Exception {
        var fixtures = new CatalogPublicationLookupFixtures(mapper); var node = fixtures.child(fixtures.root(Instant.now()));
        var saved = writer.save(node.request(), null).proposal();
        var first = impactWriter.save(UUID.randomUUID(), saved.proposalId(), saved.version()).report();
        var last = impactWriter.save(UUID.randomUUID(), saved.proposalId(), saved.version()).report();
        var body = (tools.jackson.databind.node.ObjectNode) last.report();
        ((tools.jackson.databind.node.ArrayNode) body.at("/scenarios/0/after/checks")).remove(0);
        try {
            replace(last, mapper.writeValueAsString(body), CatalogDraftCanonicalizer.sha256(body));
            var result = preflight.proposal(saved.proposalId(), saved.version(), saved.proposalSha256(), null);
            assertEquals(CatalogPublicationImpactVerifier.Status.REPLAY_MISMATCH, result.impact().status());
            assertTrue(result.blockers().contains(IMPACT_REPLAY_MISMATCH)); assertNull(result.impact().reportId());
            assertEquals(0, result.impact().scenarioCount()); assertFalse(result.publicationReady());
            assertEquals(last.reportId(), repository.latestImpact(saved.proposalId(), saved.version()).id());
            assertEquals(first, impacts.get(saved.proposalId(), saved.version(), first.reportId())); registryStillEmpty();
        } finally { replace(last, mapper.writeValueAsString(last.report()), last.reportSha256()); }
        assertEquals(CatalogPublicationImpactVerifier.Status.VERIFIED_PARTIAL_ANALYSIS,
                preflight.proposal(saved.proposalId(), saved.version(), saved.proposalSha256(), null).impact().status());
    }

    @Test
    void auditTimestampTamperBlocksTheReceiptWithoutRefreshingOrDeletingStoredReports() throws Exception {
        var fixtures = new CatalogPublicationLookupFixtures(mapper); var node = fixtures.child(fixtures.root(Instant.now()));
        var saved = writer.save(node.request(), null).proposal();
        var report = impactWriter.save(UUID.randomUUID(), saved.proposalId(), saved.version()).report();
        try (var admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (var alter = admin.prepareStatement("UPDATE audit.catalog_impact_report_events SET occurred_at = occurred_at + INTERVAL '1 hour' WHERE report_id = ?")) {
                alter.setObject(1, report.reportId()); alter.executeUpdate();
            }
            try {
                var result = preflight.proposal(saved.proposalId(), saved.version(), saved.proposalSha256(), null);
                assertEquals(CatalogPublicationImpactVerifier.Status.INVALID_RECEIPT, result.impact().status());
                assertTrue(result.blockers().contains(IMPACT_RECEIPT_INVALID)); assertNull(result.impact().reportId());
                assertEquals(report, impacts.get(saved.proposalId(), saved.version(), report.reportId())); registryStillEmpty();
            } finally {
                try (var restore = admin.prepareStatement("UPDATE audit.catalog_impact_report_events SET occurred_at = occurred_at - INTERVAL '1 hour' WHERE report_id = ?")) {
                    restore.setObject(1, report.reportId()); restore.executeUpdate();
                }
            }
        }
    }

    @Test
    void storedFactPathReceiptCannotFallbackAcrossRevisionOrToAnOlderUntamperedRun() throws Exception {
        var fixtures = new CatalogPublicationLookupFixtures(mapper); var node = fixtures.child(fixtures.root(Instant.now()));
        var saved = writer.save(node.request(), null).proposal();
        var missing = preflight.proposal(saved.proposalId(), saved.version(), saved.proposalSha256(), null);
        assertEquals(CatalogPublicationFactPathVerifier.Status.MISSING, missing.storedFactPaths().status());
        assertTrue(missing.blockers().contains(FACT_PATH_RECEIPT_MISSING));
        var first = regressionWriter.save(UUID.randomUUID(), saved.proposalId(), saved.version()).report();
        var last = regressionWriter.save(UUID.randomUUID(), saved.proposalId(), saved.version()).report();
        var body = (tools.jackson.databind.node.ObjectNode) last.report();
        ((tools.jackson.databind.node.ArrayNode) body.get("cases")).remove(0);
        try {
            replace(last, mapper.writeValueAsString(body), CatalogDraftCanonicalizer.sha256(body), "catalog_fact_path_reports", "catalog_fact_path_report_events");
            var invalid = preflight.proposal(saved.proposalId(), saved.version(), saved.proposalSha256(), null);
            assertEquals(CatalogPublicationFactPathVerifier.Status.REPLAY_MISMATCH, invalid.storedFactPaths().status());
            assertTrue(invalid.blockers().contains(FACT_PATH_REPLAY_MISMATCH)); assertNull(invalid.storedFactPaths().reportId());
            assertEquals(0, invalid.storedFactPaths().checkedCases()); assertFalse(invalid.publicationReady());
            assertEquals(last.reportId(), regressions.latest(saved.proposalId(), saved.version()).id());
            assertEquals(first, regressions.get(saved.proposalId(), saved.version(), first.reportId()));
        } finally { replace(last, mapper.writeValueAsString(last.report()), last.reportSha256(), "catalog_fact_path_reports", "catalog_fact_path_report_events"); }
        var original = preflight.proposal(saved.proposalId(), saved.version(), saved.proposalSha256(), null);
        assertTrue(original.storedFactPaths().storedIntegrityValidated());
        var request = node.request();
        var next = writer.save(new CatalogChangePreviewRequest(1, request.proposalId(), request.rationale() + " new revision",
                request.expectedBaseSha256(), request.base(), request.candidate()), saved.version()).proposal();
        var head = preflight.proposal(next.proposalId(), next.version(), next.proposalSha256(), null);
        assertEquals(CatalogPublicationFactPathVerifier.Status.MISSING, head.storedFactPaths().status());
        assertTrue(head.blockers().contains(FACT_PATH_RECEIPT_MISSING));
        assertEquals(original.storedFactPaths(), preflight.proposal(saved.proposalId(), saved.version(), saved.proposalSha256(), null).storedFactPaths());
        registryStillEmpty();
    }

    @Test
    void bootstrapCandidateChecksReadExactReviewInSameReadOnlySnapshotWithoutCreatingReportsOrApproving() {
        var candidate = new CatalogPublicationLookupFixtures(mapper).root(Instant.now()).snapshot().catalog().asDraft();
        var observations = validator.validate(candidate).facts().stream().map(f -> new CatalogBootstrapReviewRequest.Observation(
                f.optionId(), f.path(), SOURCE_SUPPORTS_CLAIM)).toList();
        var request = new CatalogBootstrapReviewRequest(1, UUID.randomUUID(), CatalogDraftCanonicalizer.sha256(candidate), candidate,
                observations, CatalogBootstrapReviewRequest.Confirmation.MANUAL_BOOTSTRAP_SOURCE_REVIEW);
        var actor = new CuratorActor("http://localhost:8081", "synthetic-bootstrap-impact-curator", "123456789012345678", "987654321098765432", Instant.now());
        var receipt = bootstrapReviews.record(request, actor).review();
        doAnswer(call -> { transaction(); return call.callRealMethod(); }).when(bootstrapReviews).reviewed(any(UUID.class), anyString());
        doAnswer(call -> { transaction(); return call.callRealMethod(); }).when(bootstrapReports).latest(any(UUID.class));
        int reportCount = dsl.fetchCount(io.authweave.core.generated.jooq.tables.CatalogFactPathReports.CATALOG_FACT_PATH_REPORTS);
        int oldReportCount = dsl.fetchCount(CATALOG_IMPACT_REPORTS);
        int bootstrapReportCount = dsl.fetchCount(io.authweave.core.generated.jooq.tables.CatalogBootstrapImpactReports.CATALOG_BOOTSTRAP_IMPACT_REPORTS);
        var result = preflight.bootstrap(request.reviewId(), receipt.reviewSha256()); var check = result.bootstrapImpact();
        assertEquals(CatalogBootstrapImpactService.CheckStatus.ANALYZED, check.status()); assertEquals(result.evaluatedAt(), check.evaluatedAt());
        assertEquals(request.reviewId(), check.reviewId()); assertEquals(receipt.reviewSha256(), check.reviewSha256());
        assertEquals(receipt.candidateSha256(), check.candidateSha256()); assertEquals(9, check.recordedFacts());
        assertEquals(68, check.checkedFactPaths()); assertEquals(59, check.missingFactPaths()); assertEquals(3, check.checkedScenarios());
        assertTrue(check.allDeclaredFactPathsChecked()); assertTrue(check.allFrozenScenariosChecked());
        assertFalse(check.storedReportVerified()); assertFalse(check.coverageComplete());
        assertFalse(result.approvalGranted()); assertFalse(result.publicationReady()); assertFalse(result.writesPerformed());
        assertTrue(result.blockers().contains(BOOTSTRAP_IMPACT_RECEIPT_MISSING)); assertTrue(result.blockers().contains(IMPACT_COVERAGE_INCOMPLETE));
        assertEquals(CatalogPublicationImpactVerifier.Status.NOT_CHECKED, result.impact().status());
        assertEquals(CatalogPublicationFactPathVerifier.Status.NOT_CHECKED, result.storedFactPaths().status());
        var missing = preflight.bootstrap(UUID.randomUUID(), receipt.reviewSha256());
        assertEquals(CatalogBootstrapImpactService.CheckStatus.NOT_CHECKED, missing.bootstrapImpact().status());
        assertTrue(missing.blockers().contains(BOOTSTRAP_REVIEW_UNAVAILABLE));
        var wrong = preflight.bootstrap(request.reviewId(), "0".repeat(64));
        assertEquals(CatalogBootstrapImpactService.CheckStatus.NOT_CHECKED, wrong.bootstrapImpact().status());
        assertEquals(io.authweave.core.catalog.impact.CatalogArchitectureImpactService.CheckStatus.NOT_CHECKED,
                missing.profileImpactCoverage().architectureImpact().status());
        assertEquals(io.authweave.core.catalog.impact.CatalogArchitectureImpactService.CheckStatus.NOT_CHECKED,
                wrong.profileImpactCoverage().architectureImpact().status());
        assertEquals(receipt, bootstrapReviews.get(request.reviewId(), receipt.reviewSha256()));
        assertEquals(reportCount, dsl.fetchCount(io.authweave.core.generated.jooq.tables.CatalogFactPathReports.CATALOG_FACT_PATH_REPORTS));
        assertEquals(oldReportCount, dsl.fetchCount(CATALOG_IMPACT_REPORTS)); registryStillEmpty();
        assertEquals(bootstrapReportCount, dsl.fetchCount(io.authweave.core.generated.jooq.tables.CatalogBootstrapImpactReports.CATALOG_BOOTSTRAP_IMPACT_REPORTS));
        assertEquals(CatalogPublicationBootstrapImpactVerifier.Status.MISSING, result.storedBootstrapImpact().status());
        assertEquals(CatalogProfileImpactCoverageService.Status.INCOMPLETE, result.profileImpactCoverage().status());
        assertEquals(result.evaluatedAt(), result.profileImpactCoverage().evaluatedAt()); assertEquals(12, result.profileImpactCoverage().additionalGaps().size());
        var architecture = result.profileImpactCoverage().architectureImpact();
        assertEquals(result.evaluatedAt(), architecture.evaluatedAt()); assertEquals(20, architecture.checkedPatterns());
        assertEquals(result.profileImpactCoverage().scenarioSetSha256(), architecture.scenarioSetSha256());
        assertFalse(architecture.configurationVerified()); assertFalse(architecture.prerequisitesVerified()); assertFalse(architecture.publicationReady());
        assertEquals(result.evaluatedAt(), result.scopedProfileImpact().evaluatedAt()); assertEquals(4, result.scopedProfileImpact().checkedScenarios());
        assertEquals(request.reviewId(), result.scopedProfileImpact().inputId()); assertEquals(receipt.reviewSha256(), result.scopedProfileImpact().inputSha256());
        var json = mapper.writeValueAsString(check); assertFalse(json.contains("sourceUrl")); assertFalse(json.contains("profile")); assertFalse(json.contains(actor.subject()));
    }

    private void replace(CatalogImpactReport report, String body, String digest) throws Exception {
        replace(report, body, digest, "catalog_impact_reports", "catalog_impact_report_events");
    }

    private void replace(CatalogImpactReport report, String body, String digest, String table, String eventTable) throws Exception {
        // Disposable test DB admin only. Restore a fully consistent tuple; never alter runtime grants or real data.
        try (var admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            admin.setAutoCommit(false);
            try (var configuration = admin.createStatement()) { configuration.execute("SET LOCAL session_replication_role = replica"); }
            try (var update = admin.prepareStatement("UPDATE core." + table + " SET report = ?::jsonb, report_sha256 = ? WHERE id = ?")) {
                update.setString(1, body); update.setString(2, digest); update.setObject(3, report.reportId()); update.executeUpdate();
            }
            try (var audit = admin.prepareStatement("UPDATE audit." + eventTable + " SET report_sha256 = ? WHERE report_id = ?")) {
                audit.setString(1, digest); audit.setObject(2, report.reportId()); audit.executeUpdate();
            }
            admin.commit();
        }
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

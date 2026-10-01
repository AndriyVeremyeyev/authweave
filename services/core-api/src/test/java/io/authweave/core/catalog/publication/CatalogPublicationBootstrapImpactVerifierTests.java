package io.authweave.core.catalog.publication;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.impact.*;
import static io.authweave.core.catalog.publication.CatalogPublicationLookupFixtures.*;
import static io.authweave.core.catalog.publication.CatalogPublicationBootstrapImpactVerifier.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogPublicationBootstrapImpactVerifierTests {
    private static final Instant AT = Instant.parse("2026-10-01T12:00:00Z"), REVIEW_AT = AT.minusSeconds(120), REPORT_AT = AT.minusSeconds(30);
    private final JsonMapper mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final CatalogBootstrapImpactReportRepository repository = mock(CatalogBootstrapImpactReportRepository.class);
    private CatalogBootstrapImpactService impacts;
    private CatalogPublicationBootstrapImpactVerifier verifier;
    private CatalogBootstrapReviewRequest request;
    private CatalogBootstrapImpactReportRepository.Row row;
    @BeforeEach void receipt() throws Exception {
        var validator = new CatalogDraftValidator(Clock.fixed(AT, ZoneOffset.UTC));
        impacts = new CatalogBootstrapImpactService(validator, new CatalogScenarioCases(mapper));
        verifier = new CatalogPublicationBootstrapImpactVerifier(repository, impacts, mapper);
        var candidate = new CatalogPublicationLookupFixtures(mapper).root(REVIEW_AT).snapshot().catalog().asDraft();
        request = new CatalogBootstrapReviewRequest(1, UUID.randomUUID(), CatalogDraftCanonicalizer.sha256(candidate), candidate,
                validator.validate(candidate).facts().stream().map(f -> new CatalogBootstrapReviewRequest.Observation(f.optionId(), f.path(),
                        io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict.SOURCE_SUPPORTS_CLAIM)).toList(),
                CatalogBootstrapReviewRequest.Confirmation.MANUAL_BOOTSTRAP_SOURCE_REVIEW);
        setReport(mapper.valueToTree(impacts.analyzeAt(request, REPORT_AT)));
    }
    @Test void fullReplayKeepsOriginalTimeAndDistinguishesDeclaredChecksFromCompleteCoverageOrSourceTruth() {
        var result = run(); assertEquals(Status.VERIFIED_BOOTSTRAP_ANALYSIS, result.status());
        assertTrue(result.storedIntegrityValidated()); assertTrue(result.historicalReplayVerified());
        assertEquals(REPORT_AT, result.analysis().evaluatedAt()); assertEquals(row.id(), result.reportId());
        assertEquals(68, result.analysis().checkedFactPaths()); assertEquals(59, result.analysis().missingFactPaths());
        assertEquals(3, result.analysis().checkedScenarios()); assertTrue(result.allDeclaredFactPathsChecked()); assertTrue(result.allFrozenScenariosChecked());
        assertFalse(result.coverageComplete()); assertFalse(result.sourceVerificationPerformed()); assertFalse(result.baselineVerified());
        assertFalse(result.approvalGranted()); assertFalse(result.writesPerformed()); assertFalse(result.publicationReady()); assertFalse(result.evaluationReady());
        assertEquals(result, verifier.verify(request, REVIEW_AT, AT.plusSeconds(400L * 86400)));
        var json = mapper.writeValueAsString(result); assertFalse(json.contains("sourceUrl")); assertFalse(json.contains("actor")); assertFalse(json.contains("observations"));
    }
    @Test void absentAndOversizedReportsWithholdAllIdentityCountsAndNeverFallback() {
        stored(null); unavailable(Status.MISSING); stored(with(row, "reportBytes", 0L)); unavailable(Status.READ_BUDGET_EXCEEDED);
        stored(with(row, "reportBytes", CatalogBootstrapImpactReportRepository.MAX_JSON_BYTES + 1)); unavailable(Status.READ_BUDGET_EXCEEDED);
    }
    @ParameterizedTest @ValueSource(strings = {"id", "number", "overflow", "reviewId", "candidateSha256", "reviewSha256", "schemaVersion", "canonicalizationVersion",
            "reportSha256", "body-null", "bad-json", "recordedAt", "before-review", "future", "event", "event-id", "event-reportId", "event-reviewId",
            "event-candidateSha256", "event-reviewSha256", "event-reportSha256", "event-action", "event-actorType", "event-actorId", "event-correlationId", "event-outcome", "event-occurredAt", "event-past", "event-future"})
    void metadataAndServiceAuditAreMandatoryAndBoundToTheSameExactReview(String field) {
        var invalid = switch (field) {
            case "id", "recordedAt", "event" -> with(row, field, null);
            case "number" -> with(row, field, 0L);
            case "overflow" -> with(row, "number", 9007199254740992L);
            case "reviewId" -> with(row, field, UUID.randomUUID());
            case "schemaVersion" -> with(row, field, 2);
            case "candidateSha256", "reviewSha256", "reportSha256", "canonicalizationVersion" -> with(row, field, "invalid");
            case "body-null" -> with(row, "report", null);
            case "bad-json" -> with(row, "report", "{ broken");
            case "before-review" -> with(row, "recordedAt", REVIEW_AT.minusNanos(1));
            case "future" -> with(row, "recordedAt", AT.plusSeconds(31));
            case "event-id", "event-correlationId", "event-occurredAt" -> with(row, "event", with(row.event(), field.substring(6), null));
            case "event-reportId", "event-reviewId" -> with(row, "event", with(row.event(), field.substring(6), UUID.randomUUID()));
            case "event-past" -> with(row, "event", with(row.event(), "occurredAt", row.recordedAt().minusSeconds(31)));
            case "event-future" -> with(row, "event", with(row.event(), "occurredAt", row.recordedAt().plusSeconds(31)));
            default -> with(row, "event", with(row.event(), field.substring(6), "forged"));
        }; stored(invalid); unavailable(Status.INVALID_RECEIPT);
    }
    @ParameterizedTest @ValueSource(strings = {"policyVersion", "ruleVersion", "profilePolicyVersion", "caseSetVersion", "scenarioSetVersion"})
    void incompatibleVersionIsNotReinterpretedAsCurrentSuccess(String field) {
        var json = (ObjectNode) mapper.readTree(row.report()); json.put(field, "old"); setReport(json); unavailable(Status.INCOMPATIBLE_RULES);
        json.remove(field); setReport(json); unavailable(Status.INVALID_RECEIPT);
    }
    @ParameterizedTest @ValueSource(strings = {"caseSetSha256", "scenarioSetSha256", "caseDefinitions", "scenarioDefinitions", "cases", "scenarios", "uncoveredFacts", "scenarioUncoveredFacts",
            "outcome", "dependency", "coverageComplete", "storedReportVerified", "approvalGranted", "sourceVerificationPerformed", "reviewSha256", "candidateSha256", "extra-field"})
    void selfConsistentHashAndAuditCannotHideOmissionsOrForgedResultsAndScope(String field) {
        var json = (ObjectNode) mapper.readTree(row.report());
        switch (field) {
            case "caseSetSha256", "scenarioSetSha256", "reviewSha256", "candidateSha256" -> json.put(field, "0".repeat(64));
            case "caseDefinitions", "scenarioDefinitions", "cases", "scenarios" -> ((ArrayNode) json.get(field)).remove(0);
            case "uncoveredFacts", "scenarioUncoveredFacts" -> ((ArrayNode) json.get(field)).add(mapper.createObjectNode().put("optionId", "forged"));
            case "outcome" -> ((ObjectNode) json.at("/cases/0/result")).put("conditionalOutcome", "forged");
            case "dependency" -> ((ObjectNode) json.at("/cases/0")).put("factPath", "facts.SCIM");
            case "extra-field" -> json.put("proposalId", UUID.randomUUID().toString());
            default -> json.put(field, true);
        }
        setReport(json); unavailable(Status.REPLAY_MISMATCH);
    }
    @ParameterizedTest @ValueSource(strings = {"before-review", "after-record", "invalid", "numeric", "missing", "duplicate"})
    void evaluationTimeAndStrictJsonAreValidatedIndependentlyOfDigest(String field) {
        var json = (ObjectNode) mapper.readTree(row.report());
        switch (field) {
            case "before-review" -> json.put("evaluatedAt", REVIEW_AT.minusSeconds(31).toString());
            case "after-record" -> json.put("evaluatedAt", row.recordedAt().plusSeconds(31).toString());
            case "invalid" -> json.put("evaluatedAt", "not-a-date");
            case "numeric" -> json.put("evaluatedAt", 1);
            case "missing" -> json.remove("evaluatedAt");
            default -> { stored(with(row, "report", row.report().replaceFirst("\\{", "{\"status\":\"ANALYZED\","))); unavailable(Status.INVALID_RECEIPT); return; }
        }
        setReport(json); unavailable(Status.INVALID_RECEIPT);
    }
    @Test void databaseFailuresPropagateWithoutImplicitMissingOrRefresh() {
        when(repository.latest(any())).thenThrow(new DataAccessException("Unavailable")); assertThrows(DataAccessException.class, this::run);
    }
    @Test void compatibleBlockedAnalysisVerifiesHistoryWithoutReleasingSuccessfulCounts() {
        request = with(request, "expectedCandidateSha256", "0".repeat(64)); setReport(mapper.valueToTree(impacts.analyzeAt(request, REPORT_AT)));
        var result = run(); assertEquals(Status.VERIFIED_BLOCKED_ANALYSIS, result.status()); assertTrue(result.historicalReplayVerified());
        assertEquals(0, result.analysis().optionCount()); assertEquals(0, result.analysis().checkedFactPaths()); assertFalse(result.allDeclaredFactPathsChecked());
    }
    @Test void nonCanonicalFormattingAndArrayOrderDoNotInvalidateCanonicalReplay() {
        var json = (ObjectNode) mapper.readTree(row.report()); var definitions = (ArrayNode) json.get("caseDefinitions");
        definitions.add(definitions.remove(0)); stored(with(row, "report", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json)));
        assertEquals(Status.VERIFIED_BOOTSTRAP_ANALYSIS, run().status());
    }
    @Test void checkConstructorCannotInflateCoverageOrExposePartialInvalidReceiptMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new Check(Status.MISSING, row.id(), 1, row.reportSha256(), impacts.inspectAt(request, REPORT_AT)));
        assertThrows(IllegalArgumentException.class, () -> new Check(Status.VERIFIED_BOOTSTRAP_ANALYSIS, row.id(), 1, "0".repeat(64), impacts.inspectAt(request, REPORT_AT)));
        assertThrows(IllegalArgumentException.class, () -> new Check(Status.VERIFIED_BOOTSTRAP_ANALYSIS, row.id(), 1, row.reportSha256(), CatalogBootstrapImpactService.Check.notChecked()));
    }
    @Test void snapshotReturnsDefensiveCopiesWithoutReplayingCurrentKernel() {
        var snapshot = new CatalogBootstrapImpactReport(row.id(), row.number(), row.reviewId(), row.candidateSha256(), row.reviewSha256(), row.schemaVersion(),
                row.canonicalizationVersion(), row.reportSha256(), row.recordedAt(), mapper.readTree(row.report()));
        ((ObjectNode) snapshot.report()).put("approvalGranted", true); assertFalse(snapshot.report().get("approvalGranted").asBoolean());
        assertFalse(snapshot.report().has("proposalId"));
    }
    private Check run() { return verifier.verify(request, REVIEW_AT, AT); }
    private void unavailable(Status status) { assertEquals(Check.unavailable(status), run()); }
    private void stored(CatalogBootstrapImpactReportRepository.Row value) { when(repository.latest(any())).thenReturn(value); }
    private void setReport(tools.jackson.databind.JsonNode json) {
        var id = UUID.randomUUID(); var hash = CatalogDraftCanonicalizer.sha256(json); var digest = CatalogDraftCanonicalizer.sha256(request);
        String body = mapper.writeValueAsString(json);
        row = new CatalogBootstrapImpactReportRepository.Row(id, 1, request.reviewId(), request.expectedCandidateSha256(), digest, 1,
                CatalogDraftCanonicalizer.VERSION, hash, REPORT_AT.plusSeconds(1), body, bytes(body),
                new CatalogBootstrapImpactReportRepository.Event(UUID.randomUUID(), id, request.reviewId(), request.expectedCandidateSha256(), digest, hash,
                        "catalog-bootstrap-impact.recorded", "SERVICE", "core-api-local-catalog", UUID.randomUUID(), "SUCCEEDED", REPORT_AT.plusSeconds(2)));
        stored(row);
    }
}

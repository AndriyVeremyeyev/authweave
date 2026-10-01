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
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.impact.*;
import static io.authweave.core.catalog.publication.CatalogPublicationLookupFixtures.*;
import static io.authweave.core.catalog.publication.CatalogPublicationImpactVerifier.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogPublicationImpactVerifierTests {
    private static final Instant AT = Instant.parse("2026-10-01T12:00:00Z");
    private static final Instant PROPOSAL_AT = AT.minusSeconds(120), REPORT_AT = AT.minusSeconds(30);
    private final JsonMapper mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final Clock clock = Clock.fixed(AT, ZoneOffset.UTC);
    private final CatalogPublicationPreflightRepository repository = mock(CatalogPublicationPreflightRepository.class);
    private CatalogScenarioImpactService scenarios;
    private CatalogPublicationImpactVerifier verifier;
    private CatalogChangePreviewRequest request;
    private CatalogPublicationPreflightRepository.Impact row;

    @BeforeEach void receipt() throws Exception {
        scenarios = new CatalogScenarioImpactService(new CatalogChangePreviewService(new CatalogDraftValidator(clock), clock), new CatalogScenarioCases(mapper));
        verifier = new CatalogPublicationImpactVerifier(repository, scenarios, mapper);
        var fixtures = new CatalogPublicationLookupFixtures(mapper); request = fixtures.child(fixtures.root(PROPOSAL_AT)).request();
        setReport(mapper.valueToTree(scenarios.analyzeAt(request, 0, CatalogDraftCanonicalizer.sha256(request), REPORT_AT)));
    }

    @Test void fullHistoricalReplayValidatesPartialReportWithoutGrantingAnyAuthorityOrRefreshingTime() {
        var result = run();
        assertEquals(Status.VERIFIED_PARTIAL_ANALYSIS, result.status()); assertTrue(result.storedIntegrityValidated());
        assertTrue(result.historicalReplayVerified()); assertEquals(REPORT_AT, result.evaluatedAt());
        assertEquals(row.id(), result.reportId()); assertEquals(3, result.scenarioCount()); assertEquals(0, result.uncoveredChangeCount());
        assertEquals(7, result.deferredPathCount()); assertFalse(result.coverageComplete()); assertFalse(result.sourceVerificationPerformed());
        assertFalse(result.baselineVerified()); assertFalse(result.approvalGranted()); assertFalse(result.writesPerformed());
        assertFalse(result.publicationReady()); assertFalse(result.evaluationReady());
        assertEquals(result, run()); assertEquals(POLICY_VERSION, mapper.valueToTree(result).get("policyVersion").asText());
        var json = mapper.writeValueAsString(result);
        assertFalse(json.contains("sourceUrl")); assertFalse(json.contains("profile")); assertFalse(json.contains("actor"));
        assertFalse(json.contains(request.rationale()));
    }

    @Test void absentReceiptAndReadBudgetDoNotFallbackOrReleasePartialMetadata() {
        when(repository.latestImpact(any(), anyLong())).thenReturn(null);
        unavailable(Status.MISSING);
        stored(with(row, "reportBytes", CatalogPublicationRepository.MAX_JSON_BYTES + 1)); unavailable(Status.READ_BUDGET_EXCEEDED);
        stored(with(row, "reportBytes", 0L)); unavailable(Status.READ_BUDGET_EXCEEDED);
        stored(with(row, "reportBytes", CatalogPublicationRepository.MAX_JSON_BYTES)); assertTrue(run().storedIntegrityValidated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "number", "number-overflow", "proposalId", "version", "proposalSha256", "schemaVersion",
            "canonicalizationVersion", "reportSha256", "report-null", "invalid-json", "json-null", "recordedAt", "before-proposal", "future-time",
            "event", "event-id", "event-reportId", "event-proposalId", "event-proposalVersion", "event-reportSha256",
            "event-action", "event-actorType", "event-actorId", "event-correlationId", "event-outcome", "event-time", "event-past", "event-future"})
    void corruptMetadataOrAuditFailsClosedBeforeReturningReportIdentityOrCounts(String field) {
        var invalid = switch (field) {
            case "id", "proposalId" -> with(row, field, field.equals("id") ? null : UUID.randomUUID());
            case "number", "version" -> with(row, field, field.equals("number") ? 0L : 1L);
            case "number-overflow" -> with(row, "number", 9007199254740992L);
            case "schemaVersion" -> with(row, field, 2);
            case "proposalSha256", "canonicalizationVersion", "reportSha256" -> with(row, field, "invalid");
            case "report-null" -> with(row, "report", null);
            case "invalid-json" -> with(row, "report", "{ broken");
            case "json-null" -> with(row, "report", "null");
            case "recordedAt" -> with(row, field, null);
            case "before-proposal" -> with(row, "recordedAt", PROPOSAL_AT.minusNanos(1));
            case "future-time" -> with(row, "recordedAt", AT.plusSeconds(31));
            case "event" -> with(row, field, null);
            case "event-id", "event-correlationId" -> with(row, "event", with(row.event(), field.substring(6), null));
            case "event-time" -> with(row, "event", with(row.event(), "occurredAt", null));
            case "event-reportId", "event-proposalId" -> with(row, "event", with(row.event(), field.substring(6), UUID.randomUUID()));
            case "event-proposalVersion" -> with(row, "event", with(row.event(), "proposalVersion", 1L));
            case "event-past" -> with(row, "event", with(row.event(), "occurredAt", row.recordedAt().minusSeconds(30).minusNanos(1)));
            case "event-future" -> with(row, "event", with(row.event(), "occurredAt", row.recordedAt().plusSeconds(31)));
            default -> with(row, "event", with(row.event(), field.substring(6), "forged"));
        };
        stored(invalid); unavailable(Status.INVALID_RECEIPT);
    }

    @Test void tamperedBodyWithOriginalHashIsRejectedBeforeReplay() {
        var json = (ObjectNode) mapper.readTree(row.report()); json.put("approvalGranted", true);
        stored(with(row, "report", mapper.writeValueAsString(json))); unavailable(Status.INVALID_RECEIPT);
    }

    @ParameterizedTest @ValueSource(strings = {"policyVersion", "ruleVersion", "profilePolicyVersion", "caseSetVersion"})
    void oldOrUnknownRuleVersionsCannotAuthorizeEvenAnOtherwiseConsistentReport(String field) {
        var json = (ObjectNode) mapper.readTree(row.report()); json.put(field, "old-rules"); setReport(json); unavailable(Status.INCOMPATIBLE_RULES);
    }

    @ParameterizedTest @ValueSource(strings = {"case-digest", "definitions", "missing-scenario", "duplicate-scenario", "missing-check",
            "changed-outcome", "changed-status", "changed-dependency", "hidden-gap", "hidden-deferred", "changed-preview", "approval", "coverage", "source", "stored-binding", "extra-field"})
    void selfConsistentHashAndAuditCannotHideForgedResultsOmissionsOrInflatedCoverage(String field) {
        var json = (ObjectNode) mapper.readTree(row.report());
        switch (field) {
            case "case-digest" -> json.put("caseSetSha256", "0".repeat(64));
            case "definitions" -> ((tools.jackson.databind.node.ArrayNode) json.get("scenarioDefinitions")).remove(0);
            case "missing-scenario" -> ((tools.jackson.databind.node.ArrayNode) json.get("scenarios")).remove(0);
            case "duplicate-scenario" -> ((tools.jackson.databind.node.ArrayNode) json.get("scenarios")).add(json.get("scenarios").get(0).deepCopy());
            case "missing-check" -> ((tools.jackson.databind.node.ArrayNode) json.at("/scenarios/0/after/checks")).remove(0);
            case "changed-outcome" -> ((ObjectNode) json.at("/scenarios/0/after/checks/0")).put("conditionalOutcome",
                    json.at("/scenarios/0/after/checks/0/conditionalOutcome").asText().equals("WOULD_SATISFY") ? "WOULD_VIOLATE" : "WOULD_SATISFY");
            case "changed-status" -> ((ObjectNode) json.at("/scenarios/0/after")).put("conditionalStatus", "WOULD_SATISFY_CHECKED_REQUIREMENTS");
            case "changed-dependency" -> ((ObjectNode) json.at("/scenarios/0/after/checks/0")).put("usesFact", !json.at("/scenarios/0/after/checks/0/usesFact").asBoolean());
            case "hidden-gap" -> json.set("uncoveredChanges", mapper.valueToTree(java.util.List.of(new CatalogImpactPreview.UncoveredChange("forged", "facts.SCIM", "NO_SCENARIO_DEPENDENCY"))));
            case "hidden-deferred" -> json.set("deferredPaths", mapper.createArrayNode());
            case "changed-preview" -> ((ObjectNode) json.get("changePreview")).put("rationale", "forged");
            case "approval" -> json.put("approvalGranted", true);
            case "coverage" -> json.put("coverageComplete", true);
            case "source" -> json.put("sourceVerificationPerformed", true);
            case "stored-binding" -> json.put("storedProposalVersion", 1);
            case "extra-field" -> json.put("actor", "leak");
        }
        setReport(json); unavailable(Status.REPLAY_MISMATCH);
    }

    @Test void nonCanonicalFormattingAndArrayOrderDoNotChangeTheVerifiedResult() {
        var json = (ObjectNode) mapper.readTree(row.report());
        var definitions = (tools.jackson.databind.node.ArrayNode) json.get("scenarioDefinitions");
        var first = definitions.remove(0); definitions.add(first);
        stored(with(row, "report", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json)));
        assertEquals(Status.VERIFIED_PARTIAL_ANALYSIS, run().status());
    }

    @Test void laterPreflightDoesNotRefreshTheReportOrReplaceItsOriginalFreshnessResults() {
        var before = run();
        var later = verifier.verify(request, 0, PROPOSAL_AT, AT.plusSeconds(400L * 86400));
        assertEquals(before, later); assertEquals(REPORT_AT, later.evaluatedAt());
        assertEquals(row.reportSha256(), later.reportSha256()); assertFalse(later.sourceVerificationPerformed());
    }

    @ParameterizedTest @ValueSource(strings = {"before-proposal", "after-record", "future", "invalid", "numeric-time", "null-time", "missing-time", "duplicate-field", "numeric-policy", "null-policy", "missing-policy"})
    void formatAndTimeBindingsAreValidatedIndependentlyOfASelfConsistentDigest(String field) {
        var json = (ObjectNode) mapper.readTree(row.report());
        switch (field) {
            case "before-proposal" -> json.put("evaluatedAt", PROPOSAL_AT.minusSeconds(31).toString());
            case "after-record" -> json.put("evaluatedAt", row.recordedAt().plusSeconds(31).toString());
            case "future" -> json.put("evaluatedAt", AT.plusSeconds(31).toString());
            case "invalid" -> json.put("evaluatedAt", "not-a-date");
            case "numeric-time" -> json.put("evaluatedAt", 1);
            case "null-time" -> json.putNull("evaluatedAt");
            case "missing-time" -> json.remove("evaluatedAt");
            case "numeric-policy" -> json.put("policyVersion", 1);
            case "null-policy" -> json.putNull("policyVersion");
            case "missing-policy" -> json.remove("policyVersion");
        }
        setReport(json);
        if (field.equals("duplicate-field")) stored(with(row, "report", row.report().replaceFirst("\\{", "{\"scope\":\"CATALOG_PROFILE_SCENARIO_IMPACT\",")));
        unavailable(Status.INVALID_RECEIPT);
    }

    @Test void blockedAnalysisIsValidatedButNeverTreatedAsCoverage() {
        request = with(request, "expectedBaseSha256", "0".repeat(64));
        setReport(mapper.valueToTree(scenarios.analyzeAt(request, 0, CatalogDraftCanonicalizer.sha256(request), REPORT_AT)));
        var result = run(); assertEquals(Status.VERIFIED_BLOCKED_ANALYSIS, result.status()); assertTrue(result.storedIntegrityValidated());
        assertEquals(0, result.scenarioCount()); assertEquals(0, result.uncoveredChangeCount()); assertFalse(result.coverageComplete());
    }

    @Test void databaseOutagePropagatesRatherThanBecomingMissingOrAnOlderSuccessfulReport() {
        when(repository.latestImpact(any(), anyLong())).thenThrow(new DataAccessException("Unavailable"));
        assertThrows(DataAccessException.class, this::run);
    }

    private Check run() { return verifier.verify(request, 0, PROPOSAL_AT, AT); }
    private void unavailable(Status status) {
        var result = run(); assertEquals(Check.unavailable(status), result); assertFalse(result.storedIntegrityValidated());
        assertFalse(result.historicalReplayVerified()); assertFalse(result.coverageComplete()); assertNull(result.reportId());
        assertEquals(0, result.scenarioCount()); assertEquals(0, result.uncoveredChangeCount());
    }
    private void stored(CatalogPublicationPreflightRepository.Impact value) { when(repository.latestImpact(any(), anyLong())).thenReturn(value); }
    private void setReport(tools.jackson.databind.JsonNode body) {
        var hash = CatalogDraftCanonicalizer.sha256(body);
        var id = UUID.randomUUID(); var digest = CatalogDraftCanonicalizer.sha256(request);
        row = new CatalogPublicationPreflightRepository.Impact(id, 1, request.proposalId(), 0, digest, 1,
                CatalogDraftCanonicalizer.VERSION, hash, REPORT_AT.plusSeconds(1), mapper.writeValueAsString(body),
                bytes(mapper.writeValueAsString(body)), new CatalogImpactReportEvent(UUID.randomUUID(), id, request.proposalId(), 0,
                    hash, "catalog-impact.recorded", "SERVICE", "core-api-local-catalog", UUID.randomUUID(), "SUCCEEDED", REPORT_AT.plusSeconds(2)));
        stored(row);
    }
}

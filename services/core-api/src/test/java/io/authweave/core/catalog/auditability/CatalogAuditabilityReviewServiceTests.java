package io.authweave.core.catalog.auditability;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jooq.DSLContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.catalog.draft.*;
import static io.authweave.core.catalog.auditability.CatalogAuditabilityReviewFixtures.*;
import static io.authweave.core.catalog.auditability.CatalogAuditabilityReviewException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogAuditabilityReviewServiceTests {
    private final JsonMapper mapper = JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final CatalogAuditabilityDraftValidator drafts = new CatalogAuditabilityDraftValidator(clock, new CatalogDraftValidator(clock));
    private final DSLContext dsl = mock(DSLContext.class);
    private final CatalogAuditabilityReviewRepository repository = mock(CatalogAuditabilityReviewRepository.class);
    private final CatalogAuditabilityReviewService service = new CatalogAuditabilityReviewService(dsl, repository, new CatalogAuditabilityReviewValidator(drafts), mapper);

    @Test void exactReadReplaysFullUntrustedCandidateWithoutAnyWriteOrAuthority() throws Exception {
        var row = stored(); when(repository.find(row.id())).thenReturn(row);
        var receipt = service.get(row.id(), row.reviewSha256()); assertEquals(row.id(), receipt.reviewId()); assertEquals(2, receipt.factCount());
        assertEquals(new CatalogAuditabilityReview.Counts(2, 0, 0), receipt.counts()); assertFalse(receipt.sourceVerificationPerformed()); assertFalse(receipt.publicationReady());
        verifyNoInteractions(dsl);
        assertEquals(CONFLICT, assertThrows(CatalogAuditabilityReviewException.class, () -> service.get(row.id(), "0".repeat(64))).reason());
        assertEquals(NOT_FOUND, assertThrows(CatalogAuditabilityReviewException.class, () -> service.get(UUID.randomUUID(), row.reviewSha256())).reason());
        assertEquals(INVALID_REQUEST, assertThrows(CatalogAuditabilityReviewException.class, () -> service.get(row.id(), "bad")).reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-body", "over-budget", "empty-bytes", "schema", "policy", "base-hash", "supplement-hash", "set-hash", "review-hash",
            "catalog-version", "evidence-version", "option-count", "fact-count", "missing-audit", "audit-id", "audit-review-id", "audit-base", "audit-supplement",
            "audit-set", "audit-hash", "audit-action", "audit-type", "audit-issuer", "audit-subject", "audit-project", "audit-org", "audit-correlation",
            "audit-outcome", "audit-time", "stale-auth", "future-auth", "record-time", "changed-claim", "forged-body", "incomplete-observations"})
    void corruptOrMixedReceiptNeverFallsBackToFreshSuccessfulCalculation(String change) throws Exception {
        var row = stored();
        switch (change) {
            case "missing-body" -> row = with(row, "request", null);
            case "over-budget" -> row = with(row, "requestBytes", CatalogAuditabilityReviewRepository.MAX_JSON_BYTES + 1);
            case "empty-bytes" -> row = with(row, "requestBytes", 0L);
            case "schema" -> row = with(row, "schemaVersion", 2);
            case "policy" -> row = with(row, "policyVersion", "future-policy");
            case "base-hash" -> row = with(row, "baseSha256", "0".repeat(64));
            case "supplement-hash" -> row = with(row, "auditabilitySha256", "0".repeat(64));
            case "set-hash" -> row = with(row, "targetSetSha256", "0".repeat(64));
            case "review-hash" -> row = with(row, "reviewSha256", "0".repeat(64));
            case "catalog-version" -> row = with(row, "catalogVersion", "other");
            case "evidence-version" -> row = with(row, "evidenceVersion", "other");
            case "option-count" -> row = with(row, "optionCount", 2);
            case "fact-count" -> row = with(row, "factCount", 1);
            case "missing-audit" -> row = with(row, "audit", null);
            case "record-time" -> row = with(row, "recordedAt", NOW.plusSeconds(31));
            case "changed-claim", "forged-body", "incomplete-observations" -> {
                var body = (ObjectNode) mapper.readTree(row.request());
                if (change.equals("changed-claim")) ((ObjectNode) body.at("/candidate/auditabilityDraft/options/0/facts/0/evidence")).put("summary", "Changed claim");
                else if (change.equals("forged-body")) body.put("approvalGranted", true);
                else ((tools.jackson.databind.node.ArrayNode) body.get("observations")).remove(0);
                row = with(row, "request", mapper.writeValueAsString(body));
            }
            default -> {
                var audit = row.audit();
                String field = switch (change) {
                    case "audit-id" -> "id"; case "audit-review-id" -> "reviewId"; case "audit-base" -> "baseSha256";
                    case "audit-supplement" -> "auditabilitySha256"; case "audit-set" -> "targetSetSha256"; case "audit-hash" -> "reviewSha256";
                    case "audit-action" -> "action"; case "audit-type" -> "actorType"; case "audit-issuer" -> "issuer"; case "audit-subject" -> "subject";
                    case "audit-project" -> "projectId"; case "audit-org" -> "organizationId"; case "audit-correlation" -> "correlationId";
                    case "audit-outcome" -> "outcome"; case "audit-time" -> "occurredAt"; case "stale-auth", "future-auth" -> "authenticatedAt";
                    default -> throw new AssertionError(change);
                };
                Object value = switch (change) {
                    case "audit-id", "audit-correlation", "audit-time" -> null;
                    case "audit-review-id" -> UUID.randomUUID();
                    case "stale-auth" -> NOW.minusSeconds(901); case "future-auth" -> NOW.plusSeconds(31);
                    case "audit-issuer", "audit-subject" -> " ";
                    default -> "invalid";
                };
                row = with(row, "audit", with(audit, field, value));
            }
        }
        when(repository.find(row.id())).thenReturn(row); var invalid = row;
        assertEquals(READ_UNAVAILABLE, assertThrows(CatalogAuditabilityReviewException.class, () -> service.get(invalid.id(), invalid.reviewSha256())).reason());
        verifyNoInteractions(dsl);
    }

    @Test void databaseFailuresRemainFailuresRatherThanMissingOrFreshSuccessfulReviews() {
        UUID id = UUID.randomUUID();
        when(repository.find(id)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Synthetic DB read failure"));
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> service.get(id, "0".repeat(64)));
        verifyNoInteractions(dsl);
    }

    private CatalogAuditabilityReviewRepository.Stored stored() throws Exception {
        var request = request(mapper, drafts); String hash = CatalogDraftCanonicalizer.sha256(request), json = mapper.writeValueAsString(request);
        var audit = new CatalogAuditabilityReviewRepository.Audit(UUID.randomUUID(), request.reviewId(), request.expectedBaseContentSha256(),
                request.expectedAuditabilityContentSha256(), request.expectedTargetSetSha256(), hash, "catalog-auditability.source-reviewed", "CURATOR",
                "http://localhost:8081", "synthetic-curator", "123", "456", NOW, UUID.randomUUID(), "SUCCEEDED", NOW);
        return new CatalogAuditabilityReviewRepository.Stored(request.reviewId(), request.expectedBaseContentSha256(), request.expectedAuditabilityContentSha256(),
                request.expectedTargetSetSha256(), hash, 1, CatalogAuditabilityReviewService.POLICY_VERSION, "example-proposal-1", "example-auditability-proposal-1",
                1, 2, NOW, json, json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, audit);
    }
}

package io.authweave.core.catalog.auditability;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion;
import static io.authweave.core.catalog.auditability.CatalogAuditabilityReviewFixtures.*;
import static io.authweave.core.catalog.proposal.CatalogFactReviewRequest.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogAuditabilityReviewValidatorTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC);
    private final CatalogAuditabilityDraftValidator drafts = new CatalogAuditabilityDraftValidator(clock, new CatalogDraftValidator(clock));
    private final CatalogAuditabilityReviewValidator validator = new CatalogAuditabilityReviewValidator(drafts);

    @Test void explicitManualVerdictsCountClaimsWithoutChangingTruthAndOrderDoesNotChangeReviewHash() throws Exception {
        var request = request(mapper, drafts); assertEquals(new CatalogAuditabilityReview.Counts(2, 0, 0), validator.validate(request));
        var observations = new ArrayList<>(request.observations());
        observations.set(0, with(observations.get(0), "verdict", SOURCE_DOES_NOT_SUPPORT_CLAIM));
        observations.set(1, with(observations.get(1), "verdict", INSUFFICIENT_EVIDENCE));
        var changed = with(request, "observations", observations);
        assertEquals(new CatalogAuditabilityReview.Counts(0, 1, 1), validator.validate(changed));
        Collections.reverse(observations);
        assertEquals(CatalogDraftCanonicalizer.sha256(changed), CatalogDraftCanonicalizer.sha256(with(request, "observations", observations)));
        assertThrows(UnsupportedOperationException.class, () -> request.observations().clear());
        var receipt = new CatalogAuditabilityReview(request.reviewId(), request.expectedBaseContentSha256(), request.expectedAuditabilityContentSha256(),
                request.expectedTargetSetSha256(), CatalogDraftCanonicalizer.sha256(request), "example-proposal-1", "example-auditability-proposal-1",
                1, 2, new CatalogAuditabilityReview.Counts(2, 0, 0), clock.instant());
        assertTrue(receipt.sourceReviewRecorded()); assertFalse(receipt.sourceVerificationPerformed()); assertFalse(receipt.factTrustChanged());
        assertFalse(receipt.candidateImpactPerformed()); assertFalse(receipt.approvalGranted()); assertFalse(receipt.catalogWritesPerformed());
        assertFalse(receipt.publicationReady()); assertFalse(receipt.evaluationReady()); assertFalse(receipt.recommendationReady());
    }

    @ParameterizedTest
    @ValueSource(strings = {"base-hash", "supplement-hash", "set-hash", "target-hash", "missing", "duplicate", "foreign-option", "criterion", "changed-fact", "changed-base", "scope", "empty-facts"})
    void hashesScopeAndCompleteExplicitTargetsCannotBeBorrowedFromAnotherCandidate(String change) throws Exception {
        var request = request(mapper, drafts); var observations = new ArrayList<>(request.observations());
        switch (change) {
            case "base-hash" -> request = with(request, "expectedBaseContentSha256", "0".repeat(64));
            case "supplement-hash" -> request = with(request, "expectedAuditabilityContentSha256", "0".repeat(64));
            case "set-hash" -> request = with(request, "expectedTargetSetSha256", "0".repeat(64));
            case "target-hash" -> { observations.set(0, with(observations.get(0), "expectedTargetSha256", "0".repeat(64))); request = with(request, "observations", observations); }
            case "missing" -> { observations.removeLast(); request = with(request, "observations", observations); }
            case "duplicate" -> { observations.add(observations.getFirst()); request = with(request, "observations", observations); }
            case "foreign-option" -> { observations.set(0, with(observations.get(0), "optionId", "foreign")); request = with(request, "observations", observations); }
            case "criterion" -> { observations.set(0, with(observations.get(0), "criterion", Criterion.AUDIT_LOG_EXPORT)); request = with(request, "observations", observations); }
            default -> {
                var candidate = (ObjectNode) mapper.valueToTree(request.candidate());
                switch (change) {
                    case "changed-fact" -> ((ObjectNode) candidate.at("/auditabilityDraft/options/0/facts/0/evidence")).put("summary", "Changed interpretation");
                    case "changed-base" -> ((ObjectNode) candidate.at("/baseDraft/options/0")).put("product", "Changed product");
                    case "scope" -> ((ObjectNode) candidate.at("/auditabilityDraft/options/0/scope")).put("configuration", "Different scope");
                    case "empty-facts" -> ((ObjectNode) candidate.at("/auditabilityDraft/options/0")).putArray("facts");
                    default -> throw new AssertionError(change);
                }
                request = with(request, "candidate", mapper.treeToValue(candidate, CatalogAuditabilityDraftValidator.Request.class));
            }
        }
        var invalid = request;
        assertEquals(CatalogAuditabilityReviewException.Reason.INVALID_REQUEST,
                assertThrows(CatalogAuditabilityReviewException.class, () -> validator.validate(invalid)).reason());
    }

    @ParameterizedTest @ValueSource(strings = {"2025-01-01T00:00:00Z", "2027-01-01T00:00:00Z"})
    void manualReviewDoesNotRefreshOldOrFutureEvidenceOrChangeUnreviewedTargets(String observed) throws Exception {
        var request = request(mapper, drafts); var candidate = (ObjectNode) mapper.valueToTree(request.candidate());
        ((ObjectNode) candidate.at("/auditabilityDraft/options/0/facts/0/evidence")).put("observedAt", observed);
        var changed = bind(mapper.treeToValue(candidate, CatalogAuditabilityDraftValidator.Request.class), drafts);
        assertEquals(2, validator.validate(changed).supporting());
        var report = drafts.validate(changed.candidate()); assertEquals("UNREVIEWED", report.targets().getFirst().evidenceStatus());
        assertEquals(Instant.parse(observed), report.targets().getFirst().fact().evidence().observedAt());
    }

    @Test void constructorsBoundReceiptCountsAndRequireDedicatedManualConfirmation() throws Exception {
        var request = request(mapper, drafts);
        assertThrows(AssertionError.class, () -> with(request, "schemaVersion", 2));
        assertThrows(AssertionError.class, () -> with(request, "confirmation", null));
        assertThrows(AssertionError.class, () -> with(request, "observations", List.of()));
        assertThrows(AssertionError.class, () -> with(request, "observations", Collections.nCopies(601, request.observations().getFirst())));
        assertThrows(IllegalArgumentException.class, () -> new CatalogAuditabilityReview.Counts(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CatalogAuditabilityReview.Counts(600, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new CatalogAuditabilityReview(UUID.randomUUID(), "0".repeat(64), "1".repeat(64), "2".repeat(64),
                "3".repeat(64), "example", "evidence", 1, 1, new CatalogAuditabilityReview.Counts(2, 0, 0), clock.instant()));
    }
}

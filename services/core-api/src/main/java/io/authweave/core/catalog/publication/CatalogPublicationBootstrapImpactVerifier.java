package io.authweave.core.catalog.publication;

import java.time.Instant;
import java.time.DateTimeException;
import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.impact.CatalogBootstrapImpactService;
import io.authweave.core.catalog.impact.CatalogFactPathRegressionCases;
import io.authweave.core.catalog.impact.CatalogScenarioCases;
import io.authweave.core.evaluation.ClaimRules;
import io.authweave.core.evaluation.EligibilityEvaluator;

/** Latest exact-review receipt, replayed at its original time. No implicit write or source/publication authority. */
@Service
public final class CatalogPublicationBootstrapImpactVerifier {
    public static final String POLICY_VERSION = "catalog-publication-bootstrap-impact-verification-1";
    private final CatalogBootstrapImpactReportRepository repository;
    private final CatalogBootstrapImpactService impacts;
    private final ObjectMapper mapper;
    CatalogPublicationBootstrapImpactVerifier(CatalogBootstrapImpactReportRepository repository, CatalogBootstrapImpactService impacts, ObjectMapper mapper) {
        this.repository = repository; this.impacts = impacts; this.mapper = mapper;
    }
    public enum Status { NOT_CHECKED, MISSING, READ_BUDGET_EXCEEDED, INVALID_RECEIPT, INCOMPATIBLE_RULES, REPLAY_MISMATCH,
        VERIFIED_BLOCKED_ANALYSIS, VERIFIED_INCOMPLETE_ANALYSIS, VERIFIED_BOOTSTRAP_ANALYSIS }
    /** The nested analysis describes the replayed kernel, not a second stored receipt or a fresh preflight. */
    public record Check(Status status, UUID reportId, long reportNumber, String reportSha256, CatalogBootstrapImpactService.Check analysis) {
        public Check {
            Objects.requireNonNull(status); Objects.requireNonNull(analysis);
            boolean verified = verified(status);
            if (verified ? reportId == null || reportNumber <= 0 || reportNumber > 9007199254740991L
                    || reportSha256 == null || !reportSha256.matches("[a-f0-9]{64}") || !reportSha256.equals(analysis.reportSha256())
                    || analysis.status() == CatalogBootstrapImpactService.CheckStatus.NOT_CHECKED
                    || (status == Status.VERIFIED_BLOCKED_ANALYSIS) != (analysis.status() == CatalogBootstrapImpactService.CheckStatus.BLOCKED)
                    || (status == Status.VERIFIED_BOOTSTRAP_ANALYSIS) != (analysis.allDeclaredFactPathsChecked() && analysis.allFrozenScenariosChecked())
                    : reportId != null || reportNumber != 0 || reportSha256 != null || analysis.status() != CatalogBootstrapImpactService.CheckStatus.NOT_CHECKED)
                throw new IllegalArgumentException("Inconsistent stored bootstrap impact verification");
        }
        static Check unavailable(Status status) { return new Check(status, null, 0, null, CatalogBootstrapImpactService.Check.notChecked()); }
        private static boolean verified(Status status) { return status == Status.VERIFIED_BLOCKED_ANALYSIS
                || status == Status.VERIFIED_INCOMPLETE_ANALYSIS || status == Status.VERIFIED_BOOTSTRAP_ANALYSIS; }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public boolean storedIntegrityValidated() { return verified(status); }
        @JsonProperty public boolean historicalReplayVerified() { return storedIntegrityValidated(); }
        @JsonProperty public boolean allDeclaredFactPathsChecked() { return storedIntegrityValidated() && analysis.allDeclaredFactPathsChecked(); }
        @JsonProperty public boolean allFrozenScenariosChecked() { return storedIntegrityValidated() && analysis.allFrozenScenariosChecked(); }
        @JsonProperty public boolean coverageComplete() { return false; }
        @JsonProperty public boolean baselineVerified() { return false; }
        @JsonProperty public boolean sourceVerificationPerformed() { return false; }
        @JsonProperty public boolean approvalGranted() { return false; }
        @JsonProperty public boolean writesPerformed() { return false; }
        @JsonProperty public boolean publicationReady() { return false; }
        @JsonProperty public boolean evaluationReady() { return false; }
    }

    Check verify(CatalogBootstrapReviewRequest request, Instant reviewRecordedAt, Instant at) {
        // Latest by exact review UUID only: never discard invalid/hash-mismatched rows to expose older success.
        var row = repository.latest(request.reviewId());
        if (row == null) return Check.unavailable(Status.MISSING);
        if (row.reportBytes() <= 0 || row.reportBytes() > CatalogBootstrapImpactReportRepository.MAX_JSON_BYTES)
            return Check.unavailable(Status.READ_BUDGET_EXCEEDED);
        var digest = CatalogDraftCanonicalizer.sha256(request); var event = row.event();
        if (row.id() == null || row.number() <= 0 || row.number() > 9007199254740991L || !request.reviewId().equals(row.reviewId())
                || !request.expectedCandidateSha256().equals(row.candidateSha256()) || !digest.equals(row.reviewSha256())
                || row.report() == null || row.schemaVersion() != 1 || !CatalogDraftCanonicalizer.VERSION.equals(row.canonicalizationVersion())
                || row.reportSha256() == null || !row.reportSha256().matches("[a-f0-9]{64}") || row.recordedAt() == null
                || reviewRecordedAt == null || row.recordedAt().isBefore(reviewRecordedAt) || row.recordedAt().isAfter(at.plusSeconds(30))
                || event == null || event.id() == null || !row.id().equals(event.reportId()) || !row.reviewId().equals(event.reviewId())
                || !row.candidateSha256().equals(event.candidateSha256()) || !row.reviewSha256().equals(event.reviewSha256())
                || !row.reportSha256().equals(event.reportSha256()) || !"catalog-bootstrap-impact.recorded".equals(event.action())
                || !"SERVICE".equals(event.actorType()) || !"core-api-local-catalog".equals(event.actorId())
                || event.correlationId() == null || !"SUCCEEDED".equals(event.outcome()) || event.occurredAt() == null
                || event.occurredAt().isBefore(row.recordedAt().minusSeconds(30)) || event.occurredAt().isAfter(row.recordedAt().plusSeconds(30))
                || event.occurredAt().isAfter(at.plusSeconds(30))) return Check.unavailable(Status.INVALID_RECEIPT);
        try {
            var json = mapper.readTree(row.report());
            if (json == null || !json.isObject() || !row.reportSha256().equals(CatalogDraftCanonicalizer.sha256(json)))
                return Check.unavailable(Status.INVALID_RECEIPT);
            var time = json.get("evaluatedAt");
            if (time == null || !time.isString() || time.asText().length() > 64) return Check.unavailable(Status.INVALID_RECEIPT);
            var evaluatedAt = Instant.parse(time.asText());
            if (evaluatedAt.isBefore(reviewRecordedAt.minusSeconds(30)) || evaluatedAt.isAfter(row.recordedAt().plusSeconds(30))
                    || evaluatedAt.isAfter(at.plusSeconds(30))) return Check.unavailable(Status.INVALID_RECEIPT);
            var versions = java.util.Map.of("policyVersion", CatalogBootstrapImpactService.POLICY_VERSION,
                    "ruleVersion", ClaimRules.VERSION, "profilePolicyVersion", EligibilityEvaluator.COMPLIANCE_SCOPE_POLICY_VERSION,
                    "caseSetVersion", CatalogFactPathRegressionCases.VERSION, "scenarioSetVersion", CatalogScenarioCases.VERSION);
            for (var field : versions.keySet()) if (json.get(field) == null || !json.get(field).isString()) return Check.unavailable(Status.INVALID_RECEIPT);
            for (var field : versions.keySet()) if (!versions.get(field).equals(json.get(field).asText())) return Check.unavailable(Status.INCOMPATIBLE_RULES);
            var replay = impacts.analyzeAt(request, evaluatedAt);
            if (!row.reportSha256().equals(CatalogDraftCanonicalizer.sha256(replay))) return Check.unavailable(Status.REPLAY_MISMATCH);
            var analysis = impacts.summarize(replay);
            var status = analysis.status() == CatalogBootstrapImpactService.CheckStatus.BLOCKED ? Status.VERIFIED_BLOCKED_ANALYSIS
                    : analysis.allDeclaredFactPathsChecked() && analysis.allFrozenScenariosChecked()
                        ? Status.VERIFIED_BOOTSTRAP_ANALYSIS : Status.VERIFIED_INCOMPLETE_ANALYSIS;
            return new Check(status, row.id(), row.number(), row.reportSha256(), analysis);
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException | DateTimeException invalid) {
            return Check.unavailable(Status.INVALID_RECEIPT);
        }
        // DB failures propagate, never trigger a fallback, source fetch or new receipt.
    }
}

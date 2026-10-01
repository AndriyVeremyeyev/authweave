package io.authweave.core.catalog.publication;

import java.time.Instant;
import java.time.DateTimeException;
import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.impact.CatalogScenarioImpactService;
import io.authweave.core.catalog.impact.CatalogScenarioCases;
import io.authweave.core.catalog.impact.CatalogImpactPreview;
import io.authweave.core.evaluation.ClaimRules;
import io.authweave.core.evaluation.EligibilityEvaluator;

/** Internal read within preflight's repeatable-read transaction. Recorded integrity is not complete coverage or authority. */
@Service
public final class CatalogPublicationImpactVerifier {
    public static final String POLICY_VERSION = "catalog-publication-impact-verification-1";
    private static final long MAX_SAFE_INTEGER = 9007199254740991L;
    private final CatalogPublicationPreflightRepository repository;
    private final CatalogScenarioImpactService scenarios;
    private final ObjectMapper mapper;
    CatalogPublicationImpactVerifier(CatalogPublicationPreflightRepository repository, CatalogScenarioImpactService scenarios, ObjectMapper mapper) {
        this.repository = repository; this.scenarios = scenarios; this.mapper = mapper;
    }
    public enum Status { NOT_CHECKED, MISSING, READ_BUDGET_EXCEEDED, INVALID_RECEIPT,
        INCOMPATIBLE_RULES, REPLAY_MISMATCH, VERIFIED_BLOCKED_ANALYSIS, VERIFIED_PARTIAL_ANALYSIS }

    /** No source/profile bodies, curator/service identity or partial successful counts escape an invalid report. */
    public record Check(Status status, UUID reportId, long reportNumber, String reportSha256, Instant evaluatedAt,
            int scenarioCount, int uncoveredChangeCount, int deferredPathCount) {
        public Check {
            Objects.requireNonNull(status);
            boolean verified = status == Status.VERIFIED_BLOCKED_ANALYSIS || status == Status.VERIFIED_PARTIAL_ANALYSIS;
            if (verified ? reportId == null || reportNumber <= 0 || reportNumber > MAX_SAFE_INTEGER || reportSha256 == null
                    || !reportSha256.matches("[a-f0-9]{64}") || evaluatedAt == null || scenarioCount < 0 || scenarioCount > 600
                    || uncoveredChangeCount < 0 || uncoveredChangeCount > 13600 || deferredPathCount <= 0
                    || deferredPathCount > 100 || status == Status.VERIFIED_BLOCKED_ANALYSIS && (scenarioCount != 0 || uncoveredChangeCount != 0)
                    : reportId != null || reportNumber != 0 || reportSha256 != null || evaluatedAt != null
                        || scenarioCount != 0 || uncoveredChangeCount != 0 || deferredPathCount != 0) {
                throw new IllegalArgumentException("Inconsistent impact verification");
            }
        }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public boolean storedIntegrityValidated() { return status == Status.VERIFIED_BLOCKED_ANALYSIS || status == Status.VERIFIED_PARTIAL_ANALYSIS; }
        @JsonProperty public boolean historicalReplayVerified() { return storedIntegrityValidated(); }
        @JsonProperty public boolean coverageComplete() { return false; }
        @JsonProperty public boolean sourceVerificationPerformed() { return false; }
        @JsonProperty public boolean baselineVerified() { return false; }
        @JsonProperty public boolean approvalGranted() { return false; }
        @JsonProperty public boolean writesPerformed() { return false; }
        @JsonProperty public boolean publicationReady() { return false; }
        @JsonProperty public boolean evaluationReady() { return false; }
        static Check unavailable(Status status) { return new Check(status, null, 0, null, null, 0, 0, 0); }
    }

    Check verify(CatalogChangePreviewRequest request, long version, Instant proposalRecordedAt, Instant at) {
        var row = repository.latestImpact(request.proposalId(), version);
        if (row == null) return Check.unavailable(Status.MISSING);
        if (row.reportBytes() <= 0 || row.reportBytes() > CatalogPublicationRepository.MAX_JSON_BYTES) {
            return Check.unavailable(Status.READ_BUDGET_EXCEEDED);
        }
        var digest = CatalogDraftCanonicalizer.sha256(request);
        var event = row.event();
        if (row.id() == null || row.number() <= 0 || row.number() > MAX_SAFE_INTEGER || !request.proposalId().equals(row.proposalId())
                || row.version() != version || !digest.equals(row.proposalSha256()) || row.report() == null || row.schemaVersion() != 1
                || !CatalogDraftCanonicalizer.VERSION.equals(row.canonicalizationVersion()) || row.reportSha256() == null
                || !row.reportSha256().matches("[a-f0-9]{64}") || row.recordedAt() == null || proposalRecordedAt == null
                || row.recordedAt().isBefore(proposalRecordedAt) || row.recordedAt().isAfter(at.plusSeconds(30))
                || event == null || event.id() == null || !row.id().equals(event.reportId()) || !row.proposalId().equals(event.proposalId())
                || row.version() != event.proposalVersion() || !row.reportSha256().equals(event.reportSha256())
                || !"catalog-impact.recorded".equals(event.action()) || !"SERVICE".equals(event.actorType())
                || !"core-api-local-catalog".equals(event.actorId()) || event.correlationId() == null || !"SUCCEEDED".equals(event.outcome())
                || event.occurredAt() == null || event.occurredAt().isBefore(row.recordedAt().minusSeconds(30))
                || event.occurredAt().isAfter(row.recordedAt().plusSeconds(30)) || event.occurredAt().isAfter(at.plusSeconds(30))) {
            return Check.unavailable(Status.INVALID_RECEIPT);
        }
        try {
            var json = mapper.readTree(row.report());
            if (json == null || !json.isObject() || !row.reportSha256().equals(CatalogDraftCanonicalizer.sha256(json))) {
                return Check.unavailable(Status.INVALID_RECEIPT);
            }
            // Historical JSON contains interface-valued proposed facts. Do not deserialize/reinterpret those facts;
            // read only the replay inputs, then compare the entire stored tree with the generated current report.
            var time = json.get("evaluatedAt");
            if (time == null || !time.isString() || time.asText().length() > 64) return Check.unavailable(Status.INVALID_RECEIPT);
            var evaluatedAt = Instant.parse(time.asText());
            if (evaluatedAt.isBefore(proposalRecordedAt.minusSeconds(30))
                    || evaluatedAt.isAfter(row.recordedAt().plusSeconds(30)) || evaluatedAt.isAfter(at.plusSeconds(30))) {
                return Check.unavailable(Status.INVALID_RECEIPT);
            }
            for (var field : java.util.List.of("policyVersion", "ruleVersion", "profilePolicyVersion", "caseSetVersion")) {
                if (json.get(field) == null || !json.get(field).isString()) return Check.unavailable(Status.INVALID_RECEIPT);
            }
            if (!CatalogScenarioImpactService.POLICY_VERSION.equals(json.get("policyVersion").asText()) || !ClaimRules.VERSION.equals(json.get("ruleVersion").asText())
                    || !EligibilityEvaluator.COMPLIANCE_SCOPE_POLICY_VERSION.equals(json.get("profilePolicyVersion").asText())
                    || !CatalogScenarioCases.VERSION.equals(json.get("caseSetVersion").asText())) {
                return Check.unavailable(Status.INCOMPATIBLE_RULES);
            }
            // Regenerate every definition, scoped dependency, diff, result, gap and false trust flag at the ORIGINAL time.
            // A self-consistent forged digest/audit cannot hide altered outcomes, missing checks or inflated coverage.
            var replay = scenarios.analyzeAt(request, version, digest, evaluatedAt);
            if (!row.reportSha256().equals(CatalogDraftCanonicalizer.sha256(replay))) return Check.unavailable(Status.REPLAY_MISMATCH);
            var status = replay.status() == CatalogImpactPreview.Status.BLOCKED ? Status.VERIFIED_BLOCKED_ANALYSIS : Status.VERIFIED_PARTIAL_ANALYSIS;
            return new Check(status, row.id(), row.number(), row.reportSha256(), evaluatedAt,
                    replay.scenarios().size(), replay.uncoveredChanges().size(), replay.deferredPaths().size());
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException | DateTimeException invalid) {
            return Check.unavailable(Status.INVALID_RECEIPT);
        }
        // Database failures are outside the format/replay catch and propagate; no previous report or source fetch is tried.
    }
}

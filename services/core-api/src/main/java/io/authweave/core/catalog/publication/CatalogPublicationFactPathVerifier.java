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
import io.authweave.core.catalog.impact.CatalogFactPathReportRepository;
import io.authweave.core.catalog.impact.CatalogFactPathRegressionCases;
import io.authweave.core.catalog.impact.CatalogImpactService;
import io.authweave.core.catalog.impact.CatalogImpactPreview;
import io.authweave.core.evaluation.ClaimRules;

/** Latest exact-revision receipt, replayed at its original time. No publication or full-profile authority. */
@Service
public final class CatalogPublicationFactPathVerifier {
    public static final String POLICY_VERSION = "catalog-publication-fact-path-verification-1";
    private static final long MAX_SAFE_INTEGER = 9007199254740991L;
    private final CatalogFactPathReportRepository repository;
    private final CatalogImpactService impacts;
    private final ObjectMapper mapper;
    CatalogPublicationFactPathVerifier(CatalogFactPathReportRepository repository, CatalogImpactService impacts, ObjectMapper mapper) {
        this.repository = repository; this.impacts = impacts; this.mapper = mapper;
    }
    public enum Status { NOT_CHECKED, MISSING, READ_BUDGET_EXCEEDED, INVALID_RECEIPT, INCOMPATIBLE_RULES,
        REPLAY_MISMATCH, VERIFIED_BLOCKED_ANALYSIS, VERIFIED_INCOMPLETE_ANALYSIS, VERIFIED_FACT_PATH_ANALYSIS }

    /** Invalid receipts never release partial identity, time or successful counts. */
    public record Check(Status status, UUID reportId, long reportNumber, String reportSha256, Instant evaluatedAt,
            int affectedOptions, int changedFacts, int scopeChangedOptions, int checkedCases, int uncoveredChanges) {
        public Check {
            Objects.requireNonNull(status);
            boolean verified = verified(status);
            boolean covered = covered(status, changedFacts, scopeChangedOptions, checkedCases, uncoveredChanges);
            if (verified ? reportId == null || reportNumber <= 0 || reportNumber > MAX_SAFE_INTEGER || reportSha256 == null
                    || !reportSha256.matches("[a-f0-9]{64}") || evaluatedAt == null || affectedOptions < 0 || affectedOptions > 200
                    || changedFacts < 0 || changedFacts > affectedOptions * CatalogFactPathRegressionCases.FACT_PATH_COUNT
                    || scopeChangedOptions < 0 || scopeChangedOptions > affectedOptions || checkedCases < 0
                    || checkedCases > affectedOptions * CatalogFactPathRegressionCases.FACT_PATH_COUNT || uncoveredChanges < 0 || uncoveredChanges > 13600
                    || (status == Status.VERIFIED_FACT_PATH_ANALYSIS) != covered
                    || status == Status.VERIFIED_BLOCKED_ANALYSIS && (affectedOptions != 0 || changedFacts != 0
                        || scopeChangedOptions != 0 || checkedCases != 0 || uncoveredChanges != 0)
                    : reportId != null || reportNumber != 0 || reportSha256 != null || evaluatedAt != null || affectedOptions != 0
                        || changedFacts != 0 || scopeChangedOptions != 0 || checkedCases != 0 || uncoveredChanges != 0) {
                throw new IllegalArgumentException("Inconsistent stored fact-path verification");
            }
        }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public int declaredFactPaths() { return CatalogFactPathRegressionCases.FACT_PATH_COUNT; }
        @JsonProperty public boolean storedIntegrityValidated() { return verified(status); }
        @JsonProperty public boolean historicalReplayVerified() { return storedIntegrityValidated(); }
        @JsonProperty public boolean changedFactPathsCovered() { return status == Status.VERIFIED_FACT_PATH_ANALYSIS; }
        @JsonProperty public boolean coverageComplete() { return false; }
        @JsonProperty public boolean sourceVerificationPerformed() { return false; }
        @JsonProperty public boolean baselineVerified() { return false; }
        @JsonProperty public boolean approvalGranted() { return false; }
        @JsonProperty public boolean writesPerformed() { return false; }
        @JsonProperty public boolean publicationReady() { return false; }
        @JsonProperty public boolean evaluationReady() { return false; }
        static Check unavailable(Status status) { return new Check(status, null, 0, null, null, 0, 0, 0, 0, 0); }
        private static boolean verified(Status status) { return status == Status.VERIFIED_FACT_PATH_ANALYSIS
                || status == Status.VERIFIED_BLOCKED_ANALYSIS || status == Status.VERIFIED_INCOMPLETE_ANALYSIS; }
        private static boolean covered(Status status, int facts, int scopes, int checks, int gaps) {
            return status != Status.VERIFIED_BLOCKED_ANALYSIS && (facts > 0 || scopes > 0) && checks > 0
                    && checks >= facts && checks >= scopes * CatalogFactPathRegressionCases.FACT_PATH_COUNT && gaps == 0;
        }
    }

    Check verify(CatalogChangePreviewRequest request, long version, Instant proposalRecordedAt, Instant at) {
        // Never filter by digest/validity or fall back to an older successful report.
        var row = repository.latest(request.proposalId(), version);
        if (row == null) return Check.unavailable(Status.MISSING);
        if (row.reportBytes() <= 0 || row.reportBytes() > CatalogFactPathReportRepository.MAX_JSON_BYTES)
            return Check.unavailable(Status.READ_BUDGET_EXCEEDED);
        var digest = CatalogDraftCanonicalizer.sha256(request); var event = row.event();
        if (row.id() == null || row.number() <= 0 || row.number() > MAX_SAFE_INTEGER || !request.proposalId().equals(row.proposalId())
                || row.version() != version || !digest.equals(row.proposalSha256()) || row.report() == null || row.schemaVersion() != 1
                || !CatalogDraftCanonicalizer.VERSION.equals(row.canonicalizationVersion()) || row.reportSha256() == null
                || !row.reportSha256().matches("[a-f0-9]{64}") || row.recordedAt() == null || proposalRecordedAt == null
                || row.recordedAt().isBefore(proposalRecordedAt) || row.recordedAt().isAfter(at.plusSeconds(30))
                || event == null || event.id() == null || !row.id().equals(event.reportId()) || !row.proposalId().equals(event.proposalId())
                || row.version() != event.proposalVersion() || !row.reportSha256().equals(event.reportSha256())
                || !"catalog-fact-path.recorded".equals(event.action()) || !"SERVICE".equals(event.actorType())
                || !"core-api-local-catalog".equals(event.actorId()) || event.correlationId() == null || !"SUCCEEDED".equals(event.outcome())
                || event.occurredAt() == null || event.occurredAt().isBefore(row.recordedAt().minusSeconds(30))
                || event.occurredAt().isAfter(row.recordedAt().plusSeconds(30)) || event.occurredAt().isAfter(at.plusSeconds(30)))
            return Check.unavailable(Status.INVALID_RECEIPT);
        try {
            var json = mapper.readTree(row.report());
            if (json == null || !json.isObject() || !row.reportSha256().equals(CatalogDraftCanonicalizer.sha256(json)))
                return Check.unavailable(Status.INVALID_RECEIPT);
            // Do not deserialize historical interface-valued proposed facts. Only extract replay time/version headers.
            var time = json.get("evaluatedAt");
            if (time == null || !time.isString() || time.asText().length() > 64) return Check.unavailable(Status.INVALID_RECEIPT);
            var evaluatedAt = Instant.parse(time.asText());
            if (evaluatedAt.isBefore(proposalRecordedAt.minusSeconds(30)) || evaluatedAt.isAfter(row.recordedAt().plusSeconds(30))
                    || evaluatedAt.isAfter(at.plusSeconds(30))) return Check.unavailable(Status.INVALID_RECEIPT);
            for (var field : java.util.List.of("policyVersion", "ruleVersion", "caseSetVersion"))
                if (json.get(field) == null || !json.get(field).isString()) return Check.unavailable(Status.INVALID_RECEIPT);
            if (!CatalogImpactService.FACT_PATH_POLICY_VERSION.equals(json.get("policyVersion").asText())
                    || !ClaimRules.VERSION.equals(json.get("ruleVersion").asText())
                    || !CatalogFactPathRegressionCases.VERSION.equals(json.get("caseSetVersion").asText()))
                return Check.unavailable(Status.INCOMPATIBLE_RULES);
            // Whole-report replay detects omissions, forged dependencies/outcomes and self-consistent hash/audit tampering.
            var replay = impacts.analyzeFactPathsAt(request, version, digest, evaluatedAt);
            if (!row.reportSha256().equals(CatalogDraftCanonicalizer.sha256(replay))) return Check.unavailable(Status.REPLAY_MISMATCH);
            var preview = replay.changePreview();
            var status = replay.status() == CatalogImpactPreview.Status.BLOCKED ? Status.VERIFIED_BLOCKED_ANALYSIS
                    : Check.covered(Status.VERIFIED_FACT_PATH_ANALYSIS, preview.factChanges().size(), preview.optionChanges().size(),
                        replay.cases().size(), replay.uncoveredChanges().size()) ? Status.VERIFIED_FACT_PATH_ANALYSIS : Status.VERIFIED_INCOMPLETE_ANALYSIS;
            return new Check(status, row.id(), row.number(), row.reportSha256(), evaluatedAt, preview.affectedOptionIds().size(),
                    preview.factChanges().size(), preview.optionChanges().size(), replay.cases().size(), replay.uncoveredChanges().size());
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException | DateTimeException invalid) {
            return Check.unavailable(Status.INVALID_RECEIPT);
        }
        // Database errors propagate, never become an implicit missing receipt or source fetch.
    }
}

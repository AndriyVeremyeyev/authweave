package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.evaluation.ClaimRules;

/** Fresh body-free regression summary, separate from recorded historical impact and publication authority. */
@Service
public final class CatalogFactPathRegressionService {
    private final CatalogImpactService impacts;
    public CatalogFactPathRegressionService(CatalogImpactService impacts) { this.impacts = impacts; }
    public enum Status { NOT_CHECKED, BLOCKED, ANALYZED }
    public record Check(Status status, Instant evaluatedAt, String reportSha256, int affectedOptions,
            int changedFacts, int scopeChangedOptions, int checkedCases, int uncoveredChanges) {
        public Check {
            Objects.requireNonNull(status);
            if (affectedOptions < 0 || affectedOptions > 200 || changedFacts < 0 || changedFacts > 13600
                    || scopeChangedOptions < 0 || scopeChangedOptions > affectedOptions || checkedCases < 0 || checkedCases > 13600
                    || uncoveredChanges < 0 || uncoveredChanges > 13600
                    || (status == Status.NOT_CHECKED ? evaluatedAt != null || reportSha256 != null
                        : evaluatedAt == null || reportSha256 == null || !reportSha256.matches("[a-f0-9]{64}"))
                    || status != Status.ANALYZED && (affectedOptions != 0 || changedFacts != 0 || scopeChangedOptions != 0 || checkedCases != 0 || uncoveredChanges != 0)
                    || status == Status.ANALYZED && (checkedCases > affectedOptions * CatalogFactPathRegressionCases.FACT_PATH_COUNT
                        || changedFacts > affectedOptions * CatalogFactPathRegressionCases.FACT_PATH_COUNT)) {
                throw new IllegalArgumentException("Inconsistent fact-path regression summary");
            }
        }
        public static Check notChecked() { return new Check(Status.NOT_CHECKED, null, null, 0, 0, 0, 0, 0); }
        @JsonProperty public String policyVersion() { return CatalogImpactService.FACT_PATH_POLICY_VERSION; }
        @JsonProperty public String ruleVersion() { return ClaimRules.VERSION; }
        @JsonProperty public String caseSetVersion() { return CatalogFactPathRegressionCases.VERSION; }
        @JsonProperty public String caseSetSha256() { return CatalogFactPathRegressionCases.SHA256; }
        @JsonProperty public int declaredFactPaths() { return CatalogFactPathRegressionCases.FACT_PATH_COUNT; }
        @JsonProperty public boolean changedFactPathsCovered() {
            return status == Status.ANALYZED && (changedFacts > 0 || scopeChangedOptions > 0) && checkedCases > 0
                    && checkedCases >= changedFacts && checkedCases >= scopeChangedOptions * CatalogFactPathRegressionCases.FACT_PATH_COUNT
                    && uncoveredChanges == 0;
        }
        @JsonProperty public boolean storedReportVerified() { return false; }
        @JsonProperty public boolean coverageComplete() { return false; }
        @JsonProperty public boolean baselineVerified() { return false; }
        @JsonProperty public boolean sourceVerificationPerformed() { return false; }
        @JsonProperty public boolean approvalGranted() { return false; }
        @JsonProperty public boolean writesPerformed() { return false; }
        @JsonProperty public boolean publicationReady() { return false; }
        @JsonProperty public boolean evaluationReady() { return false; }
        @JsonProperty public boolean recommendationReady() { return false; }
    }
    public Check inspect(CatalogChangePreviewRequest request, Instant at) {
        var report = impacts.analyzeFactPathsAt(request, at); var preview = report.changePreview();
        return new Check(report.status() == CatalogImpactPreview.Status.BLOCKED ? Status.BLOCKED : Status.ANALYZED,
                report.evaluatedAt(), CatalogDraftCanonicalizer.sha256(report), preview.affectedOptionIds().size(),
                preview.factChanges().size(), preview.optionChanges().size(), report.cases().size(), report.uncoveredChanges().size());
    }
}

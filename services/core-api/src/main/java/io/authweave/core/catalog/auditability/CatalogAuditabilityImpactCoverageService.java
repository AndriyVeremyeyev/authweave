package io.authweave.core.catalog.auditability;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion;
import io.authweave.core.catalog.draft.AuditabilityCatalogDraft;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.impact.CatalogAuditabilityRegressionCases;
import io.authweave.core.catalog.impact.CatalogAuditabilityRegressionService;
import io.authweave.core.catalog.impact.CatalogImpactPreview.Outcome;
import io.authweave.core.catalog.impact.CatalogProfileImpactCoverageService.Boundary;
import io.authweave.core.catalog.impact.CatalogProfileImpactCoverageV6Service;
import io.authweave.core.catalog.auditability.CatalogAuditabilityImpactService.Impact;
import io.authweave.core.catalog.auditability.CatalogAuditabilityImpactService.Request;
import static io.authweave.core.catalog.auditability.CatalogAuditabilityReviewException.Reason.READ_UNAVAILABLE;

/** Links a fresh conditional candidate calculation to structural coverage, never to publication authority. */
@Service
public class CatalogAuditabilityImpactCoverageService {
    public static final String POLICY_VERSION = "catalog-auditability-impact-coverage-1";
    public static final String MANIFEST_SHA256 = CatalogDraftCanonicalizer.sha256(List.of(POLICY_VERSION,
            CatalogProfileImpactCoverageV6Service.MANIFEST_SHA256, CatalogAuditabilityImpactService.POLICY_VERSION,
            CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_SHA256, CatalogAuditabilityRegressionService.CHECKED_PATHS));
    private final CatalogAuditabilityImpactService impacts;
    private final CatalogProfileImpactCoverageV6Service coverage;
    private final CatalogAuditabilityRegressionCases cases;
    public CatalogAuditabilityImpactCoverageService(CatalogAuditabilityImpactService impacts,
            CatalogProfileImpactCoverageV6Service coverage, CatalogAuditabilityRegressionCases cases) {
        this.impacts = impacts; this.coverage = coverage; this.cases = cases;
    }

    public record Outcomes(int wouldSatisfy, int wouldViolate, int indeterminate) {
        public Outcomes {
            if (wouldSatisfy < 0 || wouldSatisfy > 6 || wouldViolate < 0 || wouldViolate > 6 || indeterminate < 0 || indeterminate > 6
                    || total(wouldSatisfy, wouldViolate, indeterminate) > 6)
                throw new IllegalArgumentException("Invalid selected-criterion outcome counts");
        }
        private static int total(int pass, int fail, int unknown) { return pass + fail + unknown; }
        int total() { return total(wouldSatisfy, wouldViolate, indeterminate); }
    }
    public record Dimension(String scenarioId, String profileSha256, AuditabilityCatalogDraft.Scope optionScope,
            String profilePath, List<Criterion> evidenceCriteria, Outcomes before, Outcomes after, int changedFacts, int changedChecks) {
        public Dimension {
            Objects.requireNonNull(optionScope); Objects.requireNonNull(before); Objects.requireNonNull(after);
            CatalogAuditabilityReviewRequest.digest(profileSha256); evidenceCriteria = List.copyOf(evidenceCriteria);
            if (!io.authweave.core.catalog.impact.CatalogScopedProfileCases.IDS.contains(scenarioId)
                    || !CatalogAuditabilityRegressionService.CHECKED_PATHS.contains(profilePath)
                    || !evidenceCriteria.equals(Arrays.stream(Criterion.values()).filter(evidenceCriteria::contains).toList())
                    || profilePath.endsWith("minimumRetentionDays") && evidenceCriteria.stream().anyMatch(c -> c != Criterion.AUDIT_LOG_RETENTION)
                    || before.total() != evidenceCriteria.size() || after.total() != evidenceCriteria.size()
                    || changedFacts < 0 || changedFacts > evidenceCriteria.size() || changedChecks < 0 || changedChecks > evidenceCriteria.size())
                throw new IllegalArgumentException("Invalid candidate auditability dimension");
        }
        @JsonProperty public String state() { return evidenceCriteria.isEmpty() ? "SCOPE_GUARD_ONLY" : "CONDITIONAL_CANDIDATE_CHECKS_PRESENT"; }
    }
    public record Check(Instant evaluatedAt, CatalogProfileImpactCoverageV6Service.Check profileCoverage,
            Impact candidateImpact, List<Dimension> dimensions) {
        public Check {
            Objects.requireNonNull(evaluatedAt); Objects.requireNonNull(profileCoverage); Objects.requireNonNull(candidateImpact);
            dimensions = List.copyOf(dimensions);
            if (profileCoverage.status() != CatalogProfileImpactCoverageV6Service.Status.INCOMPLETE
                    || !evaluatedAt.equals(profileCoverage.evaluatedAt()) || !evaluatedAt.equals(candidateImpact.evaluatedAt())
                    || !profileCoverage.scenarioSetSha256().equals(candidateImpact.scenarioSetSha256())
                    || !dimensions.equals(candidateDimensions(profileCoverage, candidateImpact)))
                throw new IllegalArgumentException("Unbound candidate impact coverage");
        }
        @JsonProperty public String status() { return "INCOMPLETE"; }
        @JsonProperty public String scope() { return "CONDITIONAL_AUDITABILITY_PROFILE_IMPACT_COVERAGE"; }
        @JsonProperty public String analysisBasis() { return "STRUCTURAL_PROFILE_RULES_AND_BOUND_CONDITIONAL_AUDITABILITY_IMPACT"; }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public String manifestSha256() { return MANIFEST_SHA256; }
        @JsonProperty public String profileCoverageSha256() { return CatalogDraftCanonicalizer.sha256(profileCoverage); }
        @JsonProperty public String candidateImpactSha256() { return candidateImpact.analysisSha256(); }
        @JsonProperty public String analysisSha256() { return CatalogDraftCanonicalizer.sha256(List.of(POLICY_VERSION,
                evaluatedAt, MANIFEST_SHA256, profileCoverageSha256(), candidateImpactSha256(), dimensions, structuralOnlyDimensions())); }
        @JsonProperty public int checkedAuditabilityDimensions() { return dimensions.size(); }
        @JsonProperty public List<CatalogProfileImpactCoverageV6Service.DimensionCheck> structuralOnlyDimensions() {
            return profileCoverage.dimensions().stream().filter(d -> d.boundary() != Boundary.AUDITABILITY).toList();
        }
        @JsonProperty public boolean candidateAuditabilityChangesEvaluated() { return true; }
        @JsonProperty public boolean storedReviewsVerified() { return true; }
        @JsonProperty public boolean coverageComplete() { return false; }
        @JsonProperty public boolean configurationVerified() { return false; }
        @JsonProperty public boolean complianceVerified() { return false; }
        @JsonProperty public boolean storedReportVerified() { return false; }
        @JsonProperty public boolean sourceVerificationPerformed() { return false; }
        @JsonProperty public boolean factTrustChanged() { return false; }
        @JsonProperty public boolean baselineVerified() { return false; }
        @JsonProperty public boolean approvalGranted() { return false; }
        @JsonProperty public boolean writesPerformed() { return false; }
        @JsonProperty public boolean publicationReady() { return false; }
        @JsonProperty public boolean evaluationReady() { return false; }
        @JsonProperty public boolean recommendationReady() { return false; }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Check preview(Request request) {
        Objects.requireNonNull(request);
        var impact = impacts.preview(request);
        try {
            if (!impact.beforeReview().reviewId().equals(request.beforeReviewId())
                    || !impact.beforeReview().reviewSha256().equals(request.expectedBeforeReviewSha256())
                    || !impact.afterReview().reviewId().equals(request.afterReviewId())
                    || !impact.afterReview().reviewSha256().equals(request.expectedAfterReviewSha256())
                    || !impact.scenarioSetSha256().equals(cases.sha256())) throw new IllegalArgumentException("Foreign impact binding");
            for (var row : impact.scenarios()) {
                var definition = cases.definitions().stream().filter(d -> d.scenarioId().equals(row.scenarioId())).findFirst().orElseThrow();
                if (!definition.profileSha256().equals(row.profileSha256()) || !definition.requirements().equals(row.requirements()))
                    throw new IllegalArgumentException("Foreign scenario inputs");
            }
            var structural = coverage.inspectAt(impact.evaluatedAt());
            return new Check(impact.evaluatedAt(), structural, impact, candidateDimensions(structural, impact));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new CatalogAuditabilityReviewException(READ_UNAVAILABLE);
        }
        // Missing/corrupt reviews and DB failures propagate; no fallback or durable impact write.
    }

    private static List<Dimension> candidateDimensions(CatalogProfileImpactCoverageV6Service.Check structural, Impact impact) {
        var result = new ArrayList<Dimension>();
        for (var scenario : impact.scenarios()) {
            var selected = Arrays.stream(Criterion.values()).filter(scenario.requirements().selectedCriteria()::contains).toList();
            var definitions = structural.dimensions().stream().filter(d -> d.scenarioId().equals(scenario.scenarioId()) && d.boundary() == Boundary.AUDITABILITY).toList();
            if (definitions.size() != 3) throw new IllegalArgumentException("Missing structural audit dimensions");
            for (var check : scenario.checks()) {
                boolean applied = selected.contains(check.criterion());
                if (applied == (check.before().conditionalOutcome() == Outcome.NOT_APPLIED)
                        || applied == (check.after().conditionalOutcome() == Outcome.NOT_APPLIED))
                    throw new IllegalArgumentException("Audit impact selection disagrees");
            }
            for (var definition : definitions) {
                var criteria = definition.profilePath().endsWith("minimumRetentionDays")
                        ? selected.stream().filter(c -> c == Criterion.AUDIT_LOG_RETENTION).toList() : selected;
                if (!criteria.equals(definition.evidenceCriteria())) throw new IllegalArgumentException("Foreign audit dependencies");
                var checks = scenario.checks().stream().filter(c -> criteria.contains(c.criterion())).toList();
                result.add(new Dimension(scenario.scenarioId(), scenario.profileSha256(), scenario.optionScope(), definition.profilePath(), criteria,
                        outcomes(checks.stream().map(c -> c.before().conditionalOutcome()).toList()),
                        outcomes(checks.stream().map(c -> c.after().conditionalOutcome()).toList()),
                        (int) checks.stream().filter(CatalogAuditabilityImpactService.Check::factChanged).count(),
                        (int) checks.stream().filter(CatalogAuditabilityImpactService.Check::conditionalResultChanged).count()));
            }
        }
        return List.copyOf(result);
    }
    private static Outcomes outcomes(List<Outcome> values) {
        return new Outcomes((int) values.stream().filter(v -> v == Outcome.WOULD_SATISFY).count(),
                (int) values.stream().filter(v -> v == Outcome.WOULD_VIOLATE).count(),
                (int) values.stream().filter(v -> v == Outcome.INDETERMINATE).count());
    }
}

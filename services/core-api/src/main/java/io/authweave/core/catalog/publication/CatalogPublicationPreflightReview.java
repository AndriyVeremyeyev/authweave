package io.authweave.core.catalog.publication;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.authweave.core.catalog.impact.CatalogProfilePlanningCoverageService;

/** Bounded display projection of a fresh Core denial, not a receipt or publication token. */
public record CatalogPublicationPreflightReview(CatalogPublicationPreflight.Mode mode, UUID inputId,
        Long inputVersion, String inputSha256, Instant evaluatedAt,
        CatalogPublicationPreflight.FactCounts facts, long reviewThroughNumber,
        PlanningSummary planning, List<CatalogPublicationPreflight.Blocker> blockers) {
    public CatalogPublicationPreflightReview {
        Objects.requireNonNull(mode); Objects.requireNonNull(inputId); Objects.requireNonNull(evaluatedAt);
        Objects.requireNonNull(facts); blockers = List.copyOf(blockers);
        if (inputSha256 == null || !inputSha256.matches("[a-f0-9]{64}")
                || (mode == CatalogPublicationPreflight.Mode.CURATED_BOOTSTRAP ? inputVersion != null
                    : inputVersion == null || inputVersion < 0 || inputVersion > 9007199254740991L)
                || reviewThroughNumber < 0 || reviewThroughNumber > 9007199254740991L
                || new java.util.HashSet<>(blockers).size() != blockers.size()
                || !blockers.containsAll(List.of(CatalogPublicationPreflight.Blocker.IMPACT_COVERAGE_INCOMPLETE,
                    CatalogPublicationPreflight.Blocker.CURATOR_AUTHORIZATION_NOT_PERFORMED,
                    CatalogPublicationPreflight.Blocker.PUBLICATION_WORKFLOW_UNAVAILABLE))
                || planning != null && !evaluatedAt.equals(planning.evaluatedAt()))
            throw new IllegalArgumentException("Invalid publication preflight review");
    }
    public record Regression(CatalogProfilePlanningCoverageService.Family family, int checkedCases) { }
    public record PlanningSummary(Instant evaluatedAt, String analysisSha256, int checkedDimensions,
            int structuralVerificationGaps, int planningVerificationGaps, List<Regression> regressions) {
        public PlanningSummary { regressions = List.copyOf(regressions); }
        @JsonProperty public String status() { return "INCOMPLETE"; }
        @JsonProperty public String policyVersion() { return CatalogProfilePlanningCoverageService.POLICY_VERSION; }
    }
    public static CatalogPublicationPreflightReview from(CatalogPublicationPreflight.Result result,
            UUID id, Long version, String digest) {
        if (result.mode() == CatalogPublicationPreflight.Mode.PROPOSAL_APPROVAL
                && (!id.equals(result.proposalId()) || !Objects.equals(version, result.proposalVersion())
                    || !digest.equals(result.proposalSha256()))) throw new IllegalArgumentException("Unbound preflight review");
        if (result.mode() == CatalogPublicationPreflight.Mode.CURATED_BOOTSTRAP && result.profilePlanningCoverage() != null
                && (!id.equals(result.bootstrapImpact().reviewId()) || !digest.equals(result.bootstrapImpact().reviewSha256())))
            throw new IllegalArgumentException("Unbound bootstrap preflight review");
        var report = result.profilePlanningCoverage();
        var summary = report == null ? null : new PlanningSummary(report.evaluatedAt(), report.analysisSha256(),
                report.checkedDimensions(), report.structuralCoverage().verificationGaps().size(), report.verificationGaps().size(),
                report.regressions().stream().map(r -> new Regression(r.family(), r.checkedCases())).toList());
        return new CatalogPublicationPreflightReview(result.mode(), id, version, digest, result.evaluatedAt(),
                result.facts(), result.reviewThroughNumber(), summary, result.blockers());
    }
    @JsonProperty public int schemaVersion() { return 1; }
    @JsonProperty public String scope() { return "CATALOG_PUBLICATION_PREFLIGHT_REVIEW"; }
    @JsonProperty public String policyVersion() { return CatalogPublicationPreflight.POLICY_VERSION; }
    @JsonProperty public String status() { return "BLOCKED"; }
    @JsonProperty public boolean coverageComplete() { return false; }
    @JsonProperty public boolean baselineVerified() { return false; }
    @JsonProperty public boolean sourceVerificationPerformed() { return false; }
    @JsonProperty public boolean approvalGranted() { return false; }
    @JsonProperty public boolean publicationReady() { return false; }
    @JsonProperty public boolean evaluationReady() { return false; }
    @JsonProperty public boolean writesPerformed() { return false; }
}

package io.authweave.core.catalog.publication;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.catalog.draft.CatalogChangePreviewRequest;
import io.authweave.core.catalog.draft.CatalogChangePreviewService;
import io.authweave.core.catalog.draft.CatalogChangePreview;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.draft.CatalogDraftValidation;
import io.authweave.core.catalog.draft.CatalogDraftValidator;
import io.authweave.core.catalog.draft.ProviderCatalogDraft;
import io.authweave.core.catalog.proposal.CatalogFactReview;
import io.authweave.core.catalog.proposal.CatalogFactReviewRepository;
import io.authweave.core.catalog.impact.CatalogFactPathRegressionService;
import io.authweave.core.catalog.impact.CatalogBootstrapImpactService;
import io.authweave.core.catalog.impact.CatalogProfileImpactCoverageService;
import io.authweave.core.catalog.impact.CatalogProfileImpactCoverageV6Service;
import io.authweave.core.catalog.impact.CatalogScopedProfileImpactService;

/** Core-owned denial policy, not authorization, a prepared publication token or a publisher. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CatalogPublicationPreflight {
    public static final String POLICY_VERSION = "catalog-publication-preflight-11";
    private static final long MAX_SAFE_INTEGER = 9007199254740991L;
    private final CatalogPublicationPreflightRepository repository;
    private final CatalogFactReviewRepository reviews;
    private final CatalogPublicationRepository publications;
    private final CatalogPublicationLookup lookup;
    private final CatalogDraftValidator validator;
    private final CatalogChangePreviewService preview;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final CatalogBootstrapReviewService bootstrapReviews;
    private final CatalogPublicationImpactVerifier impacts;
    private final CatalogFactPathRegressionService regressions;
    private final CatalogPublicationFactPathVerifier storedRegressions;
    private final CatalogBootstrapImpactService bootstrapImpacts;
    private final CatalogPublicationBootstrapImpactVerifier storedBootstrapImpacts;
    private final CatalogProfileImpactCoverageService profileCoverage;
    private final CatalogScopedProfileImpactService scopedImpacts;
    private final CatalogProfileImpactCoverageV6Service profileCoverageV6;

    CatalogPublicationPreflight(CatalogPublicationPreflightRepository repository, CatalogFactReviewRepository reviews,
            CatalogPublicationRepository publications, CatalogPublicationLookup lookup, CatalogDraftValidator validator,
            CatalogChangePreviewService preview, ObjectMapper mapper, Clock clock, CatalogBootstrapReviewService bootstrapReviews,
            CatalogPublicationImpactVerifier impacts, CatalogFactPathRegressionService regressions,
            CatalogPublicationFactPathVerifier storedRegressions, CatalogBootstrapImpactService bootstrapImpacts,
            CatalogPublicationBootstrapImpactVerifier storedBootstrapImpacts, CatalogProfileImpactCoverageService profileCoverage,
            CatalogScopedProfileImpactService scopedImpacts, CatalogProfileImpactCoverageV6Service profileCoverageV6) {
        this.repository = repository; this.reviews = reviews; this.publications = publications; this.lookup = lookup;
        this.validator = validator; this.preview = preview; this.mapper = mapper; this.clock = clock;
        this.bootstrapReviews = bootstrapReviews;
        this.impacts = impacts;
        this.regressions = regressions;
        this.storedRegressions = storedRegressions;
        this.bootstrapImpacts = bootstrapImpacts;
        this.storedBootstrapImpacts = storedBootstrapImpacts;
        this.profileCoverage = profileCoverage;
        this.scopedImpacts = scopedImpacts;
        this.profileCoverageV6 = profileCoverageV6;
    }

    public enum Mode { PROPOSAL_APPROVAL, CURATED_BOOTSTRAP }
    public enum Blocker {
        PROPOSAL_NOT_FOUND, PROPOSAL_READ_BUDGET_EXCEEDED, PROPOSAL_FORMAT_INVALID, PROPOSAL_DIGEST_MISMATCH,
        PROPOSAL_NOT_CURRENT, PROPOSAL_REJECTED, PROPOSAL_ALREADY_PUBLISHED, CHANGE_NOT_REVIEWABLE,
        CANDIDATE_INVALID, CATALOG_LABEL_ALREADY_USED, FACT_REVIEW_LEDGER_INVALID,
        FACT_OBSERVATIONS_MISSING, SOURCE_CONTRADICTION, INSUFFICIENT_SOURCE_EVIDENCE,
        FACT_EVIDENCE_STALE, FACT_EVIDENCE_FUTURE,
        BASELINE_REFERENCE_MISSING, BASELINE_INTEGRITY_UNAVAILABLE, BASELINE_CONTENT_MISMATCH,
        BASELINE_NOT_TIP, BASELINE_AUTHORITY_UNAVAILABLE, BOOTSTRAP_REGISTRY_NOT_EMPTY,
        BOOTSTRAP_REVIEW_WORKFLOW_UNAVAILABLE, BOOTSTRAP_REVIEW_UNAVAILABLE, BOOTSTRAP_IMPACT_WORKFLOW_UNAVAILABLE,
        BOOTSTRAP_IMPACT_ANALYSIS_BLOCKED, BOOTSTRAP_IMPACT_ANALYSIS_INCOMPLETE,
        BOOTSTRAP_IMPACT_RECEIPT_MISSING, BOOTSTRAP_IMPACT_REPORT_READ_BUDGET_EXCEEDED, BOOTSTRAP_IMPACT_RECEIPT_INVALID,
        BOOTSTRAP_IMPACT_RULES_INCOMPATIBLE, BOOTSTRAP_IMPACT_REPLAY_MISMATCH,
        STORED_BOOTSTRAP_IMPACT_ANALYSIS_BLOCKED, STORED_BOOTSTRAP_IMPACT_ANALYSIS_INCOMPLETE,
        IMPACT_RECEIPT_MISSING, IMPACT_REPORT_READ_BUDGET_EXCEEDED, IMPACT_RECEIPT_INVALID,
        IMPACT_RULES_INCOMPATIBLE, IMPACT_REPLAY_MISMATCH, IMPACT_ANALYSIS_BLOCKED,
        FACT_PATH_REGRESSION_BLOCKED, FACT_PATH_REGRESSION_INCOMPLETE, IMPACT_COVERAGE_INCOMPLETE,
        FACT_PATH_RECEIPT_MISSING, FACT_PATH_REPORT_READ_BUDGET_EXCEEDED, FACT_PATH_RECEIPT_INVALID,
        FACT_PATH_RULES_INCOMPATIBLE, FACT_PATH_REPLAY_MISMATCH, STORED_FACT_PATH_ANALYSIS_BLOCKED, STORED_FACT_PATH_ANALYSIS_INCOMPLETE,
        SCOPED_PROFILE_REGRESSION_BLOCKED, SCOPED_PROFILE_REGRESSION_INCOMPLETE,
        CURATOR_AUTHORIZATION_NOT_PERFORMED, PUBLICATION_WORKFLOW_UNAVAILABLE
    }

    public record FactCounts(int total, int unobserved, int supporting, int contradicting, int insufficient,
            int stale, int future) {
        public FactCounts {
            if (total < 0 || total > 6800 || unobserved < 0 || supporting < 0 || contradicting < 0 || insufficient < 0
                    || (long) unobserved + supporting + contradicting + insufficient != total
                    || stale < 0 || future < 0 || (long) stale + future > total) {
                throw new IllegalArgumentException("Invalid fact counts");
            }
        }
        @JsonProperty public boolean allFactsHaveSupportingObservation() { return total > 0 && supporting == total; }
    }

    public record Result(Mode mode, Instant evaluatedAt, UUID proposalId, Long proposalVersion,
            String proposalSha256, FactCounts facts, long reviewThroughNumber,
            boolean baselineIntegrityValidated, boolean baselineContentMatches, CatalogPublicationImpactVerifier.Check impact,
            CatalogFactPathRegressionService.Check factPaths,
            CatalogPublicationFactPathVerifier.Check storedFactPaths,
            CatalogBootstrapImpactService.Check bootstrapImpact,
            CatalogPublicationBootstrapImpactVerifier.Check storedBootstrapImpact,
            CatalogProfileImpactCoverageService.Check profileImpactCoverage,
            CatalogProfileImpactCoverageV6Service.Check profileImpactCoverageV6,
            CatalogScopedProfileImpactService.Check scopedProfileImpact,
            List<Blocker> blockers) {
        public Result {
            Objects.requireNonNull(mode); Objects.requireNonNull(evaluatedAt); Objects.requireNonNull(facts);
            Objects.requireNonNull(impact);
            Objects.requireNonNull(factPaths);
            Objects.requireNonNull(storedFactPaths);
            Objects.requireNonNull(bootstrapImpact);
            Objects.requireNonNull(storedBootstrapImpact);
            Objects.requireNonNull(profileImpactCoverage);
            Objects.requireNonNull(profileImpactCoverageV6);
            Objects.requireNonNull(scopedProfileImpact);
            blockers = List.copyOf(blockers);
            if (blockers.isEmpty() || new HashSet<>(blockers).size() != blockers.size()
                    || !blockers.contains(Blocker.PUBLICATION_WORKFLOW_UNAVAILABLE)
                    || !blockers.contains(Blocker.CURATOR_AUTHORIZATION_NOT_PERFORMED)
                    || !blockers.contains(Blocker.IMPACT_COVERAGE_INCOMPLETE)
                    || profileImpactCoverage.coverageComplete()
                    || profileImpactCoverageV6.coverageComplete()
                    || (profileImpactCoverage.status() == CatalogProfileImpactCoverageService.Status.NOT_CHECKED)
                        != (profileImpactCoverageV6.status() == CatalogProfileImpactCoverageV6Service.Status.NOT_CHECKED)
                    || profileImpactCoverageV6.status() != CatalogProfileImpactCoverageV6Service.Status.NOT_CHECKED
                        && (!evaluatedAt.equals(profileImpactCoverageV6.evaluatedAt())
                            || !profileImpactCoverage.scenarioSetSha256().equals(profileImpactCoverageV6.baseScenarioSetSha256())
                            || !CatalogDraftCanonicalizer.sha256(profileImpactCoverage).equals(profileImpactCoverageV6.catalogCoverageSha256()))
                    || (profileImpactCoverage.status() == CatalogProfileImpactCoverageService.Status.NOT_CHECKED)
                        != (scopedProfileImpact.status() == CatalogScopedProfileImpactService.Status.NOT_CHECKED)
                    || profileImpactCoverage.status() != CatalogProfileImpactCoverageService.Status.NOT_CHECKED
                        && !evaluatedAt.equals(profileImpactCoverage.evaluatedAt())
                    || scopedProfileImpact.status() != CatalogScopedProfileImpactService.Status.NOT_CHECKED
                        && (!evaluatedAt.equals(scopedProfileImpact.evaluatedAt())
                            || profileImpactCoverage.status() == CatalogProfileImpactCoverageService.Status.NOT_CHECKED
                            || !profileImpactCoverage.scenarioSetSha256().equals(scopedProfileImpact.scenarioSetSha256())
                            || mode == Mode.PROPOSAL_APPROVAL && (scopedProfileImpact.mode() != CatalogScopedProfileImpactService.Mode.PROPOSAL_COMPARISON
                                || !Objects.equals(proposalId, scopedProfileImpact.inputId()) || !Objects.equals(proposalSha256, scopedProfileImpact.inputSha256()))
                            || mode == Mode.CURATED_BOOTSTRAP && (scopedProfileImpact.mode() != CatalogScopedProfileImpactService.Mode.CURATED_BOOTSTRAP
                                || !Objects.equals(bootstrapImpact.reviewId(), scopedProfileImpact.inputId())
                                || !Objects.equals(bootstrapImpact.reviewSha256(), scopedProfileImpact.inputSha256())
                                || !Objects.equals(bootstrapImpact.candidateSha256(), scopedProfileImpact.candidateSha256())))
                    || reviewThroughNumber < 0 || reviewThroughNumber > MAX_SAFE_INTEGER
                    || baselineContentMatches && !baselineIntegrityValidated
                    || mode == Mode.CURATED_BOOTSTRAP && impact.status() != CatalogPublicationImpactVerifier.Status.NOT_CHECKED
                    || mode == Mode.CURATED_BOOTSTRAP && factPaths.status() != CatalogFactPathRegressionService.Status.NOT_CHECKED
                    || mode == Mode.CURATED_BOOTSTRAP && storedFactPaths.status() != CatalogPublicationFactPathVerifier.Status.NOT_CHECKED
                    || mode == Mode.PROPOSAL_APPROVAL && bootstrapImpact.status() != CatalogBootstrapImpactService.CheckStatus.NOT_CHECKED
                    || mode == Mode.PROPOSAL_APPROVAL && storedBootstrapImpact.status() != CatalogPublicationBootstrapImpactVerifier.Status.NOT_CHECKED
                    || (mode == Mode.CURATED_BOOTSTRAP ? proposalId != null || proposalVersion != null || proposalSha256 != null
                        : proposalId == null || proposalVersion == null || proposalSha256 == null)) {
                throw new IllegalArgumentException("Inconsistent preflight result");
            }
        }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public String status() { return "BLOCKED"; }
        @JsonProperty public boolean coverageComplete() { return false; }
        @JsonProperty public boolean baselineVerified() { return false; }
        @JsonProperty public boolean sourceVerificationPerformed() { return false; }
        @JsonProperty public boolean approvalGranted() { return false; }
        @JsonProperty public boolean writesPerformed() { return false; }
        @JsonProperty public boolean publicationReady() { return false; }
        @JsonProperty public boolean evaluationReady() { return false; }
    }

    /** Exact stored revision, never caller-supplied evidence, verdict totals, trusted booleans or manifests. */
    public Result proposal(UUID id, long version, String expectedSha256, PublishedCatalogSnapshot.Reference baseline) {
        Objects.requireNonNull(id);
        if (version < 0 || version > MAX_SAFE_INTEGER || expectedSha256 == null || !expectedSha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Use an exact proposal revision and SHA-256");
        }
        var at = clock.instant(); var blockers = mandatory(); blockers.add(Blocker.BASELINE_AUTHORITY_UNAVAILABLE);
        var row = repository.proposal(id, version);
        if (row == null) {
            blockers.add(Blocker.PROPOSAL_NOT_FOUND);
            return result(Mode.PROPOSAL_APPROVAL, at, id, version, expectedSha256, empty(), 0, false, false, blockers);
        }
        if (!Objects.equals(row.currentVersion(), version)) blockers.add(Blocker.PROPOSAL_NOT_CURRENT);
        if (row.rejected()) blockers.add(Blocker.PROPOSAL_REJECTED);
        if (row.published()) blockers.add(Blocker.PROPOSAL_ALREADY_PUBLISHED);
        if (!expectedSha256.equals(row.sha256())) {
            blockers.add(Blocker.PROPOSAL_DIGEST_MISMATCH);
            return result(Mode.PROPOSAL_APPROVAL, at, id, version, expectedSha256, empty(), 0, false, false, blockers);
        }
        if (row.requestBytes() <= 0 || row.requestBytes() > CatalogPublicationRepository.MAX_JSON_BYTES) {
            blockers.add(Blocker.PROPOSAL_READ_BUDGET_EXCEEDED);
            return result(Mode.PROPOSAL_APPROVAL, at, id, version, expectedSha256, empty(), 0, false, false, blockers);
        }
        CatalogChangePreviewRequest request;
        try {
            if (!id.equals(row.id()) || version != row.version() || row.schemaVersion() != 1 || !"PROPOSED".equals(row.state())
                    || row.request() == null || row.recordedAt() == null || row.recordedAt().isAfter(at.plusSeconds(30))) {
                throw new IllegalArgumentException("Invalid stored revision");
            }
            request = mapper.readValue(row.request(), CatalogChangePreviewRequest.class);
            if (request == null || !id.equals(request.proposalId()) || !row.sha256().equals(CatalogDraftCanonicalizer.sha256(request))) {
                throw new IllegalArgumentException("Stored request mismatch");
            }
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException invalid) {
            blockers.add(Blocker.PROPOSAL_FORMAT_INVALID);
            return result(Mode.PROPOSAL_APPROVAL, at, id, version, expectedSha256, empty(), 0, false, false, blockers);
        }
        var validation = validator.validateAt(request.candidate(), at);
        candidate(validation, blockers);
        if (preview.previewAt(request, at).status() != CatalogChangePreview.Status.REVIEW_REQUIRED) blockers.add(Blocker.CHANGE_NOT_REVIEWABLE);
        var observed = observations(row, validation, at, blockers);
        var factPaths = regressions.inspect(request, at);
        if (factPaths.status() != CatalogFactPathRegressionService.Status.ANALYZED) blockers.add(Blocker.FACT_PATH_REGRESSION_BLOCKED);
        else if (!factPaths.changedFactPathsCovered()) blockers.add(Blocker.FACT_PATH_REGRESSION_INCOMPLETE);
        var impact = impacts.verify(request, version, row.recordedAt(), at);
        switch (impact.status()) {
            case MISSING -> blockers.add(Blocker.IMPACT_RECEIPT_MISSING);
            case READ_BUDGET_EXCEEDED -> blockers.add(Blocker.IMPACT_REPORT_READ_BUDGET_EXCEEDED);
            case INVALID_RECEIPT, NOT_CHECKED -> blockers.add(Blocker.IMPACT_RECEIPT_INVALID);
            case INCOMPATIBLE_RULES -> blockers.add(Blocker.IMPACT_RULES_INCOMPATIBLE);
            case REPLAY_MISMATCH -> blockers.add(Blocker.IMPACT_REPLAY_MISMATCH);
            case VERIFIED_BLOCKED_ANALYSIS -> blockers.add(Blocker.IMPACT_ANALYSIS_BLOCKED);
            case VERIFIED_PARTIAL_ANALYSIS -> { /* Integrity does not remove the mandatory full-coverage gate. */ }
        }
        var storedFactPaths = storedRegressions.verify(request, version, row.recordedAt(), at);
        switch (storedFactPaths.status()) {
            case MISSING -> blockers.add(Blocker.FACT_PATH_RECEIPT_MISSING);
            case READ_BUDGET_EXCEEDED -> blockers.add(Blocker.FACT_PATH_REPORT_READ_BUDGET_EXCEEDED);
            case INVALID_RECEIPT, NOT_CHECKED -> blockers.add(Blocker.FACT_PATH_RECEIPT_INVALID);
            case INCOMPATIBLE_RULES -> blockers.add(Blocker.FACT_PATH_RULES_INCOMPATIBLE);
            case REPLAY_MISMATCH -> blockers.add(Blocker.FACT_PATH_REPLAY_MISMATCH);
            case VERIFIED_BLOCKED_ANALYSIS -> blockers.add(Blocker.STORED_FACT_PATH_ANALYSIS_BLOCKED);
            case VERIFIED_INCOMPLETE_ANALYSIS -> blockers.add(Blocker.STORED_FACT_PATH_ANALYSIS_INCOMPLETE);
            case VERIFIED_FACT_PATH_ANALYSIS -> { /* Exact changed-path coverage is not full-profile/bootstrap authority. */ }
        }
        boolean integrity = false, matches = false;
        if (baseline == null) blockers.add(Blocker.BASELINE_REFERENCE_MISSING);
        else {
            var comparison = lookup.compareBaseline(baseline, request);
            integrity = comparison.lookup().storedIntegrityValidated();
            matches = comparison.suppliedBaseDigestMatches() && comparison.suppliedBaseContentMatches();
            if (!integrity) blockers.add(Blocker.BASELINE_INTEGRITY_UNAVAILABLE);
            else {
                if (!matches) blockers.add(Blocker.BASELINE_CONTENT_MISMATCH);
                if (publications.successors(baseline.snapshotId()) != 0) blockers.add(Blocker.BASELINE_NOT_TIP);
            }
        }
        var coverage = profileCoverage.inspectAt(at); var scopedImpact = scopedImpacts.inspectAt(request, at); scopedBlockers(scopedImpact, blockers);
        return result(Mode.PROPOSAL_APPROVAL, at, id, version, row.sha256(), observed.counts(), observed.through(), integrity, matches,
                impact, factPaths, storedFactPaths, coverage, profileCoverageV6.inspectUsing(coverage, at), scopedImpact, blockers);
        // Storage errors propagate. This read is never reused as authorization for a later write.
    }

    /** First publication is a distinct workflow. An empty registry or a null parent is not bootstrap approval. */
    public Result bootstrap(ProviderCatalogDraft candidate) {
        Objects.requireNonNull(candidate);
        var at = clock.instant(); var blockers = mandatory();
        blockers.add(Blocker.BOOTSTRAP_REVIEW_WORKFLOW_UNAVAILABLE);
        blockers.add(Blocker.BOOTSTRAP_IMPACT_WORKFLOW_UNAVAILABLE);
        if (!repository.registryEmpty()) blockers.add(Blocker.BOOTSTRAP_REGISTRY_NOT_EMPTY);
        var validation = validator.validateAt(candidate, at); candidate(validation, blockers);
        var facts = counts(validation, 0, 0, 0); observationBlockers(facts, blockers);
        return result(Mode.CURATED_BOOTSTRAP, at, null, null, null, facts, 0, false, false, blockers);
    }

    /** Stored review is loaded by exact UUID/digest; caller-supplied supporting totals never enter this boundary. */
    public Result bootstrap(UUID reviewId, String expectedReviewSha256) {
        var at = clock.instant(); var blockers = mandatory();
        CatalogBootstrapReviewService.ReviewedCandidate reviewed;
        try { reviewed = bootstrapReviews.reviewed(reviewId, expectedReviewSha256); }
        catch (CatalogBootstrapReviewException invalid) {
            blockers.add(Blocker.BOOTSTRAP_REVIEW_UNAVAILABLE);
            return result(Mode.CURATED_BOOTSTRAP, at, null, null, null, empty(), 0, false, false, blockers);
        }
        if (!repository.registryEmpty()) blockers.add(Blocker.BOOTSTRAP_REGISTRY_NOT_EMPTY);
        var validation = validator.validateAt(reviewed.request().candidate(), at); candidate(validation, blockers);
        var counts = reviewed.review().counts();
        var facts = counts(validation, counts.supporting(), counts.contradicting(), counts.insufficient());
        observationBlockers(facts, blockers);
        var bootstrapImpact = bootstrapImpacts.inspectAt(reviewed.request(), at);
        if (bootstrapImpact.status() != CatalogBootstrapImpactService.CheckStatus.ANALYZED) blockers.add(Blocker.BOOTSTRAP_IMPACT_ANALYSIS_BLOCKED);
        else if (!bootstrapImpact.allDeclaredFactPathsChecked() || !bootstrapImpact.allFrozenScenariosChecked()) blockers.add(Blocker.BOOTSTRAP_IMPACT_ANALYSIS_INCOMPLETE);
        var storedBootstrapImpact = storedBootstrapImpacts.verify(reviewed.request(), reviewed.review().recordedAt(), at);
        switch (storedBootstrapImpact.status()) {
            case MISSING -> blockers.add(Blocker.BOOTSTRAP_IMPACT_RECEIPT_MISSING);
            case READ_BUDGET_EXCEEDED -> blockers.add(Blocker.BOOTSTRAP_IMPACT_REPORT_READ_BUDGET_EXCEEDED);
            case INVALID_RECEIPT, NOT_CHECKED -> blockers.add(Blocker.BOOTSTRAP_IMPACT_RECEIPT_INVALID);
            case INCOMPATIBLE_RULES -> blockers.add(Blocker.BOOTSTRAP_IMPACT_RULES_INCOMPATIBLE);
            case REPLAY_MISMATCH -> blockers.add(Blocker.BOOTSTRAP_IMPACT_REPLAY_MISMATCH);
            case VERIFIED_BLOCKED_ANALYSIS -> blockers.add(Blocker.STORED_BOOTSTRAP_IMPACT_ANALYSIS_BLOCKED);
            case VERIFIED_INCOMPLETE_ANALYSIS -> blockers.add(Blocker.STORED_BOOTSTRAP_IMPACT_ANALYSIS_INCOMPLETE);
            case VERIFIED_BOOTSTRAP_ANALYSIS -> { /* Declared paths/scenarios are not complete profile/source/authorization coverage. */ }
        }
        var coverage = profileCoverage.inspectAt(at); var scopedImpact = scopedImpacts.inspectAt(reviewed.request(), at); scopedBlockers(scopedImpact, blockers);
        return new Result(Mode.CURATED_BOOTSTRAP, at, null, null, null, facts, 0, false, false,
                CatalogPublicationImpactVerifier.Check.unavailable(CatalogPublicationImpactVerifier.Status.NOT_CHECKED),
                CatalogFactPathRegressionService.Check.notChecked(),
                CatalogPublicationFactPathVerifier.Check.unavailable(CatalogPublicationFactPathVerifier.Status.NOT_CHECKED), bootstrapImpact,
                storedBootstrapImpact, coverage, profileCoverageV6.inspectUsing(coverage, at), scopedImpact, List.copyOf(blockers));
        // Even supporting stored observations cannot replace fresh write authorization, full coverage or a publication workflow.
    }

    private void candidate(CatalogDraftValidation validation, EnumSet<Blocker> blockers) {
        if (validation.status() != CatalogDraftValidation.Status.VALID_DRAFT || validation.factCount() == 0) blockers.add(Blocker.CANDIDATE_INVALID);
        if (repository.labelUsed(validation.catalogVersion())) blockers.add(Blocker.CATALOG_LABEL_ALREADY_USED);
    }
    private static void scopedBlockers(CatalogScopedProfileImpactService.Check check, EnumSet<Blocker> blockers) {
        if (check.status() != CatalogScopedProfileImpactService.Status.ANALYZED) blockers.add(Blocker.SCOPED_PROFILE_REGRESSION_BLOCKED);
        else if (!check.allScopedScenariosChecked()) blockers.add(Blocker.SCOPED_PROFILE_REGRESSION_INCOMPLETE);
    }

    private Observations observations(CatalogPublicationPreflightRepository.Proposal row, CatalogDraftValidation validation,
            Instant at, EnumSet<Blocker> blockers) {
        var latest = reviews.latest(row.id(), row.version(), row.sha256());
        var targets = new HashSet<FactKey>(); validation.facts().forEach(f -> targets.add(new FactKey(f.optionId(), f.path())));
        var selected = new HashMap<FactKey, CatalogFactReview>(); var ids = new HashSet<UUID>(); var numbers = new HashSet<Long>();
        boolean valid = latest.size() <= 6800;
        for (var review : latest) {
            var key = new FactKey(review.optionId(), review.factPath());
            valid &= row.id().equals(review.proposalId()) && review.proposalVersion() == row.version() && row.sha256().equals(review.proposalSha256())
                    && review.reviewId() != null && ids.add(review.reviewId()) && review.reviewNumber() > 0
                    && review.reviewNumber() <= MAX_SAFE_INTEGER && numbers.add(review.reviewNumber()) && targets.contains(key)
                    && selected.put(key, review) == null && review.verdict() != null && review.recordedAt() != null
                    && !review.recordedAt().isBefore(row.recordedAt()) && !review.recordedAt().isAfter(at.plusSeconds(30))
                    && "HUMAN_SOURCE_REVIEW_OBSERVATION".equals(review.kind()) && !review.sourceVerificationPerformed()
                    && !review.approvalGranted() && !review.catalogWritesPerformed() && !review.factTrustChanged();
        }
        if (!valid) {
            blockers.add(Blocker.FACT_REVIEW_LEDGER_INVALID);
            var facts = counts(validation, 0, 0, 0); observationBlockers(facts, blockers); return new Observations(facts, 0);
        }
        int supporting = 0, contradicting = 0, insufficient = 0; long through = 0;
        for (var review : selected.values()) {
            through = Math.max(through, review.reviewNumber());
            switch (review.verdict()) {
                case SOURCE_SUPPORTS_CLAIM -> supporting++;
                case SOURCE_DOES_NOT_SUPPORT_CLAIM -> contradicting++;
                case INSUFFICIENT_EVIDENCE -> insufficient++;
            }
        }
        var facts = counts(validation, supporting, contradicting, insufficient);
        observationBlockers(facts, blockers); return new Observations(facts, through);
    }

    private static FactCounts counts(CatalogDraftValidation validation, int supporting, int contradicting, int insufficient) {
        int stale = 0, future = 0;
        for (var fact : validation.facts()) switch (fact.freshness()) { case STALE -> stale++; case FUTURE -> future++; default -> { } }
        return new FactCounts(validation.factCount(), validation.factCount() - supporting - contradicting - insufficient,
                supporting, contradicting, insufficient, stale, future);
    }
    private static void observationBlockers(FactCounts facts, EnumSet<Blocker> blockers) {
        if (facts.unobserved() > 0) blockers.add(Blocker.FACT_OBSERVATIONS_MISSING);
        if (facts.contradicting() > 0) blockers.add(Blocker.SOURCE_CONTRADICTION);
        if (facts.insufficient() > 0) blockers.add(Blocker.INSUFFICIENT_SOURCE_EVIDENCE);
        if (facts.stale() > 0) blockers.add(Blocker.FACT_EVIDENCE_STALE);
        if (facts.future() > 0) blockers.add(Blocker.FACT_EVIDENCE_FUTURE);
    }
    private static EnumSet<Blocker> mandatory() { return EnumSet.of(Blocker.IMPACT_COVERAGE_INCOMPLETE,
            Blocker.CURATOR_AUTHORIZATION_NOT_PERFORMED, Blocker.PUBLICATION_WORKFLOW_UNAVAILABLE); }
    private static FactCounts empty() { return new FactCounts(0, 0, 0, 0, 0, 0, 0); }
    private static Result result(Mode mode, Instant at, UUID id, Long version, String digest, FactCounts facts,
            long through, boolean integrity, boolean matches, EnumSet<Blocker> blockers) {
        return result(mode, at, id, version, digest, facts, through, integrity, matches,
                CatalogPublicationImpactVerifier.Check.unavailable(CatalogPublicationImpactVerifier.Status.NOT_CHECKED),
                CatalogFactPathRegressionService.Check.notChecked(),
                CatalogPublicationFactPathVerifier.Check.unavailable(CatalogPublicationFactPathVerifier.Status.NOT_CHECKED),
                CatalogProfileImpactCoverageService.Check.notChecked(), CatalogProfileImpactCoverageV6Service.Check.notChecked(), CatalogScopedProfileImpactService.Check.notChecked(), blockers);
    }
    private static Result result(Mode mode, Instant at, UUID id, Long version, String digest, FactCounts facts,
            long through, boolean integrity, boolean matches, CatalogPublicationImpactVerifier.Check impact,
            CatalogFactPathRegressionService.Check factPaths, CatalogPublicationFactPathVerifier.Check storedFactPaths,
            CatalogProfileImpactCoverageService.Check profileImpactCoverage, CatalogProfileImpactCoverageV6Service.Check profileImpactCoverageV6,
            CatalogScopedProfileImpactService.Check scopedProfileImpact, EnumSet<Blocker> blockers) {
        return new Result(mode, at, id, version, digest, facts, through, integrity, matches, impact, factPaths, storedFactPaths,
                CatalogBootstrapImpactService.Check.notChecked(),
                CatalogPublicationBootstrapImpactVerifier.Check.unavailable(CatalogPublicationBootstrapImpactVerifier.Status.NOT_CHECKED),
                profileImpactCoverage, profileImpactCoverageV6, scopedProfileImpact, List.copyOf(blockers));
    }
    private record FactKey(String optionId, String path) { }
    private record Observations(FactCounts counts, long through) { }
}

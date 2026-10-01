package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import io.authweave.core.catalog.draft.CatalogDraftFacts;
import io.authweave.core.catalog.draft.CatalogDraftValidator;
import io.authweave.core.catalog.draft.CatalogDraftValidation;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.catalog.publication.CatalogBootstrapReviewRequest;
import static io.authweave.core.catalog.impact.CatalogBootstrapImpact.*;

/** Pure first-candidate analysis, using the shared rule kernel; storage/review authority belongs to the caller's boundary. */
@Service
public final class CatalogBootstrapImpactService {
    public static final String POLICY_VERSION = "catalog-bootstrap-impact-1";
    private final CatalogDraftValidator validator;
    private final CatalogScenarioCases scenarios;
    public CatalogBootstrapImpactService(CatalogDraftValidator validator, CatalogScenarioCases scenarios) {
        this.validator = validator; this.scenarios = scenarios;
    }

    public CatalogBootstrapImpact analyzeAt(CatalogBootstrapReviewRequest request, Instant at) {
        Objects.requireNonNull(request); Objects.requireNonNull(at);
        var candidate = request.candidate(); var validation = validator.validateAt(candidate, at);
        var blockers = new ArrayList<Blocker>();
        if (!validation.contentSha256().equals(request.expectedCandidateSha256())) blockers.add(Blocker.CANDIDATE_DIGEST_MISMATCH);
        if (validation.status() != CatalogDraftValidation.Status.VALID_DRAFT || validation.factCount() == 0) blockers.add(Blocker.INVALID_CANDIDATE);
        var checks = new ArrayList<FactCase>(); var results = new ArrayList<ScenarioCase>();
        var gaps = new ArrayList<UncoveredFact>(); var scenarioGaps = new ArrayList<UncoveredFact>();
        if (blockers.isEmpty()) {
            var paths = CatalogFactPathRegressionCases.PROBES.stream().map(CatalogImpactCases.Probe::factPath).collect(Collectors.toSet());
            var dependencies = scenarios.plans().stream().flatMap(List::stream).filter(ScenarioRulePlan.Rule::usesFact)
                    .map(ScenarioRulePlan.Rule::factPath).collect(Collectors.toSet());
            for (var option : candidate.options().stream().sorted(java.util.Comparator.comparing(o -> o.id())).toList()) {
                var facts = CatalogDraftFacts.entries(option);
                for (var probe : CatalogFactPathRegressionCases.PROBES)
                    checks.add(new FactCase(probe.id(), option.id(), probe.factPath(), CatalogImpactService.side(probe, true, facts.get(probe.factPath()), at)));
                facts.keySet().stream().sorted().forEach(path -> {
                    if (!paths.contains(path)) gaps.add(new UncoveredFact(option.id(), path, "NO_BOOTSTRAP_PROBE_FOR_FACT_PATH"));
                    if (!dependencies.contains(path)) scenarioGaps.add(new UncoveredFact(option.id(), path, "NO_BOOTSTRAP_SCENARIO_DEPENDENCY"));
                });
                for (int i = 0; i < scenarios.definitions().size(); i++)
                    results.add(new ScenarioCase(scenarios.definitions().get(i).id(), option.id(),
                            CatalogScenarioImpactService.evaluate(scenarios.plans().get(i), option, at)));
            }
        }
        return new CatalogBootstrapImpact(blockers.isEmpty() ? Status.ANALYZED : Status.BLOCKED, at, request.reviewId(),
                CatalogDraftCanonicalizer.sha256(request), validation.contentSha256(), candidate.catalogVersion(), validation,
                blockers, CatalogFactPathRegressionCases.PROBES, checks, scenarios.sha256(), scenarios.definitions(), results, gaps, scenarioGaps);
        // Observations bind request identity but never alter outcomes, freshness or proposed evidence trust.
    }

    public enum CheckStatus { NOT_CHECKED, BLOCKED, ANALYZED }
    /** Small fresh summary. It is not a stored impact receipt and carries no source/profile/actor bodies. */
    public record Check(CheckStatus status, Instant evaluatedAt, UUID reviewId, String reviewSha256, String candidateSha256,
            String reportSha256, int optionCount, int recordedFacts, int checkedFactPaths, int missingFactPaths,
            int violatingFactPaths, int indeterminateFactPaths, int uncoveredFacts, int checkedScenarios, int scenarioUncoveredFacts) {
        public Check {
            Objects.requireNonNull(status);
            if (status == CheckStatus.NOT_CHECKED ? evaluatedAt != null || reviewId != null || reviewSha256 != null || candidateSha256 != null || reportSha256 != null
                    || optionCount != 0 || recordedFacts != 0 || checkedFactPaths != 0 || missingFactPaths != 0 || violatingFactPaths != 0
                    || indeterminateFactPaths != 0 || uncoveredFacts != 0 || checkedScenarios != 0 || scenarioUncoveredFacts != 0
                    : evaluatedAt == null || reviewId == null || !hash(reviewSha256) || !hash(candidateSha256) || !hash(reportSha256)
                        || optionCount < 0 || optionCount > 100 || recordedFacts < 0 || recordedFacts > optionCount * CatalogFactPathRegressionCases.FACT_PATH_COUNT
                        || checkedFactPaths < 0 || checkedFactPaths > optionCount * CatalogFactPathRegressionCases.FACT_PATH_COUNT
                        || missingFactPaths < 0 || violatingFactPaths < 0 || indeterminateFactPaths < missingFactPaths
                        || (long) violatingFactPaths + indeterminateFactPaths > checkedFactPaths
                        || uncoveredFacts < 0 || uncoveredFacts > recordedFacts || checkedScenarios < 0 || checkedScenarios > optionCount * 3
                        || scenarioUncoveredFacts < 0 || scenarioUncoveredFacts > recordedFacts
                        || status == CheckStatus.BLOCKED && (optionCount != 0 || recordedFacts != 0 || checkedFactPaths != 0
                            || missingFactPaths != 0 || violatingFactPaths != 0 || indeterminateFactPaths != 0 || uncoveredFacts != 0
                            || checkedScenarios != 0 || scenarioUncoveredFacts != 0)) {
                throw new IllegalArgumentException("Inconsistent bootstrap impact summary");
            }
        }
        public static Check notChecked() { return new Check(CheckStatus.NOT_CHECKED, null, null, null, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0); }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public int declaredFactPaths() { return CatalogFactPathRegressionCases.FACT_PATH_COUNT; }
        @JsonProperty public int declaredScenarios() { return 3; }
        @JsonProperty public boolean allDeclaredFactPathsChecked() { return status == CheckStatus.ANALYZED && optionCount > 0
                && checkedFactPaths == optionCount * declaredFactPaths() && uncoveredFacts == 0; }
        @JsonProperty public boolean allFrozenScenariosChecked() { return status == CheckStatus.ANALYZED && optionCount > 0 && checkedScenarios == optionCount * declaredScenarios(); }
        @JsonProperty public boolean storedReportVerified() { return false; }
        @JsonProperty public boolean coverageComplete() { return false; }
        @JsonProperty public boolean baselineVerified() { return false; }
        @JsonProperty public boolean sourceVerificationPerformed() { return false; }
        @JsonProperty public boolean approvalGranted() { return false; }
        @JsonProperty public boolean writesPerformed() { return false; }
        @JsonProperty public boolean publicationReady() { return false; }
        @JsonProperty public boolean evaluationReady() { return false; }
        private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    }
    public Check inspectAt(CatalogBootstrapReviewRequest request, Instant at) {
        return summarize(analyzeAt(request, at));
    }
    /** Body-free summary of an already computed report; never refreshes its evaluation time. */
    public Check summarize(CatalogBootstrapImpact report) {
        boolean analyzed = report.status() == Status.ANALYZED;
        return new Check(analyzed ? CheckStatus.ANALYZED : CheckStatus.BLOCKED, report.evaluatedAt(), report.reviewId(), report.reviewSha256(), report.candidateSha256(),
                CatalogDraftCanonicalizer.sha256(report), analyzed ? report.validation().optionCount() : 0, analyzed ? report.validation().factCount() : 0,
                report.cases().size(), (int) report.cases().stream().filter(c -> !c.result().factPresent()).count(),
                (int) report.cases().stream().filter(c -> c.result().conditionalOutcome() == CatalogImpactPreview.Outcome.WOULD_VIOLATE).count(),
                (int) report.cases().stream().filter(c -> c.result().conditionalOutcome() == CatalogImpactPreview.Outcome.INDETERMINATE).count(),
                report.uncoveredFacts().size(), report.scenarios().size(), report.scenarioUncoveredFacts().size());
    }
}

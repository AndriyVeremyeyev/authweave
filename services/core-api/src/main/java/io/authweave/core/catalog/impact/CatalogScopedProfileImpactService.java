package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.publication.CatalogBootstrapReviewRequest;
import static io.authweave.core.catalog.impact.CatalogImpactPreview.Outcome.*;

/** Fresh conditional regression on additional source-controlled profiles, not a stored receipt or permission to publish. */
@Service
public final class CatalogScopedProfileImpactService {
    public static final String POLICY_VERSION = "catalog-scoped-profile-impact-1";
    private final CatalogScopedProfileCases cases;
    private final CatalogChangePreviewService previews;
    private final CatalogDraftValidator validator;
    public CatalogScopedProfileImpactService(CatalogScopedProfileCases cases, CatalogChangePreviewService previews, CatalogDraftValidator validator) {
        this.cases = cases; this.previews = previews; this.validator = validator;
    }
    public enum Mode { NOT_CHECKED, PROPOSAL_COMPARISON, CURATED_BOOTSTRAP }
    public enum Status { NOT_CHECKED, BLOCKED, ANALYZED }
    public record Case(String scenarioId, String optionId, boolean scopeChanged, List<String> consideredFactPaths,
            List<String> changedCheckIds, CatalogScenarioImpact.Side before, CatalogScenarioImpact.Side after) {
        public Case {
            consideredFactPaths = List.copyOf(consideredFactPaths); changedCheckIds = List.copyOf(changedCheckIds);
            if (!CatalogScopedProfileCases.IDS.contains(scenarioId) || optionId == null || !optionId.matches("[a-z0-9][a-z0-9.-]{0,99}")
                    || consideredFactPaths.size() > CatalogFactPathRegressionCases.FACT_PATH_COUNT || new HashSet<>(consideredFactPaths).size() != consideredFactPaths.size()
                    || consideredFactPaths.stream().anyMatch(p -> CatalogFactPathRegressionCases.PROBES.stream().noneMatch(d -> d.factPath().equals(p)))
                    || changedCheckIds.size() > 100 || new HashSet<>(changedCheckIds).size() != changedCheckIds.size())
                throw new IllegalArgumentException("Invalid scoped profile case");
        }
    }
    public record Gap(String optionId, String factPath) { }
    public record Analysis(Mode mode, Status status, Instant evaluatedAt, UUID inputId, String inputSha256, String candidateSha256,
            String scenarioSetSha256, List<Case> scenarios, List<Gap> uncoveredFacts) {
        public Analysis {
            Objects.requireNonNull(mode); Objects.requireNonNull(status); Objects.requireNonNull(evaluatedAt); Objects.requireNonNull(inputId);
            scenarios = List.copyOf(scenarios); uncoveredFacts = List.copyOf(uncoveredFacts);
            var boundScenarios = scenarios;
            if (mode == Mode.NOT_CHECKED || status == Status.NOT_CHECKED || !hash(inputSha256) || !hash(candidateSha256) || !hash(scenarioSetSha256)
                    || scenarios.size() > CatalogScopedProfileCases.COUNT * (mode == Mode.CURATED_BOOTSTRAP ? 100 : 200)
                    || scenarios.size() % CatalogScopedProfileCases.COUNT != 0 || uncoveredFacts.size() > 13600
                    || new HashSet<>(scenarios.stream().map(c -> c.optionId() + "|" + c.scenarioId()).toList()).size() != scenarios.size()
                    || new HashSet<>(uncoveredFacts).size() != uncoveredFacts.size()
                    || scenarios.stream().anyMatch(c -> !boundScenarios.stream().filter(other -> other.optionId().equals(c.optionId()))
                        .map(Case::scenarioId).collect(Collectors.toSet()).equals(CatalogScopedProfileCases.IDS))
                    || status == Status.BLOCKED && (!scenarios.isEmpty() || !uncoveredFacts.isEmpty())
                    || scenarios.stream().anyMatch(c -> c.after() == null || c.after().checks().size() > 100
                        || mode == Mode.CURATED_BOOTSTRAP && (c.before() != null || c.scopeChanged() || !c.changedCheckIds().isEmpty())
                        || mode == Mode.PROPOSAL_COMPARISON && (c.before() == null || c.before().checks().size() != c.after().checks().size())))
                throw new IllegalArgumentException("Inconsistent scoped profile analysis");
        }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public String scenarioSetVersion() { return CatalogScopedProfileCases.VERSION; }
        @JsonProperty public String ruleVersion() { return io.authweave.core.evaluation.ClaimRules.VERSION; }
        @JsonProperty public String profilePolicyVersion() { return io.authweave.core.evaluation.EligibilityEvaluator.COMPLIANCE_SCOPE_POLICY_VERSION; }
        @JsonProperty public String analysisBasis() { return "ASSUMED_TRUE_CLAIMS_AND_APPLICABLE_CONDITIONS"; }
        @JsonProperty public boolean coverageComplete() { return false; }
        @JsonProperty public boolean storedReportVerified() { return false; }
        @JsonProperty public boolean baselineVerified() { return false; }
        @JsonProperty public boolean sourceVerificationPerformed() { return false; }
        @JsonProperty public boolean approvalGranted() { return false; }
        @JsonProperty public boolean writesPerformed() { return false; }
        @JsonProperty public boolean publicationReady() { return false; }
        @JsonProperty public boolean evaluationReady() { return false; }
    }
    /** Bounded body-free summary. Counts describe performed checks, not satisfied requirements or verified evidence. */
    public record Check(Mode mode, Status status, Instant evaluatedAt, UUID inputId, String inputSha256, String candidateSha256,
            String scenarioSetSha256, String analysisSha256, int affectedOptions, int checkedScenarios, int conditionalChecks,
            int missingFacts, int violatingChecks, int indeterminateChecks, int changedChecks, int uncoveredFacts) {
        public Check {
            Objects.requireNonNull(mode); Objects.requireNonNull(status);
            if (status == Status.NOT_CHECKED ? mode != Mode.NOT_CHECKED || evaluatedAt != null || inputId != null || inputSha256 != null
                    || candidateSha256 != null || scenarioSetSha256 != null || analysisSha256 != null || affectedOptions != 0 || checkedScenarios != 0
                    || conditionalChecks != 0 || missingFacts != 0 || violatingChecks != 0 || indeterminateChecks != 0 || changedChecks != 0 || uncoveredFacts != 0
                    : mode == Mode.NOT_CHECKED || evaluatedAt == null || inputId == null || !hash(inputSha256) || !hash(candidateSha256)
                        || !hash(scenarioSetSha256) || !hash(analysisSha256) || affectedOptions < 0 || affectedOptions > (mode == Mode.CURATED_BOOTSTRAP ? 100 : 200)
                        || checkedScenarios != affectedOptions * CatalogScopedProfileCases.COUNT || conditionalChecks < checkedScenarios || conditionalChecks > checkedScenarios * 100
                        || missingFacts < 0 || violatingChecks < 0 || indeterminateChecks < missingFacts || (long) violatingChecks + indeterminateChecks > conditionalChecks
                        || changedChecks < 0 || changedChecks > checkedScenarios * 100 || mode == Mode.CURATED_BOOTSTRAP && changedChecks != 0
                        || uncoveredFacts < 0 || uncoveredFacts > affectedOptions * CatalogFactPathRegressionCases.FACT_PATH_COUNT
                        || status == Status.BLOCKED && (affectedOptions != 0 || conditionalChecks != 0 || changedChecks != 0 || uncoveredFacts != 0))
                throw new IllegalArgumentException("Inconsistent scoped profile summary");
        }
        public static Check notChecked() { return new Check(Mode.NOT_CHECKED, Status.NOT_CHECKED, null, null, null, null, null, null, 0, 0, 0, 0, 0, 0, 0, 0); }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public String scenarioSetVersion() { return CatalogScopedProfileCases.VERSION; }
        @JsonProperty public String ruleVersion() { return io.authweave.core.evaluation.ClaimRules.VERSION; }
        @JsonProperty public String profilePolicyVersion() { return io.authweave.core.evaluation.EligibilityEvaluator.COMPLIANCE_SCOPE_POLICY_VERSION; }
        @JsonProperty public String analysisBasis() { return "ASSUMED_TRUE_CLAIMS_AND_APPLICABLE_CONDITIONS"; }
        @JsonProperty public int declaredScenarios() { return CatalogScopedProfileCases.COUNT; }
        @JsonProperty public boolean allScopedScenariosChecked() { return status == Status.ANALYZED && affectedOptions > 0 && checkedScenarios == affectedOptions * declaredScenarios(); }
        @JsonProperty public boolean coverageComplete() { return false; }
        @JsonProperty public boolean storedReportVerified() { return false; }
        @JsonProperty public boolean baselineVerified() { return false; }
        @JsonProperty public boolean sourceVerificationPerformed() { return false; }
        @JsonProperty public boolean approvalGranted() { return false; }
        @JsonProperty public boolean writesPerformed() { return false; }
        @JsonProperty public boolean publicationReady() { return false; }
        @JsonProperty public boolean evaluationReady() { return false; }
    }

    public Analysis compareAt(CatalogChangePreviewRequest request, Instant at) {
        var preview = previews.previewAt(request, at); var results = new ArrayList<Case>(); var gaps = new ArrayList<Gap>();
        if (preview.diffComputed()) {
            var oldOptions = options(request.base()); var newOptions = options(request.candidate());
            var scopes = preview.optionChanges().stream().map(CatalogChangePreview.OptionChange::optionId).collect(Collectors.toSet());
            for (var id : preview.affectedOptionIds()) {
                var before = oldOptions.get(id); var after = newOptions.get(id); boolean scope = scopes.contains(id);
                var paths = preview.factChanges().stream().filter(c -> c.optionId().equals(id)).map(CatalogChangePreview.FactChange::path)
                        .collect(Collectors.toCollection(TreeSet::new));
                if (scope) { if (before != null) paths.addAll(CatalogDraftFacts.entries(before).keySet());
                    if (after != null) paths.addAll(CatalogDraftFacts.entries(after).keySet()); }
                gaps(id, paths, gaps);
                for (int i = 0; i < cases.definitions().size(); i++) {
                    var plan = cases.plans().get(i); var oldSide = CatalogScenarioImpactService.evaluate(plan, before, at);
                    var newSide = CatalogScenarioImpactService.evaluate(plan, after, at); var changed = new ArrayList<String>();
                    for (int j = 0; j < oldSide.checks().size(); j++) {
                        var a = oldSide.checks().get(j); var b = newSide.checks().get(j);
                        if (a.conditionalOutcome() != b.conditionalOutcome() || !a.reason().equals(b.reason())) changed.add(a.checkId());
                    }
                    results.add(new Case(cases.definitions().get(i).id(), id, scope, considered(plan, paths), changed, oldSide, newSide));
                }
            }
        }
        return new Analysis(Mode.PROPOSAL_COMPARISON, preview.diffComputed() ? Status.ANALYZED : Status.BLOCKED, at,
                request.proposalId(), preview.proposalSha256(), preview.candidateReview().contentSha256(), cases.sha256(), results, gaps);
    }
    public Analysis bootstrapAt(CatalogBootstrapReviewRequest request, Instant at) {
        var validation = validator.validateAt(request.candidate(), at); var results = new ArrayList<Case>(); var gaps = new ArrayList<Gap>();
        boolean valid = validation.status() == CatalogDraftValidation.Status.VALID_DRAFT && validation.factCount() > 0
                && validation.contentSha256().equals(request.expectedCandidateSha256());
        if (valid) for (var option : request.candidate().options().stream().sorted(java.util.Comparator.comparing(ProviderCatalogDraft.Option::id)).toList()) {
            var paths = CatalogDraftFacts.entries(option).keySet(); gaps(option.id(), paths, gaps);
            for (int i = 0; i < cases.definitions().size(); i++) {
                var plan = cases.plans().get(i);
                results.add(new Case(cases.definitions().get(i).id(), option.id(), false, considered(plan, paths), List.of(), null,
                        CatalogScenarioImpactService.evaluate(plan, option, at)));
            }
        }
        return new Analysis(Mode.CURATED_BOOTSTRAP, valid ? Status.ANALYZED : Status.BLOCKED, at, request.reviewId(),
                CatalogDraftCanonicalizer.sha256(request), validation.contentSha256(), cases.sha256(), results, gaps);
    }
    public Check inspectAt(CatalogChangePreviewRequest request, Instant at) { return summarize(compareAt(request, at)); }
    public Check inspectAt(CatalogBootstrapReviewRequest request, Instant at) { return summarize(bootstrapAt(request, at)); }
    public Check summarize(Analysis report) {
        var checks = report.scenarios().stream().flatMap(c -> c.after().checks().stream()).toList();
        return new Check(report.mode(), report.status(), report.evaluatedAt(), report.inputId(), report.inputSha256(), report.candidateSha256(),
                report.scenarioSetSha256(), CatalogDraftCanonicalizer.sha256(report), (int) report.scenarios().stream().map(Case::optionId).distinct().count(),
                report.scenarios().size(), checks.size(), (int) checks.stream().filter(c -> c.usesFact() && !c.factPresent()).count(),
                (int) checks.stream().filter(c -> c.conditionalOutcome() == WOULD_VIOLATE).count(),
                (int) checks.stream().filter(c -> c.conditionalOutcome() == INDETERMINATE).count(),
                report.scenarios().stream().mapToInt(c -> c.changedCheckIds().size()).sum(), report.uncoveredFacts().size());
    }
    private void gaps(String id, Set<String> paths, List<Gap> gaps) {
        var dependencies = cases.plans().stream().flatMap(List::stream).filter(ScenarioRulePlan.Rule::usesFact).map(ScenarioRulePlan.Rule::factPath).collect(Collectors.toSet());
        paths.stream().sorted().filter(p -> !dependencies.contains(p)).forEach(p -> gaps.add(new Gap(id, p)));
    }
    private static List<String> considered(List<ScenarioRulePlan.Rule> plan, Set<String> paths) {
        return plan.stream().filter(ScenarioRulePlan.Rule::usesFact).map(ScenarioRulePlan.Rule::factPath).filter(paths::contains).distinct().sorted().toList();
    }
    private static Map<String, ProviderCatalogDraft.Option> options(ProviderCatalogDraft draft) {
        var result = new TreeMap<String, ProviderCatalogDraft.Option>(); draft.options().forEach(o -> result.put(o.id(), o)); return result;
    }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
}

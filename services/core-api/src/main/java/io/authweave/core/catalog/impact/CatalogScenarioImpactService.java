package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.draft.ProviderCatalogDraft.*;
import io.authweave.core.catalog.proposal.CatalogProposalException;
import io.authweave.core.evaluation.*;
import static io.authweave.core.catalog.impact.CatalogScenarioImpact.*;
import static io.authweave.core.catalog.impact.CatalogImpactPreview.Outcome.*;

@Service
public final class CatalogScenarioImpactService {
    public static final String POLICY_VERSION = "catalog-scenario-impact-1";
    public static final List<String> DEFERRED_PATHS = List.of("security.browserTokenExposureMinimization", "security.auditability",
            "security.assurance", "security.complianceTargets", "security.authenticationControls",
            "provisioning", "operations");
    private final CatalogChangePreviewService previews;
    private final CatalogScenarioCases cases;
    public CatalogScenarioImpactService(CatalogChangePreviewService previews, CatalogScenarioCases cases) {
        this.previews = previews; this.cases = cases;
    }
    public CatalogScenarioImpact analyze(CatalogChangePreviewRequest request) { return analyze(request, null, null); }
    CatalogScenarioImpact analyze(CatalogChangePreviewRequest request, Long version, String digest) {
        var preview = previews.preview(request);
        if (digest != null && !digest.equals(preview.proposalSha256())) throw new CatalogProposalException(CatalogProposalException.Reason.REPLAY_UNAVAILABLE);
        var results = new ArrayList<ScenarioImpact>(); var uncovered = new ArrayList<CatalogImpactPreview.UncoveredChange>();
        if (preview.diffComputed()) {
            var oldOptions = options(request.base()); var newOptions = options(request.candidate());
            var scopes = preview.optionChanges().stream().map(CatalogChangePreview.OptionChange::optionId).collect(Collectors.toSet());
            var allDependencies = cases.plans().stream().flatMap(List::stream).filter(ScenarioRulePlan.Rule::usesFact)
                    .map(ScenarioRulePlan.Rule::factPath).collect(Collectors.toSet());
            for (var id : new TreeSet<>(preview.affectedOptionIds())) {
                var oldOption = oldOptions.get(id); var newOption = newOptions.get(id); boolean scope = scopes.contains(id);
                var paths = preview.factChanges().stream().filter(c -> c.optionId().equals(id)).map(CatalogChangePreview.FactChange::path)
                        .collect(Collectors.toCollection(TreeSet::new));
                if (scope) { if (oldOption != null) paths.addAll(CatalogDraftFacts.entries(oldOption).keySet());
                    if (newOption != null) paths.addAll(CatalogDraftFacts.entries(newOption).keySet()); }
                for (var path : paths) if (!allDependencies.contains(path)) uncovered.add(new CatalogImpactPreview.UncoveredChange(id, path, "NO_SCENARIO_DEPENDENCY"));
                for (int i = 0; i < cases.definitions().size(); i++) {
                    var plan = cases.plans().get(i); var before = evaluate(plan, oldOption, preview.evaluatedAt()); var after = evaluate(plan, newOption, preview.evaluatedAt());
                    var affected = plan.stream().filter(ScenarioRulePlan.Rule::usesFact).map(ScenarioRulePlan.Rule::factPath)
                            .filter(paths::contains).distinct().sorted().toList();
                    var changed = new ArrayList<String>();
                    for (int j = 0; j < before.checks().size(); j++) {
                        var a = before.checks().get(j); var b = after.checks().get(j);
                        if (a.conditionalOutcome() != b.conditionalOutcome() || !a.reason().equals(b.reason())) changed.add(a.checkId());
                    }
                    results.add(new ScenarioImpact(cases.definitions().get(i).id(), id, scope, affected,
                            before.conditionalStatus() != after.conditionalStatus(), changed, before, after));
                }
            }
        }
        return new CatalogScenarioImpact("CATALOG_PROFILE_SCENARIO_IMPACT", POLICY_VERSION, ClaimRules.VERSION,
                EligibilityEvaluator.COMPLIANCE_SCOPE_POLICY_VERSION, CatalogScenarioCases.VERSION, cases.sha256(),
                "ASSUMED_TRUE_CLAIMS_AND_APPLICABLE_CONDITIONS", preview.evaluatedAt(), request.proposalId(), preview.proposalSha256(),
                version, digest != null, preview.diffComputed() ? CatalogImpactPreview.Status.ANALYZED : CatalogImpactPreview.Status.BLOCKED,
                preview.diffComputed(), preview.diffComputed(), false, false, false, false, false, false, false,
                DEFERRED_PATHS, preview, cases.definitions(), results, uncovered);
    }

    static Side evaluate(List<ScenarioRulePlan.Rule> plan, Option option, Instant at) {
        var facts = option == null ? Map.<String, ProposedFact>of() : CatalogDraftFacts.entries(option);
        var checks = new ArrayList<Check>();
        for (var rule : plan) {
            var fact = rule.factPath() == null ? null : facts.get(rule.factPath());
            if (rule.usesFact()) {
                var side = CatalogImpactService.side(rule.probe(), option != null, fact, at);
                checks.add(new Check(rule.checkId(), rule.profilePath(), rule.factPath(), true, side.factPresent(),
                        side.conditionalOutcome(), side.reason().name(), side.freshness(), side.conditionsRecorded()));
            } else {
                // Catalog metadata may be shown, but cannot resolve profile scope, intent or unknown criticality.
                var outcome = switch (rule.presetOutcome()) {
                    case NOT_APPLIED -> NOT_APPLIED; case UNKNOWN -> INDETERMINATE;
                    default -> throw new IllegalStateException("Scope-only rule must not establish provider support");
                };
                checks.add(new Check(rule.checkId(), rule.profilePath(), rule.factPath(), false, fact != null, outcome, rule.presetReason(),
                        CatalogImpactService.freshness(fact, at), fact != null && !fact.conditions().isEmpty()));
            }
        }
        var outcomes = checks.stream().map(Check::conditionalOutcome).toList();
        var status = option == null ? ConditionalStatus.OPTION_ABSENT
                : outcomes.contains(WOULD_VIOLATE) ? ConditionalStatus.WOULD_VIOLATE_CHECKED_REQUIREMENTS
                : outcomes.contains(INDETERMINATE) || !outcomes.contains(WOULD_SATISFY) ? ConditionalStatus.INDETERMINATE
                : ConditionalStatus.WOULD_SATISFY_CHECKED_REQUIREMENTS;
        return new Side(option != null, status, checks);
    }
    private static Map<String, Option> options(ProviderCatalogDraft draft) {
        var result = new TreeMap<String, Option>(); draft.options().forEach(o -> result.put(o.id(), o)); return result;
    }
}

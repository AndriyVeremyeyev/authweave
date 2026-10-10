package io.authweave.core.catalog.impact;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import io.authweave.core.catalog.draft.CatalogDraftFacts;
import io.authweave.core.catalog.draft.ProviderCatalogDraft;
import io.authweave.core.evaluation.CapabilityPreflight.Outcome;

/** Fresh Core-owned whole-decision coverage. Never accepts transported reports, counts or readiness flags. No publisher/HTTP route. */
@Service
public class DecisionPublicationCoverageService {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final DecisionPublicationCoveragePolicy policy;
    private final StoredCandidateDecisionService loader;
    private final Clock clock;
    public DecisionPublicationCoverageService(DecisionPublicationCoveragePolicy policy, StoredCandidateDecisionService loader, Clock clock) {
        this.policy = policy; this.loader = loader; this.clock = clock;
    }
    public enum Status { COMPLETE_DECLARED_SCOPE, INCOMPLETE }
    public record RouteCheck(String profilePath, DecisionPublicationCoveragePolicy.Handling handling,
            int beforeOutputs, int afterOutputs, boolean accounted) {
        public RouteCheck {
            if (profilePath == null || handling == null || beforeOutputs < 0 || afterOutputs < 0
                    || beforeOutputs > 12000 || afterOutputs > 12000 || accounted != (beforeOutputs > 0 && afterOutputs > 0))
                throw new IllegalArgumentException("Unaccounted decision route");
        }
    }
    public record ScenarioCheck(String scenarioId, String profileSha256, String weightsSha256, String impactSha256,
            String beforeResultSha256, String afterResultSha256, String beforeInputSha256, String afterInputSha256,
            int beforeOptions, int afterOptions, int beforeUnknownFindings, int afterUnknownFindings,
            boolean decisionOutcomesChanged, List<RouteCheck> routes) {
        public ScenarioCheck {
            routes = List.copyOf(routes);
            if (!CatalogScopedProfileCases.IDS.contains(scenarioId) || beforeOptions < 1 || afterOptions < 1 || beforeOptions > 100 || afterOptions > 100
                    || beforeUnknownFindings < 0 || afterUnknownFindings < 0 || beforeUnknownFindings > 12000 || afterUnknownFindings > 12000
                    || routes.size() != 34 || routes.stream().map(RouteCheck::profilePath).distinct().count() != 34
                    || !routes.stream().map(RouteCheck::profilePath).collect(java.util.stream.Collectors.toSet()).equals(
                        CatalogProfilePlanningCoverageService.INPUT_ROUTES.stream().map(CatalogProfilePlanningCoverageService.InputRoute::profilePath)
                            .collect(java.util.stream.Collectors.toSet()))) throw new IllegalArgumentException("Incomplete decision scenario coverage");
            for (var digest : List.of(profileSha256, weightsSha256, impactSha256, beforeResultSha256, afterResultSha256, beforeInputSha256, afterInputSha256)) hash(digest);
        }
    }
    public record Check(String scope, String policyVersion, String canonicalization, Instant evaluatedAt,
            int profileSchemaVersion, String profileSchemaSha256, String decisionPolicySha256, String scenarioSetSha256,
            String manifestSha256, Map<String, String> componentVersions,
            StoredCandidateDecisionService.Reference before, StoredCandidateDecisionService.Reference after,
            List<ScenarioCheck> scenarios, List<String> uncoveredFacts,
            List<CatalogProfilePlanningCoverageService.VerificationGap> verificationGaps,
            Status status, boolean decisionScopeCoverageComplete, boolean storedSourceReviewsVerified,
            boolean coverageComplete, boolean currentCuratorAuthorityVerified, boolean sourceVerificationPerformed,
            boolean configurationVerified, boolean complianceVerified, boolean actualGoldenAcceptancePerformed,
            boolean historicalReportPromotionPerformed, boolean approvalGranted, boolean publicationReady, boolean writesPerformed) {
        public Check {
            componentVersions = Map.copyOf(componentVersions); scenarios = List.copyOf(scenarios);
            uncoveredFacts = List.copyOf(uncoveredFacts); verificationGaps = List.copyOf(verificationGaps);
            boolean complete = uncoveredFacts.isEmpty() && scenarios.stream().allMatch(s -> s.routes().stream().allMatch(RouteCheck::accounted));
            if (!DecisionPublicationCoveragePolicy.SCOPE.equals(scope) || !DecisionPublicationCoveragePolicy.VERSION.equals(policyVersion)
                    || !DecisionCanonicalizer.VERSION.equals(canonicalization) || evaluatedAt == null || profileSchemaVersion != 6
                    || !CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_SHA256.equals(profileSchemaSha256)
                    || !DecisionPublicationCoveragePolicy.COMPONENT_VERSIONS.equals(componentVersions) || before == null || after == null
                    || scenarios.size() != 4 || !scenarios.stream().map(ScenarioCheck::scenarioId).collect(java.util.stream.Collectors.toSet()).equals(CatalogScopedProfileCases.IDS)
                    || new TreeSet<>(uncoveredFacts).size() != uncoveredFacts.size() || !CatalogProfilePlanningCoverageService.VERIFICATION_GAPS.equals(verificationGaps)
                    || decisionScopeCoverageComplete != complete || status != (complete ? Status.COMPLETE_DECLARED_SCOPE : Status.INCOMPLETE)
                    || coverageComplete || currentCuratorAuthorityVerified || sourceVerificationPerformed || configurationVerified || complianceVerified
                    || actualGoldenAcceptancePerformed || historicalReportPromotionPerformed || approvalGranted || publicationReady || writesPerformed)
                throw new IllegalArgumentException("Unversioned or falsely authoritative decision coverage");
            hash(decisionPolicySha256); hash(scenarioSetSha256); hash(manifestSha256);
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Check inspect(StoredCandidateDecisionService.Reference beforeReference, StoredCandidateDecisionService.Reference afterReference) {
        var before = loader.load(beforeReference); var after = loader.load(afterReference);
        return stored(beforeReference, afterReference, before, after, clock.instant());
    }

    /** Internal deterministic replay at a server-owned publication time, never exposed as caller-controlled HTTP input. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Check replay(StoredCandidateDecisionService.Reference beforeReference, StoredCandidateDecisionService.Reference afterReference, Instant at) {
        var before = loader.load(beforeReference); var after = loader.load(afterReference);
        return stored(beforeReference, afterReference, before, after, at);
    }
    private Check stored(StoredCandidateDecisionService.Reference beforeReference, StoredCandidateDecisionService.Reference afterReference,
            StoredCandidateDecisionService.Inputs before, StoredCandidateDecisionService.Inputs after, Instant at) {
        for (var input : List.of(before, after)) if (input.baseReview().recordedAt().isAfter(at.plusSeconds(30))
                || input.auditabilityReview() != null && input.auditabilityReview().recordedAt().isAfter(at.plusSeconds(30)))
            throw new IllegalArgumentException("Source review is ahead of the server clock");
        return analyze(beforeReference, afterReference, before.snapshot(), after.snapshot(), at, true);
    }

    /** Pure package-internal calculation for regressions; only inspect() establishes stored-review provenance. */
    Check analyze(StoredCandidateDecisionService.Reference beforeReference, StoredCandidateDecisionService.Reference afterReference,
            CandidateDecisionImpactEvaluator.Snapshot before, CandidateDecisionImpactEvaluator.Snapshot after, Instant at) {
        return analyze(beforeReference, afterReference, before, after, at, false);
    }
    private Check analyze(StoredCandidateDecisionService.Reference beforeReference, StoredCandidateDecisionService.Reference afterReference,
            CandidateDecisionImpactEvaluator.Snapshot before, CandidateDecisionImpactEvaluator.Snapshot after, Instant at, boolean storedReviewsVerified) {
        if (!beforeReference.decisionCatalogSha256().equals(DecisionCanonicalizer.sha256(before.catalog()))
                || !afterReference.decisionCatalogSha256().equals(DecisionCanonicalizer.sha256(after.catalog())))
            throw new IllegalArgumentException("Coverage inputs do not match the pinned candidates");
        var checked = new ArrayList<ScenarioCheck>(); var missing = new TreeSet<String>();
        var consumedBefore = new TreeSet<String>(); var consumedAfter = new TreeSet<String>();
        for (var scenario : policy.scenarios()) {
            var impact = CandidateDecisionImpactEvaluator.evaluate(scenario.profile(), 6, scenario.weights(), before, after, at);
            var routes = policy.routes().stream().map(route -> {
                int a = outputs(route, scenario.profile(), impact.before()); int b = outputs(route, scenario.profile(), impact.after());
                return new RouteCheck(route.profilePath(), route.handling(), a, b, a > 0 && b > 0);
            }).toList();
            collect(impact.before(), consumedBefore); collect(impact.after(), consumedAfter);
            checked.add(new ScenarioCheck(scenario.id(), impact.profileSha256(), impact.weightsSha256(),
                    DecisionCanonicalizer.sha256(MAPPER.valueToTree(impact)), impact.beforeResultSha256(), impact.afterResultSha256(),
                    impact.beforeInputSha256(), impact.afterInputSha256(), impact.before().candidates().size(), impact.after().candidates().size(),
                    unknowns(impact.before()), unknowns(impact.after()), impact.decisionOutcomesChanged(), routes));
        }
        facts(before).stream().filter(f -> !consumedBefore.contains(f)).forEach(f -> missing.add("before:" + f));
        facts(after).stream().filter(f -> !consumedAfter.contains(f)).forEach(f -> missing.add("after:" + f));
        boolean complete = checked.size() == CatalogScopedProfileCases.COUNT && missing.isEmpty()
                && checked.stream().allMatch(s -> s.routes().size() == 34 && s.routes().stream().allMatch(RouteCheck::accounted));
        return new Check(DecisionPublicationCoveragePolicy.SCOPE, DecisionPublicationCoveragePolicy.VERSION, DecisionCanonicalizer.VERSION, at,
                6, CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_SHA256, policy.decisionPolicySha256(), policy.scenarioSetSha256(), policy.manifestSha256(),
                DecisionPublicationCoveragePolicy.COMPONENT_VERSIONS, beforeReference, afterReference, checked, List.copyOf(missing),
                CatalogProfilePlanningCoverageService.VERIFICATION_GAPS, complete ? Status.COMPLETE_DECLARED_SCOPE : Status.INCOMPLETE,
                complete, storedReviewsVerified, false, false, false, false, false, false, false, false, false, false);
    }
    private static int outputs(DecisionPublicationCoveragePolicy.Route route, JsonNode profile, CandidateDecisionEvaluator.Result result) {
        if (profile.at("/" + route.profilePath().replace('.', '/')).isMissingNode()) return 0;
        return switch (route.handling()) {
            case CANDIDATE_FINDINGS, AUDITABILITY_FINDINGS -> {
                var counts = result.candidates().stream().mapToInt(candidate ->
                    (int) candidate.hardChecks().findings().stream().filter(f -> route.outputProfilePath().equals(f.profilePath())).count()).toArray();
                yield java.util.Arrays.stream(counts).anyMatch(n -> n == 0) ? 0 : java.util.Arrays.stream(counts).sum();
            }
            case CONDITIONAL_ARCHITECTURE -> result.architecture().patterns().size() == 5 ? 5 : 0;
            case EXPLICIT_LIMITATION -> (int) result.limitations().stream().filter(l -> route.profilePath().equals(l.profilePath())
                    && profile.at("/" + route.profilePath().replace('.', '/')).toString().equals(l.declaredValue())).count();
        };
    }
    private static int unknowns(CandidateDecisionEvaluator.Result result) {
        return result.candidates().stream().mapToInt(c -> (int) c.hardChecks().findings().stream().filter(f -> f.outcome() == Outcome.UNKNOWN).count()).sum();
    }
    private static Set<String> facts(CandidateDecisionImpactEvaluator.Snapshot snapshot) {
        var result = new TreeSet<String>();
        MAPPER.treeToValue(snapshot.catalog(), ProviderCatalogDraft.class).options().forEach(option ->
                CatalogDraftFacts.entries(option).keySet().forEach(path -> result.add(option.id() + "|" + path)));
        if (snapshot.auditability() != null) CandidateAuditabilityInput.claimDigests(snapshot.auditability().supplement()).keySet().forEach(address ->
                result.add(address.optionId() + "|auditabilitySupplement." + address.criterion()));
        return result;
    }
    private static void collect(CandidateDecisionEvaluator.Result result, Set<String> facts) {
        result.candidates().forEach(c -> c.hardChecks().findings().stream().filter(f -> f.factPath() != null)
                .forEach(f -> facts.add(c.hardChecks().optionId() + "|" + f.factPath())));
        result.architecture().patterns().forEach(p -> p.choice().optionChecks().forEach(o -> collect(o, facts)));
        result.architecture().provisioning().forEach(p -> p.optionChecks().forEach(o -> collect(o, facts)));
        result.architecture().apiProtection().optionChecks().forEach(o -> collect(o, facts));
    }
    private static void collect(CandidateDecisionEvaluator.OptionCheck option, Set<String> facts) {
        option.capabilities().forEach(c -> facts.add(option.optionId() + "|facts." + c.capability()));
    }
    private static void hash(String value) { if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid coverage digest"); }
}

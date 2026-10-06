package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.evaluation.ArchitectureConfigurationEvaluator;
import io.authweave.core.evaluation.ArchitectureConfigurationEvaluator.Reason;
import io.authweave.core.evaluation.ArchitectureConfigurationEvaluator.SettingId;
import io.authweave.core.evaluation.ArchitecturePatternEvaluator;
import io.authweave.core.evaluation.ArchitecturePatternPreflight;
import io.authweave.core.evaluation.ArchitecturePatternPreflight.PatternId;
import io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator;
import io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator.Outcome;
import io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator.Status;

/** Fresh source-controlled design regression, not candidate impact, an observed configuration or a stored receipt. */
@Service
public final class CatalogArchitectureConfigurationRegressionService {
    public static final String POLICY_VERSION = "catalog-architecture-configuration-regression-1";
    public static final String DEFINITIONS_SHA256 = CatalogDraftCanonicalizer.sha256(List.of(ArchitectureConfigurationEvaluator.POLICY_VERSION,
            List.of(PatternId.values()).stream().map(ArchitectureConfigurationEvaluator::definitions).toList(), List.of(Reason.values()),
            List.of(Outcome.values()), List.of(Status.values()), ArchitectureConfigurationEvaluator.DEFERRED_BOUNDARIES,
            ArchitecturePatternEvaluator.POLICY_VERSION, CatalogArchitectureImpactService.DEFINITIONS_SHA256));
    private final CatalogArchitectureConfigurationCases cases;
    public CatalogArchitectureConfigurationRegressionService(CatalogArchitectureConfigurationCases cases) { this.cases = cases; }
    public record Row(CatalogArchitectureConfigurationCases.Definition input, ArchitecturePatternPreflight.Pattern savedInputPreflight,
            ArchitectureConfigurationEvaluator.Analysis analysis) { }
    public record Analysis(Instant evaluatedAt, String scenarioSetSha256, List<Row> rows) {
        public Analysis { Objects.requireNonNull(evaluatedAt); rows = List.copyOf(rows); }
    }
    public record Counts(int conditionallySatisfied, int conditionallyNotSatisfied, int unknown, int notApplicable) {
        public Counts { if (conditionallySatisfied < 0 || conditionallyNotSatisfied < 0 || unknown < 0 || notApplicable < 0
                || (long) conditionallySatisfied + conditionallyNotSatisfied + unknown + notApplicable != CatalogArchitectureConfigurationCases.CHECK_COUNT)
            throw new IllegalArgumentException("Incomplete architecture setting counts"); }
    }
    public record Results(int conditionallyMatches, int conditionallyDoesNotMatch, int needsInformation, int notApplicable) {
        public Results { if (conditionallyMatches < 0 || conditionallyDoesNotMatch < 0 || needsInformation < 0 || notApplicable < 0
                || (long) conditionallyMatches + conditionallyDoesNotMatch + needsInformation + notApplicable != CatalogArchitectureConfigurationCases.COUNT)
            throw new IllegalArgumentException("Incomplete architecture result counts"); }
    }
    public record ReasonCount(Reason reasonCode, int checks) {
        public ReasonCount { Objects.requireNonNull(reasonCode); if (checks < 0 || checks > CatalogArchitectureConfigurationCases.CHECK_COUNT) throw new IllegalArgumentException("Invalid reason count"); }
    }
    /** Only metadata and counts, never fixture/profile/settings bodies or caller identity. */
    public record Check(Instant evaluatedAt, String scenarioSetSha256, String analysisSha256, Counts outcomes, Results results,
            List<ReasonCount> reasons, int selectedCases, int unknownClientCases, int unselectedClientCases, int savedInputNeedsInformation) {
        public Check {
            Objects.requireNonNull(evaluatedAt); Objects.requireNonNull(outcomes); Objects.requireNonNull(results); reasons = List.copyOf(reasons);
            if (!hash(scenarioSetSha256) || !hash(analysisSha256) || selectedCases < 0 || unknownClientCases < 0 || unselectedClientCases < 0
                    || (long) selectedCases + unknownClientCases + unselectedClientCases != CatalogArchitectureConfigurationCases.COUNT
                    || savedInputNeedsInformation < unknownClientCases || savedInputNeedsInformation > selectedCases + unknownClientCases
                    || results.notApplicable() != unselectedClientCases || results.needsInformation() < unknownClientCases
                    || reasons.size() != Reason.values().length || reasons.stream().mapToLong(ReasonCount::checks).sum() != CatalogArchitectureConfigurationCases.CHECK_COUNT)
                throw new IllegalArgumentException("Invalid configuration regression summary");
            for (int i = 0; i < reasons.size(); i++) if (reasons.get(i).reasonCode() != Reason.values()[i]) throw new IllegalArgumentException("Use canonical reason order");
            if (reasons.get(0).checks() != outcomes.conditionallySatisfied() || reasons.get(1).checks() != outcomes.conditionallyNotSatisfied()
                    || (long) reasons.get(2).checks() + reasons.get(3).checks() != outcomes.unknown() || reasons.get(4).checks() != outcomes.notApplicable())
                throw new IllegalArgumentException("Configuration reason/outcome parity mismatch");
        }
        @JsonProperty public String scope() { return "SYNTHETIC_PROPOSED_ARCHITECTURE_CONFIGURATION_REGRESSION"; }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public String configurationPolicyVersion() { return ArchitectureConfigurationEvaluator.POLICY_VERSION; }
        @JsonProperty public String preflightPolicyVersion() { return ArchitecturePatternEvaluator.POLICY_VERSION; }
        @JsonProperty public String definitionsSha256() { return DEFINITIONS_SHA256; }
        @JsonProperty public String scenarioSetVersion() { return CatalogArchitectureConfigurationCases.VERSION; }
        @JsonProperty public String baseScenarioSetVersion() { return CatalogScopedProfileCases.VERSION; }
        @JsonProperty public String baseScenarioSetSha256() { return CatalogArchitectureConfigurationCases.BASE_SHA256; }
        @JsonProperty public int profileSchemaVersion() { return CatalogProfileImpactCoverageService.PROFILE_SCHEMA_VERSION; }
        @JsonProperty public String profileSchemaSha256() { return CatalogProfileImpactCoverageService.PROFILE_SCHEMA_SHA256; }
        @JsonProperty public String analysisBasis() { return "UNVERIFIED_SYNTHETIC_PROPOSED_CONFIGURATION"; }
        @JsonProperty public int declaredProfiles() { return CatalogScopedProfileCases.COUNT; }
        @JsonProperty public int declaredPatterns() { return PatternId.values().length; }
        @JsonProperty public int checkedCases() { return CatalogArchitectureConfigurationCases.COUNT; }
        @JsonProperty public int checkedSettings() { return CatalogArchitectureConfigurationCases.CHECK_COUNT; }
        @JsonProperty public List<SettingId> exercisedSettings() { return List.of(SettingId.values()); }
        @JsonProperty public List<String> checkedPaths() { return ArchitecturePatternEvaluator.CHECKED_PATHS; }
        @JsonProperty public List<String> deferredBoundaries() { return ArchitectureConfigurationEvaluator.DEFERRED_BOUNDARIES; }
        @JsonProperty public boolean candidateChangesEvaluated() { return false; }
        @JsonProperty public boolean coverageComplete() { return false; }
        @JsonProperty public boolean configurationObserved() { return false; }
        @JsonProperty public boolean configurationVerified() { return false; }
        @JsonProperty public boolean providerCompatibilityVerified() { return false; }
        @JsonProperty public boolean runtimeFlowVerified() { return false; }
        @JsonProperty public boolean sourceVerificationPerformed() { return false; }
        @JsonProperty public boolean storedReportVerified() { return false; }
        @JsonProperty public boolean baselineVerified() { return false; }
        @JsonProperty public boolean approvalGranted() { return false; }
        @JsonProperty public boolean publicationReady() { return false; }
        @JsonProperty public boolean evaluationReady() { return false; }
        @JsonProperty public boolean recommendationReady() { return false; }
        @JsonProperty public boolean writesPerformed() { return false; }
    }
    public Analysis analyzeAt(Instant at) {
        var rows = cases.definitions().stream().map(input -> {
            var preflight = ArchitecturePatternEvaluator.evaluate(input.profile()).stream().filter(p -> p.patternId() == input.patternId()).findFirst().orElseThrow();
            var analysis = ArchitectureConfigurationEvaluator.evaluate(input.patternId(), ArchitecturePrerequisiteEvaluator.scope(preflight), input.settings());
            var expected = CatalogArchitectureConfigurationCases.expected(input);
            if (analysis.clientScope() != expected.scope() || analysis.status() != expected.status() || !analysis.checks().equals(expected.checks()))
                throw new IllegalStateException("Architecture setting policy drift: review explicit fixture expectations");
            return new Row(input, preflight, analysis);
        }).toList();
        return new Analysis(at, cases.sha256(), rows);
    }
    public Check inspectAt(Instant at) { return summarize(analyzeAt(at)); }
    public Check summarize(Analysis report) {
        if (!report.equals(analyzeAt(report.evaluatedAt()))) throw new IllegalArgumentException("Use the complete exact-bound configuration regression");
        var analyses = report.rows().stream().map(Row::analysis).toList();
        var checks = analyses.stream().flatMap(a -> a.checks().stream()).toList();
        return new Check(report.evaluatedAt(), report.scenarioSetSha256(), CatalogDraftCanonicalizer.sha256(report),
                new Counts(count(checks, Outcome.CONDITIONALLY_SATISFIED), count(checks, Outcome.CONDITIONALLY_NOT_SATISFIED), count(checks, Outcome.UNKNOWN), count(checks, Outcome.NOT_APPLICABLE)),
                new Results(status(analyses, Status.CONDITIONALLY_MATCHES), status(analyses, Status.CONDITIONALLY_DOES_NOT_MATCH), status(analyses, Status.NEEDS_INFORMATION), status(analyses, Status.NOT_APPLICABLE)),
                List.of(Reason.values()).stream().map(reason -> new ReasonCount(reason, (int) checks.stream().filter(c -> c.reasonCode() == reason).count())).toList(),
                scope(analyses, ArchitecturePrerequisiteEvaluator.ClientScope.SELECTED), scope(analyses, ArchitecturePrerequisiteEvaluator.ClientScope.UNKNOWN), scope(analyses, ArchitecturePrerequisiteEvaluator.ClientScope.NOT_SELECTED),
                (int) report.rows().stream().filter(row -> row.savedInputPreflight().status() == ArchitecturePatternPreflight.Status.NEEDS_INFORMATION).count());
    }
    private static int count(List<ArchitectureConfigurationEvaluator.Check> checks, Outcome outcome) { return (int) checks.stream().filter(c -> c.outcome() == outcome).count(); }
    private static int status(List<ArchitectureConfigurationEvaluator.Analysis> analyses, Status status) { return (int) analyses.stream().filter(a -> a.status() == status).count(); }
    private static int scope(List<ArchitectureConfigurationEvaluator.Analysis> analyses, ArchitecturePrerequisiteEvaluator.ClientScope scope) { return (int) analyses.stream().filter(a -> a.clientScope() == scope).count(); }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
}

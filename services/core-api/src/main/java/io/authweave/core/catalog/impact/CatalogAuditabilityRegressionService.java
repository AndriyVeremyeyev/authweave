package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion;
import io.authweave.core.catalog.AuditabilityCatalog;
import io.authweave.core.catalog.AuditabilityFacts.Fact;
import io.authweave.core.catalog.ProviderCatalog;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.evaluation.AuditabilityEvaluator;
import io.authweave.core.evaluation.CapabilityPreflight.Outcome;
import io.authweave.core.evaluation.EvidencePolicy;

/** Fresh synthetic capability regression, not proposed-catalog impact, a durable receipt or publication authority. */
@Service
public final class CatalogAuditabilityRegressionService {
    public static final String POLICY_VERSION = "catalog-auditability-regression-1";
    public static final String DEFINITIONS_SHA256 = CatalogDraftCanonicalizer.sha256(List.of(AuditabilityEvaluator.POLICY_VERSION,
            AuditabilityEvaluator.DEFINITIONS, List.of(AuditabilityEvaluator.Reason.values()),
            AuditabilityEvaluator.DEFERRED_BOUNDARIES, EvidencePolicy.MAX_AGE.getSeconds()));
    public static final List<String> CHECKED_PATHS = List.of("security.auditability", "security.auditabilityRequirements.selectedCriteria",
            "security.auditabilityRequirements.minimumRetentionDays");
    private final CatalogAuditabilityRegressionCases cases;
    private final AuditabilityCatalog evidence;
    private final String evidenceSha256;
    public CatalogAuditabilityRegressionService(CatalogAuditabilityRegressionCases cases, ProviderCatalog base, AuditabilityCatalog evidence) {
        evidence.validateBase(base);
        if (evidence.kind() != ProviderCatalog.Kind.SYNTHETIC) throw new IllegalStateException("Use synthetic regression evidence only");
        this.cases = cases; this.evidence = evidence; this.evidenceSha256 = CatalogDraftCanonicalizer.sha256(evidence);
    }
    public record Case(String scenarioId, String profileSha256, AuditabilityEvaluator.Analysis analysis, List<Fact> evidence) {
        public Case {
            Objects.requireNonNull(analysis); evidence = List.copyOf(evidence);
            if (!CatalogScopedProfileCases.IDS.contains(scenarioId) || !hash(profileSha256)
                    || analysis.criticality() != io.authweave.core.assessment.domain.profile.RequirementCriticality.REQUIRED || analysis.requirements().isUnrecorded()
                    || !analysis.equals(AuditabilityEvaluator.evaluate(analysis.criticality(), analysis.requirements(),
                        analysis.optionScope(), evidence, analysis.evaluatedAt())))
                throw new IllegalArgumentException("Unbound auditability regression case");
        }
    }
    public record Analysis(Instant evaluatedAt, String scenarioSetSha256, String baseCatalogVersion, String evidenceVersion,
            String evidenceSha256, List<Case> scenarios) {
        public Analysis {
            Objects.requireNonNull(evaluatedAt); scenarios = List.copyOf(scenarios);
            var scopes = scenarios.stream().map(c -> c.analysis().optionScope()).distinct().toList();
            var signatures = new HashMap<String, String>(); var facts = new HashMap<io.authweave.core.catalog.AuditabilityFacts.Scope, String>();
            var seen = new HashSet<String>();
            if (!hash(scenarioSetSha256) || !hash(evidenceSha256) || !version(baseCatalogVersion) || !version(evidenceVersion)
                    || scopes.isEmpty() || scopes.size() > 100 || scenarios.size() != scopes.size() * CatalogScopedProfileCases.COUNT)
                throw new IllegalArgumentException("Incomplete auditability regression");
            for (var row : scenarios) {
                var a = row.analysis(); var key = row.scenarioId() + "|" + CatalogDraftCanonicalizer.sha256(a.optionScope());
                var signature = CatalogDraftCanonicalizer.sha256(List.of(row.profileSha256(), a.criticality(), a.requirements()));
                var prior = signatures.putIfAbsent(row.scenarioId(), signature);
                var evidenceDigest = CatalogDraftCanonicalizer.sha256(row.evidence()); var priorFacts = facts.putIfAbsent(a.optionScope(), evidenceDigest);
                if (!seen.add(key) || !evaluatedAt.equals(a.evaluatedAt()) || prior != null && !prior.equals(signature)
                        || priorFacts != null && !priorFacts.equals(evidenceDigest))
                    throw new IllegalArgumentException("Inconsistent auditability regression binding");
            }
            for (var scope : scopes) if (!new HashSet<>(scenarios.stream().filter(c -> c.analysis().optionScope().equals(scope))
                    .map(Case::scenarioId).toList()).equals(CatalogScopedProfileCases.IDS))
                throw new IllegalArgumentException("Missing auditability scenario for option scope");
        }
    }
    public record OutcomeCounts(int pass, int fail, int unknown, int notApplied) {
        public OutcomeCounts { if (pass < 0 || fail < 0 || unknown < 0 || notApplied < 0 || total(pass, fail, unknown, notApplied) > 2400)
            throw new IllegalArgumentException("Invalid auditability check counts"); }
    }
    public record CandidateCounts(int matchesCheckedRequirements, int doesNotMatch, int needsInformation, int notApplied) {
        public CandidateCounts { if (matchesCheckedRequirements < 0 || doesNotMatch < 0 || needsInformation < 0 || notApplied < 0
                || total(matchesCheckedRequirements, doesNotMatch, needsInformation, notApplied) > 400)
            throw new IllegalArgumentException("Invalid auditability candidate counts"); }
    }
    public record ReasonCount(AuditabilityEvaluator.Reason reasonCode, int checks) {
        public ReasonCount { Objects.requireNonNull(reasonCode); if (checks < 0 || checks > 2400) throw new IllegalArgumentException("Invalid auditability reason count"); }
    }
    /** Body-free inventory. Counts describe evaluated synthetic fixtures, not verified deployments or satisfied policy. */
    public record Check(Instant evaluatedAt, String scenarioSetSha256, String baseCatalogVersion, String evidenceVersion,
            String evidenceSha256, String analysisSha256, int checkedScopes, int checkedCases,
            OutcomeCounts outcomes, CandidateCounts candidates, List<ReasonCount> reasons, List<Criterion> exercisedRequiredCriteria) {
        public Check {
            Objects.requireNonNull(evaluatedAt); Objects.requireNonNull(outcomes); Objects.requireNonNull(candidates);
            reasons = List.copyOf(reasons); exercisedRequiredCriteria = List.copyOf(exercisedRequiredCriteria);
            if (!hash(scenarioSetSha256) || !hash(evidenceSha256) || !hash(analysisSha256) || !version(baseCatalogVersion) || !version(evidenceVersion)
                    || checkedScopes < 1 || checkedScopes > 100 || checkedCases != checkedScopes * CatalogScopedProfileCases.COUNT
                    || total(outcomes.pass(), outcomes.fail(), outcomes.unknown(), outcomes.notApplied()) != checkedCases * Criterion.values().length
                    || total(candidates.matchesCheckedRequirements(), candidates.doesNotMatch(), candidates.needsInformation(), candidates.notApplied()) != checkedCases
                    || reasons.size() != AuditabilityEvaluator.Reason.values().length
                    || new HashSet<>(reasons.stream().map(ReasonCount::reasonCode).toList()).size() != reasons.size()
                    || reasons.stream().mapToLong(ReasonCount::checks).sum() != (long) checkedCases * Criterion.values().length
                    || exercisedRequiredCriteria.size() > Criterion.values().length
                    || new HashSet<>(exercisedRequiredCriteria).size() != exercisedRequiredCriteria.size())
                throw new IllegalArgumentException("Inconsistent auditability regression summary");
            for (int i = 0; i < reasons.size(); i++) if (reasons.get(i).reasonCode() != AuditabilityEvaluator.Reason.values()[i])
                throw new IllegalArgumentException("Use canonical auditability reason inventory");
            for (var outcome : Outcome.values()) {
                int expected = switch (outcome) { case PASS -> outcomes.pass(); case FAIL -> outcomes.fail();
                    case UNKNOWN -> outcomes.unknown(); case NOT_APPLIED -> outcomes.notApplied(); };
                if (reasons.stream().filter(r -> outcome(r.reasonCode()) == outcome).mapToInt(ReasonCount::checks).sum() != expected)
                    throw new IllegalArgumentException("Auditability reasons do not match outcomes");
            }
        }
        @JsonProperty public String scope() { return "SYNTHETIC_SCOPED_AUDITABILITY_REGRESSION"; }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public String auditabilityPolicyVersion() { return AuditabilityEvaluator.POLICY_VERSION; }
        @JsonProperty public String definitionsSha256() { return DEFINITIONS_SHA256; }
        @JsonProperty public String scenarioSetVersion() { return CatalogAuditabilityRegressionCases.VERSION; }
        @JsonProperty public String baseScenarioSetVersion() { return CatalogScopedProfileCases.VERSION; }
        @JsonProperty public int profileSchemaVersion() { return CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_VERSION; }
        @JsonProperty public String profileSchemaSha256() { return CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_SHA256; }
        @JsonProperty public String analysisBasis() { return "DATED_SYNTHETIC_SCOPED_CAPABILITY_EVIDENCE"; }
        @JsonProperty public int declaredScenarios() { return CatalogScopedProfileCases.COUNT; }
        @JsonProperty public int declaredCriteria() { return Criterion.values().length; }
        @JsonProperty public int checkedCriteria() { return checkedCases * Criterion.values().length; }
        @JsonProperty public boolean allDeclaredCriteriaExercised() { return exercisedRequiredCriteria.size() == Criterion.values().length; }
        @JsonProperty public List<String> checkedPaths() { return CHECKED_PATHS; }
        @JsonProperty public List<String> deferredBoundaries() { return AuditabilityEvaluator.DEFERRED_BOUNDARIES; }
        @JsonProperty public boolean coverageComplete() { return false; }
        @JsonProperty public boolean candidateChangesEvaluated() { return false; }
        @JsonProperty public boolean configurationVerified() { return false; }
        @JsonProperty public boolean complianceVerified() { return false; }
        @JsonProperty public boolean sourceVerificationPerformed() { return false; }
        @JsonProperty public boolean storedReportVerified() { return false; }
        @JsonProperty public boolean baselineVerified() { return false; }
        @JsonProperty public boolean approvalGranted() { return false; }
        @JsonProperty public boolean writesPerformed() { return false; }
        @JsonProperty public boolean publicationReady() { return false; }
        @JsonProperty public boolean evaluationReady() { return false; }
        @JsonProperty public boolean recommendationReady() { return false; }
    }
    public Analysis analyzeAt(Instant at) {
        var rows = cases.definitions().stream().flatMap(definition -> evidence.options().stream().map(option -> new Case(
                definition.scenarioId(), definition.profileSha256(), AuditabilityEvaluator.evaluate(definition.criticality(), definition.requirements(),
                    option.scope(), option.facts(), at), option.facts()))).toList();
        return new Analysis(at, cases.sha256(), evidence.baseCatalogVersion(), evidence.evidenceVersion(), evidenceSha256, rows);
    }
    public Check inspectAt(Instant at) { return summarize(analyzeAt(at)); }
    public Check summarize(Analysis analysis) {
        if (!analysis.scenarioSetSha256().equals(cases.sha256()) || !analysis.baseCatalogVersion().equals(evidence.baseCatalogVersion())
                || !analysis.evidenceVersion().equals(evidence.evidenceVersion()) || !analysis.evidenceSha256().equals(evidenceSha256)
                || analysis.scenarios().size() != cases.definitions().size() * evidence.options().size())
            throw new IllegalArgumentException("Use the complete bound synthetic auditability regression");
        for (var row : analysis.scenarios()) {
            var definition = cases.definitions().stream().filter(d -> d.scenarioId().equals(row.scenarioId())).findFirst().orElseThrow();
            var option = evidence.options().stream().filter(o -> o.scope().equals(row.analysis().optionScope())).findFirst().orElseThrow();
            if (!definition.profileSha256().equals(row.profileSha256()) || definition.criticality() != row.analysis().criticality()
                    || !definition.requirements().equals(row.analysis().requirements()) || !option.facts().equals(row.evidence()))
                throw new IllegalArgumentException("Auditability regression inputs or evidence drifted");
        }
        var checks = analysis.scenarios().stream().flatMap(c -> c.analysis().checks().stream()).toList();
        var outcomes = new OutcomeCounts(count(checks, Outcome.PASS), count(checks, Outcome.FAIL), count(checks, Outcome.UNKNOWN), count(checks, Outcome.NOT_APPLIED));
        var candidates = new CandidateCounts(count(analysis, AuditabilityEvaluator.Status.MATCHES_CHECKED_REQUIREMENTS), count(analysis, AuditabilityEvaluator.Status.DOES_NOT_MATCH),
                count(analysis, AuditabilityEvaluator.Status.NEEDS_INFORMATION), count(analysis, AuditabilityEvaluator.Status.NOT_APPLIED));
        var reasons = java.util.Arrays.stream(AuditabilityEvaluator.Reason.values()).map(reason -> new ReasonCount(reason,
                (int) checks.stream().filter(c -> c.reasonCode() == reason).count())).toList();
        var criteria = checks.stream().filter(c -> c.outcome() != Outcome.NOT_APPLIED).map(AuditabilityEvaluator.Check::criterion).distinct().sorted().toList();
        return new Check(analysis.evaluatedAt(), analysis.scenarioSetSha256(), analysis.baseCatalogVersion(), analysis.evidenceVersion(), analysis.evidenceSha256(),
                CatalogDraftCanonicalizer.sha256(analysis), (int) analysis.scenarios().stream().map(c -> c.analysis().optionScope()).distinct().count(),
                analysis.scenarios().size(), outcomes, candidates, reasons, criteria);
    }
    private static int count(List<AuditabilityEvaluator.Check> checks, Outcome outcome) { return (int) checks.stream().filter(c -> c.outcome() == outcome).count(); }
    private static int count(Analysis report, AuditabilityEvaluator.Status status) { return (int) report.scenarios().stream().filter(c -> c.analysis().status() == status).count(); }
    private static long total(int a, int b, int c, int d) { return (long) a + b + c + d; }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static boolean version(String value) { return value != null && value.matches("[a-z0-9][a-z0-9.-]{0,99}"); }
    private static Outcome outcome(AuditabilityEvaluator.Reason reason) {
        return switch (reason) {
            case NO_REQUIREMENT, PREFERENCE_NOT_SCORED, CRITERION_NOT_SELECTED -> Outcome.NOT_APPLIED;
            case CAPABILITY_UNAVAILABLE, RETENTION_BELOW_MINIMUM -> Outcome.FAIL;
            case DOCUMENTED_CAPABILITY_AVAILABLE, RETENTION_MEETS_MINIMUM -> Outcome.PASS;
            default -> Outcome.UNKNOWN;
        };
    }
}

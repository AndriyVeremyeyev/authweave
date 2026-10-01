package io.authweave.core.catalog.impact;

import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.evaluation.ArchitecturePatternEvaluator;
import io.authweave.core.evaluation.ArchitecturePatternPreflight;
import static io.authweave.core.evaluation.ArchitecturePatternPreflight.*;

/** Profile-only architecture rules. No catalog input, observed configuration, chosen winner or verified prerequisite. */
@Service
public final class CatalogArchitectureImpactService {
    public static final String POLICY_VERSION = "catalog-architecture-impact-1";
    public static final int PATTERN_COUNT = 5;
    public static final List<Definition> DEFINITIONS = ArchitecturePatternEvaluator.evaluate(ApplicationIdentityProfile.unknown()).stream()
            .map(p -> new Definition(p.patternId(), p.displayName(), p.clientType(), p.tokenHandling(), p.advantages(), p.tradeoffs(), p.prerequisites(), p.references())).toList();
    public static final String DEFINITIONS_SHA256 = CatalogDraftCanonicalizer.sha256(List.of(ArchitecturePatternEvaluator.POLICY_VERSION,
            ArchitecturePatternEvaluator.CHECKED_PATHS, ArchitecturePatternEvaluator.DEFERRED_PATHS, DEFINITIONS));
    private static final Set<PatternId> IDS = Set.of(PatternId.BFF_SESSION, PatternId.SERVER_SIDE_SESSION, PatternId.SPA_CODE_PKCE,
            PatternId.NATIVE_CODE_PKCE, PatternId.M2M_CLIENT_CREDENTIALS);
    private final CatalogScopedProfileCases cases;
    private final ObjectMapper mapper;
    public CatalogArchitectureImpactService(CatalogScopedProfileCases cases, ObjectMapper mapper) { this.cases = cases; this.mapper = mapper; }

    public record Definition(PatternId patternId, String displayName, ClientType clientType, TokenHandling tokenHandling,
            List<String> advantages, List<String> tradeoffs, List<String> prerequisites, List<URI> references) {
        public Definition { advantages = List.copyOf(advantages); tradeoffs = List.copyOf(tradeoffs); prerequisites = List.copyOf(prerequisites); references = List.copyOf(references); }
    }
    public record RuleCheck(String profilePath, Outcome outcome, Reason reasonCode) {
        public RuleCheck { Objects.requireNonNull(profilePath); Objects.requireNonNull(outcome); Objects.requireNonNull(reasonCode); }
    }
    public record PatternResult(PatternId patternId, ArchitecturePatternPreflight.Status status, List<RuleCheck> checks) {
        public PatternResult {
            Objects.requireNonNull(patternId); Objects.requireNonNull(status); checks = List.copyOf(checks);
            if (!IDS.contains(patternId) || checks.size() != ArchitecturePatternEvaluator.CHECKED_PATHS.size()
                    || !new HashSet<>(checks.stream().map(RuleCheck::profilePath).toList()).equals(Set.copyOf(ArchitecturePatternEvaluator.CHECKED_PATHS)))
                throw new IllegalArgumentException("Invalid architecture rule result");
        }
    }
    public record Scenario(String scenarioId, List<PatternResult> patterns) {
        public Scenario {
            patterns = List.copyOf(patterns);
            if (!CatalogScopedProfileCases.IDS.contains(scenarioId) || patterns.size() != PATTERN_COUNT
                    || !new HashSet<>(patterns.stream().map(PatternResult::patternId).toList()).equals(IDS))
                throw new IllegalArgumentException("Incomplete architecture scenario");
        }
    }
    public record Analysis(Instant evaluatedAt, String scenarioSetSha256, List<Definition> definitions, List<Scenario> scenarios) {
        public Analysis {
            Objects.requireNonNull(evaluatedAt); definitions = List.copyOf(definitions); scenarios = List.copyOf(scenarios);
            if (!hash(scenarioSetSha256) || !definitions.equals(DEFINITIONS) || definitions.size() != PATTERN_COUNT
                    || scenarios.size() != CatalogScopedProfileCases.COUNT
                    || !new HashSet<>(scenarios.stream().map(Scenario::scenarioId).toList()).equals(CatalogScopedProfileCases.IDS))
                throw new IllegalArgumentException("Incomplete architecture analysis");
        }
        @JsonProperty public String scope() { return "PROFILE_ARCHITECTURE_PATTERN_REGRESSION"; }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public String architecturePolicyVersion() { return ArchitecturePatternEvaluator.POLICY_VERSION; }
        @JsonProperty public String architectureDefinitionsSha256() { return DEFINITIONS_SHA256; }
        @JsonProperty public String scenarioSetVersion() { return CatalogScopedProfileCases.VERSION; }
        @JsonProperty public String analysisBasis() { return "CONDITIONAL_PATTERN_PREREQUISITES_NOT_VERIFIED"; }
        @JsonProperty public boolean configurationVerified() { return false; }
        @JsonProperty public boolean prerequisitesVerified() { return false; }
        @JsonProperty public boolean providerCompatibilityVerified() { return false; }
        @JsonProperty public boolean coverageComplete() { return false; }
        @JsonProperty public boolean sourceVerificationPerformed() { return false; }
        @JsonProperty public boolean baselineVerified() { return false; }
        @JsonProperty public boolean approvalGranted() { return false; }
        @JsonProperty public boolean writesPerformed() { return false; }
        @JsonProperty public boolean publicationReady() { return false; }
        @JsonProperty public boolean evaluationReady() { return false; }
        @JsonProperty public boolean recommendationReady() { return false; }
    }
    public enum CheckStatus { NOT_CHECKED, ANALYZED }
    /** Profile/library digests only: no caller profile values, catalog scope, source bodies or actor identities. */
    public record Check(CheckStatus status, Instant evaluatedAt, String scenarioSetSha256, String analysisSha256,
            int checkedProfiles, int checkedPatterns, int conditionalMatches, int needsInformation, int notApplicable) {
        public Check {
            Objects.requireNonNull(status);
            if (status == CheckStatus.NOT_CHECKED ? evaluatedAt != null || scenarioSetSha256 != null || analysisSha256 != null
                    || checkedProfiles != 0 || checkedPatterns != 0 || conditionalMatches != 0 || needsInformation != 0 || notApplicable != 0
                    : evaluatedAt == null || !hash(scenarioSetSha256) || !hash(analysisSha256) || checkedProfiles != CatalogScopedProfileCases.COUNT
                        || checkedPatterns != checkedProfiles * PATTERN_COUNT || conditionalMatches < 0 || needsInformation < 0 || notApplicable < 0
                        || (long) conditionalMatches + needsInformation + notApplicable != checkedPatterns)
                throw new IllegalArgumentException("Inconsistent architecture summary");
        }
        public static Check notChecked() { return new Check(CheckStatus.NOT_CHECKED, null, null, null, 0, 0, 0, 0, 0); }
        @JsonProperty public String scope() { return "PROFILE_ARCHITECTURE_PATTERN_REGRESSION"; }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public String architecturePolicyVersion() { return ArchitecturePatternEvaluator.POLICY_VERSION; }
        @JsonProperty public String architectureDefinitionsSha256() { return DEFINITIONS_SHA256; }
        @JsonProperty public String scenarioSetVersion() { return CatalogScopedProfileCases.VERSION; }
        @JsonProperty public String analysisBasis() { return "CONDITIONAL_PATTERN_PREREQUISITES_NOT_VERIFIED"; }
        @JsonProperty public boolean allDeclaredPatternsChecked() { return status == CheckStatus.ANALYZED; }
        @JsonProperty public boolean configurationVerified() { return false; }
        @JsonProperty public boolean prerequisitesVerified() { return false; }
        @JsonProperty public boolean providerCompatibilityVerified() { return false; }
        @JsonProperty public boolean coverageComplete() { return false; }
        @JsonProperty public boolean storedReportVerified() { return false; }
        @JsonProperty public boolean sourceVerificationPerformed() { return false; }
        @JsonProperty public boolean baselineVerified() { return false; }
        @JsonProperty public boolean approvalGranted() { return false; }
        @JsonProperty public boolean writesPerformed() { return false; }
        @JsonProperty public boolean publicationReady() { return false; }
        @JsonProperty public boolean evaluationReady() { return false; }
        @JsonProperty public boolean recommendationReady() { return false; }
    }

    public Analysis analyzeAt(Instant at) { return analyze(cases.definitions(), cases.sha256(), at); }
    Analysis analyze(List<CatalogScenarioCases.Definition> definitions, String digest, Instant at) {
        if (DEFINITIONS.size() != PATTERN_COUNT || !new HashSet<>(DEFINITIONS.stream().map(Definition::patternId).toList()).equals(IDS)
                || definitions.size() != CatalogScopedProfileCases.COUNT
                || !new HashSet<>(definitions.stream().map(CatalogScenarioCases.Definition::id).toList()).equals(CatalogScopedProfileCases.IDS)
                || definitions.stream().anyMatch(d -> d.profileSchemaVersion() != 5) || !CatalogDraftCanonicalizer.sha256(definitions).equals(digest))
            throw new IllegalStateException("Review architecture/scenario policy binding");
        var scenarios = definitions.stream().map(d -> {
            var profile = mapper.treeToValue(d.profile(), ApplicationIdentityProfile.class);
            var patterns = ArchitecturePatternEvaluator.evaluate(profile).stream().map(p -> new PatternResult(p.patternId(), p.status(),
                    p.checks().stream().map(c -> new RuleCheck(c.profilePath(), c.outcome(), c.reasonCode())).toList())).toList();
            return new Scenario(d.id(), patterns);
        }).toList();
        return new Analysis(at, digest, DEFINITIONS, scenarios);
    }
    public Check inspectAt(Instant at) { return summarize(analyzeAt(at)); }
    public Check summarize(Analysis report) {
        var patterns = report.scenarios().stream().flatMap(s -> s.patterns().stream()).toList();
        return new Check(CheckStatus.ANALYZED, report.evaluatedAt(), report.scenarioSetSha256(), CatalogDraftCanonicalizer.sha256(report), report.scenarios().size(),
                patterns.size(), (int) patterns.stream().filter(p -> p.status() == ArchitecturePatternPreflight.Status.MATCHES_CHECKED_REQUIREMENTS).count(),
                (int) patterns.stream().filter(p -> p.status() == ArchitecturePatternPreflight.Status.NEEDS_INFORMATION).count(),
                (int) patterns.stream().filter(p -> p.status() == ArchitecturePatternPreflight.Status.NOT_APPLICABLE).count());
    }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
}

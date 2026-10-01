package io.authweave.core.evaluation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import static io.authweave.core.evaluation.ArchitecturePatternPreflight.PatternId;

/** Conditional design declarations only: no observed deployment, provider evidence, approval or configuration write. */
public final class ArchitecturePrerequisiteEvaluator {
    public static final String POLICY_VERSION = "architecture-prerequisites-1";
    public enum PrerequisiteId {
        BFF_BACKEND_API_PROXY, BFF_SESSION_DEFENSES,
        SERVER_SESSION_RESOURCE_ACCESS, DIRECT_BROWSER_API_ACCESS_ASSESSED,
        SPA_PUBLIC_PKCE_BROWSER_ENDPOINTS, SPA_TOKEN_THREAT_MODEL,
        NATIVE_EXTERNAL_AGENT_REDIRECT_PKCE, NATIVE_STORAGE_API_AUTHORIZATION,
        WORKLOAD_CONFIDENTIAL_CLIENT, WORKLOAD_AUTHORIZATION_CONTEXT, WORKLOAD_GRANT_API_PERMISSIONS
    }
    public enum ClientScope { SELECTED, NOT_SELECTED, UNKNOWN }
    public enum Declaration { SATISFIED, NOT_SATISFIED, UNKNOWN }
    public enum Outcome { CONDITIONALLY_SATISFIED, CONDITIONALLY_NOT_SATISFIED, UNKNOWN, NOT_APPLICABLE }
    public enum Reason { DECLARED_CONDITION_SATISFIED, DECLARED_CONDITION_NOT_SATISFIED, CONDITION_UNKNOWN, CLIENT_SCOPE_UNKNOWN, PATTERN_NOT_APPLICABLE }
    public enum Status { CONDITIONALLY_MATCHES, CONDITIONALLY_DOES_NOT_MATCH, NEEDS_INFORMATION, NOT_APPLICABLE }
    public record Definition(PatternId patternId, PrerequisiteId prerequisiteId, String description) { }
    public static final List<Definition> DEFINITIONS = definitions();
    private ArchitecturePrerequisiteEvaluator() { }

    public record Check(PrerequisiteId prerequisiteId, Outcome outcome, Reason reasonCode) {
        public Check {
            Objects.requireNonNull(prerequisiteId); Objects.requireNonNull(outcome); Objects.requireNonNull(reasonCode);
            var expected = switch (reasonCode) {
                case DECLARED_CONDITION_SATISFIED -> Outcome.CONDITIONALLY_SATISFIED;
                case DECLARED_CONDITION_NOT_SATISFIED -> Outcome.CONDITIONALLY_NOT_SATISFIED;
                case CONDITION_UNKNOWN, CLIENT_SCOPE_UNKNOWN -> Outcome.UNKNOWN;
                case PATTERN_NOT_APPLICABLE -> Outcome.NOT_APPLICABLE;
            };
            if (outcome != expected) throw new IllegalArgumentException("Inconsistent prerequisite outcome");
        }
    }
    public record Analysis(PatternId patternId, ClientScope clientScope, List<Check> checks) {
        public Analysis {
            Objects.requireNonNull(patternId); Objects.requireNonNull(clientScope); checks = List.copyOf(checks);
            var ids = ids(patternId);
            if (checks.size() != ids.size() || !new HashSet<>(checks.stream().map(Check::prerequisiteId).toList()).equals(ids)
                    || checks.stream().anyMatch(c -> switch (clientScope) {
                        case UNKNOWN -> c.reasonCode() != Reason.CLIENT_SCOPE_UNKNOWN;
                        case NOT_SELECTED -> c.reasonCode() != Reason.PATTERN_NOT_APPLICABLE;
                        case SELECTED -> c.reasonCode() == Reason.CLIENT_SCOPE_UNKNOWN || c.reasonCode() == Reason.PATTERN_NOT_APPLICABLE;
                    })) throw new IllegalArgumentException("Incomplete or unbound prerequisite analysis");
        }
        @JsonProperty public Status status() {
            if (clientScope == ClientScope.NOT_SELECTED) return Status.NOT_APPLICABLE;
            if (checks.stream().anyMatch(c -> c.outcome() == Outcome.CONDITIONALLY_NOT_SATISFIED)) return Status.CONDITIONALLY_DOES_NOT_MATCH;
            if (checks.stream().anyMatch(c -> c.outcome() == Outcome.UNKNOWN)) return Status.NEEDS_INFORMATION;
            return Status.CONDITIONALLY_MATCHES;
        }
        @JsonProperty public String policyVersion() { return POLICY_VERSION; }
        @JsonProperty public String analysisBasis() { return "UNVERIFIED_DESIGN_DECLARATIONS"; }
        @JsonProperty public boolean configurationVerified() { return false; }
        @JsonProperty public boolean providerCompatibilityVerified() { return false; }
        @JsonProperty public boolean recommendationReady() { return false; }
    }

    public static Analysis evaluate(PatternId patternId, ClientScope scope, Map<PrerequisiteId, Declaration> declarations) {
        Objects.requireNonNull(patternId); Objects.requireNonNull(scope); declarations = Map.copyOf(declarations);
        if (!ids(patternId).containsAll(declarations.keySet())) throw new IllegalArgumentException("Prerequisite belongs to another pattern");
        var supplied = declarations;
        var checks = DEFINITIONS.stream().filter(d -> d.patternId() == patternId).map(d -> {
            Reason reason = scope == ClientScope.UNKNOWN ? Reason.CLIENT_SCOPE_UNKNOWN
                    : scope == ClientScope.NOT_SELECTED ? Reason.PATTERN_NOT_APPLICABLE
                    : switch (supplied.getOrDefault(d.prerequisiteId(), Declaration.UNKNOWN)) {
                        case SATISFIED -> Reason.DECLARED_CONDITION_SATISFIED;
                        case NOT_SATISFIED -> Reason.DECLARED_CONDITION_NOT_SATISFIED;
                        case UNKNOWN -> Reason.CONDITION_UNKNOWN;
                    };
            Outcome outcome = switch (reason) {
                case DECLARED_CONDITION_SATISFIED -> Outcome.CONDITIONALLY_SATISFIED;
                case DECLARED_CONDITION_NOT_SATISFIED -> Outcome.CONDITIONALLY_NOT_SATISFIED;
                case CONDITION_UNKNOWN, CLIENT_SCOPE_UNKNOWN -> Outcome.UNKNOWN;
                case PATTERN_NOT_APPLICABLE -> Outcome.NOT_APPLICABLE;
            };
            return new Check(d.prerequisiteId(), outcome, reason);
        }).toList();
        return new Analysis(patternId, scope, checks);
    }

    public static ClientScope scope(ArchitecturePatternPreflight.Pattern pattern) {
        var client = pattern.checks().stream().filter(c -> c.profilePath().equals("application.clients")).findFirst().orElseThrow();
        return switch (client.reasonCode()) {
            case CLIENT_SELECTED -> ClientScope.SELECTED;
            case CLIENT_NOT_SELECTED -> ClientScope.NOT_SELECTED;
            case CLIENT_CONTEXT_UNKNOWN -> ClientScope.UNKNOWN;
            default -> throw new IllegalStateException("Review architecture client-scope binding");
        };
    }
    private static Set<PrerequisiteId> ids(PatternId pattern) {
        return DEFINITIONS.stream().filter(d -> d.patternId() == pattern).map(Definition::prerequisiteId).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    private static List<Definition> definitions() {
        var result = new ArrayList<Definition>();
        for (var pattern : ArchitecturePatternEvaluator.evaluate(ApplicationIdentityProfile.unknown())) {
            var ids = switch (pattern.patternId()) {
                case BFF_SESSION -> List.of(PrerequisiteId.BFF_BACKEND_API_PROXY, PrerequisiteId.BFF_SESSION_DEFENSES);
                case SERVER_SIDE_SESSION -> List.of(PrerequisiteId.SERVER_SESSION_RESOURCE_ACCESS, PrerequisiteId.DIRECT_BROWSER_API_ACCESS_ASSESSED);
                case SPA_CODE_PKCE -> List.of(PrerequisiteId.SPA_PUBLIC_PKCE_BROWSER_ENDPOINTS, PrerequisiteId.SPA_TOKEN_THREAT_MODEL);
                case NATIVE_CODE_PKCE -> List.of(PrerequisiteId.NATIVE_EXTERNAL_AGENT_REDIRECT_PKCE, PrerequisiteId.NATIVE_STORAGE_API_AUTHORIZATION);
                case M2M_CLIENT_CREDENTIALS -> List.of(PrerequisiteId.WORKLOAD_CONFIDENTIAL_CLIENT, PrerequisiteId.WORKLOAD_AUTHORIZATION_CONTEXT, PrerequisiteId.WORKLOAD_GRANT_API_PERMISSIONS);
            };
            if (ids.size() != pattern.prerequisites().size()) throw new IllegalStateException("Review architecture prerequisite library drift");
            for (int i = 0; i < ids.size(); i++) result.add(new Definition(pattern.patternId(), ids.get(i), pattern.prerequisites().get(i)));
        }
        if (result.size() != PrerequisiteId.values().length || result.stream().map(Definition::prerequisiteId).distinct().count() != result.size())
            throw new IllegalStateException("Incomplete architecture prerequisite library");
        return List.copyOf(result);
    }
}

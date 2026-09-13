package io.authweave.core.evaluation;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;

/** Checks only client selection and browser token exposure; prerequisites are not verified. */
public record ArchitecturePatternPreflight(
        UUID workspaceId, UUID assessmentId, long assessmentVersion,
        String policyVersion, Instant evaluatedAt, String scope, boolean recommendationReady,
        List<ClientType> selectedClients, RequirementCriticality browserTokenExposureRequirement,
        List<String> checkedPaths, List<String> deferredPaths, List<Pattern> patterns) {

    public ArchitecturePatternPreflight {
        selectedClients = List.copyOf(selectedClients);
        checkedPaths = List.copyOf(checkedPaths);
        deferredPaths = List.copyOf(deferredPaths);
        patterns = List.copyOf(patterns);
    }

    public enum PatternId { BFF_SESSION, SERVER_SIDE_SESSION, SPA_CODE_PKCE, NATIVE_CODE_PKCE, M2M_CLIENT_CREDENTIALS }
    public enum TokenHandling { SERVER_SIDE, BROWSER, NATIVE_APP, WORKLOAD }
    public enum Status { MATCHES_CHECKED_REQUIREMENTS, NEEDS_INFORMATION, NOT_APPLICABLE }
    public enum Outcome { PASS, UNKNOWN, NOT_APPLIED }
    public enum Reason {
        CLIENT_SELECTED, CLIENT_NOT_SELECTED, CLIENT_CONTEXT_UNKNOWN, PATTERN_NOT_APPLICABLE,
        BROWSER_CRITERION_NOT_APPLICABLE, TOKENS_HELD_SERVER_SIDE, ACCEPTABLE_EXPOSURE_UNDEFINED,
        MINIMIZATION_PROHIBITION_UNDEFINED, REQUIREMENT_UNKNOWN, PREFERENCE_NOT_SCORED, NO_REQUIREMENT
    }

    public record Check(String profilePath, Outcome outcome, Reason reasonCode, String explanation) { }

    public record Pattern(PatternId patternId, String displayName, ClientType clientType,
            TokenHandling tokenHandling, Status status, List<Check> checks,
            List<String> advantages, List<String> tradeoffs, List<String> prerequisites, List<URI> references) {
        public Pattern {
            checks = List.copyOf(checks);
            advantages = List.copyOf(advantages);
            tradeoffs = List.copyOf(tradeoffs);
            prerequisites = List.copyOf(prerequisites);
            references = List.copyOf(references);
        }
    }
}

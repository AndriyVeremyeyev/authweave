package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ProvisioningLifecyclePreview(UUID workspaceId, UUID assessmentId, long assessmentVersion,
        Instant evaluatedAt, ProvisioningLifecycleEvaluator.Analysis analysis) {
    public ProvisioningLifecyclePreview {
        Objects.requireNonNull(workspaceId); Objects.requireNonNull(assessmentId); Objects.requireNonNull(evaluatedAt); Objects.requireNonNull(analysis);
        if (assessmentVersion < 0 || assessmentVersion > 9007199254740991L) throw new IllegalArgumentException("Invalid assessment version");
    }
    @JsonProperty public String policyVersion() { return ProvisioningLifecycleEvaluator.POLICY_VERSION; }
    @JsonProperty public String scope() { return "PROVISIONING_LIFECYCLE_DESIGN_PREVIEW"; }
    @JsonProperty public String analysisBasis() { return "SAVED_REQUIREMENTS_AND_UNVERIFIED_DESIGN_DECLARATIONS"; }
    @JsonProperty public List<ProvisioningLifecycleEvaluator.Definition> patterns() { return ProvisioningLifecycleEvaluator.DEFINITIONS; }
    @JsonProperty public List<ProvisioningLifecycleEvaluator.ConditionDefinition> conditionDefinitions() { return ProvisioningLifecycleEvaluator.CONDITION_DEFINITIONS; }
    @JsonProperty public List<String> checkedProfilePaths() { return ProvisioningLifecycleEvaluator.CHECKED_PATHS; }
    @JsonProperty public List<String> deferredBoundaries() { return ProvisioningLifecycleEvaluator.DEFERRED_BOUNDARIES; }
    @JsonProperty public boolean configurationVerified() { return false; }
    @JsonProperty public boolean providerCompatibilityVerified() { return false; }
    @JsonProperty public boolean lifecycleVerified() { return false; }
    @JsonProperty public boolean groupSynchronizationVerified() { return false; }
    @JsonProperty public boolean accessRevocationVerified() { return false; }
    @JsonProperty public boolean writesPerformed() { return false; }
    @JsonProperty public boolean publicationReady() { return false; }
    @JsonProperty public boolean recommendationReady() { return false; }
}

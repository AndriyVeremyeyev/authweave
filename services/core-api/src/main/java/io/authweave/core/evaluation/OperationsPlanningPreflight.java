package io.authweave.core.evaluation;

import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.authweave.core.assessment.domain.profile.OperationalConstraints.*;
import io.authweave.core.assessment.domain.profile.UsagePlanning;
import io.authweave.core.evaluation.OperationsPlanningEvaluator.*;

/** Output-only snapshot of owner inputs and generic planning alternatives; never a deployment recommendation. */
public record OperationsPlanningPreflight(UUID workspaceId, UUID assessmentId, long assessmentVersion, Instant evaluatedAt,
        Inputs inputs, UsageInputs usageInputs, List<Option> options) {
    public record Inputs(HostingPreference hosting, DeploymentTarget deploymentTarget, IdentityExpertise identityExpertise, BudgetSensitivity budgetSensitivity) {
        public Inputs { Objects.requireNonNull(hosting); Objects.requireNonNull(deploymentTarget); Objects.requireNonNull(identityExpertise); Objects.requireNonNull(budgetSensitivity); }
    }
    public record UsageInputs(UsagePlanningPreflight.Status status, List<UsagePlanning.Metric> recordedMetrics, List<String> missingPaths) {
        public UsageInputs {
            Objects.requireNonNull(status); recordedMetrics = List.copyOf(recordedMetrics); missingPaths = List.copyOf(missingPaths);
            var recorded = recordedMetrics;
            var absent = List.of(UsagePlanning.Metric.values()).stream().filter(m -> !recorded.contains(m)).map(m -> "operations.usagePlanning.volumes." + m.name()).toList();
            var permitted = Stream.concat(Stream.of("operations.usagePlanning.scopeDescription", "operations.usagePlanning.assumptions"),
                    List.of(UsagePlanning.Metric.values()).stream().map(m -> "operations.usagePlanning.volumes." + m.name())).toList();
            var orderedMissing = Stream.concat(Stream.concat(missingPaths.contains("operations.usagePlanning.scopeDescription") ? Stream.of("operations.usagePlanning.scopeDescription") : Stream.empty(), absent.stream()),
                    missingPaths.contains("operations.usagePlanning.assumptions") ? Stream.of("operations.usagePlanning.assumptions") : Stream.empty()).toList();
            if (!recordedMetrics.equals(recordedMetrics.stream().distinct().sorted().toList()) || new HashSet<>(missingPaths).size() != missingPaths.size()
                    || !permitted.containsAll(missingPaths) || !missingPaths.stream().filter(p -> p.contains(".volumes.")).toList().equals(absent)
                    || !missingPaths.equals(orderedMissing) || recordedMetrics.isEmpty() && missingPaths.contains("operations.usagePlanning.assumptions")
                    || (status == UsagePlanningPreflight.Status.NEEDS_INFORMATION) != !missingPaths.isEmpty())
                throw new IllegalArgumentException("Inconsistent usage input inventory");
        }
    }
    public record Option(OptionId optionId, Alignment hostingAlignment, SupportPlanning supportPlanning, BudgetPlanning budgetPlanning,
            List<String> advantages, List<String> tradeoffs, List<String> responsibilities) {
        public Option {
            Objects.requireNonNull(optionId); Objects.requireNonNull(hostingAlignment); Objects.requireNonNull(supportPlanning); Objects.requireNonNull(budgetPlanning);
            advantages = List.copyOf(advantages); tradeoffs = List.copyOf(tradeoffs); responsibilities = List.copyOf(responsibilities);
        }
    }
    public OperationsPlanningPreflight {
        Objects.requireNonNull(workspaceId); Objects.requireNonNull(assessmentId); Objects.requireNonNull(evaluatedAt); Objects.requireNonNull(inputs); Objects.requireNonNull(usageInputs);
        options = List.copyOf(options);
        if (assessmentVersion < 0 || assessmentVersion > 9007199254740991L || !options.equals(OperationsPlanningEvaluator.options(inputs)))
            throw new IllegalArgumentException("Unbound operations planning alternatives");
    }
    @JsonProperty public String policyVersion() { return OperationsPlanningEvaluator.POLICY_VERSION; }
    @JsonProperty public String scope() { return "UNVERIFIED_OPERATIONS_PLANNING"; }
    @JsonProperty public String analysisBasis() { return "SAVED_OWNER_INPUTS_AND_GENERIC_RESPONSIBILITIES"; }
    @JsonProperty public UsagePlanningPreflight.Status status() { return missingPaths().isEmpty() ? UsagePlanningPreflight.Status.INPUTS_RECORDED : UsagePlanningPreflight.Status.NEEDS_INFORMATION; }
    @JsonProperty public List<String> missingPaths() { return Stream.concat(OperationsPlanningEvaluator.missingInputs(inputs).stream(), usageInputs.missingPaths().stream()).toList(); }
    @JsonProperty public List<String> checkedPaths() { return OperationsPlanningEvaluator.CHECKED_PATHS; }
    @JsonProperty public List<String> sharedResponsibilities() { return OperationsPlanningEvaluator.SHARED_RESPONSIBILITIES; }
    @JsonProperty public List<String> deferredBoundaries() { return OperationsPlanningEvaluator.DEFERRED_BOUNDARIES; }
    @JsonProperty public List<URI> references() { return OperationsPlanningEvaluator.REFERENCES; }
    @JsonProperty public String hostingExplanation() { return "Hosting alignment is a preference comparison, not provider eligibility or a hard requirement."; }
    @JsonProperty public String deploymentExplanation() { return "The saved deployment target describes the assessed application, not a verified identity-service location or supported hosting combination."; }
    @JsonProperty public String budgetExplanation() { return "Budget sensitivity is a preference, not a spending cap. Recorded usage, including zero, cannot establish a free tier or total cost."; }
    @JsonProperty public boolean providerEligibilityEvaluated() { return false; }
    @JsonProperty public boolean deploymentCompatibilityVerified() { return false; }
    @JsonProperty public boolean operationalReadinessVerified() { return false; }
    @JsonProperty public boolean pricingEvaluated() { return false; }
    @JsonProperty public boolean costModelEvaluated() { return false; }
    @JsonProperty public boolean budgetFitVerified() { return false; }
    @JsonProperty public boolean configurationVerified() { return false; }
    @JsonProperty public boolean recommendationReady() { return false; }
    @JsonProperty public boolean publicationReady() { return false; }
    @JsonProperty public boolean writesPerformed() { return false; }
}

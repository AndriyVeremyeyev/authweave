package io.authweave.core.evaluation;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import io.authweave.core.assessment.domain.profile.OperationalConstraints;
import io.authweave.core.assessment.domain.profile.OperationalConstraints.HostingPreference;
import io.authweave.core.assessment.domain.profile.OperationalConstraints.IdentityExpertise;

/** Generic planning responsibilities, not provider facts, exclusion, pricing or operational verification. */
public final class OperationsPlanningEvaluator {
    public static final String POLICY_VERSION = "operations-planning-preflight-1";
    public enum OptionId { MANAGED_IDENTITY_SERVICE, SELF_HOSTED_IDENTITY_SERVICE }
    public enum Alignment { PREFERENCE_ALIGNED, PREFERENCE_DIFFERS, NO_PREFERENCE, PREFERENCE_UNKNOWN }
    public enum SupportPlanning { INTEGRATION_SUPPORT_PLAN_NEEDED, OPERATOR_SUPPORT_PLAN_NEEDED, RESPONSIBILITY_PLAN_NEEDED, SUPPORT_CAPACITY_UNDEFINED }
    public enum BudgetPlanning { COST_MODEL_NEEDED, BUDGET_SCOPE_UNDEFINED }
    public static final List<String> CHECKED_PATHS = List.of("operations.hosting", "operations.deploymentTarget", "operations.identityExpertise",
            "operations.budgetSensitivity", "operations.usagePlanning.scopeDescription", "operations.usagePlanning.assumptions", "operations.usagePlanning.volumes");
    public static final List<String> SHARED_RESPONSIBILITIES = List.of(
            "Own application integration, tenant/client configuration and application authorization.",
            "Assign lifecycle, incident response and evidence-review owners; document escalation and recovery responsibilities.",
            "Verify the operator contract, limits and responsibility split for the exact deployment.");
    public static final List<String> DEFERRED_BOUNDARIES = List.of(
            "Exact provider, plan, region and application-to-identity deployment compatibility",
            "Observed operator capacity, responsibilities, support and recovery exercises",
            "Dated prices, billing-unit mapping, paid features and additional environments",
            "Infrastructure, support, migration and team operational costs",
            "Protocol, lifecycle, auditability, assurance and compliance verification",
            "Provider eligibility, scoring, ranking and final recommendation");
    public static final List<URI> REFERENCES = List.of(URI.create("https://learn.microsoft.com/en-us/azure/security/fundamentals/shared-responsibility"),
            URI.create("https://www.keycloak.org/server/configuration-production"));
    private OperationsPlanningEvaluator() { }

    public static OperationsPlanningPreflight evaluate(UUID workspaceId, UUID assessmentId, long version, OperationalConstraints operations, Instant at) {
        var inputs = new OperationsPlanningPreflight.Inputs(operations.hosting(), operations.deploymentTarget(), operations.identityExpertise(), operations.budgetSensitivity());
        var usage = UsagePlanningEvaluator.evaluate(workspaceId, assessmentId, version, operations.usagePlanning(), at);
        var recorded = usage.quantityChecks().stream().filter(c -> c.input() != null).map(UsagePlanningPreflight.QuantityCheck::metric).toList();
        return new OperationsPlanningPreflight(workspaceId, assessmentId, version, at, inputs,
                new OperationsPlanningPreflight.UsageInputs(usage.status(), recorded, usage.missingPaths()), options(inputs));
    }

    public static List<String> missingInputs(OperationsPlanningPreflight.Inputs input) {
        var missing = new ArrayList<String>();
        if (input.hosting() == HostingPreference.UNKNOWN) missing.add("operations.hosting");
        if (input.deploymentTarget() == OperationalConstraints.DeploymentTarget.UNDECIDED) missing.add("operations.deploymentTarget");
        if (input.identityExpertise() == IdentityExpertise.UNKNOWN) missing.add("operations.identityExpertise");
        if (input.budgetSensitivity() == OperationalConstraints.BudgetSensitivity.UNKNOWN) missing.add("operations.budgetSensitivity");
        return List.copyOf(missing);
    }

    public static List<OperationsPlanningPreflight.Option> options(OperationsPlanningPreflight.Inputs input) {
        return List.of(OptionId.values()).stream().map(id -> {
            boolean managed = id == OptionId.MANAGED_IDENTITY_SERVICE;
            var alignment = input.hosting() == HostingPreference.UNKNOWN ? Alignment.PREFERENCE_UNKNOWN
                    : input.hosting() == HostingPreference.NO_PREFERENCE ? Alignment.NO_PREFERENCE
                    : (input.hosting() == HostingPreference.MANAGED) == managed ? Alignment.PREFERENCE_ALIGNED : Alignment.PREFERENCE_DIFFERS;
            var support = input.identityExpertise() == IdentityExpertise.UNKNOWN ? SupportPlanning.SUPPORT_CAPACITY_UNDEFINED
                    : input.identityExpertise() != IdentityExpertise.LIMITED ? SupportPlanning.RESPONSIBILITY_PLAN_NEEDED
                    : managed ? SupportPlanning.INTEGRATION_SUPPORT_PLAN_NEEDED : SupportPlanning.OPERATOR_SUPPORT_PLAN_NEEDED;
            return new OperationsPlanningPreflight.Option(id, alignment, support,
                    input.budgetSensitivity() == OperationalConstraints.BudgetSensitivity.UNKNOWN ? BudgetPlanning.BUDGET_SCOPE_UNDEFINED : BudgetPlanning.COST_MODEL_NEEDED,
                    managed ? List.of("Can transfer operation of the identity platform to a service operator, subject to the exact contract.")
                            : List.of("Can give your team more direct control over the identity components it operates."),
                    managed ? List.of("Service reliance, plan/region limits and export options need independent evaluation.", "Managed service does not remove application configuration and access-management responsibilities.")
                            : List.of("More platform operation and recovery work remains in the scope your team must plan.", "Self-hosting and zero recorded usage do not imply zero infrastructure or team costs."),
                    managed ? List.of("Review vendor-operated responsibilities and the customer's remaining configuration duties.", "Validate support, recovery, export and service commitments for the exact plan; no verified SLA is inferred.")
                            : List.of("Plan runtime/database/network/TLS hardening and security updates for components you operate.", "Plan and test monitoring, backups, recovery and availability for the identity deployment."));
        }).toList();
    }
}

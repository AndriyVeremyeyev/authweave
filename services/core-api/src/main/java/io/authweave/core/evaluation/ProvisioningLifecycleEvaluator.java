package io.authweave.core.evaluation;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.authweave.core.assessment.domain.profile.ProvisioningRequirements;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;

/** Design alternatives, not provider support, observed lifecycle behavior or a recommendation. */
public final class ProvisioningLifecycleEvaluator {
    public static final String POLICY_VERSION = "provisioning-lifecycle-design-1";
    public enum PatternId { SCIM_PUSH, JIT_LOGIN, SCIM_AND_JIT }
    public enum ConditionId {
        TENANT_AND_SUBJECT_CORRELATION, ATTRIBUTE_OWNERSHIP_AND_MAPPING,
        OFFBOARDING_AND_ACCESS_REVOCATION, FAILURE_RECOVERY_AND_RECONCILIATION,
        SCIM_CLIENT_SERVER_DIRECTION, SCIM_USER_OPERATIONS,
        JIT_TRUSTED_LOGIN_AND_LINKING, SCIM_JIT_COLLISION_POLICY
    }
    public enum Declaration { SATISFIED, NOT_SATISFIED, UNKNOWN }
    public enum Outcome { CONDITIONALLY_SATISFIED, CONDITIONALLY_NOT_SATISFIED, UNKNOWN, NOT_APPLIED }
    public enum Reason {
        REQUIRED_MECHANISM_PLANNED, REQUIRED_MECHANISM_ABSENT, FORBIDDEN_MECHANISM_PLANNED, FORBIDDEN_MECHANISM_ABSENT,
        REQUIREMENT_UNKNOWN, PREFERENCE_NOT_SCORED, NO_REQUIREMENT, GROUP_LIFECYCLE_UNASSESSED, GROUP_PROHIBITION_UNASSESSED,
        DECLARED_CONDITION_SATISFIED, DECLARED_CONDITION_NOT_SATISFIED, CONDITION_UNKNOWN
    }
    public enum Status { CONDITIONALLY_MATCHES, CONDITIONALLY_DOES_NOT_MATCH, NEEDS_INFORMATION }
    public record Definition(PatternId patternId, String displayName, boolean scimPlanned, boolean jitPlanned,
            List<String> advantages, List<String> tradeoffs, List<ConditionId> conditions, List<URI> references) {
        public Definition { advantages = List.copyOf(advantages); tradeoffs = List.copyOf(tradeoffs); conditions = List.copyOf(conditions); references = List.copyOf(references); }
    }
    public record ConditionDefinition(ConditionId conditionId, String description) { }
    public record RequirementCheck(String profilePath, RequirementCriticality criticality, Outcome outcome, Reason reasonCode) { }
    public record ConditionCheck(ConditionId conditionId, Outcome outcome, Reason reasonCode) { }
    public static final List<String> CHECKED_PATHS = List.of("provisioning.scim", "provisioning.justInTimeProvisioning", "provisioning.groupSynchronization");
    public static final List<String> DEFERRED_BOUNDARIES = List.of("providerOperationsAndEntitlements", "observedProvisioningDelivery",
            "groupMembershipAndAuthorization", "sessionAndTokenRevocation", "reconciliationAndFailureRecovery");
    public static final List<ConditionDefinition> CONDITION_DEFINITIONS = List.of(
            new ConditionDefinition(ConditionId.TENANT_AND_SUBJECT_CORRELATION, "Define tenant boundaries and stable subject correlation; an email match alone is not proof of identity."),
            new ConditionDefinition(ConditionId.ATTRIBUTE_OWNERSHIP_AND_MAPPING, "Define which source owns each attribute and how changes map to the application."),
            new ConditionDefinition(ConditionId.OFFBOARDING_AND_ACCESS_REVOCATION, "Design account offboarding and removal of application access, including existing sessions and tokens; a directory update alone is not proof of revocation."),
            new ConditionDefinition(ConditionId.FAILURE_RECOVERY_AND_RECONCILIATION, "Define recovery, retries, duplicate handling and reconciliation when updates fail or arrive late."),
            new ConditionDefinition(ConditionId.SCIM_CLIENT_SERVER_DIRECTION, "Identify the SCIM client, server, tenant boundary and authorized provisioning credentials in the intended direction."),
            new ConditionDefinition(ConditionId.SCIM_USER_OPERATIONS, "Define and test the intended User create, update and disable or delete operations; a SCIM label does not prove this operation set."),
            new ConditionDefinition(ConditionId.JIT_TRUSTED_LOGIN_AND_LINKING, "Define trusted login-time account creation, attribute mapping and safe linking to an existing subject."),
            new ConditionDefinition(ConditionId.SCIM_JIT_COLLISION_POLICY, "Define ownership and collision handling when SCIM and login-time creation act on the same account."));
    private static final URI SCIM_PROTOCOL = URI.create("https://www.rfc-editor.org/rfc/rfc7644.html");
    private static final URI SCIM_SCHEMA = URI.create("https://www.rfc-editor.org/rfc/rfc7643.html");
    private static final URI JIT_EXAMPLE = URI.create("https://zitadel.com/docs/guides/integrate/identity-providers/introduction");
    public static final List<Definition> DEFINITIONS = definitions();
    private ProvisioningLifecycleEvaluator() { }

    public record Analysis(PatternId patternId, ProvisioningRequirements requirements, Map<ConditionId, Declaration> declarations) {
        public Analysis {
            Objects.requireNonNull(patternId); Objects.requireNonNull(requirements);
            declarations = Map.copyOf(declarations);
            if (!definition(patternId).conditions().containsAll(declarations.keySet())) throw new IllegalArgumentException("Condition belongs to another provisioning pattern");
        }
        @JsonProperty public List<RequirementCheck> requirementChecks() {
            var selected = definition(patternId);
            return List.of(mechanism(CHECKED_PATHS.get(0), requirements.scim(), selected.scimPlanned()),
                    mechanism(CHECKED_PATHS.get(1), requirements.justInTimeProvisioning(), selected.jitPlanned()),
                    groups(requirements.groupSynchronization()));
        }
        @JsonProperty public List<ConditionCheck> conditionChecks() {
            return definition(patternId).conditions().stream().map(id -> {
                var declaration = declarations.getOrDefault(id, Declaration.UNKNOWN);
                var reason = switch (declaration) {
                    case SATISFIED -> Reason.DECLARED_CONDITION_SATISFIED;
                    case NOT_SATISFIED -> Reason.DECLARED_CONDITION_NOT_SATISFIED;
                    case UNKNOWN -> Reason.CONDITION_UNKNOWN;
                };
                return new ConditionCheck(id, outcome(reason), reason);
            }).toList();
        }
        @JsonProperty public Status status() {
            var outcomes = new ArrayList<Outcome>();
            requirementChecks().forEach(c -> outcomes.add(c.outcome())); conditionChecks().forEach(c -> outcomes.add(c.outcome()));
            if (outcomes.contains(Outcome.CONDITIONALLY_NOT_SATISFIED)) return Status.CONDITIONALLY_DOES_NOT_MATCH;
            if (outcomes.contains(Outcome.UNKNOWN)) return Status.NEEDS_INFORMATION;
            return Status.CONDITIONALLY_MATCHES;
        }
    }
    public static Analysis evaluate(ProvisioningRequirements requirements, PatternId patternId, Map<ConditionId, Declaration> declarations) {
        return new Analysis(patternId, requirements, declarations);
    }
    public static Definition definition(PatternId pattern) {
        return DEFINITIONS.stream().filter(d -> d.patternId() == pattern).findFirst().orElseThrow();
    }
    private static RequirementCheck mechanism(String path, RequirementCriticality requirement, boolean planned) {
        var reason = switch (requirement) {
            case REQUIRED -> planned ? Reason.REQUIRED_MECHANISM_PLANNED : Reason.REQUIRED_MECHANISM_ABSENT;
            case FORBIDDEN -> planned ? Reason.FORBIDDEN_MECHANISM_PLANNED : Reason.FORBIDDEN_MECHANISM_ABSENT;
            case UNKNOWN -> Reason.REQUIREMENT_UNKNOWN;
            case PREFERRED -> Reason.PREFERENCE_NOT_SCORED;
            case NOT_REQUIRED -> Reason.NO_REQUIREMENT;
        };
        return new RequirementCheck(path, requirement, outcome(reason), reason);
    }
    private static RequirementCheck groups(RequirementCriticality requirement) {
        var reason = switch (requirement) {
            case REQUIRED -> Reason.GROUP_LIFECYCLE_UNASSESSED;
            case FORBIDDEN -> Reason.GROUP_PROHIBITION_UNASSESSED;
            case UNKNOWN -> Reason.REQUIREMENT_UNKNOWN;
            case PREFERRED -> Reason.PREFERENCE_NOT_SCORED;
            case NOT_REQUIRED -> Reason.NO_REQUIREMENT;
        };
        return new RequirementCheck(CHECKED_PATHS.get(2), requirement, outcome(reason), reason);
    }
    private static Outcome outcome(Reason reason) {
        return switch (reason) {
            case REQUIRED_MECHANISM_PLANNED, FORBIDDEN_MECHANISM_ABSENT, DECLARED_CONDITION_SATISFIED -> Outcome.CONDITIONALLY_SATISFIED;
            case REQUIRED_MECHANISM_ABSENT, FORBIDDEN_MECHANISM_PLANNED, DECLARED_CONDITION_NOT_SATISFIED -> Outcome.CONDITIONALLY_NOT_SATISFIED;
            case PREFERENCE_NOT_SCORED, NO_REQUIREMENT -> Outcome.NOT_APPLIED;
            case REQUIREMENT_UNKNOWN, GROUP_LIFECYCLE_UNASSESSED, GROUP_PROHIBITION_UNASSESSED, CONDITION_UNKNOWN -> Outcome.UNKNOWN;
        };
    }
    private static List<Definition> definitions() {
        var common = List.of(ConditionId.TENANT_AND_SUBJECT_CORRELATION, ConditionId.ATTRIBUTE_OWNERSHIP_AND_MAPPING,
                ConditionId.OFFBOARDING_AND_ACCESS_REVOCATION, ConditionId.FAILURE_RECOVERY_AND_RECONCILIATION);
        var scim = new ArrayList<>(common); scim.addAll(List.of(ConditionId.SCIM_CLIENT_SERVER_DIRECTION, ConditionId.SCIM_USER_OPERATIONS));
        var jit = new ArrayList<>(common); jit.add(ConditionId.JIT_TRUSTED_LOGIN_AND_LINKING);
        var hybrid = new ArrayList<>(scim); hybrid.addAll(List.of(ConditionId.JIT_TRUSTED_LOGIN_AND_LINKING, ConditionId.SCIM_JIT_COLLISION_POLICY));
        return List.of(
                new Definition(PatternId.SCIM_PUSH, "SCIM provisioning", true, false,
                        List.of("Lifecycle changes can be delivered independently of an interactive user login."),
                        List.of("The intended SCIM direction, operations and recovery behavior need explicit implementation and testing.", "User provisioning does not establish group-to-role mapping or application session revocation."), scim, List.of(SCIM_PROTOCOL, SCIM_SCHEMA)),
                new Definition(PatternId.JIT_LOGIN, "Login-time JIT provisioning", false, true,
                        List.of("An account can be created or updated as part of a trusted interactive login."),
                        List.of("Login-time creation alone does not supply SCIM or out-of-band offboarding.", "Users who do not log in need a separate update and offboarding path."), jit, List.of(JIT_EXAMPLE)),
                new Definition(PatternId.SCIM_AND_JIT, "SCIM with login-time JIT", true, true,
                        List.of("Separates out-of-band lifecycle delivery from login-time account onboarding."),
                        List.of("Two writers require explicit correlation, attribute ownership and collision policy.", "Combining mechanisms does not prove provider interoperability or access revocation."), hybrid, List.of(SCIM_PROTOCOL, SCIM_SCHEMA, JIT_EXAMPLE)));
    }
}

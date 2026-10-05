package io.authweave.core.evaluation;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.authweave.core.assessment.domain.profile.ProvisioningRequirements;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.Declaration;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.Outcome;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.PatternId;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.Status;

/** Explicit group transport and offboarding design, never observed provider behavior. */
public final class ProvisioningLifecycleV2Evaluator {
    public static final String POLICY_VERSION = "provisioning-lifecycle-design-2";
    public enum GroupStrategy { UNKNOWN, NONE, SCIM_GROUPS, APPLICATION_BRIDGE }
    public enum ConditionId {
        TENANT_AND_SUBJECT_CORRELATION, ATTRIBUTE_OWNERSHIP_AND_MAPPING,
        ACCOUNT_DISABLE_AND_LOGIN_BLOCK, APPLICATION_SESSION_INVALIDATION, TOKEN_REVOCATION_OR_BOUNDED_EXPIRY,
        FAILURE_RECOVERY_AND_RECONCILIATION, SCIM_CLIENT_SERVER_DIRECTION, SCIM_USER_OPERATIONS,
        JIT_TRUSTED_LOGIN_AND_LINKING, SCIM_JIT_COLLISION_POLICY,
        GROUP_SOURCE_AND_MEMBERSHIP_MAPPING, GROUP_CHANGE_DELIVERY_AND_RECONCILIATION,
        GROUP_TO_ROLE_MAPPING_AND_ENFORCEMENT, GROUP_REMOVAL_AND_ACCESS_RECHECK,
        SCIM_GROUP_OPERATIONS, APPLICATION_BRIDGE_AUTHORIZATION_AND_IDEMPOTENCY
    }
    public enum Reason {
        REQUIRED_MECHANISM_PLANNED, REQUIRED_MECHANISM_ABSENT, FORBIDDEN_MECHANISM_PLANNED, FORBIDDEN_MECHANISM_ABSENT,
        REQUIREMENT_UNKNOWN, PREFERENCE_NOT_SCORED, NO_REQUIREMENT,
        GROUP_STRATEGY_UNKNOWN, GROUP_TRANSPORT_PLANNED, NO_GROUP_TRANSPORT_PLANNED, SCIM_GROUPS_REQUIRE_SCIM_PATTERN,
        DECLARED_CONDITION_SATISFIED, DECLARED_CONDITION_NOT_SATISFIED, CONDITION_UNKNOWN
    }
    public record RequirementCheck(String profilePath, RequirementCriticality criticality, Outcome outcome, Reason reasonCode) { }
    public record ConditionCheck(ConditionId conditionId, Outcome outcome, Reason reasonCode) { }
    public record DesignCheck(String boundary, Outcome outcome, Reason reasonCode) { }
    public record ConditionDefinition(ConditionId conditionId, String description) { }
    public record GroupDefinition(GroupStrategy groupStrategy, String displayName, List<String> advantages,
            List<String> tradeoffs, List<ConditionId> conditions, List<URI> references) {
        public GroupDefinition { advantages = List.copyOf(advantages); tradeoffs = List.copyOf(tradeoffs); conditions = List.copyOf(conditions); references = List.copyOf(references); }
    }
    public static final List<ConditionId> COMMON = List.of(ConditionId.TENANT_AND_SUBJECT_CORRELATION,
            ConditionId.ATTRIBUTE_OWNERSHIP_AND_MAPPING, ConditionId.ACCOUNT_DISABLE_AND_LOGIN_BLOCK,
            ConditionId.APPLICATION_SESSION_INVALIDATION, ConditionId.TOKEN_REVOCATION_OR_BOUNDED_EXPIRY,
            ConditionId.FAILURE_RECOVERY_AND_RECONCILIATION);
    public static final List<ConditionId> GROUP_COMMON = List.of(ConditionId.GROUP_SOURCE_AND_MEMBERSHIP_MAPPING,
            ConditionId.GROUP_CHANGE_DELIVERY_AND_RECONCILIATION, ConditionId.GROUP_TO_ROLE_MAPPING_AND_ENFORCEMENT,
            ConditionId.GROUP_REMOVAL_AND_ACCESS_RECHECK);
    public static final List<GroupDefinition> GROUP_STRATEGIES = List.of(
            new GroupDefinition(GroupStrategy.UNKNOWN, "Group strategy not selected", List.of(),
                    List.of("An unknown strategy is not proof of either group synchronization or its absence."), List.of(), List.of()),
            new GroupDefinition(GroupStrategy.NONE, "No group synchronization", List.of("Avoids an additional group delivery integration."),
                    List.of("Cannot satisfy required group synchronization; application authorization still needs its own design."), List.of(), List.of()),
            new GroupDefinition(GroupStrategy.SCIM_GROUPS, "SCIM Group resources", List.of("Plans group and membership delivery through the selected SCIM transport."),
                    List.of("Requires a SCIM user-lifecycle pattern in this preview; a User-only SCIM interface is insufficient.",
                            "Group delivery does not define application roles, enforcement or revocation."),
                    append(GROUP_COMMON, ConditionId.SCIM_GROUP_OPERATIONS),
                    List.of(URI.create("https://www.rfc-editor.org/rfc/rfc7643.html#section-4.2"), URI.create("https://www.rfc-editor.org/rfc/rfc7644.html"))),
            new GroupDefinition(GroupStrategy.APPLICATION_BRIDGE, "Application-owned group synchronization bridge",
                    List.of("Separates application group delivery from the user provisioning mechanism."),
                    List.of("The application must implement and test authorized delivery, reconciliation and idempotency.",
                            "A bridge does not supply required SCIM user provisioning or prove vendor interoperability."),
                    append(GROUP_COMMON, ConditionId.APPLICATION_BRIDGE_AUTHORIZATION_AND_IDEMPOTENCY), List.of()));
    public static final List<ConditionDefinition> CONDITION_DEFINITIONS = List.of(
            new ConditionDefinition(ConditionId.TENANT_AND_SUBJECT_CORRELATION, "Define tenant boundaries and stable subject correlation; an email match alone is not proof of identity."),
            new ConditionDefinition(ConditionId.ATTRIBUTE_OWNERSHIP_AND_MAPPING, "Define which source owns each attribute and how changes map to the application."),
            new ConditionDefinition(ConditionId.ACCOUNT_DISABLE_AND_LOGIN_BLOCK, "Define delivery of account disablement and prevention of new application logins, including users who never log in again; JIT alone is not an offboarding channel."),
            new ConditionDefinition(ConditionId.APPLICATION_SESSION_INVALIDATION, "Define how existing application sessions lose access after offboarding; directory disablement or IdP logout alone does not prove application session invalidation."),
            new ConditionDefinition(ConditionId.TOKEN_REVOCATION_OR_BOUNDED_EXPIRY, "Define revocation or resource-server enforcement and the accepted residual-access window for access and refresh tokens; revoking a refresh token does not by itself prove immediate rejection of every issued access token."),
            new ConditionDefinition(ConditionId.FAILURE_RECOVERY_AND_RECONCILIATION, "Define recovery, retries, duplicate handling and reconciliation when updates fail or arrive late."),
            new ConditionDefinition(ConditionId.SCIM_CLIENT_SERVER_DIRECTION, "Identify the SCIM client, server, tenant boundary and authorized provisioning credentials in the intended direction."),
            new ConditionDefinition(ConditionId.SCIM_USER_OPERATIONS, "Define and test intended User create, update and disable or delete operations; a SCIM label does not prove this operation set."),
            new ConditionDefinition(ConditionId.JIT_TRUSTED_LOGIN_AND_LINKING, "Define trusted login-time account creation, attribute mapping and safe linking to an existing subject."),
            new ConditionDefinition(ConditionId.SCIM_JIT_COLLISION_POLICY, "Define ownership and collision handling when SCIM and login-time creation act on the same account."),
            new ConditionDefinition(ConditionId.GROUP_SOURCE_AND_MEMBERSHIP_MAPPING, "Define authoritative group sources and tenant-scoped group/member identifiers, including nested-group policy."),
            new ConditionDefinition(ConditionId.GROUP_CHANGE_DELIVERY_AND_RECONCILIATION, "Define membership-change delivery independent of a user's next login, reconciliation and handling of missing or reordered changes."),
            new ConditionDefinition(ConditionId.GROUP_TO_ROLE_MAPPING_AND_ENFORCEMENT, "Define application-owned group-to-role mapping and authorization enforcement; received group names are not access grants by themselves."),
            new ConditionDefinition(ConditionId.GROUP_REMOVAL_AND_ACCESS_RECHECK, "Define how membership removal or group deletion removes mapped grants and invalidates cached authorization for existing sessions and tokens."),
            new ConditionDefinition(ConditionId.SCIM_GROUP_OPERATIONS, "Define and test Group create, membership update, removal and deletion in the intended SCIM direction; User support does not establish Group support."),
            new ConditionDefinition(ConditionId.APPLICATION_BRIDGE_AUTHORIZATION_AND_IDEMPOTENCY, "Define an authenticated tenant-scoped bridge, least-privilege writers, duplicate handling and idempotent application of membership changes."));
    public static final List<URI> OFFBOARDING_REFERENCES = List.of(URI.create("https://www.rfc-editor.org/rfc/rfc7643.html#section-4.1.1"),
            URI.create("https://www.rfc-editor.org/rfc/rfc7009.html#section-3"));
    private ProvisioningLifecycleV2Evaluator() { }

    public record Analysis(PatternId patternId, GroupStrategy groupStrategy, ProvisioningRequirements requirements,
            Map<ConditionId, Declaration> declarations) {
        public Analysis {
            Objects.requireNonNull(patternId); Objects.requireNonNull(groupStrategy); Objects.requireNonNull(requirements);
            declarations = Map.copyOf(declarations);
            if (!conditions(patternId, groupStrategy).containsAll(declarations.keySet())) throw new IllegalArgumentException("Condition belongs to another provisioning design");
        }
        @JsonProperty public List<RequirementCheck> requirementChecks() {
            boolean scim = patternId != PatternId.JIT_LOGIN, jit = patternId != PatternId.SCIM_PUSH;
            return List.of(mechanism("provisioning.scim", requirements.scim(), scim),
                    mechanism("provisioning.justInTimeProvisioning", requirements.justInTimeProvisioning(), jit),
                    mechanism("provisioning.groupSynchronization", requirements.groupSynchronization(),
                            groupStrategy == GroupStrategy.UNKNOWN ? null : groupStrategy != GroupStrategy.NONE));
        }
        @JsonProperty public List<DesignCheck> designChecks() {
            Reason reason = switch (groupStrategy) {
                case UNKNOWN -> Reason.GROUP_STRATEGY_UNKNOWN;
                case NONE -> Reason.NO_GROUP_TRANSPORT_PLANNED;
                case APPLICATION_BRIDGE -> Reason.GROUP_TRANSPORT_PLANNED;
                case SCIM_GROUPS -> patternId == PatternId.JIT_LOGIN ? Reason.SCIM_GROUPS_REQUIRE_SCIM_PATTERN : Reason.GROUP_TRANSPORT_PLANNED;
            };
            return List.of(new DesignCheck("groupTransport", outcome(reason), reason));
        }
        @JsonProperty public List<ConditionCheck> conditionChecks() {
            return conditions(patternId, groupStrategy).stream().map(id -> {
                Reason reason = switch (declarations.getOrDefault(id, Declaration.UNKNOWN)) {
                    case SATISFIED -> Reason.DECLARED_CONDITION_SATISFIED;
                    case NOT_SATISFIED -> Reason.DECLARED_CONDITION_NOT_SATISFIED;
                    case UNKNOWN -> Reason.CONDITION_UNKNOWN;
                };
                return new ConditionCheck(id, outcome(reason), reason);
            }).toList();
        }
        @JsonProperty public Status status() {
            var outcomes = new ArrayList<Outcome>();
            requirementChecks().forEach(c -> outcomes.add(c.outcome())); designChecks().forEach(c -> outcomes.add(c.outcome()));
            conditionChecks().forEach(c -> outcomes.add(c.outcome()));
            if (outcomes.contains(Outcome.CONDITIONALLY_NOT_SATISFIED)) return Status.CONDITIONALLY_DOES_NOT_MATCH;
            return outcomes.contains(Outcome.UNKNOWN) ? Status.NEEDS_INFORMATION : Status.CONDITIONALLY_MATCHES;
        }
    }
    public static List<ConditionId> conditions(PatternId pattern, GroupStrategy groups) {
        Objects.requireNonNull(pattern); Objects.requireNonNull(groups);
        var result = new ArrayList<>(COMMON);
        if (pattern != PatternId.JIT_LOGIN) result.addAll(List.of(ConditionId.SCIM_CLIENT_SERVER_DIRECTION, ConditionId.SCIM_USER_OPERATIONS));
        if (pattern != PatternId.SCIM_PUSH) result.add(ConditionId.JIT_TRUSTED_LOGIN_AND_LINKING);
        if (pattern == PatternId.SCIM_AND_JIT) result.add(ConditionId.SCIM_JIT_COLLISION_POLICY);
        result.addAll(GROUP_STRATEGIES.stream().filter(d -> d.groupStrategy() == groups).findFirst().orElseThrow().conditions());
        return List.copyOf(result);
    }
    public static Analysis evaluate(ProvisioningRequirements requirements, PatternId pattern, GroupStrategy groups, Map<ConditionId, Declaration> declarations) {
        return new Analysis(pattern, groups, requirements, declarations);
    }
    private static RequirementCheck mechanism(String path, RequirementCriticality criticality, Boolean planned) {
        Reason reason = switch (criticality) {
            case REQUIRED -> planned == null ? Reason.GROUP_STRATEGY_UNKNOWN : planned ? Reason.REQUIRED_MECHANISM_PLANNED : Reason.REQUIRED_MECHANISM_ABSENT;
            case FORBIDDEN -> planned == null ? Reason.GROUP_STRATEGY_UNKNOWN : planned ? Reason.FORBIDDEN_MECHANISM_PLANNED : Reason.FORBIDDEN_MECHANISM_ABSENT;
            case UNKNOWN -> Reason.REQUIREMENT_UNKNOWN;
            case PREFERRED -> Reason.PREFERENCE_NOT_SCORED;
            case NOT_REQUIRED -> Reason.NO_REQUIREMENT;
        };
        return new RequirementCheck(path, criticality, outcome(reason), reason);
    }
    private static Outcome outcome(Reason reason) {
        return switch (reason) {
            case REQUIRED_MECHANISM_PLANNED, FORBIDDEN_MECHANISM_ABSENT, DECLARED_CONDITION_SATISFIED, GROUP_TRANSPORT_PLANNED -> Outcome.CONDITIONALLY_SATISFIED;
            case REQUIRED_MECHANISM_ABSENT, FORBIDDEN_MECHANISM_PLANNED, DECLARED_CONDITION_NOT_SATISFIED, SCIM_GROUPS_REQUIRE_SCIM_PATTERN -> Outcome.CONDITIONALLY_NOT_SATISFIED;
            case PREFERENCE_NOT_SCORED, NO_REQUIREMENT, NO_GROUP_TRANSPORT_PLANNED -> Outcome.NOT_APPLIED;
            case REQUIREMENT_UNKNOWN, GROUP_STRATEGY_UNKNOWN, CONDITION_UNKNOWN -> Outcome.UNKNOWN;
        };
    }
    private static List<ConditionId> append(List<ConditionId> values, ConditionId id) { var result = new ArrayList<>(values); result.add(id); return List.copyOf(result); }
}

package io.authweave.core.catalog.impact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.assessment.domain.profile.*;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.Declaration;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.Outcome;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.PatternId;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator.Status;
import io.authweave.core.evaluation.ProvisioningLifecycleV2Evaluator;
import io.authweave.core.evaluation.ProvisioningLifecycleV2Evaluator.*;

/** Explicit synthetic overlays only; neither owner defaults nor observed lifecycle evidence. */
@Component
public final class CatalogLifecycleRegressionCases {
    public static final String VERSION = "catalog-provisioning-lifecycle-scenarios-1";
    public static final String BASE_SHA256 = "ce70ce85cbb2e5b1537f36e2120a5c4add5ef0ce5d64be46397436bbc1993ca1";
    public static final int COUNT = 2016;
    public enum RequirementVariant { BASE_PROFILE, REQUIRED, FORBIDDEN, UNKNOWN, PREFERRED, NOT_REQUIRED }
    public enum DeclarationVariant { UNRECORDED, EXPLICIT_UNKNOWN, ALL_SATISFIED, ALL_NOT_SATISFIED,
        OFFBOARDING_GAPS, OFFBOARDING_FAILURE_WITH_GAP, GROUP_REMOVAL_GAP, GROUP_REMOVAL_FAILURE_WITH_GAP }
    public static final List<ConditionId> OFFBOARDING = List.of(ConditionId.ACCOUNT_DISABLE_AND_LOGIN_BLOCK,
            ConditionId.APPLICATION_SESSION_INVALIDATION, ConditionId.TOKEN_REVOCATION_OR_BOUNDED_EXPIRY);
    public record Definition(String scenarioId, RequirementVariant requirementVariant, PatternId patternId, GroupStrategy groupStrategy,
            DeclarationVariant declarationVariant, String sourceProfileSha256, String profileSha256,
            ProvisioningRequirements requirements, Map<ConditionId, Declaration> declarations) {
        public Definition {
            Objects.requireNonNull(requirementVariant); Objects.requireNonNull(patternId); Objects.requireNonNull(groupStrategy);
            Objects.requireNonNull(declarationVariant); Objects.requireNonNull(requirements); declarations = Map.copyOf(declarations);
            if (!CatalogScopedProfileCases.IDS.contains(scenarioId) || !hash(sourceProfileSha256) || !hash(profileSha256)
                    || !applies(declarationVariant, groupStrategy) || !scope(patternId, groupStrategy).containsAll(declarations.keySet()))
                throw new IllegalArgumentException("Invalid scoped lifecycle scenario");
        }
        public String key() { return scenarioId + "/" + requirementVariant + "/" + patternId + "/" + groupStrategy + "/" + declarationVariant; }
    }
    public record Expected(List<RequirementCheck> requirementChecks, List<DesignCheck> designChecks,
            List<ConditionCheck> conditionChecks, Status status) { }
    private final List<Definition> definitions;
    private final String sha256, auditabilityScenarioSetSha256;
    public CatalogLifecycleRegressionCases(CatalogScopedProfileCases base, CatalogAuditabilityRegressionCases auditability, ObjectMapper mapper) {
        if (!BASE_SHA256.equals(base.sha256()) || !BASE_SHA256.equals(CatalogDraftCanonicalizer.sha256(base.definitions()))
                || !BASE_SHA256.equals(auditability.baseScenarioSetSha256())) throw new IllegalStateException("Review frozen lifecycle sources");
        var rows = new ArrayList<Definition>();
        for (var source : base.definitions().stream().sorted(Comparator.comparing(CatalogScenarioCases.Definition::id)).toList()) {
            if (source.profileSchemaVersion() != 5) throw new IllegalStateException("Use unchanged frozen v5 sources");
            var supplement = auditability.definitions().stream().filter(d -> d.scenarioId().equals(source.id())).findFirst().orElseThrow();
            var v6 = (ObjectNode) source.profile();
            ((ObjectNode) v6.get("security")).set("auditabilityRequirements", mapper.valueToTree(supplement.requirements()));
            if (!supplement.profileSha256().equals(CatalogDraftCanonicalizer.sha256(v6))) throw new IllegalStateException("Review lossless v6 source binding");
            var original = mapper.treeToValue(v6, ApplicationIdentityProfile.class).provisioning();
            for (var variant : RequirementVariant.values()) {
                var requirements = variant == RequirementVariant.BASE_PROFILE ? original : all(RequirementCriticality.valueOf(variant.name()));
                var profile = v6.deepCopy(); profile.set("provisioning", mapper.valueToTree(requirements));
                var typed = mapper.treeToValue(profile, ApplicationIdentityProfile.class);
                if (!ApplicationIdentityProfileValidator.validate(typed).issues().isEmpty() || typed.minimumSchemaVersion() != 6)
                    throw new IllegalStateException("Invalid compiled lifecycle profile");
                var profileSha256 = CatalogDraftCanonicalizer.sha256(profile);
                for (var pattern : PatternId.values()) for (var group : GroupStrategy.values()) {
                    if (!scope(pattern, group).equals(ProvisioningLifecycleV2Evaluator.conditions(pattern, group)))
                        throw new IllegalStateException("Review explicit lifecycle condition inventory");
                    for (var design : DeclarationVariant.values()) if (applies(design, group)) rows.add(new Definition(source.id(), variant,
                            pattern, group, design, supplement.profileSha256(), profileSha256, requirements, declarations(pattern, group, design)));
                }
            }
        }
        definitions = List.copyOf(rows); auditabilityScenarioSetSha256 = auditability.sha256();
        if (definitions.size() != COUNT || definitions.stream().map(Definition::key).distinct().count() != COUNT)
            throw new IllegalStateException("Review complete bounded lifecycle matrix");
        sha256 = CatalogDraftCanonicalizer.sha256(List.of(VERSION, CatalogAuditabilityRegressionCases.VERSION,
                auditabilityScenarioSetSha256, CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_SHA256, BASE_SHA256, definitions));
    }
    public List<Definition> definitions() { return definitions; }
    public String sha256() { return sha256; }
    public String auditabilityScenarioSetSha256() { return auditabilityScenarioSetSha256; }
    public static ProvisioningRequirements all(RequirementCriticality value) { return new ProvisioningRequirements(value, value, value); }
    public static boolean applies(DeclarationVariant variant, GroupStrategy group) {
        return variant != DeclarationVariant.GROUP_REMOVAL_GAP && variant != DeclarationVariant.GROUP_REMOVAL_FAILURE_WITH_GAP
                || group == GroupStrategy.SCIM_GROUPS || group == GroupStrategy.APPLICATION_BRIDGE;
    }
    /** Independent scope table, not the production conditions function. */
    public static List<ConditionId> scope(PatternId pattern, GroupStrategy group) {
        var ids = new ArrayList<>(List.of(ConditionId.TENANT_AND_SUBJECT_CORRELATION, ConditionId.ATTRIBUTE_OWNERSHIP_AND_MAPPING,
                ConditionId.ACCOUNT_DISABLE_AND_LOGIN_BLOCK, ConditionId.APPLICATION_SESSION_INVALIDATION,
                ConditionId.TOKEN_REVOCATION_OR_BOUNDED_EXPIRY, ConditionId.FAILURE_RECOVERY_AND_RECONCILIATION));
        switch (pattern) {
            case SCIM_PUSH -> ids.addAll(List.of(ConditionId.SCIM_CLIENT_SERVER_DIRECTION, ConditionId.SCIM_USER_OPERATIONS));
            case JIT_LOGIN -> ids.add(ConditionId.JIT_TRUSTED_LOGIN_AND_LINKING);
            case SCIM_AND_JIT -> ids.addAll(List.of(ConditionId.SCIM_CLIENT_SERVER_DIRECTION, ConditionId.SCIM_USER_OPERATIONS,
                    ConditionId.JIT_TRUSTED_LOGIN_AND_LINKING, ConditionId.SCIM_JIT_COLLISION_POLICY));
        }
        if (group == GroupStrategy.SCIM_GROUPS || group == GroupStrategy.APPLICATION_BRIDGE) {
            ids.addAll(List.of(ConditionId.GROUP_SOURCE_AND_MEMBERSHIP_MAPPING, ConditionId.GROUP_CHANGE_DELIVERY_AND_RECONCILIATION,
                    ConditionId.GROUP_TO_ROLE_MAPPING_AND_ENFORCEMENT, ConditionId.GROUP_REMOVAL_AND_ACCESS_RECHECK));
            ids.add(group == GroupStrategy.SCIM_GROUPS ? ConditionId.SCIM_GROUP_OPERATIONS : ConditionId.APPLICATION_BRIDGE_AUTHORIZATION_AND_IDEMPOTENCY);
        }
        return List.copyOf(ids);
    }
    public static Map<ConditionId, Declaration> declarations(PatternId pattern, GroupStrategy group, DeclarationVariant variant) {
        if (!applies(variant, group)) throw new IllegalArgumentException("Group-removal case needs an explicit group transport");
        var values = new EnumMap<ConditionId, Declaration>(ConditionId.class);
        if (variant == DeclarationVariant.UNRECORDED) return Map.of();
        var value = variant == DeclarationVariant.EXPLICIT_UNKNOWN ? Declaration.UNKNOWN : variant == DeclarationVariant.ALL_NOT_SATISFIED ? Declaration.NOT_SATISFIED : Declaration.SATISFIED;
        scope(pattern, group).forEach(id -> values.put(id, value));
        if (variant == DeclarationVariant.OFFBOARDING_GAPS || variant == DeclarationVariant.OFFBOARDING_FAILURE_WITH_GAP) {
            values.remove(ConditionId.TOKEN_REVOCATION_OR_BOUNDED_EXPIRY);
            if (variant == DeclarationVariant.OFFBOARDING_GAPS) values.remove(ConditionId.APPLICATION_SESSION_INVALIDATION);
            else values.put(ConditionId.APPLICATION_SESSION_INVALIDATION, Declaration.NOT_SATISFIED);
        }
        if (variant == DeclarationVariant.GROUP_REMOVAL_GAP) values.remove(ConditionId.GROUP_REMOVAL_AND_ACCESS_RECHECK);
        if (variant == DeclarationVariant.GROUP_REMOVAL_FAILURE_WITH_GAP) {
            values.put(ConditionId.GROUP_REMOVAL_AND_ACCESS_RECHECK, Declaration.NOT_SATISFIED); values.remove(ConditionId.GROUP_TO_ROLE_MAPPING_AND_ENFORCEMENT);
        }
        return Map.copyOf(values);
    }
    /** Expected outcomes use explicit fixture policy, never evaluator output. */
    public static Expected expected(Definition d) {
        var requirements = d.requirements();
        var checks = List.of(mechanism("provisioning.scim", requirements.scim(), d.patternId() != PatternId.JIT_LOGIN),
                mechanism("provisioning.justInTimeProvisioning", requirements.justInTimeProvisioning(), d.patternId() != PatternId.SCIM_PUSH),
                mechanism("provisioning.groupSynchronization", requirements.groupSynchronization(), d.groupStrategy() == GroupStrategy.UNKNOWN ? null : d.groupStrategy() != GroupStrategy.NONE));
        var designReason = d.groupStrategy() == GroupStrategy.UNKNOWN ? Reason.GROUP_STRATEGY_UNKNOWN : d.groupStrategy() == GroupStrategy.NONE ? Reason.NO_GROUP_TRANSPORT_PLANNED
                : d.groupStrategy() == GroupStrategy.SCIM_GROUPS && d.patternId() == PatternId.JIT_LOGIN ? Reason.SCIM_GROUPS_REQUIRE_SCIM_PATTERN : Reason.GROUP_TRANSPORT_PLANNED;
        var design = List.of(new DesignCheck("groupTransport", outcome(designReason), designReason));
        var conditions = scope(d.patternId(), d.groupStrategy()).stream().map(id -> {
            var value = d.declarations().getOrDefault(id, Declaration.UNKNOWN);
            var reason = value == Declaration.SATISFIED ? Reason.DECLARED_CONDITION_SATISFIED : value == Declaration.NOT_SATISFIED ? Reason.DECLARED_CONDITION_NOT_SATISFIED : Reason.CONDITION_UNKNOWN;
            return new ConditionCheck(id, outcome(reason), reason);
        }).toList();
        var outcomes = new ArrayList<Outcome>(); checks.forEach(c -> outcomes.add(c.outcome())); design.forEach(c -> outcomes.add(c.outcome())); conditions.forEach(c -> outcomes.add(c.outcome()));
        var status = outcomes.contains(Outcome.CONDITIONALLY_NOT_SATISFIED) ? Status.CONDITIONALLY_DOES_NOT_MATCH : outcomes.contains(Outcome.UNKNOWN) ? Status.NEEDS_INFORMATION : Status.CONDITIONALLY_MATCHES;
        return new Expected(checks, design, conditions, status);
    }
    private static RequirementCheck mechanism(String path, RequirementCriticality criticality, Boolean planned) {
        Reason reason = switch (criticality) {
            case UNKNOWN -> Reason.REQUIREMENT_UNKNOWN; case PREFERRED -> Reason.PREFERENCE_NOT_SCORED; case NOT_REQUIRED -> Reason.NO_REQUIREMENT;
            case REQUIRED -> planned == null ? Reason.GROUP_STRATEGY_UNKNOWN : planned ? Reason.REQUIRED_MECHANISM_PLANNED : Reason.REQUIRED_MECHANISM_ABSENT;
            case FORBIDDEN -> planned == null ? Reason.GROUP_STRATEGY_UNKNOWN : planned ? Reason.FORBIDDEN_MECHANISM_PLANNED : Reason.FORBIDDEN_MECHANISM_ABSENT;
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
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
}

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
import io.authweave.core.assessment.domain.profile.OperationalConstraints.*;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.evaluation.OperationsPlanningPreflight;
import io.authweave.core.evaluation.UsagePlanningPreflight;
import static io.authweave.core.evaluation.OperationsPlanningEvaluator.*;

/** Bounded synthetic overlays only. Frozen profiles and v6 audit requirements are never replaced. */
@Component
public final class CatalogOperationsPlanningCases {
    public static final String VERSION = "catalog-operations-planning-scenarios-1";
    public static final String BASE_SHA256 = "ce70ce85cbb2e5b1537f36e2120a5c4add5ef0ce5d64be46397436bbc1993ca1";
    public static final int COUNT = 140;
    public enum PreferenceVariant { BASE_PROFILE, MANAGED_AZURE, SELF_HOSTED_ON_PREMISES, NO_PREFERENCE_AWS, EXPLICIT_UNKNOWN, MANAGED_GOOGLE_CLOUD, SELF_HOSTED_MULTI_CLOUD }
    public enum UsageVariant { UNRECORDED, OBSERVED_ZERO, ASSUMED_WITHOUT_ASSUMPTIONS, ASSUMED_WITH_ASSUMPTIONS, SPARSE_OBSERVED }
    public static final Map<PreferenceVariant, OperationsPlanningPreflight.Inputs> PREFERENCES = Map.of(
        PreferenceVariant.MANAGED_AZURE, inputs(HostingPreference.MANAGED, DeploymentTarget.AZURE, IdentityExpertise.LIMITED, BudgetSensitivity.HIGH),
        PreferenceVariant.SELF_HOSTED_ON_PREMISES, inputs(HostingPreference.SELF_HOSTED, DeploymentTarget.ON_PREMISES, IdentityExpertise.ADVANCED, BudgetSensitivity.HIGH),
        PreferenceVariant.NO_PREFERENCE_AWS, inputs(HostingPreference.NO_PREFERENCE, DeploymentTarget.AWS, IdentityExpertise.MODERATE, BudgetSensitivity.MODERATE),
        PreferenceVariant.EXPLICIT_UNKNOWN, inputs(HostingPreference.UNKNOWN, DeploymentTarget.UNDECIDED, IdentityExpertise.UNKNOWN, BudgetSensitivity.UNKNOWN),
        PreferenceVariant.MANAGED_GOOGLE_CLOUD, inputs(HostingPreference.MANAGED, DeploymentTarget.GOOGLE_CLOUD, IdentityExpertise.ADVANCED, BudgetSensitivity.LOW),
        PreferenceVariant.SELF_HOSTED_MULTI_CLOUD, inputs(HostingPreference.SELF_HOSTED, DeploymentTarget.MULTI_CLOUD, IdentityExpertise.MODERATE, BudgetSensitivity.LOW));
    public record Definition(String scenarioId, PreferenceVariant preferenceVariant, UsageVariant usageVariant,
            String sourceProfileSha256, String profileSha256, OperationsPlanningPreflight.Inputs inputs, UsagePlanning usagePlanning) {
        public Definition {
            Objects.requireNonNull(preferenceVariant); Objects.requireNonNull(usageVariant); Objects.requireNonNull(inputs); Objects.requireNonNull(usagePlanning);
            if (!CatalogScopedProfileCases.IDS.contains(scenarioId) || !hash(sourceProfileSha256) || !hash(profileSha256)) throw new IllegalArgumentException("Invalid operations scenario binding");
        }
        public String key() { return scenarioId + "/" + preferenceVariant + "/" + usageVariant; }
        public OperationalConstraints operations() { return new OperationalConstraints(inputs.hosting(), inputs.deploymentTarget(), inputs.identityExpertise(), inputs.budgetSensitivity(), usagePlanning); }
    }
    public record Expected(UsagePlanningPreflight.Status status, List<String> missingPaths, OperationsPlanningPreflight.UsageInputs usageInputs,
            List<Alignment> alignments, List<SupportPlanning> support, List<BudgetPlanning> budget) { }
    private final List<Definition> definitions;
    private final String sha256, auditabilityScenarioSetSha256;
    public CatalogOperationsPlanningCases(CatalogScopedProfileCases base, CatalogAuditabilityRegressionCases auditability, ObjectMapper mapper) {
        if (!BASE_SHA256.equals(base.sha256()) || !BASE_SHA256.equals(CatalogDraftCanonicalizer.sha256(base.definitions()))
                || !BASE_SHA256.equals(auditability.baseScenarioSetSha256())) throw new IllegalStateException("Review frozen operations scenario binding");
        var rows = new ArrayList<Definition>();
        for (var source : base.definitions().stream().sorted(Comparator.comparing(CatalogScenarioCases.Definition::id)).toList()) {
            if (source.profileSchemaVersion() != 5) throw new IllegalStateException("Use unchanged frozen v5 sources");
            var supplement = auditability.definitions().stream().filter(d -> d.scenarioId().equals(source.id())).findFirst().orElseThrow();
            var v6 = (ObjectNode) source.profile();
            ((ObjectNode) v6.get("security")).set("auditabilityRequirements", mapper.valueToTree(supplement.requirements()));
            if (!supplement.profileSha256().equals(CatalogDraftCanonicalizer.sha256(v6))) throw new IllegalStateException("Review lossless v6 operations source");
            var original = mapper.treeToValue(v6, ApplicationIdentityProfile.class).operations();
            for (var preference : PreferenceVariant.values()) for (var usage : UsageVariant.values()) {
                var selected = preference == PreferenceVariant.BASE_PROFILE ? inputs(original.hosting(), original.deploymentTarget(), original.identityExpertise(), original.budgetSensitivity()) : PREFERENCES.get(preference);
                var planning = planning(usage);
                var profile = v6.deepCopy();
                var operations = mapper.<ObjectNode>valueToTree(selected);
                operations.set("usagePlanning", mapper.valueToTree(planning));
                profile.set("operations", operations);
                var typed = mapper.treeToValue(profile, ApplicationIdentityProfile.class);
                if (!ApplicationIdentityProfileValidator.validate(typed).issues().isEmpty() || typed.minimumSchemaVersion() != 6)
                    throw new IllegalStateException("Invalid compiled v6 operations scenario");
                rows.add(new Definition(source.id(), preference, usage, supplement.profileSha256(), CatalogDraftCanonicalizer.sha256(profile), selected, planning));
            }
        }
        definitions = List.copyOf(rows); auditabilityScenarioSetSha256 = auditability.sha256();
        if (definitions.size() != COUNT || definitions.stream().map(Definition::key).distinct().count() != COUNT) throw new IllegalStateException("Review complete bounded operations matrix");
        sha256 = CatalogDraftCanonicalizer.sha256(List.of(VERSION, CatalogAuditabilityRegressionCases.VERSION, auditabilityScenarioSetSha256,
                CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_SHA256, BASE_SHA256, definitions));
    }
    public List<Definition> definitions() { return definitions; }
    public String sha256() { return sha256; }
    public String auditabilityScenarioSetSha256() { return auditabilityScenarioSetSha256; }
    private static OperationsPlanningPreflight.Inputs inputs(HostingPreference h, DeploymentTarget d, IdentityExpertise e, BudgetSensitivity b) { return new OperationsPlanningPreflight.Inputs(h, d, e, b); }
    public static UsagePlanning planning(UsageVariant variant) {
        if (variant == UsageVariant.UNRECORDED) return new UsagePlanning("", List.of(), Map.of());
        if (variant == UsageVariant.SPARSE_OBSERVED) return new UsagePlanning(" \t\n", List.of(), Map.of(UsagePlanning.Metric.MONTHLY_ACTIVE_USERS, new UsagePlanning.Quantity(UsagePlanning.Basis.OBSERVED, 0L)));
        var quantities = new EnumMap<UsagePlanning.Metric, UsagePlanning.Quantity>(UsagePlanning.Metric.class);
        for (var metric : UsagePlanning.Metric.values()) quantities.put(metric, new UsagePlanning.Quantity(
            variant == UsageVariant.OBSERVED_ZERO ? UsagePlanning.Basis.OBSERVED : UsagePlanning.Basis.ASSUMED,
            variant == UsageVariant.ASSUMED_WITH_ASSUMPTIONS ? 9007199254740991L : 0L));
        return new UsagePlanning("Synthetic planning month", variant == UsageVariant.ASSUMED_WITH_ASSUMPTIONS ? List.of("Synthetic forecast, not verified usage") : List.of(), quantities);
    }
    /** Independent expectation tables and input-presence policy, never evaluator output. */
    public static Expected expected(Definition definition) {
        var input = definition.inputs(); var planning = definition.usagePlanning();
        var metrics = List.of(UsagePlanning.Metric.values()).stream().filter(planning.volumes()::containsKey).toList();
        var usageMissing = new ArrayList<String>();
        if (planning.scopeDescription().isBlank()) usageMissing.add("operations.usagePlanning.scopeDescription");
        for (var metric : UsagePlanning.Metric.values()) if (!planning.volumes().containsKey(metric)) usageMissing.add("operations.usagePlanning.volumes." + metric);
        if (planning.assumptions().isEmpty() && planning.volumes().values().stream().anyMatch(q -> q.basis() == UsagePlanning.Basis.ASSUMED)) usageMissing.add("operations.usagePlanning.assumptions");
        var missing = new ArrayList<String>();
        if (input.hosting() == HostingPreference.UNKNOWN) missing.add("operations.hosting");
        if (input.deploymentTarget() == DeploymentTarget.UNDECIDED) missing.add("operations.deploymentTarget");
        if (input.identityExpertise() == IdentityExpertise.UNKNOWN) missing.add("operations.identityExpertise");
        if (input.budgetSensitivity() == BudgetSensitivity.UNKNOWN) missing.add("operations.budgetSensitivity");
        missing.addAll(usageMissing);
        var alignments = switch (input.hosting()) {
            case MANAGED -> List.of(Alignment.PREFERENCE_ALIGNED, Alignment.PREFERENCE_DIFFERS);
            case SELF_HOSTED -> List.of(Alignment.PREFERENCE_DIFFERS, Alignment.PREFERENCE_ALIGNED);
            case NO_PREFERENCE -> List.of(Alignment.NO_PREFERENCE, Alignment.NO_PREFERENCE);
            case UNKNOWN -> List.of(Alignment.PREFERENCE_UNKNOWN, Alignment.PREFERENCE_UNKNOWN);
        };
        var support = switch (input.identityExpertise()) {
            case LIMITED -> List.of(SupportPlanning.INTEGRATION_SUPPORT_PLAN_NEEDED, SupportPlanning.OPERATOR_SUPPORT_PLAN_NEEDED);
            case MODERATE, ADVANCED -> List.of(SupportPlanning.RESPONSIBILITY_PLAN_NEEDED, SupportPlanning.RESPONSIBILITY_PLAN_NEEDED);
            case UNKNOWN -> List.of(SupportPlanning.SUPPORT_CAPACITY_UNDEFINED, SupportPlanning.SUPPORT_CAPACITY_UNDEFINED);
        };
        var budget = input.budgetSensitivity() == BudgetSensitivity.UNKNOWN ? BudgetPlanning.BUDGET_SCOPE_UNDEFINED : BudgetPlanning.COST_MODEL_NEEDED;
        return new Expected(status(missing), List.copyOf(missing), new OperationsPlanningPreflight.UsageInputs(status(usageMissing), metrics, usageMissing), alignments, support, List.of(budget, budget));
    }
    private static UsagePlanningPreflight.Status status(List<String> missing) { return missing.isEmpty() ? UsagePlanningPreflight.Status.INPUTS_RECORDED : UsagePlanningPreflight.Status.NEEDS_INFORMATION; }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
}

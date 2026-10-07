package io.authweave.core.catalog.impact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.assessment.domain.profile.*;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.UserPopulation;
import io.authweave.core.assessment.domain.profile.SecurityRequirements.*;
import io.authweave.core.catalog.draft.CatalogDraftCanonicalizer;
import io.authweave.core.evaluation.ComplianceScopeCheck;
import static io.authweave.core.evaluation.AssuranceCompliancePlanningPreflight.*;

/** Bounded synthetic investigation inputs; no standards, source evidence or customer defaults. */
@Component
public final class CatalogAssuranceComplianceCases {
    public static final String VERSION = "catalog-assurance-compliance-scenarios-1";
    public static final String BASE_SHA256 = "ce70ce85cbb2e5b1537f36e2120a5c4add5ef0ce5d64be46397436bbc1993ca1";
    public static final int COUNT = 36;
    public enum Variant { BASE_PROFILE, UNRECORDED_SCOPE, HUMAN_BASELINE, HUMAN_ELEVATED,
        HUMAN_HIGH_UNRECORDED_CONTROLS, HUMAN_HIGH_FORBIDDEN_CONTROL, MACHINE_ONLY, MIXED_PARTIAL_SCOPE, HUMAN_NONE_IDENTIFIED }
    public record Definition(String scenarioId, Variant variant, String sourceProfileSha256, String profileSha256,
            Inputs inputs, @JsonIgnore ApplicationIdentityProfile profile) {
        public Definition {
            Objects.requireNonNull(variant); Objects.requireNonNull(inputs); Objects.requireNonNull(profile);
            if (!CatalogScopedProfileCases.IDS.contains(scenarioId) || !hash(sourceProfileSha256) || !hash(profileSha256)
                    || !inputs.equals(CatalogAssuranceComplianceCases.inputs(profile))) throw new IllegalArgumentException("Invalid assurance/compliance scenario binding");
        }
        public String key() { return scenarioId + "/" + variant; }
    }
    private final List<Definition> definitions;
    private final String sha256, auditabilityScenarioSetSha256;
    public CatalogAssuranceComplianceCases(CatalogScopedProfileCases base, CatalogAuditabilityRegressionCases auditability, ObjectMapper mapper) {
        if (!BASE_SHA256.equals(base.sha256()) || !BASE_SHA256.equals(CatalogDraftCanonicalizer.sha256(base.definitions()))
                || !BASE_SHA256.equals(auditability.baseScenarioSetSha256())) throw new IllegalStateException("Review frozen assurance/compliance sources");
        var rows = new ArrayList<Definition>();
        for (var source : base.definitions().stream().sorted(Comparator.comparing(CatalogScenarioCases.Definition::id)).toList()) {
            if (source.profileSchemaVersion() != 5) throw new IllegalStateException("Use unchanged frozen v5 sources");
            var supplement = auditability.definitions().stream().filter(d -> d.scenarioId().equals(source.id())).findFirst().orElseThrow();
            var v6 = (ObjectNode) source.profile();
            ((ObjectNode) v6.get("security")).set("auditabilityRequirements", mapper.valueToTree(supplement.requirements()));
            if (!supplement.profileSha256().equals(CatalogDraftCanonicalizer.sha256(v6))) throw new IllegalStateException("Review lossless v6 assurance source");
            var original = mapper.treeToValue(v6, ApplicationIdentityProfile.class);
            for (var variant : Variant.values()) {
                var selected = variant == Variant.BASE_PROFILE ? inputs(original) : overlay(variant);
                var profile = v6.deepCopy();
                if (variant != Variant.BASE_PROFILE) {
                    ((ObjectNode) profile.get("application")).set("clients", mapper.valueToTree(selected.clients()));
                    ((ObjectNode) profile.get("audience")).set("populations", mapper.valueToTree(selected.populations()));
                    var security = (ObjectNode) profile.get("security");
                    security.set("assurance", mapper.valueToTree(selected.assuranceExpectation()));
                    security.set("multiFactorAuthentication", mapper.valueToTree(selected.controls().multiFactorAuthentication()));
                    security.set("authenticationControls", mapper.valueToTree(new AuthenticationControls(selected.controls().phishingResistance(), selected.controls().nonExportableKeys(), selected.controls().stepUpAuthentication())));
                    security.set("complianceScopeStatus", mapper.valueToTree(selected.complianceScopeStatus()));
                    security.set("complianceTargets", mapper.valueToTree(selected.complianceTargets()));
                }
                var typed = mapper.treeToValue(profile, ApplicationIdentityProfile.class);
                // Missing scope is an intentional case; contradictions and unrelated validation drift are not.
                var issues = ApplicationIdentityProfileValidator.validate(typed).issues();
                if (typed.minimumSchemaVersion() != 6 || issues.stream().anyMatch(i -> i.type() == ProfileIssueType.CONTRADICTION)
                        || (variant != Variant.UNRECORDED_SCOPE && !issues.isEmpty())) throw new IllegalStateException("Invalid compiled assurance/compliance scenario");
                rows.add(new Definition(source.id(), variant, supplement.profileSha256(), CatalogDraftCanonicalizer.sha256(profile), selected, typed));
            }
        }
        definitions = List.copyOf(rows); auditabilityScenarioSetSha256 = auditability.sha256();
        if (definitions.size() != COUNT || definitions.stream().map(Definition::key).distinct().count() != COUNT) throw new IllegalStateException("Review complete bounded assurance matrix");
        sha256 = CatalogDraftCanonicalizer.sha256(List.of(VERSION, CatalogAuditabilityRegressionCases.VERSION, auditabilityScenarioSetSha256,
                CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_SHA256, BASE_SHA256, definitions));
    }
    public List<Definition> definitions() { return definitions; }
    public String sha256() { return sha256; }
    public String auditabilityScenarioSetSha256() { return auditabilityScenarioSetSha256; }
    private static Inputs inputs(ApplicationIdentityProfile profile) {
        var s = profile.security(); var c = s.authenticationControls();
        return new Inputs(ordered(profile.application().clients()), ordered(profile.audience().populations()), s.assurance(),
                new Controls(s.multiFactorAuthentication(), c.phishingResistance(), c.nonExportableKeys(), c.stepUpAuthentication()), s.complianceScopeStatus(), ordered(s.complianceTargets()));
    }
    private static <E extends Enum<E>> List<E> ordered(java.util.Set<E> values) { return values.stream().sorted(Comparator.comparing(Enum::name)).toList(); }
    private static Controls controls(RequirementCriticality value) { return new Controls(value, value, value, value); }
    public static Inputs overlay(Variant variant) {
        var clients = List.of(ClientType.BROWSER); var users = List.of(UserPopulation.EMPLOYEES);
        return switch (variant) {
            case UNRECORDED_SCOPE -> new Inputs(List.of(), List.of(), AssuranceLevel.UNKNOWN, controls(RequirementCriticality.UNKNOWN), ComplianceScopeStatus.UNKNOWN, List.of());
            case HUMAN_BASELINE -> new Inputs(clients, users, AssuranceLevel.BASELINE, controls(RequirementCriticality.NOT_REQUIRED), ComplianceScopeStatus.TARGETS_IDENTIFIED, List.of(ComplianceTarget.GDPR));
            case HUMAN_ELEVATED -> new Inputs(clients, users, AssuranceLevel.ELEVATED, controls(RequirementCriticality.PREFERRED), ComplianceScopeStatus.TARGETS_IDENTIFIED, ordered(java.util.Set.of(ComplianceTarget.values())));
            case HUMAN_HIGH_UNRECORDED_CONTROLS -> new Inputs(clients, users, AssuranceLevel.HIGH, controls(RequirementCriticality.UNKNOWN), ComplianceScopeStatus.TARGETS_IDENTIFIED, List.of(ComplianceTarget.OTHER));
            case HUMAN_HIGH_FORBIDDEN_CONTROL -> new Inputs(clients, users, AssuranceLevel.HIGH,
                    new Controls(RequirementCriticality.REQUIRED, RequirementCriticality.FORBIDDEN, RequirementCriticality.NOT_REQUIRED, RequirementCriticality.PREFERRED), ComplianceScopeStatus.TARGETS_IDENTIFIED, List.of(ComplianceTarget.SOC_2));
            case MACHINE_ONLY -> new Inputs(List.of(ClientType.MACHINE_TO_MACHINE), List.of(), AssuranceLevel.ELEVATED, controls(RequirementCriticality.REQUIRED), ComplianceScopeStatus.NONE_IDENTIFIED, List.of());
            case MIXED_PARTIAL_SCOPE -> new Inputs(List.of(ClientType.BROWSER, ClientType.MACHINE_TO_MACHINE), List.of(UserPopulation.CONTRACTORS), AssuranceLevel.HIGH,
                    new Controls(RequirementCriticality.PREFERRED, RequirementCriticality.REQUIRED, RequirementCriticality.NOT_REQUIRED, RequirementCriticality.UNKNOWN), ComplianceScopeStatus.UNKNOWN, List.of(ComplianceTarget.OTHER, ComplianceTarget.SOC_2));
            case HUMAN_NONE_IDENTIFIED -> new Inputs(clients, users, AssuranceLevel.HIGH, controls(RequirementCriticality.NOT_REQUIRED), ComplianceScopeStatus.NONE_IDENTIFIED, List.of());
            case BASE_PROFILE -> throw new IllegalArgumentException("Base requirements must come from the frozen profile");
        };
    }
    // Independent questions and decision table, not production evaluator output.
    public static final List<String> QUESTIONS = List.of(
        "Define the assurance objective, covered identities and evaluation criteria; BASELINE, ELEVATED and HIGH have no automatic standards mapping.",
        "Confirm the human clients and populations in scope; declared scope does not verify their deployed flows.",
        "Clarify MFA and each independent control, then investigate scoped enforcement and weaker fallback paths; labels do not set these requirements.",
        "Investigate enrollment, account recovery and authenticator replacement for the selected human flows.",
        "Investigate session handling, reauthentication and sensitive-action wiring for the selected human flows.",
        "Investigate workload credential issuance, storage, rotation and revocation when machine clients are in scope.",
        "Identify federation parties, trust boundaries and application validation responsibilities; no deployed protocol behavior is checked here.");
    public record Expected(HumanScope humanScope, List<AssuranceItem> assuranceItems, List<ComplianceItem> complianceItems, ComplianceScopeCheck complianceScopeCheck) { }
    public static Expected expected(Definition definition) {
        var input = definition.inputs(); boolean machine = input.clients().equals(List.of(ClientType.MACHINE_TO_MACHINE));
        var human = machine ? HumanScope.MACHINE_ONLY : input.clients().isEmpty() || input.populations().isEmpty() ? HumanScope.SCOPE_UNRESOLVED : HumanScope.HUMAN_SCOPE_RECORDED;
        boolean unclear = List.of(input.controls().multiFactorAuthentication(), input.controls().phishingResistance(), input.controls().nonExportableKeys(), input.controls().stepUpAuthentication()).stream()
                .anyMatch(c -> c == RequirementCriticality.UNKNOWN || c == RequirementCriticality.FORBIDDEN);
        var statuses = List.of(ItemStatus.INPUT_CLARIFICATION_NEEDED,
                machine ? ItemStatus.NOT_APPLIED : human == HumanScope.SCOPE_UNRESOLVED ? ItemStatus.INPUT_CLARIFICATION_NEEDED : ItemStatus.EVIDENCE_NEEDED,
                machine ? ItemStatus.NOT_APPLIED : unclear ? ItemStatus.INPUT_CLARIFICATION_NEEDED : ItemStatus.EVIDENCE_NEEDED,
                machine ? ItemStatus.NOT_APPLIED : ItemStatus.EVIDENCE_NEEDED, machine ? ItemStatus.NOT_APPLIED : ItemStatus.EVIDENCE_NEEDED,
                input.clients().contains(ClientType.MACHINE_TO_MACHINE) ? ItemStatus.EVIDENCE_NEEDED : input.clients().isEmpty() ? ItemStatus.INPUT_CLARIFICATION_NEEDED : ItemStatus.NOT_APPLIED,
                ItemStatus.EVIDENCE_NEEDED);
        var reasons = List.of(input.assuranceExpectation() == AssuranceLevel.UNKNOWN ? Reason.EXPECTATION_UNRECORDED : Reason.LABEL_NEEDS_DEFINITION,
                machine ? Reason.HUMAN_FLOW_NOT_SELECTED : human == HumanScope.SCOPE_UNRESOLVED ? Reason.HUMAN_SCOPE_UNRESOLVED : Reason.DECLARED_SCOPE_NOT_VERIFIED,
                machine ? Reason.HUMAN_FLOW_NOT_SELECTED : unclear ? Reason.CONTROL_INTENT_UNRESOLVED : Reason.CONTROLS_NOT_VERIFIED,
                machine ? Reason.HUMAN_FLOW_NOT_SELECTED : Reason.FLOW_EVIDENCE_NOT_EVALUATED, machine ? Reason.HUMAN_FLOW_NOT_SELECTED : Reason.FLOW_EVIDENCE_NOT_EVALUATED,
                input.clients().contains(ClientType.MACHINE_TO_MACHINE) ? Reason.FLOW_EVIDENCE_NOT_EVALUATED : input.clients().isEmpty() ? Reason.CLIENT_SCOPE_UNRESOLVED : Reason.WORKLOAD_FLOW_NOT_SELECTED,
                Reason.FLOW_EVIDENCE_NOT_EVALUATED);
        var items = java.util.stream.IntStream.range(0, 7).mapToObj(i -> new AssuranceItem(ItemId.values()[i], statuses.get(i), reasons.get(i), QUESTIONS.get(i))).toList();
        var targets = input.complianceTargets().stream().map(t -> new ComplianceItem(t,
                input.complianceScopeStatus() != ComplianceScopeStatus.TARGETS_IDENTIFIED || t == ComplianceTarget.OTHER ? ItemStatus.INPUT_CLARIFICATION_NEEDED : ItemStatus.EVIDENCE_NEEDED,
                input.complianceScopeStatus() != ComplianceScopeStatus.TARGETS_IDENTIFIED ? Reason.TARGET_SCOPE_NOT_ESTABLISHED : t == ComplianceTarget.OTHER ? Reason.OTHER_TARGET_NEEDS_DEFINITION : Reason.TARGET_EVIDENCE_NOT_EVALUATED)).toList();
        var reason = switch (input.complianceScopeStatus()) {
            case UNKNOWN -> ComplianceScopeCheck.Reason.COMPLIANCE_SCOPE_UNKNOWN;
            case NONE_IDENTIFIED -> ComplianceScopeCheck.Reason.NO_COMPLIANCE_TARGETS_IDENTIFIED;
            case TARGETS_IDENTIFIED -> ComplianceScopeCheck.Reason.COMPLIANCE_TARGETS_NOT_EVALUATED;
        };
        var explanations = Map.of(ComplianceScopeCheck.Reason.COMPLIANCE_SCOPE_UNKNOWN, "The requirements scope has not been recorded. Existing target labels are preserved but do not establish scope or compliance. Clarify the requirements with the assessment owner.",
                ComplianceScopeCheck.Reason.NO_COMPLIANCE_TARGETS_IDENTIFIED, "The owner recorded no identified compliance requirements for this assessment. No target check is applied; this is not a finding of legal exemption or compliance.",
                ComplianceScopeCheck.Reason.COMPLIANCE_TARGETS_NOT_EVALUATED, "Target labels are recorded, but applicable obligations, product/service scope and supporting evidence have not been evaluated. OTHER needs a concrete definition. No candidate is verified or rejected from labels alone.");
        return new Expected(human, items, targets, new ComplianceScopeCheck("security.complianceScopeStatus", input.complianceScopeStatus(), input.complianceTargets(),
                input.complianceScopeStatus() == ComplianceScopeStatus.NONE_IDENTIFIED ? io.authweave.core.evaluation.CapabilityPreflight.Outcome.NOT_APPLIED : io.authweave.core.evaluation.CapabilityPreflight.Outcome.UNKNOWN, reason, explanations.get(reason), false));
    }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
}

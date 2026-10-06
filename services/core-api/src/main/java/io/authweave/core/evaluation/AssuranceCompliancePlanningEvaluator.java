package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType;
import io.authweave.core.assessment.domain.profile.ComplianceScopeStatus;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.assessment.domain.profile.SecurityRequirements.AssuranceLevel;
import io.authweave.core.assessment.domain.profile.SecurityRequirements.ComplianceTarget;
import static io.authweave.core.evaluation.AssuranceCompliancePlanningPreflight.*;

/** Generic investigation prompts only; no mapping of labels to standards or provider evidence. */
public final class AssuranceCompliancePlanningEvaluator {
    public static final String POLICY_VERSION = "assurance-compliance-planning-1";
    public static final List<String> CHECKED_PATHS = List.of("application.clients", "audience.populations", "security.assurance",
            "security.multiFactorAuthentication", "security.authenticationControls.phishingResistance",
            "security.authenticationControls.nonExportableKeys", "security.authenticationControls.stepUpAuthentication",
            "security.complianceScopeStatus", "security.complianceTargets");
    public static final List<String> COMPLIANCE_QUESTIONS = List.of(
            "Define the concrete obligation or criterion, its applicability basis and the responsible reviewer; a target label is not a determination.",
            "Identify the exact application and product/service scope, plan, region, version, responsibility split and exclusions to investigate.",
            "Collect dated, scoped source material with its review status and limitations, and separately investigate deployed controls; no evidence is verified here.");
    public static final List<String> DEFERRED_BOUNDARIES = List.of("Formal assurance framework and level mapping",
            "Observed authentication, enrollment, recovery, session and workload flows", "Exact provider and service evidence verification",
            "Legal applicability and target-specific compliance assessment", "Candidate-change coverage and publication authority");
    private AssuranceCompliancePlanningEvaluator() { }

    public static AssuranceCompliancePlanningPreflight evaluate(UUID workspaceId, UUID assessmentId, long version,
            ApplicationIdentityProfile profile, Instant at) {
        var s = profile.security(); var c = s.authenticationControls();
        var inputs = new Inputs(profile.application().clients().stream().sorted(Comparator.comparing(Enum::name)).toList(),
                profile.audience().populations().stream().sorted(Comparator.comparing(Enum::name)).toList(), s.assurance(),
                new Controls(s.multiFactorAuthentication(), c.phishingResistance(), c.nonExportableKeys(), c.stepUpAuthentication()),
                s.complianceScopeStatus(), s.complianceTargets().stream().sorted(Comparator.comparing(Enum::name)).toList());
        return new AssuranceCompliancePlanningPreflight(workspaceId, assessmentId, version, at, inputs,
                ComplianceScopeEvaluator.evaluate(s), assuranceItems(inputs), complianceItems(inputs));
    }

    public static HumanScope humanScope(Inputs input) {
        if (input.clients().equals(List.of(ClientType.MACHINE_TO_MACHINE))) return HumanScope.MACHINE_ONLY;
        return !input.clients().isEmpty() && !input.populations().isEmpty() ? HumanScope.HUMAN_SCOPE_RECORDED : HumanScope.SCOPE_UNRESOLVED;
    }

    public static List<AssuranceItem> assuranceItems(Inputs input) {
        boolean machineOnly = humanScope(input) == HumanScope.MACHINE_ONLY;
        boolean controlsUnclear = List.of(input.controls().multiFactorAuthentication(), input.controls().phishingResistance(),
                input.controls().nonExportableKeys(), input.controls().stepUpAuthentication()).stream()
                .anyMatch(c -> c == RequirementCriticality.UNKNOWN || c == RequirementCriticality.FORBIDDEN);
        return List.of(ItemId.values()).stream().map(id -> {
            ItemStatus status; Reason reason;
            switch (id) {
                case ASSURANCE_OBJECTIVE -> {
                    status = ItemStatus.INPUT_CLARIFICATION_NEEDED;
                    reason = input.assuranceExpectation() == AssuranceLevel.UNKNOWN ? Reason.EXPECTATION_UNRECORDED : Reason.LABEL_NEEDS_DEFINITION;
                }
                case HUMAN_AUTHENTICATION_SCOPE -> {
                    status = machineOnly ? ItemStatus.NOT_APPLIED : humanScope(input) == HumanScope.SCOPE_UNRESOLVED
                            ? ItemStatus.INPUT_CLARIFICATION_NEEDED : ItemStatus.EVIDENCE_NEEDED;
                    reason = machineOnly ? Reason.HUMAN_FLOW_NOT_SELECTED : humanScope(input) == HumanScope.SCOPE_UNRESOLVED
                            ? Reason.HUMAN_SCOPE_UNRESOLVED : Reason.DECLARED_SCOPE_NOT_VERIFIED;
                }
                case AUTHENTICATION_CONTROLS -> {
                    status = machineOnly ? ItemStatus.NOT_APPLIED : controlsUnclear ? ItemStatus.INPUT_CLARIFICATION_NEEDED : ItemStatus.EVIDENCE_NEEDED;
                    reason = machineOnly ? Reason.HUMAN_FLOW_NOT_SELECTED : controlsUnclear ? Reason.CONTROL_INTENT_UNRESOLVED : Reason.CONTROLS_NOT_VERIFIED;
                }
                case ENROLLMENT_AND_RECOVERY, SESSIONS_AND_REAUTHENTICATION -> {
                    status = machineOnly ? ItemStatus.NOT_APPLIED : ItemStatus.EVIDENCE_NEEDED;
                    reason = machineOnly ? Reason.HUMAN_FLOW_NOT_SELECTED : Reason.FLOW_EVIDENCE_NOT_EVALUATED;
                }
                case WORKLOAD_IDENTITY -> {
                    status = input.clients().contains(ClientType.MACHINE_TO_MACHINE) ? ItemStatus.EVIDENCE_NEEDED
                            : input.clients().isEmpty() ? ItemStatus.INPUT_CLARIFICATION_NEEDED : ItemStatus.NOT_APPLIED;
                    reason = input.clients().contains(ClientType.MACHINE_TO_MACHINE) ? Reason.FLOW_EVIDENCE_NOT_EVALUATED
                            : input.clients().isEmpty() ? Reason.CLIENT_SCOPE_UNRESOLVED : Reason.WORKLOAD_FLOW_NOT_SELECTED;
                }
                case FEDERATION_AND_TRUST_BOUNDARIES -> { status = ItemStatus.EVIDENCE_NEEDED; reason = Reason.FLOW_EVIDENCE_NOT_EVALUATED; }
                default -> throw new IllegalStateException("Unknown investigation item");
            }
            return new AssuranceItem(id, status, reason, id.question());
        }).toList();
    }

    public static List<ComplianceItem> complianceItems(Inputs input) {
        return input.complianceTargets().stream().map(target -> {
            boolean scopeEstablished = input.complianceScopeStatus() == ComplianceScopeStatus.TARGETS_IDENTIFIED;
            return new ComplianceItem(target, !scopeEstablished || target == ComplianceTarget.OTHER ? ItemStatus.INPUT_CLARIFICATION_NEEDED : ItemStatus.EVIDENCE_NEEDED,
                    !scopeEstablished ? Reason.TARGET_SCOPE_NOT_ESTABLISHED : target == ComplianceTarget.OTHER ? Reason.OTHER_TARGET_NEEDS_DEFINITION : Reason.TARGET_EVIDENCE_NOT_EVALUATED);
        }).toList();
    }
}

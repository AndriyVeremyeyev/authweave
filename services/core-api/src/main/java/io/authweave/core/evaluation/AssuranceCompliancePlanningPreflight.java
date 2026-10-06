package io.authweave.core.evaluation;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType;
import io.authweave.core.assessment.domain.profile.AudienceRequirements.UserPopulation;
import io.authweave.core.assessment.domain.profile.ComplianceScopeStatus;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.assessment.domain.profile.SecurityRequirements.AssuranceLevel;
import io.authweave.core.assessment.domain.profile.SecurityRequirements.ComplianceTarget;

/** Output-only, version-bound investigation inventory; no evidence or compliance decision is stored. */
public record AssuranceCompliancePlanningPreflight(UUID workspaceId, UUID assessmentId, long assessmentVersion,
        Instant evaluatedAt, Inputs inputs, ComplianceScopeCheck complianceScopeCheck,
        List<AssuranceItem> assuranceItems, List<ComplianceItem> complianceItems) {
    public enum HumanScope { MACHINE_ONLY, HUMAN_SCOPE_RECORDED, SCOPE_UNRESOLVED }
    public enum ItemStatus { INPUT_CLARIFICATION_NEEDED, EVIDENCE_NEEDED, NOT_APPLIED }
    public enum Reason { EXPECTATION_UNRECORDED, LABEL_NEEDS_DEFINITION, HUMAN_FLOW_NOT_SELECTED, HUMAN_SCOPE_UNRESOLVED,
        DECLARED_SCOPE_NOT_VERIFIED, CONTROL_INTENT_UNRESOLVED, CONTROLS_NOT_VERIFIED, FLOW_EVIDENCE_NOT_EVALUATED,
        CLIENT_SCOPE_UNRESOLVED, WORKLOAD_FLOW_NOT_SELECTED, TARGET_SCOPE_NOT_ESTABLISHED, OTHER_TARGET_NEEDS_DEFINITION, TARGET_EVIDENCE_NOT_EVALUATED }
    public enum ItemId {
        ASSURANCE_OBJECTIVE("Define the assurance objective, covered identities and evaluation criteria; BASELINE, ELEVATED and HIGH have no automatic standards mapping."),
        HUMAN_AUTHENTICATION_SCOPE("Confirm the human clients and populations in scope; declared scope does not verify their deployed flows."),
        AUTHENTICATION_CONTROLS("Clarify MFA and each independent control, then investigate scoped enforcement and weaker fallback paths; labels do not set these requirements."),
        ENROLLMENT_AND_RECOVERY("Investigate enrollment, account recovery and authenticator replacement for the selected human flows."),
        SESSIONS_AND_REAUTHENTICATION("Investigate session handling, reauthentication and sensitive-action wiring for the selected human flows."),
        WORKLOAD_IDENTITY("Investigate workload credential issuance, storage, rotation and revocation when machine clients are in scope."),
        FEDERATION_AND_TRUST_BOUNDARIES("Identify federation parties, trust boundaries and application validation responsibilities; no deployed protocol behavior is checked here.");
        private final String question;
        ItemId(String question) { this.question = question; }
        public String question() { return question; }
    }
    public record Controls(RequirementCriticality multiFactorAuthentication, RequirementCriticality phishingResistance,
            RequirementCriticality nonExportableKeys, RequirementCriticality stepUpAuthentication) {
        public Controls { Objects.requireNonNull(multiFactorAuthentication); Objects.requireNonNull(phishingResistance); Objects.requireNonNull(nonExportableKeys); Objects.requireNonNull(stepUpAuthentication); }
    }
    public record Inputs(List<ClientType> clients, List<UserPopulation> populations, AssuranceLevel assuranceExpectation,
            Controls controls, ComplianceScopeStatus complianceScopeStatus, List<ComplianceTarget> complianceTargets) {
        public Inputs {
            clients = ordered(clients); populations = ordered(populations); complianceTargets = ordered(complianceTargets);
            Objects.requireNonNull(assuranceExpectation); Objects.requireNonNull(controls); Objects.requireNonNull(complianceScopeStatus);
        }
    }
    public record AssuranceItem(ItemId itemId, ItemStatus status, Reason reasonCode, String question) { }
    public record ComplianceItem(ComplianceTarget target, ItemStatus status, Reason reasonCode) { }
    public AssuranceCompliancePlanningPreflight {
        Objects.requireNonNull(workspaceId); Objects.requireNonNull(assessmentId); Objects.requireNonNull(evaluatedAt); Objects.requireNonNull(inputs); Objects.requireNonNull(complianceScopeCheck);
        assuranceItems = List.copyOf(assuranceItems); complianceItems = List.copyOf(complianceItems);
        // Scope explanations come from the existing scope evaluator, never a second interpretation.
        var security = new io.authweave.core.assessment.domain.profile.SecurityRequirements(RequirementCriticality.UNKNOWN,
                RequirementCriticality.UNKNOWN, RequirementCriticality.UNKNOWN, RequirementCriticality.UNKNOWN,
                inputs.assuranceExpectation(), java.util.Set.copyOf(inputs.complianceTargets()), null, null, inputs.complianceScopeStatus());
        if (assessmentVersion < 0 || assessmentVersion > 9007199254740991L
                || !complianceScopeCheck.equals(ComplianceScopeEvaluator.evaluate(security))
                || !assuranceItems.equals(AssuranceCompliancePlanningEvaluator.assuranceItems(inputs))
                || !complianceItems.equals(AssuranceCompliancePlanningEvaluator.complianceItems(inputs)))
            throw new IllegalArgumentException("Unbound assurance/compliance planning inventory");
    }
    private static <E extends Enum<E>> List<E> ordered(List<E> values) {
        var copy = List.copyOf(values);
        if (!copy.equals(copy.stream().distinct().sorted(Comparator.comparing(Enum::name)).toList()))
            throw new IllegalArgumentException("Planning inputs must be unique and ordered");
        return copy;
    }
    @JsonProperty public String policyVersion() { return AssuranceCompliancePlanningEvaluator.POLICY_VERSION; }
    @JsonProperty public String scope() { return "UNVERIFIED_ASSURANCE_COMPLIANCE_PLANNING"; }
    @JsonProperty public String analysisBasis() { return "SAVED_INPUTS_AND_GENERIC_INVESTIGATION_PROMPTS"; }
    @JsonProperty public String status() { return "NEEDS_INFORMATION"; }
    @JsonProperty public HumanScope humanScope() { return AssuranceCompliancePlanningEvaluator.humanScope(inputs); }
    @JsonProperty public List<String> checkedPaths() { return AssuranceCompliancePlanningEvaluator.CHECKED_PATHS; }
    @JsonProperty public List<String> complianceQuestions() { return AssuranceCompliancePlanningEvaluator.COMPLIANCE_QUESTIONS; }
    @JsonProperty public List<String> deferredBoundaries() { return AssuranceCompliancePlanningEvaluator.DEFERRED_BOUNDARIES; }
    @JsonProperty public boolean assuranceVerified() { return false; }
    @JsonProperty public boolean complianceVerified() { return false; }
    @JsonProperty public boolean legalApplicabilityDetermined() { return false; }
    @JsonProperty public boolean providerEligibilityEvaluated() { return false; }
    @JsonProperty public boolean configurationVerified() { return false; }
    @JsonProperty public boolean recommendationReady() { return false; }
    @JsonProperty public boolean publicationReady() { return false; }
    @JsonProperty public boolean writesPerformed() { return false; }
}

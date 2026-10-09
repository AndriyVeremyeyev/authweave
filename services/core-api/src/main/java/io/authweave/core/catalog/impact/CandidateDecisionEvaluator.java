package io.authweave.core.catalog.impact;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.catalog.ProviderCatalog.Availability;
import io.authweave.core.catalog.ProviderCatalog.Capability;
import io.authweave.core.catalog.draft.ProviderCatalogDraft;
import io.authweave.core.evaluation.ArchitecturePatternEvaluator;
import io.authweave.core.evaluation.ArchitecturePatternPreflight.Pattern;
import io.authweave.core.evaluation.ArchitecturePatternPreflight.PatternId;
import io.authweave.core.evaluation.ArchitecturePrerequisiteEvaluator;
import io.authweave.core.evaluation.ProvisioningLifecycleEvaluator;
import io.authweave.core.evaluation.ProvisioningLifecycleV2Evaluator;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import static io.authweave.core.assessment.domain.profile.RequirementCriticality.*;
import static io.authweave.core.catalog.impact.CandidateHardConstraintEvaluator.*;

/** Composes actual candidate checks, scores and conditional design advice on one exact snapshot.
 * Assertions remain unauthenticated calculation hypotheses. This is NOT the reserved final-result
 * contract, a saved assessment, an observed configuration, an approval or a publication endpoint. */
public final class CandidateDecisionEvaluator {
    public static final String VERSION = "decision-candidate-composition-2";
    public static final String ARCHITECTURE_VERSION = "decision-conditional-architecture-1";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private CandidateDecisionEvaluator() { }

    public enum Disposition { RECOMMENDED, ALTERNATIVE, UNRESOLVED, NOT_APPLICABLE }
    public enum Match { CONDITIONAL_MATCH, UNRESOLVED, EXCLUDED }
    public enum AdviceStatus { CONDITIONAL_ADVICE, NEEDS_INFORMATION, NOT_APPLICABLE }
    public enum ApiStatus { REQUIRED_CONDITIONAL, OPTIONAL_CONDITIONAL, NEEDS_INFORMATION, NOT_REQUIRED, FORBIDDEN }
    public record CapabilityCheck(Capability capability, boolean usable, String reasonCode, Evidence evidence) { }
    public record OptionCheck(String optionId, Match match, List<CapabilityCheck> capabilities) {
        public OptionCheck { capabilities = List.copyOf(capabilities); }
    }
    public record Choice(String id, Disposition disposition, String reasonCode, List<String> conditionalOptionIds,
            List<OptionCheck> optionChecks, List<String> pros, List<String> cons, List<String> conditions, List<URI> references) {
        public Choice {
            conditionalOptionIds = List.copyOf(conditionalOptionIds); optionChecks = List.copyOf(optionChecks);
            pros = List.copyOf(pros); cons = List.copyOf(cons); conditions = List.copyOf(conditions); references = List.copyOf(references);
        }
    }
    public record PatternAdvice(Choice choice, ArchitecturePrerequisiteEvaluator.Analysis prerequisites) { }
    public record ApiAdvice(ApiStatus status, List<OptionCheck> optionChecks, List<String> conditions) {
        public ApiAdvice { optionChecks = List.copyOf(optionChecks); conditions = List.copyOf(conditions); }
    }
    public record Architecture(AdviceStatus status, String basis, List<PatternAdvice> patterns, ApiAdvice apiProtection,
            List<Choice> provisioning) {
        public Architecture { patterns = List.copyOf(patterns); provisioning = List.copyOf(provisioning); }
    }
    public record Binding(CandidatePreferenceScorer.Binding inputs, String scoringVersion, String architectureVersion,
            String patternDefinitionsVersion, String prerequisiteVersion, String provisioningDefinitionsVersion,
            String provisioningConditionsVersion, String hardKernelVersion, String auditabilityInputVersion) { }
    public record Limitation(String profilePath, String declaredValue, String reasonCode, boolean blocksDeploymentRecommendation,
            String explanation) { }
    public record Result(String scope, String kernelVersion, String policyVersion, Binding binding,
            CandidatePreferenceScorer.Status status, CandidatePreferenceScorer.Weights weights,
            List<CandidatePreferenceScorer.ScoredCandidate> candidates, List<String> shortlist,
            List<CandidatePreferenceScorer.RankGroup> rankGroups, Architecture architecture, List<Limitation> limitations,
            List<String> followUps, List<String> deferredBoundaries, boolean sourceAuthorityVerified,
            boolean configurationVerified, boolean complianceVerified, boolean publicationReady, boolean writesPerformed) {
        public Result {
            candidates = List.copyOf(candidates); shortlist = List.copyOf(shortlist); rankGroups = List.copyOf(rankGroups);
            limitations = List.copyOf(limitations); followUps = List.copyOf(followUps); deferredBoundaries = List.copyOf(deferredBoundaries);
        }
    }

    public static Result evaluate(JsonNode profileDocument, int profileSchemaVersion, JsonNode candidateDocument,
            SourceAssertions assertions, JsonNode weightsDocument, Instant at) {
        return evaluate(profileDocument, profileSchemaVersion, candidateDocument, assertions, null, weightsDocument, at);
    }

    public static Result evaluate(JsonNode profileDocument, int profileSchemaVersion, JsonNode candidateDocument,
            SourceAssertions assertions, CandidateAuditabilityInput auditability, JsonNode weightsDocument, Instant at) {
        var profileJson = Objects.requireNonNull(profileDocument).deepCopy();
        var catalogJson = Objects.requireNonNull(candidateDocument).deepCopy();
        var weightsJson = Objects.requireNonNull(weightsDocument).deepCopy();
        // Validates exact claims, profile and weights before any architecture fact can be consumed.
        var scoring = CandidatePreferenceScorer.evaluate(profileJson, profileSchemaVersion, catalogJson, assertions, auditability, weightsJson, at);
        var profile = MAPPER.treeToValue(profileJson, ApplicationIdentityProfile.class);
        var facts = new Facts(profile, catalogJson, assertions, scoring, at);
        var patterns = ArchitecturePatternEvaluator.evaluate(profile).stream().map(pattern -> pattern(profile, facts, pattern)).toList();
        var provisioning = ProvisioningLifecycleEvaluator.DEFINITIONS.stream().map(definition -> provisioning(profile, facts, definition)).toList();
        var apiChecks = facts.options(List.of(Capability.OAUTH2_APIS), false);
        var apiStatus = switch (profile.protocols().oauth2ProtectedApis()) {
            case NOT_REQUIRED -> ApiStatus.NOT_REQUIRED;
            case FORBIDDEN -> ApiStatus.FORBIDDEN;
            case UNKNOWN -> ApiStatus.NEEDS_INFORMATION;
            case REQUIRED -> matched(apiChecks).isEmpty() ? ApiStatus.NEEDS_INFORMATION : ApiStatus.REQUIRED_CONDITIONAL;
            case PREFERRED -> matched(apiChecks).isEmpty() ? ApiStatus.NEEDS_INFORMATION : ApiStatus.OPTIONAL_CONDITIONAL;
        };
        var api = new ApiAdvice(apiStatus, apiChecks, List.of(
                "Browser sessions and enterprise SSO do not independently establish OAuth API protection.",
                "When OAuth APIs are selected, verify issuer, audience, scopes, token validation and resource-level authorization at each API.",
                "An OAuth capability claim does not prove client-credentials grant support or a deployed authorization policy."));
        var status = patterns.stream().allMatch(p -> p.choice().disposition() == Disposition.NOT_APPLICABLE)
                ? AdviceStatus.NOT_APPLICABLE : patterns.stream().anyMatch(p -> p.choice().disposition() == Disposition.UNRESOLVED)
                    || apiStatus == ApiStatus.NEEDS_INFORMATION || provisioning.stream().anyMatch(p -> p.disposition() == Disposition.UNRESOLVED)
                        ? AdviceStatus.NEEDS_INFORMATION : AdviceStatus.CONDITIONAL_ADVICE;
        return new Result("UNVERIFIED_CANDIDATE_DECISION_CALCULATION", VERSION, scoring.policyVersion(),
                new Binding(scoring.binding(), scoring.kernelVersion(), ARCHITECTURE_VERSION, ArchitecturePatternEvaluator.POLICY_VERSION,
                        ArchitecturePrerequisiteEvaluator.POLICY_VERSION, ProvisioningLifecycleEvaluator.POLICY_VERSION,
                        ProvisioningLifecycleV2Evaluator.POLICY_VERSION, scoring.hardKernelVersion(),
                        auditability == null ? null : CandidateAuditabilityInput.VERSION),
                scoring.status(), scoring.weights(), scoring.candidates(), scoring.shortlist(), scoring.rankGroups(),
                new Architecture(status, "CONDITIONAL_DESIGN_ADVICE_WITH_UNAUTHENTICATED_SOURCE_HYPOTHESES", patterns, api, provisioning),
                limitations(profileJson), List.of(
                        "Obtain authenticated source review for each exact option claim; calculation assertions are not curator approvals.",
                        "Resolve hard-check and architecture evidence gaps without substituting preference points.",
                        "Select and test architecture prerequisites, API authorization and provisioning lifecycle before deployment.",
                        "Review operations and costs for the declared workload; no free-tier or cloud compatibility guarantee is calculated."),
                scoring.deferredBoundaries().stream().filter(path -> !path.equals("architectureAdvice")).toList(),
                false, false, false, false, false);
    }

    private static PatternAdvice pattern(ApplicationIdentityProfile profile, Facts facts, Pattern pattern) {
        var scope = ArchitecturePrerequisiteEvaluator.scope(pattern);
        var prerequisites = ArchitecturePrerequisiteEvaluator.evaluate(pattern.patternId(), scope, Map.of());
        boolean session = pattern.patternId() == PatternId.SERVER_SIDE_SESSION;
        var capabilities = switch (pattern.patternId()) {
            case SERVER_SIDE_SESSION -> List.of(Capability.OIDC, Capability.SAML); // Either reviewed sign-in protocol; never both implicitly required.
            case BFF_SESSION, SPA_CODE_PKCE -> List.of(Capability.OIDC, Capability.OAUTH2_APIS);
            case NATIVE_CODE_PKCE -> profile.protocols().oauth2ProtectedApis() == REQUIRED || profile.protocols().oauth2ProtectedApis() == PREFERRED
                    ? List.of(Capability.OIDC, Capability.OAUTH2_APIS) : List.of(Capability.OIDC);
            case M2M_CLIENT_CREDENTIALS -> List.of(Capability.OAUTH2_APIS);
        };
        var checks = facts.options(capabilities, session);
        var disposition = Disposition.ALTERNATIVE;
        String reason = "CONDITIONAL_ALTERNATIVE";
        if (scope == ArchitecturePrerequisiteEvaluator.ClientScope.NOT_SELECTED) {
            disposition = Disposition.NOT_APPLICABLE; reason = "CLIENT_NOT_SELECTED";
        } else if (scope == ArchitecturePrerequisiteEvaluator.ClientScope.UNKNOWN) {
            disposition = Disposition.UNRESOLVED; reason = "CLIENT_CONTEXT_UNKNOWN";
        } else if (matched(checks).isEmpty()) {
            disposition = Disposition.UNRESOLVED; reason = "NO_CONDITIONALLY_COMPATIBLE_OPTION";
        } else {
            var api = profile.protocols().oauth2ProtectedApis();
            var token = profile.security().browserTokenExposureMinimization();
            boolean browser = pattern.clientType() == io.authweave.core.assessment.domain.profile.ApplicationTopology.ClientType.BROWSER;
            if (api == UNKNOWN) {
                disposition = Disposition.UNRESOLVED; reason = "API_REQUIREMENT_UNKNOWN";
            } else if (browser && (token == UNKNOWN || token == FORBIDDEN)) {
                disposition = Disposition.UNRESOLVED; reason = "BROWSER_EXPOSURE_INTENT_UNDEFINED";
            } else if (pattern.patternId() == PatternId.SPA_CODE_PKCE && token == REQUIRED) {
                disposition = Disposition.UNRESOLVED; reason = "ACCEPTABLE_BROWSER_EXPOSURE_UNDEFINED";
            } else if (pattern.patternId() == PatternId.M2M_CLIENT_CREDENTIALS && api != REQUIRED && api != PREFERRED) {
                disposition = Disposition.UNRESOLVED; reason = "WORKLOAD_API_AUTHORIZATION_NOT_SELECTED";
            } else if (session && (api == NOT_REQUIRED || api == FORBIDDEN)
                    || pattern.patternId() == PatternId.BFF_SESSION && (api == REQUIRED || api == PREFERRED) && (token == REQUIRED || token == PREFERRED)
                    || pattern.patternId() == PatternId.NATIVE_CODE_PKCE || pattern.patternId() == PatternId.M2M_CLIENT_CREDENTIALS) {
                disposition = Disposition.RECOMMENDED; reason = "CONDITIONAL_PROFILE_FIT";
            }
        }
        var conditions = new ArrayList<>(pattern.prerequisites());
        conditions.add("Advice is conditional on the listed exact-option capability hypotheses and all unverified design prerequisites.");
        conditions.addAll(operationsConditions(profile));
        if (session) conditions.add("Select supported OIDC or SAML backend sign-in; direct browser access to separate APIs is not covered by the application session.");
        return new PatternAdvice(choice(pattern.patternId().name(), disposition, reason, checks, pattern.advantages(), pattern.tradeoffs(),
                conditions, pattern.references()), prerequisites);
    }

    private static Choice provisioning(ApplicationIdentityProfile profile, Facts facts, ProvisioningLifecycleEvaluator.Definition definition) {
        var requirements = profile.provisioning();
        var capabilities = new ArrayList<Capability>();
        if (definition.scimPlanned()) capabilities.add(Capability.SCIM);
        if (definition.jitPlanned()) capabilities.add(Capability.JIT);
        if (requirements.groupSynchronization() == REQUIRED || requirements.groupSynchronization() == PREFERRED) capabilities.add(Capability.GROUP_SYNC);
        var checks = facts.options(capabilities, false);
        var disposition = Disposition.ALTERNATIVE; String reason = "CONDITIONAL_ALTERNATIVE";
        if (requirements.scim() == REQUIRED && !definition.scimPlanned()) {
            disposition = Disposition.NOT_APPLICABLE; reason = "REQUIRED_SCIM_CANNOT_BE_REPLACED_BY_JIT";
        } else if (requirements.justInTimeProvisioning() == REQUIRED && !definition.jitPlanned()) {
            disposition = Disposition.NOT_APPLICABLE; reason = "REQUIRED_JIT_NOT_PLANNED";
        } else if (requirements.scim() == FORBIDDEN && definition.scimPlanned()
                || requirements.justInTimeProvisioning() == FORBIDDEN && definition.jitPlanned()) {
            disposition = Disposition.NOT_APPLICABLE; reason = "FORBIDDEN_PROVISIONING_MECHANISM";
        } else if (requirements.scim() == UNKNOWN || requirements.justInTimeProvisioning() == UNKNOWN || requirements.groupSynchronization() == UNKNOWN) {
            disposition = Disposition.UNRESOLVED; reason = "PROVISIONING_REQUIREMENT_UNKNOWN";
        } else if (matched(checks).isEmpty()) {
            disposition = Disposition.UNRESOLVED; reason = "NO_CONDITIONALLY_COMPATIBLE_OPTION";
        } else if ((requirements.scim() == REQUIRED || requirements.scim() == PREFERRED) == definition.scimPlanned()
                && (requirements.justInTimeProvisioning() == REQUIRED || requirements.justInTimeProvisioning() == PREFERRED) == definition.jitPlanned()) {
            disposition = Disposition.RECOMMENDED; reason = "CONDITIONAL_PROFILE_FIT";
        }
        var groups = requirements.groupSynchronization() == NOT_REQUIRED || requirements.groupSynchronization() == FORBIDDEN
                ? ProvisioningLifecycleV2Evaluator.GroupStrategy.NONE : ProvisioningLifecycleV2Evaluator.GroupStrategy.UNKNOWN;
        var ids = ProvisioningLifecycleV2Evaluator.conditions(definition.patternId(), groups);
        var conditions = new ArrayList<>(ProvisioningLifecycleV2Evaluator.CONDITION_DEFINITIONS.stream()
                .filter(d -> ids.contains(d.conditionId())).map(ProvisioningLifecycleV2Evaluator.ConditionDefinition::description).toList());
        conditions.add("All lifecycle conditions remain unverified; SCIM capability alone does not prove direction, operation set, group delivery or session/token revocation.");
        conditions.addAll(operationsConditions(profile));
        if (groups == ProvisioningLifecycleV2Evaluator.GroupStrategy.UNKNOWN) conditions.add("Choose and test group transport, membership mapping, role enforcement and removal handling independently; User-only SCIM is insufficient.");
        return choice(definition.patternId().name(), disposition, reason, checks, definition.advantages(), definition.tradeoffs(), conditions, definition.references());
    }

    private static Choice choice(String id, Disposition disposition, String reason, List<OptionCheck> checks, List<String> pros,
            List<String> cons, List<String> conditions, List<URI> references) {
        return new Choice(id, disposition, reason,
                disposition == Disposition.RECOMMENDED || disposition == Disposition.ALTERNATIVE ? matched(checks) : List.of(),
                checks, pros, cons, conditions, references);
    }
    private static List<String> matched(List<OptionCheck> checks) {
        return checks.stream().filter(c -> c.match() == Match.CONDITIONAL_MATCH).map(OptionCheck::optionId).toList();
    }

    private static List<String> operationsConditions(ApplicationIdentityProfile profile) {
        var operations = profile.operations();
        return List.of(
                "Hosting preference " + operations.hosting() + " is planning intent, not option elimination: self-hosting requires patching, backups and recovery ownership; managed service terms require independent review.",
                "Deployment target " + operations.deploymentTarget() + " does not prove cloud compatibility, residency or an available managed offering.",
                "Identity expertise " + operations.identityExpertise() + " does not verify operator capacity; identify who maintains sessions, workload credentials and provisioning recovery.");
    }

    /** Every proposed pattern consumes exact facts, including facts not required/scored by this profile. */
    private static final class Facts {
        private final ApplicationIdentityProfile profile;
        private final Instant at;
        private final CandidatePreferenceScorer.Analysis scoring;
        private final Map<String, ProviderCatalogDraft.Option> options = new HashMap<>();
        private final Map<String, JsonNode> rawOptions = new HashMap<>();
        private final Map<String, Map<String, Assertion>> assertions = new HashMap<>();
        Facts(ApplicationIdentityProfile profile, JsonNode catalog, SourceAssertions input, CandidatePreferenceScorer.Analysis scoring, Instant at) {
            this.profile = profile; this.scoring = scoring; this.at = at;
            MAPPER.treeToValue(catalog, ProviderCatalogDraft.class).options().forEach(o -> options.put(o.id(), o));
            catalog.get("options").forEach(o -> rawOptions.put(o.get("id").asText(), o));
            input.facts().forEach(a -> assertions.computeIfAbsent(a.optionId(), ignored -> new HashMap<>()).put(a.factPath(), a.assertion()));
        }
        List<OptionCheck> options(List<Capability> capabilities, boolean either) {
            return scoring.candidates().stream().map(c -> {
                var hard = c.hardChecks();
                var checks = capabilities.stream().map(capability -> capability(hard.optionId(), capability)).toList();
                boolean usable = either ? checks.stream().anyMatch(CapabilityCheck::usable) : checks.stream().allMatch(CapabilityCheck::usable);
                var match = hard.hardVerdict() == Verdict.EXCLUDED ? Match.EXCLUDED
                        : hard.hardVerdict() == Verdict.UNRESOLVED || !usable ? Match.UNRESOLVED : Match.CONDITIONAL_MATCH;
                return new OptionCheck(hard.optionId(), match, checks);
            }).toList();
        }
        CapabilityCheck capability(String id, Capability capability) {
            String path = "facts." + capability;
            var fact = options.get(id).facts().get(capability);
            var assertion = assertions.getOrDefault(id, Map.of()).get(path);
            var evidence = fact == null ? null : new Evidence(claimSha256(rawOptions.get(id), path), assertion,
                    fact.evidence().sourceUrl(), fact.evidence().observedAt(), fact.conditions());
            String problem = evidenceProblem(fact, assertion, at);
            var requirement = switch (capability) {
                case OIDC -> profile.protocols().criticalityOf(io.authweave.core.assessment.domain.profile.ProtocolRequirements.FederationProtocol.OIDC);
                case SAML -> profile.protocols().criticalityOf(io.authweave.core.assessment.domain.profile.ProtocolRequirements.FederationProtocol.SAML);
                case OAUTH2_APIS -> profile.protocols().oauth2ProtectedApis();
                default -> NOT_REQUIRED; // SCIM/JIT prohibitions are handled by the provisioning choice policy.
            };
            String reason = requirement == FORBIDDEN ? "PROFILE_FORBIDS_PATTERN_CAPABILITY" : problem != null ? problem
                    : fact.availability() == Availability.UNKNOWN ? "CAPABILITY_UNKNOWN"
                    : fact.availability() == Availability.UNAVAILABLE ? "UNSUPPORTED" : "SUPPORTED";
            return new CapabilityCheck(capability, reason.equals("SUPPORTED"), reason, evidence);
        }
    }

    private static List<Limitation> limitations(JsonNode profile) {
        var result = new ArrayList<Limitation>();
        result.add(new Limitation(null, null, "SOURCE_AUTHORITY_UNVERIFIED", true,
                "All assertions are calculation hypotheses, not authenticated review receipts; no trusted catalog or deployment approval is produced."));
        for (String path : List.of("security.authenticationControls.phishingResistance", "security.authenticationControls.nonExportableKeys",
                "security.authenticationControls.stepUpAuthentication", "security.dataResidency", "security.complianceScopeStatus",
                "security.auditability", "security.assurance", "security.complianceTargets")) {
            result.add(new Limitation(path, profile.at("/" + path.replace('.', '/')).toString(), "DECLARED_SCOPE_NOT_OBSERVED", true,
                    "Hard outcomes remain in candidate findings; capability evidence is not verified enforcement, retention, deployed residency, assurance or compliance certification."));
        }
        for (String path : List.of("operations.hosting", "operations.deploymentTarget", "operations.identityExpertise",
                "operations.budgetSensitivity", "operations.usagePlanning.scopeDescription", "operations.usagePlanning.assumptions", "operations.usagePlanning.volumes")) {
            result.add(new Limitation(path, profile.at("/" + path.replace('.', '/')).toString(), "OPERATIONS_AND_COST_PLANNING_ONLY", true,
                    "Keep this declared planning input explicit: assess hosting, cloud interoperability, operator capacity and workload separately. Usage labels are not validated billing units; no price or free-tier guarantee is inferred."));
        }
        return List.copyOf(result);
    }
}

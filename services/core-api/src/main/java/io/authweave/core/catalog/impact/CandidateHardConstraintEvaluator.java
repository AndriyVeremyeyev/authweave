package io.authweave.core.catalog.impact;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import io.authweave.core.assessment.domain.profile.*;
import io.authweave.core.catalog.AuditabilityFacts;
import io.authweave.core.catalog.ProviderCatalog.Availability;
import io.authweave.core.catalog.draft.*;
import io.authweave.core.catalog.draft.ProviderCatalogDraft.*;
import io.authweave.core.evaluation.*;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static io.authweave.core.evaluation.CapabilityPreflight.Outcome;

/** First decision-engine kernel, not a final result or trusted catalog loading path.
 * Source assertions are calculation inputs, NOT authenticated/stored curator receipts.
 * No controller, repositories, source fetcher, scores, ranking or publication authority. */
public final class CandidateHardConstraintEvaluator {
    public static final String VERSION = "decision-hard-check-1";
    public static final String POLICY_VERSION = "decision-core-explicit-1";
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private CandidateHardConstraintEvaluator() { }

    public enum Verdict { ELIGIBLE, EXCLUDED, UNRESOLVED }
    public enum Assertion { SOURCE_SUPPORTS_CLAIM, SOURCE_DOES_NOT_SUPPORT_CLAIM, INSUFFICIENT_EVIDENCE }
    public record FactAssertion(String optionId, String factPath, String claimSha256, Assertion assertion) {
        public FactAssertion {
            Objects.requireNonNull(optionId); Objects.requireNonNull(factPath);
            digest(claimSha256); Objects.requireNonNull(assertion);
        }
    }
    public record SourceAssertions(String candidateSha256, List<FactAssertion> facts) {
        public SourceAssertions { digest(candidateSha256); facts = List.copyOf(facts); }
        public static SourceAssertions unreviewed(JsonNode candidate) {
            return new SourceAssertions(DecisionCanonicalizer.sha256(candidate), List.of());
        }
    }
    public record Binding(int profileSchemaVersion, String profileSha256, String catalogVersion,
            String catalogSha256, String sourceAssertionsSha256, String canonicalization, Instant evaluatedAt) { }
    public record Evidence(String claimSha256, Assertion sourceAssertion, java.net.URI sourceUrl,
            Instant observedAt, List<String> conditions) {
        public Evidence { conditions = List.copyOf(conditions); }
    }
    public record Finding(String checkId, String profilePath, String factPath, String criticality,
            Outcome outcome, String reasonCode, Evidence evidence) { }
    public record Candidate(String optionId, String providerId, String product, String plan, String region,
            Deployment deployment, String configuration, Verdict hardVerdict, List<Finding> findings) {
        public Candidate { findings = List.copyOf(findings); }
    }
    public record Analysis(String scope, String kernelVersion, String policyVersion, Binding binding,
            List<Candidate> candidates, List<String> deferredBoundaries,
            boolean sourceAuthorityVerified, boolean configurationVerified, boolean complianceVerified,
            boolean publicationReady, boolean writesPerformed) {
        public Analysis { candidates = List.copyOf(candidates); deferredBoundaries = List.copyOf(deferredBoundaries); }
    }
    private record Address(String optionId, String factPath) { }

    /** Exact wire JSON is bound before typed conversion; record defaults must not change its digest. */
    public static Analysis evaluate(JsonNode profileDocument, int profileSchemaVersion,
            JsonNode candidateDocument, SourceAssertions sourceAssertions, Instant at) {
        Objects.requireNonNull(at); Objects.requireNonNull(sourceAssertions);
        var profileJson = Objects.requireNonNull(profileDocument).deepCopy();
        var catalogJson = Objects.requireNonNull(candidateDocument).deepCopy();
        requireV6(profileJson, profileSchemaVersion);
        var profile = MAPPER.treeToValue(profileJson, ApplicationIdentityProfile.class);
        if (!ApplicationIdentityProfileValidator.validate(profile).canSave())
            throw new IllegalArgumentException("Contradictory profile cannot be evaluated");
        var draft = MAPPER.treeToValue(catalogJson, ProviderCatalogDraft.class);
        var validation = new CatalogDraftValidator(Clock.fixed(at, ZoneOffset.UTC)).validateAt(draft, at);
        if (validation.status() != CatalogDraftValidation.Status.VALID_DRAFT)
            throw new IllegalArgumentException("Invalid decision candidate");
        var catalogDigest = DecisionCanonicalizer.sha256(catalogJson);
        if (!catalogDigest.equals(sourceAssertions.candidateSha256()))
            throw new IllegalArgumentException("Source assertions belong to another candidate");
        var rawOptions = new HashMap<String, JsonNode>();
        catalogJson.get("options").forEach(option -> rawOptions.put(option.get("id").asText(), option));
        var assertions = boundAssertions(draft, rawOptions, sourceAssertions);
        var rules = ScenarioRulePlan.from(profile).stream().sorted(Comparator.comparing(ScenarioRulePlan.Rule::checkId)).toList();
        var candidates = draft.options().stream().sorted(Comparator.comparing(Option::id)).map(option -> {
            var findings = new ArrayList<Finding>();
            var facts = CatalogDraftFacts.entries(option);
            for (var rule : rules) {
                var fact = rule.factPath() == null ? null : facts.get(rule.factPath());
                var assertion = assertions.get(new Address(option.id(), rule.factPath()));
                var evidence = fact == null ? null : new Evidence(claimSha256(rawOptions.get(option.id()), rule.factPath()),
                        assertion, fact.evidence().sourceUrl(), fact.evidence().observedAt(), fact.conditions());
                Outcome outcome = rule.presetOutcome(); String reason = rule.presetReason();
                if (rule.usesFact()) {
                    reason = evidenceProblem(fact, assertion, at);
                    if (reason != null) outcome = Outcome.UNKNOWN;
                    else if (rule.criticality() == RequirementCriticality.FORBIDDEN
                            && fact instanceof CapabilityFact capability && capability.availability() == Availability.OPTIONAL) {
                        // No typed disablement evidence exists in the draft contract. Prose conditions cannot invent it.
                        outcome = Outcome.UNKNOWN; reason = "CONFIGURATION_REQUIRED";
                    } else {
                        outcome = claim(rule, fact);
                        reason = switch (outcome) {
                            case PASS -> "SUPPORTED"; case FAIL -> "UNSUPPORTED";
                            case UNKNOWN -> "REQUIRED_UNKNOWN";
                            case NOT_APPLIED -> throw new IllegalStateException("Active hard check cannot be skipped");
                        };
                    }
                }
                String criticality = rule.kind() == CatalogChangePreview.FactKind.COMPATIBILITY
                        || rule.profilePath().equals("security.complianceScopeStatus") ? "CONTEXT" : rule.criticality().name();
                findings.add(new Finding(rule.checkId(), rule.profilePath(), rule.factPath(), criticality, outcome, reason, evidence));
            }
            // Draft v1 has no auditability supplement. Never borrow unrelated synthetic audit facts.
            var auditScope = new AuditabilityFacts.Scope(option.id(), option.plan(), option.region(), option.configuration());
            var audit = AuditabilityEvaluator.evaluate(profile.security().auditability(),
                    profile.security().auditabilityRequirements(), auditScope, List.of(), at);
            audit.checks().forEach(check -> findings.add(new Finding("security.auditability|" + check.criterion(),
                    "security.auditability", "auditabilitySupplement." + check.criterion(),
                    profile.security().auditability().name(), check.outcome(), check.reasonCode().name(), null)));
            var assurance = profile.security().assurance();
            findings.add(new Finding("security.assurance|scope", "security.assurance", null, "CONTEXT",
                    assurance == SecurityRequirements.AssuranceLevel.BASELINE ? Outcome.NOT_APPLIED : Outcome.UNKNOWN,
                    "PLANNING_ONLY", null));
            findings.sort(Comparator.comparing(Finding::checkId));
            var verdict = findings.stream().anyMatch(f -> f.outcome() == Outcome.FAIL) ? Verdict.EXCLUDED
                    : findings.stream().anyMatch(f -> f.outcome() == Outcome.UNKNOWN) ? Verdict.UNRESOLVED : Verdict.ELIGIBLE;
            return new Candidate(option.id(), option.providerId(), option.product(), option.plan(), option.region(),
                    option.deployment(), option.configuration(), verdict, findings);
        }).toList();
        return new Analysis("CANDIDATE_HARD_CHECK_KERNEL", VERSION, POLICY_VERSION,
                new Binding(profileSchemaVersion, DecisionCanonicalizer.sha256(profileJson), draft.catalogVersion(),
                        catalogDigest, DecisionCanonicalizer.sha256(MAPPER.valueToTree(sourceAssertions)), DecisionCanonicalizer.VERSION, at),
                candidates, List.of("preferenceScoringAndSensitivity", "shortlistAndRanking", "architectureAdvice",
                        "operationsAndCostPlanning", "reviewReceiptAuthenticationAndLoading", "publishedSnapshotPinning",
                        "realAuditabilitySupplementLoading", "deployedConfigurationAndLifecycle", "complianceCertification"),
                false, false, false, false, false);
    }

    /** Same claim binding as the offline candidate assembler, including the entire exact option scope. */
    public static String claimSha256(JsonNode option, String factPath) {
        JsonNode fact = option;
        for (String segment : factPath.split("\\.")) fact = fact.path(segment);
        if (fact.isMissingNode() || fact.isNull()) throw new IllegalArgumentException("No claim at this fact address");
        var claim = MAPPER.createObjectNode();
        claim.put("optionId", option.get("id").asText()); claim.put("optionSha256", DecisionCanonicalizer.sha256(option));
        claim.put("factPath", factPath); claim.set("fact", fact);
        return DecisionCanonicalizer.sha256(claim);
    }

    private static Map<Address, Assertion> boundAssertions(ProviderCatalogDraft draft, Map<String, JsonNode> rawOptions,
            SourceAssertions input) {
        var known = new HashMap<String, Map<String, ProposedFact>>();
        draft.options().forEach(option -> known.put(option.id(), CatalogDraftFacts.entries(option)));
        var result = new HashMap<Address, Assertion>();
        for (var entry : input.facts()) {
            if (!known.getOrDefault(entry.optionId(), Map.of()).containsKey(entry.factPath())
                    || !claimSha256(rawOptions.get(entry.optionId()), entry.factPath()).equals(entry.claimSha256())
                    || result.putIfAbsent(new Address(entry.optionId(), entry.factPath()), entry.assertion()) != null)
                throw new IllegalArgumentException("Unknown, changed or duplicate source assertion");
        }
        return Map.copyOf(result);
    }

    // Shared with preference scoring; callers must first validate exact candidate/claim bindings.
    static String evidenceProblem(ProposedFact fact, Assertion assertion, Instant at) {
        if (fact == null) return "EVIDENCE_MISSING";
        if (assertion == null) return "EVIDENCE_UNREVIEWED";
        if (assertion == Assertion.SOURCE_DOES_NOT_SUPPORT_CLAIM) return "EVIDENCE_CONTRADICTED";
        if (assertion == Assertion.INSUFFICIENT_EVIDENCE) return "EVIDENCE_INSUFFICIENT";
        if (fact.evidence().observedAt().isAfter(at)) return "EVIDENCE_FUTURE";
        if (fact.evidence().observedAt().isBefore(at.minus(EvidencePolicy.MAX_AGE))) return "EVIDENCE_STALE";
        return null;
    }

    private static Outcome claim(ScenarioRulePlan.Rule rule, ProposedFact fact) {
        return switch (fact) {
            case CapabilityFact value -> ClaimRules.capability(rule.criticality(), value.availability());
            case CompatibilityFact value -> ClaimRules.compatibility(value.support());
            case ResidencyFact value -> ClaimRules.residency(value.coverage(), value.storageCountries(), rule.allowedCountries());
            case AuthenticationFact value -> ClaimRules.authentication(value.availability(), value.enforcement());
            default -> throw new IllegalArgumentException("Unsupported decision fact");
        };
    }

    private static void requireV6(JsonNode profile, int version) {
        if (version != 6 || !profile.isObject()) throw new IllegalArgumentException("Decision kernel requires profile v6");
        for (String path : List.of("/security/dataResidencyDetails", "/security/authenticationControls",
                "/security/complianceScopeStatus", "/security/auditabilityRequirements", "/operations/usagePlanning"))
            if (profile.at(path).isMissingNode() || profile.at(path).isNull())
                throw new IllegalArgumentException("Incomplete v6 decision profile");
    }

    private static void digest(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid decision binding digest");
    }
}

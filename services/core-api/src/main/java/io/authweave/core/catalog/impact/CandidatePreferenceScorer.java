package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.domain.profile.RequirementCriticality;
import io.authweave.core.catalog.ProviderCatalog.Availability;
import io.authweave.core.catalog.ProviderCatalog.Capability;
import io.authweave.core.catalog.draft.ProviderCatalogDraft;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static io.authweave.core.catalog.impact.CandidateHardConstraintEvaluator.*;

/** Candidate-only scoring over freshly computed hard checks, never caller-supplied eligibility.
 * Source assertions remain calculation hypotheses, not authenticated curator review receipts.
 * No hidden weights, automatic winner, publication authority, persistence or final-result endpoint. */
public final class CandidatePreferenceScorer {
    public static final String VERSION = "decision-preference-scoring-2";
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private CandidatePreferenceScorer() { }

    public enum Mode { NONE, EXPLICIT }
    public enum PreferenceOutcome { AVAILABLE, UNAVAILABLE, UNKNOWN }
    public enum Status { RANKED_SHORTLIST, UNRANKED_SHORTLIST, NEEDS_INFORMATION, NO_ELIGIBLE_OPTIONS }
    public record Weight(Capability capability, int weight) {
        public Weight {
            Objects.requireNonNull(capability);
            if (weight < 1 || weight > 100) throw new IllegalArgumentException("Weight must be an integer from 1 to 100");
        }
    }
    public record Weights(Mode mode, List<Weight> values) {
        public Weights {
            Objects.requireNonNull(mode); values = List.copyOf(values);
            var dimensions = new HashSet<Capability>();
            if (values.size() > 9 || values.stream().anyMatch(value -> !dimensions.add(value.capability()))
                    || (mode == Mode.NONE ? !values.isEmpty() : values.isEmpty() || values.stream().mapToInt(Weight::weight).sum() != 100))
                throw new IllegalArgumentException("Use unique explicit weights totaling 100, or NONE with no values");
        }
    }
    public record Binding(CandidateHardConstraintEvaluator.Binding hardChecks, String weightsSha256) { }
    public record Contribution(Capability capability, String profilePath, int weight, PreferenceOutcome outcome,
            int earnedPoints, String reasonCode, Evidence evidence) { }
    public record Score(int lowerBound, int upperBound, int unknownWeight, List<Contribution> contributions) {
        public Score { contributions = List.copyOf(contributions); }
    }
    public record ScoredCandidate(CandidateHardConstraintEvaluator.Candidate hardChecks, Score score) { }
    public record RankGroup(int rank, List<String> optionIds) {
        public RankGroup { optionIds = List.copyOf(optionIds); }
    }
    public record Analysis(String scope, String kernelVersion, String hardKernelVersion, String policyVersion,
            Binding binding, Weights weights, Status status, List<ScoredCandidate> candidates,
            List<String> shortlist, List<RankGroup> rankGroups, List<String> deferredBoundaries,
            boolean sourceAuthorityVerified, boolean configurationVerified, boolean complianceVerified,
            boolean publicationReady, boolean writesPerformed) {
        public Analysis {
            candidates = List.copyOf(candidates); shortlist = List.copyOf(shortlist);
            rankGroups = List.copyOf(rankGroups); deferredBoundaries = List.copyOf(deferredBoundaries);
        }
    }
    public record Sensitivity(String scope, Analysis before, Analysis after, boolean writesPerformed) { }

    /** One exact input snapshot. No cached/transported hard-check report can inject a scoreable option. */
    public static Analysis evaluate(JsonNode profileDocument, int profileSchemaVersion, JsonNode candidateDocument,
            SourceAssertions sourceAssertions, JsonNode weightsDocument, Instant at) {
        return evaluate(profileDocument, profileSchemaVersion, candidateDocument, sourceAssertions, null, weightsDocument, at);
    }

    public static Analysis evaluate(JsonNode profileDocument, int profileSchemaVersion, JsonNode candidateDocument,
            SourceAssertions sourceAssertions, CandidateAuditabilityInput auditability, JsonNode weightsDocument, Instant at) {
        var profileJson = Objects.requireNonNull(profileDocument).deepCopy();
        var catalogJson = Objects.requireNonNull(candidateDocument).deepCopy();
        var weightsJson = Objects.requireNonNull(weightsDocument).deepCopy();
        var weights = readWeights(weightsJson);
        var hard = CandidateHardConstraintEvaluator.evaluate(profileJson, profileSchemaVersion, catalogJson, sourceAssertions, auditability, at);
        var profile = MAPPER.treeToValue(profileJson, ApplicationIdentityProfile.class);
        var preferred = ScenarioRulePlan.from(profile).stream().filter(rule -> rule.kind() == io.authweave.core.catalog.draft.CatalogChangePreview.FactKind.CAPABILITY
                && rule.criticality() == RequirementCriticality.PREFERRED).toList();
        var dimensions = new HashSet<Capability>();
        preferred.forEach(rule -> dimensions.add(Capability.valueOf(rule.factPath().substring("facts.".length()))));
        var supplied = new HashSet<Capability>(); weights.values().forEach(weight -> supplied.add(weight.capability()));
        if (!supplied.equals(dimensions) || (dimensions.isEmpty() ? weights.mode() != Mode.NONE : weights.mode() != Mode.EXPLICIT))
            throw new IllegalArgumentException("Weights must cover exactly the profile's explicitly preferred capabilities");

        var options = new HashMap<String, ProviderCatalogDraft.Option>();
        MAPPER.treeToValue(catalogJson, ProviderCatalogDraft.class).options().forEach(option -> options.put(option.id(), option));
        // Stable presentation only: input array order is still preserved in the originating weights digest.
        var orderedWeights = weights.values().stream().sorted(Comparator.comparing(weight -> weight.capability().name())).toList();
        var candidates = hard.candidates().stream().map(candidate -> {
            Score score = null;
            if (candidate.hardVerdict() == Verdict.ELIGIBLE && weights.mode() == Mode.EXPLICIT) {
                var contributions = orderedWeights.stream().map(weight -> contribution(candidate,
                        options.get(candidate.optionId()).facts().get(weight.capability()), weight, at)).toList();
                int lower = contributions.stream().mapToInt(Contribution::earnedPoints).sum();
                int unknown = contributions.stream().filter(c -> c.outcome() == PreferenceOutcome.UNKNOWN).mapToInt(Contribution::weight).sum();
                score = new Score(lower, lower + unknown, unknown, contributions);
            }
            return new ScoredCandidate(candidate, score);
        }).toList();
        var eligible = candidates.stream().filter(candidate -> candidate.hardChecks().hardVerdict() == Verdict.ELIGIBLE).toList();
        var shortlist = eligible.stream().map(candidate -> candidate.hardChecks().optionId()).toList();
        boolean rankable = !eligible.isEmpty() && weights.mode() == Mode.EXPLICIT
                && eligible.stream().allMatch(candidate -> candidate.score().unknownWeight() == 0);
        var ranks = new ArrayList<RankGroup>();
        if (rankable) {
            var groups = new TreeMap<Integer, List<String>>(Comparator.reverseOrder());
            eligible.forEach(candidate -> groups.computeIfAbsent(candidate.score().lowerBound(), ignored -> new ArrayList<>())
                    .add(candidate.hardChecks().optionId()));
            groups.values().forEach(ids -> ranks.add(new RankGroup(ranks.size() + 1, ids)));
        }
        var status = !eligible.isEmpty() ? rankable ? Status.RANKED_SHORTLIST : Status.UNRANKED_SHORTLIST
                : candidates.stream().anyMatch(candidate -> candidate.hardChecks().hardVerdict() == Verdict.UNRESOLVED)
                    ? Status.NEEDS_INFORMATION : Status.NO_ELIGIBLE_OPTIONS;
        return new Analysis("CANDIDATE_CAPABILITY_SCORING_KERNEL", VERSION, hard.kernelVersion(), hard.policyVersion(),
                new Binding(hard.binding(), DecisionCanonicalizer.sha256(weightsJson)), weights, status, candidates, shortlist, ranks,
                hard.deferredBoundaries().stream().filter(path -> !List.of("preferenceScoringAndSensitivity", "shortlistAndRanking").contains(path)).toList(),
                false, false, false, false, false);
    }

    /** Both runs receive the SAME copied profile/catalog, assertions and clock; only explicit weights differ. */
    public static Sensitivity compareWeights(JsonNode profileDocument, int profileSchemaVersion, JsonNode candidateDocument,
            SourceAssertions sourceAssertions, JsonNode beforeWeights, JsonNode afterWeights, Instant at) {
        return compareWeights(profileDocument, profileSchemaVersion, candidateDocument, sourceAssertions, null, beforeWeights, afterWeights, at);
    }

    public static Sensitivity compareWeights(JsonNode profileDocument, int profileSchemaVersion, JsonNode candidateDocument,
            SourceAssertions sourceAssertions, CandidateAuditabilityInput auditability, JsonNode beforeWeights, JsonNode afterWeights, Instant at) {
        var profile = Objects.requireNonNull(profileDocument).deepCopy();
        var candidate = Objects.requireNonNull(candidateDocument).deepCopy();
        var beforeDocument = Objects.requireNonNull(beforeWeights).deepCopy();
        var afterDocument = Objects.requireNonNull(afterWeights).deepCopy();
        var before = evaluate(profile, profileSchemaVersion, candidate, sourceAssertions, auditability, beforeDocument, at);
        var after = evaluate(profile, profileSchemaVersion, candidate, sourceAssertions, auditability, afterDocument, at);
        if (!before.binding().hardChecks().equals(after.binding().hardChecks())
                || !before.candidates().stream().map(ScoredCandidate::hardChecks).toList()
                    .equals(after.candidates().stream().map(ScoredCandidate::hardChecks).toList()))
            throw new IllegalStateException("Sensitivity must preserve all hard-check inputs and outcomes");
        return new Sensitivity("FIXED_CANDIDATE_WEIGHT_SENSITIVITY", before, after, false);
    }

    private static Contribution contribution(CandidateHardConstraintEvaluator.Candidate candidate,
            ProviderCatalogDraft.CapabilityFact fact, Weight weight, Instant at) {
        String path = "facts." + weight.capability();
        var finding = candidate.findings().stream().filter(check -> path.equals(check.factPath())).findFirst().orElseThrow();
        if (!finding.criticality().equals("PREFERRED")) throw new IllegalStateException("Only preferences may contribute points");
        var evidence = finding.evidence();
        String problem = evidenceProblem(fact, evidence == null ? null : evidence.sourceAssertion(), at);
        var outcome = problem != null || fact.availability() == Availability.UNKNOWN ? PreferenceOutcome.UNKNOWN
                : fact.availability() == Availability.UNAVAILABLE ? PreferenceOutcome.UNAVAILABLE : PreferenceOutcome.AVAILABLE;
        String reason = problem != null ? problem : outcome == PreferenceOutcome.UNKNOWN ? "CAPABILITY_UNKNOWN"
                : outcome == PreferenceOutcome.AVAILABLE ? "SUPPORTED" : "UNSUPPORTED";
        return new Contribution(weight.capability(), finding.profilePath(), weight.weight(), outcome,
                outcome == PreferenceOutcome.AVAILABLE ? weight.weight() : 0, reason, evidence);
    }

    private static Weights readWeights(JsonNode document) {
        if (!document.isObject() || !document.path("mode").isString() || !document.path("values").isArray()
                || document.get("values").size() > 9)
            throw new IllegalArgumentException("Use an explicit weights document");
        for (var value : document.get("values")) {
            if (!value.isObject() || !value.path("capability").isString() || !value.path("weight").isIntegralNumber()
                    || !value.path("weight").canConvertToInt())
                throw new IllegalArgumentException("Weights cannot use strings, fractions or out-of-range numbers");
        }
        return MAPPER.treeToValue(document, Weights.class);
    }
}

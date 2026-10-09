package io.authweave.core.catalog.impact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.NullNode;

/** Whole composed decisions, not preflight-count coverage or publication authority.
 * Both sides are recomputed on one profile/weights/clock; no caller result is accepted. */
public final class CandidateDecisionImpactEvaluator {
    public static final String VERSION = "decision-candidate-impact-1";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private CandidateDecisionImpactEvaluator() { }
    public record Snapshot(JsonNode catalog, CandidateHardConstraintEvaluator.SourceAssertions assertions,
            CandidateAuditabilityInput auditability) {
        public Snapshot { catalog = Objects.requireNonNull(catalog).deepCopy(); Objects.requireNonNull(assertions); }
        @Override public JsonNode catalog() { return catalog.deepCopy(); }
    }
    public record Delta(String path, JsonNode before, JsonNode after) {
        public Delta { before = before.deepCopy(); after = after.deepCopy(); }
        @Override public JsonNode before() { return before.deepCopy(); }
        @Override public JsonNode after() { return after.deepCopy(); }
    }
    public record Result(String scope, String impactVersion, String canonicalization, Instant evaluatedAt,
            String profileSha256, String weightsSha256, String beforeInputSha256, String afterInputSha256,
            String beforeResultSha256, String afterResultSha256, CandidateDecisionEvaluator.Result before,
            CandidateDecisionEvaluator.Result after, List<Delta> deltas, boolean candidateInputsChanged,
            boolean decisionOutcomesChanged, boolean resultDetailsChanged, boolean coverageComplete,
            boolean sourceVerificationPerformed, boolean approvalGranted, boolean publicationReady, boolean writesPerformed) {
        public Result { deltas = List.copyOf(deltas); }
    }
    public static Result evaluate(JsonNode profileDocument, int profileSchemaVersion, JsonNode weightsDocument,
            Snapshot beforeInput, Snapshot afterInput, Instant at) {
        var profile = Objects.requireNonNull(profileDocument).deepCopy(); var weights = Objects.requireNonNull(weightsDocument).deepCopy();
        Objects.requireNonNull(beforeInput); Objects.requireNonNull(afterInput); Objects.requireNonNull(at);
        var before = run(profile, profileSchemaVersion, weights, beforeInput, at);
        var after = run(profile, profileSchemaVersion, weights, afterInput, at);
        var a = details(before); var b = details(after); var paths = new TreeSet<>(a.keySet()); paths.addAll(b.keySet());
        var deltas = new ArrayList<Delta>();
        for (var path : paths) {
            var left = a.getOrDefault(path, NullNode.getInstance()); var right = b.getOrDefault(path, NullNode.getInstance());
            if (!left.equals(right)) deltas.add(new Delta(path, left, right));
        }
        var beforeDigest = DecisionCanonicalizer.sha256(MAPPER.valueToTree(beforeInput));
        var afterDigest = DecisionCanonicalizer.sha256(MAPPER.valueToTree(afterInput));
        return new Result("CANDIDATE_WHOLE_DECISION_IMPACT", VERSION, DecisionCanonicalizer.VERSION, at,
                DecisionCanonicalizer.sha256(profile), DecisionCanonicalizer.sha256(weights), beforeDigest, afterDigest,
                DecisionCanonicalizer.sha256(MAPPER.valueToTree(before)), DecisionCanonicalizer.sha256(MAPPER.valueToTree(after)),
                before, after, deltas, !beforeDigest.equals(afterDigest), !outcomes(before).equals(outcomes(after)), !deltas.isEmpty(),
                false, false, false, false, false);
    }
    private static CandidateDecisionEvaluator.Result run(JsonNode profile, int schema, JsonNode weights, Snapshot input, Instant at) {
        return CandidateDecisionEvaluator.evaluate(profile, schema, input.catalog(), input.assertions(), input.auditability(), weights, at);
    }
    /** Stable keyed paths prevent an inserted option/check from appearing to replace its neighbour. */
    private static Map<String, JsonNode> details(CandidateDecisionEvaluator.Result result) {
        var values = new TreeMap<String, JsonNode>();
        values.put("/status", MAPPER.valueToTree(result.status()));
        for (var candidate : result.candidates()) {
            var prefix = "/candidates/" + escape(candidate.hardChecks().optionId());
            values.put(prefix + "/hardVerdict", MAPPER.valueToTree(candidate.hardChecks().hardVerdict()));
            values.put(prefix + "/score", MAPPER.valueToTree(candidate.score()));
            candidate.hardChecks().findings().forEach(f -> values.put(prefix + "/findings/" + escape(f.checkId()), MAPPER.valueToTree(f)));
        }
        values.put("/shortlist", MAPPER.valueToTree(result.shortlist())); values.put("/rankGroups", MAPPER.valueToTree(result.rankGroups()));
        values.put("/architecture/status", MAPPER.valueToTree(result.architecture().status()));
        values.put("/architecture/apiProtection", MAPPER.valueToTree(result.architecture().apiProtection()));
        result.architecture().patterns().forEach(p -> values.put("/architecture/patterns/" + escape(p.choice().id()), MAPPER.valueToTree(p)));
        result.architecture().provisioning().forEach(p -> values.put("/architecture/provisioning/" + escape(p.id()), MAPPER.valueToTree(p)));
        values.put("/limitations", MAPPER.valueToTree(result.limitations()));
        values.put("/followUps", MAPPER.valueToTree(result.followUps())); values.put("/deferredBoundaries", MAPPER.valueToTree(result.deferredBoundaries()));
        return values;
    }
    /** Outcome projection deliberately excludes evidence identity, dates, conditions and binding metadata. */
    private static JsonNode outcomes(CandidateDecisionEvaluator.Result result) {
        var tree = MAPPER.createObjectNode(); tree.put("status", result.status().name());
        tree.set("shortlist", MAPPER.valueToTree(result.shortlist())); tree.set("rankGroups", MAPPER.valueToTree(result.rankGroups()));
        var candidates = tree.putObject("candidates");
        result.candidates().forEach(c -> {
            var item = candidates.putObject(c.hardChecks().optionId()); item.put("hardVerdict", c.hardChecks().hardVerdict().name());
            var findings = item.putObject("findings"); c.hardChecks().findings().forEach(f -> findings.put(f.checkId(), f.outcome().name()));
            if (c.score() != null) {
                var score = item.putObject("score"); score.put("lower", c.score().lowerBound()); score.put("upper", c.score().upperBound());
                score.put("unknownWeight", c.score().unknownWeight());
                var contributions = score.putObject("contributions"); c.score().contributions().forEach(v -> contributions.put(v.capability().name(), v.outcome().name()));
            }
        });
        var architecture = tree.putObject("architecture"); architecture.put("status", result.architecture().status().name());
        architecture.put("api", result.architecture().apiProtection().status().name());
        addChoices(architecture, "patterns", result.architecture().patterns().stream().map(CandidateDecisionEvaluator.PatternAdvice::choice).toList());
        addChoices(architecture, "provisioning", result.architecture().provisioning());
        var api = architecture.putObject("apiOptions"); result.architecture().apiProtection().optionChecks().forEach(o -> api.put(o.optionId(), o.match().name()));
        return tree;
    }
    private static void addChoices(tools.jackson.databind.node.ObjectNode root, String name, List<CandidateDecisionEvaluator.Choice> choices) {
        var values = root.putObject(name); choices.forEach(c -> {
            var item = values.putObject(c.id()); item.put("disposition", c.disposition().name());
            item.set("conditionalOptionIds", MAPPER.valueToTree(c.conditionalOptionIds()));
            var options = item.putObject("options"); c.optionChecks().forEach(o -> options.put(o.optionId(), o.match().name()));
        });
    }
    private static String escape(String value) { return value.replace("~", "~0").replace("/", "~1"); }
}

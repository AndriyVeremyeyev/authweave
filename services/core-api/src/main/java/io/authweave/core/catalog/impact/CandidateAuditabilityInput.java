package io.authweave.core.catalog.impact;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.domain.profile.AuditabilityRequirements.Criterion;
import io.authweave.core.catalog.AuditabilityFacts;
import io.authweave.core.catalog.draft.AuditabilityCatalogDraft;
import io.authweave.core.catalog.draft.CatalogAuditabilityDraftValidator;
import io.authweave.core.catalog.draft.CatalogDraftValidator;
import io.authweave.core.catalog.draft.ProviderCatalogDraft;
import io.authweave.core.evaluation.AuditabilityEvaluator;
import io.authweave.core.evaluation.CapabilityPreflight.Outcome;
import io.authweave.core.evaluation.EvidencePolicy;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static io.authweave.core.catalog.impact.CandidateHardConstraintEvaluator.*;

/** Exact supplement input; source assertions alone are calculation hypotheses, never review authority. */
public record CandidateAuditabilityInput(String candidateSha256, JsonNode supplement, List<FactAssertion> assertions) {
    public static final String VERSION = "decision-auditability-input-1";
    private static final JsonMapper MAPPER = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    public CandidateAuditabilityInput {
        digest(candidateSha256); supplement = Objects.requireNonNull(supplement).deepCopy(); assertions = List.copyOf(assertions);
    }
    @Override public JsonNode supplement() { return supplement.deepCopy(); }
    public record FactAssertion(String optionId, Criterion criterion, String claimSha256, Assertion assertion) {
        public FactAssertion { Objects.requireNonNull(optionId); Objects.requireNonNull(criterion); digest(claimSha256); Objects.requireNonNull(assertion); }
    }
    public record Address(String optionId, Criterion criterion) { }
    record Bound(String supplementSha256, String assertionsSha256, Map<String, AuditabilityCatalogDraft.ScopedOption> options,
            Map<Address, Assertion> assertions, Map<Address, String> claimDigests) {
        List<Finding> findings(ApplicationIdentityProfile profile, ProviderCatalogDraft.Option option, Instant at) {
            var scope = new AuditabilityFacts.Scope(option.id(), option.plan(), option.region(), option.configuration());
            var scoped = AuditabilityEvaluator.evaluate(profile.security().auditability(), profile.security().auditabilityRequirements(), scope, List.of(), at);
            return scoped.checks().stream().map(check -> {
                var fact = options.get(option.id()).facts().stream().filter(f -> f.criterion() == check.criterion()).findFirst().orElse(null);
                var assertion = assertions.get(new Address(option.id(), check.criterion()));
                var evidence = fact == null ? null : new Evidence(claimDigests.get(new Address(option.id(), check.criterion())), assertion,
                        fact.evidence().sourceUrl(), fact.evidence().observedAt(), fact.conditions(), fact.documentedMinimumRetentionDays());
                var outcome = check.outcome(); var reason = check.reasonCode().name();
                // Only EVIDENCE_MISSING here represents an active selected required criterion.
                if (check.reasonCode() == AuditabilityEvaluator.Reason.EVIDENCE_MISSING && fact != null) {
                    reason = assertion == null ? "EVIDENCE_UNREVIEWED" : assertion == Assertion.SOURCE_DOES_NOT_SUPPORT_CLAIM ? "EVIDENCE_CONTRADICTED"
                            : assertion == Assertion.INSUFFICIENT_EVIDENCE ? "EVIDENCE_INSUFFICIENT"
                            : fact.evidence().observedAt().isAfter(at) ? "EVIDENCE_FUTURE"
                            : fact.evidence().observedAt().isBefore(at.minus(EvidencePolicy.MAX_AGE)) ? "EVIDENCE_STALE" : null;
                    if (reason != null) outcome = Outcome.UNKNOWN;
                    else {
                        var claim = AuditabilityEvaluator.documentedClaim(check.criterion(), fact.support(), fact.documentedMinimumRetentionDays(),
                                check.criterion() == Criterion.AUDIT_LOG_RETENTION ? profile.security().auditabilityRequirements().minimumRetentionDays() : null);
                        outcome = claim.outcome(); reason = claim.reasonCode().name();
                    }
                }
                return new Finding("security.auditability|" + check.criterion(), "security.auditability", "auditabilitySupplement." + check.criterion(),
                        profile.security().auditability().name(), outcome, reason, evidence);
            }).toList();
        }
    }
    Bound bind(JsonNode candidate, ProviderCatalogDraft base, Instant at) {
        if (!candidateSha256.equals(DecisionCanonicalizer.sha256(candidate))) throw new IllegalArgumentException("Auditability input belongs to another exact candidate");
        var raw = supplement();
        var typed = MAPPER.treeToValue(raw, AuditabilityCatalogDraft.class);
        var clock = Clock.fixed(at, ZoneOffset.UTC);
        var validation = new CatalogAuditabilityDraftValidator(clock, new CatalogDraftValidator(clock))
                .validateAt(new CatalogAuditabilityDraftValidator.Request(base, typed), at);
        if (validation.status() != CatalogAuditabilityDraftValidator.Status.VALID_DRAFT) throw new IllegalArgumentException("Invalid or wrongly scoped auditability supplement");
        var options = new HashMap<String, AuditabilityCatalogDraft.ScopedOption>();
        typed.options().forEach(o -> options.put(o.scope().optionId(), o));
        var known = claimDigests(raw);
        var bound = new HashMap<Address, Assertion>();
        for (var assertion : assertions) {
            var address = new Address(assertion.optionId(), assertion.criterion());
            if (!assertion.claimSha256().equals(known.get(address)) || bound.putIfAbsent(address, assertion.assertion()) != null)
                throw new IllegalArgumentException("Unknown, changed or duplicate auditability source assertion");
        }
        return new Bound(DecisionCanonicalizer.sha256(raw), DecisionCanonicalizer.sha256(MAPPER.valueToTree(assertions)), Map.copyOf(options), Map.copyOf(bound), known);
    }
    public static String claimSha256(JsonNode supplement, String optionId, Criterion criterion) {
        var value = claimDigests(supplement).get(new Address(optionId, criterion));
        if (value == null) throw new IllegalArgumentException("Missing exact auditability claim");
        return value;
    }
    /** Hash the potentially large supplement once, not once per fact. */
    public static Map<Address, String> claimDigests(JsonNode supplement) {
        var digest = DecisionCanonicalizer.sha256(supplement); var result = new HashMap<Address, String>();
        for (var option : supplement.path("options")) {
            for (var fact : option.path("facts")) {
                var claim = MAPPER.createObjectNode(); claim.put("scope", "DECISION_AUDITABILITY_CLAIM_V1");
                claim.put("supplementSha256", digest); claim.set("optionScope", option.get("scope")); claim.set("fact", fact);
                var address = new Address(option.at("/scope/optionId").asText(), Criterion.valueOf(fact.path("criterion").asText()));
                if (result.putIfAbsent(address, DecisionCanonicalizer.sha256(claim)) != null) throw new IllegalArgumentException("Duplicate auditability claim");
            }
        }
        return Map.copyOf(result);
    }
    private static void digest(String value) { if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid input digest"); }
}

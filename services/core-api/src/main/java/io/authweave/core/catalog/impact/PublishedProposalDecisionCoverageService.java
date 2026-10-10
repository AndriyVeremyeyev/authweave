package io.authweave.core.catalog.impact;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import io.authweave.core.catalog.draft.AuditabilityCatalogDraft;
import io.authweave.core.catalog.draft.CatalogDraftFacts;
import io.authweave.core.catalog.draft.ProviderCatalogDraft;
import io.authweave.core.catalog.publication.CatalogBootstrapPublicationReader;
import io.authweave.core.catalog.publication.PublishedCatalogSnapshot;
import io.authweave.core.evaluation.EvidencePolicy;
import static io.authweave.core.catalog.impact.CandidateHardConstraintEvaluator.Assertion;

/** Fresh calculation inputs for a future successor publisher, not a transported approval token.
 * Original bootstrap coverage/proof formats remain unchanged. No HTTP, writes or head selection. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PublishedProposalDecisionCoverageService {
    public static final String VERSION = "publication-proposal-decision-coverage-1";
    public static final Map<String, String> COMPONENT_VERSIONS;
    static {
        var versions = new TreeMap<>(DecisionPublicationCoveragePolicy.COMPONENT_VERSIONS);
        versions.put("publishedBaselineLoading", CatalogBootstrapPublicationReader.VERSION);
        versions.put("proposalReviewLoading", StoredProposalDecisionService.VERSION);
        versions.put("publishedProposalImpact", StoredProposalDecisionImpactService.VERSION);
        versions.put("underlyingCoverage", DecisionPublicationCoveragePolicy.VERSION);
        COMPONENT_VERSIONS = Map.copyOf(versions);
    }
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final TrustedPublishedCatalogService published;
    private final StoredProposalDecisionService proposals;
    private final DecisionPublicationCoverageService coverage;
    private final DecisionPublicationCoveragePolicy policy;
    private final Clock clock;
    private final String manifestSha256;
    public PublishedProposalDecisionCoverageService(TrustedPublishedCatalogService published, StoredProposalDecisionService proposals,
            DecisionPublicationCoverageService coverage, DecisionPublicationCoveragePolicy policy, Clock clock) {
        this.published = published; this.proposals = proposals; this.coverage = coverage; this.policy = policy; this.clock = clock;
        manifestSha256 = hash(List.of(VERSION, DecisionPublicationCoveragePolicy.SCOPE, policy.manifestSha256(), COMPONENT_VERSIONS));
    }
    /** Completeness of documented claim reviews/dates, not supported features, PASS decisions or external source truth. */
    public record CandidateClaims(int recorded, int supporting, int contradicted, int insufficient, int unreviewed,
            int current, int stale, int future, boolean allRecordedClaimsSupportedAndCurrent) {
        public CandidateClaims {
            if (recorded < 1 || recorded > 7400 || supporting < 0 || contradicted < 0 || insufficient < 0 || unreviewed < 0
                    || current < 0 || stale < 0 || future < 0 || (long) supporting + contradicted + insufficient + unreviewed != recorded
                    || (long) current + stale + future != recorded
                    || allRecordedClaimsSupportedAndCurrent != (supporting == recorded && current == recorded))
                throw new IllegalArgumentException("Invalid recorded-claim eligibility");
        }
    }
    public record Check(String scope, String policyVersion, String canonicalization, Instant evaluatedAt,
            int profileSchemaVersion, String profileSchemaSha256, String decisionPolicySha256, String scenarioSetSha256,
            String manifestSha256, Map<String, String> componentVersions,
            PublishedCatalogSnapshot.Reference before, String beforeProofSha256, String beforeCatalogSha256,
            StoredProposalDecisionService.Reference after, String afterCatalogSha256,
            List<DecisionPublicationCoverageService.ScenarioCheck> scenarios, List<String> uncoveredFacts,
            List<CatalogProfilePlanningCoverageService.VerificationGap> verificationGaps, CandidateClaims candidateClaims,
            DecisionPublicationCoverageService.Status status, boolean decisionScopeCoverageComplete,
            boolean historicalPublicationWorkflowVerified, boolean storedSourceReviewsVerified,
            boolean coverageComplete, boolean currentCuratorAuthorityVerified, boolean sourceVerificationPerformed,
            boolean configurationVerified, boolean complianceVerified, boolean actualGoldenAcceptancePerformed,
            boolean historicalReportPromotionPerformed, boolean assessmentResultPinned, boolean approvalGranted,
            boolean publicationReady, boolean writesPerformed) {
        public Check {
            componentVersions = Map.copyOf(componentVersions); scenarios = List.copyOf(scenarios);
            uncoveredFacts = List.copyOf(uncoveredFacts); verificationGaps = List.copyOf(verificationGaps);
            var ids = scenarios.stream().map(DecisionPublicationCoverageService.ScenarioCheck::scenarioId).toList();
            boolean complete = uncoveredFacts.isEmpty() && scenarios.stream().allMatch(s -> s.routes().stream().allMatch(DecisionPublicationCoverageService.RouteCheck::accounted));
            if (!DecisionPublicationCoveragePolicy.SCOPE.equals(scope) || !VERSION.equals(policyVersion) || !DecisionCanonicalizer.VERSION.equals(canonicalization)
                    || evaluatedAt == null || profileSchemaVersion != 6 || !CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_SHA256.equals(profileSchemaSha256)
                    || !COMPONENT_VERSIONS.equals(componentVersions) || before == null || after == null || candidateClaims == null
                    || !ids.equals(CatalogScopedProfileCases.IDS.stream().sorted().toList())
                    || !new java.util.TreeSet<>(uncoveredFacts).stream().toList().equals(uncoveredFacts)
                    || !CatalogProfilePlanningCoverageService.VERIFICATION_GAPS.equals(verificationGaps)
                    || decisionScopeCoverageComplete != complete || status != (complete ? DecisionPublicationCoverageService.Status.COMPLETE_DECLARED_SCOPE : DecisionPublicationCoverageService.Status.INCOMPLETE)
                    || !historicalPublicationWorkflowVerified || !storedSourceReviewsVerified
                    || coverageComplete || currentCuratorAuthorityVerified || sourceVerificationPerformed || configurationVerified || complianceVerified
                    || actualGoldenAcceptancePerformed || historicalReportPromotionPerformed || assessmentResultPinned || approvalGranted || publicationReady || writesPerformed)
                throw new IllegalArgumentException("Invalid published-proposal coverage boundary");
            for (var digest : List.of(profileSchemaSha256, decisionPolicySha256, scenarioSetSha256, manifestSha256, beforeProofSha256, beforeCatalogSha256, afterCatalogSha256)) digest(digest);
        }
    }

    public Check inspect(PublishedCatalogSnapshot.Reference before, StoredProposalDecisionService.Reference after) {
        var baseline = published.load(Objects.requireNonNull(before)); var proposal = proposals.load(Objects.requireNonNull(after));
        return calculate(baseline, proposal, clock.instant());
    }
    /** Only internal publication/replay callers provide the server-owned time; there is no HTTP clock input. */
    public Check replay(PublishedCatalogSnapshot.Reference before, StoredProposalDecisionService.Reference after, Instant at) {
        var baseline = published.load(Objects.requireNonNull(before)); var proposal = proposals.load(Objects.requireNonNull(after));
        return calculate(baseline, proposal, Objects.requireNonNull(at));
    }
    private Check calculate(TrustedPublishedCatalogService.Inputs baseline, StoredProposalDecisionService.Inputs proposal, Instant at) {
        // Frozen policy 1 binds the bootstrap loader. Multihop publication requires a separately versioned policy.
        if (!CatalogBootstrapPublicationReader.VERSION.equals(baseline.publication().loaderVersion()))
            throw new IllegalArgumentException("Coverage policy 1 requires a verified bootstrap baseline");
        StoredProposalDecisionImpactService.requireExactBase(baseline, proposal);
        if (baseline.publication().snapshot().publication().publishedAt().isAfter(at.plusSeconds(30))
                || proposal.proposalRecordedAt().isAfter(at.plusSeconds(30))
                || proposal.observations().stream().anyMatch(r -> r.recordedAt().isAfter(at.plusSeconds(30)))
                || proposal.auditabilityReview() != null && proposal.auditabilityReview().recordedAt().isAfter(at.plusSeconds(30)))
            throw new IllegalArgumentException("Publication/proposal review is ahead of the server-owned replay clock");
        var a = baseline.decisionInputs(); var b = proposal.snapshot(); var execution = coverage.analyzeInputs(a, b, at);
        return new Check(DecisionPublicationCoveragePolicy.SCOPE, VERSION, DecisionCanonicalizer.VERSION, at, 6,
                CatalogAuditabilityRegressionCases.PROFILE_SCHEMA_SHA256, policy.decisionPolicySha256(), policy.scenarioSetSha256(), manifestSha256,
                COMPONENT_VERSIONS, baseline.publication().reference(), baseline.publication().proofSha256(), DecisionCanonicalizer.sha256(a.catalog()),
                proposal.reference(), DecisionCanonicalizer.sha256(b.catalog()), execution.scenarios(), execution.uncoveredFacts(),
                CatalogProfilePlanningCoverageService.VERIFICATION_GAPS, claims(b, at), execution.status(), execution.complete(), true, true,
                false, false, false, false, false, false, false, false, false, false, false);
    }
    static CandidateClaims claims(CandidateDecisionImpactEvaluator.Snapshot snapshot, Instant at) {
        var counts = new int[8]; var assertions = new java.util.HashMap<String, Assertion>();
        snapshot.assertions().facts().forEach(f -> assertions.put(f.optionId() + "/" + f.factPath(), f.assertion()));
        MAPPER.treeToValue(snapshot.catalog(), ProviderCatalogDraft.class).options().forEach(o -> CatalogDraftFacts.entries(o)
                .forEach((path, fact) -> count(counts, assertions.get(o.id() + "/" + path), fact.evidence().observedAt(), at)));
        var audit = snapshot.auditability();
        if (audit != null) {
            var supplemental = new java.util.HashMap<CandidateAuditabilityInput.Address, Assertion>();
            audit.assertions().forEach(f -> supplemental.put(new CandidateAuditabilityInput.Address(f.optionId(), f.criterion()), f.assertion()));
            MAPPER.treeToValue(audit.supplement(), AuditabilityCatalogDraft.class).options().forEach(o -> o.facts().forEach(f ->
                    count(counts, supplemental.get(new CandidateAuditabilityInput.Address(o.scope().optionId(), f.criterion())), f.evidence().observedAt(), at)));
        }
        return new CandidateClaims(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], counts[6], counts[7], counts[1] == counts[0] && counts[5] == counts[0]);
    }
    private static void count(int[] n, Assertion assertion, Instant observedAt, Instant at) {
        n[0]++; n[assertion == null ? 4 : switch (assertion) { case SOURCE_SUPPORTS_CLAIM -> 1; case SOURCE_DOES_NOT_SUPPORT_CLAIM -> 2; case INSUFFICIENT_EVIDENCE -> 3; }]++;
        n[observedAt.isAfter(at) ? 7 : observedAt.isBefore(at.minus(EvidencePolicy.MAX_AGE)) ? 6 : 5]++;
    }
    private static String hash(Object value) { return DecisionCanonicalizer.sha256(MAPPER.valueToTree(value)); }
    private static void digest(String value) { if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Use an exact coverage digest"); }
}

package io.authweave.core.evaluation;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.assessment.application.AssessmentApplicationService;
import io.authweave.core.assessment.domain.*;
import io.authweave.core.assessment.domain.profile.*;
import io.authweave.core.assessment.persistence.PersistedAssessment;
import io.authweave.core.catalog.*;
import static io.authweave.core.evaluation.CapabilityPreflight.Outcome.*;
import static io.authweave.core.evaluation.HardConstraintPreflight.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditabilityConstraintTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private static final Instant AT = Instant.parse("2026-09-12T12:00:00Z");
    private static final UUID WORKSPACE = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID ASSESSMENT = UUID.fromString("80000000-0000-4000-8000-000000000001");
    private <T> T resource(String name, Class<T> type) throws Exception {
        try (var input = new ClassPathResource("catalog/" + name).getInputStream()) { return mapper.readValue(input, type); }
    }
    private EligibilityPreflightV4 baseline(CapabilityPreflight.Status status) throws Exception {
        var base = resource("synthetic.v4.json", ProviderCatalog.class);
        var candidates = base.options().stream().map(option -> {
            var checks = status == CapabilityPreflight.Status.NEEDS_INFORMATION ? List.<CapabilityPreflight.Check>of()
                    : List.of(new CapabilityPreflight.Check(ProviderCatalog.Capability.OIDC, "protocols.oidc",
                        RequirementCriticality.REQUIRED, status == CapabilityPreflight.Status.DOES_NOT_MATCH ? FAIL : PASS,
                        status == CapabilityPreflight.Status.DOES_NOT_MATCH ? CapabilityPreflight.Reason.REQUIRED_CAPABILITY_UNAVAILABLE
                            : CapabilityPreflight.Reason.REQUIRED_CAPABILITY_AVAILABLE, "Synthetic OIDC check.", null),
                        new CapabilityPreflight.Check(ProviderCatalog.Capability.SOCIAL_LOGIN, "protocols.socialLogin",
                            RequirementCriticality.PREFERRED, NOT_APPLIED, CapabilityPreflight.Reason.PREFERENCE_NOT_SCORED,
                            "Preference remains separate.", option.facts().get(ProviderCatalog.Capability.SOCIAL_LOGIN)));
            return new EligibilityPreflightV3.Candidate(option.id(), option.displayName(), option.plan(), option.region(),
                    status, checks, List.of(), List.of(), List.of());
        }).toList();
        return new EligibilityPreflightV4(WORKSPACE, ASSESSMENT, 7, base.catalogVersion(), base.kind(),
                EligibilityEvaluator.COMPLIANCE_SCOPE_POLICY_VERSION, CapabilityEvaluator.POLICY_VERSION,
                EligibilityEvaluator.POLICY_VERSION, ResidencyEvaluator.POLICY_VERSION,
                AuthenticationControlEvaluator.POLICY_VERSION, ComplianceScopeEvaluator.POLICY_VERSION,
                SecurityRequirements.AssuranceLevel.UNKNOWN, AT, "SYNTHETIC_ELIGIBILITY_PREFLIGHT", false,
                EligibilityEvaluator.RESIDENCY_DEFERRED_PATHS,
                new ComplianceScopeCheck("security.complianceScopeStatus", ComplianceScopeStatus.NONE_IDENTIFIED,
                    List.of(), NOT_APPLIED, ComplianceScopeCheck.Reason.NO_COMPLIANCE_TARGETS_IDENTIFIED,
                    "No identified targets; no compliance verification.", false), candidates);
    }
    private AuditabilityCapabilityPreflight audit(RequirementCriticality criticality,
            AuditabilityRequirements requirements, Instant at) throws Exception {
        var base = resource("synthetic.v4.json", ProviderCatalog.class);
        var evidence = resource("auditability-evidence.v1.json", AuditabilityCatalog.class);
        var candidates = evidence.options().stream().map(option -> new AuditabilityCapabilityPreflight.Candidate(
                base.options().stream().filter(o -> o.id().equals(option.scope().optionId())).findFirst().orElseThrow().displayName(),
                AuditabilityEvaluator.evaluate(criticality, requirements, option.scope(), option.facts(), at), option.facts())).toList();
        return new AuditabilityCapabilityPreflight(WORKSPACE, ASSESSMENT, 7, base.catalogVersion(), evidence.evidenceVersion(),
                base.kind(), at, criticality, requirements, candidates);
    }
    private static AuditabilityRequirements all(int retention) {
        return new AuditabilityRequirements(Set.copyOf(Arrays.asList(AuditabilityRequirements.Criterion.values())), retention);
    }
    @Test void auditFailuresExcludeAndKeepUnknownsWhilePreferencesCannotRescueThem() throws Exception {
        var snapshot = new AuditabilityConstraintSnapshot(baseline(CapabilityPreflight.Status.MATCHES_CHECKED_REQUIREMENTS),
                audit(RequirementCriticality.REQUIRED, all(30), AT));
        var hard = HardConstraintPreflight.from(snapshot);
        assertEquals(HardConstraintPreflight.AUDITABILITY_POLICY_VERSION, hard.policyVersion());
        assertEquals(List.of(PASSES_CHECKED_REQUIREMENTS, EXCLUDED, UNRESOLVED), hard.candidates().stream().map(HardConstraintPreflight.Candidate::verdict).toList());
        var mixed = hard.candidates().get(1);
        assertEquals(List.of("CAPABILITY_UNAVAILABLE", "RETENTION_BELOW_MINIMUM"), mixed.exclusionReasons().stream().map(HardConstraintPreflight.Finding::reasonCode).toList());
        assertEquals("EVIDENCE_MISSING", mixed.informationGaps().getFirst().reasonCode());
        assertTrue(mixed.exclusionReasons().get(1).explanation().contains("requested 30 days"));
        assertTrue(mixed.exclusionReasons().stream().allMatch(f -> f.dimension() == HardConstraintPreflight.Dimension.AUDITABILITY));
        var comparison = SyntheticComparison.from(snapshot);
        assertEquals(SyntheticComparison.AUDITABILITY_POLICY_VERSION, comparison.policyVersion());
        assertSame(snapshot.auditability(), comparison.auditability());
        assertFalse(comparison.recommendationReady()); assertFalse(comparison.rankingPerformed());
        var request = new WeightedComparisonRequest(Map.of(ProviderCatalog.Capability.SOCIAL_LOGIN, 100));
        var weighted = WeightedComparisonPreview.from(comparison, request);
        assertEquals(WeightedComparisonPreview.ScoreStatus.EXCLUDED, weighted.scores().get(1).status());
        assertNull(weighted.scores().get(1).score()); assertTrue(weighted.scores().get(1).contributions().isEmpty());
        assertEquals(WeightedComparisonPreview.ScoreStatus.UNRESOLVED_HARD_CONSTRAINTS, weighted.scores().get(2).status());
        var sensitivity = WeightedSensitivityPreview.from(comparison, new WeightedSensitivityRequest(request.weights(), request.weights()));
        assertNull(sensitivity.deltas().get(1).scoreDelta()); assertTrue(sensitivity.deltas().get(1).capabilityDeltas().isEmpty());
    }
    @ParameterizedTest @EnumSource(RequirementCriticality.class)
    void criticalityAndEmptyScopeAreNeverInvented(RequirementCriticality criticality) throws Exception {
        var snapshot = new AuditabilityConstraintSnapshot(baseline(CapabilityPreflight.Status.MATCHES_CHECKED_REQUIREMENTS),
                audit(criticality, AuditabilityRequirements.unspecified(), AT));
        var expected = criticality == RequirementCriticality.PREFERRED || criticality == RequirementCriticality.NOT_REQUIRED
                ? PASSES_CHECKED_REQUIREMENTS : UNRESOLVED;
        var hard = HardConstraintPreflight.from(snapshot);
        assertTrue(hard.candidates().stream().allMatch(c -> c.verdict() == expected && c.exclusionReasons().isEmpty()));
        if (criticality == RequirementCriticality.REQUIRED)
            assertTrue(hard.candidates().stream().flatMap(c -> c.informationGaps().stream()).allMatch(f -> f.reasonCode().equals("AUDIT_SCOPE_UNKNOWN")));
    }
    @Test void auditPassCanSupplyAnAffirmativeCheckButCannotEraseAnotherFailure() throws Exception {
        var audit = audit(RequirementCriticality.REQUIRED, all(30), AT);
        var empty = HardConstraintPreflight.from(new AuditabilityConstraintSnapshot(baseline(CapabilityPreflight.Status.NEEDS_INFORMATION), audit));
        assertEquals(PASSES_CHECKED_REQUIREMENTS, empty.candidates().getFirst().verdict());
        assertTrue(empty.candidates().getFirst().informationGaps().isEmpty());
        var excluded = HardConstraintPreflight.from(new AuditabilityConstraintSnapshot(baseline(CapabilityPreflight.Status.DOES_NOT_MATCH), audit));
        assertTrue(excluded.candidates().stream().allMatch(c -> c.verdict() == EXCLUDED));
        assertEquals(HardConstraintPreflight.Dimension.CAPABILITY, excluded.candidates().getFirst().exclusionReasons().getFirst().dimension());
        var notApplied = HardConstraintPreflight.from(new AuditabilityConstraintSnapshot(baseline(CapabilityPreflight.Status.NEEDS_INFORMATION),
                audit(RequirementCriticality.NOT_REQUIRED, all(30), AT)));
        assertEquals(UNRESOLVED, notApplied.candidates().getFirst().verdict());
        assertEquals("NO_AFFIRMATIVE_CHECKS", notApplied.candidates().getFirst().informationGaps().getFirst().reasonCode());
    }
    @ParameterizedTest @ValueSource(ints = { 1, 7, 8, 89, 90, 91, 180 })
    void retentionUsesExplicitThresholdOnly(int requested) throws Exception {
        var requirements = new AuditabilityRequirements(Set.of(AuditabilityRequirements.Criterion.AUDIT_LOG_RETENTION), requested);
        var result = HardConstraintPreflight.from(new AuditabilityConstraintSnapshot(baseline(CapabilityPreflight.Status.MATCHES_CHECKED_REQUIREMENTS),
                audit(RequirementCriticality.REQUIRED, requirements, AT)));
        var original = result.auditability();
        for (int i = 0; i < result.candidates().size(); i++) {
            var analysis = original.candidates().get(i).analysis();
            assertEquals(analysis.status() == AuditabilityEvaluator.Status.DOES_NOT_MATCH ? EXCLUDED :
                    analysis.status() == AuditabilityEvaluator.Status.NEEDS_INFORMATION ? UNRESOLVED : PASSES_CHECKED_REQUIREMENTS,
                    result.candidates().get(i).verdict());
            assertTrue(result.candidates().get(i).exclusionReasons().stream().allMatch(f -> f.explanation().startsWith("AUDIT_LOG_RETENTION:")));
        }
    }
    @Test void snapshotRejectsForeignStaleAndAmbiguousBindings() throws Exception {
        var source = baseline(CapabilityPreflight.Status.MATCHES_CHECKED_REQUIREMENTS);
        var audit = audit(RequirementCriticality.REQUIRED, all(30), AT);
        for (String field : List.of("workspaceId", "assessmentId", "assessmentVersion", "baseCatalogVersion")) {
            var changed = (ObjectNode) mapper.valueToTree(audit);
            if (field.equals("assessmentVersion")) changed.put(field, 8);
            else changed.put(field, field.equals("baseCatalogVersion") ? "synthetic-foreign" : UUID.randomUUID().toString());
            var rebound = mapper.treeToValue(changed, AuditabilityCapabilityPreflight.class);
            assertThrows(IllegalArgumentException.class, () -> new AuditabilityConstraintSnapshot(source, rebound));
        }
        assertThrows(IllegalArgumentException.class, () -> new AuditabilityConstraintSnapshot(source,
                audit(RequirementCriticality.REQUIRED, all(30), AT.plusNanos(1))));
        var wrongDisplay = new java.util.ArrayList<>(audit.candidates());
        var first = wrongDisplay.getFirst();
        wrongDisplay.set(0, new AuditabilityCapabilityPreflight.Candidate("Foreign display", first.analysis(), first.evidence()));
        assertThrows(IllegalArgumentException.class, () -> new AuditabilityConstraintSnapshot(source,
                new AuditabilityCapabilityPreflight(WORKSPACE, ASSESSMENT, 7, audit.baseCatalogVersion(), audit.evidenceVersion(),
                    audit.catalogKind(), AT, audit.criticality(), audit.requirements(), wrongDisplay)));
        var scope = first.analysis().optionScope();
        var alternate = new AuditabilityFacts.Scope(scope.optionId(), scope.plan(), scope.region(), "Different configuration");
        var duplicate = new java.util.ArrayList<>(audit.candidates());
        duplicate.set(1, new AuditabilityCapabilityPreflight.Candidate(first.displayName(),
                AuditabilityEvaluator.evaluate(audit.criticality(), audit.requirements(), alternate, List.of(), AT), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AuditabilityConstraintSnapshot(source,
                new AuditabilityCapabilityPreflight(WORKSPACE, ASSESSMENT, 7, audit.baseCatalogVersion(), audit.evidenceVersion(),
                    audit.catalogKind(), AT, audit.criticality(), audit.requirements(), duplicate)));
    }
    @ParameterizedTest @ValueSource(ints = { -1, 0, 90, 91 })
    void serviceReadsOnceAndNeverRefreshesEvidenceOrWrites(int days) throws Exception {
        var id = new AssessmentId(ASSESSMENT); var workspace = new WorkspaceId(WORKSPACE);
        var draft = Assessment.createDraft(id, workspace);
        var profile = (ObjectNode) mapper.valueToTree(draft.profile()); var security = (ObjectNode) profile.get("security");
        security.put("auditability", "REQUIRED"); security.set("auditabilityRequirements", mapper.valueToTree(all(30)));
        draft.updateProfile(mapper.treeToValue(profile, ApplicationIdentityProfile.class));
        var before = draft.profile(); var assessments = mock(AssessmentApplicationService.class);
        when(assessments.getAssessment(workspace, id)).thenReturn(new PersistedAssessment(draft, 7, AT, AT));
        var at = AT.plusSeconds(days * 86400L);
        var service = new EligibilityPreflightService(assessments, resource("synthetic.v4.json", ProviderCatalog.class),
                resource("auditability-evidence.v1.json", AuditabilityCatalog.class), Clock.fixed(at, ZoneOffset.UTC));
        var result = service.previewWithAuditability(workspace, id);
        assertEquals(at, result.eligibility().evaluatedAt()); assertEquals(at, result.auditability().evaluatedAt());
        assertEquals(7, result.eligibility().assessmentVersion()); assertEquals(before, draft.profile());
        assertEquals(AT, result.auditability().candidates().getFirst().evidence().getFirst().observedAt());
        if (days < 0 || days > 90) assertTrue(result.auditability().candidates().getFirst().analysis().checks().stream()
                .allMatch(c -> c.reasonCode() == (days < 0 ? AuditabilityEvaluator.Reason.EVIDENCE_FROM_FUTURE : AuditabilityEvaluator.Reason.EVIDENCE_STALE)));
        verify(assessments).getAssessment(workspace, id); verifyNoMoreInteractions(assessments);
    }
}

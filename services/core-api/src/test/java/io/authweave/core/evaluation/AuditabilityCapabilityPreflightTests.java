package io.authweave.core.evaluation;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import io.authweave.core.assessment.application.AssessmentApplicationService;
import io.authweave.core.assessment.domain.*;
import io.authweave.core.assessment.domain.profile.*;
import io.authweave.core.assessment.persistence.PersistedAssessment;
import io.authweave.core.catalog.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditabilityCapabilityPreflightTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Instant at = Instant.parse("2026-09-12T12:00:00Z");
    private final WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());
    private final AssessmentId id = new AssessmentId(UUID.randomUUID());
    private <T> T resource(String name, Class<T> type) throws Exception {
        try (var input = new ClassPathResource("catalog/" + name).getInputStream()) { return mapper.readValue(input, type); }
    }
    private Assessment assessment(boolean recorded) {
        var draft = Assessment.createDraft(id, workspace);
        if (recorded) {
            var profile = (ObjectNode) mapper.valueToTree(draft.profile()); var security = (ObjectNode) profile.get("security");
            security.put("auditability", "REQUIRED"); var requirements = security.putObject("auditabilityRequirements");
            requirements.putArray("selectedCriteria").add("AUTHENTICATION_SUCCESS_EVENTS").add("AUTHENTICATION_FAILURE_EVENTS")
                    .add("ADMINISTRATIVE_CHANGE_EVENTS").add("PROVISIONING_CHANGE_EVENTS").add("AUDIT_LOG_EXPORT").add("AUDIT_LOG_RETENTION");
            requirements.put("minimumRetentionDays", 30); draft.updateProfile(mapper.treeToValue(profile, ApplicationIdentityProfile.class));
        }
        return draft;
    }
    @Test void legacyPreviewKeepsScopeUnknownAndOnlyReadsTheAssessment() throws Exception {
        var draft = assessment(false); var service = mock(AssessmentApplicationService.class);
        when(service.getAssessment(workspace, id)).thenReturn(new PersistedAssessment(draft, 7, at, at));
        var result = new AuditabilityCapabilityPreflightService(service, resource("synthetic.v4.json", ProviderCatalog.class),
                resource("auditability-evidence.v1.json", AuditabilityCatalog.class), Clock.fixed(at, ZoneOffset.UTC)).preview(workspace, id);
        assertEquals(7, result.assessmentVersion()); assertEquals(workspace.value(), result.workspaceId()); assertEquals(id.value(), result.assessmentId());
        assertFalse(result.sourceVerificationPerformed()); assertFalse(result.recommendationReady());
        assertEquals(AuditabilityRequirements.unspecified(), result.requirements());
        assertTrue(result.candidates().stream().allMatch(candidate -> candidate.analysis().status() == AuditabilityEvaluator.Status.NEEDS_INFORMATION));
        verify(service).getAssessment(workspace, id); verifyNoMoreInteractions(service);
        assertEquals(ApplicationIdentityProfile.unknown(), draft.profile());
    }
    @ParameterizedTest @ValueSource(ints = { -1, 0, 90, 91 })
    void datedEvidenceIsNotRefreshedByAReadAndUnreviewedFactsNeverEstablishSupport(int offsetDays) throws Exception {
        var service = mock(AssessmentApplicationService.class);
        when(service.getAssessment(workspace, id)).thenReturn(new PersistedAssessment(assessment(true), 7, at, at));
        var evidence = resource("auditability-evidence.v1.json", AuditabilityCatalog.class);
        var result = new AuditabilityCapabilityPreflightService(service, resource("synthetic.v4.json", ProviderCatalog.class), evidence,
                Clock.fixed(at.plusSeconds(offsetDays * 86400L), ZoneOffset.UTC)).preview(workspace, id);
        var complete = result.candidates().getFirst().analysis();
        assertEquals(offsetDays < 0 || offsetDays > 90 ? AuditabilityEvaluator.Status.NEEDS_INFORMATION :
                AuditabilityEvaluator.Status.MATCHES_CHECKED_REQUIREMENTS, complete.status());
        assertTrue(complete.checks().stream().allMatch(check -> offsetDays < 0 ? check.reasonCode() == AuditabilityEvaluator.Reason.EVIDENCE_FROM_FUTURE :
                offsetDays > 90 ? check.reasonCode() == AuditabilityEvaluator.Reason.EVIDENCE_STALE : check.outcome() == CapabilityPreflight.Outcome.PASS));
        assertTrue(result.candidates().get(2).analysis().checks().stream().allMatch(check -> check.reasonCode() == AuditabilityEvaluator.Reason.EVIDENCE_UNREVIEWED));
        assertEquals(at, result.candidates().getFirst().evidence().getFirst().observedAt());
        assertEquals(7, result.assessmentVersion()); verify(service).getAssessment(workspace, id); verifyNoMoreInteractions(service);
    }
    @Test void candidateRejectsBorrowedEvidenceAndTopLevelRejectsMismatchedRequirements() throws Exception {
        var evidence = resource("auditability-evidence.v1.json", AuditabilityCatalog.class);
        var first = evidence.options().getFirst(); var requirements = new AuditabilityRequirements(Set.of(AuditabilityRequirements.Criterion.AUDIT_LOG_RETENTION), 30);
        var analysis = AuditabilityEvaluator.evaluate(RequirementCriticality.REQUIRED, requirements, first.scope(), first.facts(), at);
        assertThrows(IllegalArgumentException.class, () -> new AuditabilityCapabilityPreflight.Candidate("Fictional", analysis, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AuditabilityCapabilityPreflight.Candidate("Fictional", analysis, evidence.options().get(1).facts()));
        var candidate = new AuditabilityCapabilityPreflight.Candidate("Fictional", analysis, first.facts());
        assertThrows(IllegalArgumentException.class, () -> new AuditabilityCapabilityPreflight(workspace.value(), id.value(), 7,
                evidence.baseCatalogVersion(), evidence.evidenceVersion(), ProviderCatalog.Kind.SYNTHETIC, at, RequirementCriticality.UNKNOWN,
                requirements, List.of(candidate)));
    }
}

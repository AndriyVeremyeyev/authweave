package io.authweave.core.assessment.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.authweave.core.assessment.domain.Assessment;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.persistence.AssessmentNotFoundException;
import io.authweave.core.assessment.persistence.AssessmentRepository;
import io.authweave.core.assessment.persistence.PersistedAssessment;
import io.authweave.core.assessment.persistence.WorkspaceRepository;

@Service
public class AssessmentApplicationService {

    private final AssessmentRepository assessmentRepository;
    private final WorkspaceRepository workspaceRepository;

    public AssessmentApplicationService(
            AssessmentRepository assessmentRepository,
            WorkspaceRepository workspaceRepository) {
        this.assessmentRepository = assessmentRepository;
        this.workspaceRepository = workspaceRepository;
    }

    @Transactional
    public boolean provisionWorkspace(WorkspaceId workspaceId) {
        return workspaceRepository.insertIfAbsent(workspaceId);
    }

    @Transactional
    public PersistedAssessment createAssessment(
            WorkspaceId workspaceId,
            AssessmentId assessmentId) {
        requireWorkspace(workspaceId);
        return assessmentRepository.insert(Assessment.createDraft(assessmentId, workspaceId));
    }

    @Transactional(readOnly = true)
    public PersistedAssessment getAssessment(
            WorkspaceId workspaceId,
            AssessmentId assessmentId) {
        return assessmentRepository.findById(workspaceId, assessmentId)
                .orElseThrow(() -> new AssessmentNotFoundException(workspaceId, assessmentId));
    }

    @Transactional
    public PersistedAssessment updateProfile(
            WorkspaceId workspaceId,
            AssessmentId assessmentId,
            long expectedVersion,
            ApplicationIdentityProfile profile) {
        PersistedAssessment persisted = getAssessment(workspaceId, assessmentId);
        persisted.assessment().updateProfile(profile);
        return assessmentRepository.update(persisted.assessment(), expectedVersion);
    }

    private void requireWorkspace(WorkspaceId workspaceId) {
        if (!workspaceRepository.exists(workspaceId)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
    }
}

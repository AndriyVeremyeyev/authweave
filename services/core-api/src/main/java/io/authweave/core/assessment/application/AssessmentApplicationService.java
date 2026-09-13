package io.authweave.core.assessment.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.authweave.core.assessment.domain.Assessment;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import io.authweave.core.assessment.persistence.AssessmentNotFoundException;
import io.authweave.core.assessment.persistence.AssessmentEvent;
import io.authweave.core.assessment.persistence.AssessmentHistoryRepository;
import io.authweave.core.assessment.persistence.AssessmentRevision;
import io.authweave.core.assessment.persistence.HistoryPage;
import io.authweave.core.assessment.persistence.AssessmentRepository;
import io.authweave.core.assessment.persistence.AssessmentVersionConflictException;
import io.authweave.core.assessment.persistence.PersistedAssessment;
import io.authweave.core.assessment.persistence.WorkspaceRepository;

@Service
public class AssessmentApplicationService {

    private final AssessmentRepository assessmentRepository;
    private final WorkspaceRepository workspaceRepository;
    private final AssessmentHistoryRepository historyRepository;

    public AssessmentApplicationService(
            AssessmentRepository assessmentRepository,
            WorkspaceRepository workspaceRepository,
            AssessmentHistoryRepository historyRepository) {
        this.assessmentRepository = assessmentRepository;
        this.workspaceRepository = workspaceRepository;
        this.historyRepository = historyRepository;
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
        return updateProfile(workspaceId, assessmentId, expectedVersion, profile, false);
    }

    @Transactional
    public PersistedAssessment updateLegacyProfile(WorkspaceId workspaceId, AssessmentId assessmentId,
            long expectedVersion, ApplicationIdentityProfile profile) {
        return updateProfile(workspaceId, assessmentId, expectedVersion, profile, true);
    }

    private PersistedAssessment updateProfile(WorkspaceId workspaceId, AssessmentId assessmentId,
            long expectedVersion, ApplicationIdentityProfile profile, boolean legacy) {
        PersistedAssessment persisted = getAssessment(workspaceId, assessmentId);
        if (persisted.version() != expectedVersion) {
            throw new AssessmentVersionConflictException(
                    workspaceId, assessmentId, expectedVersion, persisted.version());
        }
        ApplicationIdentityProfile previousProfile = persisted.assessment().profile();
        if (legacy && !previousProfile.security().dataResidencyDetails().isUnrecorded()) {
            throw new ProfileUpgradeRequiredException();
        }
        persisted.assessment().updateProfile(profile);
        if (previousProfile.equals(profile)) {
            return persisted;
        }
        return assessmentRepository.update(persisted.assessment(), expectedVersion);
    }

    @Transactional(readOnly = true)
    public HistoryPage<AssessmentRevision> getRevisions(
            WorkspaceId workspaceId, AssessmentId assessmentId, Long afterVersion, int limit) {
        requireAssessment(workspaceId, assessmentId);
        return historyRepository.findRevisions(workspaceId, assessmentId, afterVersion, limit);
    }

    @Transactional(readOnly = true)
    public HistoryPage<AssessmentEvent> getEvents(
            WorkspaceId workspaceId, AssessmentId assessmentId, Long afterVersion, int limit) {
        requireAssessment(workspaceId, assessmentId);
        return historyRepository.findEvents(workspaceId, assessmentId, afterVersion, limit);
    }

    private void requireAssessment(WorkspaceId workspaceId, AssessmentId assessmentId) {
        // Reading recorded history must not revalidate the current aggregate against newer domain rules.
        if (!assessmentRepository.exists(workspaceId, assessmentId)) {
            throw new AssessmentNotFoundException(workspaceId, assessmentId);
        }
    }

    private void requireWorkspace(WorkspaceId workspaceId) {
        if (!workspaceRepository.exists(workspaceId)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
    }
}

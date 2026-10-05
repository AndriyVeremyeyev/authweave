package io.authweave.core.evaluation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;
import io.authweave.core.assessment.persistence.AssessmentVersionConflictException;

@Service
public class ArchitectureConfigurationPreviewService {
    private final ArchitecturePatternPreflightService patterns;
    public ArchitectureConfigurationPreviewService(ArchitecturePatternPreflightService patterns) { this.patterns = patterns; }

    @Transactional(readOnly = true)
    public ArchitectureConfigurationPreview preview(WorkspaceId workspaceId, AssessmentId assessmentId, ArchitectureConfigurationRequest request) {
        var preflight = patterns.preview(workspaceId, assessmentId);
        if (preflight.assessmentVersion() != request.expectedVersion()) throw new AssessmentVersionConflictException(
                workspaceId, assessmentId, request.expectedVersion(), preflight.assessmentVersion());
        var pattern = preflight.patterns().stream().filter(p -> p.patternId() == request.patternId()).findFirst().orElseThrow();
        try {
            return new ArchitectureConfigurationPreview(preflight, ArchitectureConfigurationEvaluator.evaluate(request.patternId(),
                    ArchitecturePrerequisiteEvaluator.scope(pattern), request.settings()));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new InvalidArchitectureConfigurationRequestException();
        }
    }
}

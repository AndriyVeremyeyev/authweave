package io.authweave.core.evaluation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;
import io.authweave.core.assessment.persistence.AssessmentVersionConflictException;

@Service
public class ArchitecturePrerequisitePreviewService {
    private final ArchitecturePatternPreflightService patterns;

    public ArchitecturePrerequisitePreviewService(ArchitecturePatternPreflightService patterns) {
        this.patterns = patterns;
    }

    @Transactional(readOnly = true)
    public ArchitecturePrerequisitePreview preview(WorkspaceId workspaceId, AssessmentId assessmentId,
            ArchitecturePrerequisiteRequest request) {
        var preflight = patterns.preview(workspaceId, assessmentId);
        if (preflight.assessmentVersion() != request.expectedVersion()) {
            throw new AssessmentVersionConflictException(workspaceId, assessmentId,
                    request.expectedVersion(), preflight.assessmentVersion());
        }
        var pattern = preflight.patterns().stream().filter(p -> p.patternId() == request.patternId())
                .findFirst().orElseThrow();
        try {
            var analysis = ArchitecturePrerequisiteEvaluator.evaluate(request.patternId(),
                    ArchitecturePrerequisiteEvaluator.scope(pattern), request.declarations());
            return new ArchitecturePrerequisitePreview(preflight, request.declarations(), analysis);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new InvalidArchitecturePrerequisiteRequestException();
        }
    }
}

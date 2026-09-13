package io.authweave.core.evaluation;

import java.time.Clock;
import java.util.Comparator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.authweave.core.assessment.application.AssessmentApplicationService;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;

@Service
public class ArchitecturePatternPreflightService {
    private final AssessmentApplicationService assessments;
    private final Clock clock;

    public ArchitecturePatternPreflightService(AssessmentApplicationService assessments, Clock clock) {
        this.assessments = assessments;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ArchitecturePatternPreflight preview(WorkspaceId workspaceId, AssessmentId assessmentId) {
        var assessment = assessments.getAssessment(workspaceId, assessmentId);
        var profile = assessment.assessment().profile();
        return new ArchitecturePatternPreflight(workspaceId.value(), assessmentId.value(), assessment.version(),
                ArchitecturePatternEvaluator.POLICY_VERSION, clock.instant(), "ARCHITECTURE_PATTERN_PREFLIGHT", false,
                profile.application().clients().stream().sorted(Comparator.comparing(Enum::name)).toList(),
                profile.security().browserTokenExposureMinimization(),
                ArchitecturePatternEvaluator.CHECKED_PATHS, ArchitecturePatternEvaluator.DEFERRED_PATHS,
                ArchitecturePatternEvaluator.evaluate(profile));
    }
}

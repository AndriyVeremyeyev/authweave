package io.authweave.core.evaluation;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;

@RestController
public class HardConstraintPreflightController {
    private final EligibilityPreflightService eligibility;

    public HardConstraintPreflightController(EligibilityPreflightService eligibility) {
        this.eligibility = eligibility;
    }

    @GetMapping("/api/v5/workspaces/{workspaceId}/assessments/{assessmentId}/hard-constraint-preflight")
    public HardConstraintPreflight preview(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId) {
        return HardConstraintPreflight.from(eligibility.previewWithComplianceScope(
                new WorkspaceId(workspaceId), new AssessmentId(assessmentId)));
    }
}

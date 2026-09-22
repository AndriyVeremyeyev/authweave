package io.authweave.core.evaluation;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;

@RestController
public class SyntheticComparisonController {
    private final EligibilityPreflightService eligibility;

    public SyntheticComparisonController(EligibilityPreflightService eligibility) {
        this.eligibility = eligibility;
    }

    @GetMapping("/api/v5/workspaces/{workspaceId}/assessments/{assessmentId}/comparison-preflight")
    public SyntheticComparison preview(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId) {
        return SyntheticComparison.from(eligibility.previewWithComplianceScope(
                new WorkspaceId(workspaceId), new AssessmentId(assessmentId)));
    }

    @PostMapping("/api/v5/workspaces/{workspaceId}/assessments/{assessmentId}/weighted-comparison-preview")
    public WeightedComparisonPreview weightedPreview(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId,
            @RequestBody WeightedComparisonRequest request) {
        var comparison = SyntheticComparison.from(eligibility.previewWithComplianceScope(
                new WorkspaceId(workspaceId), new AssessmentId(assessmentId)));
        return WeightedComparisonPreview.from(comparison, request);
    }
}

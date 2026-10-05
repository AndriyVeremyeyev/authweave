package io.authweave.core.evaluation;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

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

    @PostMapping("/api/v5/workspaces/{workspaceId}/assessments/{assessmentId}/weight-sensitivity-preview")
    public WeightedSensitivityPreview sensitivityPreview(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId,
            @RequestBody WeightedSensitivityRequest request) {
        var comparison = SyntheticComparison.from(eligibility.previewWithComplianceScope(
                new WorkspaceId(workspaceId), new AssessmentId(assessmentId)));
        return WeightedSensitivityPreview.from(comparison, request);
    }

    private SyntheticComparison comparisonWithAuditability(UUID workspaceId, UUID assessmentId) {
        return SyntheticComparison.from(eligibility.previewWithAuditability(
                new WorkspaceId(workspaceId), new AssessmentId(assessmentId)));
    }

    @GetMapping("/api/v6/workspaces/{workspaceId}/assessments/{assessmentId}/comparison-preflight")
    public ResponseEntity<SyntheticComparison> previewWithAuditability(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(comparisonWithAuditability(workspaceId, assessmentId));
    }

    @PostMapping("/api/v6/workspaces/{workspaceId}/assessments/{assessmentId}/weighted-comparison-preview")
    public ResponseEntity<WeightedComparisonPreview> weightedWithAuditability(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId,
            @RequestBody WeightedComparisonRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                WeightedComparisonPreview.from(comparisonWithAuditability(workspaceId, assessmentId), request));
    }

    @PostMapping("/api/v6/workspaces/{workspaceId}/assessments/{assessmentId}/weight-sensitivity-preview")
    public ResponseEntity<WeightedSensitivityPreview> sensitivityWithAuditability(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId,
            @RequestBody WeightedSensitivityRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                WeightedSensitivityPreview.from(comparisonWithAuditability(workspaceId, assessmentId), request));
    }
}

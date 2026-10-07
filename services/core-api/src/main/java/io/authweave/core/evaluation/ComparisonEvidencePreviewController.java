package io.authweave.core.evaluation;

import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;
import io.authweave.core.config.InternalServiceCredentialFilter;

@RestController
public final class ComparisonEvidencePreviewController {
    private final EligibilityPreflightService service;
    private final InternalServiceCredentialFilter credentials;
    public ComparisonEvidencePreviewController(EligibilityPreflightService service, InternalServiceCredentialFilter credentials) {
        this.service = service; this.credentials = credentials;
    }
    @GetMapping("/api/v6/workspaces/{workspaceId}/assessments/{assessmentId}/comparison-evidence-preview")
    public ResponseEntity<ComparisonEvidencePreview> preview(@PathVariable UUID workspaceId,
            @PathVariable UUID assessmentId, HttpServletRequest request) {
        int status = credentials.personalWorkspaceStatus(request, workspaceId);
        if (status == 200 && (request.getQueryString() != null || request.getContentLengthLong() > 0
                || request.getHeader("Transfer-Encoding") != null)) status = 400;
        if (status != 200) return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.comparisonEvidence(new WorkspaceId(workspaceId), new AssessmentId(assessmentId)));
    }
}

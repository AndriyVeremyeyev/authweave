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
public class OperationsPlanningPreflightController {
    private final OperationsPlanningPreflightService service;
    private final InternalServiceCredentialFilter credentials;
    public OperationsPlanningPreflightController(OperationsPlanningPreflightService service, InternalServiceCredentialFilter credentials) { this.service = service; this.credentials = credentials; }
    @GetMapping("/api/v1/workspaces/{workspaceId}/assessments/{assessmentId}/operations-planning-preflight")
    public ResponseEntity<OperationsPlanningPreflight> preview(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId, HttpServletRequest request) {
        // Bind ownership to the routed workspace too, independent of encoded URI filtering.
        int status = credentials.personalWorkspaceStatus(request, workspaceId);
        if (status == 200 && (request.getQueryString() != null || request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null)) status = 400;
        if (status != 200) return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.preview(new WorkspaceId(workspaceId), new AssessmentId(assessmentId)));
    }
}

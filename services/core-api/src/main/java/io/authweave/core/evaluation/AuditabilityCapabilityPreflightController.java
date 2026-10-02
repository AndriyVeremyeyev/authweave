package io.authweave.core.evaluation;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;

@RestController
public class AuditabilityCapabilityPreflightController {
    private final AuditabilityCapabilityPreflightService service;
    public AuditabilityCapabilityPreflightController(AuditabilityCapabilityPreflightService service) { this.service = service; }
    @GetMapping("/api/v6/workspaces/{workspaceId}/assessments/{assessmentId}/auditability-capability-preflight")
    public ResponseEntity<AuditabilityCapabilityPreflight> preview(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.preview(new WorkspaceId(workspaceId), new AssessmentId(assessmentId)));
    }
}

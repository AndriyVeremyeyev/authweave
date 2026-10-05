package io.authweave.core.evaluation;

import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;

@RestController
public class ProvisioningLifecyclePreviewController {
    private final ProvisioningLifecyclePreviewService service;
    public ProvisioningLifecyclePreviewController(ProvisioningLifecyclePreviewService service) { this.service = service; }
    @PostMapping("/api/v1/workspaces/{workspaceId}/assessments/{assessmentId}/provisioning-lifecycle-preview")
    public ResponseEntity<ProvisioningLifecyclePreview> preview(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId,
            @Valid @RequestBody ProvisioningLifecycleRequest body, HttpServletRequest request) {
        if (request.getQueryString() != null) return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.preview(new WorkspaceId(workspaceId), new AssessmentId(assessmentId), body));
    }

    @PostMapping("/api/v2/workspaces/{workspaceId}/assessments/{assessmentId}/provisioning-lifecycle-preview")
    public ResponseEntity<ProvisioningLifecycleV2Preview> previewV2(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId,
            @Valid @RequestBody ProvisioningLifecycleV2Request body, HttpServletRequest request) {
        if (request.getQueryString() != null) return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.previewV2(new WorkspaceId(workspaceId), new AssessmentId(assessmentId), body));
    }
}

package io.authweave.core.evaluation;

import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;

@RestController
public class ArchitectureConfigurationPreviewController {
    private final ArchitectureConfigurationPreviewService service;
    public ArchitectureConfigurationPreviewController(ArchitectureConfigurationPreviewService service) { this.service = service; }

    @PostMapping("/api/v1/workspaces/{workspaceId}/assessments/{assessmentId}/architecture-configuration-preview")
    public ResponseEntity<ArchitectureConfigurationPreview> preview(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId,
            @Valid @RequestBody ArchitectureConfigurationRequest body, HttpServletRequest request) {
        if (request.getQueryString() != null) return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.preview(new WorkspaceId(workspaceId), new AssessmentId(assessmentId), body));
    }
}

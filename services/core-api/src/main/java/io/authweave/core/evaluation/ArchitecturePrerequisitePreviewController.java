package io.authweave.core.evaluation;

import java.util.UUID;
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
public class ArchitecturePrerequisitePreviewController {
    private final ArchitecturePrerequisitePreviewService service;

    public ArchitecturePrerequisitePreviewController(ArchitecturePrerequisitePreviewService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/workspaces/{workspaceId}/assessments/{assessmentId}/architecture-prerequisite-preview")
    public ResponseEntity<ArchitecturePrerequisitePreview> preview(@PathVariable UUID workspaceId,
            @PathVariable UUID assessmentId, @Valid @RequestBody ArchitecturePrerequisiteRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.preview(new WorkspaceId(workspaceId), new AssessmentId(assessmentId), request));
    }
}

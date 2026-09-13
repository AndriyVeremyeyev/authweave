package io.authweave.core.evaluation;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;

@RestController
public class ArchitecturePatternPreflightController {
    private final ArchitecturePatternPreflightService service;

    public ArchitecturePatternPreflightController(ArchitecturePatternPreflightService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/assessments/{assessmentId}/architecture-pattern-preflight")
    public ArchitecturePatternPreflight preview(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId) {
        return service.preview(new WorkspaceId(workspaceId), new AssessmentId(assessmentId));
    }
}

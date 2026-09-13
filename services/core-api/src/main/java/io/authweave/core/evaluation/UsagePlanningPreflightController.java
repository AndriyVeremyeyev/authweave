package io.authweave.core.evaluation;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;

@RestController
public class UsagePlanningPreflightController {
    private final UsagePlanningPreflightService service;
    public UsagePlanningPreflightController(UsagePlanningPreflightService service) { this.service = service; }

    @GetMapping("/api/v5/workspaces/{workspaceId}/assessments/{assessmentId}/usage-planning-preflight")
    public UsagePlanningPreflight preview(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId) {
        return service.preview(new WorkspaceId(workspaceId), new AssessmentId(assessmentId));
    }
}

package io.authweave.core.evaluation;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;

@RestController
public class EligibilityPreflightController {
    private final EligibilityPreflightService service;

    public EligibilityPreflightController(EligibilityPreflightService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/assessments/{assessmentId}/eligibility-preflight")
    public EligibilityPreflight preview(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId) {
        return service.preview(new WorkspaceId(workspaceId), new AssessmentId(assessmentId));
    }
}

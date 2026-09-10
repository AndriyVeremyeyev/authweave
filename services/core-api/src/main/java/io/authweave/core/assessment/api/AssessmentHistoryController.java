package io.authweave.core.assessment.api;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.authweave.core.assessment.application.AssessmentApplicationService;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;
import io.authweave.core.assessment.persistence.AssessmentEvent;
import io.authweave.core.assessment.persistence.AssessmentRevision;
import io.authweave.core.assessment.persistence.HistoryPage;

/** Local-only until the application has authenticated workspace membership checks. */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/assessments/{assessmentId}")
public class AssessmentHistoryController {

    private final AssessmentApplicationService service;

    public AssessmentHistoryController(AssessmentApplicationService service) {
        this.service = service;
    }

    @GetMapping("/revisions")
    public HistoryPage<AssessmentRevision> revisions(
            @PathVariable UUID workspaceId,
            @PathVariable UUID assessmentId,
            @RequestParam(required = false) @Min(0) @Max(9007199254740991L) Long afterVersion,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.getRevisions(new WorkspaceId(workspaceId), new AssessmentId(assessmentId), afterVersion, limit);
    }

    @GetMapping("/events")
    public HistoryPage<AssessmentEvent> events(
            @PathVariable UUID workspaceId,
            @PathVariable UUID assessmentId,
            @RequestParam(required = false) @Min(0) @Max(9007199254740991L) Long afterVersion,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.getEvents(new WorkspaceId(workspaceId), new AssessmentId(assessmentId), afterVersion, limit);
    }
}

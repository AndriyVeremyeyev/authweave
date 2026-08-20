package io.authweave.core.assessment.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.authweave.core.assessment.application.AssessmentApplicationService;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;
import io.authweave.core.assessment.persistence.PersistedAssessment;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class AssessmentController {

    private final AssessmentApplicationService assessmentService;

    public AssessmentController(AssessmentApplicationService assessmentService) {
        this.assessmentService = assessmentService;
    }

    @PutMapping
    public ResponseEntity<Void> provisionWorkspace(@PathVariable UUID workspaceId) {
        boolean created = assessmentService.provisionWorkspace(new WorkspaceId(workspaceId));
        if (created) {
            return ResponseEntity.created(workspaceLocation(workspaceId)).build();
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/assessments")
    public ResponseEntity<AssessmentResponse> createAssessment(@PathVariable UUID workspaceId) {
        AssessmentId assessmentId = new AssessmentId(UUID.randomUUID());
        PersistedAssessment persisted = assessmentService.createAssessment(
                new WorkspaceId(workspaceId),
                assessmentId);
        URI location = assessmentLocation(workspaceId, assessmentId.value());
        return ResponseEntity.created(location)
                .body(AssessmentResponse.from(persisted));
    }

    @GetMapping("/assessments/{assessmentId}")
    public AssessmentResponse getAssessment(
            @PathVariable UUID workspaceId,
            @PathVariable UUID assessmentId) {
        return AssessmentResponse.from(assessmentService.getAssessment(
                new WorkspaceId(workspaceId),
                new AssessmentId(assessmentId)));
    }

    @PutMapping("/assessments/{assessmentId}/profile")
    public AssessmentResponse updateProfile(
            @PathVariable UUID workspaceId,
            @PathVariable UUID assessmentId,
            @Valid @RequestBody UpdateAssessmentProfileRequest request) {
        return AssessmentResponse.from(assessmentService.updateProfile(
                new WorkspaceId(workspaceId),
                new AssessmentId(assessmentId),
                request.expectedVersion(),
                request.profile()));
    }

    private static URI workspaceLocation(UUID workspaceId) {
        return URI.create("/api/v1/workspaces/" + workspaceId);
    }

    private static URI assessmentLocation(UUID workspaceId, UUID assessmentId) {
        return URI.create("/api/v1/workspaces/%s/assessments/%s"
                .formatted(workspaceId, assessmentId));
    }
}

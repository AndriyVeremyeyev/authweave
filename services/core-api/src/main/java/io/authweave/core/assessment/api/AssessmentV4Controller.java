package io.authweave.core.assessment.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.authweave.core.assessment.application.AssessmentApplicationService;
import io.authweave.core.assessment.domain.AssessmentId;
import io.authweave.core.assessment.domain.WorkspaceId;
import io.authweave.core.assessment.persistence.AssessmentRevision;
import io.authweave.core.assessment.persistence.HistoryPage;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v4/workspaces/{workspaceId}/assessments")
public class AssessmentV4Controller {
    private final AssessmentApplicationService service;
    private final ObjectMapper mapper;

    public AssessmentV4Controller(AssessmentApplicationService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<AssessmentV4Response> create(@PathVariable UUID workspaceId) {
        var id = new AssessmentId(UUID.randomUUID());
        var assessment = service.createAssessment(new WorkspaceId(workspaceId), id);
        return ResponseEntity.created(URI.create("/api/v4/workspaces/" + workspaceId + "/assessments/" + id.value()))
                .body(AssessmentV4Response.from(assessment, mapper));
    }

    @GetMapping("/{assessmentId}")
    public AssessmentV4Response get(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId) {
        return AssessmentV4Response.from(service.getAssessment(new WorkspaceId(workspaceId), new AssessmentId(assessmentId)), mapper);
    }

    @PutMapping("/{assessmentId}/profile")
    public AssessmentV4Response update(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId,
            @Valid @RequestBody UpdateAssessmentProfileV4Request request) {
        return AssessmentV4Response.from(service.updateProfile(new WorkspaceId(workspaceId), new AssessmentId(assessmentId),
                request.expectedVersion(), request.profile().toDomain()), mapper);
    }

    @GetMapping("/{assessmentId}/revisions")
    public HistoryPage<AssessmentRevision> revisions(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId,
            @RequestParam(required = false) @Min(0) @Max(9007199254740991L) Long afterVersion,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.getRevisions(new WorkspaceId(workspaceId), new AssessmentId(assessmentId), afterVersion, limit);
    }
}

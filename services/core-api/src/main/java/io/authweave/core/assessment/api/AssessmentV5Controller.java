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
import io.authweave.core.assessment.persistence.AssessmentListPage;
import io.authweave.core.assessment.persistence.HistoryPage;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v5/workspaces/{workspaceId}/assessments")
public class AssessmentV5Controller {
    private final AssessmentApplicationService service;
    private final ObjectMapper mapper;

    public AssessmentV5Controller(AssessmentApplicationService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<AssessmentV5Response> create(@PathVariable UUID workspaceId) {
        var id = new AssessmentId(UUID.randomUUID());
        var assessment = service.createAssessment(new WorkspaceId(workspaceId), id);
        return ResponseEntity.created(URI.create("/api/v5/workspaces/" + workspaceId + "/assessments/" + id.value()))
                .body(AssessmentV5Response.from(assessment, mapper));
    }

    @GetMapping
    public AssessmentListPage list(@PathVariable UUID workspaceId,
            @RequestParam(required = false) UUID beforeId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return service.listAssessments(new WorkspaceId(workspaceId), beforeId, limit);
    }

    @GetMapping("/{assessmentId}")
    public AssessmentV5Response get(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId) {
        return AssessmentV5Response.from(service.getAssessment(new WorkspaceId(workspaceId), new AssessmentId(assessmentId)), mapper);
    }

    @PutMapping("/{assessmentId}/profile")
    public AssessmentV5Response update(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId,
            @Valid @RequestBody UpdateAssessmentProfileV5Request request) {
        return AssessmentV5Response.from(service.updateProfile(new WorkspaceId(workspaceId), new AssessmentId(assessmentId),
                request.expectedVersion(), request.profile().toDomain()), mapper);
    }

    @GetMapping("/{assessmentId}/revisions")
    public HistoryPage<AssessmentRevision> revisions(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId,
            @RequestParam(required = false) @Min(0) @Max(9007199254740991L) Long afterVersion,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.getRevisions(new WorkspaceId(workspaceId), new AssessmentId(assessmentId), afterVersion, limit);
    }
}

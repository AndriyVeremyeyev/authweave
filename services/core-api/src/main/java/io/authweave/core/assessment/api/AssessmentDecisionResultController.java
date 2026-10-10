package io.authweave.core.assessment.api;

import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.authweave.core.assessment.persistence.AssessmentDecisionResultService;
import io.authweave.core.assessment.result.AssessmentDecisionResultRequest;
import io.authweave.core.config.InternalServiceCredentialFilter;

/** Explicit owner operation; a curator role does not confer access to another personal assessment. */
@RestController
@RequestMapping("/api/v6/workspaces/{workspaceId}/assessments/{assessmentId}/decision-results")
public class AssessmentDecisionResultController {
    private final AssessmentDecisionResultService results;
    private final InternalServiceCredentialFilter credentials;
    public AssessmentDecisionResultController(AssessmentDecisionResultService results, InternalServiceCredentialFilter credentials) {
        this.results = results; this.credentials = credentials;
    }
    @PostMapping
    ResponseEntity<?> save(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId,
            @RequestBody AssessmentDecisionResultRequest body, HttpServletRequest request) {
        int status = credentials.personalWorkspaceStatus(request, workspaceId);
        if (status != 200) return ResponseEntity.status(status).header("Cache-Control", "no-store").build();
        var saved = results.save(workspaceId, assessmentId, body, actor(request));
        return ResponseEntity.status(saved.created() ? 201 : 200).header("Cache-Control", "no-store").body(saved.receipt());
    }
    /** Same audited write contract, followed by a bounded independently replayed projection. */
    @PostMapping("/summary")
    ResponseEntity<?> saveSummary(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId,
            @RequestBody AssessmentDecisionResultRequest body, HttpServletRequest request) {
        int status = credentials.personalWorkspaceStatus(request, workspaceId);
        if (status != 200) return ResponseEntity.status(status).header("Cache-Control", "no-store").build();
        query(request, java.util.Set.of());
        var saved = results.save(workspaceId, assessmentId, body, actor(request));
        var summary = results.summary(workspaceId, assessmentId, saved.receipt().reference(), actor(request));
        return ResponseEntity.status(saved.created() ? 201 : 200).header("Cache-Control", "no-store").body(summary);
    }
    @GetMapping
    ResponseEntity<?> history(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId,
            @RequestParam(required = false) UUID beforeResultId,
            @RequestParam(required = false) @Min(1) @Max(9007199254740991L) Long beforeVersion,
            @RequestParam(required = false) @Pattern(regexp = "[a-f0-9]{64}") String beforeResultSha256, HttpServletRequest request) {
        int status = credentials.personalWorkspaceStatus(request, workspaceId);
        if (status != 200) return ResponseEntity.status(status).header("Cache-Control", "no-store").build();
        query(request, java.util.Set.of("beforeResultId", "beforeVersion", "beforeResultSha256"));
        boolean any = beforeResultId != null || beforeVersion != null || beforeResultSha256 != null;
        if (any && (beforeResultId == null || beforeVersion == null || beforeResultSha256 == null)) invalid();
        var before = any ? new AssessmentDecisionResultRequest.Reference(beforeResultId, beforeVersion, beforeResultSha256) : null;
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(results.history(workspaceId, assessmentId, before, actor(request)));
    }
    @GetMapping("/{resultId}/summary")
    ResponseEntity<?> summary(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId, @PathVariable UUID resultId,
            @RequestParam @Min(1) @Max(9007199254740991L) long version,
            @RequestParam @Pattern(regexp = "[a-f0-9]{64}") String resultSha256, HttpServletRequest request) {
        int status = credentials.personalWorkspaceStatus(request, workspaceId);
        if (status != 200) return ResponseEntity.status(status).header("Cache-Control", "no-store").build();
        query(request, java.util.Set.of("version", "resultSha256"));
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(results.summary(workspaceId, assessmentId,
                new AssessmentDecisionResultRequest.Reference(resultId, version, resultSha256), actor(request)));
    }
    @GetMapping("/{resultId}/advice")
    ResponseEntity<?> advice(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId, @PathVariable UUID resultId,
            @RequestParam @Min(1) @Max(9007199254740991L) long version,
            @RequestParam @Pattern(regexp = "[a-f0-9]{64}") String resultSha256, HttpServletRequest request) {
        int status = credentials.personalWorkspaceStatus(request, workspaceId);
        if (status != 200) return ResponseEntity.status(status).header("Cache-Control", "no-store").build();
        query(request, java.util.Set.of("version", "resultSha256"));
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(results.advice(workspaceId, assessmentId,
                new AssessmentDecisionResultRequest.Reference(resultId, version, resultSha256), actor(request)));
    }
    @GetMapping("/{resultId}")
    ResponseEntity<?> get(@PathVariable UUID workspaceId, @PathVariable UUID assessmentId, @PathVariable UUID resultId,
            @RequestParam @Min(1) @Max(9007199254740991L) long version,
            @RequestParam @Pattern(regexp = "[a-f0-9]{64}") String resultSha256, HttpServletRequest request) {
        int status = credentials.personalWorkspaceStatus(request, workspaceId);
        if (status != 200) return ResponseEntity.status(status).header("Cache-Control", "no-store").build();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(results.get(workspaceId, assessmentId,
                new AssessmentDecisionResultRequest.Reference(resultId, version, resultSha256), actor(request)));
    }
    private static AssessmentDecisionResultService.Actor actor(HttpServletRequest request) {
        return new AssessmentDecisionResultService.Actor(request.getHeader("X-AuthWeave-Oidc-Issuer"), request.getHeader("X-AuthWeave-Oidc-Subject"));
    }
    private static void query(HttpServletRequest request, java.util.Set<String> keys) {
        if (request.getParameterMap().entrySet().stream().anyMatch(e -> !keys.contains(e.getKey()) || e.getValue().length != 1)) invalid();
    }
    private static void invalid() {
        throw new io.authweave.core.assessment.result.AssessmentDecisionResultException(
                io.authweave.core.assessment.result.AssessmentDecisionResultException.Reason.INVALID_INPUT);
    }
}

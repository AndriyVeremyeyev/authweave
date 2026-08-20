package io.authweave.core.assessment.api;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.authweave.core.assessment.application.WorkspaceNotFoundException;
import io.authweave.core.assessment.domain.profile.InvalidApplicationIdentityProfileException;
import io.authweave.core.assessment.persistence.AssessmentNotFoundException;
import io.authweave.core.assessment.persistence.AssessmentVersionConflictException;

@RestControllerAdvice
public class AssessmentProblemDetailsHandler {

    @ExceptionHandler(WorkspaceNotFoundException.class)
    ProblemDetail workspaceNotFound(
            WorkspaceNotFoundException exception,
            HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.NOT_FOUND,
                "workspace-not-found",
                "Workspace not found",
                exception.getMessage(),
                request);
        problem.setProperty("workspaceId", exception.workspaceId().value());
        return problem;
    }

    @ExceptionHandler(AssessmentNotFoundException.class)
    ProblemDetail assessmentNotFound(
            AssessmentNotFoundException exception,
            HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.NOT_FOUND,
                "assessment-not-found",
                "Assessment not found",
                exception.getMessage(),
                request);
        problem.setProperty("workspaceId", exception.workspaceId().value());
        problem.setProperty("assessmentId", exception.assessmentId().value());
        return problem;
    }

    @ExceptionHandler(AssessmentVersionConflictException.class)
    ProblemDetail versionConflict(
            AssessmentVersionConflictException exception,
            HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                "assessment-version-conflict",
                "Assessment version conflict",
                exception.getMessage(),
                request);
        problem.setProperty("workspaceId", exception.workspaceId().value());
        problem.setProperty("assessmentId", exception.assessmentId().value());
        problem.setProperty("expectedVersion", exception.expectedVersion());
        problem.setProperty("actualVersion", exception.actualVersion());
        return problem;
    }

    @ExceptionHandler(InvalidApplicationIdentityProfileException.class)
    ProblemDetail invalidProfile(
            InvalidApplicationIdentityProfileException exception,
            HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "invalid-application-identity-profile",
                "Application identity profile is invalid",
                exception.getMessage(),
                request);
        problem.setProperty("issues", exception.issues());
        return problem;
    }

    private static ProblemDetail problem(
            HttpStatus status,
            String code,
            String title,
            String detail,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:authweave:problem:" + code));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        return problem;
    }
}

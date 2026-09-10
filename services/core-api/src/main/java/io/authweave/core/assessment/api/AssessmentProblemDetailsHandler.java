package io.authweave.core.assessment.api;

import java.net.URI;
import java.util.Comparator;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import io.authweave.core.assessment.application.WorkspaceNotFoundException;
import io.authweave.core.assessment.domain.InvalidAssessmentTransitionException;
import io.authweave.core.assessment.domain.profile.InvalidApplicationIdentityProfileException;
import io.authweave.core.assessment.persistence.AssessmentNotFoundException;
import io.authweave.core.assessment.persistence.AssessmentVersionConflictException;
import tools.jackson.core.JacksonException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AssessmentProblemDetailsHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableBody(HttpMessageNotReadableException exception, HttpServletRequest request) {
        String path = "$";
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof JacksonException jackson && !jackson.getPath().isEmpty()) {
                StringBuilder location = new StringBuilder("$");
                for (JacksonException.Reference reference : jackson.getPath()) {
                    if (reference.getPropertyName() != null) {
                        location.append('.').append(reference.getPropertyName());
                    } else if (reference.getIndex() >= 0) {
                        location.append('[').append(reference.getIndex()).append(']');
                    }
                }
                path = location.substring(0, Math.min(location.length(), 500));
                break;
            }
        }
        return invalidRequest(List.of(new RequestViolation(path,
                "Use the documented JSON structure, types and enum values.")), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidFields(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<RequestViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new RequestViolation(error.getField(), switch (error.getCode()) {
                    case "NotNull" -> "This field is required and must not be null.";
                    case "UniqueElements" -> "Array items must be unique.";
                    case "PositiveOrZero" -> "Use a non-negative integer.";
                    default -> "Invalid value.";
                }))
                .distinct()
                .sorted(Comparator.comparing(RequestViolation::path))
                .limit(100)
                .toList();
        return invalidRequest(violations, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail invalidPath(MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        return invalidRequest(List.of(new RequestViolation(exception.getName(),
                exception.getRequiredType() == java.util.UUID.class
                        ? "Use a valid UUID." : "Use the documented parameter type.")), request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail invalidParameters(HandlerMethodValidationException exception, HttpServletRequest request) {
        if (exception.isForReturnValue()) {
            throw exception;
        }
        List<RequestViolation> violations = exception.getParameterValidationResults().stream()
                .map(result -> new RequestViolation(
                        result.getMethodParameter().getParameterName(), "Use a value within the documented bounds."))
                .distinct()
                .sorted(Comparator.comparing(RequestViolation::path))
                .toList();
        return invalidRequest(violations, request);
    }

    @ExceptionHandler(InvalidAssessmentTransitionException.class)
    ProblemDetail invalidTransition(
            InvalidAssessmentTransitionException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "invalid-assessment-transition",
                "Assessment state conflict", "The current assessment state does not allow this change.", request);
    }

    private static ProblemDetail invalidRequest(List<RequestViolation> violations, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request",
                "The request does not match the API contract.", request);
        problem.setProperty("violations", violations);
        return problem;
    }

    private record RequestViolation(String path, String message) {
    }

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

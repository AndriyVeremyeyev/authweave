package io.authweave.core.assessment.domain;

public final class InvalidAssessmentTransitionException extends RuntimeException {

    private final AssessmentStatus currentStatus;
    private final AssessmentStatus targetStatus;

    InvalidAssessmentTransitionException(
            AssessmentStatus currentStatus,
            AssessmentStatus targetStatus) {
        super("Cannot move assessment from %s to %s".formatted(currentStatus, targetStatus));
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
    }

    public AssessmentStatus currentStatus() {
        return currentStatus;
    }

    public AssessmentStatus targetStatus() {
        return targetStatus;
    }
}

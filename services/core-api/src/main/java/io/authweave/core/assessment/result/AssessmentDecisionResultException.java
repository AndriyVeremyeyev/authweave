package io.authweave.core.assessment.result;

public final class AssessmentDecisionResultException extends RuntimeException {
    public enum Reason { FORBIDDEN, NOT_FOUND, CONFLICT, INVALID_INPUT, READ_UNAVAILABLE, UNSUPPORTED_POLICY, TOO_LARGE }
    private final Reason reason;
    public AssessmentDecisionResultException(Reason reason) { super("Assessment decision result " + reason); this.reason = reason; }
    public Reason reason() { return reason; }
}

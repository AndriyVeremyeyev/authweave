package io.authweave.core.catalog.impact;

public final class CandidateDecisionReportException extends RuntimeException {
    public enum Reason { NOT_FOUND, CONFLICT, READ_UNAVAILABLE, UNSUPPORTED_VERSION, REPORT_TOO_LARGE }
    private final Reason reason;
    public CandidateDecisionReportException(Reason reason) { super("Candidate decision report: " + reason); this.reason = reason; }
    public Reason reason() { return reason; }
}

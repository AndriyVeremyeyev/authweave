package io.authweave.core.catalog.impact;

public final class CatalogFactPathReportException extends RuntimeException {
    public enum Reason { NOT_FOUND, ID_CONFLICT, READ_BUDGET_EXCEEDED, REPLAY_UNAVAILABLE, REPORT_TOO_LARGE }
    private final Reason reason;
    public CatalogFactPathReportException(Reason reason) { super(reason.name()); this.reason = reason; }
    public Reason reason() { return reason; }
}

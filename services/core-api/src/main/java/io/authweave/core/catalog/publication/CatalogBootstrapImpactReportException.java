package io.authweave.core.catalog.publication;

public final class CatalogBootstrapImpactReportException extends RuntimeException {
    public enum Reason { NOT_FOUND, ID_CONFLICT, READ_BUDGET_EXCEEDED, REPORT_TOO_LARGE, REGISTRY_NOT_EMPTY, REVIEW_UNAVAILABLE }
    private final Reason reason;
    public CatalogBootstrapImpactReportException(Reason reason) { super(reason.name()); this.reason = reason; }
    public Reason reason() { return reason; }
}

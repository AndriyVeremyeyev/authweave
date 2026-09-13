package io.authweave.core.catalog.impact;

public final class CatalogImpactReportException extends RuntimeException {
    public enum Reason { NOT_FOUND, ID_CONFLICT }
    private final Reason reason;
    public CatalogImpactReportException(Reason reason) { super(reason.name()); this.reason = reason; }
    public Reason reason() { return reason; }
}

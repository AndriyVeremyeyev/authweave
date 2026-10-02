package io.authweave.core.catalog.auditability;

public final class CatalogAuditabilityReviewException extends RuntimeException {
    public enum Reason { INVALID_REQUEST, NOT_FOUND, CONFLICT, READ_UNAVAILABLE }
    private final Reason reason;
    public CatalogAuditabilityReviewException(Reason reason) { super(reason.name()); this.reason = reason; }
    public Reason reason() { return reason; }
}

package io.authweave.core.catalog.publication;

public final class CatalogBootstrapReviewException extends RuntimeException {
    public enum Reason { INVALID_REQUEST, NOT_FOUND, CONFLICT, READ_UNAVAILABLE }
    private final Reason reason;
    public CatalogBootstrapReviewException(Reason reason) { super(reason.name()); this.reason = reason; }
    public Reason reason() { return reason; }
}

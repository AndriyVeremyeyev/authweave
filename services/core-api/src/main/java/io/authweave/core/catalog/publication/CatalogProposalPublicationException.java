package io.authweave.core.catalog.publication;

public final class CatalogProposalPublicationException extends RuntimeException {
    public enum Reason { CONFLICT, SOURCE_NOT_ELIGIBLE, COVERAGE_INCOMPLETE, AUTHENTICATION_EXPIRED, STORED_PUBLICATION_INVALID }
    private final Reason reason;
    public CatalogProposalPublicationException(Reason reason) { super(reason.name()); this.reason = reason; }
    public Reason reason() { return reason; }
}

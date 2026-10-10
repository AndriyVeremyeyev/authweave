package io.authweave.core.catalog.publication;

/** Fail-closed internal loading denial. No partial manifest or source material is released. */
public final class CatalogPublishedLoadingException extends RuntimeException {
    public enum Reason { PUBLICATION_PROOF_NOT_FOUND, REFERENCE_MISMATCH, STORED_PUBLICATION_INVALID }
    private final Reason reason;
    public CatalogPublishedLoadingException(Reason reason) {
        super(reason.name()); this.reason = reason;
    }
    public Reason reason() { return reason; }
}

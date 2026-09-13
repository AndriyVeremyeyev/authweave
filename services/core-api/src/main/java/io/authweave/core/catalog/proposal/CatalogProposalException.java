package io.authweave.core.catalog.proposal;

public final class CatalogProposalException extends RuntimeException {
    public enum Reason { NOT_FOUND, VERSION_CONFLICT, NOT_REVIEWABLE, VERSION_EXHAUSTED, REPLAY_UNAVAILABLE }
    private final Reason reason;
    public CatalogProposalException(Reason reason) { super(reason.name()); this.reason = reason; }
    public Reason reason() { return reason; }
}

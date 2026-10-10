package io.authweave.core.catalog.impact;

/** Body-free internal read denial. Database failures remain infrastructure failures. */
public final class CatalogProposalDecisionLoadingException extends RuntimeException {
    public enum Reason { PROPOSAL_NOT_FOUND, REFERENCE_MISMATCH, STORED_PROPOSAL_INVALID, REVIEW_SET_MISMATCH, BASELINE_MISMATCH }
    private final Reason reason;
    public CatalogProposalDecisionLoadingException(Reason reason) { super(reason.name()); this.reason = reason; }
    public Reason reason() { return reason; }
}

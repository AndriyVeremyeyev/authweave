package io.authweave.core.assessment.domain.profile;

/** Owner-recorded requirements scope, never a legal applicability or compliance verdict. */
public enum ComplianceScopeStatus {
    UNKNOWN,
    NONE_IDENTIFIED,
    TARGETS_IDENTIFIED;

    public static final class UnknownFilter {
        @Override public boolean equals(Object value) { return value == UNKNOWN; }
        @Override public int hashCode() { return 0; }
    }
}

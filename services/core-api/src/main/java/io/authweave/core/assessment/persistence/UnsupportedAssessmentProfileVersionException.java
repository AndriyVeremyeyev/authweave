package io.authweave.core.assessment.persistence;

public final class UnsupportedAssessmentProfileVersionException extends RuntimeException {

    private final short version;

    UnsupportedAssessmentProfileVersionException(short version) {
        super("Unsupported assessment profile schema version: " + version);
        this.version = version;
    }

    public short version() {
        return version;
    }
}

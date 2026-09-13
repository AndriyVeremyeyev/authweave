package io.authweave.core.assessment.application;

public final class ProfileUpgradeRequiredException extends RuntimeException {
    public ProfileUpgradeRequiredException() {
        this(2);
    }

    public ProfileUpgradeRequiredException(int version) {
        super("This profile contains newer security requirements. Use API v" + version
                + " assessment/profile or revisions endpoints to preserve them.");
    }
}

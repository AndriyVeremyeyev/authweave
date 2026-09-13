package io.authweave.core.assessment.application;

public final class ProfileUpgradeRequiredException extends RuntimeException {
    public ProfileUpgradeRequiredException() {
        super("This profile contains residency details. Use the API v2 assessment/profile or revisions endpoint to preserve them.");
    }
}

package io.authweave.core.assessment.domain.profile;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class InvalidApplicationIdentityProfileException extends RuntimeException {

    private final List<ProfileIssue> issues;

    public InvalidApplicationIdentityProfileException(List<ProfileIssue> issues) {
        super(messageFor(issues));
        this.issues = List.copyOf(issues);
    }

    public List<ProfileIssue> issues() {
        return issues;
    }

    private static String messageFor(List<ProfileIssue> issues) {
        Objects.requireNonNull(issues, "issues must not be null");
        if (issues.isEmpty()) {
            throw new IllegalArgumentException("issues must not be empty");
        }
        return "Application identity profile is invalid: " + issues.stream()
                .map(ProfileIssue::code)
                .collect(Collectors.joining(", "));
    }
}

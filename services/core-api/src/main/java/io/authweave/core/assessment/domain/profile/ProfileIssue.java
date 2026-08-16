package io.authweave.core.assessment.domain.profile;

import java.util.Objects;

public record ProfileIssue(
        String code,
        String path,
        ProfileIssueType type,
        String message) {

    public ProfileIssue {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}

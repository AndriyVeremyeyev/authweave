package io.authweave.core.assessment.domain.profile;

import java.util.List;
import java.util.Objects;

public record ProfileValidationResult(List<ProfileIssue> issues) {

    public ProfileValidationResult {
        issues = List.copyOf(Objects.requireNonNull(issues, "issues must not be null"));
    }

    public boolean canSave() {
        return issues.stream().noneMatch(
                issue -> issue.type() == ProfileIssueType.CONTRADICTION);
    }

    public boolean canEvaluate() {
        return issues.isEmpty();
    }

    public List<ProfileIssue> contradictions() {
        return issues.stream()
                .filter(issue -> issue.type() == ProfileIssueType.CONTRADICTION)
                .toList();
    }
}

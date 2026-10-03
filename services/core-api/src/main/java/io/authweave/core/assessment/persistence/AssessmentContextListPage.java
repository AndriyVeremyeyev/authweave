package io.authweave.core.assessment.persistence;

import java.util.List;
import java.util.UUID;

public record AssessmentContextListPage(List<AssessmentContextListItem> items, UUID nextBeforeId) {
    public AssessmentContextListPage {
        items = List.copyOf(items);
    }

    static AssessmentContextListPage from(List<AssessmentContextListItem> rows, int limit) {
        boolean hasMore = rows.size() > limit;
        var items = rows.subList(0, Math.min(rows.size(), limit));
        return new AssessmentContextListPage(items, hasMore ? items.getLast().id() : null);
    }
}

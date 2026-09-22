package io.authweave.core.assessment.persistence;

import java.util.List;
import java.util.UUID;

public record AssessmentListPage(List<AssessmentListItem> items, UUID nextBeforeId) {

    public AssessmentListPage {
        items = List.copyOf(items);
    }

    static AssessmentListPage from(List<AssessmentListItem> rows, int limit) {
        boolean hasMore = rows.size() > limit;
        List<AssessmentListItem> items = rows.subList(0, Math.min(rows.size(), limit));
        return new AssessmentListPage(items, hasMore ? items.getLast().id() : null);
    }
}

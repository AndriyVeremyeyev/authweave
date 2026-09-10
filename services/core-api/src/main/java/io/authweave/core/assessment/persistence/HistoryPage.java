package io.authweave.core.assessment.persistence;

import java.util.List;
import java.util.function.ToLongFunction;

public record HistoryPage<T>(List<T> items, Long nextAfterVersion) {

    public HistoryPage {
        items = List.copyOf(items);
    }

    static <T> HistoryPage<T> from(List<T> rows, int limit, ToLongFunction<T> version) {
        boolean hasMore = rows.size() > limit;
        List<T> items = rows.subList(0, Math.min(rows.size(), limit));
        return new HistoryPage<>(items, hasMore ? version.applyAsLong(items.getLast()) : null);
    }
}

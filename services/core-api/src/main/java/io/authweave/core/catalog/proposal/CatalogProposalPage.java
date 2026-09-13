package io.authweave.core.catalog.proposal;

import java.util.List;
import java.util.function.ToLongFunction;

public record CatalogProposalPage<T>(List<T> items, Long nextAfterVersion) {
    public CatalogProposalPage { items = List.copyOf(items); }
    static <T> CatalogProposalPage<T> from(List<T> rows, int limit, ToLongFunction<T> version) {
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        return new CatalogProposalPage<>(items, more ? version.applyAsLong(items.getLast()) : null);
    }
}

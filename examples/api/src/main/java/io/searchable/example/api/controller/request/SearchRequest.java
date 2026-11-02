package io.searchable.example.api.controller.request;

import io.searchable.example.api.controller.payload.FilterPayload;
import io.searchable.example.api.controller.payload.SearchOptionsPayload;
import io.searchable.core.domain.search.PaginationParams;
import io.searchable.core.domain.search.SearchOptions;
import io.searchable.core.domain.search.SearchType;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/** Body for {@code POST /api/v1/search}. */
public record SearchRequest(
    @NotBlank String query,
    List<String> namespaceIds,
    SearchType searchType,
    SearchOptionsPayload options,
    Map<String, Object> filters,
    FilterPayload filter
) {

    public io.searchable.core.domain.search.SearchRequest toDomain() {
        final boolean highlight = options == null || options.highlightEnabled() == null
            || options.highlightEnabled();
        final int offset = options == null || options.offset() == null ? 0 : options.offset();
        final int limit = options == null || options.maxResults() == null ? 10 : options.maxResults();
        return io.searchable.core.domain.search.SearchRequest.builder()
            .query(query)
            .namespaceIds(namespaceIds == null ? List.of() : namespaceIds)
            .searchType(searchType)
            .options(new SearchOptions(highlight))
            .pagination(new PaginationParams(offset, limit))
            .filters(filters == null ? Map.of() : filters)
            .filterExpression(filter == null ? null : filter.toDomain())
            .build();
    }
}

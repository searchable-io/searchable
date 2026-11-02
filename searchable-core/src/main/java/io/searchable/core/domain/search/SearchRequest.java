package io.searchable.core.domain.search;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable search request.
 *
 * <p>Use {@link #builder()} for construction. {@link #searchType} is optional;
 * when absent the namespace's configured architecture is used.
 *
 * <p>Candidates can be narrowed in two equivalent ways. {@link #filters()} is
 * the map form, where keys are combined with AND and a list value is combined
 * with OR. {@link #filterExpression()} is the richer form that can also express
 * OR across different keys. When both are set the expression wins; use
 * {@link #effectiveFilter()} to obtain whichever applies.
 */
public final class SearchRequest {

    private final String query;
    private final List<String> namespaceIds;
    private final SearchType searchType;
    private final SearchOptions options;
    private final PaginationParams pagination;
    private final Map<String, Object> filters;
    private final FilterExpression filterExpression;

    private SearchRequest(final Builder b) {
        this.query = Objects.requireNonNull(b.query, "query must not be null");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        this.namespaceIds = b.namespaceIds == null || b.namespaceIds.isEmpty()
            ? List.of()
            : List.copyOf(b.namespaceIds);
        this.searchType = b.searchType;
        this.options = b.options == null ? SearchOptions.defaults() : b.options;
        this.pagination = b.pagination == null ? PaginationParams.defaults() : b.pagination;
        this.filters = b.filters == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(b.filters));
        this.filterExpression = b.filterExpression;
    }

    public String query() { return query; }
    public List<String> namespaceIds() { return namespaceIds; }
    public SearchType searchType() { return searchType; }
    public SearchOptions options() { return options; }
    public PaginationParams pagination() { return pagination; }
    public Map<String, Object> filters() { return filters; }
    public FilterExpression filterExpression() { return filterExpression; }

    /**
     * The filter to apply, taking the expression when set and otherwise
     * converting {@link #filters()}.
     *
     * @return the filter, or empty when neither form was supplied
     */
    public Optional<FilterExpression> effectiveFilter() {
        if (filterExpression != null) {
            return Optional.of(filterExpression);
        }
        return FilterExpression.fromMap(filters);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link SearchRequest}. */
    public static final class Builder {
        private String query;
        private List<String> namespaceIds;
        private SearchType searchType;
        private SearchOptions options;
        private PaginationParams pagination;
        private Map<String, Object> filters;
        private FilterExpression filterExpression;

        public Builder query(final String value) { this.query = value; return this; }
        public Builder namespaceIds(final List<String> v) { this.namespaceIds = v; return this; }
        public Builder searchType(final SearchType v) { this.searchType = v; return this; }
        public Builder options(final SearchOptions v) { this.options = v; return this; }
        public Builder pagination(final PaginationParams v) { this.pagination = v; return this; }
        public Builder filters(final Map<String, Object> v) { this.filters = v; return this; }
        public Builder filterExpression(final FilterExpression v) { this.filterExpression = v; return this; }

        public SearchRequest build() {
            return new SearchRequest(this);
        }
    }
}

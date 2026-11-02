package io.searchable.core.application;

import io.searchable.core.domain.namespace.Namespace;
import io.searchable.core.domain.namespace.NamespaceConfig;
import io.searchable.core.domain.namespace.NamespaceRepository;
import io.searchable.core.domain.search.FilterExpression;
import io.searchable.core.domain.search.PaginationParams;
import io.searchable.core.domain.search.SearchHit;
import io.searchable.core.domain.search.SearchRequest;
import io.searchable.core.domain.search.SearchResult;
import io.searchable.core.domain.search.SearchStrategy;
import io.searchable.core.domain.search.SearchType;
import io.searchable.core.infrastructure.lucene.FilterQueryBuilder;
import io.searchable.core.spi.FilterPluginRegistry;
import io.searchable.core.spi.SearchHitFilterPlugin;
import io.searchable.core.infrastructure.lucene.LuceneFullTextSearcher;
import io.searchable.core.infrastructure.lucene.LuceneVectorSearcher;
import org.apache.lucene.search.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Application-layer search facade.
 *
 * <p>The effective {@link SearchType} is resolved per request:
 * <ol>
 *   <li>{@code request.searchType()} when non-null</li>
 *   <li>The first target namespace's
 *       {@link NamespaceConfig#architecture()} when available</li>
 *   <li>{@link SearchType#FULL_TEXT} as a fallback</li>
 * </ol>
 */
public final class SearchService {

    /** Aggregation key reporting how many hits a filter plugin rejected. */
    public static final String FILTERED_OUT_KEY = "filteredOutByPlugin";

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final NamespaceRepository namespaces;
    private final LuceneFullTextSearcher fullTextSearcher;
    private final LuceneVectorSearcher vectorSearcher;
    private final HybridSearchOrchestrator hybrid;
    private final SearchResultEnricher enricher;
    private final FilterPluginRegistry plugins;

    public SearchService(final NamespaceRepository namespaces,
                         final LuceneFullTextSearcher fullTextSearcher,
                         final LuceneVectorSearcher vectorSearcher,
                         final HybridSearchOrchestrator hybrid) {
        this(namespaces, fullTextSearcher, vectorSearcher, hybrid,
            new SearchResultEnricher(null));
    }

    public SearchService(final NamespaceRepository namespaces,
                         final LuceneFullTextSearcher fullTextSearcher,
                         final LuceneVectorSearcher vectorSearcher,
                         final HybridSearchOrchestrator hybrid,
                         final SearchResultEnricher enricher) {
        this.namespaces = Objects.requireNonNull(namespaces);
        this.fullTextSearcher = Objects.requireNonNull(fullTextSearcher);
        this.vectorSearcher = Objects.requireNonNull(vectorSearcher);
        this.hybrid = Objects.requireNonNull(hybrid);
        this.plugins = FilterPluginRegistry.empty();
        this.enricher = Objects.requireNonNull(enricher);
    }

    /**
     * A copy of this service that applies the given filter plugins.
     *
     * @param registry the plugins available to namespaces
     * @return the new service
     */
    public SearchService withPlugins(final FilterPluginRegistry registry) {
        return new SearchService(namespaces, fullTextSearcher, vectorSearcher, hybrid,
            enricher, registry);
    }

    private SearchService(final NamespaceRepository namespaces,
                          final LuceneFullTextSearcher fullTextSearcher,
                          final LuceneVectorSearcher vectorSearcher,
                          final HybridSearchOrchestrator hybrid,
                          final SearchResultEnricher enricher,
                          final FilterPluginRegistry plugins) {
        this.namespaces = Objects.requireNonNull(namespaces);
        this.fullTextSearcher = Objects.requireNonNull(fullTextSearcher);
        this.vectorSearcher = Objects.requireNonNull(vectorSearcher);
        this.hybrid = Objects.requireNonNull(hybrid);
        this.enricher = Objects.requireNonNull(enricher);
        this.plugins = Objects.requireNonNull(plugins, "plugins must not be null");
    }

    public SearchResult search(final SearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        final long start = System.nanoTime();
        final List<String> targets = resolveTargets(request);
        if (targets.isEmpty()) {
            return SearchResult.empty(elapsedMs(start));
        }
        final SearchType type = resolveType(request, targets);
        log.debug("executing search type={} targets={}", type, targets);

        final SearchResult raw = switch (type) {
            case FULL_TEXT -> aggregate(targets, request,
                (ns, req) -> fullTextSearcher.search(ns, req, resolveFilter(ns, req)), start);
            case VECTOR -> aggregate(targets, request,
                (ns, req) -> vectorSearcher.search(ns, req, resolveFilter(ns, req)), start);
            case HYBRID -> aggregate(targets, request,
                this::hybridSearch, start);
        };
        return enricher.enrich(applyHitFilters(raw, targets));
    }

    /**
     * Apply the namespaces' hit filter plugins to an already paged result.
     *
     * <p>Rejected hits are not replaced, because refilling the page would mean
     * searching again until enough hits survive, and how far that goes depends
     * on the plugin rather than on the request. The number of rejected hits is
     * reported under {@value #FILTERED_OUT_KEY} so the caller can widen the
     * page instead
     * (see {@code docs/devel/adr/0004-two-extension-points-for-search-filtering.md}).
     */
    private SearchResult applyHitFilters(final SearchResult result, final List<String> targets) {
        final Map<String, List<SearchHitFilterPlugin>> byNamespace = new LinkedHashMap<>();
        for (final String namespaceId : targets) {
            byNamespace.put(namespaceId, plugins.hitFilters(configOf(namespaceId).filterPlugins()));
        }
        if (byNamespace.values().stream().allMatch(List::isEmpty)) {
            return result;
        }
        final List<SearchHit> kept = new ArrayList<>(result.hits().size());
        int rejected = 0;
        for (final SearchHit hit : result.hits()) {
            final List<SearchHitFilterPlugin> filters =
                byNamespace.getOrDefault(hit.namespaceId(), List.of());
            if (filters.stream().allMatch(filter -> filter.accept(hit))) {
                kept.add(hit);
            } else {
                rejected++;
            }
        }
        if (rejected == 0) {
            return result;
        }
        log.debug("hit filter plugins rejected {} of {} hits", rejected, result.hits().size());
        final Map<String, Object> aggregations = new LinkedHashMap<>(result.aggregations());
        aggregations.put(FILTERED_OUT_KEY, rejected);
        return new SearchResult(kept, result.totalHits(), result.maxScore(),
            aggregations, result.tookMs());
    }

    private SearchResult aggregate(final List<String> targets,
                                   final SearchRequest request,
                                   final BiFunction<String, SearchRequest, SearchResult> engine,
                                   final long startNanos) {
        if (targets.size() == 1) {
            return applyIndexWeight(targets.get(0), engine.apply(targets.get(0), request));
        }

        final SearchRequest expanded = expandFor(request);
        final List<SearchHit> all = new ArrayList<>();
        long totalHits = 0L;
        for (final String namespaceId : targets) {
            final SearchResult partial = applyIndexWeight(namespaceId, engine.apply(namespaceId, expanded));
            totalHits += partial.totalHits();
            all.addAll(partial.hits());
        }
        all.sort(Comparator.comparingDouble(SearchHit::score).reversed());
        final int from = Math.min(request.pagination().offset(), all.size());
        final int to = Math.min(from + request.pagination().limit(), all.size());
        final List<SearchHit> page = all.subList(from, to);
        final double maxScore = all.isEmpty() ? 0.0 : all.get(0).score();
        return new SearchResult(page, totalHits, maxScore, Map.of(), elapsedMs(startNanos));
    }

    /**
     * Multiply every hit's score by the namespace's {@code indexWeight}.
     * When the weight is exactly {@code 1.0} (the default) the result is
     * returned unchanged to avoid allocating new {@link SearchHit} records.
     */
    private SearchResult applyIndexWeight(final String namespaceId, final SearchResult result) {
        final double weight = namespaces.findById(namespaceId)
            .map(Namespace::config)
            .map(NamespaceConfig::indexWeight)
            .orElse(NamespaceConfig.DEFAULT_INDEX_WEIGHT);
        if (weight == NamespaceConfig.DEFAULT_INDEX_WEIGHT || result.hits().isEmpty()) {
            return result;
        }
        final List<SearchHit> scaled = new ArrayList<>(result.hits().size());
        for (final SearchHit h : result.hits()) {
            scaled.add(new SearchHit(h.documentId(), h.namespaceId(), h.title(),
                h.content(), h.score() * weight, h.highlights(), h.metadata()));
        }
        final double maxScore = scaled.isEmpty() ? 0.0 : scaled.get(0).score();
        return new SearchResult(scaled, result.totalHits(), maxScore,
            result.aggregations(), result.tookMs());
    }

    private SearchResult hybridSearch(final String namespaceId, final SearchRequest request) {
        final NamespaceConfig config = configOf(namespaceId);
        final Query filter = resolveFilter(namespaceId, request);
        if (config.searchStrategy() == SearchStrategy.PARALLEL) {
            return hybrid.parallel(namespaceId, request, filter);
        }
        return hybrid.sequential(namespaceId, request, config.searchOrder(), filter);
    }

    private NamespaceConfig configOf(final String namespaceId) {
        return namespaces.findById(namespaceId)
            .map(Namespace::config)
            .orElseThrow(() -> new NoSuchElementException("Namespace not found: " + namespaceId));
    }

    /**
     * Build the Lucene filter for one namespace.
     *
     * <p>A filter that cannot be applied fails the search. Callers narrow
     * results for a reason, often to keep documents away from the user asking,
     * so returning the unnarrowed result would hand back exactly what the
     * filter was meant to exclude. That holds whether the namespace declares
     * no indexable key at all or the filter names a key it has not declared.
     *
     * <p>Searches that pass no filter are unaffected, which is every caller
     * written against releases where {@code filters} was accepted but never
     * applied.
     */
    private Query resolveFilter(final String namespaceId, final SearchRequest request) {
        final Optional<FilterExpression> expression = request.effectiveFilter();
        if (expression.isEmpty()) {
            return null;
        }
        final NamespaceConfig config = configOf(namespaceId);
        if (!config.isMetadataFilteringEnabled()) {
            throw new IllegalArgumentException(
                "Namespace " + namespaceId + " declares no indexable metadata key, so the filter on "
                    + expression.get().keys() + " cannot be applied; declare the keys in"
                    + " indexedMetadataKeys and rebuild the namespace before filtering on them");
        }
        return FilterQueryBuilder.build(expression.get(), config.indexedMetadataKeys(),
            plugins.fieldPlugins(config.filterPlugins()));
    }

    private SearchType resolveType(final SearchRequest request, final List<String> targets) {
        if (request.searchType() != null) {
            return request.searchType();
        }
        return targets.stream()
            .findFirst()
            .flatMap(namespaces::findById)
            .map(Namespace::config)
            .map(NamespaceConfig::architecture)
            .orElse(SearchType.FULL_TEXT);
    }

    private List<String> resolveTargets(final SearchRequest request) {
        if (request.namespaceIds().isEmpty()) {
            return namespaces.findAll().stream().map(Namespace::id).toList();
        }
        for (final String id : request.namespaceIds()) {
            if (!namespaces.exists(id)) {
                throw new NoSuchElementException("Namespace not found: " + id);
            }
        }
        return request.namespaceIds();
    }

    private SearchRequest expandFor(final SearchRequest request) {
        final PaginationParams page = request.pagination();
        return SearchRequest.builder()
            .query(request.query())
            .namespaceIds(List.of())
            .searchType(request.searchType())
            .options(request.options())
            .pagination(new PaginationParams(0, page.offset() + page.limit()))
            .filters(request.filters())
            .filterExpression(request.filterExpression())
            .build();
    }

    private long elapsedMs(final long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}

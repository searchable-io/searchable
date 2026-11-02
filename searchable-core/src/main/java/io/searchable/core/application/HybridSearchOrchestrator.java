package io.searchable.core.application;

import io.searchable.core.domain.search.PaginationParams;
import io.searchable.core.domain.search.SearchHit;
import io.searchable.core.domain.search.SearchOrder;
import io.searchable.core.domain.search.ChunkAggregation;
import io.searchable.core.domain.search.SearchOptions;
import io.searchable.core.domain.search.SearchRequest;
import org.apache.lucene.search.Query;
import io.searchable.core.domain.search.SearchResult;
import io.searchable.core.infrastructure.lucene.LuceneFullTextSearcher;
import io.searchable.core.infrastructure.lucene.LuceneVectorSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs hybrid search by combining the full-text and vector searchers
 * either sequentially (intersection / re-rank) or in parallel
 * (Reciprocal Rank Fusion).
 */
public final class HybridSearchOrchestrator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchOrchestrator.class);

    private final LuceneFullTextSearcher fullTextSearcher;
    private final LuceneVectorSearcher vectorSearcher;
    private final ResultMerger merger;
    private final ExecutorService executor;

    public HybridSearchOrchestrator(final LuceneFullTextSearcher fullTextSearcher,
                                    final LuceneVectorSearcher vectorSearcher) {
        this(fullTextSearcher, vectorSearcher, new ResultMerger(),
            Executors.newVirtualThreadPerTaskExecutor());
    }

    public HybridSearchOrchestrator(final LuceneFullTextSearcher fullTextSearcher,
                                    final LuceneVectorSearcher vectorSearcher,
                                    final ResultMerger merger,
                                    final ExecutorService executor) {
        this.fullTextSearcher = Objects.requireNonNull(fullTextSearcher);
        this.vectorSearcher = Objects.requireNonNull(vectorSearcher);
        this.merger = Objects.requireNonNull(merger);
        this.executor = Objects.requireNonNull(executor);
    }

    /**
     * Sequential hybrid: the primary engine retrieves candidate documents,
     * the secondary engine re-ranks them. {@link SearchOrder} controls
     * which engine acts as the primary.
     */
    public SearchResult sequential(final String namespaceId,
                                   final SearchRequest request,
                                   final SearchOrder order) {
        return sequential(namespaceId, request, order, null);
    }

    /** Sequential hybrid, narrowing candidates with the supplied filter query. */
    public SearchResult sequential(final String namespaceId,
                                   final SearchRequest request,
                                   final SearchOrder order,
                                   final Query filter) {
        Objects.requireNonNull(order, "order must not be null");
        final long start = System.nanoTime();

        final SearchRequest expanded = expandedRequest(request);
        final SearchResult primary;
        final SearchResult secondary;
        if (order == SearchOrder.FULL_TEXT_FIRST) {
            primary = fullTextSearcher.search(namespaceId, expanded, filter);
            secondary = vectorSearcher.search(namespaceId, expanded, filter);
        } else {
            primary = vectorSearcher.search(namespaceId, expanded, filter);
            secondary = fullTextSearcher.search(namespaceId, expanded, filter);
        }

        final List<SearchHit> merged = merger.intersect(primary.hits(), secondary.hits());
        return paginate(merged, request, start);
    }

    /**
     * Parallel hybrid: both engines run concurrently and their results
     * are merged via Reciprocal Rank Fusion.
     */
    public SearchResult parallel(final String namespaceId, final SearchRequest request) {
        return parallel(namespaceId, request, null);
    }

    /** Parallel hybrid, narrowing candidates with the supplied filter query. */
    public SearchResult parallel(final String namespaceId, final SearchRequest request,
                                 final Query filter) {
        final long start = System.nanoTime();
        final SearchRequest expanded = expandedRequest(request);

        final CompletableFuture<SearchResult> ft = CompletableFuture.supplyAsync(
            () -> fullTextSearcher.search(namespaceId, expanded, filter), executor);
        final CompletableFuture<SearchResult> vec = CompletableFuture.supplyAsync(
            () -> vectorSearcher.search(namespaceId, expanded, filter), executor);

        try {
            final SearchResult fullText = ft.join();
            final SearchResult vector = vec.join();
            final List<SearchHit> merged = merger.reciprocalRankFusion(
                List.of(fullText.hits(), vector.hits()), ResultMerger.DEFAULT_RRF_K);
            return paginate(merged, request, start);
        } catch (CompletionException e) {
            throw new IllegalStateException("Parallel hybrid search failed", e.getCause());
        }
    }

    private SearchRequest expandedRequest(final SearchRequest request) {
        // Both engines must return enough candidates for accurate merging.
        final PaginationParams page = request.pagination();
        final int target = Math.max((page.offset() + page.limit()) * 2, 20);
        return SearchRequest.builder()
            .query(request.query())
            .namespaceIds(request.namespaceIds())
            .searchType(request.searchType())
            .options(documentGranularity(request.options()))
            .pagination(new PaginationParams(0, target))
            .filters(request.filters())
            .filterExpression(request.filterExpression())
            .build();
    }

    /**
     * Raise the aggregation policy so both engines return document-level hits.
     *
     * <p>Fusion ranks documents, so the two input lists must have the same
     * granularity. The full-text searcher always folds chunks into their
     * parent document; the vector searcher only does so when asked. Leaving
     * the policy at {@link ChunkAggregation#NONE} would therefore feed
     * chunk-level hits from one side and document-level hits from the other,
     * and the fused ranking would not be document-level at all.
     *
     * <p>This applies to the hybrid path only. A plain vector search keeps
     * whatever the caller asked for.
     */
    private static SearchOptions documentGranularity(final SearchOptions options) {
        if (options.aggregation() != ChunkAggregation.NONE) {
            return options;
        }
        return new SearchOptions(options.highlightEnabled(), options.snippetLength(),
            options.escapeMarkup(), options.lazyLoad(), options.bm25K1(), options.bm25B(),
            options.metaWeights(), ChunkAggregation.BEST_CHUNK);
    }

    private SearchResult paginate(final List<SearchHit> merged,
                                  final SearchRequest request,
                                  final long startNanos) {
        final int offset = Math.min(request.pagination().offset(), merged.size());
        final int upper = Math.min(offset + request.pagination().limit(), merged.size());
        final List<SearchHit> page = merged.subList(offset, upper);
        final double maxScore = merged.isEmpty() ? 0.0 : merged.get(0).score();
        final long tookMs = (System.nanoTime() - startNanos) / 1_000_000;
        return new SearchResult(page, merged.size(), maxScore, Map.of(), tookMs);
    }

    @Override
    public void close() {
        executor.shutdown();
        log.info("hybrid orchestrator executor shut down");
    }
}

package io.searchable.core.infrastructure.lucene;

import io.searchable.core.domain.embedding.EmbeddingProvider;
import io.searchable.core.domain.search.SearchHit;
import io.searchable.core.domain.search.ChunkAggregation;
import io.searchable.core.domain.search.SearchRequest;
import io.searchable.core.domain.search.SearchResult;
import io.searchable.core.domain.search.SubResult;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Executes nearest-neighbor vector search against a namespace's Lucene index.
 *
 * <p>The query text is embedded via the supplied {@link EmbeddingProvider},
 * then a {@code KnnFloatVectorQuery} is run with {@code k} derived from
 * {@code offset + limit} so that pagination can be applied client-side.
 */
public final class LuceneVectorSearcher {

    private final LuceneIndexProvider provider;
    private final LuceneDocumentMapper mapper;
    private final Function<String, EmbeddingProvider> embeddingResolver;

    public LuceneVectorSearcher(final LuceneIndexProvider provider,
                                final EmbeddingProvider embeddingProvider) {
        this(provider, new LuceneDocumentMapper(), embeddingProvider);
    }

    public LuceneVectorSearcher(final LuceneIndexProvider provider,
                                final LuceneDocumentMapper mapper,
                                final EmbeddingProvider embeddingProvider) {
        this(provider, mapper, ns -> Objects.requireNonNull(embeddingProvider,
            "embeddingProvider must not be null"));
    }

    /**
     * Full form, where the embedding provider is resolved per namespace so
     * that namespaces can use different models.
     */
    public LuceneVectorSearcher(final LuceneIndexProvider provider,
                                final LuceneDocumentMapper mapper,
                                final Function<String, EmbeddingProvider> embeddingResolver) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.embeddingResolver = Objects.requireNonNull(embeddingResolver,
            "embeddingResolver must not be null");
    }

    /**
     * The embedding provider assigned to a namespace.
     *
     * @param namespaceId the namespace
     * @return the provider
     * @throws IllegalStateException when the namespace has no provider assigned
     */
    public EmbeddingProvider embeddingFor(final String namespaceId) {
        final EmbeddingProvider embedding = embeddingResolver.apply(namespaceId);
        if (embedding == null) {
            throw new IllegalStateException(
                "No embedding provider is assigned to namespace " + namespaceId);
        }
        return embedding;
    }

    public SearchResult search(final String namespaceId, final SearchRequest request) {
        return search(namespaceId, request, null);
    }

    /**
     * Run a vector search, optionally narrowing candidates with a filter query.
     *
     * <p>The filter is passed to the KNN query itself, so candidates are
     * narrowed during the approximate nearest neighbor walk rather than
     * discarded afterwards.
     */
    public SearchResult search(final String namespaceId, final SearchRequest request,
                               final Query filter) {
        Objects.requireNonNull(namespaceId, "namespaceId must not be null");
        Objects.requireNonNull(request, "request must not be null");
        final long start = System.nanoTime();

        final LuceneIndexContext ctx = provider.getOrCreate(namespaceId);
        IndexSearcher searcher = null;
        try {
            searcher = ctx.acquireSearcher();
            final io.searchable.core.domain.embedding.EmbeddingProvider embedding =
                embeddingFor(namespaceId);
            verifyVectorDimension(searcher, namespaceId, embedding.dimension());
            final float[] queryVector = embedding.embed(request.query());
            final int topN = Math.max(
                request.pagination().offset() + request.pagination().limit(), 1);
            final KnnFloatVectorQuery query = new KnnFloatVectorQuery(
                LuceneFields.VECTOR, queryVector, topN, filter);
            final TopDocs hits = searcher.search(query, topN);

            final List<SearchHit> result = collectHits(searcher, hits, request, namespaceId);
            final double maxScore = hits.scoreDocs.length == 0 ? 0.0 : hits.scoreDocs[0].score;
            final long tookMs = (System.nanoTime() - start) / 1_000_000;
            return new SearchResult(result, hits.totalHits.value(), maxScore, Map.of(), tookMs);
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to execute vector search on namespace " + namespaceId, e);
        } finally {
            try {
                if (searcher != null) {
                    ctx.release(searcher);
                }
            } catch (IOException ignored) {
                // best-effort release
            }
        }
    }

    /**
     * Stop a search whose query vector does not fit the indexed vectors.
     *
     * <p>Lucene would otherwise reject the query deep inside the KNN walk, or
     * on an empty segment return nothing at all without saying why. Checking
     * the field's declared dimension first turns a model mismatch into a clear
     * failure before any search work happens.
     */
    private void verifyVectorDimension(final IndexSearcher searcher,
                                       final String namespaceId,
                                       final int queryDimension) {
        for (final org.apache.lucene.index.LeafReaderContext leaf
                : searcher.getIndexReader().leaves()) {
            final org.apache.lucene.index.FieldInfo info =
                leaf.reader().getFieldInfos().fieldInfo(LuceneFields.VECTOR);
            if (info == null) {
                continue;
            }
            final int indexed = info.getVectorDimension();
            if (indexed > 0 && indexed != queryDimension) {
                throw new IllegalStateException(
                    "Namespace " + namespaceId + " holds " + indexed
                        + "-dimensional vectors but the configured embedding provider produces "
                        + queryDimension + "; rebuild the namespace before switching models");
            }
        }
    }

    private List<SearchHit> collectHits(final IndexSearcher searcher,
                                        final TopDocs hits,
                                        final SearchRequest request,
                                        final String namespaceId) throws IOException {
        if (request.options().aggregation() == ChunkAggregation.NONE) {
            return collectChunks(searcher, hits, request, namespaceId);
        }
        return collectByDocument(searcher, hits, request, namespaceId);
    }

    /**
     * One hit per matching chunk, in score order. Several chunks of the same
     * document appear as separate hits, which is the behaviour of earlier
     * releases.
     */
    private List<SearchHit> collectChunks(final IndexSearcher searcher,
                                          final TopDocs hits,
                                          final SearchRequest request,
                                          final String namespaceId) throws IOException {
        final List<SearchHit> result = new ArrayList<>();
        final int offset = request.pagination().offset();
        final int upper = Math.min(hits.scoreDocs.length,
            offset + request.pagination().limit());

        final boolean lazy = request.options().lazyLoad();
        for (int i = offset; i < upper; i++) {
            final ScoreDoc scoreDoc = hits.scoreDocs[i];
            final org.apache.lucene.document.Document doc =
                searcher.storedFields().document(scoreDoc.doc);
            final String parentId = doc.get(LuceneFields.PARENT_ID);
            final String id = parentId != null ? parentId : doc.get(LuceneFields.ID);
            final String title = doc.get(LuceneFields.TITLE);
            final String content = lazy ? null : doc.get(LuceneFields.CONTENT);

            // Parent metadata is held in the metadata DB, not in Lucene.
            // The application-layer SearchResultEnricher attaches metadata
            // to hits after this method returns.
            result.add(new SearchHit(id, namespaceId, title, content, scoreDoc.score,
                Map.of(), Map.of()));
        }
        return result;
    }

    /**
     * One hit per parent document, represented by its highest scoring chunk.
     *
     * <p>Folding happens before paging, so a page holds the requested number of
     * documents rather than of chunks. With
     * {@link ChunkAggregation#DOCUMENT} the remaining chunks of the document
     * are attached as sub-results, matching what the full-text searcher
     * returns, so both lists can be fused at the same granularity.
     */
    private List<SearchHit> collectByDocument(final IndexSearcher searcher,
                                              final TopDocs hits,
                                              final SearchRequest request,
                                              final String namespaceId) throws IOException {
        final boolean lazy = request.options().lazyLoad();
        final boolean withSubResults =
            request.options().aggregation() == ChunkAggregation.DOCUMENT;
        final Map<String, SearchHit> best = new LinkedHashMap<>();
        final Map<String, List<SubResult>> extras = new LinkedHashMap<>();

        for (final ScoreDoc scoreDoc : hits.scoreDocs) {
            final org.apache.lucene.document.Document doc =
                searcher.storedFields().document(scoreDoc.doc);
            final String parentId = doc.get(LuceneFields.PARENT_ID);
            final String chunkId = doc.get(LuceneFields.ID);
            final String id = parentId != null ? parentId : chunkId;
            if (best.containsKey(id)) {
                if (withSubResults) {
                    extras.computeIfAbsent(id, k -> new ArrayList<>())
                        .add(toSubResult(doc, id, chunkId, scoreDoc.score));
                }
                continue;
            }
            final String content = lazy ? null : doc.get(LuceneFields.CONTENT);
            best.put(id, new SearchHit(id, namespaceId, doc.get(LuceneFields.TITLE),
                content, scoreDoc.score, Map.of(), Map.of()));
        }

        final List<SearchHit> folded = new ArrayList<>(best.size());
        for (final Map.Entry<String, SearchHit> entry : best.entrySet()) {
            final List<SubResult> subResults = extras.get(entry.getKey());
            final SearchHit hit = entry.getValue();
            folded.add(subResults == null || subResults.isEmpty()
                ? hit
                : new SearchHit(hit.documentId(), hit.namespaceId(), hit.title(), hit.content(),
                    hit.score(), hit.highlights(), hit.metadata(), subResults));
        }

        final int offset = Math.min(request.pagination().offset(), folded.size());
        final int upper = Math.min(offset + request.pagination().limit(), folded.size());
        return List.copyOf(folded.subList(offset, upper));
    }

    private SubResult toSubResult(final org.apache.lucene.document.Document doc,
                                  final String parentId,
                                  final String chunkId,
                                  final double score) {
        final Map<String, Object> chunkMetadata =
            mapper.deserializeMetadata(doc.get(LuceneFields.CHUNK_METADATA_JSON));
        final Object heading = chunkMetadata.get("heading");
        final Object level = chunkMetadata.get("level");
        final String content = doc.get(LuceneFields.CONTENT);
        return new SubResult(
            chunkId == null ? parentId : chunkId,
            parentId,
            level instanceof Number n ? n.intValue() : 0,
            heading == null ? "" : heading.toString(),
            content == null ? "" : content,
            score,
            Map.of(),
            null);
    }
}

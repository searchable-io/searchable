package io.searchable.core.infrastructure.lucene;

import io.searchable.core.domain.search.SearchHit;
import io.searchable.core.domain.search.SearchRequest;
import io.searchable.core.domain.search.SearchResult;
import io.searchable.core.domain.search.SubResult;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleFragmenter;
import org.apache.lucene.search.highlight.SimpleHTMLEncoder;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.apache.lucene.search.highlight.TokenSources;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes full-text search against a namespace's Lucene index.
 */
public final class LuceneFullTextSearcher {

    private static final String HL_PRE = "<mark>";
    private static final String HL_POST = "</mark>";

    private final LuceneIndexProvider provider;
    private final LuceneDocumentMapper mapper;

    public LuceneFullTextSearcher(final LuceneIndexProvider provider) {
        this(provider, new LuceneDocumentMapper());
    }

    public LuceneFullTextSearcher(final LuceneIndexProvider provider,
                                  final LuceneDocumentMapper mapper) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    public SearchResult search(final String namespaceId, final SearchRequest request) {
        return search(namespaceId, request, null);
    }

    /**
     * Run a full-text search, optionally narrowing candidates with a filter query.
     *
     * <p>The filter is attached as a FILTER clause, so it narrows the candidate
     * set without contributing to the score. Highlighting still uses the
     * unfiltered query, so filter terms are never highlighted.
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
            applyBm25Override(searcher, request);
            final Analyzer analyzer = ctx.analyzer();
            final Query query = parseQuery(analyzer, request);
            final int topN = request.pagination().offset() + request.pagination().limit();
            final TopDocs hits = searcher.search(withFilter(query, filter), Math.max(topN, 1));

            final List<SearchHit> result = collectHits(searcher, hits, analyzer, query,
                request, namespaceId);
            final double maxScore = hits.scoreDocs.length == 0 ? 0.0 : hits.scoreDocs[0].score;
            final long tookMs = (System.nanoTime() - start) / 1_000_000;
            return new SearchResult(result, hits.totalHits.value(), maxScore, Map.of(), tookMs);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to execute search on namespace " + namespaceId, e);
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
     * Swap the searcher's {@link Similarity} to a {@link BM25Similarity}
     * configured with the request-level overrides. When neither parameter
     * is set, the searcher is left with Lucene's defaults (k1=1.2, b=0.75).
     */
    private static Query withFilter(final Query query, final Query filter) {
        if (filter == null) {
            return query;
        }
        return new BooleanQuery.Builder()
            .add(query, BooleanClause.Occur.MUST)
            .add(filter, BooleanClause.Occur.FILTER)
            .build();
    }

    private void applyBm25Override(final IndexSearcher searcher, final SearchRequest request) {
        final Double k1 = request.options().bm25K1();
        final Double b = request.options().bm25B();
        if (k1 == null && b == null) {
            return;
        }
        final float effectiveK1 = k1 != null ? k1.floatValue() : 1.2f;
        final float effectiveB = b != null ? b.floatValue() : 0.75f;
        searcher.setSimilarity(new BM25Similarity(effectiveK1, effectiveB));
    }

    private Query parseQuery(final Analyzer analyzer, final SearchRequest request) throws Exception {
        final java.util.Map<String, Double> metaWeights = request.options().metaWeights();
        final String queryText = QueryParser.escape(request.query());
        if (metaWeights.isEmpty()) {
            final QueryParser parser = new QueryParser(LuceneFields.CONTENT, analyzer);
            parser.setDefaultOperator(QueryParser.Operator.OR);
            return parser.parse(queryText);
        }
        // Multi-field query with per-field boosts (TASK-058).
        final String[] fields = metaWeights.keySet().toArray(String[]::new);
        final java.util.Map<String, Float> boosts = new java.util.LinkedHashMap<>();
        for (final var e : metaWeights.entrySet()) {
            boosts.put(e.getKey(), e.getValue().floatValue());
        }
        final MultiFieldQueryParser parser =
            new MultiFieldQueryParser(fields, analyzer, boosts);
        parser.setDefaultOperator(QueryParser.Operator.OR);
        return parser.parse(queryText);
    }

    private List<SearchHit> collectHits(final IndexSearcher searcher,
                                        final TopDocs hits,
                                        final Analyzer analyzer,
                                        final Query query,
                                        final SearchRequest request,
                                        final String namespaceId) throws Exception {
        final Highlighter highlighter;
        if (request.options().highlightEnabled()) {
            final var formatter = new SimpleHTMLFormatter(HL_PRE, HL_POST);
            highlighter = request.options().escapeMarkup()
                ? new Highlighter(formatter, new SimpleHTMLEncoder(), new QueryScorer(query))
                : new Highlighter(formatter, new QueryScorer(query));
            highlighter.setTextFragmenter(new SimpleFragmenter(request.options().snippetLength()));
        } else {
            highlighter = null;
        }

        final IndexReader reader = searcher.getIndexReader();
        final boolean lazy = request.options().lazyLoad();

        // Walk every scoreDoc and group chunks by their parent document id.
        // The first occurrence (highest scoring per Lucene's sort) becomes
        // the main SearchHit; subsequent chunks for the same parent become
        // SubResults — implementing TASK-050 (Sub-results search / scoring).
        final LinkedHashMap<String, SearchHitBuilder> grouped = new LinkedHashMap<>();
        for (final ScoreDoc scoreDoc : hits.scoreDocs) {
            final org.apache.lucene.document.Document doc =
                searcher.storedFields().document(scoreDoc.doc);
            final String parentId = doc.get(LuceneFields.PARENT_ID);
            final String id = parentId != null ? parentId : doc.get(LuceneFields.ID);

            final SearchHitBuilder builder = grouped.computeIfAbsent(id, k -> new SearchHitBuilder());
            if (builder.first == null) {
                builder.first = new ChunkRow(doc, scoreDoc.score, scoreDoc.doc);
            } else {
                builder.others.add(new ChunkRow(doc, scoreDoc.score, scoreDoc.doc));
            }
        }

        // Apply pagination over the deduplicated parent set.
        final int offset = request.pagination().offset();
        final int limit = request.pagination().limit();
        final List<SearchHitBuilder> ordered = new ArrayList<>(grouped.values());
        final int upper = Math.min(ordered.size(), offset + limit);
        final List<SearchHit> result = new ArrayList<>();
        for (int i = offset; i < upper; i++) {
            result.add(ordered.get(i).build(namespaceId, lazy, highlighter, analyzer, reader));
        }
        return result;
    }

    private final class SearchHitBuilder {
        ChunkRow first;
        final List<ChunkRow> others = new ArrayList<>();

        SearchHit build(final String namespaceId, final boolean lazy,
                        final Highlighter highlighter, final Analyzer analyzer,
                        final IndexReader reader) throws Exception {
            final org.apache.lucene.document.Document doc = first.doc;
            final String parentId = doc.get(LuceneFields.PARENT_ID);
            final String id = parentId != null ? parentId : doc.get(LuceneFields.ID);
            final String title = doc.get(LuceneFields.TITLE);
            final String content = lazy ? null : doc.get(LuceneFields.CONTENT);

            final Map<String, List<String>> highlights = (highlighter == null || lazy)
                ? Map.of()
                : buildHighlights(highlighter, analyzer, reader, first.luceneDocId,
                    doc.get(LuceneFields.CONTENT));

            // Parent metadata (incl. metadata.url) is held in the metadata DB,
            // not in the Lucene index. Hits returned by this searcher carry
            // an empty metadata map and SubResult.anchorUrl=null; the
            // application-layer SearchResultEnricher fills both in by
            // batch-loading from DocumentMetadataRepository.
            final List<SubResult> subResults = new ArrayList<>();
            for (final ChunkRow row : others) {
                subResults.add(toSubResult(row, id));
            }
            return new SearchHit(id, namespaceId, title, content,
                first.score, highlights, Map.of(), subResults);
        }
    }

    private SubResult toSubResult(final ChunkRow row, final String parentId) {
        final Map<String, Object> chunkMeta = mapper.deserializeMetadata(
            row.doc.get(LuceneFields.CHUNK_METADATA_JSON));
        final int level = chunkMeta.get("level") instanceof Number n ? n.intValue() : 0;
        final String heading = chunkMeta.get("heading") instanceof String s ? s : "";
        final String chunkContent = row.doc.get(LuceneFields.CONTENT);
        final String sectionId = parentId + "#" + row.doc.get(LuceneFields.CHUNK_ORDINAL);
        // anchorUrl is left null here; SearchResultEnricher attaches it once
        // it has the parent metadata.url loaded from the metadata DB.
        return new SubResult(sectionId, parentId, level, heading,
            chunkContent == null ? "" : chunkContent,
            row.score, Map.of(), null);
    }

    private record ChunkRow(org.apache.lucene.document.Document doc, double score, int luceneDocId) { }

    private Map<String, List<String>> buildHighlights(final Highlighter highlighter,
                                                      final Analyzer analyzer,
                                                      final IndexReader reader,
                                                      final int docId,
                                                      final String content) throws Exception {
        if (content == null || content.isEmpty()) {
            return Map.of();
        }
        final TokenStream stream = TokenSources.getTokenStream(LuceneFields.CONTENT,
            reader.termVectors().get(docId), content, analyzer, -1);
        final String fragment = highlighter.getBestFragment(stream, content);
        if (fragment == null) {
            return Map.of();
        }
        final Map<String, List<String>> highlights = new LinkedHashMap<>();
        highlights.put(LuceneFields.CONTENT, List.of(fragment));
        return highlights;
    }
}

package io.searchable.core.infrastructure.lucene;

import io.searchable.core.application.HybridSearchOrchestrator;
import io.searchable.core.domain.document.Document;
import io.searchable.core.domain.search.ChunkAggregation;
import io.searchable.core.domain.search.PaginationParams;
import io.searchable.core.domain.search.SearchHit;
import io.searchable.core.domain.search.SearchOptions;
import io.searchable.core.domain.search.SearchRequest;
import io.searchable.core.domain.search.SearchResult;
import io.searchable.core.infrastructure.chunking.FixedSizeChunkingStrategy;
import io.searchable.core.infrastructure.embedding.HashEmbeddingProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour of each {@link ChunkAggregation} policy on the vector path, and
 * the granularity the hybrid path feeds into fusion (TASK-016).
 */
class ChunkAggregationTest {

    private static final String NS = "ns-aggregate";

    @TempDir Path tempDir;

    private LuceneIndexProvider provider;
    private HashEmbeddingProvider embedding;
    private LuceneFullTextSearcher fullText;
    private LuceneVectorSearcher vector;

    @BeforeEach
    void setUp() {
        provider = new LuceneIndexProvider(new IndexLayout(tempDir), AnalyzerFactory.japanese());
        embedding = new HashEmbeddingProvider(32);
        final LuceneIndexer indexer = new LuceneIndexer(provider, new LuceneDocumentMapper(),
            embedding, new FixedSizeChunkingStrategy(40, 0), ns -> Set.of());
        fullText = new LuceneFullTextSearcher(provider);
        vector = new LuceneVectorSearcher(provider, embedding);

        indexer.indexBatch(NS, List.of(
            Document.builder().id("d1").namespaceId(NS).title("報告書A")
                .content("売上の報告です。".repeat(20))
                .metadata(Map.of()).build(),
            Document.builder().id("d2").namespaceId(NS).title("報告書B")
                .content("費用の報告です。".repeat(20))
                .metadata(Map.of()).build()));
    }

    @AfterEach
    void tearDown() {
        provider.close();
        embedding.close();
    }

    private static SearchRequest requestWith(final ChunkAggregation aggregation) {
        return SearchRequest.builder()
            .query("報告")
            .options(new SearchOptions(false, 200, true, false, null, null,
                Map.of(), aggregation))
            .pagination(new PaginationParams(0, 10))
            .build();
    }

    @Test
    void indexingProducedSeveralChunksPerDocument() throws Exception {
        assertThat(provider.getOrCreate(NS).documentCount()).isGreaterThan(2L);
    }

    @Test
    void noneKeepsChunksAsSeparateHits() {
        final SearchResult result = vector.search(NS, requestWith(ChunkAggregation.NONE));

        final List<String> ids = result.hits().stream().map(SearchHit::documentId).toList();
        assertThat(ids).hasSizeGreaterThan(ids.stream().distinct().toList().size());
    }

    @Test
    void bestChunkKeepsOneHitPerDocument() {
        final SearchResult result = vector.search(NS, requestWith(ChunkAggregation.BEST_CHUNK));

        final List<String> ids = result.hits().stream().map(SearchHit::documentId).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(result.hits()).allSatisfy(hit -> assertThat(hit.subResults()).isEmpty());
    }

    @Test
    void bestChunkRepresentsDocumentByItsHighestScoringChunk() {
        final SearchResult chunks = vector.search(NS, requestWith(ChunkAggregation.NONE));
        final SearchResult folded = vector.search(NS, requestWith(ChunkAggregation.BEST_CHUNK));

        final double bestOfD1 = chunks.hits().stream()
            .filter(h -> h.documentId().equals("d1"))
            .mapToDouble(SearchHit::score).max().orElseThrow();
        final double foldedD1 = folded.hits().stream()
            .filter(h -> h.documentId().equals("d1"))
            .mapToDouble(SearchHit::score).findFirst().orElseThrow();

        assertThat(foldedD1).isEqualTo(bestOfD1);
    }

    @Test
    void documentPolicyAttachesRemainingChunksAsSubResults() {
        final SearchResult result = vector.search(NS, requestWith(ChunkAggregation.DOCUMENT));

        final List<String> ids = result.hits().stream().map(SearchHit::documentId).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(result.hits()).anySatisfy(hit -> assertThat(hit.subResults()).isNotEmpty());
        assertThat(result.hits()).allSatisfy(hit ->
            assertThat(hit.subResults()).allSatisfy(sub ->
                assertThat(sub.parentDocumentId()).isEqualTo(hit.documentId())));
    }

    @Test
    void hybridFusesAtDocumentGranularityEvenWhenPolicyIsNone() {
        try (HybridSearchOrchestrator orchestrator = new HybridSearchOrchestrator(fullText, vector)) {
            final SearchResult result = orchestrator.parallel(NS, requestWith(ChunkAggregation.NONE));

            final List<String> ids = result.hits().stream().map(SearchHit::documentId).toList();
            assertThat(ids).doesNotHaveDuplicates();
        }
    }
}

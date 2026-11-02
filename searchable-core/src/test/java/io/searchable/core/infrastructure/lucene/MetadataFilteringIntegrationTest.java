package io.searchable.core.infrastructure.lucene;

import io.searchable.core.application.HybridSearchOrchestrator;
import io.searchable.core.domain.document.Document;
import io.searchable.core.domain.search.FilterExpression;
import io.searchable.core.domain.search.PaginationParams;
import io.searchable.core.domain.search.SearchHit;
import io.searchable.core.domain.search.SearchOrder;
import io.searchable.core.domain.search.SearchRequest;
import io.searchable.core.domain.search.SearchResult;
import io.searchable.core.infrastructure.chunking.WholeDocumentChunkingStrategy;
import io.searchable.core.infrastructure.embedding.HashEmbeddingProvider;
import org.apache.lucene.search.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end check that a filter narrows candidates at query stage on all
 * three search paths, and that it does not eat into the requested page size
 * the way a post-search filter would (TASK-010).
 */
class MetadataFilteringIntegrationTest {

    private static final String NS = "ns-filter";
    private static final Set<String> DECLARED = Set.of("dept", "visibility");
    private static final int PAGE = 5;

    @TempDir Path tempDir;

    private LuceneIndexProvider provider;
    private HashEmbeddingProvider embedding;
    private LuceneFullTextSearcher fullText;
    private LuceneVectorSearcher vector;

    @BeforeEach
    void setUp() {
        provider = new LuceneIndexProvider(new IndexLayout(tempDir), AnalyzerFactory.japanese());
        embedding = new HashEmbeddingProvider(64);
        final LuceneIndexer indexer = new LuceneIndexer(provider, new LuceneDocumentMapper(),
            embedding, new WholeDocumentChunkingStrategy(), ns -> DECLARED);
        fullText = new LuceneFullTextSearcher(provider);
        vector = new LuceneVectorSearcher(provider, embedding);

        // 20 documents, alternating department; every document matches the query term.
        final List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            final Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("dept", i % 2 == 0 ? "sales" : "legal");
            metadata.put("visibility", i < 10 ? "COMPANY" : "DEPARTMENT");
            docs.add(Document.builder()
                .id("doc-" + i)
                .namespaceId(NS)
                .title("報告書 " + i)
                .content("四半期の売上に関する報告書です")
                .metadata(metadata)
                .build());
        }
        indexer.indexBatch(NS, docs);
    }

    @AfterEach
    void tearDown() {
        provider.close();
        embedding.close();
    }

    private static SearchRequest request() {
        return SearchRequest.builder()
            .query("報告書")
            .pagination(new PaginationParams(0, PAGE))
            .build();
    }

    private static Query filterOn(final String key, final String value) {
        return FilterQueryBuilder.build(FilterExpression.term(key, value), DECLARED);
    }

    private List<String> deptOf(final SearchResult result) {
        // The searchers return hit ids; the parity of the id encodes the department.
        return result.hits().stream().map(SearchHit::documentId)
            .map(id -> Integer.parseInt(id.substring("doc-".length())) % 2 == 0 ? "sales" : "legal")
            .toList();
    }

    @Test
    void fullTextReturnsFullPageOfMatchingDepartment() {
        final SearchResult result = fullText.search(NS, request(), filterOn("dept", "legal"));

        assertThat(result.hits()).hasSize(PAGE);
        assertThat(deptOf(result)).containsOnly("legal");
    }

    @Test
    void vectorReturnsFullPageOfMatchingDepartment() {
        final SearchResult result = vector.search(NS, request(), filterOn("dept", "legal"));

        assertThat(result.hits()).hasSize(PAGE);
        assertThat(deptOf(result)).containsOnly("legal");
    }

    @Test
    void hybridParallelReturnsFullPageOfMatchingDepartment() {
        try (HybridSearchOrchestrator orchestrator = new HybridSearchOrchestrator(fullText, vector)) {
            final SearchResult result = orchestrator.parallel(NS, request(), filterOn("dept", "legal"));

            assertThat(result.hits()).hasSize(PAGE);
            assertThat(deptOf(result)).containsOnly("legal");
        }
    }

    @Test
    void hybridSequentialReturnsOnlyMatchingDepartment() {
        try (HybridSearchOrchestrator orchestrator = new HybridSearchOrchestrator(fullText, vector)) {
            final SearchResult result = orchestrator.sequential(
                NS, request(), SearchOrder.FULL_TEXT_FIRST, filterOn("dept", "legal"));

            assertThat(result.hits()).isNotEmpty();
            assertThat(deptOf(result)).containsOnly("legal");
        }
    }

    @Test
    void postSearchFilteringWouldLoseHitsThatQueryStageFilteringKeeps() {
        // Without a filter the engine fills the page from both departments, so
        // discarding non-matching hits afterwards leaves fewer than PAGE results.
        final SearchResult unfiltered = fullText.search(NS, request());
        final long legalInPage = deptOf(unfiltered).stream().filter("legal"::equals).count();

        assertThat(legalInPage).isLessThan(PAGE);

        final SearchResult filtered = fullText.search(NS, request(), filterOn("dept", "legal"));
        assertThat(filtered.hits()).hasSize(PAGE);
    }

    @Test
    void combinedExpressionNarrowsOnBothKeys() {
        final Query filter = FilterQueryBuilder.build(
            FilterExpression.and(
                FilterExpression.term("dept", "legal"),
                FilterExpression.term("visibility", "DEPARTMENT")),
            DECLARED);

        final SearchResult result = fullText.search(NS, request(), filter);

        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits()).allSatisfy(hit -> {
            final int index = Integer.parseInt(hit.documentId().substring("doc-".length()));
            assertThat(index % 2).isEqualTo(1);
            assertThat(index).isGreaterThanOrEqualTo(10);
        });
    }

    @Test
    void undeclaredKeyIsRejectedBeforeSearching() {
        assertThatThrownBy(() -> FilterQueryBuilder.build(
            FilterExpression.term("secret", "x"), DECLARED))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("secret");
    }
}

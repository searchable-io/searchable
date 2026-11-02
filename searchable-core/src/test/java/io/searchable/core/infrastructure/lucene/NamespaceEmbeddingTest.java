package io.searchable.core.infrastructure.lucene;

import io.searchable.core.domain.document.Document;
import io.searchable.core.domain.embedding.EmbeddingProvider;
import io.searchable.core.domain.search.PaginationParams;
import io.searchable.core.domain.search.SearchRequest;
import io.searchable.core.infrastructure.chunking.WholeDocumentChunkingStrategy;
import io.searchable.core.infrastructure.embedding.HashEmbeddingProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-namespace embedding providers and the dimension guard (TASK-017..019).
 */
class NamespaceEmbeddingTest {

    private static final String SMALL_NS = "ns-small";
    private static final String LARGE_NS = "ns-large";

    @TempDir Path tempDir;

    private LuceneIndexProvider provider;
    private HashEmbeddingProvider small;
    private HashEmbeddingProvider large;
    private LuceneIndexer indexer;
    private LuceneVectorSearcher searcher;

    @BeforeEach
    void setUp() {
        provider = new LuceneIndexProvider(new IndexLayout(tempDir), AnalyzerFactory.japanese());
        small = new HashEmbeddingProvider(32);
        large = new HashEmbeddingProvider(64);
        final Function<String, EmbeddingProvider> resolver =
            ns -> SMALL_NS.equals(ns) ? small : large;
        indexer = new LuceneIndexer(provider, new LuceneDocumentMapper(), resolver,
            new WholeDocumentChunkingStrategy(), ns -> Set.of());
        searcher = new LuceneVectorSearcher(provider, new LuceneDocumentMapper(), resolver);

        indexer.index(doc(SMALL_NS, "s1"));
        indexer.index(doc(LARGE_NS, "l1"));
    }

    @AfterEach
    void tearDown() {
        provider.close();
        small.close();
        large.close();
    }

    private static Document doc(final String namespaceId, final String id) {
        return Document.builder()
            .id(id).namespaceId(namespaceId)
            .title("報告書").content("売上に関する報告です")
            .metadata(Map.of())
            .build();
    }

    private static SearchRequest request() {
        return SearchRequest.builder()
            .query("報告")
            .pagination(new PaginationParams(0, 10))
            .build();
    }

    @Test
    void eachNamespaceUsesItsOwnProvider() {
        assertThat(indexer.embeddingFor(SMALL_NS).dimension()).isEqualTo(32);
        assertThat(indexer.embeddingFor(LARGE_NS).dimension()).isEqualTo(64);
        assertThat(searcher.embeddingFor(SMALL_NS)).isSameAs(small);
        assertThat(searcher.embeddingFor(LARGE_NS)).isSameAs(large);
    }

    @Test
    void bothNamespacesAreSearchableWithTheirOwnDimension() {
        assertThat(searcher.search(SMALL_NS, request()).hits()).hasSize(1);
        assertThat(searcher.search(LARGE_NS, request()).hits()).hasSize(1);
    }

    @Test
    void searchingWithAMismatchedDimensionFailsBeforeTheKnnWalk() {
        final LuceneVectorSearcher mismatched = new LuceneVectorSearcher(
            provider, new LuceneDocumentMapper(), ns -> large);

        assertThatThrownBy(() -> mismatched.search(SMALL_NS, request()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("32")
            .hasMessageContaining("64")
            .hasMessageContaining("rebuild");
    }

    @Test
    void missingProviderIsReportedClearly() {
        final LuceneVectorSearcher none = new LuceneVectorSearcher(
            provider, new LuceneDocumentMapper(), ns -> null);

        assertThatThrownBy(() -> none.search(SMALL_NS, request()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No embedding provider");
    }

    @Test
    void indexerWithoutProviderSkipsVectors() {
        final LuceneIndexer plain = new LuceneIndexer(provider, new LuceneDocumentMapper(),
            (Function<String, EmbeddingProvider>) ns -> null,
            new WholeDocumentChunkingStrategy(), ns -> Set.of());

        plain.index(doc("ns-plain", "p1"));

        assertThat(plain.embeddingFor("ns-plain")).isNull();
    }
}

package io.searchable.core.infrastructure.lucene;

import io.searchable.core.domain.document.Document;
import io.searchable.core.domain.search.FilterExpression;
import io.searchable.core.domain.search.PaginationParams;
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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Metadata-only reindex keeps the body searchable while changing the
 * filterable values (TASK-011).
 */
class MetadataReindexTest {

    private static final String NS = "ns-reindex";
    private static final Set<String> DECLARED = Set.of("dept");

    @TempDir Path tempDir;

    private LuceneIndexProvider provider;
    private HashEmbeddingProvider embedding;
    private LuceneIndexer indexer;
    private LuceneFullTextSearcher fullText;

    @BeforeEach
    void setUp() {
        provider = new LuceneIndexProvider(new IndexLayout(tempDir), AnalyzerFactory.japanese());
        embedding = new HashEmbeddingProvider(32);
        indexer = new LuceneIndexer(provider, new LuceneDocumentMapper(), embedding,
            new WholeDocumentChunkingStrategy(), ns -> DECLARED);
        fullText = new LuceneFullTextSearcher(provider);
        indexer.index(Document.builder()
            .id("d1").namespaceId(NS)
            .title("営業報告")
            .content("四半期の売上に関する詳細な報告です")
            .metadata(Map.of("dept", "sales"))
            .build());
    }

    @AfterEach
    void tearDown() {
        provider.close();
        embedding.close();
    }

    private SearchResult searchWith(final String dept) {
        final Query filter = FilterQueryBuilder.build(
            FilterExpression.term("dept", dept), DECLARED);
        return fullText.search(NS, SearchRequest.builder()
            .query("報告")
            .pagination(new PaginationParams(0, 10))
            .build(), filter);
    }

    @Test
    void reindexSwapsFilterableValueAndKeepsBody() {
        assertThat(searchWith("sales").hits()).hasSize(1);
        assertThat(searchWith("legal").hits()).isEmpty();

        final boolean rewritten = indexer.reindexMetadata(Document.builder()
            .id("d1").namespaceId(NS)
            .title("営業報告")
            .content("")
            .metadata(Map.of("dept", "legal"))
            .build());

        assertThat(rewritten).isTrue();
        assertThat(searchWith("legal").hits()).hasSize(1);
        assertThat(searchWith("sales").hits()).isEmpty();
    }

    @Test
    void bodyRemainsSearchableAfterReindex() {
        indexer.reindexMetadata(Document.builder()
            .id("d1").namespaceId(NS)
            .title("営業報告")
            .content("")
            .metadata(Map.of("dept", "legal"))
            .build());

        final SearchResult result = fullText.search(NS, SearchRequest.builder()
            .query("売上")
            .pagination(new PaginationParams(0, 10))
            .build());

        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().get(0).documentId()).isEqualTo("d1");
    }

    @Test
    void documentCountIsUnchanged() throws Exception {
        final long before = provider.getOrCreate(NS).documentCount();

        indexer.reindexMetadata(Document.builder()
            .id("d1").namespaceId(NS)
            .title("営業報告")
            .content("")
            .metadata(Map.of("dept", "legal"))
            .build());

        assertThat(provider.getOrCreate(NS).documentCount()).isEqualTo(before);
    }

    @Test
    void unknownDocumentIsReportedAsNotRewritten() {
        final boolean rewritten = indexer.reindexMetadata(Document.builder()
            .id("missing").namespaceId(NS)
            .title("x").content("")
            .metadata(Map.of("dept", "legal"))
            .build());

        assertThat(rewritten).isFalse();
    }
}

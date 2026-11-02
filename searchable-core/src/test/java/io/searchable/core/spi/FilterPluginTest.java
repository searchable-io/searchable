package io.searchable.core.spi;

import io.searchable.core.domain.document.Document;
import io.searchable.core.domain.embedding.EmbeddingProvider;
import io.searchable.core.domain.search.FilterExpression;
import io.searchable.core.domain.search.PaginationParams;
import io.searchable.core.domain.search.SearchHit;
import io.searchable.core.domain.search.SearchRequest;
import io.searchable.core.domain.search.SearchResult;
import io.searchable.core.infrastructure.chunking.WholeDocumentChunkingStrategy;
import io.searchable.core.infrastructure.lucene.AnalyzerFactory;
import io.searchable.core.infrastructure.lucene.FilterQueryBuilder;
import io.searchable.core.infrastructure.lucene.IndexLayout;
import io.searchable.core.infrastructure.lucene.LuceneDocumentMapper;
import io.searchable.core.infrastructure.lucene.LuceneFullTextSearcher;
import io.searchable.core.infrastructure.lucene.LuceneIndexProvider;
import io.searchable.core.infrastructure.lucene.LuceneIndexer;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two filter extension points: a field plugin owning both halves of one
 * key, and name-based selection per namespace (TASK-021, TASK-022).
 */
class FilterPluginTest {

    private static final String NS = "ns-plugin";

    /** Lower-cases on both the index and the query side, so case stops mattering. */
    private static final class CaseInsensitiveDept implements MetadataFieldPlugin {
        @Override
        public String name() {
            return "ci-dept";
        }

        @Override
        public boolean supports(final String key) {
            return "dept".equals(key);
        }

        @Override
        public List<IndexableField> indexFields(final String fieldName, final String key,
                                                final Object value) {
            return List.of(new StringField(fieldName,
                value.toString().toLowerCase(Locale.ROOT), Field.Store.NO));
        }

        @Override
        public Query query(final String fieldName, final String key, final List<String> values) {
            return new TermQuery(new Term(fieldName, values.get(0).toLowerCase(Locale.ROOT)));
        }
    }

    private static final class RejectEverything implements SearchHitFilterPlugin {
        @Override
        public String name() {
            return "reject-all";
        }

        @Override
        public boolean accept(final SearchHit hit) {
            return false;
        }
    }

    @TempDir Path tempDir;

    private LuceneIndexProvider provider;
    private LuceneFullTextSearcher fullText;
    private final MetadataFieldPlugin plugin = new CaseInsensitiveDept();

    @BeforeEach
    void setUp() {
        provider = new LuceneIndexProvider(new IndexLayout(tempDir), AnalyzerFactory.japanese());
        final java.util.function.Function<String, EmbeddingProvider> noEmbedding = ns -> null;
        final LuceneIndexer indexer = new LuceneIndexer(provider, new LuceneDocumentMapper(),
            noEmbedding,
            new WholeDocumentChunkingStrategy(), ns -> Set.of("dept"), ns -> List.of(plugin));
        fullText = new LuceneFullTextSearcher(provider);
        indexer.index(Document.builder()
            .id("d1").namespaceId(NS).title("報告書")
            .content("売上の報告です")
            .metadata(Map.of("dept", "Sales"))
            .build());
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    private SearchResult searchDept(final String value) {
        final Query filter = FilterQueryBuilder.build(
            FilterExpression.term("dept", value), Set.of("dept"), List.of(plugin));
        return fullText.search(NS, SearchRequest.builder()
            .query("報告")
            .pagination(new PaginationParams(0, 10))
            .build(), filter);
    }

    @Test
    void pluginOwnsBothSidesSoCaseStopsMattering() {
        assertThat(searchDept("sales").hits()).hasSize(1);
        assertThat(searchDept("SALES").hits()).hasSize(1);
        assertThat(searchDept("Sales").hits()).hasSize(1);
    }

    @Test
    void builtInExactMatchWouldNotHaveMatched() {
        final Query builtIn = FilterQueryBuilder.build(
            FilterExpression.term("dept", "Sales"), Set.of("dept"), List.of());
        final SearchResult result = fullText.search(NS, SearchRequest.builder()
            .query("報告")
            .pagination(new PaginationParams(0, 10))
            .build(), builtIn);

        assertThat(result.hits()).isEmpty();
    }

    @Test
    void registrySelectsOnlyNamedPlugins() {
        final FilterPluginRegistry registry =
            new FilterPluginRegistry(List.of(plugin), List.of(new RejectEverything()));

        assertThat(registry.fieldPlugins(Set.of("ci-dept"))).containsExactly(plugin);
        assertThat(registry.fieldPlugins(Set.of())).isEmpty();
        assertThat(registry.hitFilters(Set.of("reject-all"))).hasSize(1);
        assertThat(registry.registeredNames()).containsExactlyInAnyOrder("ci-dept", "reject-all");
    }

    @Test
    void aNameOwnedByTheOtherKindIsNotAnError() {
        final FilterPluginRegistry registry =
            new FilterPluginRegistry(List.of(plugin), List.of(new RejectEverything()));

        assertThat(registry.fieldPlugins(Set.of("ci-dept", "reject-all")))
            .containsExactly(plugin);
        assertThat(registry.hitFilters(Set.of("ci-dept", "reject-all"))).hasSize(1);
    }

    @Test
    void anUnknownPluginNameFailsRatherThanWideningTheResults() {
        final FilterPluginRegistry registry =
            new FilterPluginRegistry(List.of(plugin), List.of(new RejectEverything()));

        assertThatThrownBy(() -> registry.fieldPlugins(Set.of("typo-in-config")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("typo-in-config")
            .hasMessageContaining("ci-dept");
        assertThatThrownBy(() -> registry.hitFilters(Set.of("typo-in-config")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateNamesAreRejected() {
        assertThatThrownBy(() -> new FilterPluginRegistry(
            List.of(plugin, new CaseInsensitiveDept()), List.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ci-dept");
    }

    @Test
    void emptyRegistryStillFailsOnAnEnabledName() {
        assertThat(FilterPluginRegistry.empty().fieldPlugins(Set.of())).isEmpty();
        assertThat(FilterPluginRegistry.empty().registeredNames()).isEmpty();
        assertThatThrownBy(() -> FilterPluginRegistry.empty().fieldPlugins(Set.of("ci-dept")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ci-dept");
    }
}

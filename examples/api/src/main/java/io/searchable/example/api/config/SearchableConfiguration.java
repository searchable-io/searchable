package io.searchable.example.api.config;

import io.searchable.core.application.HybridSearchOrchestrator;
import io.searchable.core.application.IndexService;
import io.searchable.core.application.IndexStatisticsService;
import io.searchable.core.application.NamespaceService;
import io.searchable.core.application.SearchPerformanceMonitor;
import io.searchable.core.application.SearchService;
import io.searchable.core.application.config.SearchableGlobalConfig;
import io.searchable.core.application.config.SearchableGlobalConfigProvider;
import io.searchable.core.domain.chunking.ChunkingStrategy;
import io.searchable.core.domain.dictionary.UserDictionaryRepository;
import io.searchable.core.domain.dictionary.UserDictionaryResolver;
import io.searchable.core.domain.embedding.EmbeddingProvider;
import io.searchable.core.domain.index.IndexMetadataRepository;
import io.searchable.core.domain.namespace.NamespaceRepository;
import io.searchable.core.domain.search.SearchOrder;
import io.searchable.core.domain.search.SearchStrategy;
import io.searchable.core.domain.search.SearchType;
import io.searchable.core.infrastructure.chunking.FixedSizeChunkingStrategy;
import io.searchable.core.infrastructure.chunking.ParagraphChunkingStrategy;
import io.searchable.core.infrastructure.chunking.SectionChunkingStrategy;
import io.searchable.core.infrastructure.chunking.SentenceChunkingStrategy;
import io.searchable.core.infrastructure.chunking.WholeDocumentChunkingStrategy;
import io.searchable.core.infrastructure.dictionary.FileUserDictionaryRepository;
import io.searchable.core.infrastructure.dictionary.JdbcUserDictionaryRepository;
import io.searchable.core.infrastructure.embedding.HashEmbeddingProvider;
import io.searchable.core.infrastructure.lucene.AnalyzerFactory;
import io.searchable.core.infrastructure.lucene.UserDictionaryAnalyzerFactory;
import io.searchable.core.infrastructure.lucene.IndexLayout;
import io.searchable.core.infrastructure.lucene.RetentionPolicy;
import io.searchable.core.infrastructure.lucene.LuceneDocumentMapper;
import io.searchable.core.infrastructure.lucene.LuceneFullTextSearcher;
import io.searchable.core.infrastructure.lucene.LuceneIndexProvider;
import io.searchable.core.infrastructure.lucene.LuceneIndexer;
import io.searchable.core.infrastructure.lucene.LuceneVectorSearcher;
import io.searchable.core.infrastructure.persistence.DataSourceFactory;
import io.searchable.core.infrastructure.persistence.PersistenceConfig;
import io.searchable.core.infrastructure.persistence.SchemaInitializer;
import io.searchable.core.infrastructure.persistence.jdbc.JdbcIndexMetadataRepository;
import io.searchable.core.infrastructure.persistence.jdbc.JdbcNamespaceRepository;
import io.searchable.core.infrastructure.plugin.PluginLoader;
import io.searchable.core.infrastructure.runtime.PidFile;
import io.searchable.core.infrastructure.runtime.PidRegistry;
import io.searchable.core.infrastructure.runtime.SighupListener;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Locale;

/**
 * Wires the core services as Spring beans.
 */
@Configuration
@EnableConfigurationProperties(SearchableProperties.class)
public class SearchableConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public DataSource dataSource(final SearchableProperties props) {
        final SearchableProperties.Persistence p = props.getPersistence();
        return DataSourceFactory.create(
            new PersistenceConfig(p.getType(), p.getUrl(), p.getUsername(), p.getPassword()));
    }

    @Bean(initMethod = "initialize")
    public SchemaInitializer schemaInitializer(final DataSource dataSource) {
        return new SchemaInitializer(dataSource);
    }

    @Bean
    public NamespaceRepository namespaceRepository(final DataSource dataSource,
                                                   final SchemaInitializer init) {
        // SchemaInitializer is referenced to ensure the schema is created before
        // any repository starts issuing queries.
        return new JdbcNamespaceRepository(dataSource);
    }

    @Bean
    public IndexMetadataRepository indexMetadataRepository(final DataSource dataSource,
                                                           final SchemaInitializer init) {
        return new JdbcIndexMetadataRepository(dataSource);
    }

    @Bean
    public UserDictionaryRepository userDictionaryRepository(final SearchableProperties props,
                                                             final DataSource dataSource,
                                                             final SchemaInitializer init) {
        final SearchableProperties.Dictionary d = props.getDictionary();
        return switch (d.getStorage().toLowerCase(Locale.ROOT)) {
            case "file" -> new FileUserDictionaryRepository(d.getDirectory());
            case "db" -> new JdbcUserDictionaryRepository(dataSource);
            default -> throw new IllegalArgumentException(
                "Unsupported dictionary storage: " + d.getStorage());
        };
    }

    @Bean
    public UserDictionaryResolver userDictionaryResolver(
            final UserDictionaryRepository repository) {
        return new UserDictionaryResolver(repository);
    }

    @Bean
    public AnalyzerFactory analyzerFactory(final UserDictionaryResolver resolver) {
        return new UserDictionaryAnalyzerFactory(resolver);
    }

    @Bean(destroyMethod = "close")
    public LuceneIndexProvider luceneIndexProvider(final SearchableProperties props,
                                                   final AnalyzerFactory analyzerFactory) {
        final SearchableProperties.Retention retention = props.getIndex().getRetention();
        return new LuceneIndexProvider(
            new IndexLayout(props.getIndex().getDirectory()),
            analyzerFactory,
            RetentionPolicy.of(retention.getGenerations(), retention.getMaxAge()));
    }

    @Bean(destroyMethod = "close")
    public EmbeddingProvider embeddingProvider(final SearchableProperties props) {
        final SearchableProperties.Embedding e = props.getEmbedding();
        return switch (e.getProvider().toLowerCase(Locale.ROOT)) {
            case "hash" -> new HashEmbeddingProvider(e.getDimension());
            // 'onnx' provider requires an externally configured Tokenizer bean and
            // is intentionally not auto-wired here. Override this bean in a
            // user-supplied configuration to enable it.
            default -> throw new IllegalArgumentException(
                "Unsupported embedding provider: " + e.getProvider());
        };
    }

    @Bean
    public ChunkingStrategy chunkingStrategy(final SearchableProperties props) {
        final SearchableProperties.Chunking c = props.getChunking();
        return switch (c.getStrategy().toLowerCase(Locale.ROOT)) {
            case "whole" -> new WholeDocumentChunkingStrategy();
            case "fixed" -> new FixedSizeChunkingStrategy(c.getChunkSize(), c.getOverlap());
            case "sentence" -> new SentenceChunkingStrategy(c.getSentenceTargetSize());
            case "paragraph" -> new ParagraphChunkingStrategy();
            case "section" -> new SectionChunkingStrategy();
            default -> throw new IllegalArgumentException(
                "Unsupported chunking strategy: " + c.getStrategy());
        };
    }

    @Bean
    public LuceneIndexer luceneIndexer(final LuceneIndexProvider provider,
                                       final EmbeddingProvider embeddingProvider,
                                       final ChunkingStrategy chunkingStrategy) {
        return new LuceneIndexer(provider, new LuceneDocumentMapper(),
            embeddingProvider, chunkingStrategy);
    }

    @Bean
    public LuceneFullTextSearcher luceneFullTextSearcher(final LuceneIndexProvider provider) {
        return new LuceneFullTextSearcher(provider);
    }

    @Bean
    public LuceneVectorSearcher luceneVectorSearcher(final LuceneIndexProvider provider,
                                                     final EmbeddingProvider embeddingProvider) {
        return new LuceneVectorSearcher(provider, embeddingProvider);
    }

    @Bean(destroyMethod = "close")
    public PluginLoader pluginLoader(final SearchableProperties props) {
        return new PluginLoader(props.getPlugins().getDirectory());
    }

    @Bean
    public SearchableGlobalConfig globalConfig(final SearchableProperties props) {
        final SearchableProperties.Global g = props.getGlobal();
        return new SearchableGlobalConfig(
            SearchType.valueOf(g.getDefaultArchitecture()),
            SearchStrategy.valueOf(g.getDefaultSearchStrategy()),
            SearchOrder.valueOf(g.getDefaultSearchOrder())
        );
    }

    @Bean
    public SearchableGlobalConfigProvider globalConfigProvider(final SearchableGlobalConfig globalConfig) {
        return new SearchableGlobalConfigProvider(globalConfig);
    }

    @Bean
    public SearchPerformanceMonitor searchPerformanceMonitor() {
        return new SearchPerformanceMonitor();
    }

    @Bean
    public IndexStatisticsService indexStatisticsService(final NamespaceRepository nr,
                                                         final IndexMetadataRepository imr) {
        return new IndexStatisticsService(nr, imr);
    }

    @Bean
    public NamespaceService namespaceService(final NamespaceRepository nr,
                                             final IndexMetadataRepository imr,
                                             final LuceneIndexProvider provider,
                                             final SearchableGlobalConfigProvider provider2,
                                             final Clock clock) {
        return new NamespaceService(nr, imr, provider, provider2, clock);
    }

    @Bean
    public io.searchable.core.domain.document.DocumentMetadataRepository documentMetadataRepository(
            final DataSource dataSource,
            final SchemaInitializer init) {
        return new io.searchable.core.infrastructure.persistence.jdbc.JdbcDocumentMetadataRepository(
            dataSource);
    }

    @Bean
    public IndexService indexService(final NamespaceRepository nr,
                                     final IndexMetadataRepository imr,
                                     final LuceneIndexProvider provider,
                                     final LuceneIndexer indexer,
                                     final io.searchable.core.domain.document.DocumentMetadataRepository dmr,
                                     final Clock clock) {
        return new IndexService(nr, imr, provider, indexer, dmr, clock);
    }

    @Bean(destroyMethod = "close")
    public HybridSearchOrchestrator hybridSearchOrchestrator(
            final LuceneFullTextSearcher fullText,
            final LuceneVectorSearcher vector) {
        return new HybridSearchOrchestrator(fullText, vector);
    }

    @Bean
    public SearchService searchService(final NamespaceRepository nr,
                                       final LuceneFullTextSearcher fullText,
                                       final LuceneVectorSearcher vector,
                                       final HybridSearchOrchestrator hybrid,
                                       final io.searchable.core.domain.document.DocumentMetadataRepository dmr) {
        return new SearchService(nr, fullText, vector, hybrid,
            new io.searchable.core.application.SearchResultEnricher(dmr));
    }

    @Bean
    public PidRegistry pidRegistry(final SearchableProperties props) {
        return new PidRegistry(props.getDataDirectory());
    }

    @Bean
    public IndexStatusReporter indexStatusReporter(final IndexStatisticsService statistics,
                                                   final SearchableProperties props) {
        return new IndexStatusReporter(statistics, props.getDataDirectory());
    }

    @Bean
    public ApiIndexHotReloadBridge apiIndexHotReloadBridge(
            final LuceneIndexProvider provider,
            final SearchableProperties props,
            final IndexStatusReporter reporter) {
        return new ApiIndexHotReloadBridge(provider, props.getDataDirectory(), reporter);
    }

    /**
     * Owns the {@code api.pid} file and the {@code SIGHUP} handler that
     * triggers a refresh of every open Lucene context. The API itself
     * doesn't strictly need to refresh (its writer-attached searcher is
     * NRT) but the same hook lets it react when another process — e.g.
     * the CLI or a second API instance — commits.
     */
    public static final class ApiIndexHotReloadBridge implements AutoCloseable {
        private static final Logger log = LoggerFactory.getLogger(ApiIndexHotReloadBridge.class);
        private static final String APP_NAME = "api";
        private final LuceneIndexProvider provider;
        private final IndexStatusReporter reporter;
        private final PidFile pidFile;
        private final SighupListener listener;

        ApiIndexHotReloadBridge(final LuceneIndexProvider provider,
                                final java.nio.file.Path dataDirectory,
                                final IndexStatusReporter reporter) {
            this.provider = provider;
            this.reporter = reporter;
            this.pidFile = PidFile.open(dataDirectory, APP_NAME);
            this.listener = SighupListener.install(() -> {
                final int n = provider.refresh();
                log.info("SIGHUP received: refreshed {} namespace(s)", n);
                reporter.reportReload();
            });
            if (!listener.isInstalled()) {
                log.warn("api will not auto-refresh on CLI ingest "
                    + "(SIGHUP unavailable on this platform)");
            }
        }

        @PreDestroy
        @Override
        public void close() {
            listener.uninstall();
            pidFile.close();
        }
    }
}

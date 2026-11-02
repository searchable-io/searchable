package io.searchable.admin.config;

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
import io.searchable.ai.AiProviderRegistry;
import io.searchable.ai.SummaryConfig;
import io.searchable.ai.SummaryConfigProvider;
import io.searchable.ai.SummaryService;
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
            false,
            props.getIndex().getBackend(),
            java.time.Clock.systemUTC(),
            java.time.Duration.ofSeconds(30),
            java.time.Duration.ofMinutes(5),
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

    @Bean
    public io.searchable.core.application.BackupService backupService(
            final LuceneIndexProvider provider,
            final SearchableProperties props) {
        return new io.searchable.core.application.BackupService(provider,
            new IndexLayout(props.getIndex().getDirectory()));
    }

    @Bean(destroyMethod = "close")
    public HybridSearchOrchestrator hybridSearchOrchestrator(
            final LuceneFullTextSearcher fullText,
            final LuceneVectorSearcher vector) {
        return new HybridSearchOrchestrator(fullText, vector);
    }

    /**
     * Registry of {@link io.searchable.ai.AiProvider} implementations discovered
     * via {@link java.util.ServiceLoader}. Closed on application shutdown so
     * provider-owned HTTP clients are released.
     */
    @Bean(destroyMethod = "close")
    public AiProviderRegistry aiProviderRegistry() {
        return AiProviderRegistry.discover();
    }

    /**
     * Build a {@link SummaryConfig} from {@code searchable.ai.*} properties.
     * When {@code searchable.ai.enabled=false} (default) the config carries a
     * null provider name so {@link SummaryService} returns
     * {@link SummaryService#disabledResponse()}.
     */
    @Bean
    public SummaryConfig summaryConfig(final SearchableProperties props) {
        final SearchableProperties.Ai a = props.getAi();
        final String providerName = a.isEnabled() && !a.getProvider().isBlank()
            ? a.getProvider() : null;
        final String model = a.getModel().isBlank() ? null : a.getModel();
        return new SummaryConfig(
            providerName,
            model,
            a.getTimeout(),
            a.getMaxTokens(),
            a.getTemperature(),
            a.getMaxContextItems(),
            a.getMaxContextChars(),
            a.isFallbackOnError()
        );
    }

    /**
     * Mutable holder so the admin UI can change the active summarisation
     * configuration without restarting the application.
     */
    @Bean
    public SummaryConfigProvider summaryConfigProvider(final SummaryConfig initial) {
        return new SummaryConfigProvider(initial);
    }

    @Bean
    public SummaryService summaryService(final AiProviderRegistry registry,
                                         final SummaryConfigProvider configProvider) {
        return new SummaryService(registry, configProvider);
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
}

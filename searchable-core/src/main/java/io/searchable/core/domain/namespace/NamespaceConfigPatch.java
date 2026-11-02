package io.searchable.core.domain.namespace;

import io.searchable.core.infrastructure.lucene.AnalyzerType;
import io.searchable.core.domain.search.SearchOrder;
import io.searchable.core.domain.search.SearchStrategy;
import io.searchable.core.domain.search.SearchType;

import java.util.Map;
import java.util.Set;

/**
 * Partial namespace configuration where every field is optional.
 *
 * <p>Used by application services to merge user-supplied overrides on top
 * of global defaults before constructing the strict {@link NamespaceConfig}.
 *
 * <p>{@code indexWeight} is boxed so that {@code null} can express
 * "fall back to the global default" while explicit values (including
 * {@code 0.0}) are honored. {@code indexedMetadataKeys} follows the same
 * rule: {@code null} keeps the default (no key indexed) while an empty set
 * explicitly clears the declaration.
 */
public record NamespaceConfigPatch(
    SearchType architecture,
    SearchStrategy searchStrategy,
    SearchOrder searchOrder,
    EmbeddingConfig embeddingConfig,
    AiConfig aiConfig,
    Double indexWeight,
    Map<String, Object> customParams,
    Set<String> indexedMetadataKeys,
    Set<String> filterPlugins,
    AnalyzerType analyzerType
) {

    public static NamespaceConfigPatch empty() {
        return new NamespaceConfigPatch(null, null, null, null, null, null, null, null, null, null);
    }

    /** Backward-compatible constructor that omits {@code analyzerType}. */
    public NamespaceConfigPatch(final SearchType architecture,
                                final SearchStrategy searchStrategy,
                                final SearchOrder searchOrder,
                                final EmbeddingConfig embeddingConfig,
                                final AiConfig aiConfig,
                                final Double indexWeight,
                                final Map<String, Object> customParams,
                                final Set<String> indexedMetadataKeys,
                                final Set<String> filterPlugins) {
        this(architecture, searchStrategy, searchOrder, embeddingConfig,
            aiConfig, indexWeight, customParams, indexedMetadataKeys, filterPlugins, null);
    }

    /** Backward-compatible constructor that omits {@code filterPlugins}. */
    public NamespaceConfigPatch(final SearchType architecture,
                                final SearchStrategy searchStrategy,
                                final SearchOrder searchOrder,
                                final EmbeddingConfig embeddingConfig,
                                final AiConfig aiConfig,
                                final Double indexWeight,
                                final Map<String, Object> customParams,
                                final Set<String> indexedMetadataKeys) {
        this(architecture, searchStrategy, searchOrder, embeddingConfig,
            aiConfig, indexWeight, customParams, indexedMetadataKeys, null, null);
    }

    /** Backward-compatible constructor that omits {@code indexedMetadataKeys}. */
    public NamespaceConfigPatch(final SearchType architecture,
                                final SearchStrategy searchStrategy,
                                final SearchOrder searchOrder,
                                final EmbeddingConfig embeddingConfig,
                                final AiConfig aiConfig,
                                final Double indexWeight,
                                final Map<String, Object> customParams) {
        this(architecture, searchStrategy, searchOrder, embeddingConfig,
            aiConfig, indexWeight, customParams, null, null, null);
    }

    /** Backward-compatible constructor that omits {@code indexWeight}. */
    public NamespaceConfigPatch(final SearchType architecture,
                                final SearchStrategy searchStrategy,
                                final SearchOrder searchOrder,
                                final EmbeddingConfig embeddingConfig,
                                final AiConfig aiConfig,
                                final Map<String, Object> customParams) {
        this(architecture, searchStrategy, searchOrder, embeddingConfig,
            aiConfig, null, customParams, null, null, null);
    }
}

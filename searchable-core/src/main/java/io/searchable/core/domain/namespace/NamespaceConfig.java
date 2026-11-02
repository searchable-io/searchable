package io.searchable.core.domain.namespace;

import io.searchable.core.infrastructure.lucene.AnalyzerType;
import io.searchable.core.domain.search.SearchOrder;
import io.searchable.core.domain.search.SearchStrategy;
import io.searchable.core.domain.search.SearchType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-namespace configuration controlling search behavior.
 *
 * <p>{@code indexWeight} is the multiplier applied to every hit returned
 * from this namespace when results are aggregated across multiple namespaces.
 * Use values {@code > 1.0} to boost the namespace, {@code 0 &lt; w &lt; 1.0}
 * to suppress it. The default of {@code 1.0} is neutral.
 *
 * <p>{@code indexedMetadataKeys} lists the {@code Document.metadata} keys that
 * are written to the index as searchable fields. Only declared keys may appear
 * in a search filter. When the set is empty the namespace writes no metadata to
 * the index and filters are ignored, which is the behaviour of releases before
 * filtering was introduced.
 *
 * <p>{@code filterPlugins} names the search filter plugins applied to this
 * namespace. A plugin that is on the classpath but not named here is not
 * applied, so adding a dependency never changes an existing namespace.
 *
 * <p>{@code analyzerType} is the analyzer this namespace indexes and queries
 * with. {@code null} means it has not been decided yet: ingestion detects the
 * language once and writes the result back, after which it stays fixed,
 * because the index and the query must be split by the same rules.
 */
public record NamespaceConfig(
    SearchType architecture,
    SearchStrategy searchStrategy,
    SearchOrder searchOrder,
    EmbeddingConfig embeddingConfig,
    AiConfig aiConfig,
    double indexWeight,
    Map<String, Object> customParams,
    Set<String> indexedMetadataKeys,
    Set<String> filterPlugins,
    AnalyzerType analyzerType
) {

    public static final double DEFAULT_INDEX_WEIGHT = 1.0;

    public NamespaceConfig {
        Objects.requireNonNull(architecture, "architecture must not be null");
        Objects.requireNonNull(searchStrategy, "searchStrategy must not be null");
        Objects.requireNonNull(searchOrder, "searchOrder must not be null");
        Objects.requireNonNull(aiConfig, "aiConfig must not be null");
        if (indexWeight < 0.0 || Double.isNaN(indexWeight) || Double.isInfinite(indexWeight)) {
            throw new IllegalArgumentException(
                "indexWeight must be a finite non-negative number, was " + indexWeight);
        }
        customParams = customParams == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(customParams));
        indexedMetadataKeys = copyNames(indexedMetadataKeys, "indexedMetadataKeys");
        filterPlugins = copyNames(filterPlugins, "filterPlugins");
    }

    private static Set<String> copyNames(final Set<String> names, final String field) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        for (final String name : names) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException(field + " must not contain a blank entry");
            }
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(names));
    }

    /** Backward-compatible constructor that omits {@code analyzerType}. */
    public NamespaceConfig(final SearchType architecture,
                           final SearchStrategy searchStrategy,
                           final SearchOrder searchOrder,
                           final EmbeddingConfig embeddingConfig,
                           final AiConfig aiConfig,
                           final double indexWeight,
                           final Map<String, Object> customParams,
                           final Set<String> indexedMetadataKeys,
                           final Set<String> filterPlugins) {
        this(architecture, searchStrategy, searchOrder, embeddingConfig, aiConfig,
            indexWeight, customParams, indexedMetadataKeys, filterPlugins, null);
    }

    /** Backward-compatible constructor that omits {@code filterPlugins}. */
    public NamespaceConfig(final SearchType architecture,
                           final SearchStrategy searchStrategy,
                           final SearchOrder searchOrder,
                           final EmbeddingConfig embeddingConfig,
                           final AiConfig aiConfig,
                           final double indexWeight,
                           final Map<String, Object> customParams,
                           final Set<String> indexedMetadataKeys) {
        this(architecture, searchStrategy, searchOrder, embeddingConfig, aiConfig,
            indexWeight, customParams, indexedMetadataKeys, Set.of(), null);
    }

    /**
     * Backward-compatible constructor that omits {@code indexedMetadataKeys};
     * no metadata key is indexed.
     */
    public NamespaceConfig(final SearchType architecture,
                           final SearchStrategy searchStrategy,
                           final SearchOrder searchOrder,
                           final EmbeddingConfig embeddingConfig,
                           final AiConfig aiConfig,
                           final double indexWeight,
                           final Map<String, Object> customParams) {
        this(architecture, searchStrategy, searchOrder, embeddingConfig, aiConfig,
            indexWeight, customParams, Set.of(), Set.of(), null);
    }

    /**
     * Backward-compatible constructor that omits {@code indexWeight};
     * {@link #DEFAULT_INDEX_WEIGHT} is applied.
     */
    public NamespaceConfig(final SearchType architecture,
                           final SearchStrategy searchStrategy,
                           final SearchOrder searchOrder,
                           final EmbeddingConfig embeddingConfig,
                           final AiConfig aiConfig,
                           final Map<String, Object> customParams) {
        this(architecture, searchStrategy, searchOrder, embeddingConfig, aiConfig,
            DEFAULT_INDEX_WEIGHT, customParams, Set.of(), Set.of(), null);
    }

    /** Default configuration: full-text only, sequential strategy, no AI. */
    public static NamespaceConfig defaults() {
        return new NamespaceConfig(
            SearchType.FULL_TEXT,
            SearchStrategy.SEQUENTIAL,
            SearchOrder.FULL_TEXT_FIRST,
            null,
            AiConfig.disabled(),
            DEFAULT_INDEX_WEIGHT,
            Map.of(),
            Set.of(),
            Set.of(),
            null
        );
    }

    /**
     * A copy of this configuration with the analyzer decided.
     *
     * @param type the analyzer to fix for this namespace
     * @return the new configuration
     */
    public NamespaceConfig withAnalyzerType(final AnalyzerType type) {
        return new NamespaceConfig(architecture, searchStrategy, searchOrder, embeddingConfig,
            aiConfig, indexWeight, customParams, indexedMetadataKeys, filterPlugins, type);
    }

    public boolean isFullTextEnabled() {
        return architecture == SearchType.FULL_TEXT || architecture == SearchType.HYBRID;
    }

    public boolean isVectorEnabled() {
        return architecture == SearchType.VECTOR || architecture == SearchType.HYBRID;
    }

    /**
     * Whether this namespace declares any metadata key as indexable.
     *
     * @return {@code true} when at least one key is declared
     */
    public boolean isMetadataFilteringEnabled() {
        return !indexedMetadataKeys.isEmpty();
    }
}

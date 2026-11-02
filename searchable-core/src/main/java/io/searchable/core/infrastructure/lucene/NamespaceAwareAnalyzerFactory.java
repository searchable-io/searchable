package io.searchable.core.infrastructure.lucene;

import io.searchable.core.domain.namespace.Namespace;
import io.searchable.core.domain.namespace.NamespaceConfig;
import io.searchable.core.domain.namespace.NamespaceRepository;
import org.apache.lucene.analysis.Analyzer;

import java.util.Objects;

/**
 * Analyzer factory that honours each namespace's configured analyzer.
 *
 * <p>A namespace with no analyzer configured falls back to the supplied
 * default, which is also what Kuromoji namespaces use so that per-namespace
 * user dictionaries keep working.
 */
public final class NamespaceAwareAnalyzerFactory implements AnalyzerFactory {

    private final NamespaceRepository namespaces;
    private final AnalyzerFactory fallback;

    /**
     * @param namespaces repository used to read the namespace configuration
     * @param fallback   factory used when the namespace configures no analyzer,
     *                   and for Kuromoji namespaces so user dictionaries apply
     */
    public NamespaceAwareAnalyzerFactory(final NamespaceRepository namespaces,
                                         final AnalyzerFactory fallback) {
        this.namespaces = Objects.requireNonNull(namespaces, "namespaces must not be null");
        this.fallback = Objects.requireNonNull(fallback, "fallback must not be null");
    }

    @Override
    public Analyzer create(final String namespaceId) {
        final AnalyzerType type = namespaces.findById(namespaceId)
            .map(Namespace::config)
            .map(NamespaceConfig::analyzerType)
            .orElse(null);
        if (type == null || type == AnalyzerType.KUROMOJI) {
            return fallback.create(namespaceId);
        }
        return AnalyzerFactory.forType(type).create(namespaceId);
    }
}

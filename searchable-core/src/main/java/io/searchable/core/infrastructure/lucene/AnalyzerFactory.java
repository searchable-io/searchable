package io.searchable.core.infrastructure.lucene;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.ja.JapaneseAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

/**
 * Factory for the per-namespace {@link Analyzer}.
 *
 * <p>{@link #japanese()} returns Lucene's {@link JapaneseAnalyzer} (Kuromoji)
 * for every namespace. {@link NamespaceAwareAnalyzerFactory} picks the
 * analyzer each namespace is configured with instead.
 */
public interface AnalyzerFactory {

    /** Create a new analyzer instance for the given namespace. */
    Analyzer create(String namespaceId);

    /** Default factory backed by Kuromoji {@link JapaneseAnalyzer}. */
    static AnalyzerFactory japanese() {
        return namespaceId -> new JapaneseAnalyzer();
    }

    /** Factory backed by Lucene's language-neutral {@code StandardAnalyzer}. */
    static AnalyzerFactory standard() {
        return namespaceId -> new StandardAnalyzer();
    }

    /** Factory backed by Lucene's {@code EnglishAnalyzer}. */
    static AnalyzerFactory english() {
        return namespaceId -> new EnglishAnalyzer();
    }

    /** Resolve a built-in factory by analyzer type. */
    static AnalyzerFactory forType(final AnalyzerType type) {
        return switch (type) {
            case KUROMOJI -> japanese();
            case SUDACHI -> new SudachiAnalyzerFactory();
            case STANDARD -> standard();
            case ENGLISH -> english();
        };
    }
}

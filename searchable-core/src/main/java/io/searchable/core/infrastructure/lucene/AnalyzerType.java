package io.searchable.core.infrastructure.lucene;

import java.util.Locale;

/**
 * Selectable analyzer implementations.
 *
 * <p>The analyzer is chosen per namespace, falling back to the global
 * configuration ({@code searchable.analyzer}). {@link #KUROMOJI} is the
 * default and always available; {@link #SUDACHI} requires the user to add the
 * {@code com.worksap.nlp:sudachi} + {@code lucene-analyzers-sudachi}
 * artifacts and a Sudachi system dictionary on the classpath.
 *
 * <p>The analyzer decides how text is split into terms, and the same rules
 * must apply when indexing and when querying. Changing the analyzer of a
 * namespace therefore requires rebuilding its index.
 */
public enum AnalyzerType {

    /** Built-in Lucene Kuromoji analyzer (default). */
    KUROMOJI,

    /**
     * Sudachi-backed analyzer (loaded reflectively to keep Sudachi an
     * optional runtime dependency). Falls back to {@link #KUROMOJI}
     * with a warning when the Sudachi classes are not on the classpath.
     */
    SUDACHI,

    /**
     * Lucene's {@code StandardAnalyzer}: language-neutral tokenization with no
     * stemming. Suitable when a namespace mixes languages or holds text that
     * should not be stemmed at all.
     */
    STANDARD,

    /**
     * Lucene's {@code EnglishAnalyzer}: English stemming and stop words.
     * Suitable for English corpora, where Kuromoji would split words in ways
     * that make English queries miss.
     */
    ENGLISH;

    public static AnalyzerType from(final String value) {
        if (value == null || value.isBlank()) {
            return KUROMOJI;
        }
        return AnalyzerType.valueOf(value.toUpperCase(Locale.ROOT));
    }
}

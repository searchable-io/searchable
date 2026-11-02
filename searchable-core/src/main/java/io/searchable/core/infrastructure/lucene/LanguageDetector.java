package io.searchable.core.infrastructure.lucene;

import java.util.Optional;

/**
 * Decides which analyzer suits a namespace by looking at its text.
 *
 * <p>Used once per namespace, when ingestion finds no analyzer configured.
 * The decision is then persisted, because the analyzer that indexed the terms
 * has to be the one that parses the query.
 */
public interface LanguageDetector {

    /**
     * Pick an analyzer for the given sample text.
     *
     * @param sample text taken from the documents about to be indexed
     * @return the analyzer to use, or empty when the sample says nothing
     */
    Optional<AnalyzerType> detect(String sample);

    /**
     * The detector to use when none was supplied.
     *
     * <p>Uses the Lingua backend when it is on the classpath and falls back to
     * the character-ratio heuristic otherwise, so the optional dependency
     * changes accuracy rather than availability.
     *
     * @return the detector
     */
    static LanguageDetector defaultDetector() {
        if (LinguaLanguageDetector.isAvailable()) {
            return new LinguaLanguageDetector();
        }
        return new CjkRatioLanguageDetector();
    }
}

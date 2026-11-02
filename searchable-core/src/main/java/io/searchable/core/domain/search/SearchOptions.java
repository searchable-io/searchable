package io.searchable.core.domain.search;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional per-request search behavior flags.
 *
 * @param highlightEnabled whether to include highlighted snippets in hits
 * @param snippetLength    maximum number of characters per highlighted snippet
 *                         (positive). The default {@value #DEFAULT_SNIPPET_LENGTH}
 *                         matches what most search UIs render in two lines.
 * @param escapeMarkup     when {@code true}, HTML special characters in the
 *                         original document are escaped in highlighted snippets
 *                         so the inserted {@code <mark>} tags remain the only
 *                         active markup.
 * @param lazyLoad         when {@code true}, the search engine returns the
 *                         smallest payload possible (document id, namespace,
 *                         title, score, metadata) and omits the full
 *                         {@code content} field and highlight fragments.
 * @param bm25K1           BM25 {@code k1} term-frequency saturation override.
 * @param bm25B            BM25 {@code b} length-normalization override.
 * @param metaWeights      per-Lucene-field score multipliers
 *                         ({@code field -> boost}). Field names match
 *                         the names stored in the index, e.g.
 *                         {@code "title"} or {@code "content"}. Values
 *                         must be positive. {@code null} or empty means
 *                         "use the default single-field query".
 * @param aggregation      how chunk-level hits are folded into document-level
 *                         hits. Defaults to {@link ChunkAggregation#NONE},
 *                         which keeps the behaviour of earlier releases.
 */
public record SearchOptions(
    boolean highlightEnabled,
    int snippetLength,
    boolean escapeMarkup,
    boolean lazyLoad,
    Double bm25K1,
    Double bm25B,
    Map<String, Double> metaWeights,
    ChunkAggregation aggregation
) {

    public static final int DEFAULT_SNIPPET_LENGTH = 200;

    public SearchOptions {
        if (snippetLength <= 0) {
            snippetLength = DEFAULT_SNIPPET_LENGTH;
        }
        if (bm25K1 != null && (bm25K1 < 0.0 || Double.isNaN(bm25K1) || Double.isInfinite(bm25K1))) {
            throw new IllegalArgumentException(
                "bm25K1 must be a finite non-negative number, was " + bm25K1);
        }
        if (bm25B != null && (bm25B < 0.0 || bm25B > 1.0 || Double.isNaN(bm25B))) {
            throw new IllegalArgumentException(
                "bm25B must be in [0.0, 1.0], was " + bm25B);
        }
        if (metaWeights == null) {
            metaWeights = Map.of();
        } else {
            for (final Map.Entry<String, Double> e : metaWeights.entrySet()) {
                final double v = e.getValue();
                if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0.0) {
                    throw new IllegalArgumentException(
                        "metaWeights[" + e.getKey() + "] must be a finite positive number, was " + v);
                }
            }
            metaWeights = Collections.unmodifiableMap(new LinkedHashMap<>(metaWeights));
        }
        if (aggregation == null) {
            aggregation = ChunkAggregation.NONE;
        }
    }

    /** Backward-compatible constructor that omits the aggregation policy. */
    public SearchOptions(final boolean highlightEnabled,
                         final int snippetLength,
                         final boolean escapeMarkup,
                         final boolean lazyLoad,
                         final Double bm25K1,
                         final Double bm25B,
                         final Map<String, Double> metaWeights) {
        this(highlightEnabled, snippetLength, escapeMarkup, lazyLoad, bm25K1, bm25B,
            metaWeights, ChunkAggregation.NONE);
    }

    /** Backward-compatible constructor matching the original single-arg form. */
    public SearchOptions(final boolean highlightEnabled) {
        this(highlightEnabled, DEFAULT_SNIPPET_LENGTH, true, false, null, null, Map.of());
    }

    public SearchOptions(final boolean highlightEnabled,
                         final int snippetLength,
                         final boolean escapeMarkup) {
        this(highlightEnabled, snippetLength, escapeMarkup, false, null, null, Map.of());
    }

    public SearchOptions(final boolean highlightEnabled,
                         final int snippetLength,
                         final boolean escapeMarkup,
                         final boolean lazyLoad) {
        this(highlightEnabled, snippetLength, escapeMarkup, lazyLoad, null, null, Map.of());
    }

    public SearchOptions(final boolean highlightEnabled,
                         final int snippetLength,
                         final boolean escapeMarkup,
                         final boolean lazyLoad,
                         final Double bm25K1,
                         final Double bm25B) {
        this(highlightEnabled, snippetLength, escapeMarkup, lazyLoad, bm25K1, bm25B, Map.of());
    }

    public static SearchOptions defaults() {
        return new SearchOptions(true, DEFAULT_SNIPPET_LENGTH, true, false, null, null,
            Map.of(), ChunkAggregation.NONE);
    }
}

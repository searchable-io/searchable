package io.searchable.core.domain.search;

/**
 * How chunk-level hits are folded back into document-level hits.
 *
 * <p>A document is indexed as one Lucene sub-document per chunk, so a single
 * document can occupy several places in a result list. This option decides
 * what the caller sees.
 */
public enum ChunkAggregation {

    /**
     * Return chunks as they come out of the engine, without folding.
     * This is the default and matches the behaviour of earlier releases.
     */
    NONE,

    /**
     * Keep one hit per parent document, represented by its highest scoring
     * chunk. Lower scoring chunks of the same document are dropped.
     */
    BEST_CHUNK,

    /**
     * Keep one hit per parent document, with the remaining chunks attached as
     * {@link SearchHit#subResults()}.
     */
    DOCUMENT
}

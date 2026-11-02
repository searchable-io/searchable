package io.searchable.core.spi;

import io.searchable.core.domain.search.SearchHit;
import io.searchable.plugin.SearchablePlugin;

/**
 * Extension point that inspects hits after the search has run.
 *
 * <p>Use this when the decision cannot be expressed as an index lookup, for
 * example when it depends on an external authorization service.
 *
 * <p><strong>Hits rejected here are not replaced.</strong> The engine has
 * already returned at most {@code limit} hits, so a page can come back shorter
 * than requested; the number of rejected hits is reported on the result so the
 * caller can widen {@code limit}. This filter is also never passed to the
 * vector search itself, because Lucene evaluates a KNN filter across the whole
 * segment before walking the graph. Conditions that have to narrow candidates
 * belong in a {@link MetadataFieldPlugin} instead.
 *
 * <p>Implementations are discovered with {@link java.util.ServiceLoader} and
 * then selected per namespace by {@link SearchablePlugin#name()}.
 *
 * @see MetadataFieldPlugin
 */
public interface SearchHitFilterPlugin extends SearchablePlugin {

    /**
     * Whether the hit should be kept.
     *
     * @param hit the hit returned by the engine
     * @return {@code true} to keep it, {@code false} to drop it
     */
    boolean accept(SearchHit hit);
}

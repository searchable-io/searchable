package io.searchable.core.spi;

import io.searchable.plugin.SearchablePlugin;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.Query;

import java.util.List;

/**
 * Extension point that owns how one metadata key is written to the index and
 * how a filter on that key is turned into a Lucene query.
 *
 * <p>Both halves live in one plugin on purpose: a value is only matchable when
 * the query is built the same way the field was written. Splitting them lets
 * the two drift apart, which is the failure this extension point is meant to
 * avoid.
 *
 * <p>Keys that no plugin claims are handled by the built-in exact match, which
 * writes the term without analysis and matches it byte for byte.
 *
 * <p>Implementations are discovered with {@link java.util.ServiceLoader} and
 * then selected per namespace by {@link SearchablePlugin#name()}. A plugin that
 * is registered but not named in the namespace configuration is not applied.
 *
 * <p>Changing or adding a plugin does not rewrite documents that are already
 * indexed; the namespace has to be rebuilt for the new fields to exist.
 *
 * @see SearchHitFilterPlugin
 */
public interface MetadataFieldPlugin extends SearchablePlugin {

    /**
     * Whether this plugin handles the given metadata key.
     *
     * @param key the metadata key as declared on the namespace
     * @return {@code true} when this plugin owns the key
     */
    boolean supports(String key);

    /**
     * Fields to add to the Lucene document for one metadata value.
     *
     * @param fieldName the Lucene field name reserved for this key
     * @param key       the metadata key
     * @param value     the raw value taken from {@code Document.metadata}
     * @return the fields to index, possibly empty
     * @throws IllegalArgumentException when the value cannot be indexed
     */
    List<IndexableField> indexFields(String fieldName, String key, Object value);

    /**
     * Query matching the given values on this key.
     *
     * @param fieldName the Lucene field name reserved for this key
     * @param key       the metadata key
     * @param values    the values the filter asked for, at least one
     * @return the query, which must not contribute to scoring
     */
    Query query(String fieldName, String key, List<String> values);
}

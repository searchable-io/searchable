package io.searchable.core.infrastructure.lucene;

import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.IndexOptions;

/**
 * Field type constants used by the Lucene mapping layer.
 */
public final class LuceneFields {

    /** Stored, indexed, analyzed text with term vectors for highlighting. */
    public static final FieldType ANALYZED_STORED_WITH_VECTORS;

    public static final String ID = "id";
    /** Domain-level document id (same value across all chunks of one document). */
    public static final String PARENT_ID = "parentId";
    /** Position of the chunk within its parent document (0-based). */
    public static final String CHUNK_ORDINAL = "chunkOrdinal";
    public static final String TITLE = "title";
    public static final String CONTENT = "content";
    /** Per-chunk metadata serialized as JSON (heading, level, weight, ...). */
    public static final String CHUNK_METADATA_JSON = "chunkMetadataJson";
    public static final String INDEXED_AT_EPOCH = "indexedAtEpoch";
    /** KNN vector field name. */
    public static final String VECTOR = "vector";
    /** Prefix applied to document metadata keys declared as indexable. */
    public static final String METADATA_FIELD_PREFIX = "meta.";

    static {
        final FieldType type = new FieldType();
        type.setStored(true);
        type.setTokenized(true);
        type.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        type.setStoreTermVectors(true);
        type.setStoreTermVectorPositions(true);
        type.setStoreTermVectorOffsets(true);
        type.freeze();
        ANALYZED_STORED_WITH_VECTORS = type;
    }

    /**
     * Lucene field name used for a declared document metadata key.
     *
     * @param key the metadata key as declared on the namespace
     * @return the field name
     */
    public static String metadataField(final String key) {
        return METADATA_FIELD_PREFIX + key;
    }

    private LuceneFields() { }
}

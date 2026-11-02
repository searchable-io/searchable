package io.searchable.core.infrastructure.lucene;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.searchable.core.domain.chunking.Chunk;
import io.searchable.core.domain.document.Document;
import io.searchable.core.spi.MetadataFieldPlugin;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.VectorSimilarityFunction;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Converts a domain {@link Document} (optionally split into one or more
 * {@link Chunk}s) into Lucene's {@link org.apache.lucene.document.Document}.
 *
 * <p>Lucene-level fields:
 * <ul>
 *   <li>{@code id} = chunk id (e.g. {@code doc-1#0})</li>
 *   <li>{@code parentId} = original document id</li>
 *   <li>{@code chunkOrdinal} = position of the chunk within the parent</li>
 *   <li>{@code title}, {@code content} = stored &amp; analyzed</li>
 *   <li>{@code vector} = KNN float vector (when an embedding is provided)</li>
 *   <li>{@code meta.KEY} = document metadata declared as indexable on the
 *       namespace, written without analysis and not stored, for filtering only</li>
 * </ul>
 */
public final class LuceneDocumentMapper {

    private final ObjectMapper objectMapper;

    public LuceneDocumentMapper() {
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** Backwards-compat overload -- treats the whole document as a single chunk. */
    public org.apache.lucene.document.Document toLucene(final Document doc) {
        return toLucene(doc, null);
    }

    /** Backwards-compat overload -- single chunk with a vector. */
    public org.apache.lucene.document.Document toLucene(final Document doc, final float[] vector) {
        Objects.requireNonNull(doc, "doc must not be null");
        final Chunk whole = new Chunk(doc.id(), 0, Chunk.defaultChunkId(doc.id(), 0),
            doc.title() + "\n" + doc.content(), Map.of("strategy", "whole"));
        return toLucene(doc, whole, vector);
    }

    /** Backwards-compat overload -- no metadata key is indexed. */
    public org.apache.lucene.document.Document toLucene(final Document doc,
                                                        final Chunk chunk,
                                                        final float[] vector) {
        return toLucene(doc, chunk, vector, Set.of());
    }

    /**
     * Build a Lucene sub-document for one chunk of the given document.
     *
     * <p>Document-level fields ({@code namespaceId}, the full parent
     * {@code metadata}) are intentionally <em>not</em> stored here -- they live
     * in the metadata DB ({@code DocumentMetadataRepository}). The Lucene
     * document carries the per-chunk search-time data, the {@code PARENT_ID}
     * link back to the document registry, and the metadata keys the namespace
     * declared as indexable.
     * See {@code docs/devel/design/architecture/overview.md} §5.7.
     *
     * <p>A declared key is written as a {@link StringField} that is not analyzed, with
     * {@code Store.NO}, so values match byte for byte and are not duplicated
     * into the stored fields of every chunk. A value that is neither a
     * {@code String} nor a list of {@code String} is rejected rather than
     * skipped, because a value missing from the index cannot be recovered at
     * search time
     * (see {@code docs/devel/adr/0003-reject-filtering-on-undeclared-metadata-keys.md}).
     * Blank values are skipped: they carry no term to match.
     *
     * @param doc                 parent domain document
     * @param chunk               chunk produced by a {@code ChunkingStrategy}
     * @param vector              L2-normalized embedding vector for the chunk
     *                            (or {@code null} to skip)
     * @param indexedMetadataKeys metadata keys declared as indexable on the namespace
     * @return the Lucene document for this chunk
     */
    public org.apache.lucene.document.Document toLucene(final Document doc,
                                                        final Chunk chunk,
                                                        final float[] vector,
                                                        final Set<String> indexedMetadataKeys) {
        return toLucene(doc, chunk, vector, indexedMetadataKeys, List.of());
    }

    /**
     * Build a Lucene sub-document, letting plugins own the keys they claim.
     *
     * @param doc                 parent domain document
     * @param chunk               chunk produced by a {@code ChunkingStrategy}
     * @param vector              embedding vector for the chunk, or {@code null}
     * @param indexedMetadataKeys metadata keys declared as indexable
     * @param plugins             the plugins enabled for the namespace, in order
     * @return the Lucene document for this chunk
     */
    public org.apache.lucene.document.Document toLucene(final Document doc,
                                                        final Chunk chunk,
                                                        final float[] vector,
                                                        final Set<String> indexedMetadataKeys,
                                                        final List<MetadataFieldPlugin> plugins) {
        Objects.requireNonNull(doc, "doc must not be null");
        Objects.requireNonNull(chunk, "chunk must not be null");
        final org.apache.lucene.document.Document lucene = new org.apache.lucene.document.Document();
        lucene.add(new StringField(LuceneFields.ID, chunk.chunkId(), Field.Store.YES));
        lucene.add(new StringField(LuceneFields.PARENT_ID, doc.id(), Field.Store.YES));
        lucene.add(new StoredField(LuceneFields.CHUNK_ORDINAL, chunk.ordinal()));
        lucene.add(new NumericDocValuesField(LuceneFields.CHUNK_ORDINAL, chunk.ordinal()));
        lucene.add(new Field(LuceneFields.TITLE, doc.title(),
            LuceneFields.ANALYZED_STORED_WITH_VECTORS));
        lucene.add(new Field(LuceneFields.CONTENT, chunk.text(),
            LuceneFields.ANALYZED_STORED_WITH_VECTORS));
        lucene.add(new StoredField(LuceneFields.CHUNK_METADATA_JSON,
            serializeMetadata(chunk.metadata())));
        if (doc.indexedAt() != null) {
            final long epoch = doc.indexedAt().toEpochMilli();
            lucene.add(new NumericDocValuesField(LuceneFields.INDEXED_AT_EPOCH, epoch));
            lucene.add(new StoredField(LuceneFields.INDEXED_AT_EPOCH, epoch));
        }
        if (vector != null) {
            lucene.add(new KnnFloatVectorField(LuceneFields.VECTOR, vector,
                VectorSimilarityFunction.DOT_PRODUCT));
        }
        addDeclaredMetadata(lucene, doc, indexedMetadataKeys, plugins);
        return lucene;
    }

    private void addDeclaredMetadata(final org.apache.lucene.document.Document lucene,
                                     final Document doc,
                                     final Set<String> indexedMetadataKeys,
                                     final List<MetadataFieldPlugin> plugins) {
        if (indexedMetadataKeys == null || indexedMetadataKeys.isEmpty()) {
            return;
        }
        final Map<String, Object> metadata = doc.metadata();
        for (final String key : indexedMetadataKeys) {
            final Object value = metadata.get(key);
            if (value == null) {
                continue;
            }
            if (addViaPlugin(lucene, key, value, plugins)) {
                continue;
            }
            if (value instanceof String text) {
                addTerm(lucene, key, text);
            } else if (value instanceof List<?> list) {
                for (final Object item : list) {
                    if (!(item instanceof String text)) {
                        throw new IllegalArgumentException(unsupportedType(doc, key, item));
                    }
                    addTerm(lucene, key, text);
                }
            } else {
                throw new IllegalArgumentException(unsupportedType(doc, key, value));
            }
        }
    }

    private boolean addViaPlugin(final org.apache.lucene.document.Document lucene,
                                 final String key,
                                 final Object value,
                                 final List<MetadataFieldPlugin> plugins) {
        for (final MetadataFieldPlugin plugin : plugins) {
            if (!plugin.supports(key)) {
                continue;
            }
            final List<IndexableField> fields =
                plugin.indexFields(LuceneFields.metadataField(key), key, value);
            if (fields != null) {
                fields.forEach(lucene::add);
            }
            return true;
        }
        return false;
    }

    private void addTerm(final org.apache.lucene.document.Document lucene,
                         final String key,
                         final String value) {
        if (!value.isBlank()) {
            lucene.add(new StringField(LuceneFields.metadataField(key), value, Field.Store.NO));
        }
    }

    private static String unsupportedType(final Document doc, final String key, final Object value) {
        return "Metadata key '" + key + "' is declared as indexable but document '" + doc.id()
            + "' holds a " + (value == null ? "null" : value.getClass().getName())
            + " value; only String and List<String> can be indexed";
    }

    public Map<String, Object> deserializeMetadata(final String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize document metadata", e);
        }
    }

    private String serializeMetadata(final Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize document metadata", e);
        }
    }
}

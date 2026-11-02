package io.searchable.core.infrastructure.lucene;

import io.searchable.core.domain.chunking.Chunk;
import io.searchable.core.domain.chunking.ChunkingStrategy;
import io.searchable.core.domain.document.Document;
import io.searchable.core.domain.embedding.EmbeddingProvider;
import io.searchable.core.spi.MetadataFieldPlugin;
import io.searchable.core.infrastructure.chunking.WholeDocumentChunkingStrategy;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Indexes domain documents into Lucene.
 *
 * <p>Documents are first passed through a {@link ChunkingStrategy} (defaulting
 * to {@link WholeDocumentChunkingStrategy} for backward compatibility); each
 * resulting {@link Chunk} is stored as its own Lucene sub-document keyed by
 * {@link LuceneFields#PARENT_ID}. When an {@link EmbeddingProvider} is
 * supplied the chunk text is embedded and stored in the KNN vector field.
 */
public final class LuceneIndexer {

    private static final Logger log = LoggerFactory.getLogger(LuceneIndexer.class);

    private final LuceneIndexProvider provider;
    private final LuceneDocumentMapper mapper;
    private final Function<String, EmbeddingProvider> embeddingResolver;
    private final ChunkingStrategy chunkingStrategy;
    private final Function<String, Set<String>> indexedKeysResolver;
    private final Function<String, List<MetadataFieldPlugin>> fieldPluginResolver;

    public LuceneIndexer(final LuceneIndexProvider provider) {
        this(provider, new LuceneDocumentMapper(), null, new WholeDocumentChunkingStrategy());
    }

    public LuceneIndexer(final LuceneIndexProvider provider,
                         final EmbeddingProvider embeddingProvider) {
        this(provider, new LuceneDocumentMapper(), embeddingProvider,
            new WholeDocumentChunkingStrategy());
    }

    public LuceneIndexer(final LuceneIndexProvider provider,
                         final LuceneDocumentMapper mapper,
                         final EmbeddingProvider embeddingProvider,
                         final ChunkingStrategy chunkingStrategy) {
        this(provider, mapper, embeddingProvider, chunkingStrategy, ns -> Set.of());
    }

    public LuceneIndexer(final LuceneIndexProvider provider,
                         final LuceneDocumentMapper mapper,
                         final EmbeddingProvider embeddingProvider,
                         final ChunkingStrategy chunkingStrategy,
                         final Function<String, Set<String>> indexedKeysResolver) {
        this(provider, mapper, ns -> embeddingProvider, chunkingStrategy, indexedKeysResolver);
    }

    /**
     * Full form, where the embedding provider is resolved per namespace so
     * that namespaces can use different models.
     */
    public LuceneIndexer(final LuceneIndexProvider provider,
                         final LuceneDocumentMapper mapper,
                         final Function<String, EmbeddingProvider> embeddingResolver,
                         final ChunkingStrategy chunkingStrategy,
                         final Function<String, Set<String>> indexedKeysResolver) {
        this(provider, mapper, embeddingResolver, chunkingStrategy, indexedKeysResolver,
            ns -> List.of());
    }

    /**
     * Full form, where metadata field plugins are resolved per namespace.
     */
    public LuceneIndexer(final LuceneIndexProvider provider,
                         final LuceneDocumentMapper mapper,
                         final Function<String, EmbeddingProvider> embeddingResolver,
                         final ChunkingStrategy chunkingStrategy,
                         final Function<String, Set<String>> indexedKeysResolver,
                         final Function<String, List<MetadataFieldPlugin>> fieldPluginResolver) {
        this.indexedKeysResolver = Objects.requireNonNull(indexedKeysResolver,
            "indexedKeysResolver must not be null");
        this.fieldPluginResolver = Objects.requireNonNull(fieldPluginResolver,
            "fieldPluginResolver must not be null");
        this.embeddingResolver = Objects.requireNonNull(embeddingResolver,
            "embeddingResolver must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.chunkingStrategy = Objects.requireNonNull(chunkingStrategy,
            "chunkingStrategy must not be null");
    }

    /** Insert or replace a single document; visible after the next refresh. */
    public void index(final Document document) {
        Objects.requireNonNull(document, "document must not be null");
        final LuceneIndexContext ctx = provider.getOrCreate(document.namespaceId());
        try {
            final IndexWriter writer = ctx.writer();
            writeChunks(writer, document);
            writer.commit();
            ctx.refresh();
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to index document " + document.id(), e);
        }
    }

    /** Insert or replace a batch of documents; single commit at the end. */
    public void indexBatch(final String namespaceId, final Iterable<Document> documents) {
        Objects.requireNonNull(namespaceId, "namespaceId must not be null");
        Objects.requireNonNull(documents, "documents must not be null");
        final LuceneIndexContext ctx = provider.getOrCreate(namespaceId);
        try {
            final IndexWriter writer = ctx.writer();
            int count = 0;
            for (final Document doc : documents) {
                if (!namespaceId.equals(doc.namespaceId())) {
                    throw new IllegalArgumentException(
                        "Document " + doc.id() + " does not belong to namespace " + namespaceId);
                }
                writeChunks(writer, doc);
                count++;
            }
            writer.commit();
            ctx.refresh();
            log.info("indexed {} documents into namespace {}", count, namespaceId);
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to index batch into namespace " + namespaceId, e);
        }
    }

    /** Delete a single document and all its chunks; returns whether anything matched. */
    public boolean delete(final String namespaceId, final String documentId) {
        Objects.requireNonNull(namespaceId, "namespaceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        if (!provider.isOpen(namespaceId)) {
            return false;
        }
        final LuceneIndexContext ctx = provider.getOrCreate(namespaceId);
        try {
            final IndexWriter writer = ctx.writer();
            final long before = ctx.documentCount();
            writer.deleteDocuments(new Term(LuceneFields.PARENT_ID, documentId));
            writer.commit();
            ctx.refresh();
            return ctx.documentCount() < before;
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to delete document " + documentId, e);
        }
    }

    /**
     * Rebuild the namespace from scratch: open a fresh build directory,
     * write all provided documents, and atomically promote it as the new
     * live version. The previous live context is retired by the provider
     * after its grace period.
     *
     * <p>Unlike {@link #indexBatch(String, Iterable)}, this does not touch
     * the existing {@link IndexWriter} on the current version, so it can
     * run concurrently with another process holding that writer's
     * {@code write.lock}.
     *
     * @return number of documents written
     */
    public int rebuild(final String namespaceId, final Iterable<Document> documents) {
        Objects.requireNonNull(namespaceId, "namespaceId must not be null");
        Objects.requireNonNull(documents, "documents must not be null");
        final LuceneIndexProvider.BuildHandle handle = provider.beginBuild(namespaceId);
        int count = 0;
        try {
            final IndexWriter writer = handle.writer();
            for (final Document doc : documents) {
                if (!namespaceId.equals(doc.namespaceId())) {
                    throw new IllegalArgumentException(
                        "Document " + doc.id() + " does not belong to namespace " + namespaceId);
                }
                writeChunks(writer, doc);
                count++;
            }
        } catch (IOException | RuntimeException e) {
            provider.cancelBuild(handle);
            if (e instanceof IOException ioe) {
                throw new IllegalStateException(
                    "Failed to write rebuild batch for namespace " + namespaceId, ioe);
            }
            throw (RuntimeException) e;
        }
        provider.completeBuild(handle);
        log.info("rebuilt namespace {} with {} documents", namespaceId, count);
        return count;
    }

    /** Drop all documents in a namespace (keeps the index open). */
    public void deleteAll(final String namespaceId) {
        Objects.requireNonNull(namespaceId, "namespaceId must not be null");
        final LuceneIndexContext ctx = provider.getOrCreate(namespaceId);
        try {
            ctx.writer().deleteAll();
            ctx.writer().commit();
            ctx.refresh();
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to clear namespace " + namespaceId, e);
        }
    }

    /**
     * Rewrite the indexed metadata of an existing document without re-sending
     * its body.
     *
     * <p>Lucene cannot update a single field, so the document's chunks have to
     * be written again. Rather than re-chunking a body the caller would have to
     * supply, the existing chunk texts are read back from the index and reused
     * verbatim; only the declared metadata fields change. Vectors are not
     * stored, so they are recomputed from the same chunk text.
     *
     * @param document carries the id, namespace, title and the new metadata;
     *                 its content is ignored because chunk texts are reused
     * @return {@code true} when the document existed and was rewritten
     */
    public boolean reindexMetadata(final Document document) {
        Objects.requireNonNull(document, "document must not be null");
        final LuceneIndexContext ctx = provider.getOrCreate(document.namespaceId());
        final List<Chunk> existing = readChunks(ctx, document.id());
        if (existing.isEmpty()) {
            return false;
        }
        final Set<String> indexedKeys = resolveIndexedKeys(document.namespaceId());
        final EmbeddingProvider embedding = embeddingFor(document.namespaceId());
        final List<MetadataFieldPlugin> fieldPlugins = resolveFieldPlugins(document.namespaceId());
        try {
            final IndexWriter writer = ctx.writer();
            writer.deleteDocuments(new Term(LuceneFields.PARENT_ID, document.id()));
            for (final Chunk chunk : existing) {
                final float[] vector = embedWith(embedding, chunk.text());
                writer.addDocument(mapper.toLucene(document, chunk, vector, indexedKeys, fieldPlugins));
            }
            writer.commit();
            ctx.refresh();
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to reindex metadata for document " + document.id(), e);
        }
        log.debug("reindexed metadata of {} ({} chunks)", document.id(), existing.size());
        return true;
    }

    private List<Chunk> readChunks(final LuceneIndexContext ctx, final String documentId) {
        IndexSearcher searcher = null;
        try {
            searcher = ctx.acquireSearcher();
            final TopDocs hits = searcher.search(
                new TermQuery(new Term(LuceneFields.PARENT_ID, documentId)), Integer.MAX_VALUE);
            final List<Chunk> chunks = new ArrayList<>(hits.scoreDocs.length);
            for (final ScoreDoc scoreDoc : hits.scoreDocs) {
                final org.apache.lucene.document.Document stored =
                    searcher.storedFields().document(scoreDoc.doc);
                chunks.add(toChunk(documentId, stored));
            }
            chunks.sort(Comparator.comparingInt(Chunk::ordinal));
            return chunks;
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to read existing chunks for document " + documentId, e);
        } finally {
            try {
                if (searcher != null) {
                    ctx.release(searcher);
                }
            } catch (IOException ignored) {
                // best-effort release
            }
        }
    }

    private Chunk toChunk(final String documentId, final org.apache.lucene.document.Document stored) {
        final int ordinal = stored.getField(LuceneFields.CHUNK_ORDINAL).numericValue().intValue();
        final Map<String, Object> metadata =
            mapper.deserializeMetadata(stored.get(LuceneFields.CHUNK_METADATA_JSON));
        return new Chunk(documentId, ordinal, stored.get(LuceneFields.ID),
            stored.get(LuceneFields.CONTENT), metadata);
    }

    private void writeChunks(final IndexWriter writer, final Document doc) throws IOException {
        final List<Chunk> chunks = chunkingStrategy.chunk(doc);
        final Set<String> indexedKeys = resolveIndexedKeys(doc.namespaceId());
        final EmbeddingProvider embedding = embeddingFor(doc.namespaceId());
        final List<MetadataFieldPlugin> fieldPlugins = resolveFieldPlugins(doc.namespaceId());
        // Replace any prior chunks for this parent document.
        writer.deleteDocuments(new Term(LuceneFields.PARENT_ID, doc.id()));
        for (final Chunk chunk : chunks) {
            final float[] vector = embedWith(embedding, chunk.text());
            writer.addDocument(mapper.toLucene(doc, chunk, vector, indexedKeys, fieldPlugins));
        }
    }

    /**
     * The embedding provider assigned to a namespace, or {@code null} when the
     * namespace indexes without vectors.
     */
    public EmbeddingProvider embeddingFor(final String namespaceId) {
        return embeddingResolver.apply(namespaceId);
    }

    private static float[] embedWith(final EmbeddingProvider embedding, final String text) {
        return embedding == null ? null : embedding.embed(text);
    }

    private List<MetadataFieldPlugin> resolveFieldPlugins(final String namespaceId) {
        final List<MetadataFieldPlugin> plugins = fieldPluginResolver.apply(namespaceId);
        return plugins == null ? List.of() : plugins;
    }

    private Set<String> resolveIndexedKeys(final String namespaceId) {
        final Set<String> keys = indexedKeysResolver.apply(namespaceId);
        return keys == null ? Set.of() : keys;
    }
}

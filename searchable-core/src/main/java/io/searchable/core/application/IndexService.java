package io.searchable.core.application;

import io.searchable.core.domain.document.ContentHashes;
import io.searchable.core.domain.document.Document;
import io.searchable.core.domain.namespace.Namespace;
import io.searchable.core.infrastructure.lucene.LanguageDetector;
import io.searchable.core.infrastructure.lucene.AnalyzerType;
import io.searchable.core.domain.embedding.EmbeddingProvider;
import io.searchable.core.domain.document.DocumentMetadataRecord;
import io.searchable.core.domain.document.DocumentMetadataRepository;
import io.searchable.core.domain.document.DocumentSource;
import io.searchable.core.domain.index.IndexMetadata;
import io.searchable.core.domain.index.IndexMetadataRepository;
import io.searchable.core.domain.index.IndexStatus;
import io.searchable.core.domain.namespace.NamespaceRepository;
import io.searchable.core.infrastructure.lucene.LuceneIndexContext;
import io.searchable.core.infrastructure.lucene.LuceneIndexProvider;
import io.searchable.core.infrastructure.lucene.LuceneIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Application-layer service for index lifecycle operations.
 *
 * <p>Wraps the low-level {@link LuceneIndexer} and keeps the namespace's
 * {@link IndexMetadata} row in sync after each mutation.
 */
public final class IndexService {

    /** Index statistics key holding the embedding model identifier. */
    public static final String EMBEDDING_MODEL_KEY = "embeddingModel";
    /** Index statistics key holding the embedding vector dimension. */
    public static final String EMBEDDING_DIMENSION_KEY = "embeddingDimension";

    /** How much ingested text is sampled when detecting the analyzer. */
    private static final int ANALYZER_SAMPLE_CHARS = 4000;

    private final LanguageDetector languageDetector = LanguageDetector.defaultDetector();

    private static final Logger log = LoggerFactory.getLogger(IndexService.class);

    private final NamespaceRepository namespaces;
    private final IndexMetadataRepository indexMetadata;
    private final LuceneIndexProvider indexProvider;
    private final LuceneIndexer indexer;
    private final DocumentMetadataRepository documentMetadata;
    private final Clock clock;

    public IndexService(final NamespaceRepository namespaces,
                        final IndexMetadataRepository indexMetadata,
                        final LuceneIndexProvider indexProvider,
                        final LuceneIndexer indexer,
                        final Clock clock) {
        this(namespaces, indexMetadata, indexProvider, indexer, null, clock);
    }

    public IndexService(final NamespaceRepository namespaces,
                        final IndexMetadataRepository indexMetadata,
                        final LuceneIndexProvider indexProvider,
                        final LuceneIndexer indexer,
                        final DocumentMetadataRepository documentMetadata,
                        final Clock clock) {
        this.namespaces = Objects.requireNonNull(namespaces);
        this.indexMetadata = Objects.requireNonNull(indexMetadata);
        this.indexProvider = Objects.requireNonNull(indexProvider);
        this.indexer = Objects.requireNonNull(indexer);
        this.documentMetadata = documentMetadata;
        this.clock = Objects.requireNonNull(clock);
    }

    public void index(final Document document) {
        Objects.requireNonNull(document, "document must not be null");
        requireNamespaceExists(document.namespaceId());
        verifyEmbeddingModel(document.namespaceId());
        indexer.index(document);
        recordMetadata(document, effectiveHash(document));
        refreshMetadata(document.namespaceId(), IndexStatus.READY);
    }

    /**
     * Index the document only if its content has changed since the last
     * ingest. Change detection compares the SHA-256 content hash supplied
     * via {@code document.source().contentHash()} (or computed on the fly)
     * against the value stored alongside the document's metadata.
     *
     * @return {@code true} when the document was actually written to the index
     */
    public boolean indexIfChanged(final Document document) {
        Objects.requireNonNull(document, "document must not be null");
        requireNamespaceExists(document.namespaceId());
        verifyEmbeddingModel(document.namespaceId());
        if (documentMetadata == null) {
            // No repository configured -- behave like {@link #index(Document)}.
            index(document);
            return true;
        }
        final String newHash = effectiveHash(document);
        final Optional<String> previousHash = documentMetadata
            .findById(document.namespaceId(), document.id())
            .map(DocumentMetadataRecord::source)
            .filter(s -> s != null)
            .map(DocumentSource::contentHash)
            .filter(h -> h != null);
        if (previousHash.isPresent() && previousHash.get().equals(newHash)) {
            log.debug("skipping unchanged document {} (hash={})", document.id(), newHash);
            return false;
        }
        indexer.index(document);
        recordMetadata(document, newHash);
        refreshMetadata(document.namespaceId(), IndexStatus.READY);
        return true;
    }

    /**
     * Replace the indexed metadata of an existing document without re-sending
     * its body.
     *
     * <p>Lucene cannot update a single field, so changing a value that is
     * declared as indexable normally means ingesting the whole document again.
     * This entry point reuses the chunk texts already held in the index and the
     * title held in the metadata DB, so the caller only supplies the new
     * metadata.
     *
     * @param namespaceId the namespace holding the document
     * @param documentId  the document to update
     * @param metadata    the metadata that replaces the stored one
     * @return {@code true} when the document existed and was rewritten
     * @throws IllegalStateException when no metadata repository is configured
     */
    public boolean reindexMetadata(final String namespaceId, final String documentId,
                                   final Map<String, Object> metadata) {
        Objects.requireNonNull(namespaceId, "namespaceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        requireNamespaceExists(namespaceId);
        if (documentMetadata == null) {
            throw new IllegalStateException(
                "Metadata-only reindex requires a document metadata repository");
        }
        final DocumentMetadataRecord record = documentMetadata.findById(namespaceId, documentId)
            .orElseThrow(() -> new NoSuchElementException(
                "Document not found: " + documentId + " in namespace " + namespaceId));
        final Document document = Document.builder()
            .id(documentId)
            .namespaceId(namespaceId)
            .title(record.title())
            .content("")
            .metadata(metadata)
            .indexedAt(record.indexedAt())
            .source(record.source())
            .build();
        if (!indexer.reindexMetadata(document)) {
            return false;
        }
        documentMetadata.save(new DocumentMetadataRecord(
            namespaceId, documentId, record.title(), metadata,
            record.indexedAt(), record.source()));
        refreshMetadata(namespaceId, IndexStatus.READY);
        return true;
    }

    /**
     * Record which embedding model produced the vectors in this namespace.
     *
     * <p>Stored alongside the index statistics so a later ingest can tell
     * whether it is about to mix vectors from a different model.
     */
    private Map<String, Object> embeddingStatistics(final String namespaceId) {
        final EmbeddingProvider embedding = indexer.embeddingFor(namespaceId);
        if (embedding == null) {
            return Map.of();
        }
        return Map.of(
            EMBEDDING_MODEL_KEY, embedding.identifier(),
            EMBEDDING_DIMENSION_KEY, embedding.dimension());
    }

    /**
     * Refuse to write when the namespace already holds vectors from another
     * model.
     *
     * <p>Writing vectors of a different dimension into the same field breaks
     * the index, and a same-dimension model change silently degrades every
     * later search, so both are stopped before any document is touched. A
     * namespace that is still empty, or that was built before this was
     * recorded, is left alone.
     */
    private void verifyEmbeddingModel(final String namespaceId) {
        final EmbeddingProvider embedding = indexer.embeddingFor(namespaceId);
        if (embedding == null) {
            return;
        }
        final Optional<IndexMetadata> recorded = indexMetadata.findByNamespaceId(namespaceId);
        if (recorded.isEmpty() || recorded.get().documentCount() == 0L) {
            return;
        }
        final Map<String, Object> statistics = recorded.get().statistics();
        final Object model = statistics.get(EMBEDDING_MODEL_KEY);
        final Object dimension = statistics.get(EMBEDDING_DIMENSION_KEY);
        if (model == null || !(dimension instanceof Number recordedDimension)) {
            return;
        }
        if (!model.equals(embedding.identifier())
            || recordedDimension.intValue() != embedding.dimension()) {
            throw new IllegalStateException(
                "Namespace " + namespaceId + " was indexed with embedding model '" + model
                    + "' (dimension " + recordedDimension.intValue() + ") but the configured provider is '"
                    + embedding.identifier() + "' (dimension " + embedding.dimension()
                    + "); rebuild the namespace before switching models");
        }
    }

    /**
     * Decide the namespace's analyzer from the text being ingested, once.
     *
     * <p>Only namespaces that have not been given an analyzer are touched, and
     * the decision is written back before the index is built. Terms are split
     * by the analyzer at index time and queries are split by it at search
     * time, so re-deciding later would break every existing term; fixing it on
     * first ingest is what keeps the two sides in step.
     */
    private void ensureAnalyzerDecided(final String namespaceId, final List<Document> documents) {
        final Namespace namespace = namespaces.findById(namespaceId).orElse(null);
        if (namespace == null || namespace.config().analyzerType() != null || documents.isEmpty()) {
            return;
        }
        final Optional<AnalyzerType> detected = languageDetector.detect(sampleText(documents));
        if (detected.isEmpty()) {
            return;
        }
        namespaces.save(namespace.withConfig(
            namespace.config().withAnalyzerType(detected.get()), clock.instant()));
        log.info("namespace {} will use the {} analyzer, detected from the ingested text",
            namespaceId, detected.get());
    }

    private static String sampleText(final List<Document> documents) {
        final StringBuilder sample = new StringBuilder();
        for (final Document document : documents) {
            if (sample.length() >= ANALYZER_SAMPLE_CHARS) {
                break;
            }
            sample.append(document.title()).append(' ').append(document.content()).append('\n');
        }
        return sample.length() > ANALYZER_SAMPLE_CHARS
            ? sample.substring(0, ANALYZER_SAMPLE_CHARS)
            : sample.toString();
    }

    private String effectiveHash(final Document document) {
        if (document.source() != null && document.source().contentHash() != null) {
            return document.source().contentHash();
        }
        return ContentHashes.hash(document);
    }

    private void recordMetadata(final Document document, final String hash) {
        if (documentMetadata == null) {
            return;
        }
        final Instant indexedAt = document.indexedAt() == null
            ? clock.instant()
            : document.indexedAt();
        final DocumentSource existing = document.source();
        final DocumentSource source = existing == null
            ? new DocumentSource("inline", document.id(), hash, null)
            : new DocumentSource(existing.type(), existing.location(), hash,
                existing.sourceUpdated());
        documentMetadata.save(new DocumentMetadataRecord(
            document.namespaceId(),
            document.id(),
            document.title(),
            document.metadata(),
            indexedAt,
            source));
    }

    public void indexBatch(final String namespaceId, final List<Document> documents) {
        Objects.requireNonNull(namespaceId, "namespaceId must not be null");
        Objects.requireNonNull(documents, "documents must not be null");
        requireNamespaceExists(namespaceId);
        verifyEmbeddingModel(namespaceId);
        ensureAnalyzerDecided(namespaceId, documents);

        markStatus(namespaceId, IndexStatus.INDEXING);
        try {
            indexer.indexBatch(namespaceId, documents);
            for (final Document d : documents) {
                recordMetadata(d, effectiveHash(d));
            }
            refreshMetadata(namespaceId, IndexStatus.READY);
        } catch (RuntimeException e) {
            markStatus(namespaceId, IndexStatus.ERROR);
            throw e;
        }
    }

    public boolean delete(final String namespaceId, final String documentId) {
        Objects.requireNonNull(namespaceId, "namespaceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        requireNamespaceExists(namespaceId);
        final boolean removed = indexer.delete(namespaceId, documentId);
        if (removed) {
            if (documentMetadata != null) {
                documentMetadata.delete(namespaceId, documentId);
            }
            refreshMetadata(namespaceId, IndexStatus.READY);
        }
        return removed;
    }

    /**
     * Full-rebuild ingest: write the given documents into a fresh build
     * directory and atomically promote it as the new live version. Used
     * by the CLI {@code ingest} so it can run concurrently with apps
     * holding the previous version's {@code IndexWriter} (e.g. the
     * example API). The namespace's metadata DB rows are replaced to
     * match the new index contents.
     *
     * @return number of documents written
     */
    public int rebuildFrom(final String namespaceId, final Iterable<Document> documents) {
        Objects.requireNonNull(namespaceId, "namespaceId must not be null");
        Objects.requireNonNull(documents, "documents must not be null");
        requireNamespaceExists(namespaceId);
        markStatus(namespaceId, IndexStatus.INDEXING);
        final java.util.List<Document> materialized = new java.util.ArrayList<>();
        for (final Document d : documents) {
            materialized.add(d);
        }
        try {
            indexer.rebuild(namespaceId, materialized);
        } catch (RuntimeException e) {
            markStatus(namespaceId, IndexStatus.ERROR);
            throw e;
        }
        if (documentMetadata != null) {
            documentMetadata.deleteByNamespace(namespaceId);
            for (final Document d : materialized) {
                recordMetadata(d, effectiveHash(d));
            }
        }
        refreshMetadata(namespaceId, IndexStatus.READY);
        log.info("rebuildFrom completed for namespace {} ({} documents)",
            namespaceId, materialized.size());
        return materialized.size();
    }

    public void rebuild(final String namespaceId) {
        Objects.requireNonNull(namespaceId, "namespaceId must not be null");
        requireNamespaceExists(namespaceId);
        markStatus(namespaceId, IndexStatus.INDEXING);
        // Zero-downtime path: replace the namespace's Lucene segments by
        // promoting an empty build directory. The live searcher continues
        // to serve queries from the previous version until completeBuild()
        // atomically swaps the context (and the old version is scheduled
        // for retirement after the grace period).
        if (indexProvider.backend() == io.searchable.core.infrastructure.lucene.StorageBackend.FILESYSTEM
                && !indexProvider.isReadOnly()) {
            final var handle = indexProvider.beginBuild(namespaceId);
            try {
                // An empty build directory promoted as-is leaves the
                // namespace with zero documents; no further write needed.
                indexProvider.completeBuild(handle);
            } catch (RuntimeException e) {
                indexProvider.cancelBuild(handle);
                markStatus(namespaceId, IndexStatus.ERROR);
                throw e;
            }
        } else {
            indexer.deleteAll(namespaceId);
        }
        if (documentMetadata != null) {
            documentMetadata.deleteByNamespace(namespaceId);
        }
        refreshMetadata(namespaceId, IndexStatus.READY);
        log.info("rebuilt index for namespace {}", namespaceId);
    }

    public IndexMetadata getMetadata(final String namespaceId) {
        Objects.requireNonNull(namespaceId, "namespaceId must not be null");
        return indexMetadata.findByNamespaceId(namespaceId)
            .orElseThrow(() -> new NoSuchElementException(
                "No index metadata for namespace: " + namespaceId));
    }

    private void requireNamespaceExists(final String id) {
        if (!namespaces.exists(id)) {
            throw new NoSuchElementException("Namespace not found: " + id);
        }
    }

    private void markStatus(final String namespaceId, final IndexStatus status) {
        final IndexMetadata current = indexMetadata.findByNamespaceId(namespaceId)
            .orElse(IndexMetadata.empty(namespaceId, clock.instant()));
        indexMetadata.save(new IndexMetadata(
            namespaceId,
            current.documentCount(),
            current.indexSizeBytes(),
            clock.instant(),
            status,
            current.statistics()
        ));
    }

    private void refreshMetadata(final String namespaceId, final IndexStatus status) {
        try {
            final LuceneIndexContext ctx = indexProvider.getOrCreate(namespaceId);
            ctx.refresh();
            indexMetadata.save(new IndexMetadata(
                namespaceId,
                ctx.documentCount(),
                ctx.indexSizeBytes(),
                clock.instant(),
                status,
                embeddingStatistics(namespaceId)
            ));
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to refresh metadata for " + namespaceId, e);
        }
    }

    /** Used for {@code now()} in tests. */
    Instant clockInstant() {
        return clock.instant();
    }
}

package io.searchable.core.infrastructure.lucene;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns the lifecycle of {@link LuceneIndexContext} instances, one per
 * namespace, on top of the timestamp-versioned layout described by
 * {@link IndexLayout}.
 *
 * <p>The provider exposes two flavours of context:
 * <ul>
 *   <li>The <em>live</em> context (returned by {@link #getOrCreate(String)})
 *       opens the namespace's latest readable version for both writes and
 *       reads. Incremental updates ({@link IndexWriter#addDocument} via the
 *       indexer, deletes, etc.) flow through this writer.</li>
 *   <li>A <em>build</em> context (returned by {@link #beginBuild(String)})
 *       opens a fresh {@code <ts>.tmp/} directory with its own
 *       {@link IndexWriter}. The build writer has no searcher attached;
 *       it is meant to be populated by a rebuild and then promoted via
 *       {@link #completeBuild(BuildHandle)}, which atomically renames the
 *       tmp directory, swaps the live context to point at the new
 *       version, and schedules the previous live context for retirement
 *       after a grace period so in-flight searchers can drain.</li>
 * </ul>
 *
 * <p>In <strong>read-only mode</strong> the provider opens a
 * {@link DirectoryReader} on the latest readable version instead of an
 * {@link IndexWriter}. Builds are rejected.
 */
public final class LuceneIndexProvider implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LuceneIndexProvider.class);
    private static final Duration DEFAULT_RETIREMENT_GRACE = Duration.ofSeconds(30);
    private static final Duration DEFAULT_STALE_TMP_THRESHOLD = Duration.ofMinutes(5);

    private final IndexLayout layout;
    private final AnalyzerFactory analyzerFactory;
    private final boolean readOnly;
    private final StorageBackend backend;
    private final Clock clock;
    private final Duration retirementGrace;
    private final Duration staleTmpThreshold;
    private final RetentionPolicy retentionPolicy;
    private final Map<String, LuceneIndexContext> contexts = new ConcurrentHashMap<>();
    private final Deque<ScheduledFuture<?>> retirements = new ArrayDeque<>();
    private final ScheduledExecutorService retirementExecutor;

    public LuceneIndexProvider(final IndexLayout layout, final AnalyzerFactory analyzerFactory) {
        this(layout, analyzerFactory, false, StorageBackend.FILESYSTEM);
    }

    public LuceneIndexProvider(final IndexLayout layout,
                               final AnalyzerFactory analyzerFactory,
                               final boolean readOnly) {
        this(layout, analyzerFactory, readOnly, StorageBackend.FILESYSTEM);
    }

    /**
     * Convenience form that keeps every default except the retention policy.
     *
     * @param layout          index directory layout
     * @param analyzerFactory analyzer factory
     * @param retentionPolicy how many old generations to keep
     */
    public LuceneIndexProvider(final IndexLayout layout,
                               final AnalyzerFactory analyzerFactory,
                               final RetentionPolicy retentionPolicy) {
        this(layout, analyzerFactory, false, StorageBackend.FILESYSTEM,
            Clock.systemUTC(), DEFAULT_RETIREMENT_GRACE, DEFAULT_STALE_TMP_THRESHOLD,
            retentionPolicy);
    }

    public LuceneIndexProvider(final IndexLayout layout,
                               final AnalyzerFactory analyzerFactory,
                               final boolean readOnly,
                               final StorageBackend backend) {
        this(layout, analyzerFactory, readOnly, backend,
            Clock.systemUTC(), DEFAULT_RETIREMENT_GRACE, DEFAULT_STALE_TMP_THRESHOLD);
    }

    public LuceneIndexProvider(final IndexLayout layout,
                               final AnalyzerFactory analyzerFactory,
                               final boolean readOnly,
                               final StorageBackend backend,
                               final Clock clock,
                               final Duration retirementGrace,
                               final Duration staleTmpThreshold) {
        this(layout, analyzerFactory, readOnly, backend, clock, retirementGrace, staleTmpThreshold,
            RetentionPolicy.keepActiveOnly());
    }

    /**
     * Full form, taking the retention policy that decides how many old index
     * generations survive a rebuild.
     *
     * @param layout            index directory layout
     * @param analyzerFactory   analyzer factory
     * @param readOnly          whether this provider may only read
     * @param backend           storage backend
     * @param clock             clock used for version timestamps and retention
     * @param retirementGrace   delay before an obsolete version is deleted
     * @param staleTmpThreshold age at which an abandoned build directory is cleaned
     * @param retentionPolicy   how many old generations to keep
     */
    public LuceneIndexProvider(final IndexLayout layout,
                               final AnalyzerFactory analyzerFactory,
                               final boolean readOnly,
                               final StorageBackend backend,
                               final Clock clock,
                               final Duration retirementGrace,
                               final Duration staleTmpThreshold,
                               final RetentionPolicy retentionPolicy) {
        this.retentionPolicy = Objects.requireNonNull(retentionPolicy,
            "retentionPolicy must not be null");
        this.layout = Objects.requireNonNull(layout, "layout must not be null");
        this.analyzerFactory = Objects.requireNonNull(analyzerFactory, "analyzerFactory must not be null");
        this.backend = Objects.requireNonNull(backend, "backend must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.retirementGrace = Objects.requireNonNull(retirementGrace, "retirementGrace must not be null");
        this.staleTmpThreshold = Objects.requireNonNull(staleTmpThreshold, "staleTmpThreshold must not be null");
        if (backend == StorageBackend.MEMORY && readOnly) {
            throw new IllegalArgumentException(
                "MEMORY storage backend cannot be combined with read-only mode");
        }
        this.readOnly = readOnly;
        this.retirementExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "lucene-retirement");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public StorageBackend backend() {
        return backend;
    }

    /** Open (and cache) the live index context for a namespace. */
    public LuceneIndexContext getOrCreate(final String namespaceId) {
        return contexts.computeIfAbsent(namespaceId, this::openLiveContext);
    }

    /** Return whether a live context is currently open for the namespace. */
    public boolean isOpen(final String namespaceId) {
        return contexts.containsKey(namespaceId);
    }

    /**
     * Refresh every currently-open namespace's searcher view so it
     * reflects segments the CLI (or API) has just committed, and reopens
     * the context if a rebuild has promoted a new {@code <ts>/}.
     *
     * <p>Typically invoked from a {@code SIGHUP} handler in read-only
     * apps so they pick up new documents (or a freshly rebuilt index)
     * without restarting. In read-write mode the writer's SearcherManager
     * is already NRT, so the call is effectively a no-op but is still
     * safe.
     *
     * @return number of namespaces whose refresh succeeded
     */
    public int refresh() {
        int n = 0;
        for (final String namespaceId : contexts.keySet()) {
            if (refresh(namespaceId)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Refresh the searcher view for a single open namespace.
     *
     * <p>If the namespace's {@link IndexLayout#latestReadable latest
     * readable} version has advanced past the version the open context
     * is pointing at (i.e. a rebuild has been promoted by another
     * process), close the current context and reopen it on the new
     * version. Otherwise just call {@code SearcherManager.maybeRefresh()}
     * to pick up incremental segment commits.
     *
     * @return {@code true} when the namespace has an open context and
     *         the refresh succeeded; {@code false} otherwise
     */
    public boolean refresh(final String namespaceId) {
        final LuceneIndexContext ctx = contexts.get(namespaceId);
        if (ctx == null) {
            return false;
        }
        if (maybeReopenAfterPromotion(namespaceId, ctx)) {
            return true;
        }
        try {
            ctx.refresh();
            return true;
        } catch (IOException e) {
            log.warn("Failed to refresh searcher for namespace {}", namespaceId, e);
            return false;
        }
    }

    private static boolean isSamePath(final Path a, final Path b) {
        if (a.equals(b)) {
            return true;
        }
        try {
            return Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean maybeReopenAfterPromotion(final String namespaceId,
                                              final LuceneIndexContext ctx) {
        if (backend != StorageBackend.FILESYSTEM) {
            return false;
        }
        final Optional<Path> openDir = ctx.versionDir();
        if (openDir.isEmpty()) {
            return false;
        }
        final Optional<Path> latest;
        try {
            latest = layout.latestReadable(namespaceId);
        } catch (RuntimeException e) {
            log.warn("Failed to resolve latest version for namespace {}", namespaceId, e);
            return false;
        }
        if (latest.isEmpty() || isSamePath(openDir.get(), latest.get())) {
            return false;
        }
        log.info("rebuild promotion detected for namespace {}: {} -> {}",
            namespaceId, openDir.get(), latest.get());
        final LuceneIndexContext fresh;
        try {
            fresh = openLiveContext(namespaceId, latest.get());
        } catch (RuntimeException e) {
            log.warn("Failed to reopen namespace {} on {}", namespaceId, latest.get(), e);
            return false;
        }
        final LuceneIndexContext previous = contexts.put(namespaceId, fresh);
        if (previous != null) {
            scheduleRetirement(namespaceId, previous);
        }
        return true;
    }

    /**
     * Reopen the live context so that a refreshed {@link AnalyzerFactory}
     * (e.g. after a user-dictionary update) takes effect immediately.
     * Existing on-disk segments are kept; only the in-memory writer /
     * reader state is rebuilt.
     */
    public void refreshAnalyzer(final String namespaceId) throws IOException {
        final LuceneIndexContext ctx = contexts.remove(namespaceId);
        if (ctx != null) {
            ctx.close();
        }
        getOrCreate(namespaceId);
    }

    /** Close and remove the live context, optionally wiping the index files. */
    public void remove(final String namespaceId, final boolean deleteFiles) throws IOException {
        final LuceneIndexContext ctx = contexts.remove(namespaceId);
        if (ctx != null) {
            ctx.close();
        }
        if (deleteFiles) {
            if (readOnly) {
                throw new IllegalStateException(
                    "Cannot delete index files in read-only mode (namespace=" + namespaceId + ")");
            }
            if (backend == StorageBackend.FILESYSTEM) {
                final Path nsDir = layout.namespaceDir(namespaceId);
                if (Files.exists(nsDir)) {
                    layout.deleteRecursively(nsDir);
                }
            }
            // MEMORY backend: closing the context already discards the buffers.
        }
    }

    /**
     * Open a build context for a full rebuild. The returned handle owns a
     * fresh {@link IndexWriter} on a {@code <ts>.tmp/} directory; the
     * caller writes documents through that writer and then either
     * {@link #completeBuild} (success) or {@link #cancelBuild} (failure).
     *
     * <p>The live searcher for the namespace continues to serve queries
     * from the previous version throughout the build.
     */
    public BuildHandle beginBuild(final String namespaceId) {
        if (readOnly) {
            throw new IllegalStateException(
                "Cannot begin a build in read-only mode (namespace=" + namespaceId + ")");
        }
        if (backend == StorageBackend.MEMORY) {
            throw new IllegalStateException(
                "beginBuild is only supported on the FILESYSTEM backend");
        }
        final Path buildDir = layout.newBuild(namespaceId, clock);
        final Analyzer analyzer = analyzerFactory.create(namespaceId);
        final IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        config.setRAMBufferSizeMB(64);
        try {
            final Directory directory = new MMapDirectory(buildDir);
            final IndexWriter writer = new IndexWriter(directory, config);
            writer.commit();
            log.info("started build for namespace {} at {}", namespaceId, buildDir);
            return new BuildHandle(namespaceId, buildDir, directory, analyzer, writer);
        } catch (IOException e) {
            // Try to clean up the half-created build dir; ignore failures
            // because we are already in an error path.
            try {
                layout.deleteRecursively(buildDir);
            } catch (RuntimeException cleanupFail) {
                log.debug("Failed to remove half-built dir {}", buildDir, cleanupFail);
            }
            throw new IllegalStateException(
                "Failed to start build for namespace " + namespaceId, e);
        }
    }

    /**
     * Promote a {@link BuildHandle} into the live context. The build
     * writer is committed and closed, the {@code <ts>.tmp/} directory is
     * atomically renamed to {@code <ts>/}, a new live context is opened
     * against the freshly promoted version, and the previous live
     * context is scheduled for retirement after the grace period so any
     * outstanding {@link org.apache.lucene.search.IndexSearcher} can
     * complete.
     */
    public void completeBuild(final BuildHandle handle) {
        Objects.requireNonNull(handle, "handle must not be null");
        try {
            handle.writer().commit();
            handle.writer().close();
            handle.directory().close();
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to finalize build writer for " + handle.namespaceId(), e);
        }
        final Path promoted = layout.promote(handle.buildDir());

        final LuceneIndexContext fresh = openLiveContext(handle.namespaceId(), promoted);
        final LuceneIndexContext old = contexts.put(handle.namespaceId(), fresh);

        if (old != null) {
            scheduleRetirement(handle.namespaceId(), old);
            scheduleObsoleteVersionCleanup(handle.namespaceId(), promoted);
        }
        log.info("promoted build for namespace {} -> {}", handle.namespaceId(), promoted);
    }

    /** Abort a build: close the writer and remove the {@code .tmp} directory. */
    public void cancelBuild(final BuildHandle handle) {
        Objects.requireNonNull(handle, "handle must not be null");
        try {
            handle.writer().rollback();
        } catch (IOException ignored) {
            // best-effort
        }
        try {
            handle.directory().close();
        } catch (IOException ignored) {
            // best-effort
        }
        layout.deleteRecursively(handle.buildDir());
        log.info("cancelled build for namespace {} at {}", handle.namespaceId(), handle.buildDir());
    }

    @Override
    public void close() {
        contexts.forEach((id, ctx) -> {
            try {
                ctx.close();
            } catch (IOException e) {
                log.warn("Failed to close index for namespace {}", id, e);
            }
        });
        contexts.clear();
        synchronized (retirements) {
            for (final ScheduledFuture<?> f : retirements) {
                f.cancel(false);
            }
            retirements.clear();
        }
        retirementExecutor.shutdown();
    }

    private LuceneIndexContext openLiveContext(final String namespaceId) {
        if (backend == StorageBackend.MEMORY) {
            return openMemoryReadWrite(namespaceId);
        }
        // Clean up orphaned <ts>.tmp directories from prior crashed builds
        // before resolving the readable version.
        cleanupStaleTmpDirs(namespaceId);
        final Path versionDir;
        try {
            versionDir = layout.latestReadable(namespaceId)
                .orElseGet(() -> bootstrapInitialVersion(namespaceId));
        } catch (NoSuchElementException e) {
            // read-only mode: no readable index. Propagate as-is.
            throw e;
        } catch (java.io.UncheckedIOException e) {
            if (readOnly) {
                throw new NoSuchElementException(
                    "No index found for namespace " + namespaceId
                        + " (root=" + layout.rootDirectory() + ") in read-only mode");
            }
            throw new IllegalStateException(
                "Failed to open Lucene index for namespace " + namespaceId, e.getCause());
        }
        return openLiveContext(namespaceId, versionDir);
    }

    private LuceneIndexContext openLiveContext(final String namespaceId, final Path versionDir) {
        if (readOnly) {
            return openReadOnly(namespaceId, versionDir);
        }
        return openFilesystemReadWrite(namespaceId, versionDir);
    }

    private Path bootstrapInitialVersion(final String namespaceId) {
        if (readOnly) {
            throw new NoSuchElementException(
                "No index found for namespace " + namespaceId
                    + " (root=" + layout.rootDirectory() + ") in read-only mode");
        }
        // No readable version yet: create an empty one. Atomic
        // promotion is used so any concurrent reader still sees nothing
        // until the directory is fully initialized.
        final Path build = layout.newBuild(namespaceId, clock);
        try {
            try (Directory dir = new MMapDirectory(build);
                 IndexWriter writer = new IndexWriter(dir,
                     new IndexWriterConfig(analyzerFactory.create(namespaceId)))) {
                writer.commit();
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to bootstrap index for namespace " + namespaceId, e);
        }
        return layout.promote(build);
    }

    private LuceneIndexContext openFilesystemReadWrite(final String namespaceId, final Path path) {
        final Directory directory;
        try {
            Files.createDirectories(path);
            directory = new MMapDirectory(path);
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to open Lucene index for namespace " + namespaceId, e);
        }
        return openWriter(namespaceId, directory, "at " + path);
    }

    private LuceneIndexContext openMemoryReadWrite(final String namespaceId) {
        return openWriter(namespaceId, new ByteBuffersDirectory(), "in memory");
    }

    private LuceneIndexContext openWriter(final String namespaceId,
                                          final Directory directory,
                                          final String locationLabel) {
        final Analyzer analyzer = analyzerFactory.create(namespaceId);
        final IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        config.setRAMBufferSizeMB(64);
        try {
            final IndexWriter writer = new IndexWriter(directory, config);
            writer.commit();
            final SearcherManager manager = new SearcherManager(writer, true, true, null);
            log.info("opened Lucene index for namespace {} {}", namespaceId, locationLabel);
            return new LuceneIndexContext(namespaceId, directory, analyzer, writer, manager);
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to open Lucene index for namespace " + namespaceId, e);
        }
    }

    private LuceneIndexContext openReadOnly(final String namespaceId, final Path path) {
        if (!Files.isDirectory(path) || isEmptyDir(path)) {
            throw new NoSuchElementException(
                "No index found for namespace " + namespaceId
                    + " (path=" + path + ") in read-only mode");
        }
        try {
            final Directory directory = new MMapDirectory(path);
            final Analyzer analyzer = analyzerFactory.create(namespaceId);
            final DirectoryReader reader = DirectoryReader.open(directory);
            final SearcherManager manager = new SearcherManager(reader, null);
            log.info("opened read-only Lucene index for namespace {} at {}", namespaceId, path);
            return new LuceneIndexContext(namespaceId, directory, analyzer, manager);
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to open Lucene index for namespace " + namespaceId, e);
        }
    }

    private static boolean isEmptyDir(final Path path) {
        try (var stream = Files.list(path)) {
            return stream.findAny().isEmpty();
        } catch (IOException e) {
            return true;
        }
    }

    private void cleanupStaleTmpDirs(final String namespaceId) {
        if (backend != StorageBackend.FILESYSTEM) {
            return;
        }
        try {
            for (final Path stale : layout.staleTmpDirs(namespaceId,
                    staleTmpThreshold.toMillis(), clock)) {
                log.info("cleaning up orphaned build dir {}", stale);
                layout.deleteRecursively(stale);
            }
        } catch (RuntimeException e) {
            log.warn("Failed to scan stale tmp dirs for namespace {}", namespaceId, e);
        }
    }

    private void scheduleRetirement(final String namespaceId, final LuceneIndexContext old) {
        final ScheduledFuture<?> future = retirementExecutor.schedule(() -> {
            try {
                log.info("retiring previous live context for namespace {}", namespaceId);
                old.close();
            } catch (IOException e) {
                log.warn("Failed to retire context for namespace {}", namespaceId, e);
            }
        }, retirementGrace.toMillis(), TimeUnit.MILLISECONDS);
        synchronized (retirements) {
            retirements.add(future);
            // Trim done futures so the queue does not grow unbounded.
            retirements.removeIf(ScheduledFuture::isDone);
        }
    }

    private void scheduleObsoleteVersionCleanup(final String namespaceId, final Path keepVersion) {
        if (backend != StorageBackend.FILESYSTEM) {
            return;
        }
        final ScheduledFuture<?> future = retirementExecutor.schedule(() -> {
            try {
                for (final Path obsolete
                        : layout.versionsToReclaim(namespaceId, retentionPolicy, keepVersion, clock)) {
                    log.info("deleting obsolete index version {}", obsolete);
                    layout.deleteRecursively(obsolete);
                }
            } catch (RuntimeException e) {
                log.warn("Failed to clean up obsolete versions for namespace {}", namespaceId, e);
            }
        }, retirementGrace.toMillis(), TimeUnit.MILLISECONDS);
        synchronized (retirements) {
            retirements.add(future);
            retirements.removeIf(ScheduledFuture::isDone);
        }
    }

    /** Handle for an in-flight build. Caller passes this to {@link #completeBuild}. */
    public static final class BuildHandle implements AutoCloseable {
        private final String namespaceId;
        private final Path buildDir;
        private final Directory directory;
        private final Analyzer analyzer;
        private final IndexWriter writer;

        BuildHandle(final String namespaceId,
                    final Path buildDir,
                    final Directory directory,
                    final Analyzer analyzer,
                    final IndexWriter writer) {
            this.namespaceId = namespaceId;
            this.buildDir = buildDir;
            this.directory = directory;
            this.analyzer = analyzer;
            this.writer = writer;
        }

        public String namespaceId() { return namespaceId; }
        public Path buildDir() { return buildDir; }
        public IndexWriter writer() { return writer; }
        public Analyzer analyzer() { return analyzer; }
        Directory directory() { return directory; }

        /** Closing the handle without promoting is equivalent to cancelling. */
        @Override
        public void close() {
            // The Provider's cancelBuild handles cleanup; this is a no-op
            // for try-with-resources usage where the caller forgets to
            // call cancel/complete explicitly. Writer.close() is idempotent.
            try {
                if (writer.isOpen()) {
                    writer.close();
                }
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }
}

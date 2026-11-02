package io.searchable.core.infrastructure.lucene;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Resolves the on-disk location of each namespace's Lucene index, with
 * support for timestamp-versioned directories that enable zero-downtime
 * rebuild.
 *
 * <p>Layout under {@link #rootDirectory()}:
 * <pre>
 *   &lt;root&gt;/
 *     &lt;namespaceId&gt;/
 *       &lt;timestampMs&gt;/          (completed, readable)
 *       &lt;newerTimestampMs&gt;.tmp/  (in-flight build, not yet readable)
 * </pre>
 *
 * <p>The timestamp is a non-negative decimal value (ms epoch by default,
 * but the only contract is monotonicity within a namespace). New builds
 * obtain their timestamp via {@link #newBuild} which applies a
 * monotonic clamp so a wall-clock jump backwards cannot collide with an
 * existing version. Promotion from {@code .tmp} to its completed name is
 * an atomic move so readers never observe a half-built directory.
 */
public final class IndexLayout {

    /** Suffix that marks a build directory as in-flight. */
    public static final String TMP_SUFFIX = ".tmp";

    private static final Pattern NAMESPACE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern VERSION_DIR = Pattern.compile("\\d+");
    private static final String LUCENE_WRITE_LOCK = "write.lock";

    private final Path rootDirectory;

    public IndexLayout(final Path rootDirectory) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory must not be null");
    }

    public Path rootDirectory() {
        return rootDirectory;
    }

    /**
     * @deprecated Use {@link #latestReadable(String)} or {@link #newBuild(String, Clock)}
     *             depending on whether the caller is reading or writing. Kept for the
     *             single-version callers that still operate on the legacy layout.
     */
    @Deprecated
    public Path directoryFor(final String namespaceId) {
        return namespaceDir(namespaceId);
    }

    /** Returns {@code <root>/<namespaceId>/}. */
    public Path namespaceDir(final String namespaceId) {
        validateNamespaceId(namespaceId);
        return rootDirectory.resolve(namespaceId);
    }

    /**
     * Throws {@link IllegalArgumentException} when {@code namespaceId} does
     * not match the {@code [a-z0-9][a-z0-9_-]{0,63}} pattern that bounds
     * every on-disk directory under {@link #rootDirectory()}. Callers that
     * build paths from untrusted input (CLI/admin/restore) should invoke
     * this at the boundary instead of relying on downstream resolution to
     * catch traversal attempts.
     */
    public static void validateNamespaceId(final String namespaceId) {
        Objects.requireNonNull(namespaceId, "namespaceId must not be null");
        if (!NAMESPACE_ID.matcher(namespaceId).matches()) {
            throw new IllegalArgumentException("Invalid namespaceId: " + namespaceId);
        }
    }

    /**
     * Latest completed version directory for the namespace, if any.
     * Returns {@link Optional#empty()} when the namespace has no completed
     * builds yet (initial state, or a rebuild that has not yet promoted).
     */
    public Optional<Path> latestReadable(final String namespaceId) {
        final List<Path> versions = readableVersions(namespaceId);
        return versions.isEmpty() ? Optional.empty()
            : Optional.of(versions.get(versions.size() - 1));
    }

    /**
     * All completed version directories under the namespace, sorted by
     * timestamp ascending. Excludes {@code .tmp} (in-flight) directories.
     */
    public List<Path> readableVersions(final String namespaceId) {
        final Path nsDir = namespaceDir(namespaceId);
        if (!Files.isDirectory(nsDir)) {
            return List.of();
        }
        final List<Path> versions = new ArrayList<>();
        try (Stream<Path> children = Files.list(nsDir)) {
            children
                .filter(Files::isDirectory)
                .filter(p -> VERSION_DIR.matcher(p.getFileName().toString()).matches())
                .sorted((a, b) -> Long.compare(versionOf(a), versionOf(b)))
                .forEach(versions::add);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list version directories under " + nsDir, e);
        }
        return Collections.unmodifiableList(versions);
    }

    /**
     * Allocate a new in-flight build directory: {@code <root>/<ns>/<ts>.tmp/}.
     * The timestamp is the clock value, monotonically clamped so it is
     * strictly greater than any existing version or in-flight build under
     * the same namespace.
     */
    public Path newBuild(final String namespaceId, final Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        final Path nsDir = namespaceDir(namespaceId);
        try {
            Files.createDirectories(nsDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create namespace dir " + nsDir, e);
        }
        final long wall = clock.instant().toEpochMilli();
        final long floor = highestExistingTimestamp(nsDir) + 1;
        final long ts = Math.max(wall, floor);
        final Path build = nsDir.resolve(ts + TMP_SUFFIX);
        try {
            Files.createDirectories(build);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create build dir " + build, e);
        }
        return build;
    }

    /**
     * Atomically rename a {@code <ts>.tmp/} build directory to its
     * completed name {@code <ts>/}. Returns the promoted path.
     *
     * @throws IllegalArgumentException if {@code buildDir} is not a
     *                                  {@code .tmp}-suffixed directory
     */
    public Path promote(final Path buildDir) {
        Objects.requireNonNull(buildDir, "buildDir must not be null");
        final String name = buildDir.getFileName().toString();
        if (!name.endsWith(TMP_SUFFIX)) {
            throw new IllegalArgumentException(
                "buildDir must end with " + TMP_SUFFIX + ": " + buildDir);
        }
        final String completedName = name.substring(0, name.length() - TMP_SUFFIX.length());
        if (!VERSION_DIR.matcher(completedName).matches()) {
            throw new IllegalArgumentException(
                "buildDir name does not parse as <timestamp>.tmp: " + buildDir);
        }
        final Path target = buildDir.resolveSibling(completedName);
        try {
            Files.move(buildDir, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to promote build dir " + buildDir, e);
        }
        return target;
    }

    /**
     * Completed versions older than {@code keepVersion} (exclusive),
     * sorted by timestamp ascending. The newest version is excluded so
     * callers do not accidentally GC the live searcher's directory.
     */
    @Deprecated(since = "1.0.1", forRemoval = false)
    public List<Path> obsoleteVersions(final String namespaceId, final Path keepVersion) {
        return versionsToReclaim(namespaceId, RetentionPolicy.keepActiveOnly(), keepVersion,
            Clock.systemUTC());
    }

    /**
     * Versions that may be deleted under the given retention policy, sorted by
     * timestamp ascending.
     *
     * <p>A version survives when it is the active one, when it is among the
     * newest {@code generations}, or when it was created within
     * {@code maxAge}. The three rules form a union, so widening either setting
     * only ever keeps more. Versions newer than the active one are never
     * reclaimed either, so a build started by another process is left alone.
     *
     * <p>A version's creation time is read from its directory name, which is
     * the millisecond timestamp assigned when the build started. No file
     * system metadata is consulted.
     *
     * @param namespaceId the namespace to inspect
     * @param policy      the retention policy
     * @param keepVersion the active version, which is always kept
     * @param clock       clock used to evaluate the age rule
     * @return the versions that can be deleted
     */
    public List<Path> versionsToReclaim(final String namespaceId,
                                        final RetentionPolicy policy,
                                        final Path keepVersion,
                                        final Clock clock) {
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(keepVersion, "keepVersion must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        final long keepTs = versionOf(keepVersion);
        final List<Path> versions = readableVersions(namespaceId);
        final int total = versions.size();
        final int newestFrom = total - policy.generations();
        final long ageCutoff = policy.hasAgeRule()
            ? clock.millis() - policy.maxAge().toMillis()
            : Long.MIN_VALUE;

        final List<Path> reclaimable = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            final Path version = versions.get(i);
            final long timestamp = versionOf(version);
            if (timestamp >= keepTs || i >= newestFrom) {
                continue;
            }
            if (policy.hasAgeRule() && timestamp >= ageCutoff) {
                continue;
            }
            reclaimable.add(version);
        }
        return Collections.unmodifiableList(reclaimable);
    }

    /**
     * Detect orphaned {@code .tmp} directories under a namespace.
     * A directory is considered orphaned when:
     * <ol>
     *   <li>its mtime is older than {@code staleAfterMillis} ago, AND</li>
     *   <li>its Lucene {@code write.lock} can be acquired (no other writer
     *       process is currently holding it).</li>
     * </ol>
     * Both conditions are required so an actively-running indexer that
     * happens to be slow does not have its working directory pulled out
     * from under it.
     */
    public List<Path> staleTmpDirs(final String namespaceId,
                                   final long staleAfterMillis,
                                   final Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        if (staleAfterMillis < 0) {
            throw new IllegalArgumentException("staleAfterMillis must not be negative");
        }
        final Path nsDir = namespaceDir(namespaceId);
        if (!Files.isDirectory(nsDir)) {
            return List.of();
        }
        final long cutoff = clock.instant().toEpochMilli() - staleAfterMillis;
        final List<Path> stale = new ArrayList<>();
        try (Stream<Path> children = Files.list(nsDir)) {
            for (final Path p : (Iterable<Path>) children::iterator) {
                if (!Files.isDirectory(p)) {
                    continue;
                }
                final String name = p.getFileName().toString();
                if (!name.endsWith(TMP_SUFFIX)) {
                    continue;
                }
                if (lastModifiedMillis(p) > cutoff) {
                    continue;
                }
                if (!canAcquireWriteLock(p)) {
                    continue;
                }
                stale.add(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan stale tmp dirs under " + nsDir, e);
        }
        return Collections.unmodifiableList(stale);
    }

    /**
     * Delete a version directory (or stale {@code .tmp}) recursively.
     * Safe to call on a missing path (no-op).
     */
    public void deleteRecursively(final Path dir) {
        Objects.requireNonNull(dir, "dir must not be null");
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to delete " + p, e);
                    }
                });
        } catch (NoSuchFileException ignored) {
            // raced with another delete; that's fine
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk " + dir, e);
        }
    }

    /** Extract the numeric timestamp from a version directory path. */
    public static long versionOf(final Path versionDir) {
        Objects.requireNonNull(versionDir, "versionDir must not be null");
        final String name = versionDir.getFileName().toString();
        final String numeric = name.endsWith(TMP_SUFFIX)
            ? name.substring(0, name.length() - TMP_SUFFIX.length())
            : name;
        try {
            return Long.parseLong(numeric);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Not a version directory: " + versionDir, e);
        }
    }

    private long highestExistingTimestamp(final Path nsDir) {
        long max = 0L;
        try (Stream<Path> children = Files.list(nsDir)) {
            for (final Path p : (Iterable<Path>) children::iterator) {
                if (!Files.isDirectory(p)) {
                    continue;
                }
                final String name = p.getFileName().toString();
                final String numeric = name.endsWith(TMP_SUFFIX)
                    ? name.substring(0, name.length() - TMP_SUFFIX.length())
                    : name;
                if (!VERSION_DIR.matcher(numeric).matches()) {
                    continue;
                }
                final long ts = Long.parseLong(numeric);
                if (ts > max) {
                    max = ts;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list " + nsDir, e);
        }
        return max;
    }

    private static long lastModifiedMillis(final Path dir) {
        try {
            final FileTime t = Files.getLastModifiedTime(dir);
            return t.toMillis();
        } catch (IOException e) {
            // Treat unreadable mtime as "very old" so the caller falls
            // back to the lock-acquisition check.
            return 0L;
        }
    }

    /**
     * Try to acquire Lucene's {@code write.lock} on the build directory.
     * Returns {@code true} when the lock is free (the .tmp is orphaned)
     * and {@code false} when an active writer holds it. The lock is
     * released immediately on success so the caller can subsequently
     * delete the directory.
     */
    private static boolean canAcquireWriteLock(final Path buildDir) {
        final Path lockFile = buildDir.resolve(LUCENE_WRITE_LOCK);
        if (!Files.exists(lockFile)) {
            // No writer ever ran (or it cleaned up). Considered orphaned.
            return true;
        }
        try (FileChannel ch = FileChannel.open(lockFile,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock lock = ch.tryLock()) {
            // tryLock returns null when another process holds it; treat
            // that as "still active".
            return lock != null;
        } catch (OverlappingFileLockException e) {
            // Another thread in this JVM already holds it.
            return false;
        } catch (IOException e) {
            // Unable to open the lock file at all -> assume not orphaned.
            return false;
        }
    }
}

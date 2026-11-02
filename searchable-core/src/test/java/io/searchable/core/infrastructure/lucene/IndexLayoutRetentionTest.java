package io.searchable.core.infrastructure.lucene;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which index generations survive each retention policy (TASK-024).
 *
 * <p>Versions are named by their creation timestamp in milliseconds, so the
 * fixture below creates versions at 1000 through 5000 and evaluates them at
 * "now" = 5000.
 */
class IndexLayoutRetentionTest {

    private static final long NOW = 5000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
    private static final String NS = "ns";

    @TempDir Path tempDir;

    private IndexLayout layout;
    private Path active;

    @BeforeEach
    void setUp() throws IOException {
        layout = new IndexLayout(tempDir);
        for (final long timestamp : List.of(1000L, 2000L, 3000L, 4000L, 5000L)) {
            Files.createDirectories(layout.namespaceDir(NS).resolve(Long.toString(timestamp)));
        }
        active = layout.namespaceDir(NS).resolve("5000");
    }

    private List<String> reclaim(final RetentionPolicy policy) {
        return layout.versionsToReclaim(NS, policy, active, CLOCK).stream()
            .map(path -> path.getFileName().toString())
            .toList();
    }

    @Test
    void keepActiveOnlyReclaimsEveryOlderVersion() {
        assertThat(reclaim(RetentionPolicy.keepActiveOnly()))
            .containsExactly("1000", "2000", "3000", "4000");
    }

    @Test
    void generationsKeepTheNewestVersions() {
        assertThat(reclaim(RetentionPolicy.of(3, Duration.ZERO)))
            .containsExactly("1000", "2000");
    }

    @Test
    void generationsBeyondTheVersionCountReclaimNothing() {
        assertThat(reclaim(RetentionPolicy.of(10, Duration.ZERO))).isEmpty();
    }

    @Test
    void maxAgeKeepsVersionsCreatedWithinTheWindow() {
        // cutoff = 5000 - 2500 = 2500, so 3000 and 4000 survive on age alone.
        assertThat(reclaim(RetentionPolicy.of(1, Duration.ofMillis(2500))))
            .containsExactly("1000", "2000");
    }

    @Test
    void aVersionExactlyAtTheAgeLimitIsKept() {
        // cutoff = 5000 - 3000 = 2000, and 2000 is kept because the age equals the limit.
        assertThat(reclaim(RetentionPolicy.of(1, Duration.ofMillis(3000))))
            .containsExactly("1000");
    }

    @Test
    void rulesFormAUnionRatherThanAnIntersection() {
        // newest 2 = {4000, 5000}; within 3500ms = {2000, 3000, 4000, 5000}.
        assertThat(reclaim(RetentionPolicy.of(2, Duration.ofMillis(3500))))
            .containsExactly("1000");
    }

    @Test
    void theActiveVersionIsNeverReclaimed() {
        final Path oldActive = layout.namespaceDir(NS).resolve("2000");

        final List<String> reclaimed = layout.versionsToReclaim(
                NS, RetentionPolicy.keepActiveOnly(), oldActive, CLOCK).stream()
            .map(path -> path.getFileName().toString())
            .toList();

        assertThat(reclaimed).containsExactly("1000");
        assertThat(reclaimed).doesNotContain("2000");
    }

    @Test
    void versionsNewerThanTheActiveOneAreLeftAlone() {
        final Path oldActive = layout.namespaceDir(NS).resolve("3000");

        final List<String> reclaimed = layout.versionsToReclaim(
                NS, RetentionPolicy.keepActiveOnly(), oldActive, CLOCK).stream()
            .map(path -> path.getFileName().toString())
            .toList();

        assertThat(reclaimed).containsExactly("1000", "2000");
    }

    @Test
    void deprecatedApiStillBehavesAsBefore() {
        assertThat(layout.obsoleteVersions(NS, active))
            .extracting(path -> path.getFileName().toString())
            .containsExactly("1000", "2000", "3000", "4000");
    }

    @Test
    void rejectsMissingArguments() {
        assertThatThrownBy(() -> layout.versionsToReclaim(NS, null, active, CLOCK))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
            layout.versionsToReclaim(NS, RetentionPolicy.keepActiveOnly(), active, null))
            .isInstanceOf(NullPointerException.class);
    }
}

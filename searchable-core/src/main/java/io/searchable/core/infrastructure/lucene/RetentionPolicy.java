package io.searchable.core.infrastructure.lucene;

import java.time.Duration;
import java.util.Objects;

/**
 * How many old index generations a namespace keeps after a rebuild.
 *
 * <p>A generation is kept when it satisfies any of the following, so the two
 * settings form a union rather than an intersection:
 *
 * <ol>
 *   <li>it is among the newest {@code generations} versions,</li>
 *   <li>it was created within {@code maxAge}, or</li>
 *   <li>it is the active version.</li>
 * </ol>
 *
 * <p>The active version is kept whatever the policy says, so a rebuild can
 * never reclaim the directory the live searcher is reading.
 *
 * @param generations how many newest versions to keep, at least 1
 * @param maxAge      how long a version is kept regardless of its position;
 *                    {@link Duration#ZERO} disables the age rule
 */
public record RetentionPolicy(int generations, Duration maxAge) {

    public RetentionPolicy {
        Objects.requireNonNull(maxAge, "maxAge must not be null");
        if (generations < 1) {
            throw new IllegalArgumentException("generations must be at least 1, was " + generations);
        }
        if (maxAge.isNegative()) {
            throw new IllegalArgumentException("maxAge must not be negative, was " + maxAge);
        }
    }

    /**
     * Build a policy.
     *
     * @param generations how many newest versions to keep, at least 1
     * @param maxAge      how long a version is kept regardless of its position
     * @return the policy
     */
    public static RetentionPolicy of(final int generations, final Duration maxAge) {
        return new RetentionPolicy(generations, maxAge);
    }

    /**
     * The policy that matches releases before retention was configurable:
     * keep only the active version.
     *
     * @return the policy
     */
    public static RetentionPolicy keepActiveOnly() {
        return new RetentionPolicy(1, Duration.ZERO);
    }

    /** Whether the age rule is in effect. */
    public boolean hasAgeRule() {
        return !maxAge.isZero();
    }
}

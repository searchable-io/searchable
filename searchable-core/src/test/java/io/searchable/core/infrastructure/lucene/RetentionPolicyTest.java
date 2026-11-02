package io.searchable.core.infrastructure.lucene;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetentionPolicyTest {

    @Test
    void keepActiveOnlyMatchesTheLegacyBehaviour() {
        final RetentionPolicy policy = RetentionPolicy.keepActiveOnly();

        assertThat(policy.generations()).isEqualTo(1);
        assertThat(policy.maxAge()).isZero();
        assertThat(policy.hasAgeRule()).isFalse();
    }

    @Test
    void holdsBothRules() {
        final RetentionPolicy policy = RetentionPolicy.of(3, Duration.ofHours(6));

        assertThat(policy.generations()).isEqualTo(3);
        assertThat(policy.maxAge()).isEqualTo(Duration.ofHours(6));
        assertThat(policy.hasAgeRule()).isTrue();
    }

    @Test
    void rejectsFewerThanOneGeneration() {
        assertThatThrownBy(() -> RetentionPolicy.of(0, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("generations");
        assertThatThrownBy(() -> RetentionPolicy.of(-1, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeMaxAge() {
        assertThatThrownBy(() -> RetentionPolicy.of(1, Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxAge");
    }

    @Test
    void rejectsNullMaxAge() {
        assertThatThrownBy(() -> RetentionPolicy.of(1, null))
            .isInstanceOf(NullPointerException.class);
    }
}

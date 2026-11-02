package io.searchable.core.domain.namespace;

import io.searchable.core.domain.search.SearchOrder;
import io.searchable.core.domain.search.SearchStrategy;
import io.searchable.core.domain.search.SearchType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NamespaceConfigIndexedKeysTest {

    private static NamespaceConfig withKeys(final Set<String> keys) {
        return new NamespaceConfig(
            SearchType.FULL_TEXT,
            SearchStrategy.SEQUENTIAL,
            SearchOrder.FULL_TEXT_FIRST,
            null,
            AiConfig.disabled(),
            NamespaceConfig.DEFAULT_INDEX_WEIGHT,
            Map.of(),
            keys);
    }

    @Test
    void defaultsToNoDeclaredKey() {
        assertThat(NamespaceConfig.defaults().indexedMetadataKeys()).isEmpty();
        assertThat(NamespaceConfig.defaults().isMetadataFilteringEnabled()).isFalse();
    }

    @Test
    void backwardCompatibleConstructorsDeclareNoKey() {
        final NamespaceConfig sevenArg = new NamespaceConfig(
            SearchType.FULL_TEXT, SearchStrategy.SEQUENTIAL, SearchOrder.FULL_TEXT_FIRST,
            null, AiConfig.disabled(), 2.0, Map.of());
        final NamespaceConfig sixArg = new NamespaceConfig(
            SearchType.FULL_TEXT, SearchStrategy.SEQUENTIAL, SearchOrder.FULL_TEXT_FIRST,
            null, AiConfig.disabled(), Map.of());

        assertThat(sevenArg.indexedMetadataKeys()).isEmpty();
        assertThat(sevenArg.indexWeight()).isEqualTo(2.0);
        assertThat(sixArg.indexedMetadataKeys()).isEmpty();
        assertThat(sixArg.indexWeight()).isEqualTo(NamespaceConfig.DEFAULT_INDEX_WEIGHT);
    }

    @Test
    void declaredKeysEnableFiltering() {
        final NamespaceConfig config = withKeys(new LinkedHashSet<>(List.of("visibility", "dept")));

        assertThat(config.indexedMetadataKeys()).containsExactly("visibility", "dept");
        assertThat(config.isMetadataFilteringEnabled()).isTrue();
    }

    @Test
    void nullKeysBecomeEmptySet() {
        assertThat(withKeys(null).indexedMetadataKeys()).isEmpty();
    }

    @Test
    void rejectsBlankKey() {
        assertThatThrownBy(() -> withKeys(new LinkedHashSet<>(List.of("visibility", "  "))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keysAreDefensivelyCopiedAndUnmodifiable() {
        final Set<String> keys = new LinkedHashSet<>(List.of("visibility"));
        final NamespaceConfig config = withKeys(keys);
        keys.add("dept");

        assertThat(config.indexedMetadataKeys()).containsExactly("visibility");
        assertThatThrownBy(() -> config.indexedMetadataKeys().add("other"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void patchDefaultsToNull() {
        assertThat(NamespaceConfigPatch.empty().indexedMetadataKeys()).isNull();

        final List<String> declared = new ArrayList<>(List.of("dept"));
        final NamespaceConfigPatch patch = new NamespaceConfigPatch(
            null, null, null, null, null, null, null, new LinkedHashSet<>(declared));

        assertThat(patch.indexedMetadataKeys()).containsExactly("dept");
    }
}

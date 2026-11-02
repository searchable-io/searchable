package io.searchable.core.infrastructure.lucene;

import io.searchable.core.domain.namespace.Namespace;
import io.searchable.core.domain.namespace.NamespaceConfig;
import io.searchable.core.domain.namespace.NamespaceRepository;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.ja.JapaneseAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NamespaceAwareAnalyzerFactoryTest {

    private final NamespaceRepository namespaces = mock(NamespaceRepository.class);
    private final AnalyzerFactory fallback = AnalyzerFactory.japanese();
    private final NamespaceAwareAnalyzerFactory factory =
        new NamespaceAwareAnalyzerFactory(namespaces, fallback);

    private void namespaceWith(final AnalyzerType type) {
        final NamespaceConfig config = NamespaceConfig.defaults().withAnalyzerType(type);
        when(namespaces.findById(any()))
            .thenReturn(Optional.of(new Namespace("ns", "Namespace", config,
                Instant.EPOCH, Instant.EPOCH)));
    }

    @Test
    void configuredEnglishNamespaceGetsTheEnglishAnalyzer() {
        namespaceWith(AnalyzerType.ENGLISH);

        try (Analyzer analyzer = factory.create("ns")) {
            assertThat(analyzer).isInstanceOf(EnglishAnalyzer.class);
        }
    }

    @Test
    void configuredStandardNamespaceGetsTheStandardAnalyzer() {
        namespaceWith(AnalyzerType.STANDARD);

        try (Analyzer analyzer = factory.create("ns")) {
            assertThat(analyzer).isInstanceOf(StandardAnalyzer.class);
        }
    }

    @Test
    void kuromojiGoesThroughTheFallbackSoUserDictionariesKeepWorking() {
        namespaceWith(AnalyzerType.KUROMOJI);

        try (Analyzer analyzer = factory.create("ns")) {
            assertThat(analyzer).isInstanceOf(JapaneseAnalyzer.class);
        }
    }

    @Test
    void undecidedNamespaceUsesTheFallback() {
        when(namespaces.findById(any())).thenReturn(Optional.of(
            new Namespace("ns", "Namespace", NamespaceConfig.defaults(),
                Instant.EPOCH, Instant.EPOCH)));

        try (Analyzer analyzer = factory.create("ns")) {
            assertThat(analyzer).isInstanceOf(JapaneseAnalyzer.class);
        }
    }

    @Test
    void unknownNamespaceUsesTheFallback() {
        when(namespaces.findById(any())).thenReturn(Optional.empty());

        try (Analyzer analyzer = factory.create("missing")) {
            assertThat(analyzer).isInstanceOf(JapaneseAnalyzer.class);
        }
    }
}

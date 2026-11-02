package io.searchable.core.infrastructure.lucene;

import io.searchable.core.domain.chunking.Chunk;
import io.searchable.core.domain.document.Document;
import org.apache.lucene.index.IndexableField;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LuceneDocumentMapperMetadataTest {

    private final LuceneDocumentMapper mapper = new LuceneDocumentMapper();

    private static Document document(final Map<String, Object> metadata) {
        return Document.builder()
            .id("doc-1")
            .namespaceId("ns")
            .title("title")
            .content("content")
            .metadata(metadata)
            .build();
    }

    private static Chunk chunk() {
        return new Chunk("doc-1", 0, "doc-1#0", "body", Map.of());
    }

    private static List<String> valuesOf(final org.apache.lucene.document.Document lucene,
                                         final String key) {
        return java.util.Arrays.stream(lucene.getFields(LuceneFields.metadataField(key)))
            .map(IndexableField::stringValue)
            .toList();
    }

    @Test
    void writesDeclaredStringKey() {
        final org.apache.lucene.document.Document lucene = mapper.toLucene(
            document(Map.of("dept", "sales")), chunk(), null, Set.of("dept"));

        assertThat(valuesOf(lucene, "dept")).containsExactly("sales");
    }

    @Test
    void writesListValueAsMultipleTerms() {
        final org.apache.lucene.document.Document lucene = mapper.toLucene(
            document(Map.of("tags", List.of("a", "b"))), chunk(), null, Set.of("tags"));

        assertThat(valuesOf(lucene, "tags")).containsExactly("a", "b");
    }

    @Test
    void ignoresUndeclaredKey() {
        final Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("dept", "sales");
        metadata.put("secret", "value");

        final org.apache.lucene.document.Document lucene = mapper.toLucene(
            document(metadata), chunk(), null, Set.of("dept"));

        assertThat(valuesOf(lucene, "dept")).containsExactly("sales");
        assertThat(valuesOf(lucene, "secret")).isEmpty();
    }

    @Test
    void declaredKeyMissingFromDocumentIsSkipped() {
        final org.apache.lucene.document.Document lucene = mapper.toLucene(
            document(Map.of("dept", "sales")), chunk(), null, Set.of("dept", "lang"));

        assertThat(valuesOf(lucene, "lang")).isEmpty();
    }

    @Test
    void blankValueIsSkipped() {
        final org.apache.lucene.document.Document lucene = mapper.toLucene(
            document(Map.of("dept", "   ")), chunk(), null, Set.of("dept"));

        assertThat(valuesOf(lucene, "dept")).isEmpty();
    }

    @Test
    void rejectsNonStringValue() {
        assertThatThrownBy(() -> mapper.toLucene(
            document(Map.of("year", 2026)), chunk(), null, Set.of("year")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("year")
            .hasMessageContaining("doc-1");
    }

    @Test
    void rejectsNonStringInsideList() {
        assertThatThrownBy(() -> mapper.toLucene(
            document(Map.of("tags", List.of("a", 1))), chunk(), null, Set.of("tags")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tags");
    }

    @Test
    void backwardCompatibleOverloadWritesNoMetadata() {
        final org.apache.lucene.document.Document lucene =
            mapper.toLucene(document(Map.of("dept", "sales")), chunk(), null);

        assertThat(valuesOf(lucene, "dept")).isEmpty();
    }

    @Test
    void metadataFieldsAreNotStored() {
        final org.apache.lucene.document.Document lucene = mapper.toLucene(
            document(Map.of("dept", "sales")), chunk(), null, Set.of("dept"));

        assertThat(lucene.getField(LuceneFields.metadataField("dept")).fieldType().stored())
            .isFalse();
    }
}

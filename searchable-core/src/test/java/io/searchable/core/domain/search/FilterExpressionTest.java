package io.searchable.core.domain.search;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilterExpressionTest {

    @Test
    void termHoldsKeyAndValues() {
        final FilterExpression.Term term = FilterExpression.term("dept", "sales", "legal");

        assertThat(term.key()).isEqualTo("dept");
        assertThat(term.values()).containsExactly("sales", "legal");
        assertThat(term.keys()).containsExactly("dept");
    }

    @Test
    void andCollectsKeysFromEveryOperand() {
        final FilterExpression expr = FilterExpression.and(
            FilterExpression.term("visibility", "DEPARTMENT"),
            FilterExpression.term("dept", "sales"));

        assertThat(expr).isInstanceOf(FilterExpression.And.class);
        assertThat(expr.keys()).containsExactlyInAnyOrder("visibility", "dept");
    }

    @Test
    void orNestsInsideAnd() {
        final FilterExpression expr = FilterExpression.or(
            FilterExpression.term("visibility", "COMPANY"),
            FilterExpression.and(
                FilterExpression.term("visibility", "DEPARTMENT"),
                FilterExpression.term("dept", "X")),
            FilterExpression.term("allowed_user", "U"));

        assertThat(expr).isInstanceOf(FilterExpression.Or.class);
        assertThat(expr.keys()).containsExactlyInAnyOrder("visibility", "dept", "allowed_user");
    }

    @Test
    void singleOperandIsFlattened() {
        final FilterExpression.Term term = FilterExpression.term("lang", "ja");

        assertThat(FilterExpression.and(term)).isSameAs(term);
        assertThat(FilterExpression.or(term)).isSameAs(term);
    }

    @Test
    void fromMapBuildsSingleTermForOneKey() {
        final FilterExpression expr = FilterExpression.fromMap(Map.of("lang", "ja")).orElseThrow();

        assertThat(expr).isEqualTo(FilterExpression.term("lang", "ja"));
    }

    @Test
    void fromMapCombinesKeysWithAnd() {
        final Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("lang", "ja");
        filters.put("category", "blog");

        final FilterExpression expr = FilterExpression.fromMap(filters).orElseThrow();

        assertThat(expr).isInstanceOf(FilterExpression.And.class);
        assertThat(expr.keys()).containsExactlyInAnyOrder("lang", "category");
    }

    @Test
    void fromMapCombinesListValuesWithOrInsideOneTerm() {
        final FilterExpression expr =
            FilterExpression.fromMap(Map.of("tags", List.of("a", "b"))).orElseThrow();

        assertThat(expr).isEqualTo(FilterExpression.term("tags", "a", "b"));
    }

    @Test
    void fromMapConvertsNonStringValuesWithToString() {
        final FilterExpression expr = FilterExpression.fromMap(Map.of("year", 2026)).orElseThrow();

        assertThat(expr).isEqualTo(FilterExpression.term("year", "2026"));
    }

    @Test
    void fromMapReturnsEmptyForNullOrEmptyInput() {
        assertThat(FilterExpression.fromMap(null)).isEmpty();
        assertThat(FilterExpression.fromMap(Map.of())).isEmpty();
    }

    @Test
    void fromMapRejectsNullValue() {
        final Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("lang", null);

        assertThatThrownBy(() -> FilterExpression.fromMap(filters))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lang");
    }

    @Test
    void fromMapRejectsNullInsideListValue() {
        final List<Object> values = new ArrayList<>();
        values.add("a");
        values.add(null);

        assertThatThrownBy(() -> FilterExpression.fromMap(Map.of("tags", values)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tags");
    }

    @Test
    void rejectsBlankKey() {
        assertThatThrownBy(() -> FilterExpression.term("  ", "x"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyValues() {
        assertThatThrownBy(() -> FilterExpression.term("dept"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankValue() {
        assertThatThrownBy(() -> FilterExpression.term("dept", "sales", " "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyOperands() {
        assertThatThrownBy(FilterExpression::and)
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FilterExpression.And(List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void valuesAreDefensivelyCopied() {
        final List<String> values = new ArrayList<>(List.of("a"));
        final FilterExpression.Term term = new FilterExpression.Term("tags", values);
        values.add("b");

        assertThat(term.values()).containsExactly("a");
    }
}

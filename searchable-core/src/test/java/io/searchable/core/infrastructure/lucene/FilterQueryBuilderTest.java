package io.searchable.core.infrastructure.lucene;

import io.searchable.core.domain.search.FilterExpression;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilterQueryBuilderTest {

    private static final Set<String> DECLARED =
        Set.of("visibility", "dept", "allowed_user", "tags");

    @Test
    void singleValueBecomesTermQueryOnPrefixedField() {
        final Query query = FilterQueryBuilder.build(
            FilterExpression.term("dept", "sales"), DECLARED);

        assertThat(query).isInstanceOf(TermQuery.class);
        final TermQuery term = (TermQuery) query;
        assertThat(term.getTerm().field()).isEqualTo("meta.dept");
        assertThat(term.getTerm().text()).isEqualTo("sales");
    }

    @Test
    void multipleValuesBecomeTermInSetQuery() {
        final Query query = FilterQueryBuilder.build(
            FilterExpression.term("tags", "a", "b"), DECLARED);

        assertThat(query).isInstanceOf(TermInSetQuery.class);
        assertThat(((TermInSetQuery) query).getTermsCount()).isEqualTo(2);
    }

    @Test
    void andBecomesFilterClauses() {
        final Query query = FilterQueryBuilder.build(
            FilterExpression.and(
                FilterExpression.term("visibility", "DEPARTMENT"),
                FilterExpression.term("dept", "sales")),
            DECLARED);

        assertThat(query).isInstanceOf(BooleanQuery.class);
        final BooleanQuery bool = (BooleanQuery) query;
        assertThat(bool.clauses()).hasSize(2);
        assertThat(bool.clauses()).allMatch(c -> c.occur() == BooleanClause.Occur.FILTER);
        assertThat(bool.getMinimumNumberShouldMatch()).isZero();
    }

    @Test
    void orBecomesShouldClausesRequiringOneMatch() {
        final Query query = FilterQueryBuilder.build(
            FilterExpression.or(
                FilterExpression.term("visibility", "COMPANY"),
                FilterExpression.term("allowed_user", "U")),
            DECLARED);

        final BooleanQuery bool = (BooleanQuery) query;
        assertThat(bool.clauses()).allMatch(c -> c.occur() == BooleanClause.Occur.SHOULD);
        assertThat(bool.getMinimumNumberShouldMatch()).isEqualTo(1);
    }

    @Test
    void nestedExpressionKeepsStructure() {
        final Query query = FilterQueryBuilder.build(
            FilterExpression.or(
                FilterExpression.term("visibility", "COMPANY"),
                FilterExpression.and(
                    FilterExpression.term("visibility", "DEPARTMENT"),
                    FilterExpression.term("dept", "sales")),
                FilterExpression.term("allowed_user", "U")),
            DECLARED);

        final BooleanQuery outer = (BooleanQuery) query;
        assertThat(outer.clauses()).hasSize(3);
        assertThat(outer.getMinimumNumberShouldMatch()).isEqualTo(1);

        final Query nested = outer.clauses().get(1).query();
        assertThat(nested).isInstanceOf(BooleanQuery.class);
        assertThat(((BooleanQuery) nested).clauses())
            .allMatch(c -> c.occur() == BooleanClause.Occur.FILTER);
    }

    @Test
    void rejectsUndeclaredKey() {
        assertThatThrownBy(() -> FilterQueryBuilder.build(
            FilterExpression.term("secret", "x"), DECLARED))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("secret")
            .hasMessageContaining("dept");
    }

    @Test
    void rejectsUndeclaredKeyNestedInsideExpression() {
        assertThatThrownBy(() -> FilterQueryBuilder.build(
            FilterExpression.or(
                FilterExpression.term("dept", "sales"),
                FilterExpression.term("secret", "x")),
            DECLARED))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("secret");
    }

    @Test
    void rejectsEmptyDeclaredKeySet() {
        assertThatThrownBy(() -> FilterQueryBuilder.build(
            FilterExpression.term("dept", "sales"), Set.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}

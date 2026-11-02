package io.searchable.core.infrastructure.lucene;

import io.searchable.core.domain.search.FilterExpression;
import io.searchable.core.spi.MetadataFieldPlugin;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Translates a {@link FilterExpression} into a Lucene {@link Query} that
 * narrows candidates without contributing to the score.
 *
 * <p>The produced query is meant to be attached as a
 * {@link BooleanClause.Occur#FILTER} clause of the full-text query, or passed
 * as the filter argument of a KNN vector query. Within the expression,
 * {@code And} becomes filter clauses and {@code Or} becomes should clauses with
 * a minimum of one match; because the whole query is consumed as a filter,
 * neither contributes to scoring.
 *
 * <p>Every key in the expression must be declared as indexable on the
 * namespace. A key that is not declared is rejected rather than silently
 * dropped or degraded into a post-search filter
 * (see {@code docs/devel/adr/0003-reject-filtering-on-undeclared-metadata-keys.md}).
 */
public final class FilterQueryBuilder {

    private FilterQueryBuilder() { }

    /**
     * Build the filter query for an expression.
     *
     * @param expression   the filter expression
     * @param declaredKeys metadata keys declared as indexable on the namespace
     * @return the Lucene query
     * @throws IllegalArgumentException when the expression references a key that is not declared
     */
    public static Query build(final FilterExpression expression, final Set<String> declaredKeys) {
        return build(expression, declaredKeys, List.of());
    }

    /**
     * Build the filter query, letting plugins own the keys they claim.
     *
     * <p>A key claimed by a {@link MetadataFieldPlugin} is translated by that
     * plugin; every other key falls back to the built-in exact match. The
     * plugin that writes a key to the index must be the one that queries it,
     * which is why both halves live in the same plugin.
     *
     * @param expression   the filter expression
     * @param declaredKeys metadata keys declared as indexable on the namespace
     * @param plugins      the plugins enabled for the namespace, in order
     * @return the Lucene query
     * @throws IllegalArgumentException when the expression references a key that is not declared
     */
    public static Query build(final FilterExpression expression, final Set<String> declaredKeys,
                              final List<MetadataFieldPlugin> plugins) {
        Objects.requireNonNull(expression, "expression must not be null");
        Objects.requireNonNull(declaredKeys, "declaredKeys must not be null");
        Objects.requireNonNull(plugins, "plugins must not be null");
        verifyDeclared(expression, declaredKeys);
        return toQuery(expression, plugins);
    }

    private static void verifyDeclared(final FilterExpression expression, final Set<String> declaredKeys) {
        for (final String key : expression.keys()) {
            if (!declaredKeys.contains(key)) {
                throw new IllegalArgumentException(
                    "Filter key '" + key + "' is not declared as an indexable metadata key; declared keys: "
                        + new TreeSet<>(declaredKeys));
            }
        }
    }

    private static Query toQuery(final FilterExpression expression,
                                 final List<MetadataFieldPlugin> plugins) {
        return switch (expression) {
            case FilterExpression.Term term -> termQuery(term, plugins);
            case FilterExpression.And and ->
                combine(and.operands(), BooleanClause.Occur.FILTER, false, plugins);
            case FilterExpression.Or or ->
                combine(or.operands(), BooleanClause.Occur.SHOULD, true, plugins);
        };
    }

    private static Query termQuery(final FilterExpression.Term term,
                                   final List<MetadataFieldPlugin> plugins) {
        final String field = LuceneFields.metadataField(term.key());
        for (final MetadataFieldPlugin plugin : plugins) {
            if (plugin.supports(term.key())) {
                final Query query = plugin.query(field, term.key(), term.values());
                if (query == null) {
                    throw new IllegalStateException("Plugin '" + plugin.name()
                        + "' returned no query for metadata key '" + term.key() + "'");
                }
                return query;
            }
        }
        final List<String> values = term.values();
        if (values.size() == 1) {
            return new TermQuery(new Term(field, values.get(0)));
        }
        return new TermInSetQuery(field, values.stream().map(BytesRef::new).toList());
    }

    private static Query combine(final List<FilterExpression> operands,
                                 final BooleanClause.Occur occur,
                                 final boolean requireOne,
                                 final List<MetadataFieldPlugin> plugins) {
        final BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (final FilterExpression operand : operands) {
            builder.add(toQuery(operand, plugins), occur);
        }
        if (requireOne) {
            builder.setMinimumNumberShouldMatch(1);
        }
        return builder.build();
    }
}

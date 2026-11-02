package io.searchable.core.domain.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Immutable boolean expression that narrows search candidates before scoring.
 *
 * <p>The expression supports the three shapes needed to express an access
 * control condition such as
 * {@code visibility=COMPANY OR (visibility=DEPARTMENT AND dept=X) OR allowed_user=U}:
 *
 * <ul>
 *   <li>{@link Term} -- one metadata key matching any of the listed values (OR within the key)</li>
 *   <li>{@link And} -- every operand must match</li>
 *   <li>{@link Or} -- at least one operand must match</li>
 * </ul>
 *
 * <p>Values are compared as exact strings. The index stores declared metadata
 * keys without analysis, so case and width differences do not match; callers
 * normalise values on both the indexing and the query side.
 *
 * <p>Only keys declared as indexable on the namespace may appear here. A key
 * that is not declared is rejected rather than silently dropped
 * (see {@code docs/devel/adr/0003-reject-filtering-on-undeclared-metadata-keys.md}).
 */
public sealed interface FilterExpression {

    /**
     * Every metadata key referenced anywhere in this expression.
     *
     * @return the referenced keys
     */
    Set<String> keys();

    /**
     * One metadata key matched against a set of candidate values.
     *
     * @param key    the metadata key, never blank
     * @param values the accepted values, at least one, none of them blank
     */
    record Term(String key, List<String> values) implements FilterExpression {

        public Term {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(values, "values must not be null");
            if (key.isBlank()) {
                throw new IllegalArgumentException("key must not be blank");
            }
            if (values.isEmpty()) {
                throw new IllegalArgumentException("values must not be empty for key: " + key);
            }
            for (final String value : values) {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("value must not be blank for key: " + key);
                }
            }
            values = List.copyOf(values);
        }

        @Override
        public Set<String> keys() {
            return Set.of(key);
        }
    }

    /**
     * Conjunction of two or more operands.
     *
     * @param operands the operands, at least two
     */
    record And(List<FilterExpression> operands) implements FilterExpression {

        public And {
            operands = requireAtLeastTwo(operands, "And");
        }

        @Override
        public Set<String> keys() {
            return collectKeys(operands);
        }
    }

    /**
     * Disjunction of two or more operands.
     *
     * @param operands the operands, at least two
     */
    record Or(List<FilterExpression> operands) implements FilterExpression {

        public Or {
            operands = requireAtLeastTwo(operands, "Or");
        }

        @Override
        public Set<String> keys() {
            return collectKeys(operands);
        }
    }

    /**
     * Build a term matching {@code key} against any of {@code values}.
     *
     * @param key    the metadata key
     * @param values the accepted values, at least one
     * @return the term
     */
    static Term term(final String key, final String... values) {
        Objects.requireNonNull(values, "values must not be null");
        return new Term(key, Arrays.asList(values));
    }

    /**
     * Combine operands with AND, flattening a single operand to itself.
     *
     * @param operands the operands, at least one
     * @return the combined expression
     */
    static FilterExpression and(final FilterExpression... operands) {
        return combine(operands, And::new);
    }

    /**
     * Combine operands with OR, flattening a single operand to itself.
     *
     * @param operands the operands, at least one
     * @return the combined expression
     */
    static FilterExpression or(final FilterExpression... operands) {
        return combine(operands, Or::new);
    }

    /**
     * Convert the legacy {@code Map} form of filters into an expression.
     *
     * <p>Keys are combined with AND. A {@link List} value under one key is
     * combined with OR, matching the semantics of the former post-search
     * {@code FacetFilter}. Values are converted with {@code toString()}.
     *
     * @param filters the legacy filters, may be null or empty
     * @return the expression, or empty when no filter was supplied
     */
    static Optional<FilterExpression> fromMap(final Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return Optional.empty();
        }
        final List<FilterExpression> terms = new ArrayList<>(filters.size());
        for (final Map.Entry<String, Object> entry : filters.entrySet()) {
            terms.add(toTerm(entry.getKey(), entry.getValue()));
        }
        return Optional.of(terms.size() == 1 ? terms.get(0) : new And(terms));
    }

    private static Term toTerm(final String key, final Object value) {
        if (value == null) {
            throw new IllegalArgumentException("filter value must not be null for key: " + key);
        }
        if (value instanceof List<?> list) {
            final List<String> values = new ArrayList<>(list.size());
            for (final Object item : list) {
                if (item == null) {
                    throw new IllegalArgumentException("filter value must not contain null for key: " + key);
                }
                values.add(item.toString());
            }
            return new Term(key, values);
        }
        return new Term(key, List.of(value.toString()));
    }

    private static FilterExpression combine(final FilterExpression[] operands,
                                            final Function<List<FilterExpression>, FilterExpression> factory) {
        Objects.requireNonNull(operands, "operands must not be null");
        if (operands.length == 0) {
            throw new IllegalArgumentException("operands must not be empty");
        }
        if (operands.length == 1) {
            return Objects.requireNonNull(operands[0], "operand must not be null");
        }
        return factory.apply(Arrays.asList(operands));
    }

    private static List<FilterExpression> requireAtLeastTwo(final List<FilterExpression> operands,
                                                            final String type) {
        Objects.requireNonNull(operands, "operands must not be null");
        if (operands.size() < 2) {
            throw new IllegalArgumentException(type + " requires at least two operands");
        }
        for (final FilterExpression operand : operands) {
            Objects.requireNonNull(operand, "operand must not be null");
        }
        return List.copyOf(operands);
    }

    private static Set<String> collectKeys(final List<FilterExpression> operands) {
        final Set<String> keys = new LinkedHashSet<>();
        for (final FilterExpression operand : operands) {
            keys.addAll(operand.keys());
        }
        return Set.copyOf(keys);
    }
}

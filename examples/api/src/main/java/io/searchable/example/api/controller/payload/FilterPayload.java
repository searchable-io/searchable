package io.searchable.example.api.controller.payload;

import io.searchable.core.domain.search.FilterExpression;

import java.util.List;

/**
 * JSON form of a search filter expression.
 *
 * <p>Exactly one of {@code term}, {@code and} or {@code or} must be set:
 *
 * <pre>{@code
 * {"or": [
 *   {"term": {"key": "visibility", "values": ["COMPANY"]}},
 *   {"and": [
 *     {"term": {"key": "visibility", "values": ["DEPARTMENT"]}},
 *     {"term": {"key": "dept", "values": ["X"]}}
 *   ]},
 *   {"term": {"key": "allowed_user", "values": ["U"]}}
 * ]}
 * }</pre>
 *
 * <p>Every key must be declared as indexable on the namespace; an undeclared
 * key is rejected rather than ignored.
 *
 * @param term one key matched against a set of values
 * @param and  operands that must all match
 * @param or   operands of which at least one must match
 */
public record FilterPayload(
    TermPayload term,
    List<FilterPayload> and,
    List<FilterPayload> or
) {

    /**
     * One metadata key matched against a set of values.
     *
     * @param key    the metadata key
     * @param values the accepted values, at least one
     */
    public record TermPayload(String key, List<String> values) { }

    /**
     * Convert to the domain expression.
     *
     * @return the expression
     * @throws IllegalArgumentException when the payload is not exactly one of the three shapes
     */
    public FilterExpression toDomain() {
        final int specified = (term == null ? 0 : 1) + (and == null ? 0 : 1) + (or == null ? 0 : 1);
        if (specified != 1) {
            throw new IllegalArgumentException(
                "A filter node must set exactly one of 'term', 'and' or 'or'");
        }
        if (term != null) {
            if (term.key() == null || term.values() == null || term.values().isEmpty()) {
                throw new IllegalArgumentException(
                    "A 'term' node requires a key and at least one value");
            }
            return FilterExpression.term(term.key(), term.values().toArray(new String[0]));
        }
        if (and != null) {
            return FilterExpression.and(operands(and, "and"));
        }
        return FilterExpression.or(operands(or, "or"));
    }

    private static FilterExpression[] operands(final List<FilterPayload> payloads, final String node) {
        if (payloads.isEmpty()) {
            throw new IllegalArgumentException("A '" + node + "' node requires at least one operand");
        }
        return payloads.stream()
            .map(payload -> {
                if (payload == null) {
                    throw new IllegalArgumentException("A '" + node + "' node must not contain null");
                }
                return payload.toDomain();
            })
            .toArray(FilterExpression[]::new);
    }
}

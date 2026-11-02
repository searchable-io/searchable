package io.searchable.core.application;

import io.searchable.core.domain.search.SearchHit;

import java.util.List;
import java.util.Map;

/**
 * Post-search filter that retains only the hits matching every entry in the
 * supplied filter map.
 *
 * <p><strong>This runs after the search.</strong> The engine has already
 * returned at most {@code limit} hits by the time this filter is applied, so
 * hits it discards are not replaced and the caller receives fewer results than
 * requested. Query-stage filtering, which does not have this problem, is built
 * by {@code FilterQueryBuilder} and applied inside the searchers.
 *
 * <p>This class is not called from the search path. It is kept as the basis
 * for the post-search extension point described in requirement R10.
 *
 * <p>Semantics:
 * <ul>
 *   <li>Multiple keys are combined with <strong>AND</strong>.</li>
 *   <li>A list value under a single key is combined with <strong>OR</strong>
 *       (the hit matches when its metadata value equals any list entry).</li>
 *   <li>The reserved key {@value #NAMESPACE_KEY} matches on
 *       {@link SearchHit#namespaceId()}.</li>
 *   <li>The reserved key {@value #DOCUMENT_ID_KEY} matches on
 *       {@link SearchHit#documentId()}.</li>
 * </ul>
 */
public final class FacetFilter {

    public static final String NAMESPACE_KEY = "_namespace";
    public static final String DOCUMENT_ID_KEY = "_id";

    private FacetFilter() { }

    public static List<SearchHit> apply(final List<SearchHit> hits,
                                        final Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return hits;
        }
        return hits.stream().filter(h -> matches(h, filters)).toList();
    }

    private static boolean matches(final SearchHit hit, final Map<String, Object> filters) {
        for (final Map.Entry<String, Object> e : filters.entrySet()) {
            final Object expected = e.getValue();
            final List<Object> expectedList = expected instanceof List<?> list
                ? List.copyOf(list)
                : List.of(expected);
            if (!matchesAny(hit, e.getKey(), expectedList)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesAny(final SearchHit hit, final String key,
                                      final List<Object> expected) {
        final Object actual = lookup(hit, key);
        if (actual == null) {
            return false;
        }
        if (actual instanceof List<?> list) {
            for (final Object item : list) {
                if (item == null) {
                    continue;
                }
                for (final Object e : expected) {
                    if (item.toString().equals(toStr(e))) {
                        return true;
                    }
                }
            }
            return false;
        }
        for (final Object e : expected) {
            if (actual.toString().equals(toStr(e))) {
                return true;
            }
        }
        return false;
    }

    private static Object lookup(final SearchHit hit, final String key) {
        return switch (key) {
            case NAMESPACE_KEY -> hit.namespaceId();
            case DOCUMENT_ID_KEY -> hit.documentId();
            default -> hit.metadata().get(key);
        };
    }

    private static String toStr(final Object o) {
        return o == null ? null : o.toString();
    }
}

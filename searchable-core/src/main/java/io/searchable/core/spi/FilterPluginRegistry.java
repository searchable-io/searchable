package io.searchable.core.spi;

import io.searchable.plugin.SearchablePlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * The search filter plugins available to this library instance.
 *
 * <p>Plugins are discovered once and then selected per namespace by name, so a
 * plugin on the classpath changes nothing until a namespace asks for it.
 */
public final class FilterPluginRegistry {

    private final Map<String, MetadataFieldPlugin> fieldPlugins;
    private final Map<String, SearchHitFilterPlugin> hitFilters;

    public FilterPluginRegistry(final List<MetadataFieldPlugin> fieldPlugins,
                                final List<SearchHitFilterPlugin> hitFilters) {
        this.fieldPlugins = index(fieldPlugins);
        this.hitFilters = index(hitFilters);
    }

    private static <T extends SearchablePlugin> Map<String, T> index(final List<T> plugins) {
        final Map<String, T> byName = new LinkedHashMap<>();
        if (plugins == null) {
            return byName;
        }
        for (final T plugin : plugins) {
            Objects.requireNonNull(plugin, "plugin must not be null");
            final T previous = byName.put(plugin.name(), plugin);
            if (previous != null) {
                throw new IllegalStateException(
                    "Two search filter plugins are registered under the name '" + plugin.name() + "'");
            }
        }
        return byName;
    }

    /** A registry holding no plugin. */
    public static FilterPluginRegistry empty() {
        return new FilterPluginRegistry(List.of(), List.of());
    }

    /**
     * Load every plugin published through {@link ServiceLoader}.
     *
     * @return the registry
     */
    public static FilterPluginRegistry discover() {
        final List<MetadataFieldPlugin> fields = new ArrayList<>();
        ServiceLoader.load(MetadataFieldPlugin.class).forEach(fields::add);
        final List<SearchHitFilterPlugin> hits = new ArrayList<>();
        ServiceLoader.load(SearchHitFilterPlugin.class).forEach(hits::add);
        return new FilterPluginRegistry(fields, hits);
    }

    /**
     * The field plugins a namespace enabled, in the order it named them.
     *
     * @param enabledNames plugin names from the namespace configuration
     * @return the plugins
     * @throws IllegalArgumentException when a named plugin is not registered
     */
    public List<MetadataFieldPlugin> fieldPlugins(final Set<String> enabledNames) {
        return select(fieldPlugins, hitFilters, enabledNames);
    }

    /**
     * The hit filters a namespace enabled, in the order it named them.
     *
     * @param enabledNames plugin names from the namespace configuration
     * @return the plugins
     * @throws IllegalArgumentException when a named plugin is not registered
     */
    public List<SearchHitFilterPlugin> hitFilters(final Set<String> enabledNames) {
        return select(hitFilters, fieldPlugins, enabledNames);
    }

    /**
     * Pick the plugins of one kind that a namespace named.
     *
     * <p>A name that matches neither kind fails rather than being skipped. A
     * plugin is typically enabled to narrow what a search returns, so dropping
     * it because of a typo or a missing jar would widen the result set without
     * anyone noticing.
     *
     * @param registered   plugins of the kind being selected
     * @param otherKind    plugins of the other kind, which legitimately own
     *                     some of the names in the same list
     * @param enabledNames plugin names from the namespace configuration
     */
    private static <T extends SearchablePlugin> List<T> select(
            final Map<String, T> registered,
            final Map<String, ? extends SearchablePlugin> otherKind,
            final Set<String> enabledNames) {
        if (enabledNames == null || enabledNames.isEmpty()) {
            return List.of();
        }
        final List<T> selected = new ArrayList<>();
        for (final String name : enabledNames) {
            final T plugin = registered.get(name);
            if (plugin != null) {
                selected.add(plugin);
                continue;
            }
            if (otherKind.containsKey(name)) {
                continue;
            }
            throw new IllegalArgumentException(
                "Filter plugin '" + name + "' is enabled on the namespace but is not registered;"
                    + " registered plugins: " + registeredNamesOf(registered, otherKind));
        }
        return List.copyOf(selected);
    }

    private static Set<String> registeredNamesOf(final Map<String, ?> first,
                                                 final Map<String, ?> second) {
        final Set<String> names = new java.util.LinkedHashSet<>(first.keySet());
        names.addAll(second.keySet());
        return names;
    }

    /** Names of every registered plugin, for diagnostics. */
    public Set<String> registeredNames() {
        final Set<String> names = new java.util.LinkedHashSet<>(fieldPlugins.keySet());
        names.addAll(hitFilters.keySet());
        return Set.copyOf(names);
    }
}

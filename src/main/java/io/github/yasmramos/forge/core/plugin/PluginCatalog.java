package io.github.yasmramos.forge.core.plugin;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The set of plugins loaded for a build, keyed by plugin id (AGENT.md section
 * 4bis). Enforces the descriptor-level invariants: ids are unique, and a
 * plugin's declared {@code [[provides]]} must agree with what it registers at
 * runtime. Actual bytecode loading lives elsewhere; this type owns the
 * identity and consistency rules so they hold no matter who loads the jars.
 */
public final class PluginCatalog {

    private final Map<String, PluginDescriptor> byId = new LinkedHashMap<>();

    /**
     * Adds a descriptor, rejecting a duplicate plugin id ({@code FORGE-PLG-0004}).
     * Returns {@code this} for fluent assembly.
     */
    public PluginCatalog register(PluginDescriptor descriptor) {
        PluginDescriptor previous = byId.putIfAbsent(descriptor.getId(), descriptor);
        if (previous != null) {
            throw new ForgeException(ErrorCode.PLG_0004,
                "plugin id '" + descriptor.getId() + "' is declared by both "
                    + previous.idVersion() + " and " + descriptor.idVersion());
        }
        return this;
    }

    /**
     * Verifies that a plugin's declared {@code [[provides]]} entries match the
     * extension points it actually registered at runtime, failing with
     * {@code FORGE-PLG-0005} on drift (section 4bis: declaration and behavior
     * must not diverge silently).
     *
     * @param descriptor          the parsed descriptor
     * @param runtimeRegistrations what the plugin registered, as "type:name"
     */
    public static void verifyProvides(PluginDescriptor descriptor, Collection<String> runtimeRegistrations) {
        Set<String> declared = new LinkedHashSet<>();
        for (PluginDescriptor.Provides p : descriptor.getProvides()) {
            declared.add(p.toString());
        }
        Set<String> runtime = new LinkedHashSet<>(runtimeRegistrations);
        if (!declared.equals(runtime)) {
            Set<String> declaredOnly = new LinkedHashSet<>(declared);
            declaredOnly.removeAll(runtime);
            Set<String> runtimeOnly = new LinkedHashSet<>(runtime);
            runtimeOnly.removeAll(declared);
            throw new ForgeException(ErrorCode.PLG_0005,
                "plugin '" + descriptor.idVersion() + "' declares " + declared
                    + " but registers " + runtime
                    + " (declared only: " + declaredOnly + ", runtime only: " + runtimeOnly + ")");
        }
    }

    public boolean contains(String id) {
        return byId.containsKey(id);
    }

    public PluginDescriptor get(String id) {
        return byId.get(id);
    }

    /** Loaded plugins as {@code id:version} pairs, for cache keys and diagnostics. */
    public List<String> idVersions() {
        List<String> result = new ArrayList<>();
        for (PluginDescriptor d : byId.values()) {
            result.add(d.idVersion());
        }
        Collections.sort(result);
        return result;
    }

    public Collection<PluginDescriptor> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public int size() {
        return byId.size();
    }
}

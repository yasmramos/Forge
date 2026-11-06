package io.github.yasmramos.forge.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Scope rules for the flattened dependency map produced by
 * {@code ConfigReader} ({@code group:artifact -> {version, type, scope}}).
 *
 * <p>A dependency's scope decides which classpath it belongs to. Getting this
 * wrong leaks {@code test}/{@code runtime} artifacts onto the main compile
 * classpath (AGENT.md section 2), so the rules live in one place and every
 * resolution path applies them.</p>
 */
public final class DependencyScopes {

    /** Scopes on the main compile classpath. */
    public static final Set<String> COMPILE =
        Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("compile", "provided")));

    /** Scopes on the run classpath ({@code forge run}): compile + runtime. */
    public static final Set<String> RUNTIME =
        Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "compile", "provided", "runtime")));

    /** Scopes available to test compilation and execution: everything. */
    public static final Set<String> TEST =
        Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "compile", "provided", "runtime", "test")));

    private DependencyScopes() {
    }

    /** Scope of a flattened dependency entry; defaults to {@code compile}. */
    public static String scopeOf(Object depValue) {
        if (depValue instanceof Map) {
            Object s = ((Map<?, ?>) depValue).get("scope");
            if (s != null) {
                return String.valueOf(s);
            }
        }
        return "compile";
    }

    /** Version of a flattened dependency entry, or {@code null} when unset. */
    public static String versionOf(Object depValue) {
        if (depValue instanceof String) {
            return (String) depValue;
        }
        if (depValue instanceof Map) {
            Object v = ((Map<?, ?>) depValue).get("version");
            if (v != null) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    /** Entries of {@code declared} whose scope is in {@code allowedScopes}. */
    public static Map<String, Object> filter(Map<String, Object> declared, Set<String> allowedScopes) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (declared != null) {
            for (Map.Entry<String, Object> e : declared.entrySet()) {
                if (allowedScopes.contains(scopeOf(e.getValue()))) {
                    out.put(e.getKey(), e.getValue());
                }
            }
        }
        return out;
    }
}

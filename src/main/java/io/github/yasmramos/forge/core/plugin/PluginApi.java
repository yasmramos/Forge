package io.github.yasmramos.forge.core.plugin;

/**
 * The core plugin API version this Forge build implements (AGENT.md section
 * 4bis). A plugin descriptor declares the range of API versions it was built
 * against; the range is checked against this value before the plugin loads.
 * Bumping the major here means breaking plugin changes, by policy.
 */
public final class PluginApi {

    /** Current core plugin API version. */
    public static final String CURRENT = "0.1";

    private PluginApi() {
    }

    public static ApiVersion currentVersion() {
        return ApiVersion.parse(CURRENT);
    }
}

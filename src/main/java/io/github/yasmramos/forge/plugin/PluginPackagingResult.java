package io.github.yasmramos.forge.plugin;

/**
 * Result of a {@link PluginPackagingType} run. On failure the message must
 * answer what happened, why, and how to fix it (AGENT.md section 10).
 */
public final class PluginPackagingResult {

    private final boolean success;
    private final String message;

    private PluginPackagingResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public static PluginPackagingResult success() {
        return new PluginPackagingResult(true, null);
    }

    public static PluginPackagingResult success(String message) {
        return new PluginPackagingResult(true, message);
    }

    public static PluginPackagingResult failure(String message) {
        return new PluginPackagingResult(false, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }
}

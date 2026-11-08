package io.github.yasmramos.forge.plugin;

/**
 * Result of a plugin task action. Mirrors the core task result: success carries
 * an optional message, failure carries one that answers what/why/how
 * (AGENT.md section 10).
 */
public final class PluginTaskResult {

    private final boolean success;
    private final String message;

    private PluginTaskResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public static PluginTaskResult success() {
        return new PluginTaskResult(true, null);
    }

    public static PluginTaskResult success(String message) {
        return new PluginTaskResult(true, message);
    }

    public static PluginTaskResult failure(String message) {
        return new PluginTaskResult(false, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }
}

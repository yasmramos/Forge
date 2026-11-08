package io.github.yasmramos.forge.plugin;

/**
 * Result of a {@link PluginCompiler} run. On failure the message must answer
 * what happened, why, and how to fix it (AGENT.md section 10).
 */
public final class PluginCompilationResult {

    private final boolean success;
    private final String message;

    private PluginCompilationResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public static PluginCompilationResult success() {
        return new PluginCompilationResult(true, null);
    }

    public static PluginCompilationResult success(String message) {
        return new PluginCompilationResult(true, message);
    }

    public static PluginCompilationResult failure(String message) {
        return new PluginCompilationResult(false, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }
}

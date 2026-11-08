package io.github.yasmramos.forge.plugin;

/**
 * Result of a {@link PluginTestRunner} run. Carries the counts Forge reports
 * and, on failure, a message that answers what/why/how (AGENT.md section 10).
 */
public final class PluginTestRunResult {

    private final boolean success;
    private final int total;
    private final int passed;
    private final String message;

    private PluginTestRunResult(boolean success, int total, int passed, String message) {
        this.success = success;
        this.total = total;
        this.passed = passed;
        this.message = message;
    }

    public static PluginTestRunResult success(int total, int passed) {
        return new PluginTestRunResult(true, total, passed, null);
    }

    public static PluginTestRunResult failure(int total, int passed, String message) {
        return new PluginTestRunResult(false, total, passed, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public int getTotal() {
        return total;
    }

    public int getPassed() {
        return passed;
    }

    public String getMessage() {
        return message;
    }
}

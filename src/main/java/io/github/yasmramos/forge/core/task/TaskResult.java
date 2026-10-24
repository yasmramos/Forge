package io.github.yasmramos.forge.core.task;

/**
 * Result of executing a task action. Carries an optional human-readable
 * message; failures must explain what happened, why, and how to fix it
 * (AGENT.md decision 10: actionable errors).
 */
public class TaskResult {

    private final boolean success;
    private final String message;

    private TaskResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public static TaskResult success() { return new TaskResult(true, null); }
    public static TaskResult success(String message) { return new TaskResult(true, message); }
    public static TaskResult failure(String message) { return new TaskResult(false, message); }

    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
}

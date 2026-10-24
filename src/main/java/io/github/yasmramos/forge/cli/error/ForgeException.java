package io.github.yasmramos.forge.cli.error;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Exception carrying a structured {@link ForgeError} value (AGENT.md section
 * 10bis). Every user-facing failure in Forge must surface as this type so the
 * CLI can render what/why/how/docs plus the accumulated task context chain.
 *
 * <p>Codes are stable public API and must be registered in
 * {@link ErrorCode}; constructing an exception with an unregistered code is a
 * bug and fails fast, which is exactly what CI enforces.
 */
public class ForgeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** One frame of the task context chain: "at package <- compile". */
    public static final class TaskFrame {
        private final String taskName;
        private final String taskType;

        public TaskFrame(String taskName, String taskType) {
            this.taskName = taskName;
            this.taskType = taskType;
        }

        public String getTaskName() { return taskName; }
        public String getTaskType() { return taskType; }

        @Override
        public String toString() {
            return taskName + " (" + taskType + ")";
        }
    }

    private final ForgeError error;
    private final List<TaskFrame> frames = new ArrayList<>();

    public ForgeException(ErrorCode.Entry entry, String detail) {
        this(toError(entry, detail));
    }

    public ForgeException(ErrorCode.Entry entry, String detail, Throwable cause) {
        this(toError(entry, detail), cause);
    }

    public ForgeException(ForgeError error) {
        super(error.getWhat() + " [code=" + error.getCode() + "]", null, true, false);
        this.error = error;
    }

    public ForgeException(ForgeError error, Throwable cause) {
        super(error.getWhat() + " [code=" + error.getCode() + "]", cause, true, false);
        this.error = error;
    }

    private static ForgeError toError(ErrorCode.Entry entry, String detail) {
        if (!ErrorCode.isRegistered(entry.getCode())) {
            throw new IllegalArgumentException(
                "Unregistered error code (must exist in docs/errors.toml): " + entry.getCode());
        }
        String what = detail == null || detail.isEmpty()
            ? entry.getWhat()
            : entry.getWhat() + " Detail: " + detail;
        return new ForgeError(entry.getCode(), what, entry.getWhy(),
            entry.getHow(), entry.getDocsUrl());
    }

    public ForgeError getError() {
        return error;
    }

    public String getCode() {
        return error.getCode();
    }

    /** Task context chain, innermost frame first. Never null. */
    public List<TaskFrame> getTaskTrace() {
        return Collections.unmodifiableList(frames);
    }

    /**
     * Adds the innermost frame where the failure happened. Returns {@code this}
     * so call sites can chain fluently at the throw site.
     */
    public ForgeException at(String taskName, String taskType) {
        frames.add(new TaskFrame(taskName, taskType));
        return this;
    }

    /**
     * Wraps an upstream error, prepending the current task as a context frame
     * (outer propagation). The original structured error is preserved intact.
     */
    public ForgeException propagateThrough(String taskName, String taskType) {
        frames.add(0, new TaskFrame(taskName, taskType));
        return this;
    }

    /** Renders the chain like the CLI does: "at package <- compile". */
    public String formatTaskChain() {
        if (frames.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("  at  ");
        for (int i = 0; i < frames.size(); i++) {
            if (i > 0) {
                sb.append(" <- ");
            }
            sb.append(frames.get(i));
        }
        return sb.toString();
    }

    /** Human rendering for TTYs; stderr-bound (AGENT.md decisions 10bis, 11). */
    public String formatUserFacing() {
        StringBuilder sb = new StringBuilder(error.format());
        String chain = formatTaskChain();
        if (!chain.isEmpty()) {
            sb.append('\n').append(chain);
        }
        return sb.toString();
    }

    /** JSON-per-line rendering for CI ({@code --format=json}, section 10bis). */
    public String formatJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"code\":\"").append(json(error.getCode())).append("\",");
        sb.append("\"what\":\"").append(json(error.getWhat())).append("\",");
        sb.append("\"why\":\"").append(json(error.getWhy())).append("\",");
        sb.append("\"how\":\"").append(json(error.getHowToFix())).append("\",");
        sb.append("\"docs\":\"").append(json(error.getDocsUrl())).append("\",");
        sb.append("\"task_chain\":[");
        for (int i = 0; i < frames.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":\"").append(json(frames.get(i).getTaskName()))
              .append("\",\"type\":\"").append(json(frames.get(i).getTaskType())).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String json(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                                 .replace("\n", "\\n").replace("\r", "");
    }
}

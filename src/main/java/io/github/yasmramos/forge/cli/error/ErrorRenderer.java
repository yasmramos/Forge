package io.github.yasmramos.forge.cli.error;

import java.io.PrintStream;

/**
 * Central renderer for structured errors (AGENT.md section 10bis).
 *
 * <p>Keeps the rendering policy in one place so every command answers the
 * same three questions and respects the same flags: human blocks on a TTY,
 * JSON-per-line in CI ({@code --format=json}), and Java stack traces only
 * when {@code --debug} is set. Exit codes are mapped by error class.
 */
public final class ErrorRenderer {

    private final boolean json;
    private final boolean debug;
    private final PrintStream err;

    public ErrorRenderer(PrintStream err, boolean json, boolean debug) {
        this.err = err;
        this.json = json;
        this.debug = debug;
    }

    /** Renders a structured error and returns the process exit code. */
    public int render(ForgeError error) {
        if (json) {
            err.println(toJson(error, ""));
        } else {
            err.println(error.format());
        }
        return error.exitCode();
    }

    /** Renders an exception, preserving any task context chain. */
    public int render(ForgeException ex) {
        if (json) {
            err.println(ex.formatJson());
        } else {
            err.println(ex.formatUserFacing(debug));
        }
        return ex.getError().exitCode();
    }

    /**
     * Renders an arbitrary exception, translating it through the legacy
     * adapter when it is not already structured.
     */
    public int render(Exception e) {
        if (e instanceof ForgeException) {
            return render((ForgeException) e);
        }
        ForgeError error = ForgeError.of(e);
        if (json) {
            err.println(toJson(error, e.getMessage()));
        } else {
            err.println(error.format());
            if (debug) {
                e.printStackTrace(err);
            }
        }
        return error.exitCode();
    }

    private static String toJson(ForgeError error, String detail) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"code\":\"").append(escape(error.getCode())).append("\",");
        sb.append("\"what\":\"").append(escape(error.getWhat())).append("\",");
        sb.append("\"why\":\"").append(escape(error.getWhy())).append("\",");
        sb.append("\"how\":\"").append(escape(error.getHowToFix())).append("\",");
        sb.append("\"docs\":\"").append(escape(error.getDocsUrl())).append("\",");
        sb.append("\"task_chain\":[]}");
        return sb.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                                 .replace("\n", "\\n").replace("\r", "");
    }
}

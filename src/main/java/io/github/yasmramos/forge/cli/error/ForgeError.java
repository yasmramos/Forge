package io.github.yasmramos.forge.cli.error;

/**
 * Structured error answering three questions (AGENT.md decision 10):
 * what happened, why, and how to fix it. An error that cannot answer all
 * three is considered badly designed. Docs links are short URLs per code.
 */
public class ForgeError {

    private final String code;
    private final String what;
    private final String why;
    private final String howToFix;
    private final String docsUrl;

    public ForgeError(String code, String what, String why, String howToFix, String docsUrl) {
        this.code = code;
        this.what = what;
        this.why = why;
        this.howToFix = howToFix;
        this.docsUrl = docsUrl;
    }

    public String getCode() { return code; }
    public String getWhat() { return what; }
    public String getWhy() { return why; }
    public String getHowToFix() { return howToFix; }
    public String getDocsUrl() { return docsUrl; }

    /** Human rendering for TTYs; stderr-bound (AGENT.md decision 11). */
    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append("error[").append(code).append("]: ").append(what).append('\n');
        sb.append("  why : ").append(why).append('\n');
        sb.append("  fix : ").append(howToFix).append('\n');
        if (docsUrl != null && !docsUrl.isEmpty()) {
            sb.append("  docs: ").append(docsUrl);
        }
        return sb.toString();
    }

    public static ForgeError of(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (m.contains("cycle detected")) {
            return new ForgeError("F001", m,
                "Tasks form a dependency loop, so no valid execution order exists.",
                "Remove or invert one edge shown in the cycle path.",
                "https://forge.dev/errors/F001");
        }
        if (m.contains("stale") || m.contains("forge.lock")) {
            return new ForgeError("F002", m,
                "The lockfile was produced from a different configuration.",
                "Run 'forge update' and commit the refreshed forge.lock.",
                "https://forge.dev/errors/F002");
        }
        return new ForgeError("F000", m,
            "An unexpected error occurred while running the build.",
            "Re-run with --debug for details, or report this error.",
            "https://forge.dev/errors/F000");
    }
}

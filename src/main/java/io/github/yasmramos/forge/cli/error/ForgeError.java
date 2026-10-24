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

    /**
     * Best-effort translation of a legacy exception into a registered,
     * structured error. New code must throw {@link ForgeException} built from
     * an {@link ErrorCode.Entry} directly; this adapter only exists so older
     * call sites keep producing catalog codes instead of ad-hoc strings.
     */
    public static ForgeError of(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (e instanceof ForgeException) {
            return ((ForgeException) e).getError();
        }
        if (m.contains("cycle detected")) {
            return new ForgeError(ErrorCode.TSK_0001.getCode(), m,
                ErrorCode.TSK_0001.getWhy(), ErrorCode.TSK_0001.getHow(),
                ErrorCode.TSK_0001.getDocsUrl());
        }
        if (m.contains("dangling") || m.contains("unknown task")) {
            return new ForgeError(ErrorCode.TSK_0002.getCode(), m,
                ErrorCode.TSK_0002.getWhy(), ErrorCode.TSK_0002.getHow(),
                ErrorCode.TSK_0002.getDocsUrl());
        }
        if (m.contains("Missing forge.lock")) {
            return new ForgeError(ErrorCode.LCK_0004.getCode(), m,
                ErrorCode.LCK_0004.getWhy(), ErrorCode.LCK_0004.getHow(),
                ErrorCode.LCK_0004.getDocsUrl());
        }
        if (m.contains("stale") || m.contains("forge.lock")) {
            return new ForgeError(ErrorCode.LCK_0003.getCode(), m,
                ErrorCode.LCK_0003.getWhy(), ErrorCode.LCK_0003.getHow(),
                ErrorCode.LCK_0003.getDocsUrl());
        }
        return new ForgeError(ErrorCode.INT_0001.getCode(), m,
            ErrorCode.INT_0001.getWhy(), ErrorCode.INT_0001.getHow(),
            ErrorCode.INT_0001.getDocsUrl());
    }
}

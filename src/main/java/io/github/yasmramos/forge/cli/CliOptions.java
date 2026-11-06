package io.github.yasmramos.forge.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * Global CLI flags (AGENT.md sections 10 and 10bis).
 *
 * <p>Recognizes the flags that apply to every command and strips them from the
 * argument list so command handlers never see them: {@code --format=json}
 * (structured error output for CI) and {@code --debug} (append stack traces).
 */
public final class CliOptions {

    private final boolean json;
    private final boolean debug;
    private final List<String> rest;

    private CliOptions(boolean json, boolean debug, List<String> rest) {
        this.json = json;
        this.debug = debug;
        this.rest = rest;
    }

    public boolean isJson() { return json; }
    public boolean isDebug() { return debug; }

    /** Command and command-specific arguments, with global flags removed. */
    public List<String> rest() { return rest; }

    public String[] restArray() { return rest.toArray(new String[0]); }

    public static CliOptions parse(String[] args) {
        boolean json = false;
        boolean debug = false;
        List<String> rest = new ArrayList<>();
        if (args != null) {
            for (String arg : args) {
                if ("--format=json".equals(arg) || "--json".equals(arg)) {
                    json = true;
                } else if ("--debug".equals(arg)) {
                    debug = true;
                } else {
                    rest.add(arg);
                }
            }
        }
        return new CliOptions(json, debug, rest);
    }
}

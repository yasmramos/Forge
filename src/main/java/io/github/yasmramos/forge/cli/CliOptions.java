package io.github.yasmramos.forge.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * Global CLI flags (AGENT.md sections 10 and 10bis).
 *
 * <p>Recognizes the flags that apply to every command and strips them from the
 * argument list so command handlers never see them: {@code --format=json}
 * (structured error output for CI), {@code --debug} (append stack traces) and
 * {@code --variant=<name>} (explicit configuration variant selection, section
 * 8). The variant may also come from the {@code FORGE_VARIANT} environment
 * variable (section 24); the flag wins when both are present.
 */
public final class CliOptions {

    private final boolean json;
    private final boolean debug;
    private final String variant;
    private final List<String> rest;

    private CliOptions(boolean json, boolean debug, String variant, List<String> rest) {
        this.json = json;
        this.debug = debug;
        this.variant = variant;
        this.rest = rest;
    }

    public boolean isJson() { return json; }
    public boolean isDebug() { return debug; }

    /**
     * Selected variant name, or null for the base configuration (AGENT.md
     * section 8: variants are always explicit, never activated by magic).
     */
    public String getVariant() { return variant; }

    /** Command and command-specific arguments, with global flags removed. */
    public List<String> rest() { return rest; }

    public String[] restArray() { return rest.toArray(new String[0]); }

    public static CliOptions parse(String[] args) {
        return parse(args, System.getenv("FORGE_VARIANT"));
    }

    /**
     * Parses the global flags, taking the variant from {@code envVariant} when
     * no {@code --variant} flag is given. Exposed so tests can supply the
     * environment without mutating the process.
     */
    static CliOptions parse(String[] args, String envVariant) {
        boolean json = false;
        boolean debug = false;
        String variant = null;
        List<String> rest = new ArrayList<>();
        boolean passthrough = false;
        if (args != null) {
            for (String arg : args) {
                // Everything after "--" belongs to the executed program
                // (section 10/11: `forge run -- ...`), never to Forge.
                if (passthrough) {
                    rest.add(arg);
                } else if ("--".equals(arg)) {
                    passthrough = true;
                    rest.add(arg);
                } else if ("--format=json".equals(arg) || "--json".equals(arg)) {
                    json = true;
                } else if ("--debug".equals(arg)) {
                    debug = true;
                } else if (arg.startsWith("--variant=")) {
                    variant = normalize(arg.substring("--variant=".length()));
                } else {
                    rest.add(arg);
                }
            }
        }
        if (variant == null) {
            variant = normalize(envVariant);
        }
        return new CliOptions(json, debug, variant, rest);
    }

    /** Blank or absent means "no variant"; keeps the base configuration. */
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

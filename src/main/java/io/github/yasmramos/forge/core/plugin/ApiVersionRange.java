package io.github.yasmramos.forge.core.plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * A version range for the plugin/core API compatibility check (AGENT.md
 * section 4bis). The descriptor declares the core API range a plugin was built
 * against, e.g. {@code ">=0.3 <1.0"}; the core accepts the plugin only if the
 * running API version falls inside that range.
 *
 * <p>Supported syntax, kept deliberately small: whitespace- or comma-separated
 * clauses, each an optional comparator ({@code >=}, {@code >}, {@code <=},
 * {@code <}, {@code =}) followed by a version, or a bare version meaning exact
 * equality. {@code *} means "any version". This covers the contract without
 * inventing a rules language, in the spirit of Forge's narrow resolutions
 * (section 2bis).</p>
 */
public final class ApiVersionRange {

    private final List<Clause> clauses;
    private final String raw;

    private ApiVersionRange(List<Clause> clauses, String raw) {
        this.clauses = clauses;
        this.raw = raw;
    }

    public static ApiVersionRange parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty API version range");
        }
        String raw = text.trim();
        List<Clause> clauses = new ArrayList<>();
        if ("*".equals(raw)) {
            return new ApiVersionRange(clauses, raw);
        }
        for (String token : raw.split("[,\\s]+")) {
            if (token.isEmpty()) {
                continue;
            }
            String op = comparatorOf(token);
            String versionText = token.substring(op.length()).trim();
            if (versionText.isEmpty()) {
                throw new IllegalArgumentException("Missing version in range: " + raw);
            }
            clauses.add(new Clause(op, ApiVersion.parse(versionText)));
        }
        if (clauses.isEmpty()) {
            throw new IllegalArgumentException("No clauses in API version range: " + raw);
        }
        return new ApiVersionRange(clauses, raw);
    }

    private static String comparatorOf(String token) {
        if (token.startsWith(">=")) return ">=";
        if (token.startsWith("<=")) return "<=";
        if (token.startsWith("==")) return "=";
        if (token.startsWith(">"))  return ">";
        if (token.startsWith("<"))  return "<";
        if (token.startsWith("="))  return "=";
        return ""; // bare version means exact equality
    }

    /** True if the given running API version satisfies every clause. */
    public boolean accepts(ApiVersion version) {
        for (Clause c : clauses) {
            int cmp = version.compareTo(c.version);
            switch (c.op) {
                case ">=": if (cmp < 0) return false; break;
                case ">":  if (cmp <= 0) return false; break;
                case "<=": if (cmp > 0) return false; break;
                case "<":  if (cmp >= 0) return false; break;
                default:   if (cmp != 0) return false; break;
            }
        }
        return true;
    }

    public String getRaw() { return raw; }

    @Override
    public String toString() { return raw; }

    private static final class Clause {
        final String op;
        final ApiVersion version;

        Clause(String op, ApiVersion version) {
            this.op = op;
            this.version = version;
        }
    }
}

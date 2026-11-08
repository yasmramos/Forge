package io.github.yasmramos.forge.core.plugin;

/**
 * Semantic version used by the plugin API range (AGENT.md section 4bis).
 * Only the numeric core of a version is compared; pre-release and build
 * metadata are accepted and ignored for ordering, which is enough for the
 * core/plugin compatibility check the range exists to perform.
 */
public final class ApiVersion implements Comparable<ApiVersion> {

    private final int major;
    private final int minor;
    private final int patch;

    private ApiVersion(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    public static ApiVersion parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty version");
        }
        String core = text.trim();
        // Drop pre-release and build metadata (everything from the first '-' or '+').
        int cut = core.length();
        for (int i = 0; i < core.length(); i++) {
            char c = core.charAt(i);
            if (c == '-' || c == '+') {
                cut = i;
                break;
            }
        }
        core = core.substring(0, cut);
        String[] parts = core.split("\\.");
        if (parts.length < 1 || parts.length > 3) {
            throw new IllegalArgumentException("Malformed version: " + text);
        }
        try {
            int major = Integer.parseInt(parts[0].trim());
            int minor = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 0;
            return new ApiVersion(major, minor, patch);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed version: " + text, e);
        }
    }

    public int getMajor() { return major; }
    public int getMinor() { return minor; }
    public int getPatch() { return patch; }

    @Override
    public int compareTo(ApiVersion o) {
        int c = Integer.compare(major, o.major);
        if (c != 0) return c;
        c = Integer.compare(minor, o.minor);
        if (c != 0) return c;
        return Integer.compare(patch, o.patch);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ApiVersion)) return false;
        return compareTo((ApiVersion) o) == 0;
    }

    @Override
    public int hashCode() {
        return (major * 31 + minor) * 31 + patch;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}

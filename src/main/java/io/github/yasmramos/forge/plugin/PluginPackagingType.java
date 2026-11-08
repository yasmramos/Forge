package io.github.yasmramos.forge.plugin;

/**
 * A packaging type a plugin provides (AGENT.md section 4: "register packaging
 * types"). A project selects one through {@code packaging} in {@code [project]};
 * Forge then produces that artifact instead of the built-in jar.
 *
 * <p>Core never inspects the packaging type's internals: a plugin registers a
 * "war" packaging type and the build produces a war through it, with the same
 * content-based caching and dependency ordering as any other artifact.</p>
 */
public interface PluginPackagingType {

    /**
     * Identifier the project names in {@code packaging} (for example {@code
     * "war"}). Matching is case-insensitive.
     */
    String id();

    /**
     * File name of the artifact this type produces, written under the request's
     * output directory (for example {@code "forge-main.war"}). Forge declares
     * this file as the task's output so it is cached and restored.
     */
    String artifactFileName();

    /**
     * Produces the artifact. Returns success or a failure whose message answers
     * what/why/how (AGENT.md section 10).
     */
    PluginPackagingResult packageArtifact(PluginPackagingRequest request);
}

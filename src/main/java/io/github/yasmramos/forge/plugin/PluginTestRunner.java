package io.github.yasmramos.forge.plugin;

/**
 * A test runner a plugin provides for one language (AGENT.md section 4:
 * "register test runners"). Forge discovers the test sources a runner claims by
 * extension and asks it to run them as part of the build.
 *
 * <p>Core never inspects the runner's internals: a Kotlin plugin registers a
 * runner for {@code .kt} test sources and the build runs Kotlin tests through
 * it, exactly like the built-in JUnit path for Java.</p>
 */
public interface PluginTestRunner {

    /**
     * Test source file extension this runner claims, without the dot (for
     * example {@code "kt"} or {@code "scala"}). Matching is case-insensitive.
     */
    String testSourceExtension();

    /**
     * Runs the requested test sources. Returns counts and success, or a failure
     * whose message answers what/why/how (AGENT.md section 10).
     */
    PluginTestRunResult run(PluginTestRunRequest request);
}

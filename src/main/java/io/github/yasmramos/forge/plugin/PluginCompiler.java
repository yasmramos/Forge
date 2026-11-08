package io.github.yasmramos.forge.plugin;

/**
 * A compiler a plugin provides for one language (AGENT.md section 4: "register
 * compilers for languages"). Forge discovers the sources a compiler claims by
 * extension and asks it to compile them as part of the build.
 *
 * <p>Core never inspects the compiler's internals: a Kotlin plugin registers a
 * compiler for {@code .kt} and the build compiles Kotlin through it, exactly
 * like the built-in Java compiler.</p>
 */
public interface PluginCompiler {

    /**
     * Source file extension this compiler claims, without the dot (for example
     * {@code "kt"} or {@code "scala"}). Matching is case-insensitive.
     */
    String sourceExtension();

    /**
     * Compiles the requested sources. Returns success or a failure whose
     * message answers what/why/how (AGENT.md section 10).
     */
    PluginCompilationResult compile(PluginCompilationRequest request);
}

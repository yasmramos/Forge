package io.github.yasmramos.forge.core.plugin;

import io.github.yasmramos.forge.cli.error.ForgeException;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Builds throwaway plugin jars at test time, fully offline: compiles a plugin
 * source with the in-JVM compiler and packages it with its
 * {@code forge-plugin.toml}. The class is kept off the test classpath so the
 * loader's isolated classloader genuinely loads it.
 */
final class PluginJarBuilder {

    static final class Built {
        final File jar;
        final PluginDependencyResolver resolver;

        Built(File jar, PluginDependencyResolver resolver) {
            this.jar = jar;
            this.resolver = resolver;
        }
    }

    private final Path work;

    PluginJarBuilder(Path work) throws Exception {
        this.work = work;
        Files.createDirectories(work);
    }

    /** A resolver that fails if called: use when the plugin has no dependencies. */
    static PluginDependencyResolver noDependencies() {
        return coordinates -> {
            throw new AssertionError("resolver should not be called: " + coordinates);
        };
    }

    static PluginDependencyResolver stubResolver(List<Path> jars) {
        return coordinates -> jars;
    }

    /**
     * Compiles {@code source} (whose public class must be {@code className}) and
     * jars it together with {@code descriptorToml}.
     */
    Built build(String className, String source, String descriptorToml) throws Exception {
        Path srcDir = Files.createDirectories(work.resolve("src-" + className.hashCode()));
        Path classDir = Files.createDirectories(work.resolve("classes-" + className.hashCode()));
        Path srcFile = srcDir.resolve(className.substring(className.lastIndexOf('.') + 1) + ".java");
        Files.write(srcFile, source.getBytes(StandardCharsets.UTF_8));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends javax.tools.JavaFileObject> units =
                fm.getJavaFileObjects(srcFile.toFile());
            List<String> options = Arrays.asList(
                "-classpath", System.getProperty("java.class.path"),
                "-d", classDir.toString());
            Boolean ok = compiler.getTask(null, fm, null, options, null, units).call();
            if (ok == null || !ok) {
                throw new IllegalStateException("plugin compilation failed for " + className);
            }
        }

        Path jarPath = work.resolve("plugin-" + className.hashCode() + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jarPath))) {
            out.putNextEntry(new JarEntry(PluginDescriptorReader.DESCRIPTOR_FILE));
            out.write(descriptorToml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            addDirectory(out, classDir, classDir);
        }
        return new Built(jarPath.toFile(), noDependencies());
    }

    private static void addDirectory(JarOutputStream out, Path root, Path dir) throws Exception {
        List<Path> children = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            s.forEach(children::add);
        }
        Collections.sort(children);
        for (Path child : children) {
            if (Files.isDirectory(child)) {
                addDirectory(out, root, child);
            } else {
                String name = root.relativize(child).toString().replace(File.separatorChar, '/');
                out.putNextEntry(new JarEntry(name));
                out.write(Files.readAllBytes(child));
                out.closeEntry();
            }
        }
    }

    static ForgeException loadExpectingFailure(PluginLoader loader, File jar) {
        try {
            loader.load(Collections.singletonList(jar));
            throw new AssertionError("expected the load to fail");
        } catch (ForgeException e) {
            return e;
        }
    }
}

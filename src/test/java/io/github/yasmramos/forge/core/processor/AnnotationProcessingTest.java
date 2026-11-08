package io.github.yasmramos.forge.core.processor;

import io.github.yasmramos.forge.model.DependencyInfo;
import io.github.yasmramos.forge.model.DependencyResolution;
import io.github.yasmramos.forge.model.DependencyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end annotation processing (AGENT.md section 23): a processor found on
 * the compile classpath must actually run and generate sources, while
 * {@code -proc:none} must stay in effect when nothing declares a processor.
 */
class AnnotationProcessingTest {

    @TempDir
    Path tmp;

    @Test
    void processorFromClasspathGeneratesSources() throws Exception {
        Path processorClasses = compileProcessor(true);

        Path appSrc = tmp.resolve("app-src");
        Files.createDirectories(appSrc.resolve("app"));
        Files.writeString(appSrc.resolve("app/App.java"),
            "package app;\n"
            + "public class App {\n"
            + "    public static String value() { return Generated.HELLO; }\n"
            + "}\n");

        Path classes = tmp.resolve("app-classes");
        Files.createDirectories(classes);

        io.github.yasmramos.forge.core.Compiler compiler =
            new io.github.yasmramos.forge.core.Compiler();
        io.github.yasmramos.forge.model.CompilationResult result = compiler.compileBatch(
            Arrays.asList(appSrc.resolve("app/App.java")),
            resolutionWith(processorClasses),
            null,
            classes.toString());

        assertTrue(result.isSuccess(), "the processor must generate Generated before App is checked");
        assertTrue(Files.exists(classes.resolve("app/Generated.class")),
            "the generated class must be emitted into the output directory");
    }

    /**
     * With no processor declared anywhere, Forge must compile with
     * {@code -proc:none}: a classpath that merely *contains* an unrelated
     * service file must not silently trigger processing. Here the processor
     * jar is absent from the resolution, so App cannot see Generated and the
     * build must fail rather than fabricate it.
     */
    @Test
    void noProcessorDeclaredMeansNoProcessing() throws Exception {
        compileProcessor(false);

        Path appSrc = tmp.resolve("app2-src");
        Files.createDirectories(appSrc.resolve("app"));
        Files.writeString(appSrc.resolve("app/App.java"),
            "package app;\n"
            + "public class App {\n"
            + "    public static String value() { return Generated.HELLO; }\n"
            + "}\n");

        Path classes = tmp.resolve("app2-classes");
        Files.createDirectories(classes);

        io.github.yasmramos.forge.core.Compiler compiler =
            new io.github.yasmramos.forge.core.Compiler();
        io.github.yasmramos.forge.model.CompilationResult result = compiler.compileBatch(
            Arrays.asList(appSrc.resolve("app/App.java")),
            new DependencyResolution(),
            null,
            classes.toString());

        assertFalse(result.isSuccess(),
            "without a declared processor javac must not generate Generated");
        assertFalse(Files.exists(classes.resolve("app/Generated.class")));
    }

    /** Compiles a tiny processor that writes {@code app.Generated}. */
    private Path compileProcessor(boolean registerService) throws Exception {
        Path src = tmp.resolve("proc-src");
        Files.createDirectories(src.resolve("proc"));
        Files.writeString(src.resolve("proc/GenProcessor.java"),
            "package proc;\n"
            + "import java.io.Writer;\n"
            + "import java.util.Set;\n"
            + "import javax.annotation.processing.*;\n"
            + "import javax.lang.model.SourceVersion;\n"
            + "import javax.lang.model.element.TypeElement;\n"
            + "import javax.tools.JavaFileObject;\n"
            + "@SupportedAnnotationTypes(\"*\")\n"
            + "@SupportedSourceVersion(SourceVersion.RELEASE_11)\n"
            + "public class GenProcessor extends AbstractProcessor {\n"
            + "    private boolean done = false;\n"
            + "    @Override\n"
            + "    public boolean process(Set<? extends TypeElement> a, RoundEnvironment e) {\n"
            + "        if (done || e.processingOver()) return false;\n"
            + "        done = true;\n"
            + "        try {\n"
            + "            JavaFileObject f = processingEnv.getFiler()\n"
            + "                .createSourceFile(\"app.Generated\");\n"
            + "            try (Writer w = f.openWriter()) {\n"
            + "                w.write(\"package app;\\npublic final class Generated {\"\n"
            + "                    + \" public static final String HELLO = \\\"hi\\\"; }\\n\");\n"
            + "            }\n"
            + "        } catch (Exception ex) { throw new RuntimeException(ex); }\n"
            + "        return true;\n"
            + "    }\n"
            + "}\n");

        Path classes = tmp.resolve("proc-classes");
        Files.createDirectories(classes);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int exit = javac.run(null, null, null,
            "-d", classes.toString(),
            src.resolve("proc/GenProcessor.java").toString());
        assertEquals(0, exit, "the test processor itself must compile");

        Path service = classes.resolve(AnnotationProcessors.SERVICE_FILE);
        Files.createDirectories(service.getParent());
        if (registerService) {
            Files.writeString(service, "proc.GenProcessor\n");
        }
        return classes;
    }

    private static DependencyResolution resolutionWith(Path processorClasses) {
        DependencyResolution resolution = new DependencyResolution();
        resolution.addDependency(new DependencyInfo(
            "proc:gen", "1.0", DependencyType.MAVEN, processorClasses, true));
        return resolution;
    }
}

package io.github.yasmramos.forge.core.importer;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a Gradle {@code build.gradle} into a {@link BuildModel} (AGENT.md section
 * 14).
 *
 * <p>Gradle's build script is Groovy code, not data: a faithful import would mean
 * executing it. This importer deliberately does the opposite - it recognises the
 * declarative shapes that cover the vast majority of real build files (plugins,
 * coordinates, the {@code dependencies} block, source/target compatibility,
 * repositories and the application main class) and records everything else as a
 * TODO. That keeps the import safe (no code is run) and honest (gaps are
 * visible).</p>
 */
public final class GradleBuildImporter {

    private static final Pattern GROUP = Pattern.compile("^\\s*group\\s*[=(]?\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern VERSION = Pattern.compile("^\\s*version\\s*[=(]?\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern SOURCE_COMPAT = Pattern.compile(
        "sourceCompatibility\\s*[=(]?\\s*(?:JavaVersion\\.VERSION_)?['\"]?(\\d+(?:\\.\\d+)?)");
    private static final Pattern MAIN_CLASS = Pattern.compile(
        "mainClass\\s*[=(]?\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern MAVEN_CENTRAL = Pattern.compile("mavenCentral\\s*\\(");
    private static final Pattern MAVEN_URL = Pattern.compile(
        "maven\\s*\\{\\s*url\\s*[=(]?\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern DEPENDENCY = Pattern.compile(
        "\\b(implementation|api|compileOnly|runtimeOnly|testImplementation|testCompileOnly|"
            + "testRuntimeOnly|compile|testCompile|providedCompile|runtime)\\b"
            + "\\s*\\(?\\s*['\"]([^'\"]+)['\"]");

    /** Gradle configuration -> Forge dependency scope. */
    private static String forgeScope(String configuration) {
        switch (configuration) {
            case "testImplementation":
            case "testCompile":
            case "testCompileOnly":
            case "testRuntimeOnly":
                return "test";
            case "runtimeOnly":
            case "runtime":
                return "runtime";
            case "compileOnly":
            case "providedCompile":
                return "provided";
            default:
                return "compile";
        }
    }

    private GradleBuildImporter() {
    }

    /**
     * Parses a {@code build.gradle}.
     *
     * @param buildFile the build.gradle file
     * @return the neutral build model
     * @throws ForgeException {@code FORGE-IMP-0002} when the file cannot be read
     */
    public static BuildModel importBuild(File buildFile) {
        List<String> lines;
        try {
            lines = Files.readAllLines(buildFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ForgeException(ErrorCode.IMP_0002,
                "could not read " + buildFile.getName() + ": " + e.getMessage(), e);
        }

        BuildModel model = new BuildModel();
        model.setName(directoryName(buildFile));
        boolean sawJavaPlugin = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("*")) {
                continue;
            }
            if (trimmed.contains("id") && trimmed.contains("java")) {
                sawJavaPlugin = true;
            }
            matchInto(GROUP, line, model::setGroup);
            matchInto(VERSION, line, model::setVersion);
            matchInto(SOURCE_COMPAT, line, model::setLanguage);
            matchInto(MAIN_CLASS, line, model::setMainClass);

            if (MAVEN_CENTRAL.matcher(line).find()) {
                model.getRepositories().put("central", "https://repo1.maven.org/maven2");
            }
            Matcher url = MAVEN_URL.matcher(line);
            if (url.find()) {
                model.getRepositories().put(repoId(url.group(1)), url.group(1));
            }

            Matcher dep = DEPENDENCY.matcher(line);
            while (dep.find()) {
                addNotation(model, dep.group(2), forgeScope(dep.group(1)));
            }

            if (looksLikeCustomLogic(trimmed)) {
                model.addTodo("Gradle build logic is not translated automatically: "
                    + abbreviate(trimmed));
            }
        }

        if (!sawJavaPlugin) {
            model.addTodo("No 'java' plugin detected; confirm [source.main] and packaging "
                + "match this project.");
        }
        model.getMainJavaSources().add("src/main/java");
        model.getTestJavaSources().add("src/test/java");
        return model;
    }

    /**
     * Splits a Gradle dependency notation. Both {@code "g:a:v"} and
     * {@code "g:a"} are accepted; the short form has no version and becomes a
     * TODO, because inventing one would produce a file that does not build.
     */
    private static void addNotation(BuildModel model, String notation, String scope) {
        String[] parts = notation.split(":");
        if (parts.length >= 3) {
            model.addDependency(parts[0] + ":" + parts[1], parts[2], scope);
        } else if (parts.length == 2) {
            model.addDependency(notation, null, scope);
            model.addTodo("Dependency '" + notation + "' has no version in build.gradle"
                + " (likely from a platform/BOM); pin it or import the BOM (AGENT.md section 9).");
        } else {
            model.addTodo("Unrecognized dependency notation '" + notation + "'.");
        }
    }

    /**
     * Flags lines that carry arbitrary build logic (closures, task bodies, plugin
     * configuration). They are the parts an import cannot safely translate.
     */
    private static boolean looksLikeCustomLogic(String line) {
        return line.startsWith("task ")
            || line.contains("<<")
            || line.contains("doLast")
            || line.startsWith("processResources")
            || line.contains("manifest {")
            || line.contains("jar {")
            || line.contains("publishing {")
            || line.contains("sourceSets {");
    }

    private static void matchInto(Pattern pattern, String line, java.util.function.Consumer<String> setter) {
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            setter.accept(matcher.group(1));
        }
    }

    private static String repoId(String url) {
        if (url.contains("repo1.maven.org") || url.contains("repo.maven.apache.org")) {
            return "central";
        }
        // A stable, TOML-safe key derived from the host.
        String host = url.replaceAll("^[a-zA-Z]+://", "").replaceAll("[/:].*$", "");
        return host.isEmpty() ? "repo" : host.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String directoryName(File buildFile) {
        File parent = buildFile.getAbsoluteFile().getParentFile();
        return parent != null ? parent.getName() : "imported";
    }

    private static String abbreviate(String line) {
        return line.length() <= 60 ? line : line.substring(0, 57) + "...";
    }
}

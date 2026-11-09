package io.github.yasmramos.forge.core.ide;

import java.util.List;

/**
 * Neutral description of a project as the IDE model writers need it (AGENT.md
 * section 13, phase 1).
 *
 * <p>It deliberately carries no IDE-specific structure: source and resource
 * roots are project-relative (portable, so the generated files never embed a
 * machine path), while classpath libraries are absolute because IDEs reference
 * jars by location. {@link IdeGenerators} turns this into IntelliJ, Eclipse and
 * VS Code models.</p>
 */
public final class ProjectModel {

    private final String name;
    private final String languageLevel;
    private final List<String> mainSourceRoots;
    private final List<String> testSourceRoots;
    private final List<String> mainResourceRoots;
    private final List<String> testResourceRoots;
    private final String classesDir;
    private final String testClassesDir;
    private final List<String> compileLibraries;
    private final List<String> testLibraries;

    public ProjectModel(String name, String languageLevel,
                        List<String> mainSourceRoots, List<String> testSourceRoots,
                        List<String> mainResourceRoots, List<String> testResourceRoots,
                        String classesDir, String testClassesDir,
                        List<String> compileLibraries, List<String> testLibraries) {
        this.name = name;
        this.languageLevel = languageLevel;
        this.mainSourceRoots = mainSourceRoots;
        this.testSourceRoots = testSourceRoots;
        this.mainResourceRoots = mainResourceRoots;
        this.testResourceRoots = testResourceRoots;
        this.classesDir = classesDir;
        this.testClassesDir = testClassesDir;
        this.compileLibraries = compileLibraries;
        this.testLibraries = testLibraries;
    }

    /** Artifact id, also the IntelliJ {@code .iml} base name. */
    public String getName() {
        return name;
    }

    /** Java language level, e.g. {@code 11} or {@code 17}. */
    public String getLanguageLevel() {
        return languageLevel;
    }

    public List<String> getMainSourceRoots() {
        return mainSourceRoots;
    }

    public List<String> getTestSourceRoots() {
        return testSourceRoots;
    }

    public List<String> getMainResourceRoots() {
        return mainResourceRoots;
    }

    public List<String> getTestResourceRoots() {
        return testResourceRoots;
    }

    /** Compiled main classes directory, project-relative. */
    public String getClassesDir() {
        return classesDir;
    }

    /** Compiled test classes directory, project-relative. */
    public String getTestClassesDir() {
        return testClassesDir;
    }

    /** Absolute paths of compile-scope libraries. */
    public List<String> getCompileLibraries() {
        return compileLibraries;
    }

    /** Absolute paths of test-scope libraries. */
    public List<String> getTestLibraries() {
        return testLibraries;
    }
}

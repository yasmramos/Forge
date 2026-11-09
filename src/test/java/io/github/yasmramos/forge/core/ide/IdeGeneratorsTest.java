package io.github.yasmramos.forge.core.ide;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the IDE model writers (AGENT.md section 13, phase 1). They are pure
 * functions of a {@link ProjectModel}, so the assertions check the emitted text
 * directly - no filesystem, no IDE.
 */
class IdeGeneratorsTest {

    private static ProjectModel model() {
        return new ProjectModel("awesome", "17",
            Collections.singletonList("src/main/java"),
            Collections.singletonList("src/test/java"),
            Collections.singletonList("src/main/resources"),
            Collections.singletonList("src/test/resources"),
            "target/classes", "target/test-classes",
            Arrays.asList("/home/u/.forge/cache/deps/a.jar", "/home/u/.forge/cache/deps/b.jar"),
            Collections.singletonList("/home/u/.forge/cache/deps/junit.jar"));
    }

    private static String content(List<IdeGenerators.GeneratedFile> files, String path) {
        return files.stream().filter(f -> f.getPath().equals(path)).findFirst()
            .orElseThrow(() -> new AssertionError("missing " + path)).getContent();
    }

    @Test
    void intellijEmitsModulesMiscCompilerAndIml() {
        List<IdeGenerators.GeneratedFile> files = IdeGenerators.intellij(model());
        assertEquals(4, files.size());
        assertTrue(content(files, ".idea/modules.xml").contains("awesome.iml"));
        assertTrue(content(files, ".idea/misc.xml").contains("languageLevel=\"JDK_17\""));
        assertTrue(content(files, "awesome.iml").contains("isTestSource=\"false\""));
        assertTrue(content(files, "awesome.iml").contains("isTestSource=\"true\""));
    }

    @Test
    void intellijMarksTestLibrariesAsTestScope() {
        String iml = content(IdeGenerators.intellij(model()), "awesome.iml");
        assertTrue(iml.contains("type=\"module-library\" scope=\"TEST\""));
        assertTrue(iml.contains("junit.jar"));
        assertTrue(iml.contains("type=\"java-resource\""));
        assertTrue(iml.contains("type=\"java-test-resource\""));
    }

    @Test
    void eclipseEmitsProjectClasspathAndPrefs() {
        List<IdeGenerators.GeneratedFile> files = IdeGenerators.eclipse(model());
        assertEquals(3, files.size());
        assertTrue(content(files, ".project").contains("<name>awesome</name>"));
        String classpath = content(files, ".classpath");
        assertTrue(classpath.contains("kind=\"src\" path=\"src/main/java\""));
        assertTrue(classpath.contains("kind=\"lib\" path=\"/home/u/.forge/cache/deps/a.jar\""));
        assertTrue(classpath.contains("kind=\"output\" path=\"target/classes\""));
        assertTrue(content(files, ".settings/org.eclipse.jdt.core.prefs")
            .contains("compliance=17"));
    }

    @Test
    void vscodeEmitsSourcePathsOutputAndLibraries() {
        List<IdeGenerators.GeneratedFile> files = IdeGenerators.vscode(model());
        assertEquals(1, files.size());
        String settings = content(files, ".vscode/settings.json");
        assertTrue(settings.contains("\"java.project.sourcePaths\": [\"src/main/java\", \"src/test/java\"]"));
        assertTrue(settings.contains("\"java.project.outputPath\": \"target/classes\""));
        assertTrue(settings.contains("junit.jar"));
    }

    @Test
    void escapesXmlAndJson() {
        ProjectModel tricky = new ProjectModel("a&b<c>\"d'", "11",
            Collections.singletonList("src/main/java"),
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            "target/classes", "target/test-classes", Collections.emptyList(),
            Collections.emptyList());
        List<IdeGenerators.GeneratedFile> files = IdeGenerators.intellij(tricky);
        String modules = content(files, ".idea/modules.xml");
        assertTrue(modules.contains("a&amp;b&lt;c&gt;&quot;d&apos;.iml"));
        assertFalse(modules.contains("\"a&b<c>"));

        String json = content(IdeGenerators.vscode(tricky), ".vscode/settings.json");
        assertTrue(json.contains("target/classes"));
    }
}

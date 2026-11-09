package io.github.yasmramos.forge.core.ide;

import io.github.yasmramos.forge.model.ProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link IdeProjectBuilder} (AGENT.md section 13, phase 1). A project
 * with no dependencies resolves to an empty classpath without touching the
 * network, so the model-shape assertions run fully offline.
 */
class IdeProjectBuilderTest {

    @Test
    void derivesRootsAndLanguageLevelFromConfig(@TempDir Path tmp) {
        ProjectConfig config = new ProjectConfig();
        config.setName("awesome");
        config.setSourceCompatibility("17");
        // An absolute output dir under the project root, as a resolved config
        // carries it; a bare "target/classes" would resolve against the JVM cwd.
        config.setOutputDirectory(new File(tmp.toFile(), "target/classes").getAbsolutePath());

        ProjectModel model = IdeProjectBuilder.build(config, tmp.toFile());

        assertEquals("awesome", model.getName());
        assertEquals("17", model.getLanguageLevel());
        assertEquals(Collections.singletonList("src/main/java"), model.getMainSourceRoots());
        assertEquals(Collections.singletonList("src/test/java"), model.getTestSourceRoots());
        assertEquals("target/classes", model.getClassesDir());
        assertEquals("target/test-classes", model.getTestClassesDir());
        assertTrue(model.getCompileLibraries().isEmpty());
        assertTrue(model.getTestLibraries().isEmpty());
    }

    @Test
    void honoursDeclaredSourceAndResourceRoots(@TempDir Path tmp) {
        ProjectConfig config = new ProjectConfig();
        config.setName("custom");
        config.getMainJavaSources().add("app/main");
        config.getTestJavaSources().add("app/test");
        config.getMainResourcePaths().add("app/resources");

        ProjectModel model = IdeProjectBuilder.build(config, tmp.toFile());

        assertEquals(Collections.singletonList("app/main"), model.getMainSourceRoots());
        assertEquals(Collections.singletonList("app/test"), model.getTestSourceRoots());
        assertEquals(Collections.singletonList("app/resources"), model.getMainResourceRoots());
    }

    @Test
    void defaultsNameAndLevelWhenUnset(@TempDir Path tmp) {
        ProjectConfig config = new ProjectConfig();
        config.setOutputDirectory("target/classes");

        ProjectModel model = IdeProjectBuilder.build(config, tmp.toFile());

        assertEquals("project", model.getName());
        assertEquals("11", model.getLanguageLevel());
    }

    @Test
    void relativisesAbsoluteSourceRoots(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        ProjectConfig config = new ProjectConfig();
        config.setName("rel");
        config.getMainJavaSources().add(new File(root, "src/main/java").getAbsolutePath());
        config.setOutputDirectory("target/classes");

        ProjectModel model = IdeProjectBuilder.build(config, root);

        assertEquals(Collections.singletonList("src/main/java"), model.getMainSourceRoots());
    }
}

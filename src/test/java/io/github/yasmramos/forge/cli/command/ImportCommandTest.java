package io.github.yasmramos.forge.cli.command;

import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.importer.ForgeTomlWriter;
import io.github.yasmramos.forge.core.importer.GradleBuildImporter;
import io.github.yasmramos.forge.core.importer.MavenPomImporter;
import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.utils.ConfigReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@code forge import} (AGENT.md sections 10 and 14). */
class ImportCommandTest {

    private static String run(File root, String... args) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImportCommand.run(root, args, new PrintStream(buffer, true, StandardCharsets.UTF_8));
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static void write(File file, String content) throws Exception {
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static final String POM =
        "<?xml version=\"1.0\"?>\n"
            + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
            + "  <artifactId>acme-core</artifactId>\n"
            + "  <groupId>com.acme</groupId>\n"
            + "  <version>3.2.1</version>\n"
            + "  <description>Core</description>\n"
            + "  <properties><maven.compiler.source>11</maven.compiler.source>"
            + "<slf4j.version>2.0.7</slf4j.version></properties>\n"
            + "  <dependencies>\n"
            + "    <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>"
            + "<version>${slf4j.version}</version></dependency>\n"
            + "    <dependency><groupId>org.junit.jupiter</groupId>"
            + "<artifactId>junit-jupiter</artifactId><version>5.9.2</version>"
            + "<scope>test</scope></dependency>\n"
            + "  </dependencies>\n"
            + "</project>\n";

    @Test
    void importsMavenPomIntoForgeToml(@TempDir Path tmp) throws Exception {
        write(new File(tmp.toFile(), "pom.xml"), POM);

        String output = run(tmp.toFile());

        File generated = new File(tmp.toFile(), "forge.toml");
        assertTrue(generated.isFile(), "forge.toml must be written");
        assertTrue(output.contains("acme-core"), output);

        // The generated file must parse as a real Forge configuration.
        ProjectConfig config = ConfigReader.loadConfig(generated);
        assertEquals("acme-core", config.getName());
        assertEquals("3.2.1", config.getVersion());
        assertEquals("com.acme", config.getGroup());
        assertEquals("11", config.getSourceCompatibility());
        assertTrue(config.getDependencies().containsKey("org.slf4j:slf4j-api"));
        assertTrue(config.getDependencies().containsKey("org.junit.jupiter:junit-jupiter"));
    }

    @Test
    void dryRunPrintsWithoutWriting(@TempDir Path tmp) throws Exception {
        write(new File(tmp.toFile(), "pom.xml"), POM);

        String output = run(tmp.toFile(), "--dry-run");

        assertFalse(new File(tmp.toFile(), "forge.toml").exists(),
            "dry run must not write forge.toml");
        assertTrue(output.contains("[project]"), output);
        assertTrue(output.contains("name    = \"acme-core\""), output);
    }

    @Test
    void refusesToOverwriteExistingConfig(@TempDir Path tmp) throws Exception {
        write(new File(tmp.toFile(), "pom.xml"), POM);
        write(new File(tmp.toFile(), "forge.toml"), "forge = \"0.1\"\n");

        ForgeException ex = assertThrows(ForgeException.class, () -> run(tmp.toFile()));
        assertEquals("FORGE-IMP-0003", ex.getCode());
    }

    @Test
    void failsWhenNothingToImport(@TempDir Path tmp) {
        ForgeException ex = assertThrows(ForgeException.class, () -> run(tmp.toFile()));
        assertEquals("FORGE-IMP-0001", ex.getCode());
    }

    @Test
    void malformedPomFailsWithStructuredError(@TempDir Path tmp) throws Exception {
        write(new File(tmp.toFile(), "pom.xml"), "<project><unclosed></project>");

        ForgeException ex = assertThrows(ForgeException.class, () -> run(tmp.toFile()));
        assertEquals("FORGE-IMP-0002", ex.getCode());
    }

    @Test
    void recordsTodosForUntranslatableMavenParts(@TempDir Path tmp) throws Exception {
        write(new File(tmp.toFile(), "pom.xml"),
            "<?xml version=\"1.0\"?>\n<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <artifactId>x</artifactId><groupId>g</groupId><version>1</version>\n"
                + "  <build><plugins><plugin><artifactId>maven-shade-plugin</artifactId>"
                + "</plugin></plugins></build>\n"
                + "</project>\n");

        String output = run(tmp.toFile(), "--dry-run");

        assertTrue(output.contains("TODO"), output);
        assertTrue(output.contains("plugins"), output);
    }

    @Test
    void importsGradleBuildIntoForgeToml(@TempDir Path tmp) throws Exception {
        write(new File(tmp.toFile(), "build.gradle"),
            "plugins { id 'java' }\n"
                + "group = 'com.example.gradle'\n"
                + "version = '2.4.0'\n"
                + "sourceCompatibility = JavaVersion.VERSION_17\n"
                + "repositories { mavenCentral() }\n"
                + "dependencies {\n"
                + "  implementation 'com.google.guava:guava:32.1.3-jre'\n"
                + "  testImplementation 'org.junit.jupiter:junit-jupiter:5.9.2'\n"
                + "}\n"
                + "application { mainClass = 'com.example.Main' }\n");

        run(tmp.toFile());

        ProjectConfig config = ConfigReader.loadConfig(new File(tmp.toFile(), "forge.toml"));
        assertEquals("com.example.gradle", config.getGroup());
        assertEquals("2.4.0", config.getVersion());
        assertEquals("17", config.getSourceCompatibility());
        assertTrue(config.getDependencies().containsKey("com.google.guava:guava"));
        assertTrue(config.getDependencies().containsKey("org.junit.jupiter:junit-jupiter"));
    }

    @Test
    void gradleDependencyWithoutVersionIsCommentedNotInvented(@TempDir Path tmp) throws Exception {
        write(new File(tmp.toFile(), "build.gradle"),
            "plugins { id 'java' }\n"
                + "repositories { mavenCentral() }\n"
                + "dependencies { implementation 'com.google.guava:guava' }\n");

        String output = run(tmp.toFile(), "--dry-run");

        assertTrue(output.contains("# \"com.google.guava:guava\""), output);
        assertFalse(output.contains("guava\" = \"${"), "no version must be invented");
    }

    @Test
    void repositoryKeysAreQuotedSoDotsDoNotBreakTheTable(@TempDir Path tmp) throws Exception {
        write(new File(tmp.toFile(), "build.gradle"),
            "plugins { id 'java' }\n"
                + "repositories { maven { url 'https://jitpack.io' } }\n");

        run(tmp.toFile());

        ProjectConfig config = ConfigReader.loadConfig(new File(tmp.toFile(), "forge.toml"));
        assertTrue(config.getRepositories().containsKey("jitpack.io"),
            "a dotted repository key must survive: " + config.getRepositories());
    }

    @Test
    void mavenBomImportBecomesATodo(@TempDir Path tmp) throws Exception {
        write(new File(tmp.toFile(), "pom.xml"),
            "<?xml version=\"1.0\"?>\n<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <artifactId>x</artifactId><groupId>g</groupId><version>1</version>\n"
                + "  <dependencyManagement><dependencies>\n"
                + "    <dependency><groupId>org.junit</groupId><artifactId>junit-bom</artifactId>"
                + "<version>5.9.2</version><type>pom</type><scope>import</scope></dependency>\n"
                + "  </dependencies></dependencyManagement>\n"
                + "</project>\n");

        String output = run(tmp.toFile(), "--dry-run");

        assertTrue(output.contains("junit-bom"), output);
        assertTrue(output.contains("BOM"), output);
    }

    @Test
    void writerKeepsPropertiesReferencedByDependencies(@TempDir Path tmp) throws Exception {
        write(new File(tmp.toFile(), "pom.xml"), POM);

        run(tmp.toFile());
        String generated = new String(
            Files.readAllBytes(new File(tmp.toFile(), "forge.toml").toPath()),
            StandardCharsets.UTF_8);

        // The ${slf4j.version} reference must be resolvable from the emitted
        // [project.properties], or the file would not build.
        assertTrue(generated.contains("\"slf4j.version\" = \"2.0.7\""), generated);
        assertTrue(generated.contains("\"org.slf4j:slf4j-api\" = \"${slf4j.version}\""), generated);
    }

    @Test
    void outputFlagWritesToCustomPath(@TempDir Path tmp) throws Exception {
        write(new File(tmp.toFile(), "pom.xml"), POM);

        run(tmp.toFile(), "--output=custom.toml");

        assertTrue(new File(tmp.toFile(), "custom.toml").isFile());
        assertFalse(new File(tmp.toFile(), "forge.toml").exists());
    }

    @Test
    void writerProducesParseableModelForBothImporters(@TempDir Path tmp) throws Exception {
        // Round-trip guard: whatever the importers produce, the writer's output
        // must always be valid TOML that ConfigReader accepts.
        File gradleDir = Files.createDirectories(tmp.resolve("gradle")).toFile();
        write(new File(gradleDir, "build.gradle"),
            "plugins { id 'java' }\ngroup = 'g'\nversion = '1'\n"
                + "repositories { mavenCentral() }\n"
                + "dependencies { implementation 'a:b:1' }\n");
        write(new File(gradleDir, "forge.toml"),
            ForgeTomlWriter.write(GradleBuildImporter.importBuild(new File(gradleDir, "build.gradle"))));
        assertEquals("g", ConfigReader.loadConfig(new File(gradleDir, "forge.toml")).getGroup());

        File mavenDir = Files.createDirectories(tmp.resolve("maven")).toFile();
        write(new File(mavenDir, "pom.xml"),
            "<?xml version=\"1.0\"?><project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + "<artifactId>a</artifactId><groupId>g</groupId><version>1</version></project>");
        write(new File(mavenDir, "forge.toml"),
            ForgeTomlWriter.write(MavenPomImporter.importPom(new File(mavenDir, "pom.xml"))));
        assertEquals("a", ConfigReader.loadConfig(new File(mavenDir, "forge.toml")).getName());
    }
}

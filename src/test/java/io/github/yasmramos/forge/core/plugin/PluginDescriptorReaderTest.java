package io.github.yasmramos.forge.core.plugin;

import io.github.yasmramos.forge.cli.error.ForgeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the forge-plugin.toml contract (AGENT.md section 4bis). */
class PluginDescriptorReaderTest {

    private File write(File dir, String toml) throws Exception {
        File f = new File(dir, "forge-plugin.toml");
        Files.write(f.toPath(), toml.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static final String VALID =
        "schema = \"0.1\"\n"
        + "[plugin]\n"
        + "id      = \"io.example.forge.kotlin\"\n"
        + "name    = \"Kotlin support\"\n"
        + "version = \"1.2.0\"\n"
        + "api     = \">=0.1 <1.0\"\n"
        + "main    = \"io.example.forge.kotlin.KotlinPlugin\"\n"
        + "[dependencies]\n"
        + "\"org.jetbrains.kotlin:kotlin-compiler-embeddable\" = \"1.9.22\"\n"
        + "[[provides]]\n"
        + "type = \"task-type\"\n"
        + "name = \"kotlinCompile\"\n"
        + "[compatibility]\n"
        + "reproducible = true\n";

    @Test
    void parsesAValidDescriptor(@TempDir File dir) throws Exception {
        PluginDescriptor d = PluginDescriptorReader.read(write(dir, VALID));
        assertEquals("0.1", d.getSchema());
        assertEquals("io.example.forge.kotlin", d.getId());
        assertEquals("Kotlin support", d.getName());
        assertEquals("1.2.0", d.getVersion());
        assertEquals(">=0.1 <1.0", d.getApi());
        assertEquals("io.example.forge.kotlin.KotlinPlugin", d.getMain());
        assertEquals("1.9.22", d.getDependencies().get(
            "org.jetbrains.kotlin:kotlin-compiler-embeddable"));
        assertEquals(1, d.getProvides().size());
        assertEquals("task-type", d.getProvides().get(0).getType());
        assertEquals("kotlinCompile", d.getProvides().get(0).getName());
        assertEquals(Boolean.TRUE, d.getReproducible());
        assertEquals("io.example.forge.kotlin:1.2.0", d.idVersion());
    }

    @Test
    void missingRequiredFieldFailsWithPlg0003(@TempDir File dir) throws Exception {
        String noMain = VALID.replace("main    = \"io.example.forge.kotlin.KotlinPlugin\"\n", "");
        ForgeException e = assertThrows(ForgeException.class,
            () -> PluginDescriptorReader.read(write(dir, noMain)));
        assertEquals("FORGE-PLG-0003", e.getCode());
        assertTrue(e.getMessage().contains("plugin.main"), e.getMessage());
    }

    @Test
    void unknownSchemaFailsWithPlg0002(@TempDir File dir) throws Exception {
        ForgeException e = assertThrows(ForgeException.class,
            () -> PluginDescriptorReader.read(write(dir, VALID.replace("schema = \"0.1\"", "schema = \"9.9\""))));
        assertEquals("FORGE-PLG-0002", e.getCode());
    }

    @Test
    void invalidApiRangeFailsWithPlg0003(@TempDir File dir) throws Exception {
        ForgeException e = assertThrows(ForgeException.class,
            () -> PluginDescriptorReader.read(write(dir, VALID.replace(">=0.1 <1.0", ">="))));
        assertEquals("FORGE-PLG-0003", e.getCode());
        assertTrue(e.getMessage().contains("plugin.api"), e.getMessage());
    }

    @Test
    void incompatibleApiRangeFailsWithPlg0001(@TempDir File dir) throws Exception {
        // Built against a future core API: must not load, and must say both versions.
        PluginDescriptor d = PluginDescriptorReader.read(
            write(dir, VALID.replace(">=0.1 <1.0", ">=2.0 <3.0")));
        ForgeException e = assertThrows(ForgeException.class,
            () -> PluginDescriptorReader.checkCompatibility(d));
        assertEquals("FORGE-PLG-0001", e.getCode());
        assertTrue(e.getMessage().contains("2.0"), e.getMessage());
        assertTrue(e.getMessage().contains(PluginApi.CURRENT), e.getMessage());
    }

    @Test
    void compatibleRangePasses(@TempDir File dir) throws Exception {
        PluginDescriptor d = PluginDescriptorReader.read(write(dir, VALID));
        assertDoesNotThrow(() -> PluginDescriptorReader.checkCompatibility(d));
    }

    @Test
    void malformedTomlFailsWithPlg0003(@TempDir File dir) throws Exception {
        ForgeException e = assertThrows(ForgeException.class,
            () -> PluginDescriptorReader.read(write(dir, "schema = ")));
        assertEquals("FORGE-PLG-0003", e.getCode());
    }

    @Test
    void badDependencyTypeFailsWithPlg0003(@TempDir File dir) throws Exception {
        String bad = VALID.replace(
            "\"org.jetbrains.kotlin:kotlin-compiler-embeddable\" = \"1.9.22\"",
            "\"org.jetbrains.kotlin:kotlin-compiler-embeddable\" = 1");
        ForgeException e = assertThrows(ForgeException.class,
            () -> PluginDescriptorReader.read(write(dir, bad)));
        assertEquals("FORGE-PLG-0003", e.getCode());
    }
}

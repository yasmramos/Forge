package io.github.yasmramos.forge.core.plugin;

import io.github.yasmramos.forge.cli.error.ForgeException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for plugin identity and provides/runtime consistency (AGENT.md section 4bis). */
class PluginCatalogTest {

    private static PluginDescriptor descriptor(String id, String version,
                                              PluginDescriptor.Provides... provides) {
        return new PluginDescriptor("0.1", id, id, version, ">=0.1 <1.0",
            id + ".Plugin", Collections.emptyMap(), Arrays.asList(provides), true);
    }

    @Test
    void duplicatePluginIdFailsWithPlg0004() {
        PluginCatalog catalog = new PluginCatalog()
            .register(descriptor("io.example.a", "1.0.0"));
        ForgeException e = assertThrows(ForgeException.class,
            () -> catalog.register(descriptor("io.example.a", "2.0.0")));
        assertEquals("FORGE-PLG-0004", e.getCode());
        assertTrue(e.getMessage().contains("io.example.a"), e.getMessage());
    }

    @Test
    void idVersionsAreSortedForStableCacheKeys() {
        PluginCatalog catalog = new PluginCatalog()
            .register(descriptor("io.example.z", "1.0.0"))
            .register(descriptor("io.example.a", "2.3.1"));
        assertEquals(Arrays.asList("io.example.a:2.3.1", "io.example.z:1.0.0"),
            catalog.idVersions());
    }

    @Test
    void providesMatchingRuntimeIsAccepted() {
        PluginDescriptor d = descriptor("io.example.k", "1.0.0",
            new PluginDescriptor.Provides("task-type", "kotlinCompile"));
        assertDoesNotThrow(() -> PluginCatalog.verifyProvides(d,
            Collections.singletonList("task-type:kotlinCompile")));
    }

    @Test
    void providesDriftFailsWithPlg0005() {
        PluginDescriptor d = descriptor("io.example.k", "1.0.0",
            new PluginDescriptor.Provides("task-type", "kotlinCompile"));
        // Declares kotlinCompile but registers kotlinTest: drift must not be silent.
        ForgeException e = assertThrows(ForgeException.class,
            () -> PluginCatalog.verifyProvides(d, Collections.singletonList("task-type:kotlinTest")));
        assertEquals("FORGE-PLG-0005", e.getCode());
        assertTrue(e.getMessage().contains("kotlinCompile"), e.getMessage());
        assertTrue(e.getMessage().contains("kotlinTest"), e.getMessage());
    }

    @Test
    void providesDriftAlsoFailsWhenRuntimeRegistersNothing() {
        PluginDescriptor d = descriptor("io.example.k", "1.0.0",
            new PluginDescriptor.Provides("task-type", "kotlinCompile"));
        assertEquals("FORGE-PLG-0005", assertThrows(ForgeException.class,
            () -> PluginCatalog.verifyProvides(d, Collections.emptyList())).getCode());
    }
}

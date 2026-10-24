package io.github.yasmramos.forge.cli.error;

import com.electronwill.nightconfig.core.Config;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** CI guard for the error catalog (AGENT.md section 10bis). */
class ErrorCatalogTest {

    @Test
    void everyRegisteredCodeHasFullFields() {
        for (ErrorCode.Entry e : ErrorCode.catalog().values()) {
            assertNotNull(e.getWhat());
            assertNotNull(e.getWhy());
            assertNotNull(e.getHow());
            assertTrue(e.getDocsUrl().startsWith("https://forge.dev/e/"), e.getCode());
            assertTrue(e.getCode().matches("FORGE-[A-Z]{3}-\\d{4}"), e.getCode());
        }
    }

    @Test
    void javaRegistryAndTomlCatalogStayInSync() throws Exception {
        Map<String, Config> toml = ErrorCode.loadCatalogResource();
        assertEquals(ErrorCode.catalog().keySet(), toml.keySet(),
            "docs/errors.toml and ErrorCode.java must list the same codes");
        for (Map.Entry<String, ErrorCode.Entry> e : ErrorCode.catalog().entrySet()) {
            Config c = toml.get(e.getKey());
            assertEquals(e.getValue().getWhat(), c.get("what"));
            assertEquals(e.getValue().getWhy(), c.get("why"));
            assertEquals(e.getValue().getHow(), c.get("how"));
            assertEquals(e.getValue().getDocsUrl(), c.get("docs"));
        }
    }

    @Test
    void unregisteredCodeFailsFast() {
        ForgeError adHoc = new ForgeError("FORGE-XXX-9999", "w", "y", "h", "d");
        assertFalse(ErrorCode.isRegistered(adHoc.getCode()));
    }

    @Test
    void exceptionCarriesStructuredErrorAndTaskChain() {
        ForgeException ex = new ForgeException(ErrorCode.TSK_0001, "a -> b -> a")
            .at("package", "package");
        ex.propagateThrough("compile", "javaCompile");
        assertEquals("FORGE-TSK-0001", ex.getCode());
        assertEquals(2, ex.getTaskTrace().size());
        String chain = ex.formatTaskChain();
        assertTrue(chain.contains("compile"), chain);
        assertTrue(chain.contains("<- package"), chain);
        // Human rendering answers what/why/how/docs (section 10 style rule).
        String human = ex.formatUserFacing();
        assertTrue(human.contains("error[FORGE-TSK-0001]"));
        assertTrue(human.contains("fix :"));
        assertTrue(human.contains("docs: https://forge.dev/e/TSK-0001"));
        // JSON rendering is keyed on code (CI mode).
        String json = ex.formatJson();
        assertTrue(json.startsWith("{\"code\":\"FORGE-TSK-0001\""), json);
        assertTrue(json.contains("\"task_chain\":["));
    }

    @Test
    void legacyAdapterMapsToRegisteredCodes() {
        assertEquals("FORGE-TSK-0001",
            ForgeError.of(new IllegalStateException("Dependency cycle detected: a -> a")).getCode());
        assertEquals("FORGE-LCK-0003",
            ForgeError.of(new IllegalStateException("forge.lock is stale")).getCode());
        assertEquals("FORGE-INT-0001",
            ForgeError.of(new RuntimeException("boom")).getCode());
    }
}

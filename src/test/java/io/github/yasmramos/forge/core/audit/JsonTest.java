package io.github.yasmramos.forge.core.audit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the minimal JSON reader used by the audit client (AGENT.md section 18).
 * The parser is hand-written (Forge ships no JSON library), so its behaviour on
 * well-formed and malformed input is verified directly.
 */
class JsonTest {

    @Test
    @SuppressWarnings("unchecked")
    void parsesObjectsArraysAndScalars() {
        Object parsed = Json.parse(
            "{\"a\":[1,2.5,true,false,null],\"b\":\"x\\ny\",\"c\":{}}");
        Map<String, Object> root = (Map<String, Object>) parsed;
        List<Object> a = (List<Object>) root.get("a");
        assertEquals(1L, a.get(0));
        assertEquals(2.5, a.get(1));
        assertEquals(Boolean.TRUE, a.get(2));
        assertEquals(Boolean.FALSE, a.get(3));
        assertNull(a.get(4));
        assertEquals("x\ny", root.get("b"));
        assertTrue(((Map<?, ?>) root.get("c")).isEmpty());
    }

    @Test
    void decodesUnicodeEscapes() {
        assertEquals("café", Json.parse("\"caf\\u00e9\""));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(Json.JsonException.class, () -> Json.parse("{}trailing"));
        assertThrows(Json.JsonException.class, () -> Json.parse("[1,2"));
        assertThrows(Json.JsonException.class, () -> Json.parse("\"unterminated"));
        assertThrows(Json.JsonException.class, () -> Json.parse("nope"));
    }
}
